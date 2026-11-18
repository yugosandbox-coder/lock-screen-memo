package io.github.yugosandboxcoder.lockmemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * GitHub のリリースを見て、新しい版があればダウンロードして更新する。
 *
 * ・通信先は GitHub（api.github.com / github.com のリリース）だけ。メモの内容などは一切送らない。
 * ・更新ファイルは、いま入っているアプリと同じ署名鍵で作られたものでなければ Android が拒否する。
 * ・Android 12 以降では確認なしで更新できる場合がある。できない機種では確認画面が 1 回出る。
 */
public final class Updater {

    public interface Listener {
        void onUpdateStateChanged();
    }

    static final String ACTION_INSTALL_STATUS = "io.github.yugosandboxcoder.lockmemo.INSTALL_STATUS";

    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000; // 自動確認は 6 時間に 1 回まで
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    private static final String APK_NAME = "update.apk";
    private static final String CHANNEL_ID = "lockmemo_update";
    private static final int NOTIFICATION_ID = 2;

    private static volatile boolean busy;
    private static volatile Intent pendingConfirm; // 利用者の確認が必要なときの、システムの確認画面
    private static volatile Listener listener;
    private static volatile boolean foreground;    // 設定画面が開いているか

    private Updater() {
    }

    // ---------- 状態 ----------

    /** 更新を見に行くリポジトリ（"ユーザー名/リポジトリ名"）。GitHub Actions のビルドで自動的に入る */
    public static String repo(Context c) {
        return c.getString(R.string.update_repo).trim();
    }

    public static boolean isConfigured(Context c) {
        return repo(c).contains("/");
    }

    public static boolean isBusy() {
        return busy;
    }

    public static boolean hasPendingConfirm() {
        return pendingConfirm != null;
    }

    /** この端末で、このアプリによる更新のインストールが許可されているか */
    public static boolean canInstall(Context c) {
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setListener(Listener l, boolean inForeground) {
        listener = l;
        foreground = inForeground;
    }

    public static long currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : legacyVersionCode(pi);
        } catch (Throwable t) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static long legacyVersionCode(PackageInfo pi) {
        return pi.versionCode;
    }

