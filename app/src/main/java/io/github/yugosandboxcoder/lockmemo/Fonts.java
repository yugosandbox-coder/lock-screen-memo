package io.github.yugosandboxcoder.lockmemo;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * メモの文字に使うフォント。
 * アプリには文字用のフォントを同梱せず、使う人が自分の端末にあるフォントファイル
 * （.ttf / .otf）を読み込めるようにしている。読み込んでいなければ端末の標準フォント。
 */
public final class Fonts {

    private static final String MEMO_FONT_FILE = "memo_font";
    private static final long MAX_FONT_BYTES = 40L * 1024 * 1024;

    private static Typeface cached;
    private static long cachedStamp = -1;

    private Fonts() {
    }

    private static File memoFontFile(Context c) {
        return new File(c.getFilesDir(), MEMO_FONT_FILE);
    }

    public static boolean hasMemoFont(Context c) {
        File f = memoFontFile(c);
        return f.exists() && f.length() > 0;
    }

    /** 読み込んだフォント。無ければ null（＝端末の標準フォントを使う） */
    public static synchronized Typeface memoTypeface(Context c) {
        File f = memoFontFile(c);
        if (!f.exists() || f.length() == 0) {
            cached = null;
            cachedStamp = -1;
            return null;
        }
        long stamp = f.lastModified() * 31 + f.length();
        if (stamp != cachedStamp) {
            try {
                cached = Typeface.createFromFile(f);
            } catch (Throwable t) {
                cached = null;
            }
            cachedStamp = stamp;
        }
        return cached;
    }

    /** 標準フォントに戻す */
    public static synchronized void clearMemoFont(Context c) {
        File f = memoFontFile(c);
        if (f.exists()) f.delete();
        cached = null;
        cachedStamp = -1;
    }

    /** 選ばれたファイルをフォントとして取り込む。フォントでなければ false */
    public static synchronized boolean importMemoFont(Context c, Uri uri) {
        File tmp = new File(c.getFilesDir(), MEMO_FONT_FILE + ".tmp");
        try {
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            long total = 0;
            try {
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_FONT_BYTES) return false;
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (!looksLikeFont(tmp)) return false;
            // 実際にフォントとして開けるか確かめる
            Typeface.createFromFile(tmp);

            File dst = memoFontFile(c);
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) return false;
            cached = null;
            cachedStamp = -1;
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /** ファイルの先頭 4 バイトで、TrueType / OpenType フォントかどうかを見分ける */
    private static boolean looksLikeFont(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] h = new byte[4];
                if (in.read(h) != 4) return false;
                boolean trueType = h[0] == 0 && h[1] == 1 && h[2] == 0 && h[3] == 0;
                String tag = new String(h, "ISO-8859-1");
                return trueType || tag.equals("OTTO") || tag.equals("true") || tag.equals("ttcf");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
