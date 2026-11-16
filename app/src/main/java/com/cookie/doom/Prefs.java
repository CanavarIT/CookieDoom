package com.cookie.doom;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Tiny wrapper around SharedPreferences. Values are passed to the game via Intent extras. */
public final class Prefs {
    private static final String FILE = "cookiedoom";

    public static final String SENSITIVITY = "sensitivity"; // 0..100
    public static final String OPACITY = "opacity";         // 0..100
    public static final String SIZE = "size";               // 0..100 (-> 0.7x .. 1.4x)
    public static final String HAPTICS = "haptics";
    public static final String MODS = "mods";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    public void putInt(String key, int v) {
        sp.edit().putInt(key, v).apply();
    }

    public boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public void putBool(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
    }

    public Set<String> enabledMods() {
        return new HashSet<>(sp.getStringSet(MODS, new HashSet<String>()));
    }

    public void setModEnabled(String fileName, boolean enabled) {
        Set<String> s = enabledMods();
        if (enabled) s.add(fileName); else s.remove(fileName);
        sp.edit().putStringSet(MODS, s).apply();
    }
}
