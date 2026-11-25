package com.google.android.projection.protocol;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Host tests for the EV log pieces that are plain Java: EvRowAgg (1 s samples
 * -> speed avg/max and battery power), EvLogger.pruneVictims (folder cap) and
 * EvLogger's buffered writes, file roll and folder cap on a temp dir.
 */
public final class EvLogTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + name);
        }
    }

    private static boolean near(float a, float b, float tol) {
        return !Float.isNaN(a) && Math.abs(a - b) <= tol;
    }

    public static void main(String[] args) throws Exception {
        aggregation();
        pruning();
        writer();
        directoryPermissions();
        usbFeed();
        System.out.println("passed: " + pass + ", failed: " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** "USB drive only": the lines the offload service reads (hmgapi-mod/scripts/offload.sh, feed). */
    private static void usbFeed() {
        String header = EvDriveLog.header("NEEV.USA.0000.V015.012.260507", "wp_daudioplus_neev_us", null, "bms", "2026-11-03");
        java.util.List<String> b = EvUsbFeed.headerBlock(7000, "egmp-NEEV-20261103-", header);
        String[] lines = header.split("\n");
        check("feed: block starts with the period and the prefix", b.get(0).equals("B 7000 egmp-NEEV-20261103-"));
        check("feed: block ends with E", b.get(b.size() - 1).equals("E"));
        check("feed: one H per header line", b.size() == lines.length + 2);
        boolean same = true;
        StringBuilder back = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            same &= b.get(i + 1).equals("H " + lines[i]);
            back.append(b.get(i + 1).substring(2)).append('\n');
        }
        check("feed: header lines in order, unchanged", same && back.toString().equals(header));
        check("feed: magic line first", b.get(1).equals("H " + EvDriveLog.MAGIC));
        check("feed: column row last", b.get(b.size() - 2).startsWith("H t_s,soc_half_pct,"));
        boolean oneLine = true;
        for (String l : b) {
            oneLine &= l.indexOf('\n') < 0 && l.indexOf('\\') < 0;
        }
        check("feed: no line break or backslash in a line", oneLine);
        Object[] v = new Object[EvDriveLog.COLUMNS.length];
        v[0] = Long.valueOf(5);
        v[1] = Integer.valueOf(92);
        String row = EvLogger.csvRow(v);
        String r = EvUsbFeed.rowLine(7000, row);
        check("feed: row line", r.startsWith("R 7000 5,92,") && r.indexOf('\n') < 0);
        check("feed: row unchanged but for the line end", (r.substring("R 7000 ".length()) + "\n").equals(row));
        check("feed: tag isn't one HMG-API keeps for the mod", !"EvDriveLog".equals(EvUsbFeed.TAG));
        // the output mode: files unless told otherwise
        check("output: files by default", EvDriveLog.output() == EvDriveLog.OUT_FILE);
        EvDriveLog.setOutput(EvDriveLog.OUT_USB);
        check("output: USB", EvDriveLog.output() == EvDriveLog.OUT_USB);
        EvDriveLog.setOutput(99);
        check("output: an unknown value is files", EvDriveLog.output() == EvDriveLog.OUT_FILE);
    }

    private static void aggregation() {
        EvRowAgg a = new EvRowAgg();
        a.reset();
        check("empty: no power", Float.isNaN(a.powerW(2)));
        check("empty: no speed", Float.isNaN(a.speedAvg()) && Float.isNaN(a.speedMax()));
        // period 1: 5 samples at 1 s, energy 10000 -> 9990 raw (20 Wh in 4 s)
        int[] e = {10000, 9998, 9995, 9992, 9990};
        float[] v = {50, 60, Float.NaN, 70, 80};
        for (int i = 0; i < 5; i++) {
            a.add(i * 1000L, e[i], v[i]);
        }
        // first row of a file: no power (stale samples right after wake;
        // the car showed a bogus -65 kW here)
        check("first row of a file: no power", Float.isNaN(a.powerW(2)));
        check("speed avg of valid samples", near(a.speedAvg(), 65f, 1e-4f));
        check("speed max", near(a.speedMax(), 80f, 0f));
        check("speed last", near(a.speedLast(), 80f, 0f));
        check("last energy", a.lastEnergyRaw() == 9990);
        a.endPeriod();
        // period 2: energy unknown in every sample -> no power, ref kept
        for (int i = 5; i < 10; i++) {
            a.add(i * 1000L, -1, 0f);
        }
        check("no energy: no power", Float.isNaN(a.powerW(2)));
        check("standstill speed 0", near(a.speedMax(), 0f, 0f));
        a.endPeriod();
        // the reference is the last sample of the previous row with energy:
        // -20 Wh between t=0 and t=4 s was row 1, row 3 measures from t=4 s
        // period 3: charging, 9990 -> 10040 raw at t=14 s: +100 Wh over 10 s
        // since the last valid sample (t=4 s) = +36000 W
        for (int i = 10; i < 15; i++) {
            a.add(i * 1000L, i == 14 ? 10040 : -1, Float.NaN);
        }
        check("power bridges unknown period, + = charging",
                near(a.powerW(2), 36000f, 0.5f));
        check("no speed samples", Float.isNaN(a.speedAvg()));
        a.reset();
        a.add(0, 5000, 10);
        check("single sample: no power", Float.isNaN(a.powerW(2)));
        // row 2 of a file: 5000 -> 4990 raw (-20 Wh) over 4 s = -18000 W
        a.endPeriod();
        for (int i = 1; i <= 4; i++) {
            a.add(i * 1000L, i == 4 ? 4990 : 5000 - i, 0f);
        }
        check("row 2: power from the previous row's last sample",
                near(a.powerW(2), -18000f, 0.5f));
        check("rounding", EvDriveLog.round1(12.345f).floatValue() == 12.3f
                && EvDriveLog.round1(Float.NaN) == null);
    }

    private static void pruning() {
        String[] n = {"egmp-NEEV-20261029-002.csv", "egmp-NEEV-20261028-001.csv",
                "egmp-NEEV-20261029-001.csv", "egmp-NEEV-20261030-001.csv"};
        long[] s = {30, 40, 20, 10};  // total 100
        check("fits: nothing deleted",
                EvLogger.pruneVictims(n, s, null, 0, 100).length == 0);
        int[] r = EvLogger.pruneVictims(n, s, null, 25, 100);
        check("oldest by name first", Arrays.equals(r, new int[] {1}));
        r = EvLogger.pruneVictims(n, s, null, 60, 100);
        check("then the next oldest", Arrays.equals(r, new int[] {1, 2}));
        r = EvLogger.pruneVictims(n, s, "egmp-NEEV-20261028-001.csv", 25, 100);
        check("current file is kept", Arrays.equals(r, new int[] {2, 0}));
        r = EvLogger.pruneVictims(n, s, "egmp-NEEV-20261030-001.csv", 1000, 100);
        check("cannot fit: everything else", r.length == 3);
        check("empty folder", EvLogger.pruneVictims(new String[0], new long[0],
                null, 5, 100).length == 0);
    }

    private static void writer() throws Exception {
        File dir = Files.createTempDirectory("evlogtest").toFile();
        try {
            final StringBuilder msgs = new StringBuilder();
            EvLogger.Sink sink = new EvLogger.Sink() {
                @Override
                public void log(String m) {
                    msgs.append(m).append('\n');
                }
            };
            String hdr = "# egmp-aa-ev-log v2\nt_s,x\n";  // 26 bytes
            String row = "0,1234567\n";                   // 10 bytes
            // caps: file 100 B, folder 250 B, buffer 64 chars
            EvLogger w = new EvLogger(dir, 100, 250, 64, sink);
            w.startNewFile();
            check("empty flush is a no-op", w.flush("egmp-T-20261029-", hdr)
                    && dir.list().length == 0);
            for (int i = 0; i < 3; i++) {
                w.append(row);
            }
            check("buffered, nothing written yet", dir.list().length == 0
                    && w.pendingChars() == 30);
            check("flush ok", w.flush("egmp-T-20261029-", hdr));
            File f1 = new File(dir, "egmp-T-20261029-001.csv");
            check("first file has header + rows", f1.length() == 26 + 30
                    && new String(Files.readAllBytes(f1.toPath()), "US-ASCII")
                            .startsWith("# egmp-aa-ev-log v2\n"));
            check("buffer emptied", w.pendingChars() == 0);
            w.append(row);
            w.flush("egmp-T-20261029-", hdr);
            check("appends without a second header", f1.length() == 66);
            // 66 + 40 > 100: rolls to -002 with a fresh header
            for (int i = 0; i < 4; i++) {
                w.append(row);
            }
            w.flush("egmp-T-20261029-", hdr);
            File f2 = new File(dir, "egmp-T-20261029-002.csv");
            check("roll at file cap", f1.length() == 66 && f2.length() == 66);
            // buffer cap: 10 rows = 100 chars > 64 -> oldest rows dropped
            for (int i = 0; i < 10; i++) {
                w.append(i + ",1234567\n");
            }
            check("buffer bounded to whole rows", w.pendingChars() <= 64
                    && w.pendingChars() % 10 == 0);
            // older file from an earlier day; folder 66+66+100 = 232
            FileOutputStream o = new FileOutputStream(new File(dir, "egmp-T-20261028-001.csv"));
            o.write(new byte[100]);
            o.close();
            // flush 60 B: 002 is full (66 + 60 > 100) -> new 003 (26 + 60 = 86):
            // 232 + 86 > 250 -> delete the oldest (20261028-001) -> 218
            w.flush("egmp-T-20261029-", hdr);
            long total = 0;
            for (File f : dir.listFiles()) {
                total += f.length();
            }
            check("oldest file deleted for the folder cap",
                    !new File(dir, "egmp-T-20261028-001.csv").exists()
                            && new File(dir, "egmp-T-20261029-003.csv").isFile());
            check("folder within cap", total <= 250);
            // a new head-unit period opens a new file even if the last isn't full
            w.startNewFile();
            w.append(row);
            w.flush("egmp-T-20261029-", hdr);
            check("new period -> new file", new File(dir, "egmp-T-20261029-004.csv").isFile());
            total = 0;
            for (File f : dir.listFiles()) {
                total += f.length();
            }
            check("folder still within cap after many writes", total <= 250);
            // unwritable folder: flush fails, rows stay buffered
            EvLogger bad = new EvLogger(null, 100, 250, 64, sink);
            bad.append(row);
            check("no folder: kept in memory", !bad.flush("p-", hdr)
                    && bad.pendingChars() == 10);
            // real caps
            check("real caps", EvLogger.MAX_DIR_BYTES == 100L * 1024 * 1024
                    && EvLogger.MAX_FILE_BYTES <= EvLogger.MAX_DIR_BYTES / 4);
        } finally {
            for (File f : dir.listFiles()) {
                f.delete();
            }
            dir.delete();
        }
    }

    private static void directoryPermissions() throws Exception {
        PermissionTrackingDirectory dir = new PermissionTrackingDirectory(
                Files.createTempDirectory("evlogperms").toFile());
        EvLogger w = new EvLogger(dir, 100, 250, 64, msg -> {});
        String prefix = "egmp-T-20261029-";
        String header = "t_s,x\n";
        try {
            w.append("0,1\n");
            check("a failed permission change still allows the flush", w.flush(prefix, header)
                    && dir.readCalls == 1 && dir.executeCalls == 1);
            w.append("5,2\n");
            check("directory permissions are retried", w.flush(prefix, header)
                    && dir.readCalls == 2 && dir.executeCalls == 2);
            w.append("10,3\n");
            check("ordinary appends skip directory permission changes", w.flush(prefix, header)
                    && dir.readCalls == 2 && dir.executeCalls == 2);

            Files.delete(w.currentFile().toPath());
            Files.delete(dir.toPath());
            w.append("15,4\n");
            check("a recreated directory gets permissions again", w.flush(prefix, header)
                    && dir.readCalls == 3 && dir.executeCalls == 3);
        } finally {
            if (w.currentFile() != null) {
                Files.deleteIfExists(w.currentFile().toPath());
            }
            Files.deleteIfExists(dir.toPath());
        }
    }

    private static final class PermissionTrackingDirectory extends File {
        int readCalls;
        int executeCalls;

        PermissionTrackingDirectory(File dir) {
            super(dir.getPath());
        }

        @Override
        public boolean setReadable(boolean readable, boolean ownerOnly) {
            return ++readCalls > 1 && readable && !ownerOnly;
        }

        @Override
        public boolean setExecutable(boolean executable, boolean ownerOnly) {
            executeCalls++;
            return executable && !ownerOnly;
        }
    }
}
