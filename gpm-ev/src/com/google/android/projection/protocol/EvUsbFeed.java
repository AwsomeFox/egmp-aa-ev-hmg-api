package com.google.android.projection.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * The EV log as lines for the system log, for the "USB drive only" mode
 * (docs/LOGGING.md): nothing is written to the head unit's storage. EvDriveLog
 * logs these lines under TAG; the mod's offload service (root) reads them with
 * logcat and appends the rows to a file on the drive.
 *
 * Lines, one log message each:
 *
 *   B <period> <prefix>   a header block starts: <period> names the head-unit
 *                         "on" period (one file per period), <prefix> is the
 *                         file name up to the sequence number
 *   H <header line>       the block's lines, in order (magic line to column row)
 *   E                     the block is complete
 *   R <period> <row>      one CSV row of that period
 *
 * The block is sent when a period starts and again every minute, so a reader
 * that starts late, or a drive plugged in mid-drive, has the header within a
 * minute; rows of a period whose block the reader hasn't seen are skipped.
 *
 * Plain Java (no Android), so the host test checks the lines.
 */
final class EvUsbFeed {
    /** The log tag. Not one of mod.json's "logs" tags: HMG-API must not keep these lines on the unit. */
    static final String TAG = "EvLogUsb";

    private EvUsbFeed() {}

    /** The header block of a period: B, one H per header line, E. */
    static List<String> headerBlock(long period, String prefix, String header) {
        List<String> out = new ArrayList<String>();
        out.add("B " + period + " " + prefix);
        int from = 0;
        while (from < header.length()) {
            int nl = header.indexOf('\n', from);
            if (nl < 0) {
                nl = header.length();
            }
            if (nl > from) {
                out.add("H " + header.substring(from, nl));
            }
            from = nl + 1;
        }
        out.add("E");
        return out;
    }

    /** One row (EvLogger.csvRow, with or without its line end) as an R line. */
    static String rowLine(long period, String row) {
        int end = row.length();
        while (end > 0 && (row.charAt(end - 1) == '\n' || row.charAt(end - 1) == '\r')) {
            end--;
        }
        return "R " + period + " " + row.substring(0, end);
    }
}
