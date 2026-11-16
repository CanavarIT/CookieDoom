package com.cookie.doom;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseBooleanArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal on-screen controls, drawn as thin translucent outlines.
 *
 *   left  40 %  floating stick  : forward / back / strafe, push to the rim = run
 *   right 60 %  drag anywhere   : turn
 *   FIRE ring   : fire - and you can keep dragging from it to turn while shooting
 *   USE, weapon prev/next, MAP, menu, [+] extras (Heretic / Hexen / Strife)
 *
 * While the engine menu is open the same widgets turn into a menu pad:
 *   stick = arrows (auto-repeat), ring = Enter, menu = Esc, plus Y / N chips.
 *
 * Input goes through SDLActivity.onNativeKey* / onNativeMouse, so the engine
 * sees ordinary keyboard + relative-mouse events (no engine changes needed).
 */
public final class TouchControlsView extends View {

    // ------------------------------------------------------------ keys ----
    private static final int K_FIRE = KeyEvent.KEYCODE_CTRL_RIGHT;
    private static final int K_USE = KeyEvent.KEYCODE_SPACE;
    private static final int K_RUN = KeyEvent.KEYCODE_SHIFT_RIGHT;
    private static final int K_FWD = KeyEvent.KEYCODE_DPAD_UP;
    private static final int K_BACK = KeyEvent.KEYCODE_DPAD_DOWN;
    private static final int K_LEFT = KeyEvent.KEYCODE_DPAD_LEFT;
    private static final int K_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    private static final int K_SLEFT = KeyEvent.KEYCODE_COMMA;
    private static final int K_SRIGHT = KeyEvent.KEYCODE_PERIOD;
    private static final int K_ENTER = KeyEvent.KEYCODE_ENTER;
    private static final int K_ESC = KeyEvent.KEYCODE_ESCAPE;
    private static final int K_MAP = KeyEvent.KEYCODE_TAB;
    private static final int K_WPREV = KeyEvent.KEYCODE_C;   // patched into m_controls.c
    private static final int K_WNEXT = KeyEvent.KEYCODE_X;
    private static final int K_YES = KeyEvent.KEYCODE_Y;
    private static final int K_NO = KeyEvent.KEYCODE_N;
    private static final int K_INV_L = KeyEvent.KEYCODE_LEFT_BRACKET;
    private static final int K_INV_R = KeyEvent.KEYCODE_RIGHT_BRACKET;
    private static final int K_ITEM = KeyEvent.KEYCODE_ENTER;
    private static final int K_FLY = KeyEvent.KEYCODE_PAGE_UP;
    private static final int K_JUMP = KeyEvent.KEYCODE_SLASH;
    private static final int K_HEAL = KeyEvent.KEYCODE_H;
    private static final int K_TOGGLE_EXTRAS = -1;

    // Button visibility groups.
    private static final int G_GAME = 0, G_MENU = 1, G_BOTH = 2, G_EXTRA = 3;

    private static final class Btn {
        final String label;
        final int key;
        final int group;
        final boolean fire;
        final boolean hamburger;
        float cx, cy, r;
        int pointer = -1;
        int sent = 0;          // key actually pressed for this touch (may differ from key in menu mode)

        Btn(String label, int key, int group, boolean fire, boolean hamburger) {
            this.label = label;
            this.key = key;
            this.group = group;
            this.fire = fire;
            this.hamburger = hamburger;
        }
    }

    // ---------------------------------------------------------- state -----
    private final GameKind kind;
    private final boolean haptics;
    private final float density;
    private final float sc;            // user size multiplier * density
    private final float lookGain;      // mouse counts per pixel
    private final int alpha;           // 0..255 for outlines

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseBooleanArray keysDown = new SparseBooleanArray();
    private final List<Btn> buttons = new ArrayList<>();
    private Btn fireBtn, useBtn, menuBtn, yesBtn, noBtn, toggleBtn;

    private boolean menuMode;
    private boolean extrasOpen;

    // Stick
    private int stickId = -1;
    private float stickCx, stickCy, stickKx, stickKy, stickR;
    private int menuDirKey = 0;

    // Look
    private int lookId = -1;
    private final float[] lastX = new float[32];
    private float lookRemainder;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int ACCENT = 0xFFFF5A3C;

