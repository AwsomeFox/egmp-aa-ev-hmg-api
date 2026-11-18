package com.google.android.projection.protocol;

import android.util.Log;

/**
 * On-car DC charge curve learning: how fast this car really charges, per SOC band, so
 * Maps' charge times (8.1) and max charge power (4.1) come from the car's own DC stops.
 *
 * EvDriveLog feeds it every log row. During a DC session (fast charging), every WINDOW_S
 * gives the battery-side power from the BMS energy, made charger-side (/ CHARGE_ETA) as
 * the curve is, and compared with the model's power at that SOC: a ratio, kept per
 * SOC band (BINS of 10%). A window counts only when the car, not the charger, set the
 * pace:
 *   - the charger's power is known (Maps was guiding to it: nav_charger_kw, the last
 *     value seen up to CHARGER_MEMORY_S before plugging in), and the window ran below
 *     CHARGER_MARGIN of it. A session at an unknown charger teaches nothing, so a slow
 *     50 kW post never makes the car look slow;
 *   - not a Tesla Supercharger row (250/325 kW: the boost converter's flat ~97 kW, which
 *     the model already has);
 *   - not the first RAMP_S (the charger ramping up), not a cold pack (below MIN_PACK_C,
 *     unknown counts as warm: the model is the preconditioned curve).
 * At the end of a session, windows far below the session's median are dropped (a charger's
 * brief dip, not the car's curve); then each band's median ratio (MIN_WINDOWS_BIN or more
 * windows) is one sample, and the median of all the session's windows one sample of the
 * car's overall scale.
 *
 * scaleAt(SOC) = (PRIOR x g + band's samples) / (PRIOR + band's n), where g is the overall
 * scale learned the same way from 1.0: a band never charged in uses the car's overall
 * scale, a band charged in moves to its own. Clamped to [CHARGE_SCALE_MIN,
 * CHARGE_SCALE_MAX]. PRIOR is 2 sessions on E-GMP (the model was fitted on E-GMP stops)
 * and PRIOR_OTHER on other EVs (setPrior), whose 400 V packs charge far slower than it.
 *
 * Kept across sessions in FILE. EvModelSender reads curveW() once per Android Auto
 * session, so a route never re-plans because of it. Off with the same setting as
 * RoadLearn ("Learn this car"). Any thread; never throws out of row().
 */
final class ChargeLearn {
    private static final String TAG = "ChargeLearn";
    static final int BINS = 10;
    static final long WINDOW_S = 60;
    static final long RAMP_S = 180;
    static final long MAX_GAP_S = 60;
    static final long CHARGER_MEMORY_S = 600;
    static final float CHARGER_MARGIN = 0.9f;
    static final float MIN_PACK_C = 20f;
    static final float PRIOR = 2f;
    static final float PRIOR_OTHER = 0.5f;
    static final float RATIO_MIN = 0.1f;   // a window outside this is noise
    static final float RATIO_MAX = 1.6f;
    static final int MIN_WINDOWS_BIN = 3;
    /** a window this far below the session's median is a dip (the charger, a handshake), not the car */
    static final float DIP = 0.6f;
    static final int MIN_WINDOWS_SESSION = 3;
    static final String FILE = "charge_curve";

    private static final Object LOCK = new Object();
    private static boolean sEnabled = true;
    private static boolean sLoaded;
    private static volatile float sPrior = PRIOR;
    private static final double[] sBinSum = new double[BINS];
    private static final int[] sBinN = new int[BINS];
    private static double sAllSum;
    private static int sAllN;
    private static Session sCur;
    private static float sLastChargerKw = Float.NaN;
    private static long sLastChargerT;

    private ChargeLearn() {}

    /** One DC session being measured. */
    private static final class Session {
        long t0, last, wT;
        int wE;
        float chargerKw;
        boolean useless;   // Supercharger row, AC: nothing to learn
        final java.util.List<Integer> bin = new java.util.ArrayList<Integer>();
        final java.util.List<Float> all = new java.util.ArrayList<Float>();
    }

    /** Off: no learning and the model's own curve; turning it off forgets. */
    static void setEnabled(boolean on) {
        synchronized (LOCK) {
            if (sEnabled && !on) {
                clearLocked();
                sCur = null;
                java.io.File f = EvModelSender.appFile(FILE);
                if (f != null && f.isFile() && !f.delete()) {
                    Log.w(TAG, "could not delete " + f);
                }
                Log.i(TAG, "learning off: the model's own charge curve");
            }
            sEnabled = on;
        }
    }

