package com.cookie.doom;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the engine command line + config files and starts {@link DoomActivity}. */
public final class GameLauncher {

    private GameLauncher() {}

    /** Command line shared by the game and by Setup (which passes it on to the game). */
    private static ArrayList<String> baseArgs(Activity a, WadStore.Entry game, List<WadStore.Entry> mods, Prefs prefs) {
        GameKind kind = game.kind;
        File cfgDir = WadStore.configDir(a);

        try {
            ensureExtraConfig(new File(cfgDir, kind.lib + "-extra.cfg"));
        } catch (IOException e) {
            Toast.makeText(a, "Config error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        ArrayList<String> args = new ArrayList<>();
        args.add("-iwad");        args.add(game.file.getAbsolutePath());
        args.add("-config");      args.add(new File(cfgDir, kind.mainConfig).getAbsolutePath());
        args.add("-extraconfig"); args.add(new File(cfgDir, kind.lib + "-extra.cfg").getAbsolutePath());
        args.add("-savedir");     args.add(WadStore.saveDir(a).getAbsolutePath());

        Set<String> enabled = prefs.enabledMods();
        List<String> pwads = new ArrayList<>();
        List<String> dehs = new ArrayList<>();
        for (WadStore.Entry m : mods) {
            if (!enabled.contains(m.file.getName())) continue;
            String n = m.file.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".wad")) pwads.add(m.file.getAbsolutePath());
            else if (kind.supportsDeh()) dehs.add(m.file.getAbsolutePath());
        }
        if (!pwads.isEmpty()) { args.add("-file"); args.addAll(pwads); }
        if (!dehs.isEmpty())  { args.add("-deh");  args.addAll(dehs); }
        return args;
    }

    private static void addPrefs(Intent i, Prefs prefs) {
        i.putExtra(DoomActivity.EXTRA_SENS, prefs.getInt(Prefs.SENSITIVITY, 50));
        i.putExtra(DoomActivity.EXTRA_OPACITY, prefs.getInt(Prefs.OPACITY, 50));
        i.putExtra(DoomActivity.EXTRA_SIZE, prefs.getInt(Prefs.SIZE, 50));
        i.putExtra(DoomActivity.EXTRA_HAPTICS, prefs.getBool(Prefs.HAPTICS, true));
    }

    public static void launch(Activity a, WadStore.Entry game, List<WadStore.Entry> mods, Prefs prefs) {
        ArrayList<String> args = baseArgs(a, game, mods, prefs);
        Intent i = new Intent(a, DoomActivity.class);
        i.putExtra(DoomActivity.EXTRA_GAME, game.kind.name());
        i.putExtra(DoomActivity.EXTRA_ARGS, args.toArray(new String[0]));
        addPrefs(i, prefs);
        a.startActivity(i);
    }

    /**
     * Opens the original Chocolate Doom Setup program (settings + multiplayer).
     * Setup itself asks which game to configure; its config files are the same
     * ones the games use (see completeArgs).
     */
    public static void launchSetup(Activity a, Prefs prefs) {
        for (GameKind k : GameKind.values()) {
            try {
                ensureExtraConfig(new File(WadStore.configDir(a), k.lib + "-extra.cfg"));
            } catch (IOException ignored) { }
        }
        ArrayList<String> args = new ArrayList<>();
        args.add("-confdir");
        args.add(WadStore.configDir(a).getAbsolutePath() + File.separator);
        Intent i = new Intent(a, SetupActivity.class);
        i.putExtra(DoomActivity.EXTRA_ARGS, args.toArray(new String[0]));
        addPrefs(i, prefs);
        a.startActivity(i);
    }

    private static boolean has(List<String> l, String o) {
        return l.contains(o);
    }

    /**
     * Command line coming back from Setup ("start game", "join", ...). Adds
     * whatever Setup does not know about: the game file, the config files of
     * this game, the save folder and the enabled mods.
     */
    public static String[] completeArgs(Context c, String game, String[] in, Prefs prefs) {
        GameKind kind = GameKind.fromName(game.toUpperCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(s);
        File cfgDir = WadStore.configDir(c);
        List<WadStore.Entry> all = WadStore.scan(c);

        if (!has(out, "-iwad")) {
            for (WadStore.Entry e : all) {
                if (e.iwad && e.kind == kind) {
                    out.add("-iwad");
                    out.add(e.file.getAbsolutePath());
                    break;
                }
            }
        }
        if (!has(out, "-config")) {
            out.add("-config");
            out.add(new File(cfgDir, kind.mainConfig).getAbsolutePath());
        }
        if (!has(out, "-extraconfig")) {
            out.add("-extraconfig");
            out.add(new File(cfgDir, kind.lib + "-extra.cfg").getAbsolutePath());
        }
        if (!has(out, "-savedir")) {
            out.add("-savedir");
            out.add(WadStore.saveDir(c).getAbsolutePath());
        }

        Set<String> enabled = prefs.enabledMods();
        List<String> pwads = new ArrayList<>();
        List<String> dehs = new ArrayList<>();
        for (WadStore.Entry m : all) {
            if (m.iwad || !enabled.contains(m.file.getName())) continue;
            String n = m.file.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".wad")) pwads.add(m.file.getAbsolutePath());
            else if (kind.supportsDeh()) dehs.add(m.file.getAbsolutePath());
        }
        if (!has(out, "-file") && !pwads.isEmpty()) { out.add("-file"); out.addAll(pwads); }
        if (!has(out, "-deh") && !dehs.isEmpty())   { out.add("-deh");  out.addAll(dehs); }
        return out.toArray(new String[0]);
    }

    /** Written once; the engine rewrites the file on exit and keeps our values. */
    private static void ensureExtraConfig(File f) throws IOException {
        if (f.exists()) return;
        try (FileWriter w = new FileWriter(f)) {
            w.write("show_endoom                    0\n");   // no text screen on exit
            w.write("show_diskicon                  0\n");   // cleaner screen
            w.write("mouse_acceleration             1.000000\n"); // 1:1 touch-look
            w.write("mouse_threshold                0\n");
        }
    }
}
