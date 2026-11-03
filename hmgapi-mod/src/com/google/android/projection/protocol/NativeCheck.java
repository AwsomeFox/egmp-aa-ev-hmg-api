package com.google.android.projection.protocol;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Is one of egmp's Android Auto library recipes really in place in this process?
 *
 * HMG-API applies the first of the mod's recipes (mod.json hooks.native) that fits
 * the library build when GPM loads it, and writes nothing if none does or if one
 * overlaps another mod's; it doesn't tell the mod which, if any. So before
 * offering sensor 23 or sending through reportDiagnosticsData, this reads each
 * recipe's patch lines back from the process's own memory (/proc/self/mem at the
 * library's code mapping) and accepts a recipe whose every patch is there.
 * Unpatched, the library would drop sensor 23 and send our bytes as a
 * diagnostics report: both stay off then.
 *
 * The sites come from the recipe files themselves (read in the zygote, where
 * the mod's folder is readable; see HmgApiEntry.zygoteInit), so supporting
 * another library build is one more recipe in recipes/ and mod.json, with no
 * change here. BUILT_IN is the US 260507 recipe's, for a mod loaded live
 * (without a zygote start).
 */
final class NativeCheck {
    private NativeCheck() {}

    static final String LIB = "libautoreceiver_infobank.so";

    /** one recipe's patch lines: file offsets and the bytes written there */
    static final class Recipe {
        final String id;
        final List<Long> offsets = new ArrayList<Long>();
        final List<String> bytes = new ArrayList<String>();

        Recipe(String id) {
            this.id = id;
        }
    }

    /** recipes/ev-sensor-48ee793a.recipe's patch lines */
    static final Recipe BUILT_IN = new Recipe("ev-sensor-48ee793a (built in)");

    static {
        BUILT_IN.offsets.add(0x144ec2L);
        BUILT_IN.bytes.add("1628");
        BUILT_IN.offsets.add(0xbb29eL);
        BUILT_IN.bytes.add("4ff00007");
        BUILT_IN.offsets.add(0xbb638L);
        BUILT_IN.bytes.add("f6f79bb9");
    }

    private static final List<Recipe> sRecipes = new ArrayList<Recipe>();
    private static volatile String sOk;   // the recipe found in place
    private static volatile String sWhy = "not checked";

    /** Reads every recipes/*.recipe of the mod (in the zygote: its folder is readable there). */
    static synchronized void load(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".recipe")) {
                continue;
            }
            try {
                Recipe r = parse(read(f));
                if (!r.offsets.isEmpty()) {
                    sRecipes.add(r);
                }
            } catch (Exception e) {
                android.util.Log.w("EvModelSender", "recipe " + f.getName() + " not read: " + e);
            }
        }
    }

    /** A recipe's id and patch lines (hmgapi.engine.MemRecipe's format); other lines are ignored. */
    static Recipe parse(String text) {
        Recipe r = new Recipe("?");
        String id = null;
        for (String line : text.split("\n")) {
            int hash = line.indexOf('#');
            String l = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (l.length() == 0) {
                continue;
            }
            String[] w = l.split("\\s+");
            if ("id".equals(w[0]) && w.length > 1) {
                id = w[1];
            } else if ("patch".equals(w[0]) && w.length > 2) {
                r.offsets.add(Long.decode(w[1]));
                r.bytes.add(w[2].toLowerCase());
            }
        }
        if (id == null) {
            return r;
        }
        Recipe named = new Recipe(id);
        named.offsets.addAll(r.offsets);
        named.bytes.addAll(r.bytes);
        return named;
    }

    static List<Recipe> recipes() {
        synchronized (NativeCheck.class) {
            return sRecipes.isEmpty() ? java.util.Collections.singletonList(BUILT_IN)
                    : new ArrayList<Recipe>(sRecipes);
        }
    }

    /** the library's memory, from its file offset 0 */
    interface Memory {
        byte[] read(long offset, int len) throws Exception;
    }

    /**
     * The first recipe whose every patch is in memory, or null; why says what
     * didn't match otherwise.
     */
    static String find(List<Recipe> recipes, Memory mem, StringBuilder why) throws Exception {
        for (Recipe r : recipes) {
            String miss = null;
            for (int i = 0; i < r.offsets.size() && miss == null; i++) {
                String want = r.bytes.get(i);
                String got = hex(mem.read(r.offsets.get(i), want.length() / 2));
                if (!got.equals(want)) {
                    miss = String.format("%s: 0x%x is %s, not %s", r.id, r.offsets.get(i), got, want);
                }
            }
            if (miss == null) {
                return r.id;
            }
            if (why.length() > 0) {
                why.append("; ");
            }
            why.append(miss);
        }
        return null;
    }

    static boolean patched() {
        if (sOk != null) {
            return true;
        }
        try {
            final long base = codeBase();
            if (base == 0) {
                sWhy = LIB + " isn't loaded";
                return false;
            }
            final RandomAccessFile mem = new RandomAccessFile("/proc/self/mem", "r");
            try {
                StringBuilder why = new StringBuilder();
                String ok = find(recipes(), new Memory() {
                    @Override
                    public byte[] read(long offset, int len) throws Exception {
                        byte[] b = new byte[len];
                        mem.seek(base + offset);
                        mem.readFully(b);
                        return b;
                    }
                }, why);
                if (ok == null) {
                    sWhy = why.toString();
                    return false;
                }
                sOk = ok;
                sWhy = ok + " in place";
                return true;
            } finally {
                mem.close();
            }
        } catch (Throwable t) {
            sWhy = "can't read it back: " + t;
            return false;
        }
    }

    static String why() {
        return sWhy;
    }

    /** where the library's file offset 0 is mapped (its code segment), 0 if not loaded */
    static long codeBase() throws Exception {
        BufferedReader in = new BufferedReader(new FileReader("/proc/self/maps"));
        try {
            String l;
            while ((l = in.readLine()) != null) {
                String[] w = l.trim().split("\\s+");
                if (w.length < 6 || !w[5].endsWith("/" + LIB) || w[1].indexOf('x') < 0
                        || Long.parseLong(w[2], 16) != 0) {
                    continue;
                }
                return Long.parseLong(w[0].substring(0, w[0].indexOf('-')), 16);
            }
            return 0;
        } finally {
            in.close();
        }
    }

    static String read(File f) throws Exception {
        StringBuilder s = new StringBuilder();
        BufferedReader in = new BufferedReader(new FileReader(f));
        try {
            String l;
            while ((l = in.readLine()) != null) {
                s.append(l).append('\n');
            }
        } finally {
            in.close();
        }
        return s.toString();
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x & 0xff));
        }
        return s.toString();
    }
}
