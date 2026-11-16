package com.cookie.doom;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything lives in  Android/data/com.cookie.doom/files/  so users can also
 * copy files over USB:
 *   wads/     IWADs and mods (PWAD / .deh / .bex) - sorted automatically
 *   config/   engine config files
 *   saves/    savegames
 */
public final class WadStore {

    public static final class Entry {
        public final File file;
        public final boolean iwad;   // true = base game, false = mod
        public final GameKind kind;  // only meaningful for IWADs
        public final String title;

        Entry(File file, boolean iwad, GameKind kind, String title) {
            this.file = file;
            this.iwad = iwad;
            this.kind = kind;
            this.title = title;
        }
    }

    private WadStore() {}

    public static File root(Context c) {
        File f = c.getExternalFilesDir(null);
        if (f == null) f = c.getFilesDir();
        return f;
    }

    public static File wadDir(Context c) { return sub(c, "wads"); }
    public static File configDir(Context c) { return sub(c, "config"); }
    public static File saveDir(Context c) { return sub(c, "saves"); }

    private static File sub(Context c, String name) {
        File d = new File(root(c), name);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Scan the wads directory and classify each file. */
    public static List<Entry> scan(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] files = wadDir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".wad")) {
                String magic = readMagic(f);
                if ("IWAD".equals(magic)) {
                    GameKind k = detect(f);
                    out.add(new Entry(f, true, k, prettyTitle(n, k)));
                } else if ("PWAD".equals(magic)) {
                    out.add(new Entry(f, false, GameKind.DOOM, f.getName()));
                }
            } else if (n.endsWith(".deh") || n.endsWith(".bex")) {
                out.add(new Entry(f, false, GameKind.DOOM, f.getName()));
            }
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    // ------------------------------------------------------------ import ---

    /** Copy a document picked via the system file picker into the wads folder. */
    public static String importUri(Context c, Uri uri) throws IOException {
        String name = displayName(c.getContentResolver(), uri);
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.isEmpty()) name = "file.wad";
        File dst = new File(wadDir(c), name);
        File tmp = new File(wadDir(c), name + ".part");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (dst.exists() && !dst.delete()) throw new IOException("Cannot replace " + name);
        if (!tmp.renameTo(dst)) throw new IOException("Cannot save " + name);
        return name;
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String s = cur.getString(0);
                if (s != null) return s;
            }
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "file.wad" : p.substring(p.lastIndexOf('/') + 1);
    }

    // --------------------------------------------------------- detection ---

    private static String readMagic(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return "";
            return new String(b, "US-ASCII");
        } catch (IOException e) {
            return "";
        }
    }

    /** File name first, then well-known lump names as a fallback. */
    static GameKind detect(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.contains("heretic")) return GameKind.HERETIC;
        if (n.contains("hexen") || n.contains("hexdd")) return GameKind.HEXEN;
        if (n.contains("strife")) return GameKind.STRIFE;
        if (n.contains("doom") || n.contains("plutonia") || n.contains("tnt")
                || n.contains("freedm") || n.contains("chex") || n.contains("hacx")) {
            return GameKind.DOOM;
        }
        List<String> lumps = lumpNames(f);
        if (lumps.contains("MAPINFO") && lumps.contains("BEHAVIOR")) return GameKind.HEXEN;
        if (lumps.contains("SCRIPT01")) return GameKind.STRIFE;
        if (lumps.contains("MUS_E1M1")) return GameKind.HERETIC;
        return GameKind.DOOM;
    }

    private static List<String> lumpNames(File f) {
        List<String> names = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] hdr = new byte[12];
            raf.readFully(hdr);
            ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
            h.position(4);
            int num = h.getInt();
            int ofs = h.getInt();
            if (num <= 0 || num > 100000 || ofs <= 0) return names;
            byte[] dir = new byte[num * 16];
            raf.seek(ofs);
            raf.readFully(dir);
            for (int i = 0; i < num; i++) {
                int o = i * 16 + 8;
                int len = 0;
                while (len < 8 && dir[o + len] != 0) len++;
                names.add(new String(dir, o, len, "US-ASCII").toUpperCase(Locale.ROOT));
            }
        } catch (IOException ignored) {
        }
        return names;
    }

    private static String prettyTitle(String lower, GameKind k) {
        switch (lower) {
            case "doom.wad":      return "DOOM";
            case "doom1.wad":     return "DOOM (Shareware)";
            case "doomu.wad":     return "The Ultimate DOOM";
            case "doom2.wad":     return "DOOM II";
            case "plutonia.wad":  return "Final DOOM: Plutonia";
            case "tnt.wad":       return "Final DOOM: TNT";
            case "freedoom1.wad": return "Freedoom: Phase 1";
            case "freedoom2.wad": return "Freedoom: Phase 2";
            case "freedm.wad":    return "FreeDM";
            case "heretic.wad":   return "Heretic";
            case "heretic1.wad":  return "Heretic (Shareware)";
            case "hexen.wad":     return "Hexen";
            case "hexdd.wad":     return "Hexen: Deathkings";
            case "strife1.wad":   return "Strife";
            case "chex.wad":      return "Chex Quest";
            case "hacx.wad":      return "HacX";
            default:
                String t = lower.substring(0, lower.length() - 4);
                if (t.isEmpty()) return lower;
                return Character.toUpperCase(t.charAt(0)) + t.substring(1);
        }
    }
}
