package com.google.android.projection.protocol;

/**
 * The battery % curve: Android Auto sensor 26, so Google Maps' battery percentages
 * match the car's own.
 *
 * Maps (26.44) registers a handler for its car sensor 25 and reads it as cdpz {1: cdqu
 * {4[]: cdqt {3: float raw %, 4: float shown %}}}. Every battery percent it shows goes
 * through it (current level, arrival at stops and the destination, its 0 % checks): raw
 * % = energy / capacity x 100, then linear between the points, with (0, 0) and
 * (100, 100) assumed at the ends. It keeps a point only if 0 < raw < 100 and
 * 0 <= shown <= 100, raw rising and shown not falling.
 *
 * Maps' sensor numbers aren't the wire's: its 25 arrives on the wire as SensorBatch
 * field 26 (Open Android Auto's RAW_EV_TRIP_SETTINGS {1: bytes}), with cdpz inside field
 * 1. Found on a US Ioniq 5 (2026-11-10): a curve adding 20 points showed in Maps that
 * way, and not as field 25 or as an unwrapped field 26.
 *
 * On E-GMP the BMS energy reaches 0 near display SOC 2 %, so Maps' own percent
 * (energy / capacity) reads a little under the car's, most near empty. The curve puts
 * raw 0 at shown ZERO_PCT and passes through the car's current (raw, display) pair.
 */
final class SocCurve {
    /** the SensorBatch field (and sensor type the library must accept) */
    static final int SENSOR = 26;
    static final float ZERO_PCT = 2f;
    static final float[] RAW_POINTS = {1f, 5f, 10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 95f, 99f};

    private SocCurve() {
    }

    /** shown % for each RAW_POINTS entry: through (0, ZERO_PCT) and (rawNow, shownNow), clamped, not falling */
    static float[] shown(float rawNow, float shownNow) {
        float slope;
        if (rawNow >= 5f && rawNow <= 100f && shownNow > ZERO_PCT && shownNow <= 100f) {
            slope = (shownNow - ZERO_PCT) / rawNow;
        } else {
            slope = (100f - ZERO_PCT) / 100f;
        }
        float[] out = new float[RAW_POINTS.length];
        float last = 0f;
        for (int i = 0; i < RAW_POINTS.length; i++) {
            float y = ZERO_PCT + slope * RAW_POINTS[i];
            y = Math.max(last, Math.min(100f, Math.max(0f, y)));
            out[i] = y;
            last = y;
        }
        return out;
    }

    /** the whole 0x8003 message: SensorBatch {26: {1: cdpz}} */
    static byte[] message(float rawNow, float shownNow) {
        float[] y = shown(rawNow, shownNow);
        Buf cdqu = new Buf();
        for (int i = 0; i < RAW_POINTS.length; i++) {
            Buf pt = new Buf();
            pt.tag(3, 5).fixed32(Float.floatToIntBits(RAW_POINTS[i]));
            pt.tag(4, 5).fixed32(Float.floatToIntBits(y[i]));
            cdqu.tag(4, 2).bytes(pt.toBytes());
        }
        byte[] cdpz = new Buf().tag(1, 2).bytes(cdqu.toBytes()).toBytes();
        byte[] field = new Buf().tag(1, 2).bytes(cdpz).toBytes();
        Buf msg = new Buf();
        msg.raw(0x80).raw(0x03);
        msg.tag(SENSOR, 2).bytes(field);
        return msg.toBytes();
    }

    /** a tiny protobuf writer */
    static final class Buf {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        Buf raw(int b) {
            out.write(b);
            return this;
        }

        Buf varint(long v) {
            while ((v & ~0x7fL) != 0) {
                out.write((int) ((v & 0x7f) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
            return this;
        }

        Buf tag(int field, int wire) {
            return varint(((long) field << 3) | wire);
        }

        Buf fixed32(int v) {
            for (int i = 0; i < 4; i++) {
                out.write((v >>> (8 * i)) & 0xff);
            }
            return this;
        }

        Buf bytes(byte[] b) {
            varint(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        byte[] toBytes() {
            return out.toByteArray();
        }
    }
}