    static void setPrior(float prior) {
        sPrior = prior;
    }

    /** DC sessions learned so far. */
    static int sessions() {
        synchronized (LOCK) {
            load();
            return sEnabled ? sAllN : 0;
        }
    }

    /** The learned scale on the model's power at this SOC (0..1), 1 when nothing is learned. */
    static float scaleAt(float soc) {
        synchronized (LOCK) {
            if (!sEnabled) {
                return 1f;
            }
            load();
            return scaleLocked(bin(soc));
        }
    }

    /** Caller holds LOCK. */
    private static float scaleLocked(int b) {
        float p = sPrior;
        double g = (p + sAllSum) / (p + sAllN);
        double s = (p * g + sBinSum[b]) / (p + sBinN[b]);
        return (float) Math.max(EvModelTemplate.CHARGE_SCALE_MIN, Math.min(EvModelTemplate.CHARGE_SCALE_MAX, s));
    }

    static int bin(float soc) {
        int b = (int) (soc * BINS);
        return Math.max(0, Math.min(BINS - 1, b));
    }

    /**
     * The learned curve (4.1 powers, W, at EvModelTemplate.CURVE_WH), or null while it's
     * the model's own (nothing learned, or learning off).
     */
    static float[] curveW() {
        synchronized (LOCK) {
            if (!sEnabled) {
                return null;
            }
            load();
            if (sAllN == 0) {
                return null;
            }
            int[] e = EvModelTemplate.CURVE_WH;
            float[] c = ChargeCurve.model();
            for (int i = 0; i < c.length; i++) {
                // a point on a band edge (50%: the plateau's end) belongs to the band below
                float soc = (float) e[i] / EvModelTemplate.CAPACITY_WH;
                int b = bin(Math.max(0f, soc - 1e-4f));
                c[i] = c[i] * scaleLocked(b);
            }
            return c;
        }
    }

    /** For the log header: the scale per band, "1.00 0.94 ...". */
    static String summary() {
        StringBuilder s = new StringBuilder();
        synchronized (LOCK) {
            load();
            for (int b = 0; b < BINS; b++) {
                if (s.length() > 0) {
                    s.append(' ');
                }
                s.append(Math.round((sEnabled ? scaleLocked(b) : 1f) * 100) / 100f);
            }
        }
        return s.toString();
    }

    /**
     * One log row. energyWh: BMS energy; capacityWh: the learned capacity (<= 0 unknown);
     * fast: DC charging now; navChargerKw: the charger Maps is guiding to (NaN unknown);
     * packMinC: coldest module (NaN unknown).
     */
    static void row(long tS, int energyWh, int capacityWh, boolean fast, float navChargerKw, float packMinC) {
        try {
            synchronized (LOCK) {
                if (!sEnabled) {
                    return;
                }
                load();
                step(tS, energyWh, capacityWh, fast, navChargerKw, packMinC);
            }
        } catch (Throwable t) {
            synchronized (LOCK) {
                sCur = null;
            }
            Log.w(TAG, "row failed (caught): " + t);
        }
    }

    /** Caller holds LOCK. */
    private static void step(long tS, int energyWh, int capacityWh, boolean fast, float navChargerKw,
            float packMinC) {
        if (!Float.isNaN(navChargerKw) && navChargerKw > 0) {
            sLastChargerKw = navChargerKw;
            sLastChargerT = tS;
        }
        Session c = sCur;
        if (c != null && (!fast || energyWh <= 0 || tS - c.last > MAX_GAP_S || tS <= c.last)) {
            finish(c);   // unplugged, a gap or a new log file
            sCur = c = null;
        }
        if (!fast || energyWh <= 0) {
            return;
        }
        if (c == null) {
            c = sCur = new Session();
            c.t0 = c.last = c.wT = tS;
            c.wE = energyWh;
            boolean recent = !Float.isNaN(sLastChargerKw) && tS - sLastChargerT <= CHARGER_MEMORY_S;
            c.chargerKw = recent ? sLastChargerKw : Float.NaN;
            int w = Float.isNaN(c.chargerKw) ? 0 : Math.round(c.chargerKw * 1000);
            c.useless = Float.isNaN(c.chargerKw) || ChargeCurve.supercharger(w) || w <= EvModelTemplate.AC_MAX_W;
            return;
        }
        c.last = tS;
        long dt = tS - c.wT;
        if (dt < WINDOW_S) {
            return;
        }
        int de = energyWh - c.wE;
        int eMid = (energyWh + c.wE) / 2;
        c.wT = tS;
        c.wE = energyWh;
        if (c.useless || tS - c.t0 < RAMP_S + dt || de <= 0) {
            return;
        }
        if (!Float.isNaN(packMinC) && packMinC < MIN_PACK_C) {
            return;
        }
        double pCh = de * 3600.0 / dt / EvModelTemplate.CHARGE_ETA;
        if (pCh >= CHARGER_MARGIN * c.chargerKw * 1000) {
            return;   // the charger may have set the pace
        }
        int cap = capacityWh > 0 ? capacityWh : EvModelTemplate.CAPACITY_WH;
        float soc = (float) eMid / cap;
        double model = ChargeCurve.power(ChargeCurve.model(), soc * EvModelTemplate.CAPACITY_WH);
        float ratio = (float) (pCh / model);
        if (ratio < RATIO_MIN || ratio > RATIO_MAX) {
            return;
        }
        c.bin.add(bin(soc));
        c.all.add(ratio);
    }

