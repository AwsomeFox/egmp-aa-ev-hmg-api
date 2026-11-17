package com.google.android.projection.protocol;

import android.util.Log;

/**
 * On-car road load learning: one scale on the model's wheel power (road
 * load A and C together), learned from this car's own steady drives.
 *
 * EvDriveLog feeds it every log row. A stretch is STRETCH_KM of driving with
 * no charging and no gap over MAX_GAP_S between rows. When one ends at an
 * average of MIN_KPH or more, it gives one sample:
 *
 *   (energy used - drivetrain spin - climate) / (wheel power x (1 + loss fraction))
 *
 * Wheel power is the model's: (A + cold penalty) x v + C x v^3 at the logged
 * speed, scaled to the odometer distance. The car's display speed reads a few
 * percent high, and v^3 would carry that into the scale. Climate is the
 * measured HEV 0x4f power. So the sample is how far this car's real road load
 * is from the model's, at the model's own losses. One number is robust where
 * A and C apart are not: highway data can't separate them
 * (docs/ENERGY-MODEL.md).
 *
 * scale() = (PRIOR + sum of samples) / (PRIOR + n), clamped to
 * [SCALE_MIN, SCALE_MAX]: the shipped model counts as PRIOR stretches at
 * 1.0, so one odd drive moves it little. Replaying a 2022 EV6's logs
 * (issue #13) from the Ioniq 5 values: the scale was 0.84 after the first
 * day, and the second day's highway energy went from +10% to -1% off. From
 * the EV6's own values it stays at ~1.0.
 *
 * Kept across sessions in FILE ("<sum> <n>"). The sender reads scale() once
 * per Android Auto session (EvModelSender.start), so a route never re-plans
 * because of it. Any thread; never throws out of row().
 */
final class RoadLearn {
    private static final String TAG = "RoadLearn";
    static final float STRETCH_KM = 10f;
    static final float MIN_KPH = 60f;
    static final long MAX_GAP_S = 30;
    static final float PRIOR = 20f;
    static final float SCALE_MIN = 0.7f;
    static final float SCALE_MAX = 1.3f;
    static final float SAMPLE_MIN = 0.4f;   // a sample outside this is a bad stretch
    static final float SAMPLE_MAX = 2.0f;
    static final float HVAC_W_PER_UNIT = 20f;
    static final String FILE = "road_scale";

    private static final Object LOCK = new Object();
    private static boolean sEnabled = true;
    private static boolean sLoaded;
    private static double sSum;
    private static int sN;
    private static Stretch sCur;

    private RoadLearn() {}

    /** One stretch being measured. */
    private static final class Stretch {
        long t0, last;
        int e0;
        float odo0;
        double sumV, sumAv, sumCv3, spin, hvac;   // m, N*m, N*m (x C), J, J
    }

    /** Off: scale() is 1 and nothing is learned or kept; turning it off forgets. */
    static void setEnabled(boolean on) {
        synchronized (LOCK) {
            if (sEnabled && !on) {
                sSum = 0;
                sN = 0;
                sCur = null;
                java.io.File f = EvModelSender.appFile(FILE);
                if (f != null && f.isFile() && !f.delete()) {
                    Log.w(TAG, "could not delete " + f);
                }
                Log.i(TAG, "learning off: road scale back to 1");
            }
            sEnabled = on;
        }
    }

    /** The learned scale on the model's wheel power, 1 when nothing is learned. */
    static float scale() {
        synchronized (LOCK) {
            if (!sEnabled) {
                return 1f;
            }
            load();
            return scaleOf(sSum, sN);
        }
    }

    /** Stretches learned so far. */
    static int samples() {
        synchronized (LOCK) {
            load();
            return sEnabled ? sN : 0;
        }
    }

