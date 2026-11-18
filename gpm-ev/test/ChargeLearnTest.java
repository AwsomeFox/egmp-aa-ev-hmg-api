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
 * ChargeLearn and ChargeCurve: the charge-time tables from a curve (exactly the
 * generator's for the model's curve), learning on synthetic DC sessions, and (EV_LOGS=<folder
 * of egmp-*.csv>) a replay of real logs, printing what each session taught.
 */
public class ChargeLearnTest {
    static int passed, failed;

    static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static final int CAP = EvModelTemplate.CAPACITY_WH;

    /**
     * A DC session: 5 s rows from soc0 to soc1 on a charger of chargerKw, the car drawing
     * trueScale x the model's power (charger-side), capped by the charger; navigation to it
     * logged a minute before. Returns the row time after it.
     */
    static long session(long t, float soc0, float soc1, float chargerKw, float trueScale, float packC) {
        ChargeLearn.row(t, (int) (soc0 * CAP), CAP, false, chargerKw, packC);   // arriving, guided
        t += 60;
        double e = soc0 * CAP;
        float[] model = ChargeCurve.model();
        int i = 0;
        while (e < soc1 * CAP) {
            double p = Math.min(chargerKw * 1000.0, trueScale * ChargeCurve.power(model, e));
            if (i < 12) {
                p *= 0.4 + 0.05 * i;   // the ramp at the start
            }
            e += p * EvModelTemplate.CHARGE_ETA * 5 / 3600;
            t += 5;
            i++;
            ChargeLearn.row(t, (int) Math.round(e), CAP, true, Float.NaN, packC);
        }
        t += 5;
        ChargeLearn.row(t, (int) Math.round(e), CAP, false, Float.NaN, packC);   // unplugged
        return t;
    }

