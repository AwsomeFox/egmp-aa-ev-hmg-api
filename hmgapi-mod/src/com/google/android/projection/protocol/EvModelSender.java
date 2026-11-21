package com.google.android.projection.protocol;

import android.util.Base64;
import android.util.Log;

/**
 * Periodic sender for the vehicle energy model (sensor 23).
 *
 * HMG-API build: started by HmgApiEntry after GPM's SensorSource.init()
 * (hook point gpm.sensor_source.init) and stopped before the Android Auto
 * connection is torn down (gpm.receiver.destroy,
 * gpm.receiver_service.disconnected). It sends through a Sink: GPM's own
 * SensorSource.nativeReportDiagnosticsData, which HMG-API's in-memory library
 * patch (the matching recipes/ev-sensor-*.recipe) turns into a raw
 * queueOutgoing.
 * Nothing in GPM's files is changed.
 * Every PERIOD_MS it builds a SensorBatch message from EvModelTemplate (a
 * single warm-pack model: for road-trip planning the car preconditions on the
 * way to a charger, so the ideal-temperature charge curve is the realistic
 * one) with live values patched in, and queues it through the patched
 * nativeReportDiagnosticsData (see tools/patch_library.py).
 *
 * Live data (read through EvCar, shared with the always-on EvDriveLog):
 *  - DISPLAY_SOC (HEV 0x19)
 *  - the nav battery packet: DATC_OUT_TEMP_SNSR patches model field 1.7
 *    (aux/HVAC table lookup) and road load A (cold penalty,
 *    EvModelTemplate.COLD_*)
 *  - the product id (ro.build.product) picks the road load for this model
 *    (EvModelTemplate.CAR_*, e.g. the EV6's), patched into A and C, times
 *    the scale RoadLearn has learned on this car (read once per session)
 *  - EV_AVLBL_ENRG / HEV 0x31 (BMS available energy, 2 Wh steps): current
 *    energy, and usable capacity learned as the energy at 100% display (see
 *    learnCapacity); the charge curve and charge-time tables are rescaled to
 *    that capacity so their taper stays at the same SOC (see scaleTemplate)
 *
 * Logging is not done here: EvDriveLog logs whenever the head unit is on and
 * reads this sender's state through the static getters (aaActive, roadA...).
 *
 * Threading: all sends happen holding LOCK; stop() takes LOCK and joins the
 * thread. HmgApiEntry calls stop() before SensorSource.shutdown() (and with it
 * nativeShutdown()), so a send never touches the native object after it is freed.
 */
public final class EvModelSender {
    private static final String TAG = "EvModelSender";
    private static final int PERIOD_MS = 5000;
    private static final long SHARED_MAX_AGE_MS = 1500;  // reuse EvDriveLog's reads

    private static final int HEV_FLAG_DISPLAY_SOC = 0x19;  // SOC in 0.5% steps

    // BMS remaining usable energy: HEV flag 0x31 ENERGYMAP_1000MS, the same
    // signal as the nav packet's EV_AVLBL_ENRG (docs/DATA-SOURCES.md 1.1), in
    // 2 Wh steps like BMS_EVENERGYCAPA; 0xFFFF = invalid. Usable capacity is
    // learned as the energy at 100% display. Energy reaches 0 at display
    // EvModelTemplate.ENERGY_ZERO_PCT (~2.3% on every car logged so far), so
    // a sample is energy x (100 - zero) / (SOC - zero): energy / SOC alone
    // read 1.6% low at 60% and 7% low at 30%. Samples at >= CAP_GOOD_HALF_PCT
    // win, lower ones (>= CAP_MIN_HALF_PCT) only fill in until one exists.
    // Smoothed; kept across sessions in CAP_FILE ("<Wh> <1 if good>").
    static final int ENRG_WH_PER_UNIT = 2;
    private static final int CAP_MIN_HALF_PCT = 60;      // 30% SOC
    private static final int CAP_GOOD_HALF_PCT = 120;    // 60% SOC
    private static final int CAP_MIN_WH = 30000;
    private static final int CAP_MAX_WH = 90000;
    static final int ENRG_MAX_RAW = CAP_MAX_WH / ENRG_WH_PER_UNIT;
    private static final float CAP_ALPHA = 0.1f;         // EMA weight per sample
    private static final String CAP_FILE = "capacity_wh";
    private static final Object CAP_LOCK = new Object();
    private static float sCapacityEst = -1f;             // <0: none yet (CAP_LOCK)
    private static boolean sCapacityGood;                // from >= 60% samples
    private static boolean sCapacityLoaded;
    private static int sEnergyRaw = -1;                  // last valid 0x31 raw

    private static final int MIN_ENERGY_WH = 500;  // Maps drops models with 0