    /** Caller holds LOCK: one session's medians become samples. */
    private static void finish(Session c) {
        if (c.all.size() < MIN_WINDOWS_SESSION) {
            return;
        }
        float m0 = median(c.all);
        java.util.List<java.util.List<Float>> bins = new java.util.ArrayList<java.util.List<Float>>();
        java.util.List<Float> kept = new java.util.ArrayList<Float>();
        for (int b = 0; b < BINS; b++) {
            bins.add(new java.util.ArrayList<Float>());
        }
        for (int i = 0; i < c.all.size(); i++) {
            float r = c.all.get(i);
            if (r >= DIP * m0) {
                bins.get(c.bin.get(i)).add(r);
                kept.add(r);
            }
        }
        if (kept.size() < MIN_WINDOWS_SESSION) {
            return;
        }
        StringBuilder log = new StringBuilder();
        for (int b = 0; b < BINS; b++) {
            java.util.List<Float> r = bins.get(b);
            if (r.size() >= MIN_WINDOWS_BIN) {
                float m = median(r);
                sBinSum[b] += m;
                sBinN[b]++;
                log.append(' ').append(b * 10).append("%:").append(Math.round(m * 100) / 100f);
            }
        }
        sAllSum += median(kept);
        sAllN++;
        save();
        Log.i(TAG, "DC session at a " + Math.round(c.chargerKw) + " kW charger, " + kept.size()
                + " minutes measured:" + log + " of the model; " + sAllN + " sessions learned");
    }

    static float median(java.util.List<Float> v) {
        java.util.List<Float> s = new java.util.ArrayList<Float>(v);
        java.util.Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2f;
    }

    /** Caller holds LOCK. */
    private static void clearLocked() {
        java.util.Arrays.fill(sBinSum, 0);
        java.util.Arrays.fill(sBinN, 0);
        sAllSum = 0;
        sAllN = 0;
    }

    /** Test hook: forget everything in memory (not the file). */
    static void resetForTest() {
        synchronized (LOCK) {
            sEnabled = true;
            sPrior = PRIOR;
            sLoaded = true;
            clearLocked();
            sCur = null;
            sLastChargerKw = Float.NaN;
            sLastChargerT = 0;
        }
    }

    /** Caller holds LOCK. FILE: "<all sum> <all n>" then "<sum> <n>" per band. */
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
                double all = Double.parseDouble(p[0]);
                int allN = Integer.parseInt(p[1]);
                double[] sum = new double[BINS];
                int[] n = new int[BINS];
                for (int b = 0; b < BINS; b++) {
                    String[] q = r.readLine().trim().split(" ");
                    sum[b] = Double.parseDouble(q[0]);
                    n[b] = Integer.parseInt(q[1]);
                    if (n[b] < 0 || sum[b] < RATIO_MIN * n[b] || sum[b] > RATIO_MAX * n[b]) {
                        return;
                    }
                }
                if (allN < 0 || all < RATIO_MIN * allN || all > RATIO_MAX * allN) {
                    return;
                }
                sAllSum = all;
                sAllN = allN;
                System.arraycopy(sum, 0, sBinSum, 0, BINS);
                System.arraycopy(n, 0, sBinN, 0, BINS);
                Log.i(TAG, "learned charge curve loaded: " + allN + " DC sessions");
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "charge curve file unreadable: " + t);
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
                w.write((float) sAllSum + " " + sAllN + "\n");
                for (int b = 0; b < BINS; b++) {
                    w.write((float) sBinSum[b] + " " + sBinN[b] + "\n");
                }
            } finally {
                w.close();
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "charge curve file not saved: " + t);
        }
    }
}