    static float scaleOf(double sum, int n) {
        float s = (float) ((PRIOR + sum) / (PRIOR + n));
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, s));
    }

    /**
     * One log row. Any value may be unknown: energyWh < 0, odoKm or speedKph
     * NaN, outC NaN (no cold penalty), hvacRaw < 0 (no climate).
     * baseA / baseC: this model's road load before scaling.
     */
    static void row(long tS, int energyWh, float odoKm, float speedKph, float outC,
            int hvacRaw, boolean charging, float baseA, float baseC) {
        try {
            synchronized (LOCK) {
                if (!sEnabled) {
                    return;
                }
                load();
                step(tS, energyWh, odoKm, speedKph, outC, hvacRaw, charging, baseA, baseC);
            }
        } catch (Throwable t) {
            synchronized (LOCK) {
                sCur = null;
            }
            Log.w(TAG, "row failed (caught): " + t);
        }
    }

    /** Caller holds LOCK. */
    private static void step(long tS, int energyWh, float odoKm, float speedKph, float outC,
            int hvacRaw, boolean charging, float baseA, float baseC) {
        if (charging || energyWh <= 0 || Float.isNaN(odoKm) || Float.isNaN(speedKph)) {
            sCur = null;
            return;
        }
        Stretch c = sCur;
        if (c != null && (tS - c.last > MAX_GAP_S || tS <= c.last || odoKm < c.odo0)) {
            c = null;   // a gap (the unit slept) or a new log file: start over
        }
        if (c == null) {
            sCur = start(tS, energyWh, odoKm);
            return;
        }
        double dt = tS - c.last;
        c.last = tS;
        double v = speedKph / 3.6;
        double a = baseA + (Float.isNaN(outC) ? 0f : coldDeltaA(outC));
        c.sumV += v * dt;
        c.sumAv += a * v * dt;
        c.sumCv3 += baseC * v * v * v * dt;
        c.spin += (EvModelTemplate.DL_SPIN_W + EvModelTemplate.DL_SPIN_W_PER_MPS * v) * dt;
        c.hvac += (hvacRaw > 0 ? hvacRaw * HVAC_W_PER_UNIT : 0f) * dt;
        double d = (odoKm - c.odo0) * 1000.0;
        if (d < STRETCH_KM * 1000.0) {
            return;
        }
        double t = tS - c.t0;
        double used = (c.e0 - energyWh) * 3600.0;
        if (t > 0 && d / t * 3.6 >= MIN_KPH && used > 0 && c.sumV > 0) {
            double k = d / c.sumV;
            double wheel = (c.sumAv * k + c.sumCv3 * k * k * k) * (1 + EvModelTemplate.DL_FRACTION);
            double sample = (used - c.spin - c.hvac) / wheel;
            if (sample >= SAMPLE_MIN && sample <= SAMPLE_MAX) {
                sSum += sample;
                sN++;
                save();
                Log.i(TAG, "stretch " + Math.round(d / 100) / 10f + " km at "
                        + Math.round(d / t * 3.6) + " km/h: " + Math.round(sample * 1000) / 1000f
                        + " of the model; scale " + scaleOf(sSum, sN) + " after " + sN);
            }
        }
        sCur = start(tS, energyWh, odoKm);
    }

    private static Stretch start(long tS, int energyWh, float odoKm) {
        Stretch s = new Stretch();
        s.t0 = tS;
        s.last = tS;
        s.e0 = energyWh;
        s.odo0 = odoKm;
        return s;
    }

    /** Extra road load (N) at temp C, as the sender adds it (COLD_TEMPS_C runs warm to cold). */
    static float coldDeltaA(float t) {
        float[] ts = EvModelTemplate.COLD_TEMPS_C;
        float[] da = EvModelTemplate.COLD_DELTA_A_N;
        if (t >= ts[0]) {
            return da[0];
        }
        for (int i = 1; i < ts.length; i++) {
            if (t >= ts[i]) {
                return da[i - 1] + (da[i] - da[i - 1]) * (t - ts[i - 1]) / (ts[i] - ts[i - 1]);
            }
        }
        return da[da.length - 1];
    }

    /** Test hook: forget everything in memory (not the file). */
    static void resetForTest() {
        synchronized (LOCK) {
            sEnabled = true;
            sLoaded = true;
            sSum = 0;
            sN = 0;
            sCur = null;
        }
    }

    /** Caller holds LOCK. */
    private static void load() {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        java.io.File f = EvModelSender.appFile(FILE);
        if (f == null || !f.isFile()) {
            return;
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                String[] p = r.readLine().trim().split(" ");
                double sum = Double.parseDouble(p[0]);
                int n = Integer.parseInt(p[1]);
                if (n >= 0 && n < 1000000 && sum >= SAMPLE_MIN * n && sum <= SAMPLE_MAX * n) {
                    sSum = sum;
                    sN = n;
                    Log.i(TAG, "learned road scale loaded: " + scaleOf(sum, n) + " from " + n
                            + " stretches");
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "road scale file unreadable: " + t);
        }
    }

    /** Caller holds LOCK. */
    private static void save() {
        java.io.File f = EvModelSender.appFile(FILE);
        if (f == null) {
            return;
        }
        try {
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            try {
                w.write((float) sSum + " " + sN + "\n");
            } finally {
                w.close();
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "road scale file not saved: " + t);
        }
    }
}
