package io.github.yugosandboxcoder.lockmemo;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * ロック画面の上に出る自前のメモ画面（ユーザー補助は使わない方式）。
 * 選んだ背景・時計・メモを、画面の端から端まで全体に描く。
 * 道具のパレットは浮かせてあり、つまみをドラッグして好きな場所へ動かせるので、
 * 画面の下の方まで書ける。
 */
public class NoteActivity extends Activity implements DrawView.Listener {

    private static final int[] COLORS = {
            0xFF222228, 0xFFFFFFFF, 0xFFFF6B6B, 0xFF3D8BFF, 0xFF2BB673, 0xFFFFC53D};
    private static final String[] SIZE_LABELS = {"細い", "ふつう", "太い"};
    private static final int[] SIZE_ICONS = {R.drawable.ic_size_1, R.drawable.ic_size_2, R.drawable.ic_size_3};

    private static WeakReference<NoteActivity> current;

    /** 画面が消えた／解除された：開いていれば保存して閉じる */
    public static void finishNote() {
        NoteActivity a = current != null ? current.get() : null;
        if (a != null) a.finishAndSave();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private BackgroundView background;
    private ClockView clock;
    private DrawView drawView;
    private ImageView penButton;
    private View unlockButton;
    private LinearLayout palette;
    private View textPanel;
    private EditText editText;
    private TextView textSizeLabel;
    private TextView sizeMarkSmall;
    private TextView sizeMarkLarge;
    private Typeface appliedFont;
    private boolean fontApplied;

    private ToolButton btnPen, btnText, btnEraser, btnColor, btnSize, btnClear;

    private int styledBg = -1;      // 見た目を合わせ済みの背景の種類
    private int colorIndex = 0;
    private int sizeLevel = 1;
    private boolean editing;
    private boolean textEntry;
    private boolean clearArmed;

    // ステータスバー・ナビゲーションバーの高さ（その下にボタンが隠れないようにする）
    private int insetTop;
    private int insetBottom;

    // パレットの位置（0〜1。動かせる範囲の中での割合）と、ドラッグ中のずれ
    private float paletteFx;
    private float paletteFy;
    private float dragDx;
    private float dragDy;

    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (clock != null) clock.invalidate();
        }
    };

    private final Runnable disarmClear = new Runnable() {
        @Override
        public void run() {
            clearArmed = false;
            btnClear.label.setText("全消去");
            btnClear.setHighlighted(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ロック画面の上に表示する（画面の点灯はこちらでは行わない＝消灯中に先に用意しておくため）
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }

        paletteFx = Prefs.getPaletteX(this);
        paletteFy = Prefs.getPaletteY(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        background = new BackgroundView(this);
        root.addView(background, matchParent());

        clock = new ClockView(this);
        root.addView(clock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        drawView = new DrawView(this);
        drawView.setListener(this);
        drawView.setSizeLevel(sizeLevel);
        drawView.setTextSizeSp(Prefs.getTextSize(this));
        root.addView(drawView, matchParent());

        unlockButton = buildUnlockButton();
        root.addView(unlockButton, new FrameLayout.LayoutParams(dp(52), dp(52),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        penButton = buildPenButton();
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(50), dp(50),
                Gravity.BOTTOM | Gravity.END);
        plp.rightMargin = dp(20);
        root.addView(penButton, plp);

        palette = buildPalette();
        palette.setVisibility(View.INVISIBLE);
        root.addView(palette, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));

        textPanel = buildTextPanel();
        textPanel.setVisibility(View.GONE);
        root.addView(textPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 画面の端（ステータスバーの下・ナビゲーションバーの下）まで描く
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                applyInsets();
                return insets;
            }
        });
        // パレットや画面の大きさが決まったら、保存してある位置へ置く
        View.OnLayoutChangeListener relayout = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                placePalette();
            }
        };
        root.addOnLayoutChangeListener(relayout);
        palette.addOnLayoutChangeListener(relayout);

        applyInsets();
        applyBackgroundStyle();
        setContentView(root);
        setEditMode(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 画面を点けるたびに、必ず閲覧モードで最新の背景を表示する
        if (editing) setEditMode(false);
        applyBackgroundStyle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        applyBackgroundStyle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(timeTick);
        } catch (Exception ignored) {
        }
        if (editing) setEditMode(false);
        drawView.save();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current != null && current.get() == this) current = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (textEntry) {
            endTextEntry(true);
        } else if (editing) {
            setEditMode(false);
        } else {
            // ロック画面に戻る（メモを閉じる）
            finishAndSave();
        }
    }

    private void finishAndSave() {
        if (editing) setEditMode(false);
        drawView.save();
        finish();
        overridePendingTransition(0, 0);
    }

    // ---------- 背景に合わせた見た目 ----------

    /** 背景・時計の設定を読み直し、変わっていれば色などを合わせる */
    private void applyBackgroundStyle() {
        background.reload();
        int bg = Prefs.getBackground(this);

        clock.setVisibility(Prefs.showClock(this) ? View.VISIBLE : View.GONE);
        clock.invalidate();

        // メモの文字フォント（設定画面で読み込んだもの。無ければ標準）。変わったときだけ付け替える
        Typeface font = Fonts.memoTypeface(this);
        if (!fontApplied || font != appliedFont) {
            fontApplied = true;
            appliedFont = font;
            drawView.setTextTypeface(font);
            editText.setTypeface(font);
            sizeMarkSmall.setTypeface(font);
            sizeMarkLarge.setTypeface(font);
        }

        if (bg == styledBg) return;
        styledBg = bg;

        clock.setInk(Backgrounds.clockInk(bg));
        // 写真の上では文字に影を付けて読みやすくする（紙の上では付けない）
        drawView.setTextShadow(bg == Prefs.BG_PHOTO);
        // 背景に合わせて最初のペン色を決める
        colorIndex = indexOfColor(Backgrounds.defaultInk(bg));
        drawView.setColor(COLORS[colorIndex]);
        styleColorButton();

        // 画面の端まで描き、明るい背景ではステータスバーのアイコンを黒くする
        Window w = getWindow();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Backgrounds.isLight(bg)) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 28) {
            // カメラの切り欠きのまわりにも背景を描く
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // システムが自動で付ける半透明の帯をやめる
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
    }

    /** システムのバーにボタン類が隠れないよう、余白を取り直す */
    private void applyInsets() {
        FrameLayout.LayoutParams clp = (FrameLayout.LayoutParams) clock.getLayoutParams();
        clp.topMargin = insetTop + dp(40);
        clock.setLayoutParams(clp);

        FrameLayout.LayoutParams ulp = (FrameLayout.LayoutParams) unlockButton.getLayoutParams();
        ulp.bottomMargin = insetBottom + dp(24);
        unlockButton.setLayoutParams(ulp);

        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) penButton.getLayoutParams();
        plp.bottomMargin = insetBottom + dp(88);
        penButton.setLayoutParams(plp);

        textPanel.setPadding(dp(10), insetTop + dp(8), dp(6), dp(8));
        placePalette();
    }

    // ---------- 浮かせた道具パレット ----------

    private float paletteMinX() {
        return dp(6);
    }

    private float paletteMaxX() {
        return Math.max(paletteMinX(), root.getWidth() - palette.getWidth() - dp(6));
    }

    private float paletteMinY() {
        return insetTop + dp(6);
    }

    private float paletteMaxY() {
        return Math.max(paletteMinY(), root.getHeight() - insetBottom - palette.getHeight() - dp(6));
    }

    /** 保存してある割合の位置へパレットを置く（画面からはみ出さない） */
    private void placePalette() {
        if (root == null || palette == null) return;
        if (root.getWidth() == 0 || root.getHeight() == 0
                || palette.getWidth() == 0 || palette.getHeight() == 0) {
            return;
        }
        palette.setX(paletteMinX() + (paletteMaxX() - paletteMinX()) * paletteFx);
        palette.setY(paletteMinY() + (paletteMaxY() - paletteMinY()) * paletteFy);
    }

    /** ドラッグ中：指の位置へパレットを動かし、その位置を割合として覚える */
    private void dragPaletteTo(float x, float y) {
        float minX = paletteMinX();
        float maxX = paletteMaxX();
        float minY = paletteMinY();
        float maxY = paletteMaxY();
        x = Math.max(minX, Math.min(maxX, x));
        y = Math.max(minY, Math.min(maxY, y));
        palette.setX(x);
        palette.setY(y);
        paletteFx = maxX > minX ? (x - minX) / (maxX - minX) : 0f;
        paletteFy = maxY > minY ? (y - minY) / (maxY - minY) : 0f;
    }

    private LinearLayout buildPalette() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF01A1A20);
        bg.setStroke(dp(1), 0x33FFFFFF);
        box.setBackground(bg);
        box.setPadding(dp(6), 0, dp(6), dp(6));
        box.setElevation(dp(6));
        box.setClickable(true); // パレットの下へタッチが抜けて線が引かれないようにする

        // つまみ：ここをドラッグするとパレットが動く
        LinearLayout grip = new LinearLayout(this);
        grip.setGravity(Gravity.CENTER);
        View pill = new View(this);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(3));
        pillBg.setColor(0xAAFFFFFF);
        pill.setBackground(pillBg);
        grip.addView(pill, new LinearLayout.LayoutParams(dp(40), dp(5)));
        grip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragDx = palette.getX() - e.getRawX();
                        dragDy = palette.getY() - e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        dragPaletteTo(e.getRawX() + dragDx, e.getRawY() + dragDy);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        Prefs.setPalettePos(NoteActivity.this, paletteFx, paletteFy);
                        return true;
                    default:
                        return false;
                }
            }
        });
        box.addView(grip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        btnPen = new ToolButton(R.drawable.ic_pen, "ペン", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTool(DrawView.TOOL_PEN);
            }
        });
        btnText = new ToolButton(R.drawable.ic_text, "文字", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTool(DrawView.TOOL_TEXT);
            }
        });
        btnEraser = new ToolButton(R.drawable.ic_eraser, "消しゴム", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTool(DrawView.TOOL_ERASER);
            }
        });
        btnColor = new ToolButton(0, "色", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                colorIndex = (colorIndex + 1) % COLORS.length;
                drawView.setColor(COLORS[colorIndex]);
                styleColorButton();
            }
        });
        btnSize = new ToolButton(SIZE_ICONS[sizeLevel], SIZE_LABELS[sizeLevel], new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sizeLevel = (sizeLevel + 1) % SIZE_LABELS.length;
                drawView.setSizeLevel(sizeLevel);
                btnSize.icon.setImageResource(SIZE_ICONS[sizeLevel]);
                btnSize.label.setText(SIZE_LABELS[sizeLevel]);
            }
        });
        ToolButton undo = new ToolButton(R.drawable.ic_undo, "戻す", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                drawView.undo();
            }
        });
        btnClear = new ToolButton(R.drawable.ic_trash, "全消去", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClearPressed();
            }
        });
        ToolButton done = new ToolButton(R.drawable.ic_check, "完了", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEditMode(false);
            }
        });
        done.setHighlighted(true);

        // 2 列 × 4 段の小さなパレット
        LinearLayout[] rows = {
                paletteRow(btnPen, btnText),
                paletteRow(btnEraser, btnColor),
                paletteRow(btnSize, undo),
                paletteRow(btnClear, done)};
        for (LinearLayout row : rows) {
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    private LinearLayout paletteRow(View a, View b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(dp(54), dp(50));
        la.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(a, la);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(dp(54), dp(50));
        lb.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(b, lb);
        return row;
    }

    // ---------- そのほかの部品 ----------

    private ImageView buildPenButton() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        return makeIconButton(R.drawable.ic_pen, bg, 0xFFFFFFFF, 13, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEditMode(true);
            }
        });
    }

    private View buildUnlockButton() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x4D000000);
        return makeIconButton(R.drawable.ic_unlock, bg, 0xFFFFFFFF, 14, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unlock();
            }
        });
    }

    private View buildTextPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF22A2A33);
        panel.setClickable(true);

        // 1 段目：入力欄と 決定／取り消し
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        editText = new EditText(this);
        editText.setHint("文字を入力（置き場所はドラッグで動かせます）");
        editText.setHintTextColor(0xFF8A8A98);
        editText.setTextColor(Color.WHITE);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMaxLines(3);
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                drawView.setPendingText(s.toString());
            }
        });
        inputRow.addView(editText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView ok = makeIconButton(R.drawable.ic_check, roundRect(0xFFFFE066), 0xFF101014, 10,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        endTextEntry(true);
                    }
                });
        inputRow.addView(ok, new LinearLayout.LayoutParams(dp(56), dp(44)));

        ImageView cancel = makeIconButton(R.drawable.ic_close, roundRect(0xFF2C2C36), 0xFFF0F0F5, 11,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        endTextEntry(false);
                    }
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(6);
        inputRow.addView(cancel, lp);
        panel.addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2 段目：文字の大きさ（動かすと、画面上の文字がその場で変わる）
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);

        sizeMarkSmall = sizeMark("あ", 13);
        sizeRow.addView(sizeMarkSmall, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));

        final int steps = Math.round(Prefs.TEXT_SIZE_MAX - Prefs.TEXT_SIZE_MIN);
        SeekBar bar = new SeekBar(this);
        bar.setMax(steps);
        bar.setProgress(Math.round(Prefs.getTextSize(this) - Prefs.TEXT_SIZE_MIN));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                float sp = Prefs.TEXT_SIZE_MIN + progress;
                drawView.setTextSizeSp(sp);
                textSizeLabel.setText(String.valueOf(Math.round(sp)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                // 次に書くときも同じ大きさで始められるよう覚えておく
                Prefs.setTextSize(NoteActivity.this, Prefs.TEXT_SIZE_MIN + s.getProgress());
            }
        });
        sizeRow.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1f));

        sizeMarkLarge = sizeMark("あ", 24);
        sizeRow.addView(sizeMarkLarge, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));

        textSizeLabel = sizeMark(String.valueOf(Math.round(Prefs.getTextSize(this))), 13);
        textSizeLabel.setTextColor(0xFFFFE066);
        sizeRow.addView(textSizeLabel, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(sizeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private TextView sizeMark(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFE8E8EE);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private ImageView makeIconButton(int iconRes, Drawable background, int tint, int paddingDp,
                                     View.OnClickListener onClick) {
        ImageView b = new ImageView(this);
        b.setImageResource(iconRes);
        b.setColorFilter(tint);
        b.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        b.setBackground(background);
        b.setClickable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    private GradientDrawable roundRect(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(color);
        return bg;
    }

    /** 色ボタンのアイコンは、現在のペン色で塗った丸 */
    private void styleColorButton() {
        if (btnColor == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(COLORS[colorIndex]);
        dot.setStroke(dp(1.5f), 0xFF9A9AA8);
        btnColor.icon.setImageDrawable(dot);
    }

    // ---------- 動作 ----------

    private void setEditMode(boolean edit) {
        if (edit) {
            editing = true;
            penButton.setVisibility(View.GONE);
            unlockButton.setVisibility(View.GONE);
            palette.setVisibility(View.VISIBLE);
            placePalette();
            selectTool(DrawView.TOOL_PEN);
        } else {
            editing = false;
            endTextEntry(true);
            handler.removeCallbacks(disarmClear);
            disarmClear.run();
            drawView.setTool(DrawView.TOOL_NONE);
            palette.setVisibility(View.INVISIBLE);
            penButton.setVisibility(View.VISIBLE);
            unlockButton.setVisibility(View.VISIBLE);
            drawView.save();
        }
    }

    private void selectTool(int tool) {
        if (drawView.getTool() == DrawView.TOOL_TEXT && tool != DrawView.TOOL_TEXT) {
            endTextEntry(true);
        }
        drawView.setTool(tool);
        btnPen.setHighlighted(tool == DrawView.TOOL_PEN);
        btnText.setHighlighted(tool == DrawView.TOOL_TEXT);
        btnEraser.setHighlighted(tool == DrawView.TOOL_ERASER);
    }

    /** DrawView から：文字の置き場所がタップされた */
    @Override
    public void onTextPositionPicked() {
        if (textEntry) return;
        textEntry = true;
        textPanel.setVisibility(View.VISIBLE);
        editText.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
    }

    /** 文字入力を終える。commit = true ならキャンバスに書き込む */
    private void endTextEntry(boolean commit) {
        if (commit) {
            drawView.commitText();
        } else {
            drawView.cancelText();
        }
        if (!textEntry) return;
        textEntry = false;
        editText.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
        editText.clearFocus();
        textPanel.setVisibility(View.GONE);
    }

    /** 全消去は 2 回押しで実行（誤操作防止） */
    private void onClearPressed() {
        handler.removeCallbacks(disarmClear);
        if (clearArmed) {
            endTextEntry(false);
            drawView.clearAll();
            disarmClear.run();
        } else {
            clearArmed = true;
            btnClear.label.setText("もう一度");
            btnClear.setHighlighted(true);
            handler.postDelayed(disarmClear, 3000);
        }
    }

    private void unlock() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        drawView.save();
        if (km == null || !km.isKeyguardLocked()) {
            finish();
            return;
        }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                finish();
            }
        });
    }

    private int indexOfColor(int color) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == color) return i;
        return 0;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** パレットのボタン：自作の線画アイコン＋小さなラベル */
    private class ToolButton extends LinearLayout {
        final ImageView icon;
        final TextView label;
        private final boolean tintIcon;

        ToolButton(int iconRes, String text, View.OnClickListener onClick) {
            super(NoteActivity.this);
            tintIcon = iconRes != 0;
            setOrientation(LinearLayout.VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setOnClickListener(onClick);

            icon = new ImageView(NoteActivity.this);
            if (iconRes != 0) icon.setImageResource(iconRes);
            addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

            label = new TextView(NoteActivity.this);
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(1);
            addView(label, lp);

            setHighlighted(false);
        }

        /** on = 選択中／強調 */
        void setHighlighted(boolean on) {
            setBackground(roundRect(on ? 0xFFFFE066 : 0xFF2C2C36));
            int fg = on ? 0xFF101014 : 0xFFF0F0F5;
            label.setTextColor(fg);
            if (tintIcon) icon.setColorFilter(fg);
        }
    }
}
