package com.cookie.doom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Game list, WAD import and the three control sliders. Built in code - no layout XML. */
public class LauncherActivity extends Activity {

    private static final int REQ_PICK = 100;
    private static final int GRAY = 0xFF777777;

    private Prefs prefs;
    private LinearLayout content;
    private AlertDialog busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // Dark icons on the white status / navigation bars.
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        scroll.setSystemUiVisibility(flags);

        // Pad by system bars / cut-outs.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in;
            }
        });
        scroll.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    // ----------------------------------------------------------------- UI --
    private void build() {
        content.removeAllViews();
        content.setPadding(dp(20), dp(16), dp(20), dp(32));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.bottomMargin = dp(8);
        content.addView(logo, llp);

        List<WadStore.Entry> all = WadStore.scan(this);
        final List<WadStore.Entry> games = new ArrayList<>();
        final List<WadStore.Entry> mods = new ArrayList<>();
        for (WadStore.Entry e : all) (e.iwad ? games : mods).add(e);

        // Games
        content.addView(section("Games"));
        if (games.isEmpty()) {
            content.addView(text("No games yet. Tap \u201CAdd files\u201D and choose your game file "
                    + "(for example doom.wad).", 15, GRAY));
        }
        for (final WadStore.Entry g : games) {
            content.addView(gameRow(g, mods));
        }

        // Add button
        Button add = new Button(this);
        add.setText("Add files");
        add.setOnClickListener(v -> pickFiles());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        content.addView(add, alp);

        // Setup: one entry for all games (Setup asks which game to configure).
        Button setup = new Button(this);
        setup.setText("Setup");
        setup.setOnClickListener(v -> GameLauncher.launchSetup(this, prefs));
        content.addView(setup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Mods
        if (!mods.isEmpty()) {
            content.addView(section("Mods"));
            for (final WadStore.Entry m : mods) {
                CheckBox cb = new CheckBox(this);
                cb.setText(m.title);
                cb.setTextColor(Color.BLACK);
                cb.setTextSize(16);
                cb.setChecked(prefs.enabledMods().contains(m.file.getName()));
                cb.setOnCheckedChangeListener((v, on) -> prefs.setModEnabled(m.file.getName(), on));
                cb.setOnLongClickListener(v -> { confirmDelete(m); return true; });
                content.addView(cb);
            }
        }

        // Controls
        content.addView(section("Controls"));
        content.addView(slider("Look sensitivity", Prefs.SENSITIVITY, 50));
        content.addView(slider("Button opacity", Prefs.OPACITY, 50));
        content.addView(slider("Button size", Prefs.SIZE, 50));
        Switch hap = new Switch(this);
        hap.setText("Vibration");
        hap.setTextColor(Color.BLACK);
        hap.setTextSize(16);
        hap.setChecked(prefs.getBool(Prefs.HAPTICS, true));
        hap.setOnCheckedChangeListener((v, on) -> prefs.putBool(Prefs.HAPTICS, on));
        hap.setPadding(0, dp(12), 0, dp(8));
        content.addView(hap);

        TextView foot = text("Setup: original Chocolate Doom settings and multiplayer.\n"
                + "Long-press a game or mod to delete it.\n"
                + "Chocolate Doom is GPL-2.0. Bring your own game data.", 12, GRAY);
        foot.setPadding(0, dp(24), 0, 0);
        content.addView(foot);
    }

    private View gameRow(final WadStore.Entry g, final List<WadStore.Entry> mods) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(text(g.title, 20, Color.BLACK));
        String size = String.format(Locale.US, "%.1f MB", g.file.length() / 1048576.0);
        TextView s = text(g.file.getName() + "  \u00B7  " + size, 12, GRAY);
        row.addView(s);
        row.setClickable(true);
        row.setForeground(ripple());
        row.setOnClickListener(v -> GameLauncher.launch(this, g, mods, prefs));
        row.setOnLongClickListener(v -> { confirmDelete(g); return true; });

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(row);
        View line = new View(this);
        line.setBackgroundColor(0xFFDDDDDD);
        box.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return box;
    }

    private View slider(String label, final String key, int def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);
        box.addView(text(label, 15, Color.BLACK));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(prefs.getInt(key, def));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { prefs.putInt(key, p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb);
        return box;
    }

    // -------------------------------------------------------------- import --
    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;

        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        busy = new AlertDialog.Builder(this)
                .setMessage("Importing\u2026")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            final StringBuilder err = new StringBuilder();
            int ok = 0;
            for (Uri u : uris) {
                try {
                    WadStore.importUri(this, u);
                    ok++;
                } catch (Exception e) {
                    err.append(e.getMessage()).append('\n');
                }
            }
            final int done = ok;
            runOnUiThread(() -> {
                if (busy != null) busy.dismiss();
                if (err.length() > 0) {
                    Toast.makeText(this, err.toString().trim(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Imported " + done + " file(s)", Toast.LENGTH_SHORT).show();
                }
                build();
            });
        }, "wad-import").start();
    }

    private void confirmDelete(final WadStore.Entry e) {
        new AlertDialog.Builder(this)
                .setMessage("Delete " + e.file.getName() + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    e.file.delete();
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------- helpers --
    private TextView section(String s) {
        TextView t = text(s, 14, GRAY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(6));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private android.graphics.drawable.Drawable ripple() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return getDrawable(tv.resourceId);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
