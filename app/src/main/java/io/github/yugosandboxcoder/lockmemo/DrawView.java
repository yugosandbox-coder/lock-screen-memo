package io.github.yugosandboxcoder.lockmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 手書き・文字・消しゴムをすべて 1 枚の透明ビットマップに描き込むキャンバス。
 * ビットマップはアプリ内部ストレージに PNG で保存され、次回もそのまま残る。
 */
public class DrawView extends View {

    public static final int TOOL_NONE = 0;   // 閲覧のみ（誤タッチ防止）
    public static final int TOOL_PEN = 1;
    public static final int TOOL_TEXT = 2;
    public static final int TOOL_ERASER = 3;

    public interface Listener {
        /** 文字ツールで置き場所がタップされた */
        void onTextPositionPicked();
    }

    private static final String FILE_NAME = "note.png";
    private static final float[] PEN_WIDTH_DP = {2.5f, 5f, 9f};
    private static final float[] ERASER_WIDTH_DP = {16f, 30f, 52f};

    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private Bitmap undoBitmap;
    private Canvas undoCanvas;
    private boolean hasUndo;
    private boolean dirty;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint penPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint copyPaint = new Paint();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private int tool = TOOL_NONE;
    private int color = Color.WHITE;
    private int sizeLevel = 1;                       // ペン・消しゴムの太さ
    private float textSizeSp = Prefs.TEXT_SIZE_DEFAULT; // 文字の大きさ（太さとは別に決める）

    private float lastX;
    private float lastY;
    private boolean stroking;

    private String pendingText = "";
    private float textX;
    private float textY;
    private boolean hasTextPos;

    private Listener listener;
    private boolean textShadow;

    public DrawView(Context context) {
        super(context);

        penPaint.setStyle(Paint.Style.STROKE);
        penPaint.setStrokeCap(Paint.Cap.ROUND);
        penPaint.setStrokeJoin(Paint.Join.ROUND);

        eraserPaint.setStyle(Paint.Style.STROKE);
        eraserPaint.setStrokeCap(Paint.Cap.ROUND);
        eraserPaint.setStrokeJoin(Paint.Join.ROUND);
        eraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(dp(1.5f));
        cursorPaint.setColor(0xAAFFFFFF);

        copyPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
    }

    // ---------- 外部から呼ぶ操作 ----------

    public void setListener(Listener l) {
        listener = l;
    }

    public void setTool(int newTool) {
        if (tool == TOOL_TEXT && newTool != TOOL_TEXT) {
            commitText();
        }
        tool = newTool;
        stroking = false;
        invalidate();
    }

    public int getTool() {
        return tool;
    }

    public void setColor(int c) {
        color = c;
        invalidate();
    }

    public void setSizeLevel(int level) {
        sizeLevel = Math.max(0, Math.min(2, level));
        invalidate();
    }

    /**
     * 文字に使うフォント。null なら端末の標準フォント。
     * フォントに無い文字は、端末の標準フォントで自動的に補われる。
     */
    public void setTextTypeface(Typeface tf) {
        textPaint.setTypeface(tf);
        invalidate();
    }

    /** 文字の大きさ（sp）。入力中の文字にもすぐ反映される */
    public void setTextSizeSp(float sp) {
        textSizeSp = Math.max(Prefs.TEXT_SIZE_MIN, Math.min(Prefs.TEXT_SIZE_MAX, sp));
        invalidate();
    }

    /** 文字に影を付けるか（写真の背景で読みやすくするため。紙の背景では付けない） */
    public void setTextShadow(boolean on) {
        textShadow = on;
        invalidate();
    }

    public void setPendingText(String text) {
        pendingText = text == null ? "" : text;
        invalidate();
    }

    public boolean hasTextPosition() {
        return hasTextPos;
    }

    /** 入力中の文字をキャンバスに確定する */
    public void commitText() {
        if (bitmap != null && hasTextPos && pendingText.trim().length() > 0) {
            saveUndo();
            drawPendingText(bitmapCanvas);
            dirty = true;
        }
        pendingText = "";
        hasTextPos = false;
        invalidate();
    }

    /** 入力中の文字を破棄する */
    public void cancelText() {
        pendingText = "";
        hasTextPos = false;
        invalidate();
    }

    /** 全部消す */
    public void clearAll() {
        if (bitmap == null) return;
        saveUndo();
        bitmap.eraseColor(Color.TRANSPARENT);
        dirty = true;
        invalidate();
    }

    public boolean canUndo() {
        return hasUndo;
    }

    /** 直前の 1 操作を取り消す */
    public void undo() {
        if (!hasUndo || bitmap == null || undoBitmap == null) return;
        bitmapCanvas.drawBitmap(undoBitmap, 0, 0, copyPaint);
        hasUndo = false;
        dirty = true;
        invalidate();
    }

    /** 「戻す」用の控えを捨ててメモリを空ける */
    public void releaseUndo() {
        if (undoBitmap != null) undoBitmap.recycle();
        undoBitmap = null;
        undoCanvas = null;
        hasUndo = false;
    }

