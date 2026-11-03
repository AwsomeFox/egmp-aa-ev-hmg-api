package com.google.android.projection.protocol;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * NativeCheck against library images (hmgapi-mod/test/recipe-test.sh makes them):
 *   NativeCheckTest <recipes dir> <image> <expected recipe id or "none">
 * The recipes are read as the zygote reads them; the image stands in for the
 * library's memory.
 */
public final class NativeCheckTest {
    public static void main(String[] a) throws Exception {
        NativeCheck.load(new File(a[0]));
        List<NativeCheck.Recipe> rs = NativeCheck.recipes();
        final RandomAccessFile img = new RandomAccessFile(a[1], "r");
        StringBuilder why = new StringBuilder();
        String got = NativeCheck.find(rs, new NativeCheck.Memory() {
            public byte[] read(long off, int len) throws Exception {
                byte[] b = new byte[len];
                img.seek(off);
                img.readFully(b);
                return b;
            }
        }, why);
        img.close();
        String want = a[2];
        boolean ok = "none".equals(want) ? got == null : want.equals(got);
        System.out.println((ok ? "ok    " : "FAIL  ") + new File(a[1]).getName() + ": " + (got == null ? "none (" + why + ")" : got)
                + " from " + rs.size() + " recipe(s)");
        if (!ok) System.exit(1);
    }
}