    public static String currentVersionName(Context c) {
        try {
            String name = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------- 確認の開始 ----------

    /** 自動更新がオンで、前回の確認から時間が空いていれば確認する */
    public static void maybeAutoCheck(Context c) {
        if (busy || !Prefs.autoUpdate(c) || !isConfigured(c)) return;
        long now = System.currentTimeMillis();
        if (Math.abs(now - Prefs.getUpdateLastCheck(c)) < AUTO_INTERVAL_MS) return;
        start(c);
    }

    /** 「今すぐ更新を確認」 */
    public static void checkNow(Context c) {
        start(c);
    }

    private static synchronized void start(Context c) {
        if (busy) return;
        busy = true;
        final Context app = c.getApplicationContext();
        Prefs.setUpdateLastCheck(app, System.currentTimeMillis());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    checkAndInstall(app);
                } catch (Throwable t) {
                    setStatus(app, "更新を確認できませんでした（" + shortMessage(t) + "）");
                } finally {
                    busy = false;
                    notifyListener();
                }
            }
        }, "lockmemo-update").start();
        notifyListener();
    }

    private static void checkAndInstall(Context app) throws Exception {
        if (!isConfigured(app)) {
            setStatus(app, "このビルドは自動更新に対応していません");
            return;
        }
        String repo = repo(app);
        setStatus(app, "更新を確認中…");

        JSONObject release = new JSONObject(
                httpGetString("https://api.github.com/repos/" + repo + "/releases/latest"));
        long remote = parseVersion(release.optString("tag_name", ""));

        String apkUrl = null;
        long apkSize = -1;
        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "");
                    apkSize = a.optLong("size", -1);
                    break;
                }
            }
        }

        long current = currentVersionCode(app);
        if (remote <= 0 || apkUrl == null) {
            setStatus(app, "公開されている更新が見つかりませんでした");
            return;
        }
        if (remote <= current) {
            setStatus(app, "最新です（バージョン " + currentVersionName(app) + "）");
            return;
        }
        // 更新ファイルは、同じリポジトリのリリースからだけ受け取る
        if (!apkUrl.startsWith("https://github.com/" + repo + "/releases/download/")) {
            throw new IOException("更新ファイルの場所が正しくありません");
        }
        if (apkSize > MAX_APK_BYTES) throw new IOException("更新ファイルが大きすぎます");

        setStatus(app, "新しい版をダウンロード中…");
        File apk = new File(app.getCacheDir(), APK_NAME);
        download(apkUrl, apk);

        PackageInfo info = app.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || !app.getPackageName().equals(info.packageName)) {
            apk.delete();
            throw new IOException("更新ファイルが正しくありません");
        }

        setStatus(app, "更新をインストール中…");
        install(app, apk);
    }

    // ---------- インストール ----------

    private static void install(Context app, File apk) throws Exception {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        try {
            // 前回の途中で止まった分を片付ける
            for (PackageInstaller.SessionInfo s : installer.getMySessions()) {
                installer.abandonSession(s.getSessionId());
            }
        } catch (Throwable ignored) {
        }

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // 自分自身の更新なので、対応している端末では確認なしで入れられる
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            try {
                InputStream in = new FileInputStream(apk);
                try {
                    copy(in, out, Long.MAX_VALUE);
                } finally {
                    in.close();
                }
                session.fsync(out);
            } finally {
                out.close();
            }

            Intent callback = new Intent(app, UpdateReceiver.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(app, id, callback, flags);
            session.commit(pi.getIntentSender());
        } catch (Exception e) {
            try {
                session.abandon();
            } catch (Throwable ignored) {
            }
            throw e;
        } finally {
            session.close();
        }
    }

    /** UpdateReceiver から：利用者の確認が必要 */
    static void onNeedsUserAction(Context c, Intent confirm) {
        pendingConfirm = confirm;
        setStatus(c, "更新の準備ができました。「更新をインストール」を押してください");
        if (foreground) {
            startConfirm(c);
        } else {
            showNotification(c);
        }
        notifyListener();
    }

    /** UpdateReceiver から：更新が終わった（通常はアプリごと入れ替わるので呼ばれないこともある） */
    static void onInstalled(Context c) {
        pendingConfirm = null;
        setStatus(c, "更新しました");
        cancelNotification(c);
        notifyListener();
    }

    static void onInstallFailed(Context c, String message) {
        pendingConfirm = null;
        setStatus(c, "更新をインストールできませんでした"
                + (message == null || message.length() == 0 ? "" : "（" + message + "）"));
        notifyListener();
    }

    /** システムの確認画面を開く。開けたら true */
    public static boolean startConfirm(Context c) {
        Intent confirm = pendingConfirm;
        if (confirm == null) return false;
        pendingConfirm = null;
        try {
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(confirm);
            cancelNotification(c);
            return true;
        } catch (Throwable t) {
            setStatus(c, "確認画面を開けませんでした。「今すぐ更新を確認」をもう一度押してください");
            return false;
        }
    }

    private static void showNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 1, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_pen)
                    .setContentTitle("ロック画面メモの更新があります")
                    .setContentText("タップして更新をインストール")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIFICATION_ID, n);
        } catch (Throwable ignored) {
            // 通知が出せなくても、設定画面を開けば更新できる
        }
    }

    private static void cancelNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
    }

    // ---------- 通信 ----------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "LockMemo-Updater");
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        return conn;
    }

    private static String httpGetString(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code == 404) throw new IOException("まだリリースがありません");
            if (code != 200) throw new IOException("通信エラー " + code);
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(in, out, 4L * 1024 * 1024);
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, File dst) throws IOException {
        if (dst.exists()) dst.delete();
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("ダウンロードエラー " + code);
            InputStream in = conn.getInputStream();
            try {
                FileOutputStream out = new FileOutputStream(dst);
                try {
                    copy(in, out, MAX_APK_BYTES);
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) throw new IOException("データが大きすぎます");
            out.write(buf, 0, n);
        }
    }

    // ---------- 補助 ----------

    /** "v1013" → 1013。数字が無ければ 0 */
    static long parseVersion(String tag) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tag.length(); i++) {
            char ch = tag.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0 || digits.length() > 9) return 0;
        return Long.parseLong(digits.toString());
    }

    private static String shortMessage(Throwable t) {
        if (t instanceof java.net.UnknownHostException || t instanceof java.net.SocketTimeoutException
                || t instanceof java.net.ConnectException) {
            return "インターネットにつながりません";
        }
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static void setStatus(Context c, String s) {
        Prefs.setUpdateStatus(c, s);
        notifyListener();
    }

    private static void notifyListener() {
        Listener l = listener;
        if (l != null) {
            try {
                l.onUpdateStateChanged();
            } catch (Throwable ignored) {
            }
        }
    }
}
