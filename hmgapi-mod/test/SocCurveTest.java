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
        check("raw 1% shows ~3% (energy is 0 at 2%)", Math.abs(p[1] - (2f + 59f / 60.3f)) < 0.01f);
        check("raw 60% shows ~60.7% (through the car's 60.3 -> 61)", Math.abs(p[2 * 7 + 1] - (2f + 59f / 60.3f * 60f)) < 0.01f);
        float[] low = points(SocCurve.message(2f, 4f));
        check("raw under 5%: the plain 2%-zero line", Math.abs(low[2 * 6 + 1] - (2f + 0.98f * 50f)) < 0.01f && mapsKeepsAll(low));
        float[] hi = points(SocCurve.message(90f, 99.5f));
        check("a steep curve is clamped at 100 and still kept", mapsKeepsAll(hi) && hi[hi.length - 1] == 100f);
        System.exit(fail);
    }
}