    public static void main(String[] args) throws Exception {
        // the generator's own tables from the model's own curve, to the second
        int[] s = ChargeCurve.tableSeconds(ChargeCurve.model());
        check(Arrays.equals(s, EvModelTemplate.TIME_S), "tableSeconds(model) == TIME_S");
        // a curve at half power: the DC rows take about twice as long
        float[] half = ChargeCurve.model();
        for (int i = 0; i < half.length; i++) half[i] /= 2;
        int[] h = ChargeCurve.tableSeconds(half);
        int row350 = 0, last = EvModelTemplate.HEADROOM_STEPS.length;
        double r = (double) h[row350] / s[row350];
        check(r > 1.9 && r < 2.1, "half the power: 350 kW row from empty " + r + "x");
        int ac = (EvModelTemplate.CHARGERS_W.length - 1) * last;   // the 1.44 kW row: the outlet, not the car
        check(Math.abs(h[ac] - s[ac]) < 0.02 * s[ac], "the 1.44 kW row barely moves: " + h[ac] + " vs " + s[ac]);

        // nothing learned: the model's curve
        ChargeLearn.resetForTest();
        check(ChargeLearn.curveW() == null && ChargeLearn.scaleAt(0.5f) == 1f, "nothing learned: model");

        // an EV6-like car: 0.9 x the model everywhere, at 350 kW chargers Maps guided to
        long t = 0;
        for (int k = 0; k < 3; k++) {
            t = session(t + 3600, 0.2f, 0.85f, 350f, 0.9f, 25f);
        }
        check(ChargeLearn.sessions() == 3, "3 sessions: " + ChargeLearn.sessions());
        float mid = ChargeLearn.scaleAt(0.6f);
        float want = (2 * ((2 + 3 * 0.9f) / 5) + 3 * 0.9f) / 5;   // (PRIOR g + 3 x 0.9) / (PRIOR + 3)
        check(Math.abs(mid - want) < 0.03f, "60%: " + mid + " ~ " + want);
        check(ChargeLearn.scaleAt(0.95f) < 1f && ChargeLearn.scaleAt(0.95f) > mid,
                "95% (never charged there): the car's overall scale, " + ChargeLearn.scaleAt(0.95f));
        float[] c = ChargeLearn.curveW();
        check(c != null && c[4] < EvModelTemplate.CURVE_W[4], "the curve is lower: " + (c == null ? null : c[4]));
        int[] learned = ChargeCurve.tableSeconds(c);
        check(learned[0] > s[0], "so the 350 kW row takes longer: " + learned[0] + " s vs " + s[0]);

        // a charger slower than the car teaches nothing: a 50 kW post never makes it look slow
        ChargeLearn.resetForTest();
        t = session(0, 0.2f, 0.7f, 50f, 1f, 25f);
        check(ChargeLearn.sessions() == 0 && ChargeLearn.curveW() == null, "50 kW post: nothing learned");
        // ... nor a charger Maps wasn't guiding to
        ChargeLearn.resetForTest();
        long t0 = 10000;
        ChargeLearn.row(t0, 20000, CAP, false, Float.NaN, 25f);
        double e = 20000;
        for (int i = 1; i < 200; i++) {
            e += 100000 * 0.95 * 5 / 3600;
            ChargeLearn.row(t0 + 5 * i, (int) e, CAP, true, Float.NaN, 25f);
        }
        ChargeLearn.row(t0 + 1005, (int) e, CAP, false, Float.NaN, 25f);
        check(ChargeLearn.sessions() == 0, "an unknown charger: nothing learned");
        // ... nor a Tesla Supercharger row (boost-limited)
        ChargeLearn.resetForTest();
        session(0, 0.2f, 0.7f, 250f, 0.4f, 25f);
        check(ChargeLearn.sessions() == 0, "Supercharger: nothing learned");
        // ... nor a cold pack
        ChargeLearn.resetForTest();
        session(0, 0.2f, 0.7f, 350f, 0.5f, 8f);
        check(ChargeLearn.sessions() == 0, "cold pack: nothing learned");

        // a 400 V car (a third of the model) on another EV's prior: most of the way in one session
        ChargeLearn.resetForTest();
        ChargeLearn.setPrior(ChargeLearn.PRIOR_OTHER);
        session(0, 0.1f, 0.8f, 350f, 0.33f, 25f);
        float k = ChargeLearn.scaleAt(0.4f);
        check(k < 0.55f, "400 V car after one session: " + k);
        check(ChargeLearn.scaleAt(0.0f) >= EvModelTemplate.CHARGE_SCALE_MIN, "clamped at the minimum");

        // off: model, nothing learned
        ChargeLearn.resetForTest();
        ChargeLearn.setEnabled(false);
        session(0, 0.2f, 0.8f, 350f, 0.5f, 25f);
        check(ChargeLearn.curveW() == null && ChargeLearn.sessions() == 0, "off: model");
        ChargeLearn.setEnabled(true);
        ChargeLearn.resetForTest();

        String dir = System.getenv("EV_LOGS");
        if (dir != null && !dir.isEmpty()) {
            replay(new File(dir));
        }
        System.out.println("ChargeLearnTest: " + passed + " passed, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    /** Replays a folder of logs through ChargeLearn in name order (EV_LOGS). */
    static void replay(File dir) throws Exception {
        ChargeLearn.resetForTest();
        File[] files = dir.listFiles();
        Arrays.sort(files);
        long base = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".csv")) {
                continue;
            }
            List<String[]> rows = new ArrayList<String[]>();
            Map<String, Integer> col = new HashMap<String, Integer>();
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    } else if (col.isEmpty()) {
                        String[] hd = line.split(",", -1);
                        for (int i = 0; i < hd.length; i++) col.put(hd[i], i);
                    } else {
                        rows.add(line.split(",", -1));
                    }
                }
            } finally {
                r.close();
            }
            int before = ChargeLearn.sessions();
            long last = base;
            for (String[] v : rows) {
                long t = base + (long) num(v, col, "t_s", 0);
                last = t;
                ChargeLearn.row(t, (int) num(v, col, "energy_wh", -1), (int) num(v, col, "capacity_wh", -1),
                        num(v, col, "fast_charging_raw", 0) >= 1, num(v, col, "nav_charger_kw", Float.NaN),
                        num(v, col, "pack_min_c", Float.NaN));
            }
            ChargeLearn.row(last + 1, -1, -1, false, Float.NaN, Float.NaN);   // the file's end ends a session
            base = last + 3600;
            if (ChargeLearn.sessions() != before) {
                System.out.println("  " + f.getName() + ": " + ChargeLearn.sessions() + " DC sessions; scale per 10%: "
                        + ChargeLearn.summary());
            }
        }
        float[] c = ChargeLearn.curveW();
        if (c != null) {
            StringBuilder s = new StringBuilder("  learned curve (kW):");
            for (int i = 0; i < c.length; i++) {
                s.append(' ').append(Math.round(EvModelTemplate.CURVE_WH[i] * 100f / CAP)).append("%:")
                        .append(Math.round(c[i] / 1000));
            }
            System.out.println(s);
        }
    }

    static float num(String[] v, Map<String, Integer> col, String name, float unknown) {
        Integer i = col.get(name);
        if (i == null || i >= v.length || v[i].isEmpty()) {
            return unknown;
        }
        try {
            return Float.parseFloat(v[i]);
        } catch (NumberFormatException e) {
            return unknown;
        }
    }
}
