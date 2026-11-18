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
import android.os.IBinder;

/**
 * 常駐サービス（ユーザー補助は使わない）。
 * 画面が点いた瞬間に、ロック中であればメモ画面（NoteActivity）を前面に出す。
 * メモ画面は show-when-locked なので本物のロック画面の上に表示される。
 */
public class LockMemoService extends Service {

    private static final String CHANNEL_ID = "lockmemo_service";
    private static final int NOTIFICATION_ID = 1;

    public static volatile boolean running;

    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // 消灯した瞬間に、点灯はさせず先にメモ画面を用意しておく
                // （次に点けたとき一瞬で出せるようにするため）
                preloadNote();
                // ついでに、新しい版が出ていないか確認する（6 時間に 1 回まで。知らせるだけ）
                Updater.maybeAutoCheck(LockMemoService.this);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                refresh();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // ロック解除：ホーム画面に残さないよう閉じる
                NoteActivity.finishNote();
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

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "ロック画面メモの常駐", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        // 押すとメモを表示する。上にスワイプしてメモを片付けたあと、ロック画面の通知から呼び戻せる
        Intent note = new Intent(this, NoteActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, note, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pen)
                .setContentTitle("ロック画面メモ")
                .setContentText("タップでメモを表示")
                .setContentIntent(pi)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build();
    }
}
