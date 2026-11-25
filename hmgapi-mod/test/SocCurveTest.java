package com.google.android.projection.protocol;

import java.util.ArrayList;
import java.util.List;

/** SocCurve: the battery % curve message, decoded the way Google Maps reads it. */
public final class SocCurveTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    /** {field, wire, value} for each field: length-delimited or fixed32 */
    static List<Object[]> fields(byte[] b, int from, int to) {
        List<Object[]> out = new ArrayList<Object[]>();
        int i = from;
        while (i < to) {
            long key = 0;
            int sh = 0;
            while (true) {
                int c = b[i++] & 0xff;
                key |= (long) (c & 0x7f) << sh;
                sh += 7;
                if ((c & 0x80) == 0) break;
            }
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            byte[] v;
            if (wire == 2) {
                int len = 0;
                sh = 0;
                while (true) {
                    int c = b[i++] & 0xff;
                    len |= (c & 0x7f) << sh;
                    sh += 7;
                    if ((c & 0x80) == 0) break;
                }
                v = java.util.Arrays.copyOfRange(b, i, i + len);
                i += len;
            } else if (wire == 5) {
                v = java.util.Arrays.copyOfRange(b, i, i + 4);
                i += 4;
            } else if (wire == 0) {
                int from0 = i;
                while ((b[i++] & 0x80) != 0) { }
                v = java.util.Arrays.copyOfRange(b, from0, i);
            } else {
                throw new IllegalStateException("wire " + wire);
            }
            out.add(new Object[] {field, wire, v});
        }
        return out;
    }

    static List<Object[]> fields(byte[] b) {
        return fields(b, 0, b.length);
    }

    static float f32(byte[] v) {
        return Float.intBitsToFloat((v[0] & 0xff) | (v[1] & 0xff) << 8 | (v[2] & 0xff) << 16 | (v[3] & 0xff) << 24);
    }

    /** the whole 0x8003 message -> [x0, y0, x1, y1, ...], as Maps gets it: field 26, its field 1 = cdpz */
    static float[] points(byte[] msg) {
        check("0x8003", (msg[0] & 0xff) == 0x80 && msg[1] == 3);
        List<Object[]> batch = fields(msg, 2, msg.length);
        check("one SensorBatch field: 26", batch.size() == 1 && (Integer) batch.get(0)[0] == 26);
        List<Object[]> wrap = fields((byte[]) batch.get(0)[2]);
        check("field 26 = {1: cdpz}", wrap.size() == 1 && (Integer) wrap.get(0)[0] == 1);
        List<Object[]> cdpz = fields((byte[]) wrap.get(0)[2]);
        check("cdpz = {1: cdqu}", cdpz.size() == 1 && (Integer) cdpz.get(0)[0] == 1);
        List<Object[]> pts = fields((byte[]) cdpz.get(0)[2]);
        float[] out = new float[pts.size() * 2];
        for (int k = 0; k < pts.size(); k++) {
            if ((Integer) pts.get(k)[0] != 4) check("cdqu: only field 4", false);
            for (Object[] f : fields((byte[]) pts.get(k)[2])) {
                if ((Integer) f[0] == 3) out[2 * k] = f32((byte[]) f[2]);
                if ((Integer) f[0] == 4) out[2 * k + 1] = f32((byte[]) f[2]);
            }
        }
        return out;
    }

    static int varint(byte[] v) {
        int r = 0, sh = 0;
        for (byte c : v) {
            r |= (c & 0x7f) << sh;
            sh += 7;
        }
        return r;
    }

    /** cdqu's fields: {1 charger %, 2 destination %, count of 4 points}; -1 where absent */
    static int[] cdqu(byte[] msg) {
        List<Object[]> batch = fields(msg, 2, msg.length);
        List<Object[]> wrap = fields((byte[]) batch.get(0)[2]);
        List<Object[]> cdpz = fields((byte[]) wrap.get(0)[2]);
        int[] out = {-1, -1, 0};
        for (Object[] f : fields((byte[]) cdpz.get(0)[2])) {
            int n = (Integer) f[0];
            if (n == 1 && (Integer) f[1] == 0) out[0] = varint((byte[]) f[2]);
            else if (n == 2 && (Integer) f[1] == 0) out[1] = varint((byte[]) f[2]);
            else if (n == 4) out[2]++;
            else check("cdqu: unexpected field " + n, false);
        }
        return out;
    }

    /** Maps' rules: 0 < x < 100, 0 <= y <= 100, x rising, y not falling */
    static boolean mapsKeepsAll(float[] p) {
        for (int k = 0; k < p.length / 2; k++) {
            float x = p[2 * k], y = p[2 * k + 1];
            if (!(x > 0f && x < 100f && y >= 0f && y <= 100f)) return false;
            if (k > 0 && (x <= p[2 * k - 2] || y < p[2 * k - 1])) return false;
        }
        return true;
    }

    public static void main(String[] a) {
        float[] p = points(SocCurve.message(60.3f, 61f));
        check("13 points", p.length == 26);
        check("Maps keeps every point", mapsKeepsAll(p));
        float z = SocCurve.ZERO_PCT;
        check("energy is 0 at display 2.3%", z == 2.3f);
        check("raw 1% shows ~3.3% (energy is 0 at 2.3%)", Math.abs(p[1] - (z + (61f - z) / 60.3f)) < 0.01f);
        check("raw 60% shows ~60.7% (through the car's 60.3 -> 61)", Math.abs(p[2 * 7 + 1] - (z + (61f - z) / 60.3f * 60f)) < 0.01f);
        float[] low = points(SocCurve.message(2f, 4f));
        check("raw under 5%: the plain 2.3%-zero line", Math.abs(low[2 * 6 + 1] - (z + (100f - z) / 100f * 50f)) < 0.01f && mapsKeepsAll(low));
        float[] hi = points(SocCurve.message(90f, 99.5f));
        check("a steep curve is clamped at 100 and still kept", mapsKeepsAll(hi) && hi[hi.length - 1] == 100f);

        // the EV trip settings (cdqu 1 and 2)
        check("curve only: the same bytes as before", java.util.Arrays.equals(SocCurve.message(60.3f, 61f),
                SocCurve.message(60.3f, 61f, true, 0, 0)));
        int[] t = cdqu(SocCurve.message(60.3f, 61f, true, 15, 30));
        check("curve + targets: charger 15%, destination 30%, 13 points", t[0] == 15 && t[1] == 30 && t[2] == 13);
        t = cdqu(SocCurve.message(0f, 0f, false, 10, 0));
        check("targets only: charger 10%, no destination, no points", t[0] == 10 && t[1] == -1 && t[2] == 0);
        t = cdqu(SocCurve.message(0f, 0f, false, 0, 80));
        check("destination only", t[0] == -1 && t[1] == 80 && t[2] == 0);
        t = cdqu(SocCurve.message(50f, 51f, true, 99, 150));
        check("clamped: charger to 50%, destination to 100%", t[0] == 50 && t[1] == 100);
        t = cdqu(SocCurve.message(50f, 51f, true, -5, 0));
        check("negative = not sent", t[0] == -1 && t[1] == -1);
        System.exit(fail);
    }
}