    public TouchControlsView(Context c, GameKind kind, int sensitivity, int opacity,
                             int size, boolean haptics) {
        super(c);
        this.kind = kind;
        this.haptics = haptics;
        this.density = c.getResources().getDisplayMetrics().density;
        this.sc = density * (0.7f + 0.7f * size / 100f);                  // 0.7x .. 1.4x
        this.lookGain = 5f * (0.4f + 1.6f * sensitivity / 100f);          // 0.4x .. 2.0x
        this.alpha = (int) (255 * (0.25f + 0.65f * opacity / 100f));

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * density);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);

        setFocusable(false);
        setHapticFeedbackEnabled(true);
        buildButtons();
        handler.post(poll);
    }

    private void buildButtons() {
        fireBtn = add(new Btn("", K_FIRE, G_BOTH, true, false));
        useBtn = add(new Btn("USE", K_USE, G_GAME, false, false));
        add(new Btn("\u2039", K_WPREV, G_GAME, false, false));
        add(new Btn("\u203A", K_WNEXT, G_GAME, false, false));
        add(new Btn("MAP", K_MAP, G_GAME, false, false));
        menuBtn = add(new Btn("", K_ESC, G_BOTH, false, true));
        yesBtn = add(new Btn("Y", K_YES, G_MENU, false, false));
        noBtn = add(new Btn("N", K_NO, G_MENU, false, false));

        if (kind.hasExtras) {
            toggleBtn = add(new Btn("+", K_TOGGLE_EXTRAS, G_GAME, false, false));
            add(new Btn("I\u2039", K_INV_L, G_EXTRA, false, false));
            add(new Btn("I\u203A", K_INV_R, G_EXTRA, false, false));
            add(new Btn("ITEM", K_ITEM, G_EXTRA, false, false));
            switch (kind) {
                case HERETIC: add(new Btn("FLY", K_FLY, G_EXTRA, false, false)); break;
                case HEXEN:   add(new Btn("JUMP", K_JUMP, G_EXTRA, false, false)); break;
                case STRIFE:
                    add(new Btn("HEAL", K_HEAL, G_EXTRA, false, false));
                    add(new Btn("JUMP", K_JUMP, G_EXTRA, false, false));
                    break;
                default: break;
            }
        }
    }

    private Btn add(Btn b) {
        buttons.add(b);
        return b;
    }

    // ----------------------------------------------------------- layout ----
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float m = 30 * density;
        stickR = 56 * sc;

        fireBtn.r = 40 * sc;
        fireBtn.cx = w - m - fireBtn.r - 6 * density;
        fireBtn.cy = h - m - fireBtn.r;

        useBtn.r = 26 * sc;
        useBtn.cx = fireBtn.cx - fireBtn.r - useBtn.r - 16 * density;
        useBtn.cy = fireBtn.cy + fireBtn.r - useBtn.r;

        float wy = fireBtn.cy - fireBtn.r - 34 * sc;
        float wr = 19 * sc;
        place("\u2039", fireBtn.cx - 24 * sc, wy, wr);
        place("\u203A", fireBtn.cx + 24 * sc, wy, wr);

        menuBtn.r = 18 * sc;
        menuBtn.cx = w - m - menuBtn.r;
        menuBtn.cy = Math.max(m * 0.8f, 24 * density) + menuBtn.r;
        place("MAP", menuBtn.cx - 46 * sc, menuBtn.cy, 18 * sc);
        if (toggleBtn != null) {
            toggleBtn.r = 18 * sc;
            toggleBtn.cx = menuBtn.cx - 92 * sc;
            toggleBtn.cy = menuBtn.cy;
        }

        // Extras: second row, right-aligned.
        int i = 0;
        for (Btn b : buttons) {
            if (b.group != G_EXTRA) continue;
            b.r = 20 * sc;
            b.cx = menuBtn.cx - i * 48 * sc;
            b.cy = menuBtn.cy + 50 * sc;
            i++;
        }

        // Menu-mode chips sit where USE normally is.
        yesBtn.r = 24 * sc;
        yesBtn.cx = useBtn.cx;
        yesBtn.cy = useBtn.cy;
        noBtn.r = 24 * sc;
        noBtn.cx = useBtn.cx - 56 * sc;
        noBtn.cy = useBtn.cy;

        // Keep edge swipes from triggering Android's back gesture.
        if (Build.VERSION.SDK_INT >= 29) {
            int eh = (int) (Math.min(h, 200 * density));
            int ew = (int) (72 * density);
            List<Rect> r = new ArrayList<>();
            r.add(new Rect(0, h - eh, ew, h));
            r.add(new Rect(w - ew, h - eh, w, h));
            setSystemGestureExclusionRects(r);
        }
    }

    private void place(String label, float cx, float cy, float r) {
        for (Btn b : buttons) {
            if (b.label.equals(label) && b.group == G_GAME) {
                b.cx = cx; b.cy = cy; b.r = r;
                return;
            }
        }
    }

    private boolean visible(Btn b) {
        switch (b.group) {
            case G_BOTH: return true;
            case G_MENU: return menuMode;
            case G_GAME: return !menuMode;
            case G_EXTRA: return !menuMode && extrasOpen;
            default: return false;
        }
    }

    // ------------------------------------------------------------ drawing --
    @Override
    protected void onDraw(Canvas c) {
        // Stick (floating) or an idle ghost ring where the thumb usually rests.
        if (stickId != -1) {
            stroke.setColor(0xFFFFFFFF);
            stroke.setAlpha(alpha);
            c.drawCircle(stickCx, stickCy, stickR, stroke);
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha((int) (alpha * 0.55f));
            c.drawCircle(stickKx, stickKy, stickR * 0.38f, fill);
        } else {
            stroke.setColor(0xFFFFFFFF);
            stroke.setAlpha((int) (alpha * 0.4f));
            c.drawCircle(homeX(), homeY(), stickR, stroke);
        }

        for (Btn b : buttons) {
            if (!visible(b)) continue;
            boolean down = b.pointer != -1;
            int color = b.fire ? ACCENT : 0xFFFFFFFF;

            stroke.setColor(color);
            stroke.setAlpha(down ? Math.min(255, alpha + 70) : alpha);
            stroke.setStrokeWidth((b.fire ? 2.4f : 1.6f) * density);
            c.drawCircle(b.cx, b.cy, b.r, stroke);

            if (down) {
                fill.setColor(color);
                fill.setAlpha(90);
                c.drawCircle(b.cx, b.cy, b.r, fill);
            }
            if (b.fire && !menuMode) {
                fill.setColor(ACCENT);
                fill.setAlpha((int) (alpha * 0.6f));
                c.drawCircle(b.cx, b.cy, b.r * 0.22f, fill);
            }

            if (b.hamburger) {
                stroke.setColor(0xFFFFFFFF);
                stroke.setAlpha(alpha);
                stroke.setStrokeWidth(1.8f * density);
                float lw = b.r * 0.5f;
                for (int k = -1; k <= 1; k++) {
                    c.drawLine(b.cx - lw, b.cy + k * b.r * 0.3f, b.cx + lw, b.cy + k * b.r * 0.3f, stroke);
                }
            } else {
                String label = b.label;
                if (b.fire) label = menuMode ? "OK" : "";
                if (b == toggleBtn) label = extrasOpen ? "\u2013" : "+";
                if (!label.isEmpty()) {
                    text.setColor(color);
                    text.setAlpha(Math.min(255, alpha + 40));
                    float ts = (label.length() > 3 ? 9f : label.length() > 1 ? 11f : 17f) * sc;
                    text.setTextSize(ts);
                    c.drawText(label, b.cx, b.cy + ts * 0.35f, text);
                }
            }
        }
    }

    private float homeX() { return Math.max(110 * density, getWidth() * 0.14f); }
    private float homeY() { return getHeight() - Math.max(90 * density, getHeight() * 0.26f); }

    // -------------------------------------------------------------- touch --
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int idx = e.getActionIndex();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    move(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(e.getPointerId(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void down(int id, float x, float y) {
        if (id >= lastX.length) return;
        lastX[id] = x;

        Btn hit = null;
        float best = Float.MAX_VALUE;
        for (Btn b : buttons) {
            if (!visible(b) || b.pointer != -1) continue;
            float dx = x - b.cx, dy = y - b.cy, d2 = dx * dx + dy * dy;
            float reach = b.r * 1.3f;
            if (d2 <= reach * reach && d2 < best) { best = d2; hit = b; }
        }
        if (hit != null) {
            hit.pointer = id;
            if (hit.key == K_TOGGLE_EXTRAS) {
                extrasOpen = !extrasOpen;
            } else {
                // In the engine menu the big ring is the OK button: it must send Enter,
                // not Ctrl (fire), otherwise menu items can never be activated.
                hit.sent = (hit.fire && menuMode) ? K_ENTER : hit.key;
                setKey(hit.sent, true);
            }
            buzz();
            return;
        }

        if (x < getWidth() * 0.4f && stickId == -1) {
            stickId = id;
            float m = stickR + 12 * density;
            stickCx = Math.max(m, Math.min(getWidth() * 0.4f, x));
            stickCy = Math.max(m, Math.min(getHeight() - m, y));
            stickKx = stickCx;
            stickKy = stickCy;
        } else if (!menuMode && lookId == -1 && x >= getWidth() * 0.4f) {
            lookId = id;
            lookRemainder = 0;
        }
    }

    private void move(int id, float x, float y) {
        if (id >= lastX.length) return;
        float dx = x - lastX[id];
        lastX[id] = x;

        if (id == stickId) {
            updateStick(x, y);
        } else if (id == lookId || (fireBtn.pointer == id && !menuMode)) {
            look(dx);
        }
    }

    private void up(int id) {
        for (Btn b : buttons) {
            if (b.pointer == id) {
                b.pointer = -1;
                if (b.key != K_TOGGLE_EXTRAS) setKey(b.sent != 0 ? b.sent : b.key, false);
                b.sent = 0;
            }
        }
        if (id == stickId) {
            stickId = -1;
            applyStick(0, 0);
        }
        if (id == lookId) lookId = -1;
    }

    // -------------------------------------------------------------- stick --
    private void updateStick(float x, float y) {
        float vx = x - stickCx, vy = y - stickCy;
        float len = (float) Math.hypot(vx, vy);
        if (len > stickR) {
            vx *= stickR / len;
            vy *= stickR / len;
        }
        stickKx = stickCx + vx;
        stickKy = stickCy + vy;
        applyStick(vx / stickR, vy / stickR);
    }

    private void applyStick(float nx, float ny) {
        float ax = Math.abs(nx), ay = Math.abs(ny);

        if (menuMode) {
            int dir = 0;
            if (Math.max(ax, ay) > 0.45f) {
                dir = ay >= ax ? (ny < 0 ? K_FWD : K_BACK) : (nx < 0 ? K_LEFT : K_RIGHT);
            }
            if (dir != menuDirKey) {
                menuDirKey = dir;
                handler.removeCallbacks(menuRepeat);
                if (dir != 0) {
                    tapKey(dir);
                    handler.postDelayed(menuRepeat, 380);
                }
            }
            return;
        }

        boolean fwd = ny < -0.3f && ay > ax * 0.5f;
        boolean back = ny > 0.3f && ay > ax * 0.5f;
        boolean left = nx < -0.35f && ax > ay * 0.5f;
        boolean right = nx > 0.35f && ax > ay * 0.5f;
        boolean run = Math.hypot(nx, ny) > 0.92f;

        setKey(K_FWD, fwd);
        setKey(K_BACK, back);
        setKey(K_SLEFT, left);
        setKey(K_SRIGHT, right);
        setKey(K_RUN, run);
    }

    private final Runnable menuRepeat = new Runnable() {
        @Override public void run() {
            if (menuMode && stickId != -1 && menuDirKey != 0) {
                tapKey(menuDirKey);
                handler.postDelayed(this, 150);
            }
        }
    };

    // --------------------------------------------------------------- look --
    private void look(float dxPixels) {
        lookRemainder += dxPixels * lookGain;
        int n = (int) lookRemainder;
        if (n == 0) return;
        lookRemainder -= n;
        try {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, n, 0f, true);
        } catch (Throwable ignored) {
            // native side not ready yet
        }
    }

    // ---------------------------------------------------------------- keys --
    private void setKey(int code, boolean want) {
        if (keysDown.get(code, false) == want) return;
        keysDown.put(code, want);
        try {
            if (want) SDLActivity.onNativeKeyDown(code);
            else SDLActivity.onNativeKeyUp(code);
        } catch (Throwable ignored) {
            // native side not ready yet
        }
    }

    /** Press + release (menu navigation, Back button). */
    public void tapKey(final int code) {
        setKey(code, true);
        handler.postDelayed(new Runnable() {
            @Override public void run() { setKey(code, false); }
        }, 45);
    }

    /** Release everything and forget all pointers (pause, mode change, cancel). */
    public void releaseAll() {
        handler.removeCallbacks(menuRepeat);
        menuDirKey = 0;
        for (int i = 0; i < keysDown.size(); i++) {
            int code = keysDown.keyAt(i);
            if (keysDown.valueAt(i)) {
                try { SDLActivity.onNativeKeyUp(code); } catch (Throwable ignored) { }
            }
        }
        keysDown.clear();
        for (Btn b : buttons) { b.pointer = -1; b.sent = 0; }
        stickId = -1;
        lookId = -1;
        postInvalidate();
    }

    private void buzz() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ---------------------------------------------------- menu detection ---
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            checkSaveName();
            boolean m = DoomActivity.menuActive();
            if (m != menuMode) {
                releaseAll();
                menuMode = m;
                invalidate();
            }
            handler.postDelayed(this, 100);
        }
    };

    // ------------------------------------------------- save-name entry ----
    // When the engine asks for a save-game name (new slot or renaming an old
    // one) show a system dialog with the Android keyboard and feed the result
    // back to the engine as ordinary key presses.
    private AlertDialog nameDialog;
    private boolean nameBusy;          // dialog open or keys still being injected

    private void checkSaveName() {
        if (nameBusy) return;
        final String cur = DoomActivity.saveText();
        if (cur == null) return;
        showNameDialog(cur);
    }

    private void showNameDialog(final String current) {
        nameBusy = true;
        releaseAll();
        final int max = (kind == GameKind.HERETIC || kind == GameKind.HEXEN) ? 16 : 22;

        final EditText et = new EditText(getContext());
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        et.setText(current);
        et.setSelection(current.length());

        FrameLayout box = new FrameLayout(getContext());
        int pad = (int) (20 * density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);

        final boolean[] done = {false};
        nameDialog = new AlertDialog.Builder(getContext())
                .setTitle("Save name")
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        done[0] = true;
                        submitName(current, et.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        nameDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                nameDialog = null;
                if (!done[0]) submitName(current, null);   // cancelled -> Esc
            }
        });
        et.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int a, android.view.KeyEvent ev) {
                if (a == EditorInfo.IME_ACTION_DONE && nameDialog != null) {
                    nameDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                    nameDialog.dismiss();
                    return true;
                }
                return false;
            }
        });
        nameDialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        nameDialog.show();
        et.requestFocus();
    }

    /** newName == null (or empty) -> cancel with Esc, otherwise replace the text and press Enter. */
    private void submitName(String old, String newName) {
        List<int[]> steps = new ArrayList<>();   // {keycode, shift}
        if (newName == null || newName.trim().isEmpty()) {
            steps.add(new int[]{K_ESC, 0});
        } else {
            for (int i = 0; i < old.length() + 2; i++) steps.add(new int[]{KeyEvent.KEYCODE_DEL, 0});
            for (char ch : newName.toCharArray()) {
                int[] k = charKey(ch);
                if (k != null) steps.add(k);
            }
            steps.add(new int[]{K_ENTER, 0});
        }
        long t = 0;
        for (final int[] st : steps) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (st[1] != 0) setKey(KeyEvent.KEYCODE_SHIFT_LEFT, true);
                    setKey(st[0], true);
                }
            }, t);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    setKey(st[0], false);
                    if (st[1] != 0) setKey(KeyEvent.KEYCODE_SHIFT_LEFT, false);
                }
            }, t + 20);
            t += 45;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { nameBusy = false; }
        }, t + 400);
    }

    private static int[] charKey(char c) {
        if (c >= 'a' && c <= 'z') return new int[]{KeyEvent.KEYCODE_A + (c - 'a'), 0};
        if (c >= 'A' && c <= 'Z') return new int[]{KeyEvent.KEYCODE_A + (c - 'A'), 0};
        if (c >= '0' && c <= '9') return new int[]{KeyEvent.KEYCODE_0 + (c - '0'), 0};
        switch (c) {
            case ' ':  return new int[]{KeyEvent.KEYCODE_SPACE, 0};
            case '-':  return new int[]{KeyEvent.KEYCODE_MINUS, 0};
            case '_':  return new int[]{KeyEvent.KEYCODE_MINUS, 1};
            case '.':  return new int[]{KeyEvent.KEYCODE_PERIOD, 0};
            case ',':  return new int[]{KeyEvent.KEYCODE_COMMA, 0};
            case '\'': return new int[]{KeyEvent.KEYCODE_APOSTROPHE, 0};
            case ';':  return new int[]{KeyEvent.KEYCODE_SEMICOLON, 0};
            case ':':  return new int[]{KeyEvent.KEYCODE_SEMICOLON, 1};
            case '=':  return new int[]{KeyEvent.KEYCODE_EQUALS, 0};
            case '+':  return new int[]{KeyEvent.KEYCODE_EQUALS, 1};
            case '/':  return new int[]{KeyEvent.KEYCODE_SLASH, 0};
            case '?':  return new int[]{KeyEvent.KEYCODE_SLASH, 1};
            default:
                int i = "!@#$%^&*()".indexOf(c);
                if (i >= 0) return new int[]{i == 9 ? KeyEvent.KEYCODE_0 : KeyEvent.KEYCODE_1 + i, 1};
                return null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (nameDialog != null) { nameDialog.setOnDismissListener(null); nameDialog.dismiss(); }
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
