package io.github.yugosandboxcoder.lockmemo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 設定画面：許可、背景の選択（7種類＋写真）、時計、文字フォント、開始・停止、自動更新 */
public class MainActivity extends Activity implements Updater.Listener {

    private static final int OK_COLOR = 0xFF1E8E3E;
    private static final int WARN_COLOR = 0xFFC0392B;
    private static final int ACCENT = 0xFF3D6DF2;
    private static final int REQ_PHOTO = 10;
    private static final int REQ_FONT = 11;

    private static final int[] BG_ORDER = {
            Prefs.BG_CREAM_RULED, Prefs.BG_WHITE_RULED, Prefs.BG_WHITE_GRID, Prefs.BG_CREAM_GRID,
            Prefs.BG_WHITE_PLAIN, Prefs.BG_BLACK_PLAIN, Prefs.BG_PHOTO};
    private static final String[] BG_NAMES = {
            "クリーム\nルーズリーフ", "白\nルーズリーフ", "方眼", "クリーム\n方眼",
            "真っ白", "真っ黒", "好きな写真"};

    private TextView overlayStatus;
    private TextView enabledStatus;
    private TextView clockStatus;
    private TextView toggleButton;
    private LinearLayout bgGrid;
    private TextView photoHint;
    private TextView fontStatus;
    private TextView updateStatus;
    private TextView updateToggle;
    private TextView updateInstallButton;
    private TextView updateAllowButton;
    private TextView versionLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(26), dp(20), dp(28));
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("ロック画面メモ", 26, 0xFF14141A);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        box.addView(text("ロック画面の上にメモを表示します。ロックを解除すると消え、ホーム画面には残りません。"
                + "右下のペンのボタンから手書き・文字入力・消去ができます。"
                + "道具のパレットは、上のつまみをドラッグして好きな場所に動かせます。", 15, 0xFF55555F), margin(6, 18));

        box.addView(section("① 他のアプリの上に重ねて表示（必須）"));
        overlayStatus = text("", 15, 0xFF55555F);
        box.addView(overlayStatus, margin(2, 6));
        box.addView(text("ロック画面にメモを出すために必要です。スイッチが押せない・「制限付き設定」と出るときは、"
                + "下の「アプリ情報を開く」→ 右上メニュー →「制限付き設定を許可」を先に押してください。", 14, 0xFF77777F), margin(0, 8));
        box.addView(button("重ねて表示の設定を開く", true, new View.OnClickListener() {
            public void onClick(View v) {
                openOverlaySettings();
            }
        }), margin(0, 8));
        box.addView(button("アプリ情報を開く", false, new View.OnClickListener() {
            public void onClick(View v) {
                openAppDetails();
            }
        }), margin(0, 22));

        box.addView(section("② 背景"));
        box.addView(text("タップで選べます。写真はいつでも変えられます。", 15, 0xFF55555F), margin(2, 10));
        bgGrid = new LinearLayout(this);
        bgGrid.setOrientation(LinearLayout.VERTICAL);
        box.addView(bgGrid, margin(0, 10));
        photoHint = text("", 14, 0xFF77777F);
        box.addView(photoHint, margin(0, 6));
        box.addView(button("写真を選ぶ", false, new View.OnClickListener() {
            public void onClick(View v) {
                pickPhoto();
            }
        }), margin(0, 8));
        box.addView(button("写真の切り取り位置を変える（拡大・移動）", false, new View.OnClickListener() {
            public void onClick(View v) {
                openCrop();
            }
        }), margin(0, 22));

        box.addView(section("③ 時計"));
        clockStatus = text("", 15, 0xFF55555F);
        box.addView(clockStatus, margin(2, 8));
        box.addView(button("時計の表示を切り替える", false, new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setShowClock(MainActivity.this, !Prefs.showClock(MainActivity.this));
                refresh();
            }
        }), margin(0, 22));

        box.addView(section("④ メモの文字フォント"));
        fontStatus = text("", 15, 0xFF55555F);
        box.addView(fontStatus, margin(2, 6));
        box.addView(text("文字入力で使うフォントを、端末にあるフォントファイル（.ttf / .otf）から選べます。"
                + "フリーフォントなどをダウンロードして選んでください。フォントに無い文字は標準の字で表示されます。",
                14, 0xFF77777F), margin(0, 8));
        box.addView(button("フォントファイルを選ぶ", false, new View.OnClickListener() {
            public void onClick(View v) {
                pickFont();
            }
        }), margin(0, 8));
        box.addView(button("標準のフォントに戻す", false, new View.OnClickListener() {
            public void onClick(View v) {
                Fonts.clearMemoFont(MainActivity.this);
                refresh();
            }
        }), margin(0, 22));

        box.addView(section("⑤ ロック画面メモ"));
        enabledStatus = text("", 15, 0xFF55555F);
        box.addView(enabledStatus, margin(2, 8));
        toggleButton = button("", false, new View.OnClickListener() {
            public void onClick(View v) {
                toggleService();
            }
        });
        box.addView(toggleButton, margin(0, 10));
        box.addView(button("今すぐ書く（試し）", false, new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, NoteActivity.class);
                i.putExtra("manual", true);
                startActivity(i);
            }
        }), margin(0, 22));

        box.addView(section("⑥ アプリの更新"));
        updateStatus = text("", 15, 0xFF55555F);
        box.addView(updateStatus, margin(2, 6));
        box.addView(text("新しい版が GitHub に公開されると、自動でダウンロードして更新します。"
                + "機種によっては確認の画面が 1 回出ます。通信するのは GitHub だけで、メモの内容は送りません。",
                14, 0xFF77777F), margin(0, 8));
        updateInstallButton = button("更新をインストール", true, new View.OnClickListener() {
            public void onClick(View v) {
                Updater.startConfirm(MainActivity.this);
                refresh();
            }
        });
        box.addView(updateInstallButton, margin(0, 8));
        updateAllowButton = button("更新のインストールを許可する（設定を開く）", true, new View.OnClickListener() {
            public void onClick(View v) {
                openInstallPermission();
            }
        });
        box.addView(updateAllowButton, margin(0, 8));
        box.addView(button("今すぐ更新を確認", false, new View.OnClickListener() {
            public void onClick(View v) {
                Updater.checkNow(MainActivity.this);
                refresh();
            }
        }), margin(0, 8));
        updateToggle = button("", false, new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setAutoUpdate(MainActivity.this, !Prefs.autoUpdate(MainActivity.this));
                refresh();
            }
        });
        box.addView(updateToggle, margin(0, 22));

        box.addView(section("うまく出ないとき（OPPO など）"));
        box.addView(text("しばらくするとメモが出なくなる場合は、電池の最適化からこのアプリを外し、"
                + "アプリ情報で「自動起動」と「バックグラウンドアクティビティ」を許可してください。", 15, 0xFF55555F), margin(2, 8));
        box.addView(button("電池の最適化の設定を開く", false, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    openAppDetails();
                }
            }
        }), margin(0, 18));

        versionLabel = text("", 12, 0xFF9A9AA2);
        box.addView(versionLabel, margin(0, 4));
        TextView credit = text("時計フォント: Auxiliary line（© とりしっぽ, SIL Open Font License 1.1）",
                12, 0xFF9A9AA2);
        box.addView(credit, margin(0, 0));

        setContentView(scroll);
        buildGrid();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.isEnabled(this) && !LockMemoService.running && Settings.canDrawOverlays(this)) {
            LockMemoService.start(this);
        }
        buildGrid();
        refresh();

        // 設定画面を開いている間は、更新の状況をすぐ画面に反映する
        Updater.setListener(this, true);
        if (Updater.hasPendingConfirm()) {
            Updater.startConfirm(this); // 通知から来たとき：そのまま確認画面へ
        } else {
            Updater.maybeAutoCheck(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Updater.setListener(null, false);
    }

    /** Updater から（別スレッドのことがある）：更新の状況が変わった */
    @Override
    public void onUpdateStateChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(overlay ? "許可済み" : "未許可（このままではロック画面に出せません）");
        overlayStatus.setTextColor(overlay ? OK_COLOR : WARN_COLOR);

        boolean enabled = Prefs.isEnabled(this);
        enabledStatus.setText(enabled ? "動作中" : "停止中");
        enabledStatus.setTextColor(enabled ? OK_COLOR : WARN_COLOR);
        toggleButton.setText(enabled ? "停止する" : "開始する");

        clockStatus.setText(Prefs.showClock(this) ? "表示する" : "表示しない");

        boolean isPhoto = Prefs.getBackground(this) == Prefs.BG_PHOTO;
        boolean hasPhoto = Backgrounds.hasPhoto(this);
        photoHint.setText(isPhoto && !hasPhoto ? "写真が未選択です。「写真を選ぶ」から選んでください。"
                : (isPhoto ? "写真の明るさは、見やすいように少し暗めにしてあります。" : ""));

        fontStatus.setText(Fonts.hasMemoFont(this) ? "読み込んだフォントを使用中" : "標準のフォント");

        // アプリの更新
        versionLabel.setText("バージョン " + Updater.currentVersionName(this));
        boolean configured = Updater.isConfigured(this);
        boolean auto = Prefs.autoUpdate(this);
        boolean canInstall = Updater.canInstall(this);
        StringBuilder st = new StringBuilder();
        if (!configured) {
            st.append("このビルドは自動更新に対応していません（GitHub で作られた版ではありません）");
        } else {
            st.append(auto ? "自動更新: オン" : "自動更新: オフ");
            st.append("　／　今のバージョン ").append(Updater.currentVersionName(this));
            String status = Prefs.getUpdateStatus(this);
            if (status.length() > 0) st.append("\n").append(status);
            if (!canInstall) st.append("\nこのアプリによる更新のインストールが、まだ許可されていません");
        }
        updateStatus.setText(st.toString());
        updateToggle.setText(auto ? "自動更新をオフにする" : "自動更新をオンにする");
        updateInstallButton.setVisibility(Updater.hasPendingConfirm() ? View.VISIBLE : View.GONE);
        updateAllowButton.setVisibility(configured && !canInstall ? View.VISIBLE : View.GONE);
    }

    // ---------- 背景グリッド ----------

    private void buildGrid() {
        bgGrid.removeAllViews();
        int selected = Prefs.getBackground(this);
        int cols = 4;
        LinearLayout row = null;
        for (int idx = 0; idx < BG_ORDER.length; idx++) {
            if (idx % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                bgGrid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(makeThumbCell(BG_ORDER[idx], BG_NAMES[idx], BG_ORDER[idx] == selected));
        }
        // 端数をうめて左寄せを保つ
        int rem = BG_ORDER.length % cols;
        if (rem != 0 && row != null) {
            for (int k = 0; k < cols - rem; k++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                row.addView(spacer, lp);
            }
        }
    }

    private View makeThumbCell(final int bg, String name, boolean selected) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(4), dp(4), dp(4));
        cell.setClickable(true);
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (bg == Prefs.BG_PHOTO && !Backgrounds.hasPhoto(MainActivity.this)) {
                    pickPhoto();
                    return;
                }
                Prefs.setBackground(MainActivity.this, bg);
                buildGrid();
                refresh();
            }
        });

        ImageView iv = new ImageView(this);
        float density = getResources().getDisplayMetrics().density;
        Bitmap thumb = Backgrounds.makeThumb(this, bg, dp(66), dp(116), density);
        iv.setImageBitmap(thumb);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(10));
        frame.setStroke(dp(selected ? 3 : 1), selected ? ACCENT : 0xFFD5D7DF);
        iv.setBackground(frame);
        iv.setPadding(dp(2), dp(2), dp(2), dp(2));
        cell.addView(iv, new LinearLayout.LayoutParams(dp(70), dp(120)));

        TextView label = text(name, 11, selected ? ACCENT : 0xFF55555F);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        cell.addView(label, lp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(clp);
        return cell;
    }

    // ---------- 写真の取り込み ----------

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "写真を選ぶ"), REQ_PHOTO);
        } catch (Exception e) {
            // 画像を選べるアプリが無い場合は何もしない
        }
    }

    /** 切り取り画面を開く。元の写真がまだ無ければ、先に写真を選んでもらう */
    private void openCrop() {
        File source = Backgrounds.photoSourceFile(this);
        boolean hasSource = source.exists() && source.length() > 0;
        if (!hasSource && !Backgrounds.hasPhoto(this)) {
            pickPhoto();
            return;
        }
        startActivity(new Intent(this, CropActivity.class));
    }

    private void pickFont() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*"); // フォントは端末によって種類の名前がまちまちなので、すべてのファイルから選べるようにする
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "フォントファイルを選ぶ"), REQ_FONT);
        } catch (Exception e) {
            // ファイルを選べるアプリが無い場合は何もしない
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FONT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            boolean ok = Fonts.importMemoFont(this, data.getData());
            refresh();
            if (!ok) fontStatus.setText("フォントとして読み込めませんでした（.ttf か .otf のファイルを選んでください）");
            return;
        }
        if (requestCode == REQ_PHOTO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (importSource(data.getData())) {
                // 取り込めたら、切り取る範囲を決める画面へ
                startActivity(new Intent(this, CropActivity.class));
            } else {
                photoHint.setText("写真を読み込めませんでした。別の写真でお試しください。");
            }
        }
    }

    /** 選んだ画像を、向きを直し、縮小して「元の写真」として内部ストレージに保存する */
    private boolean importSource(Uri uri) {
        try {
            // まずサイズだけ読む
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in0 = getContentResolver().openInputStream(uri);
            try {
                BitmapFactory.decodeStream(in0, null, bounds);
            } finally {
                if (in0 != null) in0.close();
            }
            int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
            if (longEdge <= 0) return false;

            // 拡大しても粗くなりにくいよう、長い辺 2400px 程度まで残す
            int sample = 1;
            while (longEdge / sample > 2400) sample *= 2;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            Bitmap bmp;
            InputStream in = getContentResolver().openInputStream(uri);
            try {
                bmp = BitmapFactory.decodeStream(in, null, opt);
            } finally {
                if (in != null) in.close();
            }
            if (bmp == null) return false;

            bmp = uprightBitmap(bmp, readOrientation(uri));

            File dst = Backgrounds.photoSourceFile(this);
            File tmp = new File(getFilesDir(), Prefs.PHOTO_SOURCE_FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
                out.flush();
            } finally {
                out.close();
            }
            bmp.recycle();
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写真に記録されている向き（縦持ち・横持ちなど）を読む */
    private int readOrientation(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(in);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable t) {
            return ExifInterface.ORIENTATION_NORMAL;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 記録された向きに合わせて、画像を正しい向きに直す */
    private Bitmap uprightBitmap(Bitmap bmp, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return bmp;
        }
        try {
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (Throwable t) {
            return bmp; // メモリ不足などのときは、そのままの向きで使う
        }
    }

    // ---------- 開始・停止と許可 ----------

    private void toggleService() {
        if (Prefs.isEnabled(this)) {
            Prefs.setEnabled(this, false);
            stopService(new Intent(this, LockMemoService.class));
        } else {
            if (!Settings.canDrawOverlays(this)) {
                openOverlaySettings();
                return;
            }
            Prefs.setEnabled(this, true);
            LockMemoService.start(this);
        }
        refresh();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    /** 「この提供元のアプリを許可」の設定を開く（自動更新のインストールに必要） */
    private void openInstallPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
        }
    }

    // ---------- 部品 ----------

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 17, 0xFF14141A);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFF14141A);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(primary ? ACCENT : 0xFFFFFFFF);
        if (!primary) bg.setStroke(dp(1), 0xFFD5D7DF);
        b.setBackground(bg);
        b.setClickable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout.LayoutParams margin(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
