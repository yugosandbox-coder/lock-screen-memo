package io.github.yugosandboxcoder.lockmemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * GitHub のリリースを見て、新しい版が出ていたら知らせる。
 *
 * ・このアプリ自身がファイルをダウンロードしたりインストールしたりはしない。
 *   知らせを押すとブラウザーで配布ファイルが開き、インストールは Android の標準の画面で行う。
 * ・通信先は GitHub（api.github.com）だけ。メモの内容などは一切送らない。
 */
public final class Updater {

    public interface Listener {
        void onUpdateStateChanged();
    }

    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000; // 自動確認は 6 時間に 1 回まで
    private static final String CHANNEL_ID = "lockmemo_update";
    private static final int NOTIFICATION_ID = 2;

    private static volatile boolean busy;
    private static volatile Listener listener;

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

    public static void setListener(Listener l) {
        listener = l;
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

    /** いま入っている版より新しい版が見つかっているか */
    public static boolean hasUpdate(Context c) {
        return Prefs.getUpdateUrl(c).length() > 0 && Prefs.getUpdateCode(c) > currentVersionCode(c);
    }

    /** 新しい版の配布ファイルをブラウザーで開く（ダウンロードとインストールは利用者が行う） */
    public static boolean openDownload(Context c) {
        String url = Prefs.getUpdateUrl(c);
        if (url.length() == 0) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            cancelNotification(c);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 確認 ----------

    /** 自動確認がオンで、前回の確認から時間が空いていれば確認する */
    public static void maybeAutoCheck(Context c) {
        if (busy || !Prefs.autoUpdate(c) || !isConfigured(c)) return;
        long now = System.currentTimeMillis();
        if (Math.abs(now - Prefs.getUpdateLastCheck(c)) < AUTO_INTERVAL_MS) return;
        start(c, true);
    }

    /** 「今すぐ更新を確認」 */
    public static void checkNow(Context c) {
        start(c, false);
    }

    private static synchronized void start(Context c, final boolean notify) {
        if (busy) return;
        busy = true;
        final Context app = c.getApplicationContext();
        Prefs.setUpdateLastCheck(app, System.currentTimeMillis());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(app, notify);
                } catch (Throwable t) {
                    Prefs.setUpdateStatus(app, "更新を確認できませんでした（" + shortMessage(t) + "）");
                } finally {
                    busy = false;
                    notifyListener();
                }
            }
        }, "lockmemo-update").start();
        notifyListener();
    }

    private static void check(Context app, boolean notify) throws Exception {
        if (!isConfigured(app)) {
            Prefs.setUpdateStatus(app, "このビルドは更新の確認に対応していません");
            return;
        }
        String repo = repo(app);

        JSONObject release = new JSONObject(
                httpGetString("https://api.github.com/repos/" + repo + "/releases/latest"));
        long remote = parseVersion(release.optString("tag_name", ""));
        String remoteName = release.optString("name", "");

        String apkUrl = "";
        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                if (a.optString("name", "").toLowerCase(Locale.US).endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "");
                    break;
                }
            }
        }
        // 配布ファイルは、同じリポジトリのリリースのものだけを案内する
        boolean urlOk = apkUrl.startsWith("https://github.com/" + repo + "/releases/download/");

        long current = currentVersionCode(app);
        if (remote <= 0 || !urlOk) {
            Prefs.setUpdateAvailable(app, 0, "");
            Prefs.setUpdateStatus(app, "公開されている更新が見つかりませんでした");
            return;
        }
        if (remote <= current) {
            Prefs.setUpdateAvailable(app, 0, "");
            Prefs.setUpdateStatus(app, "最新です");
            cancelNotification(app);
            return;
        }

        Prefs.setUpdateAvailable(app, remote, apkUrl);
        Prefs.setUpdateStatus(app, "新しい版があります"
                + (remoteName.length() > 0 ? "（" + remoteName + "）" : ""));
        // 同じ版について何度も通知しない
        if (notify && Prefs.getUpdateNotifiedCode(app) != remote) {
            Prefs.setUpdateNotifiedCode(app, remote);
            showNotification(app);
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
                    .setContentTitle("ロック画面メモの新しい版があります")
                    .setContentText("タップして更新の案内を開く")
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

    private static String httpGetString(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "LockMemo");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code == 404) throw new IOException("まだリリースがありません");
            if (code != 200) throw new IOException("通信エラー " + code);
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 4L * 1024 * 1024) throw new IOException("データが大きすぎます");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
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
