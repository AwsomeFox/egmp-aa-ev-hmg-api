package com.google.android.projection.protocol;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RoadLearn: the on-car road load scale, on synthetic drives (always) and on
 * real EV logs (EV_LOGS=<folder of egmp-*.csv>: replays them in name order,
 * the way EvDriveLog feeds the head unit's rows, and prints the scale).
 */
public class RoadLearnTest {
    static int passed, failed;

    static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static final float A = 196f, C = 0.493f;

    /** Battery Wh for one 5 s row at kph, for a car whose real road load is trueScale x the model's. */
    static double rowWh(float kph, float trueScale, int hvacRaw, double dt) {
        double v = kph / 3.6;
        double wheel = (A * v + C * v * v * v) * (1 + EvModelTemplate.DL_FRACTION);
        double spin = EvModelTemplate.DL_SPIN_W + EvModelTemplate.DL_SPIN_W_PER_MPS * v;
        return (trueScale * wheel + spin + hvacRaw * RoadLearn.HVAC_W_PER_UNIT) * dt / 3600;
    }

    /** Drive km at kph; returns the row time after it. */
    static long drive(long t, double[] state, float km, float kph, float trueScale, int hvacRaw,
            boolean chargingHalfway) {
        double dt = 5;
        double stepKm = kph / 3.6 * dt / 1000;
        int rows = (int) Math.ceil(km / stepKm);
        for (int i = 0; i < rows; i++) {
            t += (long) dt;
            state[0] -= rowWh(kph, trueScale, hvacRaw, dt);   // energy Wh
            state[1] += stepKm;                               // odometer km
            boolean chg = chargingHalfway && i == rows / 2;
            RoadLearn.row(t, (int) Math.round(state[0]), (float) state[1], kph, 20f, hvacRaw, chg, A, C);
        }
        return t;
    }

    public static void main(String[] args) throws Exception {
        // steady highway, a car using 85% of the model's road load
        RoadLearn.resetForTest();
        double[] s = {60000, 0};
        long t = drive(0, s, 102f, 110f, 0.85f, 25, false);   // stretches overshoot 10 km by up to a row
        int n = RoadLearn.samples();
        check(n == 10, "102 km in 10 km stretches: 10 samples, got " + n);
        float want = (RoadLearn.PRIOR + 10 * 0.85f) / (RoadLearn.PRIOR + 10);
        check(Math.abs(RoadLearn.scale() - want) < 0.01f, "scale " + RoadLearn.scale() + " ~ " + want);

        // climate power is measured and taken out: same car with the heater on
        RoadLearn.resetForTest();
        s = new double[] {60000, 0};
        drive(0, s, 102f, 110f, 0.85f, 150, false);
        check(Math.abs(RoadLearn.scale() - want) < 0.01f, "3 kW of climate doesn't move it: " + RoadLearn.scale());

        // city speeds teach nothing
        RoadLearn.resetForTest();
        s = new double[] {60000, 0};
        drive(0, s, 50f, 40f, 0.85f, 25, false);
        check(RoadLearn.samples() == 0, "40 km/h: no samples, got " + RoadLearn.samples());
        check(RoadLearn.scale() == 1f, "nothing learned: scale 1");

        // charging or a gap restarts the stretch
        RoadLearn.resetForTest();
        s = new double[] {60000, 0};
        drive(0, s, 15f, 110f, 0.85f, 25, true);   // charging row at 7.5 km
        check(RoadLearn.samples() == 0, "a charging row cuts the stretch: " + RoadLearn.samples());
        RoadLearn.resetForTest();
        s = new double[] {60000, 0};
        t = drive(0, s, 8f, 110f, 0.85f, 25, false);
        drive(t + 120, s, 8f, 110f, 0.85f, 25, false);   // the unit slept 2 min
        check(RoadLearn.samples() == 0, "a 2 min gap cuts the stretch: " + RoadLearn.samples());

        // clamped: a car at half the model stops at SCALE_MIN
        RoadLearn.resetForTest();
        s = new double[] {80000, 0};
        drive(0, s, 1000.5f, 110f, 0.5f, 25, false);
        check(RoadLearn.scale() == RoadLearn.SCALE_MIN, "clamped at " + RoadLearn.SCALE_MIN + ": " + RoadLearn.scale());

        // a nonsense stretch (energy jumped) is not a sample
        RoadLearn.resetForTest();
        s = new double[] {60000, 0};
        t = drive(0, s, 5f, 110f, 1f, 25, false);
        s[0] -= 8000;   // BMS recalibration: 8 kWh gone in one row
        drive(t, s, 6f, 110f, 1f, 25, false);
        check(RoadLearn.samples() == 0, "an 8 kWh jump isn't a sample: " + RoadLearn.samples());

        // off: scale 1, nothing learned
        RoadLearn.resetForTest();
        RoadLearn.setEnabled(false);
        s = new double[] {60000, 0};
        drive(0, s, 50.5f, 110f, 0.85f, 25, false);
        check(RoadLearn.scale() == 1f && RoadLearn.samples() == 0, "off: scale 1, no samples");
        RoadLearn.setEnabled(true);

        // another EV: the shipped model counts for fewer stretches, so it learns faster
        RoadLearn.resetForTest();
        RoadLearn.setPrior(RoadLearn.PRIOR_OTHER);
        s = new double[] {60000, 0};
        drive(0, s, 102f, 110f, 0.85f, 25, false);
        float other = (RoadLearn.PRIOR_OTHER + 10 * 0.85f) / (RoadLearn.PRIOR_OTHER + 10);
        check(Math.abs(RoadLearn.scale() - other) < 0.01f && other < want - 0.04f,
                "not E-GMP: " + RoadLearn.scale() + " ~ " + other + " after 100 km (E-GMP " + want + ")");
        RoadLearn.resetForTest();

        // the same cold penalty as the sender's
        check(RoadLearn.coldDeltaA(15f) == 0f && Math.abs(RoadLearn.coldDeltaA(-6.7f) - 156f) < 0.01f
                && RoadLearn.coldDeltaA(-50f) == 679f, "cold penalty matches COLD_DELTA_A");

        String dir = System.getenv("EV_LOGS");
        if (dir != null && !dir.isEmpty()) {
            replay(new File(dir));
        }
        System.out.println("RoadLearnTest: " + passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    /** Replays a folder of logs through RoadLearn in name order (EV_LOGS). */
    static void replay(File dir) throws Exception {
        RoadLearn.resetForTest();
        File[] files = dir.listFiles();
        Arrays.sort(files);
        long base = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".csv")) {
                continue;
            }
            String product = "";
            List<String[]> rows = new ArrayList<String[]>();
            Map<String, Integer> col = new HashMap<String, Integer>();
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("# product=")) {
                        product = line.substring(10);
                    } else if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    } else if (col.isEmpty()) {
                        String[] h = line.split(",", -1);
                        for (int i = 0; i < h.length; i++) {
                            col.put(h[i], i);
                        }
                    } else {
                        rows.add(line.split(",", -1));
                    }
                }
            } finally {
                r.close();
            }
            int ci = EvModelSender.carIndex(product);
            float a = ci < 0 ? EvModelTemplate.ROAD_A_N : EvModelTemplate.CAR_ROAD_A_N[ci];
            float c = ci < 0 ? EvModelTemplate.ROAD_C_N : EvModelTemplate.CAR_ROAD_C_N[ci];
            long last = base;
            for (String[] v : rows) {
                long t = base + (long) num(v, col, "t_s", 0);
                last = t;
                boolean chg = num(v, col, "chg_sts_raw", 0) >= 1 || num(v, col, "fast_charging_raw", 0) >= 1;
                RoadLearn.row(t, (int) num(v, col, "energy_wh", -1), num(v, col, "odo_delta_km", Float.NaN),
                        num(v, col, "speed_avg_kph", Float.NaN), num(v, col, "out_temp_c", Float.NaN),
                        (int) num(v, col, "hvac_raw", -1), chg, a, c);
            }
            base = last + 3600;   // files are separate drives
            System.out.println("  " + f.getName() + ": scale " + RoadLearn.scale() + " after "
                    + RoadLearn.samples() + " stretches");
        }
    }

    static float num(String[] v, Map<String, Integer> col, String name, float unknown) {
        Integer i = col.get(name);
        if (i == null || i >= v.length || v[i].isEmpty()) {
            return unknown;
        }
        try {
            float x = Float.parseFloat(v[i]);
            return name.equals("out_temp_c") && x <= -40f ? Float.NaN : x;
        } catch (NumberFormatException e) {
            return unknown;
        }
    }
}
