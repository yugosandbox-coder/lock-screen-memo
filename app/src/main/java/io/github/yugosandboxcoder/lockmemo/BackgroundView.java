package io.github.yugosandboxcoder.lockmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.File;

/** 選ばれた背景（紙・方眼・ルーズリーフ・写真）を全画面に描くビュー */
public class BackgroundView extends View {

    private int bg = -1;
    private Bitmap photo;
    /** 読み込み済みの写真ファイルの目印（更新日時＋サイズ）。同じなら読み直さない */
    private long photoStamp = -1;

    public BackgroundView(Context c) {
        super(c);
        reload();
    }

    /** 設定を読み直す。写真は、ファイルが変わったときだけ読み込み直す（表示を速くするため） */
    public void reload() {
        int newBg = Prefs.getBackground(getContext());
        long stamp = -1;
        if (newBg == Prefs.BG_PHOTO) {
            File f = Backgrounds.photoFile(getContext());
            stamp = f.exists() ? f.lastModified() * 31 + f.length() : -1;
        }
        if (newBg == bg && stamp == photoStamp) return; // 変化なし

        bg = newBg;
        photoStamp = stamp;
        if (photo != null) {
            photo.recycle();
            photo = null;
        }
        if (bg == Prefs.BG_PHOTO && stamp != -1) {
            photo = Backgrounds.loadPhoto(getContext());
        }
        invalidate();
    }

    public int getBackgroundType() {
        return bg;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        Backgrounds.draw(canvas, getWidth(), getHeight(), density, bg, photo,
                Prefs.getPhotoDim(getContext()));
    }
}
