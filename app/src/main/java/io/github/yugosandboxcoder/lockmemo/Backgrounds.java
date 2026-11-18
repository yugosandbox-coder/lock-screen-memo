package io.github.yugosandboxcoder.lockmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;

/**
 * 背景の描画をまとめた場所。ロック画面本体（BackgroundView）と設定画面の見本（サムネイル）の
 * 両方が、同じこの描画を使う。
 */
public final class Backgrounds {

    private Backgrounds() {
    }

    // 紙の色
    private static int paperColor(int bg) {
        switch (bg) {
            case Prefs.BG_CREAM_RULED:
            case Prefs.BG_CREAM_GRID:
                return 0xFFFAF4E6;
            case Prefs.BG_BLACK_PLAIN:
                return 0xFF111114;
            default:
                return 0xFFFFFFFF;
        }
    }

    /** 明るい背景か（ステータスバーのアイコンを黒くするかどうかに使う） */
    public static boolean isLight(int bg) {
        return bg != Prefs.BG_BLACK_PLAIN && bg != Prefs.BG_PHOTO;
    }

    /** その背景の上で見やすいペンの初期色（明るい紙→黒、暗い紙・写真→白） */
    public static int defaultInk(int bg) {
        return (bg == Prefs.BG_BLACK_PLAIN || bg == Prefs.BG_PHOTO) ? Color.WHITE : 0xFF222228;
    }

    /** 時計・日付の色 */
    public static int clockInk(int bg) {
        switch (bg) {
            case Prefs.BG_CREAM_RULED:
            case Prefs.BG_CREAM_GRID:
                return 0xFF4A4232;
            case Prefs.BG_BLACK_PLAIN:
                return 0xFFEDEDF2;
            case Prefs.BG_PHOTO:
                return 0xFFFFFFFF;
            default:
                return 0xFF3A3A42;
        }
    }

    /**
     * 背景を描く。
     * @param photo 写真背景のときに使う画像（無ければ null）。
     */
    public static void draw(Canvas c, int w, int h, float density, int bg,
                            Bitmap photo, int photoDim) {
        if (bg == Prefs.BG_PHOTO && photo != null) {
            drawPhoto(c, w, h, photo, photoDim);
            return;
        }
        c.drawColor(paperColor(bg == Prefs.BG_PHOTO ? Prefs.BG_WHITE_PLAIN : bg));

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);

        boolean cream = (bg == Prefs.BG_CREAM_RULED || bg == Prefs.BG_CREAM_GRID);
        float top = 96 * density; // 時計のあたりは線を薄く始める

        if (bg == Prefs.BG_WHITE_GRID || bg == Prefs.BG_CREAM_GRID) {
            p.setStrokeWidth(Math.max(1f, density));
            p.setColor(cream ? 0xFFE9DFC2 : 0xFFE3E8EC);
            float step = 26 * density;
            for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, p);
            for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, p);
        } else if (bg == Prefs.BG_WHITE_RULED || bg == Prefs.BG_CREAM_RULED) {
            float step = 34 * density;
            p.setStrokeWidth(Math.max(1f, 1.3f * density));
            p.setColor(cream ? 0xFFECE1C4 : 0xFFCFE3F2);
            for (float y = top; y < h - 8 * density; y += step) c.drawLine(10 * density, y, w - 10 * density, y, p);
            // 余白線
            p.setStrokeWidth(1.5f * density);
            p.setColor(cream ? 0xFFE6C9A8 : 0xFFF3B8C4);
            float mx = 40 * density;
            c.drawLine(mx, 0, mx, h, p);
            // とじ穴
            Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
            hole.setColor(cream ? 0xFFF3EBD6 : 0xFFEEF1F5);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(density);
            edge.setColor(cream ? 0xFFE0D2AF : 0xFFD5DAE0);
            float r = 7 * density, cx = 20 * density;
            float[] ys = {0.2f * h, 0.5f * h, 0.8f * h};
            for (float cy : ys) {
                c.drawCircle(cx, cy, r, hole);
                c.drawCircle(cx, cy, r, edge);
            }
        }
    }

    private static void drawPhoto(Canvas c, int w, int h, Bitmap photo, int photoDim) {
        // 画面いっぱいに中央トリミングで描く
        float scale = Math.max((float) w / photo.getWidth(), (float) h / photo.getHeight());
        int dw = Math.round(photo.getWidth() * scale);
        int dh = Math.round(photo.getHeight() * scale);
        int left = (w - dw) / 2;
        int top = (h - dh) / 2;
        c.drawColor(Color.BLACK);
        c.drawBitmap(photo, new Rect(0, 0, photo.getWidth(), photo.getHeight()),
                new RectF(left, top, left + dw, top + dh), new Paint(Paint.FILTER_BITMAP_FLAG));
        if (photoDim != 0) {
            Paint p = new Paint();
            int a = Math.round(Math.min(100, Math.abs(photoDim)) / 100f * 160);
            p.setColor((photoDim < 0 ? 0x000000 : 0xFFFFFF) | (a << 24));
            c.drawRect(0, 0, w, h, p);
        }
    }

    public static File photoFile(Context ctx) {
        return new File(ctx.getFilesDir(), Prefs.PHOTO_FILE);
    }

    public static File photoSourceFile(Context ctx) {
        return new File(ctx.getFilesDir(), Prefs.PHOTO_SOURCE_FILE);
    }

    /** 切り取り済みの写真があるか（画像は読み込まずに確認する） */
    public static boolean hasPhoto(Context ctx) {
        File f = photoFile(ctx);
        return f.exists() && f.length() > 0;
    }

    /** 写真背景の画像を内部ストレージから読む。無ければ null。 */
    public static Bitmap loadPhoto(Context ctx) {
        return loadPhoto(ctx, 1);
    }

    /** sample = 2, 4, 8… で縮小して読む（サムネイル用） */
    public static Bitmap loadPhoto(Context ctx, int sample) {
        File f = photoFile(ctx);
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 設定画面のサムネイル用ビットマップを作る */
    public static Bitmap makeThumb(Context ctx, int bg, int wPx, int hPx, float density) {
        Bitmap b = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Bitmap photo = (bg == Prefs.BG_PHOTO) ? loadPhoto(ctx, 8) : null;
        draw(c, wPx, hPx, density * 0.5f, bg, photo, Prefs.getPhotoDim(ctx));
        if (bg == Prefs.BG_PHOTO && photo == null) {
            // 写真未設定：中央にアイコン代わりの枠
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFBFC6CE);
            c.drawColor(0xFFEDEFF2);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * density);
            c.drawCircle(wPx / 2f, hPx / 2f - 4 * density, 10 * density, p);
        }
        return b;
    }
}
