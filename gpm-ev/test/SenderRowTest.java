package com.google.android.projection.protocol;

/**
 * Host test for the EV log row (EvDriveLog.buildRow / step): builds full
 * rows with the car services absent (every reading unknown) and checks
 * nothing throws and the row matches the column count. Guards against the
 * row/column count drifting: an exception in the old logRow killed GPM, and
 * with it Android Auto, on the car (2026-10-29).
 */
public final class SenderRowTest {
    public static void main(String[] args) throws Exception {
        int pass = 0;
        // a few 1 s steps: first file period, one row (5 s), no flush
        long t0 = 1000000L;
        for (int s = 0; s <= 6; s++) {
            EvDriveLog.step(t0 + s * 1000L);
        }
        Object[] v = EvDriveLog.buildRow(t0 + 7000L);
        if (v.length != EvDriveLog.COLUMNS.length) {
            throw new AssertionError("row has " + v.length + " values");
        }
        if (!"t_s".equals(EvDriveLog.COLUMNS[0]) || !Long.valueOf(7).equals(v[0])) {
            throw new AssertionError("t_s " + v[0]);
        }
        if (!EvModelTemplate.MODEL_REV.equals(v[java.util.Arrays.asList(
                EvDriveLog.COLUMNS).indexOf("model_rev")])) {
            throw new AssertionError("model_rev");
        }
        if (!Integer.valueOf(0).equals(v[java.util.Arrays.asList(
                EvDriveLog.COLUMNS).indexOf("aa_active")])) {
            throw new AssertionError("aa_active should be 0 without a session");
        }
        pass++;
        // a gap (head unit asleep) starts a new file period: t_s restarts
        EvDriveLog.step(t0 + 7000L + EvDriveLog.GAP_MS + 5000L);
        v = EvDriveLog.buildRow(t0 + 7000L + EvDriveLog.GAP_MS + 8000L);
        if (!Long.valueOf(3).equals(v[0])) {
            throw new AssertionError("t_s after gap " + v[0]);
        }
        pass++;
        // the header lists every column once, after the v2 magic line
        String h = EvDriveLog.header("NEEV.USA.0000.V015.012.260507",
                "wp_daudioplus_neev_us", "KM8KRDAFN", "bms", "2026-10-29");
        String[] lines = h.split("\n");
        if (!lines[0].equals("# egmp-aa-ev-log v2")
                || !lines[lines.length - 1].equals(String.join(",", EvDriveLog.COLUMNS))
                || !h.contains("# period_s=5\n") || !h.contains("# sample_s=1\n")) {
            throw new AssertionError("header:\n" + h);
        }
        if (new java.util.HashSet<String>(java.util.Arrays.asList(
                EvDriveLog.COLUMNS)).size() != EvDriveLog.COLUMNS.length) {
            throw new AssertionError("duplicate column");
        }
        pass++;
        // every column is on the allowlist (tools/evlog_schema.json), in its order,
        // so cleaned files keep the writer's order
        String schema = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("../tools/evlog_schema.json")), "UTF-8");
        java.util.List<String> allow = new java.util.ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"name\": \"([a-z0-9_]+)\"").matcher(schema);
        while (m.find()) {
            allow.add(m.group(1));
        }
        int last = -1;
        for (String c : EvDriveLog.COLUMNS) {
            int k = allow.indexOf(c);
            if (k <= last) {
                throw new AssertionError("column " + c + " not on the allowlist"
                        + " or out of schema order");
            }
            last = k;
        }
        if (!schema.contains("\"" + EvDriveLog.MAGIC + "\"")) {
            throw new AssertionError("schema does not accept " + EvDriveLog.MAGIC);
        }
        pass++;
        // "USB drive only": the rows go to the system log (EvUsbFeed), the header block
        // first and again after a minute; with no output, nothing is logged at all
        java.io.PrintStream real = System.out;
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream();
        long t1 = t0 + 10L * EvDriveLog.GAP_MS;   // a new period
        System.setOut(new java.io.PrintStream(cap, true));
        try {
            EvDriveLog.setOutput(EvDriveLog.OUT_USB);
            for (int s2 = 0; s2 <= 71; s2++) {
                EvDriveLog.step(t1 + s2 * 1000L);
            }
        } finally {
            System.setOut(real);
        }
        String tag = "I/" + EvUsbFeed.TAG + ": ";
        java.util.List<String> feed = new java.util.ArrayList<String>();
        for (String l : cap.toString("UTF-8").split("\n")) {
            if (l.startsWith(tag)) {
                feed.add(l.substring(tag.length()));
            }
        }
        int blocks = 0, rows = 0, firstRow = -1, firstE = -1;
        for (int k = 0; k < feed.size(); k++) {
            String l = feed.get(k);
            if (l.startsWith("B " + t1 + " egmp-")) {
                blocks++;
            } else if (l.equals("E") && firstE < 0) {
                firstE = k;
            } else if (l.startsWith("R " + t1 + " ")) {
                rows++;
                if (firstRow < 0) {
                    firstRow = k;
                }
                if (l.substring(("R " + t1 + " ").length()).split(",", -1).length != EvDriveLog.COLUMNS.length) {
                    throw new AssertionError("feed row columns: " + l);
                }
            }
        }
        if (blocks != 2 || rows != 14 || firstE < 0 || firstRow != firstE + 1
                || !feed.get(1).equals("H " + EvDriveLog.MAGIC)
                || !feed.get(firstE - 1).equals("H " + String.join(",", EvDriveLog.COLUMNS))
                || !feed.get(firstRow).startsWith("R " + t1 + " 5,")) {
            throw new AssertionError("usb feed: blocks " + blocks + " rows " + rows + " first row at " + firstRow
                    + " first E at " + firstE + "\n" + feed);
        }
        pass++;
        cap.reset();
        long t2 = t1 + 20L * EvDriveLog.GAP_MS;
        System.setOut(new java.io.PrintStream(cap, true));
        try {
            EvDriveLog.setOutput(EvDriveLog.OUT_NONE);
            for (int s2 = 0; s2 <= 71; s2++) {
                EvDriveLog.step(t2 + s2 * 1000L);
            }
        } finally {
            System.setOut(real);
            EvDriveLog.setOutput(EvDriveLog.OUT_FILE);
        }
        if (cap.toString("UTF-8").contains(EvUsbFeed.TAG) || cap.toString("UTF-8").contains("logging to")) {
            throw new AssertionError("no output, but logged: " + cap.toString("UTF-8"));
        }
        pass++;
        System.out.println("EvDriveLog row: " + EvDriveLog.COLUMNS.length
                + " columns, no exception");
        System.out.println("passed: " + pass + ", failed: 0");
    }
}
