package io.github.yugosandboxcoder.lockmemo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/** 背景にする写真の、切り取る範囲を決める画面（拡大・縮小・移動ができる） */
public class CropActivity extends Activity {

    private CropView cropView;
    private TextView hint;
    private Bitmap source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 元の写真（無ければ、いま使っている背景の写真）を読む
        File file = Backgrounds.photoSourceFile(this);
        if (!file.exists() || file.length() == 0) file = Backgrounds.photoFile(this);
        try {
            source = BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Throwable t) {
            source = null;
        }
        if (source == null) {
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101014);

        cropView = new CropView(this);
        cropView.setAspect(screenAspect());
        cropView.setReserved(dp(64), dp(96));
        cropView.setImage(source);
        root.addView(cropView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("2本指で拡大・縮小、ドラッグで移動。\n枠の中がロック画面の背景になります。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(0xFFE8E8EE);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP);
        root.addView(hint, hlp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(12), dp(12), dp(20));
        addBarButton(bar, "キャンセル", 0xFF2C2C36, 0xFFF0F0F5, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        addBarButton(bar, "最初に戻す", 0xFF2C2C36, 0xFFF0F0F5, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cropView.resetImage();
            }
        });
        addBarButton(bar, "この範囲に決定", 0xFFFFE066, 0xFF101014, 1.4f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndFinish();
            }
        });
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.BOTTOM));

        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (source != null && isFinishing()) {
            source.recycle();
            source = null;
        }
    }

    /** ロック画面（画面全体）の 幅 ÷ 高さ。縦向きで使うので、短い辺を幅とする */
    private float screenAspect() {
        int[] size = screenSize();
        return (float) size[0] / (float) size[1];
    }

    /** 画面全体のピクセル数 {幅, 高さ}（縦向き） */
    private int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        } catch (Throwable t) {
            dm = getResources().getDisplayMetrics();
        }
        int a = dm.widthPixels;
        int b = dm.heightPixels;
        if (a <= 0 || b <= 0) {
            DisplayMetrics fallback = getResources().getDisplayMetrics();
            a = fallback.widthPixels;
            b = fallback.heightPixels;
        }
        if (a <= 0 || b <= 0) return new int[]{1080, 2400};
        return new int[]{Math.min(a, b), Math.max(a, b)};
    }

    private void saveAndFinish() {
        int[] size = screenSize();
        // 大きすぎる画面でもメモリを使いすぎないよう、長い辺は 2560px まで
        float scale = Math.min(1f, 2560f / size[1]);
        int outW = Math.max(1, Math.round(size[0] * scale));
        int outH = Math.max(1, Math.round(size[1] * scale));

        Bitmap out = null;
        try {
            out = cropView.export(outW, outH);
            if (out == null) throw new IllegalStateException("no image");

            File dst = Backgrounds.photoFile(this);
            File tmp = new File(getFilesDir(), Prefs.PHOTO_FILE + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                out.compress(Bitmap.CompressFormat.JPEG, 92, os);
                os.flush();
            } finally {
                os.close();
            }
            if (!tmp.renameTo(dst)) throw new IllegalStateException("rename failed");

            Prefs.setBackground(this, Prefs.BG_PHOTO);
            finish();
        } catch (Throwable t) {
            hint.setText("保存できませんでした。もう一度お試しください。");
            hint.setTextColor(0xFFFF8A80);
        } finally {
            if (out != null) out.recycle();
        }
    }

    private void addBarButton(LinearLayout bar, String label, int bgColor, int textColor,
                              float weight, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(bgColor);
        b.setBackground(bg);
        b.setClickable(true);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), weight);
        lp.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(b, lp);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
