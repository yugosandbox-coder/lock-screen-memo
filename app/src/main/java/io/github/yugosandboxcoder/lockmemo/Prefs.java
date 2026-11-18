package io.github.yugosandboxcoder.lockmemo;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定値のまとめ。背景の種類・写真・時計の表示などを保存する。 */
public final class Prefs {

    private static final String FILE = "lockmemo";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_BACKGROUND = "background";
    public static final String KEY_SHOW_CLOCK = "show_clock";
    public static final String KEY_PHOTO_DIM = "photo_dim"; // -100..100（暗く〜明るく）

    /** 背景の種類 */
    public static final int BG_CREAM_RULED = 0;
    public static final int BG_WHITE_RULED = 1;
    public static final int BG_WHITE_GRID = 2;
    public static final int BG_CREAM_GRID = 3;
    public static final int BG_WHITE_PLAIN = 4;
    public static final int BG_BLACK_PLAIN = 5;
    public static final int BG_PHOTO = 6;

    /** ロック画面に使う、切り取り済みの写真 */
    public static final String PHOTO_FILE = "background.jpg";
    /** 切り取り前の元写真（あとで切り取り位置を変えられるように残す） */
    public static final String PHOTO_SOURCE_FILE = "photo_source.jpg";

    public static final String KEY_TEXT_SIZE = "text_size"; // 文字の大きさ（sp）
    public static final float TEXT_SIZE_MIN = 12f;
    public static final float TEXT_SIZE_MAX = 80f;
    public static final float TEXT_SIZE_DEFAULT = 26f;

    public static final String KEY_AUTO_UPDATE = "auto_update";
    public static final String KEY_UPDATE_LAST_CHECK = "update_last_check";
    public static final String KEY_UPDATE_STATUS = "update_status";

    public static final String KEY_PALETTE_X = "palette_x"; // 0..1（左〜右）
    public static final String KEY_PALETTE_Y = "palette_y"; // 0..1（上〜下）

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static int getBackground(Context c) {
        return sp(c).getInt(KEY_BACKGROUND, BG_CREAM_RULED);
    }

    public static void setBackground(Context c, int v) {
        sp(c).edit().putInt(KEY_BACKGROUND, v).apply();
    }

    public static boolean showClock(Context c) {
        return sp(c).getBoolean(KEY_SHOW_CLOCK, true);
    }

    public static void setShowClock(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SHOW_CLOCK, v).apply();
    }

    public static int getPhotoDim(Context c) {
        return sp(c).getInt(KEY_PHOTO_DIM, -20);
    }

    public static void setPhotoDim(Context c, int v) {
        sp(c).edit().putInt(KEY_PHOTO_DIM, v).apply();
    }

    public static float getTextSize(Context c) {
        float v = sp(c).getFloat(KEY_TEXT_SIZE, TEXT_SIZE_DEFAULT);
        return Math.max(TEXT_SIZE_MIN, Math.min(TEXT_SIZE_MAX, v));
    }

    public static void setTextSize(Context c, float v) {
        sp(c).edit().putFloat(KEY_TEXT_SIZE, v).apply();
    }

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean(KEY_AUTO_UPDATE, true);
    }

    public static void setAutoUpdate(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_AUTO_UPDATE, v).apply();
    }

    public static long getUpdateLastCheck(Context c) {
        return sp(c).getLong(KEY_UPDATE_LAST_CHECK, 0L);
    }

    public static void setUpdateLastCheck(Context c, long v) {
        sp(c).edit().putLong(KEY_UPDATE_LAST_CHECK, v).apply();
    }

    public static String getUpdateStatus(Context c) {
        return sp(c).getString(KEY_UPDATE_STATUS, "");
    }

    public static void setUpdateStatus(Context c, String v) {
        sp(c).edit().putString(KEY_UPDATE_STATUS, v).apply();
    }

    public static float getPaletteX(Context c) {
        return sp(c).getFloat(KEY_PALETTE_X, 1f);
    }

    public static float getPaletteY(Context c) {
        return sp(c).getFloat(KEY_PALETTE_Y, 0.5f);
    }

    public static void setPalettePos(Context c, float x, float y) {
        sp(c).edit().putFloat(KEY_PALETTE_X, x).putFloat(KEY_PALETTE_Y, y).apply();
    }
}
