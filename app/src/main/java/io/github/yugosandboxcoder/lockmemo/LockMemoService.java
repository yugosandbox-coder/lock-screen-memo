package io.github.yugosandboxcoder.lockmemo;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * 常駐サービス（ユーザー補助は使わない）。
 * 画面が点いた瞬間に、ロック中であればメモ画面（NoteActivity）を前面に出す。
 * メモ画面は show-when-locked なので本物のロック画面の上に表示される。
 *
 * 通知は 2 種類ある。
 * ・常駐の通知：サービスを動かし続けるために Android が求めるもの。押すと設定画面が開く。
 * ・「メモを表示」の通知：ロック画面が出ている間だけ出し、ロック画面でなくなったら消す。
 *   メモを上へ片付けたあと、これを押すとメモが戻る。
 */
public class LockMemoService extends Service {

    private static final String[] OLD_CHANNEL_IDS = {"lockmemo_service", "lockmemo_memo"};
    private static final String CHANNEL_RUNNING = "lockmemo_running"; // 常駐の通知
    private static final String CHANNEL_OPEN = "lockmemo_open";       // 「メモを表示」の通知
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_NOTIFICATION_ID = 3;
    /** ロック画面でなくなったのに通知が残らないよう、出している間は定期的に確かめる */
    private static final long OPEN_WATCH_MS = 1500;

    public static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean openShown;

    /** 画面が点いてロック画面が出ているはずのタイミングで「メモを表示」を出す（少し待ってから確かめる） */
    private final Runnable postOpen = new Runnable() {
        @Override
        public void run() {
            updateOpenNotification(true);
        }
    };

    private final Runnable watchOpen = new Runnable() {
        @Override
        public void run() {
            if (!openShown) return;
            updateOpenNotification(false);
            if (openShown) handler.postDelayed(this, OPEN_WATCH_MS);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // 画面が消えた＝ロック画面ではなくなったので、「メモを表示」の通知を消す
                handler.removeCallbacks(postOpen);
                hideOpenNotification();
                // 消灯した瞬間に、点灯はさせず先にメモ画面を用意しておく
                // （次に点けたとき一瞬で出せるようにするため）
                preloadNote();
                // ついでに、新しい版が出ていないか確認する（6 時間に 1 回まで。知らせるだけ）
                Updater.maybeAutoCheck(LockMemoService.this);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                refresh();
                // ロック画面が出るたびに「メモを表示」の通知を出す。
                // すぐ 1 回、念のため少し後にもう 1 回確かめる（点灯直後は状態がまだ変わりきっていないことがある）
                updateOpenNotification(true);
                handler.removeCallbacks(postOpen);
                handler.postDelayed(postOpen, 800);
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // ロック解除：ホーム画面に残さないよう閉じ、通知も消す
                NoteActivity.finishNote();
                handler.removeCallbacks(postOpen);
                hideOpenNotification();
            }
        }
    };

    public static void start(Context c) {
        Intent i = new Intent(c, LockMemoService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(receiver, filter);
        receiverRegistered = true;
        // 前回の分が残っていたら片付け、いまロック画面なら出し直す
        hideOpenNotification();
        updateOpenNotification(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        hideOpenNotification();
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 消灯時：ロックの有無を問わず、先にメモ画面を用意しておく（点灯はさせない） */
    private void preloadNote() {
        if (!Prefs.isEnabled(this) || inCall() || yieldingToMusic()) return;
        launchNote();
    }

    /** 点灯時：ロック中なら表示、解除済みなら閉じる */
    private void refresh() {
        if (Prefs.isEnabled(this) && isLocked() && !inCall() && !yieldingToMusic()) {
            launchNote();
        } else {
            NoteActivity.finishNote();
        }
    }

    // ---------- 「メモを表示」の通知 ----------

    /** いまロック画面が出ているか（画面が点いていて、ロックされている） */
    private boolean onLockScreen() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive() && isLocked();
    }

    /**
     * ロック画面が出ていれば通知を出し、そうでなければ消す。
     * repost = true のときは、すでに出していても出し直す（利用者に払われていても戻るように）。
     */
    private void updateOpenNotification(boolean repost) {
        if (Prefs.isEnabled(this) && onLockScreen() && !inCall()) {
            if (repost || !openShown) showOpenNotification();
        } else {
            hideOpenNotification();
        }
    }

    private void showOpenNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannels(nm);
            Intent note = new Intent(this, NoteActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 2, note, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_OPEN)
                    .setSmallIcon(R.drawable.ic_pen)
                    .setContentTitle("ロック画面メモ")
                    .setContentText("タップでメモを表示")
                    .setContentIntent(pi)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .build();
            nm.notify(OPEN_NOTIFICATION_ID, n);
            openShown = true;
            handler.removeCallbacks(watchOpen);
            handler.postDelayed(watchOpen, OPEN_WATCH_MS);
        } catch (Throwable ignored) {
            // 通知が許可されていないときなどは何もしない
        }
    }

    private void hideOpenNotification() {
        openShown = false;
        handler.removeCallbacks(watchOpen);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(OPEN_NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
    }

    private void ensureChannels(NotificationManager nm) {
        if (nm.getNotificationChannel(CHANNEL_RUNNING) == null) {
            // 常駐の通知：目立たせず、ロック画面にも出さない
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_RUNNING, "常駐（動作中の表示）", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_OPEN) == null) {
            // 「メモを表示」：ロック画面の通知一覧に出るよう標準の重要度にする。音と振動は鳴らさない
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_OPEN, "ロック画面でメモを表示", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        for (String old : OLD_CHANNEL_IDS) {
            try {
                nm.deleteNotificationChannel(old); // 以前の版の分を片付ける
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 状態の確認 ----------

    private boolean isLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /** 音楽などの再生中で、メモを出さない設定になっているか（本物のロック画面の再生操作を見せる） */
    private boolean yieldingToMusic() {
        if (!Prefs.yieldToMusic(this)) return false;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        return am != null && am.isMusicActive();
    }

    private boolean inCall() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return false;
        int mode = am.getMode();
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    private void launchNote() {
        Intent i = new Intent(this, NoteActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try {
            startActivity(i);
        } catch (Exception e) {
            // 「他のアプリの上に重ねて表示」が未許可だと起動できない
        }
    }

    /** 常駐の通知（サービスを動かし続けるために必要）。押すと設定画面が開く */
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) ensureChannels(nm);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_pen)
                .setContentTitle("ロック画面メモ 動作中")
                .setContentText("タップで設定を開きます")
                .setContentIntent(pi)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .build();
    }
}