    // Outside temp: unsigned raw x 0.5 - 40 C (Hyundai's own decode of the
    // DATC signal, docs/DATA-SOURCES.md 2); 0xFE/0xFF = invalid.
    private static final int COLD_HYST_C = 3;
    private static volatile int sColdTempC = Integer.MIN_VALUE;  // temp A is based on
    private static volatile float sRoadA = Float.NaN;            // last A sent

    private static final Object LOCK = new Object();
    private static Thread sThread;
    private static volatile boolean sRunning;
    private static Sink sSource;
    private static byte[] sTemplate;
    private static byte[] sScaled;          // sTemplate scaled for sScaledCapWh and sCurve
    private static float[] sCurve;          // ChargeLearn's curve, fixed for the session (null: the model's)
    private static int sScaledCapWh = -1;
    private static int sLastGoodHalfPct = -1;

    private EvModelSender() {}

    /** Where a whole raw AA message goes (message type header included). */
    interface Sink {
        int send(byte[] msg) throws Exception;
    }

    public static void start(Sink source) {
        if (HmgApiEntry.evLogOn()) {
            EvDriveLog.start();  // normally already running since GPM started; never throws
        }
        synchronized (LOCK) {
            sSource = source;
            if (sTemplate == null) {
                sTemplate = Base64.decode(EvModelTemplate.TEMPLATE_B64,
                        Base64.DEFAULT);
                if (!checkPlaceholders(sTemplate)) {
                    Log.e(TAG, "template placeholder mismatch, not starting");
                    sTemplate = null;
                    sSource = null;
                    return;
                }
            }
            if (sRunning) {
                return;
            }
            sScale = RoadLearn.scale();
            float[] curve = ChargeLearn.curveW();
            if (curve != sCurve) {
                sCurve = curve;
                sScaled = null;   // rebuild the charge tables for it
            }
            Log.i(TAG, "charge curve " + (curve == null ? "the model's" : "learned from " + ChargeLearn.sessions()
                    + " DC sessions (" + ChargeLearn.summary() + ")"));
            Log.i(TAG, "road load scale " + sScale + " (learned from "
                    + RoadLearn.samples() + " highway stretches)");
            sRunning = true;
            sThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "EvModelSender");
            sThread.setDaemon(true);
            sThread.start();
            Log.i(TAG, "started");
        }
    }

    public static void stop() {
        Thread t;
        synchronized (LOCK) {
            sRunning = false;
            t = sThread;
            sThread = null;
            sSource = null;
            sRoadA = Float.NaN;
        }
        if (t != null) {
            t.interrupt();   // wakes it from its sleep: it sees sRunning false and ends
            if (t != Thread.currentThread()) {
                try {
                    t.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Log.i(TAG, "stopped");
        }
    }

    // ---- state for EvDriveLog (any thread) --------------------------------------

    /** An Android Auto session is running (the sender is started). */
    static boolean aaActive() {
        return sRunning;
    }

    /** Road load A last sent to Maps (N), NaN outside an AA session. */
    static float roadA() {
        return sRunning ? sRoadA : Float.NaN;
    }

    /** Temperature A is based on (C), Integer.MIN_VALUE if none. */
    static int coldTempC() {
        return sColdTempC;
    }

    private static void loop() {
        while (true) {
            synchronized (LOCK) {
                // A quick reconnect starts a new worker while this one may still be
                // asleep: only the current worker sends, the old one ends here.
                if (!sRunning || sThread != Thread.currentThread()) {
                    return;
                }
                if (sSource != null) {
                    try {
                        tickLocked();
                    } catch (Throwable t) {
                        logTickFailure(t);
                    }
                }
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;   // stop()
            }
        }
    }

    /**
     * One send. Runs inside GPM: anything it throws is caught by loop(),
     * because an uncaught exception here kills GPM and with it Android Auto.
     */
    private static void tickLocked() throws Exception {
        EvCar.pollNav();
        int halfPct = displaySocHalfPct();
        int availWh = availableEnergyWh();
        learnCapacity(halfPct, availWh);
        int cap = capacityWh();
        int wh = currentEnergyWh(halfPct, availWh, cap);
        byte[] msg = (byte[]) scaledTemplateLocked(cap).clone();
        writeFixedVarint(msg, EvModelTemplate.CAPACITY_OFFSET, cap,
                EvModelTemplate.CAPACITY_WIDTH);
        writeFixedVarint(msg, EvModelTemplate.ENERGY_OFFSET, wh,
                EvModelTemplate.ENERGY_WIDTH);
        float outC = EvCar.outsideTempC();
        boolean outOk = !Float.isNaN(outC);
        if (outOk) {
            writeFloatLe(msg, EvModelTemplate.TEMP_OFFSET, outC);
        }
        pickRoadLoad();
        float roadA = coldRoadLoadA(outOk ? Math.round(outC)
                : Integer.MIN_VALUE) * sScale;
        writeFloatLe(msg, EvModelTemplate.ROAD_A_OFFSET, roadA);
        writeFloatLe(msg, EvModelTemplate.ROAD_C_OFFSET, sBaseC * sScale);
        int hvac = hvacStep(EvCar.hev(HEV_FLAG_CLIMATE_W, SHARED_MAX_AGE_MS), EvCar.now());
        msg[EvModelTemplate.HVAC_OFFSET] = (byte) hvac;
        int packC = EvCar.packMinC();
        if (packC != Integer.MIN_VALUE) {
            sPackC = packC;
        }
        writeFloatLe(msg, EvModelTemplate.BATT_TEMP_OFFSET, sPackC != Integer.MIN_VALUE
                ? (float) sPackC : EvModelTemplate.BATT_TEMP_PLACEHOLDER_C);
        int motorW = motorW(EvCar.hev(HEV_FLAG_FRONT_MOTOR_WH, SHARED_MAX_AGE_MS), cap);
        writeFixedVarint(msg, EvModelTemplate.MOTOR_OFFSET, motorW, 3);
        if (sBlock4Flat) {
            flattenBlock4(msg);
        }
        int aux = -1;
        if (sWinter) {
            aux = auxStep(auxDrainRawW(), EvCar.now());
            msg = withWinter(msg, cap, aux);
        }
        int rc = sSource.send(msg);
        if (sSensor26) {
            sendBatteryCurve(wh, cap, halfPct);
        }
        sRoadA = roadA;
        Log.i(TAG, "energy model sent: " + wh + " Wh of " + cap
                + " Wh (" + capacitySource() + "), soc=" + halfPct
                + "/200, enrg=" + sEnergyRaw + ", rc=" + rc
                + ", out=" + (outOk ? String.valueOf(outC) : "?")
                + "C, A=" + Math.round(roadA) + "N, hvac=" + hvac + ", pack="
                + (sPackC != Integer.MIN_VALUE ? sPackC + "C" : "?") + ", motor=" + motorW / 1000 + "kW"
                + (sWinter ? ", winter aux=" + aux + "W" : "") + (sBlock4Flat ? ", 4.1 flat" : "")
                + ", " + EvCar.navSummary());
    }

    // ---- VEM 1.9 peak motor power by trim ----------------------------------------

    /** HEV MCU_MG1MOTPOWER_WH: the front motor's drive energy this drive; only an AWD car moves it */
    static final int HEV_FLAG_FRONT_MOTOR_WH = 0x47;
    /** a learned usable capacity under this is a standard-range pack (58 kWh nominal; long range learns ~63) */
    static final int SR_CAPACITY_WH = 56000;
    private static volatile boolean sAwd;

    /** peak_motor_power_w: AWD once the front motor has drive energy, SR for a small pack, else RWD long range */
    static int motorW(int frontRaw, int capWh) {
        if (frontRaw > 0 && frontRaw < 0xffff) {
            sAwd = true;
        }
        if (sAwd) {
            return EvModelTemplate.MOTOR_AWD_W;
        }
        return capWh > 0 && capWh < SR_CAPACITY_WH ? EvModelTemplate.MOTOR_SR_W : EvModelTemplate.MOTOR_RWD_W;
    }

    // ---- test: block 4 flat (setting block4_flat_test) -------------------------------

    private static volatile boolean sBlock4Flat;
    static final int BLOCK4_FLAT_W = 150000;

    static void setBlock4Flat(boolean on) {
        sBlock4Flat = on;
    }

    /**
     * Every 4.1 power to BLOCK4_FLAT_W, after 8.1 was integrated from the real curve: if
     * Maps' planned charging minutes move, it reads 4.1 as a charge limit; if not, 4.1 is
     * regen (Google's v1.2: max_regen_power). The car test in docs/AA-INTEGRATION.md.
     */
    static void flattenBlock4(byte[] msg) {
        for (int off : EvModelTemplate.POWER_OFFSETS) {
            writeFixedVarint(msg, off, BLOCK4_FLAT_W, EvModelTemplate.SCALE_WIDTH);
        }
    }

    // ---- the winter add-on (setting winter_model): blocks 6, 8.2 and 10 ----------------

    private static volatile boolean sWinter;
    private static byte[] sWinterTemplate, sWinterScaled;
    private static int sWinterCapWh = -1;

    static void setWinter(boolean on) {
        sWinter = on;
    }

    /** host tests: the decoded add-on (android.util.Base64 is a stub off the car) */
    static synchronized void setWinterTemplate(byte[] b) {
        sWinterTemplate = b;
        sWinterScaled = null;
    }

    /** HEV live climate power (~20 W per unit) and live battery heater power (W) */
    static final int HEV_FLAG_BATT_HEATER_W = 0x43;
    /** a new drain must differ by this much for this long before it is sent (Maps re-routes at 500 W) */
    static final int AUX_STEP_W = 400;
    static final long AUX_HOLD_MS = 60000;
    private static int sAuxSent = -1, sAuxSeen = -1;
    private static long sAuxSeenAt;

    static int auxDrainRawW() {
        int clim = EvCar.hev(HEV_FLAG_CLIMATE_W, SHARED_MAX_AGE_MS);
        int heat = EvCar.hev(HEV_FLAG_BATT_HEATER_W, SHARED_MAX_AGE_MS);
        if (clim < 0 && heat < 0) {
            return -1;
        }
        float w = (clim > 0 ? clim * CLIMATE_W_PER_UNIT : 0f) + (heat > 0 && heat < 20000 ? heat : 0);
        return Math.round(w);
    }

    /** The drain to send (W, in 100s) for this reading (-1: unknown), with AUX_STEP_W / AUX_HOLD_MS hysteresis. */
    static synchronized int auxStep(int w, long now) {
        if (w < 0) {
            return sAuxSent < 0 ? 0 : sAuxSent;
        }
        int q = (w + 50) / 100 * 100;
        if (sAuxSent < 0) {
            sAuxSent = q;
            return q;
        }
        if (Math.abs(q - sAuxSent) < AUX_STEP_W) {
            sAuxSeen = -1;
            return sAuxSent;
        }
        if (sAuxSeen < 0 || Math.abs(q - sAuxSeen) >= AUX_STEP_W) {
            sAuxSeen = q;
            sAuxSeenAt = now;
        } else if (now - sAuxSeenAt >= AUX_HOLD_MS) {
            sAuxSent = q;
            sAuxSeen = -1;
        }
        return sAuxSent;
    }

    static synchronized void resetAux() {
        sAuxSent = -1;
        sAuxSeen = -1;
    }

    /**
     * msg (the model, 80 03 BA 01 len model) with the winter add-on appended: protobuf merges
     * its block 8 into the model's and adds blocks 6 and 10. Its 8.2 energy axes and seconds
     * are scaled to capWh like 8.1 (the learned charge curve isn't applied to them: they are
     * the cold-pack caps). The 2-byte length is rewritten.
     */
    static byte[] withWinter(byte[] msg, int capWh, int auxW) {
        byte[] add = winterScaled(capWh);
        if (add == null) {
            return msg;
        }
        add = (byte[]) add.clone();
        writeFixedVarint(add, EvModelTemplate.WINTER_AUX_OFFSET, Math.max(0, auxW), EvModelTemplate.SCALE_WIDTH);
        byte[] out = new byte[msg.length + add.length];
        System.arraycopy(msg, 0, out, 0, msg.length);
        System.arraycopy(add, 0, out, msg.length, add.length);
        int len = out.length - (EvModelTemplate.LENGTH_OFFSET + 2);
        if (len >= 1 << 14) {
            return msg;   // can't happen (the generator checks it): send the model alone
        }
        out[EvModelTemplate.LENGTH_OFFSET] = (byte) (0x80 | (len & 0x7f));
        out[EvModelTemplate.LENGTH_OFFSET + 1] = (byte) (len >>> 7);
        return out;
    }

    private static synchronized byte[] winterScaled(int capWh) {
        try {
            if (sWinterTemplate == null) {
                sWinterTemplate = Base64.decode(EvModelTemplate.WINTER_B64, Base64.DEFAULT);
            }
            if (sWinterScaled == null || capWh != sWinterCapWh) {
                sWinterScaled = scaleWinter(sWinterTemplate, capWh);
                sWinterCapWh = capWh;
            }
            return sWinterScaled;
        } catch (Throwable t) {
            logTickFailure(t);
            return null;
        }
    }

    /** the add-on with its 8.2 energy axes and seconds x k = capWh / CAPACITY_WH */
    static byte[] scaleWinter(byte[] add, int capWh) {
        byte[] out = (byte[]) add.clone();
        long ref = EvModelTemplate.CAPACITY_WH;
        int width = EvModelTemplate.SCALE_WIDTH;
        int max = (1 << (7 * width)) - 1;
        int[][] sets = {EvModelTemplate.WINTER_AXIS_OFFSETS, EvModelTemplate.WINTER_AXIS_WH,
            EvModelTemplate.WINTER_TIME_OFFSETS, EvModelTemplate.WINTER_TIME_S};
        for (int k = 0; k < 4; k += 2) {
            int[] offs = sets[k], vals = sets[k + 1];
            for (int i = 0; i < offs.length; i++) {
                long v = (vals[i] * (long) capWh + ref / 2) / ref;
                writeFixedVarint(out, offs[i], (int) Math.min(v, max), width);
            }
        }
        return out;
    }

    // ---- VEM v1.2 1.5 hvac_status and 1.8 battery_temp_c ----------------------

    /** HEV DATC_CLMTPWRCNSMPTN_W: live climate power, ~20 W per unit (EvDriveLog, RoadLearn) */
    static final int HEV_FLAG_CLIMATE_W = 0x4f;
    static final float CLIMATE_W_PER_UNIT = 20f;
    /** climate power at or above this is "on" (fans alone draw less) */
    static final float HVAC_ON_W = 150f;
    /** a new on/off state must hold this long before it is sent (Maps may re-route on a change) */
    static final long HVAC_HOLD_MS = 60000;
    /** VehicleInfo.HvacStatus */
    static final int HVAC_UNKNOWN = 0, HVAC_OFF = 1, HVAC_ON = 2;

    private static int sHvacSent = HVAC_UNKNOWN, sHvacSeen = -1;
    private static long sHvacSeenAt;
    /** last known pack temperature (cluster XEV_MIN_BATT_TEMP), C; kept when the packet goes stale */
    private static volatile int sPackC = Integer.MIN_VALUE;

    /**
     * The hvac_status to send for this climate power reading (raw < 0: unknown), with
     * HVAC_HOLD_MS of hysteresis. The first known state is sent at once.
     */
    static synchronized int hvacStep(int raw, long now) {
        int s = raw < 0 ? HVAC_UNKNOWN : raw * CLIMATE_W_PER_UNIT >= HVAC_ON_W ? HVAC_ON : HVAC_OFF;
        if (s == HVAC_UNKNOWN || s == sHvacSent) {
            sHvacSeen = -1;
            return sHvacSent;
        }
        if (sHvacSent == HVAC_UNKNOWN) {
            sHvacSent = s;
            sHvacSeen = -1;
            return s;
        }
        if (s != sHvacSeen) {
            sHvacSeen = s;
            sHvacSeenAt = now;
        } else if (now - sHvacSeenAt >= HVAC_HOLD_MS) {
            sHvacSent = s;
            sHvacSeen = -1;
        }
        return sHvacSent;
    }

    /** test hook */
    static synchronized void resetHvac() {
        sHvacSent = HVAC_UNKNOWN;
        sHvacSeen = -1;
        sPackC = Integer.MIN_VALUE;
    }

    // ---- the battery % curve (SocCurve) -------------------------------------

    /** set by HmgApiEntry: the curve is on (setting on, library accepts sensor 26) */
    private static volatile boolean sBatteryCurve;
    /** set by HmgApiEntry: sensor 26 was offered (curve or trip settings) */
    private static volatile boolean sSensor26;
    /** the EV trip settings in sensor 26 (minimum arrival % at chargers, target % at the destination); 0: Maps' own */
    private static volatile int sChargerPct, sDestPct;

    static void setBatteryCurve(boolean on) {
        sBatteryCurve = on;
    }

    static void setSensor26(boolean offered) {
        sSensor26 = offered;
    }

    /** Can change mid-session (settings apply at once); 0 leaves the user's own Maps setting. */
    static void setTripSettings(int chargerPct, int destPct) {
        sChargerPct = SocCurve.clampPct(chargerPct, SocCurve.CHARGER_PCT_MAX);
        sDestPct = SocCurve.clampPct(destPct, SocCurve.DEST_PCT_MAX);
        sBatteryCurveLogged = false;
    }

    /** Sends sensor 26 (curve and/or trip settings) as its own 0x8003 message; never throws. */
    private static void sendBatteryCurve(int wh, int cap, int halfPct) {
        try {
            boolean curve = sBatteryCurve && cap > 0 && halfPct > 0;
            int chg = sChargerPct, dst = sDestPct;
            if (!curve && chg == 0 && dst == 0) {
                return;
            }
            float raw = curve ? wh * 100f / cap : 0f;
            int rc = sSource.send(SocCurve.message(raw, halfPct / 2f, curve, chg, dst));
            if (rc != 0 || !sBatteryCurveLogged) {
                sBatteryCurveLogged = true;
                Log.i(TAG, "sensor 26 sent: " + (curve ? "battery % curve (raw " + Math.round(raw * 10) / 10f
                        + "% shows as " + halfPct / 2f + "%)" : "no curve") + ", arrival at chargers "
                        + (chg > 0 ? chg + "%" : "Maps'") + ", at the destination " + (dst > 0 ? dst + "%" : "Maps'")
                        + ", rc=" + rc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "battery % curve not sent: " + t);
        }
    }

    private static boolean sBatteryCurveLogged;

    private static int sTickFailures;

    /** Log a caught failure (first few in full, then a count every 100). */
    private static void logTickFailure(Throwable t) {
        sTickFailures++;
        if (sTickFailures <= 3) {
            Log.e(TAG, "tick failed (caught, AA unaffected)", t);
        } else if (sTickFailures % 100 == 0) {
            Log.e(TAG, "tick failed " + sTickFailures + " times, last: " + t);
        }
    }

    // ---- road load per model -------------------------------------------------

    private static volatile float sBaseA = Float.NaN;  // this model's A and C, set once
    private static volatile float sBaseC = EvModelTemplate.ROAD_C_N;
    private static volatile float sScale = 1f;  // RoadLearn's, fixed for the session

    /** This model's road load A (N) before the learned scale and the cold penalty. Any thread. */
    static float baseRoadA() {
        pickRoadLoad();
        return sBaseA;
    }

    /** This model's road load C (N/(m/s)^2) before the learned scale. Any thread. */
    static float baseRoadC() {
        pickRoadLoad();
        return sBaseC;
    }

    /** Once: the road load for this model from the product id. */
    private static synchronized void pickRoadLoad() {
        if (!Float.isNaN(sBaseA)) {
            return;
        }
        String product = EvDriveLog.prop("ro.build.product");
        int i = carIndex(product);
        sBaseC = i < 0 ? EvModelTemplate.ROAD_C_N : EvModelTemplate.CAR_ROAD_C_N[i];
        sBaseA = i < 0 ? EvModelTemplate.ROAD_A_N : EvModelTemplate.CAR_ROAD_A_N[i];
        Log.i(TAG, "road load for " + EvLogger.productShort(product) + ": A="
                + sBaseA + "N, C=" + sBaseC + (i < 0 ? " (default)" : ""));
    }

    /** Index into EvModelTemplate.CAR_* for this product id, or -1. */
    static int carIndex(String product) {
        if (product == null) {
            return -1;
        }
        for (int i = 0; i < EvModelTemplate.CAR_PREFIX.length; i++) {
            if (product.startsWith(EvModelTemplate.CAR_PREFIX[i])) {
                return i;
            }
        }
        return -1;
    }

    // ---- cold road load ------------------------------------------------------

    /**
     * Road load A for the outside temperature: this model's A plus the cold penalty
     * (EvModelTemplate.COLD_*, linear, clamped). The temperature it is based
     * on only moves once the reading is COLD_HYST_C away, so a value
     * wobbling around an edge doesn't keep changing A (Maps re-routes when A
     * moves by >= 50 N). Unknown temperature keeps the last one used, or the
     * warm base A if there is none yet.
     */
    private static float coldRoadLoadA(int outC) {
        if (outC != Integer.MIN_VALUE && (sColdTempC == Integer.MIN_VALUE
                || Math.abs(outC - sColdTempC) >= COLD_HYST_C)) {
            sColdTempC = outC;
        }
        float base = Float.isNaN(sBaseA) ? EvModelTemplate.ROAD_A_N : sBaseA;
        if (sColdTempC == Integer.MIN_VALUE) {
            return base;
        }
        return base + coldDeltaA(sColdTempC);
    }

    /** Extra road load (N) at temp C; COLD_TEMPS_C runs warm to cold. */
    private static float coldDeltaA(float t) {
        float[] ts = EvModelTemplate.COLD_TEMPS_C;
        float[] da = EvModelTemplate.COLD_DELTA_A_N;
        if (t >= ts[0]) {
            return da[0];
        }
        for (int i = 1; i < ts.length; i++) {
            if (t >= ts[i]) {
                return da[i - 1] + (da[i] - da[i - 1]) * (t - ts[i - 1])
                        / (ts[i] - ts[i - 1]);
            }
        }
        return da[da.length - 1];
    }

    // ---- energy/capacity ---------------------------------------------------

    /** DISPLAY_SOC in 0.5% steps (0..200), last good value on a failed read. */
    private static int displaySocHalfPct() {
        int halfPct = EvCar.hev(HEV_FLAG_DISPLAY_SOC, SHARED_MAX_AGE_MS);
        if (halfPct < 0 || halfPct > 200) {
            return sLastGoodHalfPct;
        }
        sLastGoodHalfPct = halfPct;
        return halfPct;
    }

    /**
     * BMS remaining usable energy in Wh (HEV 0x31, falling back to a fresh
     * nav packet), -1 if neither has a valid value.
     */
    private static int availableEnergyWh() {
        int raw = EvCar.energyRaw(SHARED_MAX_AGE_MS, ENRG_MAX_RAW);
        sEnergyRaw = raw;
        return raw > 0 ? raw * ENRG_WH_PER_UNIT : -1;
    }

    /**
     * Current energy in Wh: the BMS available energy when known (finer than
     * SOC), else SOC x capacity. Never below MIN_ENERGY_WH.
     */
    static int currentEnergyWh(int halfPct, int availWh, int capacityWh) {
        int wh;
        if (availWh > 0) {
            wh = availWh;
        } else if (halfPct >= 0) {
            wh = (int) ((long) halfPct * capacityWh / 200);
        } else {
            wh = EvModelTemplate.ENERGY_PLACEHOLDER_WH;
        }
        return Math.max(wh, MIN_ENERGY_WH);
    }

    /** Capacity (energy at 100% display) from one energy and display SOC (0.5% steps). */
    static float capacitySample(int availWh, int halfPct) {
        float zero = 2f * EvModelTemplate.ENERGY_ZERO_PCT;
        return halfPct <= zero ? -1f : (float) availWh * (200f - zero) / (halfPct - zero);
    }

    /** One capacity sample (energy at 100% display) into the estimate. */
    private static void learnCapacity(int halfPct, int availWh) {
        synchronized (CAP_LOCK) {
            loadCapacity();
            if (availWh <= 0 || halfPct < CAP_MIN_HALF_PCT) {
                return;
            }
            boolean good = halfPct >= CAP_GOOD_HALF_PCT;
            if (sCapacityGood && !good) {
                return;
            }
            float sample = capacitySample(availWh, halfPct);
            if (sample < CAP_MIN_WH || sample > CAP_MAX_WH) {
                return;
            }
            int before = Math.round(sCapacityEst);
            boolean upgrade = good && !sCapacityGood;
            sCapacityEst = sCapacityEst < 0 || upgrade ? sample
                    : sCapacityEst + CAP_ALPHA * (sample - sCapacityEst);
            sCapacityGood |= good;
            if (upgrade || Math.abs(Math.round(sCapacityEst) - before) >= 100) {
                saveCapacity();
            }
        }
    }

    /** Usable capacity in Wh, rounded to 100 Wh; nominal until learned. Any thread. */
    static int capacityWh() {
        synchronized (CAP_LOCK) {
            loadCapacity();
            if (sCapacityEst < 0) {
                return EvModelTemplate.CAPACITY_WH;
            }
            return Math.round(sCapacityEst / 100f) * 100;
        }
    }

    /** Any thread. */
    static String capacitySource() {
        synchronized (CAP_LOCK) {
            loadCapacity();
            return sCapacityEst < 0 ? "nominal" : "bms";
        }
    }

    /** Caller holds CAP_LOCK. */
    private static void loadCapacity() {
        if (sCapacityLoaded) {
            return;
        }
        sCapacityLoaded = true;
        java.io.File f = appFile(CAP_FILE);
        if (f == null || !f.isFile()) {
            return;
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f));
            try {
                String[] f2 = r.readLine().trim().split(" ");
                int v = Integer.parseInt(f2[0]);
                if (v >= CAP_MIN_WH && v <= CAP_MAX_WH) {
                    sCapacityEst = v;
                    sCapacityGood = f2.length > 1 && "1".equals(f2[1]);
                    Log.i(TAG, "learned capacity loaded: " + v + " Wh"
                            + (sCapacityGood ? "" : " (low-SOC samples)"));
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "capacity file unreadable: " + t);
        }
    }

    /** Caller holds CAP_LOCK. */
    private static void saveCapacity() {
        java.io.File f = appFile(CAP_FILE);
        if (f == null) {
            return;
        }
        try {
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            try {
                w.write(Math.round(sCapacityEst) + (sCapacityGood ? " 1" : " 0") + "\n");
            } finally {
                w.close();
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "capacity file not saved: " + t);
        }
    }

    /** A file in GPM's own files dir, or null without an app context. */
    static java.io.File appFile(String name) {
        try {
            android.content.Context ctx = EvCar.appContext();
            return ctx == null ? null : new java.io.File(ctx.getFilesDir(), name);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- template patching ---------------------------------------------------

    /** Overwrites a varint in place; never changes its width. */
    private static void writeFixedVarint(byte[] buf, int off, int value, int width) {
        for (int i = 0; i < width; i++) {
            int b = value & 0x7f;
            value >>>= 7;
            buf[off + i] = (byte) (i == width - 1 ? b : b | 0x80);
        }
    }

    private static void writeFloatLe(byte[] buf, int off, float v) {
        int bits = Float.floatToIntBits(v);
        for (int i = 0; i < 4; i++) {
            buf[off + i] = (byte) (bits >>> (8 * i));
        }
    }

    // ---- capacity scaling ------------------------------------------------------

    /**
     * The template with its charge curve (4.1) and charge-time tables (8.1)
     * scaled for capWh, cached until the capacity changes. Caller holds LOCK.
     * Falls back to the unscaled template if scaling fails, so a model is
     * still sent.
     */
    private static byte[] scaledTemplateLocked(int capWh) {
        if (sScaled == null || capWh != sScaledCapWh) {
            try {
                sScaled = scaleTemplate(sTemplate, capWh, sCurve);
                Log.i(TAG, "charge curve/tables scaled to " + capWh + " Wh");
            } catch (Throwable t) {
                logTickFailure(t);
                sScaled = sTemplate;
            }
            sScaledCapWh = capWh;
        }
        return sScaled;
    }

    /**
     * A copy of template with every 4.1/8.1 energy axis and 8.1 seconds value
     * multiplied by k = capWh / CAPACITY_WH. Charging limits follow SOC (the
     * BMS tapers by cell voltage), so a smaller usable pack reaches the same
     * taper at the same SOC: same power, k x the energy per segment, k x the
     * time. Powers are untouched. Identity at k = 1.
     */
    static byte[] scaleTemplate(byte[] template, int capWh) {
        return scaleTemplate(template, capWh, null);
    }

    /**
     * As scaleTemplate(template, capWh), with a learned charge curve (ChargeLearn: 4.1
     * powers, W, for the reference pack; null for the model's): its powers go into 4.1 and
     * the 8.1 seconds are integrated from it (ChargeCurve, as the generator does), before
     * the capacity scaling.
     */
    static byte[] scaleTemplate(byte[] template, int capWh, float[] curveW) {
        byte[] out = (byte[]) template.clone();
        long ref = EvModelTemplate.CAPACITY_WH;
        int width = EvModelTemplate.SCALE_WIDTH;
        int max = (1 << (7 * width)) - 1;
        int[] seconds = EvModelTemplate.TIME_S;
        if (curveW != null) {
            int[] po = EvModelTemplate.POWER_OFFSETS;
            for (int i = 0; i < po.length; i++) {
                writeFixedVarint(out, po[i], (int) Math.min(Math.round(curveW[i]), max), width);
            }
            seconds = ChargeCurve.tableSeconds(curveW);
        }
        if (capWh == ref && curveW == null) {
            return out;
        }
        int[] offs = EvModelTemplate.AXIS_OFFSETS;
        int[] wh = EvModelTemplate.AXIS_WH;
        for (int i = 0; i < offs.length; i++) {
            long v = (wh[i] * (long) capWh + ref / 2) / ref;
            writeFixedVarint(out, offs[i], (int) Math.min(v, max), width);
        }
        offs = EvModelTemplate.TIME_OFFSETS;
        int[] s = seconds;
        for (int i = 0; i < offs.length; i++) {
            long v = (s[i] * (long) capWh + ref / 2) / ref;
            writeFixedVarint(out, offs[i], (int) Math.min(v, max), width);
        }
        return out;
    }

    /** Fixed-width varint at off, or -1 if it isn't exactly width bytes. */
    private static int readFixedVarint(byte[] buf, int off, int width) {
        int v = 0;
        for (int i = 0; i < width; i++) {
            int b = buf[off + i] & 0xff;
            if ((b & 0x80) == 0 ? i != width - 1 : i == width - 1) {
                return -1;
            }
            v |= (b & 0x7f) << (7 * i);
        }
        return v;
    }

    /**
     * The generated offsets match the template: placeholders, road A, and
     * every scaled varint holding its reference value.
     */
    static boolean checkPlaceholders(byte[] template) {
        try {
            if (readFixedVarint(template, EvModelTemplate.ENERGY_OFFSET,
                    EvModelTemplate.ENERGY_WIDTH) != EvModelTemplate.ENERGY_PLACEHOLDER_WH
                    || readFixedVarint(template, EvModelTemplate.CAPACITY_OFFSET,
                    EvModelTemplate.CAPACITY_WIDTH) != EvModelTemplate.CAPACITY_WH) {
                return false;
            }
            byte[] aWant = new byte[4];
            writeFloatLe(aWant, 0, EvModelTemplate.ROAD_A_N);
            byte[] cWant = new byte[4];
            writeFloatLe(cWant, 0, EvModelTemplate.ROAD_C_N);
            byte[] tWant = new byte[4];
            writeFloatLe(tWant, 0, EvModelTemplate.BATT_TEMP_PLACEHOLDER_C);
            for (int i = 0; i < 4; i++) {
                if (template[EvModelTemplate.ROAD_A_OFFSET + i] != aWant[i]
                        || template[EvModelTemplate.ROAD_C_OFFSET + i] != cWant[i]
                        || template[EvModelTemplate.BATT_TEMP_OFFSET + i] != tWant[i]) {
                    return false;
                }
            }
            // hvac_status: tag (field 5, varint) then the placeholder byte 0
            if (template[EvModelTemplate.HVAC_OFFSET - 1] != (byte) (5 << 3)
                    || template[EvModelTemplate.HVAC_OFFSET] != 0) {
                return false;
            }
            if (readFixedVarint(template, EvModelTemplate.MOTOR_OFFSET, 3) != EvModelTemplate.MOTOR_RWD_W) {
                return false;
            }
            int width = EvModelTemplate.SCALE_WIDTH;
            int[] offs = EvModelTemplate.AXIS_OFFSETS;
            if (offs.length != EvModelTemplate.AXIS_WH.length) {
                return false;
            }
            for (int i = 0; i < offs.length; i++) {
                if (readFixedVarint(template, offs[i], width) != EvModelTemplate.AXIS_WH[i]) {
                    return false;
                }
            }
            offs = EvModelTemplate.POWER_OFFSETS;
            if (offs.length != EvModelTemplate.CURVE_W.length) {
                return false;
            }
            for (int i = 0; i < offs.length; i++) {
                if (readFixedVarint(template, offs[i], width) != EvModelTemplate.CURVE_W[i]) {
                    return false;
                }
            }
            offs = EvModelTemplate.TIME_OFFSETS;
            if (offs.length != EvModelTemplate.TIME_S.length) {
                return false;
            }
            for (int i = 0; i < offs.length; i++) {
                if (readFixedVarint(template, offs[i], width) != EvModelTemplate.TIME_S[i]) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
