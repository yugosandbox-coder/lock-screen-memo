package io.github.yugosandboxcoder.lockmemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Calendar;

/**
 * ロック画面の時計。数字は同梱フォント「Auxiliary line」（補助線が残ったデザイン、
 * 作者: とりしっぽ、SIL OFL 1.1）で描く。日付は読みやすさ優先で端末のフォント。
 */
public class ClockView extends View {

    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private int ink = Color.WHITE;

    public ClockView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;

        Typeface aux;
        try {
            aux = getResources().getFont(R.font.auxiliary_line);
        } catch (Throwable t) {
            aux = Typeface.create("sans-serif", Typeface.BOLD);
        }
        digitPaint.setTypeface(aux);
        digitPaint.setStyle(Paint.Style.FILL);
        digitPaint.setTextAlign(Paint.Align.LEFT);

        datePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        datePaint.setTextSize(15 * density);
    }

    public void setInk(int color) {
        ink = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round(260 * density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int hour = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && hour == 0) hour = 12;
        String hh = pad(hour);
        String mm = pad(now.get(Calendar.MINUTE));

        float left = 22 * density;

        datePaint.setColor(ink);
        datePaint.setAlpha(230);
        CharSequence date = DateFormat.format("M月d日(E)", now);
        canvas.drawText(date.toString(), left, 42 * density, datePaint);

        digitPaint.setColor(ink);
        digitPaint.setTextSize(92 * density);
        float row = 90 * density;        // 時と分の行間
        float base1 = 150 * density;     // 時のベースライン
        canvas.drawText(hh, left, base1, digitPaint);
        canvas.drawText(mm, left, base1 + row, digitPaint);
    }

    private static String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