    /** 変更があれば内部ストレージに保存する */
    public void save() {
        if (!dirty || bitmap == null) return;
        File dir = getContext().getFilesDir();
        File tmp = new File(dir, FILE_NAME + ".tmp");
        File dst = new File(dir, FILE_NAME);
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();
            } finally {
                out.close();
            }
            if (tmp.renameTo(dst)) {
                dirty = false;
            }
        } catch (Exception e) {
            // 保存に失敗しても落とさない（次回の onPause で再挑戦する）
        }
    }

    // ---------- 描画 ----------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        Bitmap old = bitmap;
        if (old == null) {
            File f = new File(getContext().getFilesDir(), FILE_NAME);
            if (f.exists()) {
                try {
                    old = BitmapFactory.decodeFile(f.getAbsolutePath());
                } catch (Throwable t) {
                    old = null;
                }
            }
        }
        if (old != null && old == bitmap && old.getWidth() >= w && old.getHeight() >= h) {
            return; // 今のビットマップで足りている
        }

        // 画面サイズが変わっても書いた内容を切り落とさないよう、大きい方に合わせる
        int newW = old == null ? w : Math.max(w, old.getWidth());
        int newH = old == null ? h : Math.max(h, old.getHeight());
        Bitmap created = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(created);
        if (old != null) {
            c.drawBitmap(old, 0, 0, null);
            old.recycle();
        }
        bitmap = created;
        bitmapCanvas = c;
        undoBitmap = null;
        undoCanvas = null;
        hasUndo = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0, 0, bitmapPaint);
        }
        if (tool == TOOL_TEXT && hasTextPos) {
            if (pendingText.length() > 0) {
                drawPendingText(canvas);
            }
            // 文字の置き場所を示すカーソル
            float size = textSizePx();
            canvas.drawLine(textX - dp(3), textY - size / 2f, textX - dp(3), textY + size / 2f, cursorPaint);
        }
        if (tool == TOOL_ERASER && stroking) {
            canvas.drawCircle(lastX, lastY, dp(ERASER_WIDTH_DP[sizeLevel]) / 2f, cursorPaint);
        }
    }

    private void drawPendingText(Canvas c) {
        textPaint.setColor(color);
        textPaint.setTextSize(textSizePx());
        if (textShadow) {
            // 写真の上でも読めるように薄い影を付ける（暗い文字には白、明るい文字には黒の影）
            boolean darkInk = Color.red(color) + Color.green(color) + Color.blue(color) < 300;
            textPaint.setShadowLayer(dp(2), 0, dp(1), darkInk ? 0xAAFFFFFF : 0xAA000000);
        } else {
            textPaint.clearShadowLayer();
        }
        int width = Math.max((int) (getWidth() - textX - dp(8)), (int) dp(80));
        StaticLayout layout = StaticLayout.Builder
                .obtain(pendingText, 0, pendingText.length(), textPaint, width)
                .build();
        c.save();
        c.translate(textX, textY - textSizePx() / 2f);
        layout.draw(c);
        c.restore();
    }

    // ---------- タッチ ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (tool == TOOL_NONE || bitmap == null) return false;

        float x = e.getX();
        float y = e.getY();
        int action = e.getActionMasked();

        if (tool == TOOL_TEXT) {
            // タップ／ドラッグで文字の置き場所を決める（入力中も動かせる）
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                textX = x;
                textY = y;
                hasTextPos = true;
                invalidate();
            } else if (action == MotionEvent.ACTION_UP) {
                if (listener != null) listener.onTextPositionPicked();
            }
            return true;
        }

        Paint p = strokePaint();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                saveUndo();
                stroking = true;
                lastX = x;
                lastY = y;
                bitmapCanvas.drawPoint(x, y, p);
                dirty = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!stroking) break;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    segmentTo(e.getHistoricalX(i), e.getHistoricalY(i), p);
                }
                segmentTo(x, y, p);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stroking = false;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void segmentTo(float x, float y, Paint p) {
        bitmapCanvas.drawLine(lastX, lastY, x, y, p);
        lastX = x;
        lastY = y;
    }

    private Paint strokePaint() {
        if (tool == TOOL_ERASER) {
            eraserPaint.setStrokeWidth(dp(ERASER_WIDTH_DP[sizeLevel]));
            return eraserPaint;
        }
        penPaint.setColor(color);
        penPaint.setStrokeWidth(dp(PEN_WIDTH_DP[sizeLevel]));
        return penPaint;
    }

    // ---------- 補助 ----------

    private void saveUndo() {
        if (bitmap == null) return;
        try {
            if (undoBitmap == null
                    || undoBitmap.getWidth() != bitmap.getWidth()
                    || undoBitmap.getHeight() != bitmap.getHeight()) {
                undoBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                undoCanvas = new Canvas(undoBitmap);
            }
            undoCanvas.drawBitmap(bitmap, 0, 0, copyPaint);
            hasUndo = true;
        } catch (OutOfMemoryError oom) {
            undoBitmap = null;
            undoCanvas = null;
            hasUndo = false;
        }
    }

    private float textSizePx() {
        return textSizeSp * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
