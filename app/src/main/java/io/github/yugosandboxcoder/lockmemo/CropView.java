package io.github.yugosandboxcoder.lockmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 写真の切り取り位置を決めるビュー。
 * 画面と同じ縦横比の枠が中央にあり、写真を 2 本指で拡大・縮小、ドラッグで移動できる。
 * 枠の中に見えている部分が、そのままロック画面の背景になる。
 */
public class CropView extends View {

    private static final float MAX_ZOOM = 8f; // 枠いっぱいの状態から最大 8 倍まで

    private Bitmap src;
    private final Matrix matrix = new Matrix();      // 写真 → 画面 の変換
    private final RectF frame = new RectF();         // 切り取り枠（画面上の位置）
    private final RectF mapped = new RectF();
    private final float[] values = new float[9];

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float aspect = 9f / 20f; // 枠の 幅 ÷ 高さ
    private int reserveTop;
    private int reserveBottom;
    private float minScale = 1f;
    private float maxScale = MAX_ZOOM;

    private float lastX;
    private float lastY;
    private final ScaleGestureDetector scaleDetector;

    public CropView(Context c) {
        super(c);
        float density = getResources().getDisplayMetrics().density;

        dimPaint.setColor(0xB3000000);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(2f * density);
        framePaint.setColor(Color.WHITE);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(Math.max(1f, density * 0.75f));
        guidePaint.setColor(0x66FFFFFF);

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    // ---------- 外部から呼ぶ操作 ----------

    public void setImage(Bitmap b) {
        src = b;
        resetImage();
    }

    /** 枠の縦横比（幅 ÷ 高さ）。ロック画面と同じ比率を渡す */
    public void setAspect(float widthOverHeight) {
        if (widthOverHeight > 0) aspect = widthOverHeight;
        computeFrame();
        resetImage();
    }

    /** 上下に置くボタンや説明のぶん、枠を置かない領域（px） */
    public void setReserved(int topPx, int bottomPx) {
        reserveTop = topPx;
        reserveBottom = bottomPx;
        computeFrame();
        resetImage();
    }

    /** 写真を、枠いっぱい・中央の最初の状態に戻す */
    public void resetImage() {
        if (src == null || frame.width() <= 0 || frame.height() <= 0) return;
        minScale = Math.max(frame.width() / src.getWidth(), frame.height() / src.getHeight());
        maxScale = minScale * MAX_ZOOM;
        matrix.reset();
        matrix.postScale(minScale, minScale);
        matrix.postTranslate(
                frame.left + (frame.width() - src.getWidth() * minScale) / 2f,
                frame.top + (frame.height() - src.getHeight() * minScale) / 2f);
        invalidate();
    }

    /** 枠の中に見えている部分を、指定の大きさの画像として取り出す */
    public Bitmap export(int outW, int outH) {
        if (src == null || frame.width() <= 0 || frame.height() <= 0) return null;
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        Matrix e = new Matrix(matrix);
        e.postTranslate(-frame.left, -frame.top);
        e.postScale(outW / frame.width(), outH / frame.height());
        c.drawBitmap(src, e, bitmapPaint);
        return out;
    }

    // ---------- 配置と描画 ----------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeFrame();
        resetImage();
    }

    private void computeFrame() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float margin = 16f * getResources().getDisplayMetrics().density;
        float availW = w - margin * 2f;
        float availH = h - reserveTop - reserveBottom;
        if (availW <= 0 || availH <= 0) return;

        float fw = availW;
        float fh = fw / aspect;
        if (fh > availH) {
            fh = availH;
            fw = fh * aspect;
        }
        float left = (w - fw) / 2f;
        float top = reserveTop + (availH - fh) / 2f;
        frame.set(left, top, left + fw, top + fh);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF101014);
        if (src != null) {
            canvas.drawBitmap(src, matrix, bitmapPaint);
        }
        if (frame.width() <= 0) return;

        // 枠の外を暗くする
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, frame.top, dimPaint);
        canvas.drawRect(0, frame.bottom, w, h, dimPaint);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, dimPaint);
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint);

        // 三分割の目安線と枠
        float x1 = frame.left + frame.width() / 3f;
        float x2 = frame.left + frame.width() * 2f / 3f;
        float y1 = frame.top + frame.height() / 3f;
        float y2 = frame.top + frame.height() * 2f / 3f;
        canvas.drawLine(x1, frame.top, x1, frame.bottom, guidePaint);
        canvas.drawLine(x2, frame.top, x2, frame.bottom, guidePaint);
        canvas.drawLine(frame.left, y1, frame.right, y1, guidePaint);
        canvas.drawLine(frame.left, y2, frame.right, y2, guidePaint);
        canvas.drawRect(frame, framePaint);
    }

    // ---------- 操作 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (src == null) return false;
        scaleDetector.onTouchEvent(e);

        int action = e.getActionMasked();
        // 指の中心点を求める（離れようとしている指は除く）。
        // 中心点の動きで移動するので、1 本指でも 2 本指でも同じように動かせる。
        int skip = action == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
        float sumX = 0;
        float sumY = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sumX += e.getX(i);
            sumY += e.getY(i);
            n++;
        }
        if (n == 0) return true;
        float cx = sumX / n;
        float cy = sumY / n;

        if (action == MotionEvent.ACTION_MOVE) {
            matrix.postTranslate(cx - lastX, cy - lastY);
            keepCovered();
            invalidate();
        }
        // 指の本数が変わった瞬間は、位置が飛ばないよう基準点だけ更新する
        lastX = cx;
        lastY = cy;
        return true;
    }

    private void zoomBy(float factor, float focusX, float focusY) {
        if (src == null || factor <= 0) return;
        float current = currentScale();
        if (current <= 0) return;
        float target = Math.max(minScale, Math.min(maxScale, current * factor));
        float f = target / current;
        matrix.postScale(f, f, focusX, focusY);
        keepCovered();
        invalidate();
    }

    private float currentScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    /** 写真が枠より内側に入り込んで、すき間ができないように位置を直す */
    private void keepCovered() {
        mapped.set(0, 0, src.getWidth(), src.getHeight());
        matrix.mapRect(mapped);
        float dx = 0;
        float dy = 0;
        if (mapped.left > frame.left) {
            dx = frame.left - mapped.left;
        } else if (mapped.right < frame.right) {
            dx = frame.right - mapped.right;
        }
        if (mapped.top > frame.top) {
            dy = frame.top - mapped.top;
        } else if (mapped.bottom < frame.bottom) {
            dy = frame.bottom - mapped.bottom;
        }
        if (dx != 0 || dy != 0) matrix.postTranslate(dx, dy);
    }
}
