package com.google.android.projection.protocol;

/**
 * The model's charge-time tables (8.1) from a charge curve (4.1), on the car: the same
 * integration as tools/build_ioniq5_model.py charge_table(), so the template's own
 * curve gives the template's own seconds exactly (ScaleTest checks it). A learned curve
 * (ChargeLearn) then gives the tables Maps should see for this car.
 *
 * Curves are charger-side max power (W) at EvModelTemplate.CURVE_WH, for the reference
 * pack (CAPACITY_WH); EvModelSender scales energies and seconds by capacity afterwards.
 */
final class ChargeCurve {
    private ChargeCurve() {}

    /** The shipped curve (4.1 powers, W). */
    static float[] model() {
        int[] w = EvModelTemplate.CURVE_W;
        float[] c = new float[w.length];
        for (int i = 0; i < w.length; i++) {
            c[i] = w[i];
        }
        return c;
    }

    /** Max charge power at energyWh (reference pack), linear on the curve (interp_curve). */
    static double power(float[] curveW, double energyWh) {
        int[] e = EvModelTemplate.CURVE_WH;
        if (energyWh <= e[0]) {
            return curveW[0];
        }
        if (energyWh >= e[e.length - 1]) {
            return curveW[e.length - 1];
        }
        for (int i = 1; i < e.length; i++) {
            if (e[i - 1] <= energyWh && energyWh <= e[i]) {
                double p0 = curveW[i - 1], p1 = curveW[i];
                return p0 + (p1 - p0) * (energyWh - e[i - 1]) / (e[i] - e[i - 1]);
            }
        }
        throw new IllegalStateException("curve axis not ascending");
    }

    static boolean supercharger(int chargerW) {
        for (int s : EvModelTemplate.SUPERCHARGERS_W) {
            if (s == chargerW) {
                return true;
            }
        }
        return false;
    }

    /** Charger-side power at this energy on this charger (charger_power). */
    static double chargerPower(float[] curveW, int chargerW, double energyWh) {
        if (supercharger(chargerW)) {
            if (energyWh < EvModelTemplate.SUPERCHARGER_FLAT_TO_WH) {
                return EvModelTemplate.SUPERCHARGER_FLAT_W;
            }
            return Math.min((double) EvModelTemplate.SUPERCHARGER_FLAT_W, power(curveW, energyWh));
        }
        return Math.min((double) chargerW, power(curveW, energyWh));
    }

    static double eta(int chargerW) {
        return chargerW <= EvModelTemplate.AC_MAX_W ? EvModelTemplate.AC_ETA : EvModelTemplate.CHARGE_ETA;
    }

    /**
     * The 8.1 seconds for every row (EvModelTemplate.CHARGERS_W order), each the seconds to
     * full from HEADROOM_STEPS cells below full: TIME_S's order, for the reference pack.
     */
    static int[] tableSeconds(float[] curveW) {
        int[] chargers = EvModelTemplate.CHARGERS_W;
        int[] steps = EvModelTemplate.HEADROOM_STEPS;
        int[] out = new int[chargers.length * steps.length];
        int n = EvModelTemplate.CAPACITY_WH / EvModelTemplate.GRID_WH;
        double de = (double) EvModelTemplate.GRID_WH / EvModelTemplate.GRID_SLICES;
        int k = 0;
        for (int c : chargers) {
            double eta = eta(c);
            double[] cum = new double[n + 1];
            double seconds = 0;
            for (int i = 1; i <= n; i++) {
                int e0 = (i - 1) * EvModelTemplate.GRID_WH;
                for (int j = 0; j < EvModelTemplate.GRID_SLICES; j++) {
                    double p = chargerPower(curveW, c, e0 + (j + 0.5) * de);
                    seconds += 3600.0 * de / (p * eta);
                }
                cum[i] = seconds;
            }
            double full = cum[n];
            for (int j : steps) {
                out[k++] = (int) Math.rint(full - cum[n - j]);   // Python's round(): half to even
            }
        }
        return out;
    }
}
