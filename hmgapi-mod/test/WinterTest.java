package com.google.android.projection.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * EvModelSender's test-setting paths: VEM 1.9 motor power by trim, the flat block 4, and
 * the winter add-on (blocks 6, 8.2, 10 appended to the model, its length rewritten, 8.2
 * scaled to the learned capacity, the live drain with hysteresis).
 */
public final class WinterTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static int[] pos = new int[1];

    static long varint(byte[] b, int[] at) {
        long v = 0;
        for (int sh = 0; ; sh += 7) {
            int c = b[at[0]++] & 0xff;
            v |= (long) (c & 0x7f) << sh;
            if ((c & 0x80) == 0) return v;
        }
    }

    /** top-level fields of b[from, to): {field, offset of value, length}; lengths must add up exactly */
    static List<int[]> fields(byte[] b, int from, int to) {
        List<int[]> out = new ArrayList<int[]>();
        int[] at = {from};
        while (at[0] < to) {
            long key = varint(b, at);
            int f = (int) (key >>> 3), w = (int) (key & 7);
            if (w == 0) {
                int s = at[0];
                varint(b, at);
                out.add(new int[] {f, s, at[0] - s});
            } else if (w == 2) {
                int n = (int) varint(b, at);
                out.add(new int[] {f, at[0], n});
                at[0] += n;
            } else if (w == 5) {
                out.add(new int[] {f, at[0], 4});
                at[0] += 4;
            } else if (w == 1) {
                out.add(new int[] {f, at[0], 8});
                at[0] += 8;
            } else {
                throw new IllegalStateException("wire " + w);
            }
        }
        if (at[0] != to) throw new IllegalStateException("lengths don't add up");
        return out;
    }

    static int count(List<int[]> fs, int f) {
        int n = 0;
        for (int[] x : fs) if (x[0] == f) n++;
        return n;
    }

    public static void main(String[] a) {
        byte[] tmpl = java.util.Base64.getMimeDecoder().decode(EvModelTemplate.TEMPLATE_B64);
        check("template placeholders (incl. 1.9 motor) match", EvModelSender.checkPlaceholders(tmpl));

        // 1.9 by trim
        check("motor: no front motor energy, long range: RWD 168 kW", EvModelSender.motorW(0, 62900) == 168000);
        check("motor: invalid reading: RWD", EvModelSender.motorW(0xffff, 62900) == 168000);
        check("motor: small learned pack: SR 125 kW", EvModelSender.motorW(-1, 50000) == 125000);
        check("motor: front motor energy seen: AWD 239 kW", EvModelSender.motorW(37, 62900) == 239000);
        check("motor: AWD sticks", EvModelSender.motorW(0, 62900) == 239000);

        // the flat block 4
        byte[] m = tmpl.clone();
        EvModelSender.flattenBlock4(m);
        boolean flat = true;
        for (int off : EvModelTemplate.POWER_OFFSETS) {
            int[] at = {off};
            flat &= varint(m, at) == EvModelSender.BLOCK4_FLAT_W;
        }
        int diff = 0;
        for (int i = 0; i < m.length; i++) if (m[i] != tmpl[i]) diff++;
        check("block 4 flat: every 4.1 power 150 kW", flat);
        check("block 4 flat: nothing else changes", diff <= EvModelTemplate.POWER_OFFSETS.length * EvModelTemplate.SCALE_WIDTH);
        check("block 4 flat: still a well-formed model", fields(m, 6, m.length).size() > 0);

        // the winter add-on, at the owner's learned 62.9 kWh
        EvModelSender.setWinterTemplate(java.util.Base64.getMimeDecoder().decode(EvModelTemplate.WINTER_B64));
        byte[] w = EvModelSender.withWinter(tmpl.clone(), 62900, 3200);
        int[] at = {EvModelTemplate.LENGTH_OFFSET};
        long len = varint(w, at);
        check("winter: length rewritten (2-byte varint, model + add-on)", at[0] == 6 && len == w.length - 6);
        List<int[]> top = fields(w, 6, w.length);
        check("winter: blocks 6 and 10 added, a second block 8 to merge", count(top, 6) == 1 && count(top, 10) == 1 && count(top, 8) == 2);
        check("winter: the model's own blocks still there", count(top, 1) == 1 && count(top, 2) == 1 && count(top, 4) == 1);
        int[] b6 = null, b8 = null, b10 = null;
        for (int[] f : top) {
            if (f[0] == 6) b6 = f;
            if (f[0] == 8) b8 = f;   // the last one: the add-on's
            if (f[0] == 10) b10 = f;
        }
        int drain = -1;
        for (int[] f : fields(w, b6[1], b6[1] + b6[2])) {
            if (f[0] == 3) for (int[] q : fields(w, f[1], f[1] + f[2])) if (q[0] == 1) { int[] p = {q[1]}; drain = (int) varint(w, p); }
        }
        check("winter: 6[0].drain_w = the live 3200 W", drain == 3200);
        List<int[]> temps = fields(w, b8[1], b8[1] + b8[2]);
        check("winter: 8.2 has 4 temperatures, no 8.1 in the add-on", count(temps, 2) == 4 && count(temps, 1) == 0);
        check("winter: block 10 has its 5 parts", fields(w, b10[1], b10[1] + b10[2]).size() >= 5);
        // 8.2's first energy axis scaled by 62900 / 77400
        int off = EvModelTemplate.WINTER_AXIS_OFFSETS[0] + tmpl.length;
        int[] ap = {off};
        long v = varint(w, ap);
        long want = (EvModelTemplate.WINTER_AXIS_WH[0] * 62900L + 38700) / 77400;
        check("winter: 8.2 energy axis scaled to the learned capacity", v == want);
        // the 5 kWh 8.2 time on the cold rows is slower than the warm one
        check("winter: cold rows are slower (5 C 350 kW: seconds to full > the 22 C row's)",
                EvModelTemplate.WINTER_TIME_S[0] > EvModelTemplate.WINTER_TIME_S[EvModelTemplate.WINTER_TIME_S.length * 3 / 4]);

        // the drain's hysteresis
        EvModelSender.resetAux();
        check("drain: first reading sent at once (rounded)", EvModelSender.auxStep(3240, 0) == 3200);
        check("drain: a 300 W move is ignored", EvModelSender.auxStep(3500, 1000) == 3200);
        check("drain: a 1 kW move waits for the hold", EvModelSender.auxStep(4300, 2000) == 3200);
        check("drain: sent after 60 s", EvModelSender.auxStep(4300, 62000) == 4300);
        check("drain: unknown keeps the last", EvModelSender.auxStep(-1, 63000) == 4300);
        EvModelSender.resetAux();
        check("drain: unknown before any reading: 0", EvModelSender.auxStep(-1, 0) == 0);

        System.exit(fail);
    }
}
