package com.cookie.doom;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.system.Os;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;

/**
 * Hosts the SDL surface, loads libSDL2.so + lib{game}.so and layers the
 * minimal touch overlay on top.
 */
public class DoomActivity extends SDLActivity {

    public static final String EXTRA_GAME = "game";
    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_SENS = "sens";
    public static final String EXTRA_OPACITY = "opacity";
    public static final String EXTRA_SIZE = "size";
    public static final String EXTRA_HAPTICS = "haptics";

    /** Implemented in android_bridge.c (linked into every game library). */
    private static native boolean nativeMenuActive();

    /** Save-name being edited in the engine (null when no name entry is active). */
    private static native String nativeSaveText();

    private TouchControlsView controls;

    static String saveText() {
        try {
            return nativeSaveText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** True while the engine's menu / prompt is open. Safe to call at any time. */
    static boolean menuActive() {
        try {
            return nativeMenuActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Setup passes IWADs by bare file name (e.g. "-iwad doom.wad"); the engine
     * looks for them in $DOOMWADDIR. Called by both the game and Setup.
     */
    static void exportWadDir(android.content.Context c) {
        try {
            Os.setenv("DOOMWADDIR", WadStore.wadDir(c).getAbsolutePath(), true);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private GameKind kind() {
        return GameKind.fromName(getIntent().getStringExtra(EXTRA_GAME));
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL2", "SDL2_mixer", kind().lib};   // last entry = main library
    }

    @Override
    protected String[] getArguments() {
        String[] a = getIntent().getStringArrayExtra(EXTRA_ARGS);
        return a != null ? a : new String[0];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        exportWadDir(this);
        super.onCreate(savedInstanceState);
        Immersive.apply(this);

        if (mLayout == null) return;   // SDL failed to load (error dialog is shown)

        controls = new TouchControlsView(
                this,
                kind(),
                getIntent().getIntExtra(EXTRA_SENS, 50),
                getIntent().getIntExtra(EXTRA_OPACITY, 50),
                getIntent().getIntExtra(EXTRA_SIZE, 50),
                getIntent().getBooleanExtra(EXTRA_HAPTICS, true));
        mLayout.addView(controls, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** SDL only hides the system bars for fullscreen SDL windows; Setup's window is not one. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersive.apply(this);
    }

    /**
     * SDL re-requests the orientation after the window is created, and for a
     * resizable window it picks FULL_USER, which follows the system rotation lock
     * (so the game stays in portrait). Always force landscape instead.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** Back = Esc: opens / closes the Doom menu. Quit through the menu. */
    @Override
    public void onBackPressed() {
        if (controls != null) {
            controls.tapKey(KeyEvent.KEYCODE_ESCAPE);
        }
    }

    @Override
    protected void onPause() {
        if (controls != null) controls.releaseAll();
        super.onPause();
    }
}
