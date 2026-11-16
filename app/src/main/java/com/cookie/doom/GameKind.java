package com.cookie.doom;

/** The four Chocolate Doom engines. Each one is its own native library. */
public enum GameKind {
    DOOM("doom", "default.cfg", false),
    HERETIC("heretic", "heretic.cfg", true),
    HEXEN("hexen", "hexen.cfg", true),
    STRIFE("strife", "strife.cfg", true);

    /** Native library name (libdoom.so, libheretic.so ...). */
    public final String lib;
    /** Main config file name used by the engine. */
    public final String mainConfig;
    /** Whether the on-screen "extras" panel (inventory, jump, fly) is needed. */
    public final boolean hasExtras;

    GameKind(String lib, String mainConfig, boolean hasExtras) {
        this.lib = lib;
        this.mainConfig = mainConfig;
        this.hasExtras = hasExtras;
    }

    /** Hexen has no DeHackEd support. */
    public boolean supportsDeh() {
        return this != HEXEN;
    }

    public static GameKind fromName(String name) {
        try {
            return valueOf(name);
        } catch (Exception e) {
            return DOOM;
        }
    }
}
