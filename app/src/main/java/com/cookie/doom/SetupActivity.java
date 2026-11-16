package com.cookie.doom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;

/**
 * Runs the original Chocolate Doom Setup program (libsetup.so): configuration
 * screens and the multiplayer lobby. Tapping works as mouse clicks. For
 * everything else there is a small on-screen pad on the sides of the screen
 * (the text screen is 16:10, so phones leave free space left and right):
 *
 *   left   - (bottom) a stick that sends the arrow keys (hold = repeat)
 *   right  - Esc, Enter, Tab, Space, F1, F2, F10 and W (Add WADs)
 *
 * Text fields open the Android keyboard, so no letter keys are needed. Starting a game from Setup ends up
 * in {@link #launchGame}.
 */
public class SetupActivity extends SDLActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL2", "SDL2_mixer", "setup"};   // last entry = main library
    }

    @Override
    protected String[] getArguments() {
        String[] a = getIntent().getStringArrayExtra(DoomActivity.EXTRA_ARGS);
        return a != null ? a : new String[0];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        DoomActivity.exportWadDir(this);   // lets Setup find the installed IWADs
        super.onCreate(savedInstanceState);
        Immersive.apply(this);

        if (mLayout == null) return;       // SDL failed to load (error dialog is shown)

        mLayout.addView(buildPad(), new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** SDL only hides the system bars for fullscreen SDL windows; Setup's window is not one. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersive.apply(this);
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** Back = Esc (back one screen / quit Setup). */
    @Override
    public void onBackPressed() {
        tap(KeyEvent.KEYCODE_ESCAPE);
    }

    /**
     * Called from native code (setup/execute.c via android_bridge.c) when the
     * user starts a game. Opens the game activity with the collected command
     * line and closes Setup (the Setup process exits right after).
     */
    public void launchGame(String game, String[] args) {
        Intent src = getIntent();
        Intent i = new Intent(this, DoomActivity.class);
        i.putExtra(DoomActivity.EXTRA_GAME,
                GameKind.fromName(game.toUpperCase(java.util.Locale.ROOT)).name());
        i.putExtra(DoomActivity.EXTRA_ARGS,
                GameLauncher.completeArgs(this, game, args, new Prefs(this)));
        i.putExtra(DoomActivity.EXTRA_SENS, src.getIntExtra(DoomActivity.EXTRA_SENS, 50));
        i.putExtra(DoomActivity.EXTRA_OPACITY, src.getIntExtra(DoomActivity.EXTRA_OPACITY, 50));
        i.putExtra(DoomActivity.EXTRA_SIZE, src.getIntExtra(DoomActivity.EXTRA_SIZE, 50));
        i.putExtra(DoomActivity.EXTRA_HAPTICS, src.getBooleanExtra(DoomActivity.EXTRA_HAPTICS, true));
        startActivity(i);
        runOnUiThread(new Runnable() {
            @Override public void run() { finish(); }
        });
    }

    // ------------------------------------------------------------- pad ----
    private View buildPad() {
        FrameLayout root = new FrameLayout(this);

        // Left: stick
        StickView stick = new StickView(this);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(120), dp(120));
        sp.gravity = Gravity.LEFT | Gravity.BOTTOM;
        sp.leftMargin = dp(14);
        sp.bottomMargin = dp(16);
        root.addView(stick, sp);

        // Right: main keys, two columns
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(row(key("Esc", KeyEvent.KEYCODE_ESCAPE), key("Enter", KeyEvent.KEYCODE_ENTER)));
        col.addView(row(key("Tab", KeyEvent.KEYCODE_TAB), key("Space", KeyEvent.KEYCODE_SPACE)));
        col.addView(row(key("F1", KeyEvent.KEYCODE_F1), key("F2", KeyEvent.KEYCODE_F2)));
        col.addView(row(key("F10", KeyEvent.KEYCODE_F10), key("W", KeyEvent.KEYCODE_W)));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        cp.rightMargin = dp(10);
        root.addView(col, cp);

        // Let touches outside the pad reach the SDL surface underneath.
        root.setClickable(false);
        return root;
    }

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.addView(a);
        r.addView(b);
        return r;
    }

    private GradientDrawable bg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Button makeButton(String label, int wDp, int hDp, int textSp) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(textSp);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setFocusable(false);
        b.setBackground(bg(0xAA202020, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    /** Main key: 52 x 46 dp. */
    private Button key(String label, final int code) {
        Button b = makeButton(label, 52, 46, 14);
        if (code != 0) {
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { tap(code); }
            });
        }
        return b;
    }

    // -------------------------------------------------------- key input ---
    private void tap(final int code) {
        try {
            SDLActivity.onNativeKeyDown(code);
        } catch (Throwable ignored) {
            return;   // native side not ready yet
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try { SDLActivity.onNativeKeyUp(code); } catch (Throwable ignored) { }
            }
        }, 45);
    }

    /** Immediate press + release (used for key repeat). */
    private void pulse(int code) {
        try {
            SDLActivity.onNativeKeyDown(code);
            SDLActivity.onNativeKeyUp(code);
        } catch (Throwable ignored) { }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------ stick ---
    /** Circle with a knob; the dominant direction sends an arrow key, held = repeat. */
    private final class StickView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float knobX, knobY;          // knob offset from the centre, in px
        private int dirKey;                  // currently held arrow, 0 = none

        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (dirKey == 0) return;
                pulse(dirKey);
                handler.postDelayed(this, 110);
            }
        };

        StickView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0x88FFFFFF);
            canvas.drawCircle(cx, cy, r - dp(1), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dirKey != 0 ? 0xCCFFA033 : 0xAAFFFFFF);
            canvas.drawCircle(cx + knobX, cy + knobY, r * 0.38f, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    update(e.getX() - getWidth() / 2f, e.getY() - getHeight() / 2f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    release();
                    return true;
                default:
                    return true;
            }
        }

        private void update(float dx, float dy) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            float len = (float) Math.hypot(dx, dy);
            float max = r * 0.62f;
            if (len > max) { dx = dx / len * max; dy = dy / len * max; }
            knobX = dx;
            knobY = dy;

            int key = 0;
            if (len > r * 0.3f) {
                if (Math.abs(dx) > Math.abs(dy)) {
                    key = dx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                } else {
                    key = dy < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
                }
            }
            if (key != dirKey) {
                handler.removeCallbacks(repeat);
                dirKey = key;
                if (key != 0) {
                    pulse(key);
                    handler.postDelayed(repeat, 380);   // first repeat after a short pause
                }
            }
            invalidate();
        }

        private void release() {
            handler.removeCallbacks(repeat);
            dirKey = 0;
            knobX = knobY = 0;
            invalidate();
        }
    }
}
