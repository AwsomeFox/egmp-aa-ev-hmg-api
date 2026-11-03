package com.google.android.projection.protocol;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import com.hkmc.cluster.NavigationCluster;
import com.hkmc.cluster.NavigationClusterListener;

/**
 * Read-only car access shared by EvModelSender (AA sessions) and EvDriveLog
 * (always on), so the two never read the same signal twice in a row.
 *
 * HMG-API build: the same calls as the NEMESIS build's EvCar (gpm-ev/), but
 * every service is reached through HMG-API's hook map (HmgCar) instead of
 * binder transaction numbers, and HEV values by their HEVFlag name. Callers
 * still pass the flag numbers egmp found on US 260507 (EvDriveLog is shared
 * with that build); FLAG_NAMES turns each into its name on this car.
 *
 * Every call is a getter (docs/DATA-SOURCES.md); nothing here writes to the
 * car. Every method catches everything and returns "unknown" (-1 / NaN /
 * null): this runs inside GPM, where an uncaught exception kills Android
 * Auto. Any thread.
 *
 * Sources:
 *  - HEV getHEVStatus(flag), cached per flag so a second caller within
 *    maxAgeMs gets the same value without a binder call
 *  - hscan (tm_hscan_service): speed, SOH. Not the VIN: HMG-API never hands a
 *    mod per-car identity, so the log header's vin_model stays empty and the
 *    product id (ro.build.product) identifies the model instead
 *  - the nav battery packet via NavigationCluster.requestBatteryInfoReq()
 *    (pack min temp, outside temp, EV_AVLBL_ENRG, DTE), requested at most
 *    every NAV_POLL_MS whoever asks; the listener stays registered for the
 *    life of the process
 *  - DATC getVRClimateStatus(flag), a pure getter over the service's caches,
 *    and isUnitCelsius(unit)
 *  - BCM drive mode: a passive IBCMListener. The service pushes the cached
 *    mode on registration and every change. We never call getDriveMode():
 *    that sends a MICOM request and the reply can show the drive-mode pop-up.
 *  - GPS altitude: LocationManager.getLastKnownLocation("gps"); only the
 *    altitude leaves this class, never position or time.
 */
final class EvCar {
    private static final String TAG = "EvCar";

    static final int HEV_INVALID16 = 0xffff;

    // DATCFlag values getVRClimateStatus answers (DATCService.smali)
    static final int DATC_TEMP = 0x2;         // driver set temp, display unit
    static final int DATC_BLOWER_LEVEL = 0x5; // MainBlowerDisp
    static final int DATC_BLOWER_MODE = 0x6;  // ModeDisp (vent/floor/def...)
    static final int DATC_AUTO = 0x8;         // AutoDisp
    static final int DATC_AC = 0x9;           // AcDisp
    static final int DATC_OP_STATE = 0xc;     // OpSts
    static final int DATC_TEMP_UNIT = 0xd;    // TempUnit raw

    /**
     * The HEV flags egmp reads, by their US 260507 number (what EvDriveLog
     * and EvModelSender pass) and HEVFlag name (docs/DATA-SOURCES.md). A flag
     * without a known name is read by its number.
     */
    private static final Object[][] FLAG_NAMES = {
        {0x07, "CHARGE_STATE"},
        {0x0f, null},  // quick-charge remaining minutes: name not confirmed
        {0x19, "DISPLAY_SOC"},
        {0x1d, "CHG_STS"},
        {0x1f, "ODOMETER"},
        {0x22, "TARGET_SOC_SLOW"},
        {0x23, "TARGET_SOC_QUICK"},
        {0x28, "FAST_CHARGING"},
        {0x31, "ENERGYMAP_1000MS"},
        {0x42, "DATC_CLMTPWRSUM_W"},
        {0x43, "XEV_BATHEATPWRMONCON_W"},
        {0x44, "XEV_BATHEATPWRSUM_WH"},
        {0x47, "MCU_MG1MOTPOWER_WH"},
        {0x48, "MCU_MG2MOTPOWER_WH"},
        {0x49, "LDC_LOADPWRSUMVAL"},
        {0x4f, "DATC_CLMTPWRCNSMPTN_W"},
        {0x52, "UNIFICATION_CHARGING_CURRENT"},
        {0x53, "OBC_ACPWRDIS"},
        {0x58, "XEV_BATPWRSUM_KWH"},
        {0x59, "MCU_MG1GENPOWER_WH"},
        {0x5a, "MCU_MG2GENPOWER_WH"},
        {0x6c, "VCMS_PWRDIS"},
    };

    // Navigation.BATTERY_INFO_RX packet indices (see
    // com.hkmc.micom.protocol.Navigation$BATTERY_INFO_RX).
    private static final int IDX_MIN_BATT_TEMP = 1;   // XEV_MIN_BATT_TEMP, C
    private static final int IDX_EV_AVLBL_ENRG = 2;   // EV_AVLBL_ENRG, 2 Wh steps
    private static final int IDX_OUT_TEMP = 4;        // DATC_OUT_TEMP_SNSR raw
    private static final int IDX_DTE_BASE = 6;        // VCU_DTE_BASE, km
    private static final int IDX_DTE_ADD = 7;         // VCU_DTE_ADD, km
    private static final long NAV_FRESH_MS = 30000;
    private static final long NAV_POLL_MS = 4000;
    private static final long GPS_FRESH_NS = 10000L * 1000000L;

    private static final Object CACHE_LOCK = new Object();
    private static final int[] sHevVal = new int[256];
    private static final long[] sHevAt = new long[256];  // 0 = never read
    private static final int[] sFlag = new int[256];     // resolved flag, 0 = not yet

    private static volatile int sSohRaw = Integer.MIN_VALUE;  // until > 0

    private EvCar() {}

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    // ---- HEV -------------------------------------------------------------------

    /**
     * getHEVStatus(flag) for egmp's flag number (resolved to this car's value
     * by name), reusing a value read less than maxAgeMs ago (by any caller).
     * -1 on failure (failures are not cached).
     */
    static int hev(int flag, long maxAgeMs) {
        if (flag < 0 || flag >= sHevVal.length) {
            return -1;
        }
        if (maxAgeMs > 0) {
            synchronized (CACHE_LOCK) {
                long at = sHevAt[flag];
                if (at != 0 && now() - at < maxAgeMs) {
                    return sHevVal[flag];
                }
            }
        }
        int v;
        try {
            v = HmgCar.hevStatus(resolve(flag));
        } catch (Throwable t) {
            return -1;
        }
        if (v >= 0) {
            synchronized (CACHE_LOCK) {
                sHevVal[flag] = v;
                sHevAt[flag] = now();
            }
        }
        return v;
    }

    /** This car's value for egmp's flag number: by HEVFlag name where known. */
    static int resolve(int flag) {
        synchronized (CACHE_LOCK) {
            if (sFlag[flag] != 0) {
                return sFlag[flag];
            }
        }
        int r = flag;
        for (Object[] e : FLAG_NAMES) {
            if (((Integer) e[0]).intValue() == flag) {
                if (e[1] != null) {
                    r = HmgCar.flag((String) e[1], flag);
                }
                break;
            }
        }
        synchronized (CACHE_LOCK) {
            sFlag[flag] = r;
        }
        return r;
    }

    // ---- hscan -----------------------------------------------------------------

    /** Display speed in km/h (hscan getCarSpeed unit 0), NaN if unknown. */
    static float speedKph() {
        try {
            float v = HmgCar.speedKph();
            return Float.isNaN(v) || v < 0 || v > 400 ? Float.NaN : v;
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** BMS SOH raw; re-read until it reports a value (it reads 0 at boot). */
    static int sohRaw() {
        int s = sSohRaw;
        if (s <= 0) {
            int v;
            try {
                v = HmgCar.sohRaw();
            } catch (Throwable t) {
                v = -1;
            }
            if (v != s) {
                Log.i(TAG, "BMS SOH raw: " + v);
            }
            sSohRaw = v;
            s = v;
        }
        return s;
    }

    /**
     * Always "": HMG-API doesn't hand mods the VIN (per-car identity), and
     * the log header's product id already says which model this is.
     */
    static String vinModel() {
        return "";
    }

    // ---- nav battery packet ----------------------------------------------------

    private static final Object NAV_LOCK = new Object();
    private static NavigationCluster sNavCluster;
    private static long sNavPolledAt;
    private static volatile long sNavPacketAt;
    private static volatile int sPackTempC = Integer.MIN_VALUE;
    private static volatile int sOutTempRaw = Integer.MIN_VALUE;
    private static volatile int sAvlblEnrg = -1;
    private static volatile int sDteBase = -1;
    private static volatile int sDteAdd = -1;

    private static final NavigationClusterListener sNavListener =
            new NavigationClusterListener() {
                @Override
                public void onHandleBatteryInfoResp(int[] d) {
                    try {
                        if (d == null || d.length <= IDX_DTE_ADD) {
                            return;
                        }
                        sPackTempC = (byte) d[IDX_MIN_BATT_TEMP];
                        sOutTempRaw = d[IDX_OUT_TEMP] & 0xff;
                        sAvlblEnrg = d[IDX_EV_AVLBL_ENRG];
                        sDteBase = d[IDX_DTE_BASE];
                        sDteAdd = d[IDX_DTE_ADD];
                        sNavPacketAt = now();
                    } catch (Throwable t) {
                        // never disturb GPM
                    }
                }
            };

    /** Ask for a fresh nav battery packet unless one was asked for recently. */
    static void pollNav() {
        try {
            synchronized (NAV_LOCK) {
                long n = now();
                if (sNavPolledAt != 0 && n - sNavPolledAt < NAV_POLL_MS) {
                    return;
                }
                sNavPolledAt = n;
                if (sNavCluster == null) {
                    Context ctx = appContext();
                    if (ctx == null) {
                        return;
                    }
                    NavigationCluster nc = new NavigationCluster(ctx);
                    nc.addListener(sNavListener);
                    sNavCluster = nc;
                    Log.i(TAG, "nav cluster listener registered");
                }
                try {
                    sNavCluster.requestBatteryInfoReq();
                } catch (Throwable t) {
                    // keep the listener; retry next poll
                    Log.w(TAG, "nav battery request failed: " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "nav cluster unavailable: " + t);
        }
    }

    static boolean navFresh() {
        long at = sNavPacketAt;
        return at != 0 && now() - at < NAV_FRESH_MS;
    }

    /** XEV_MIN_BATT_TEMP from a fresh packet, Integer.MIN_VALUE if unknown. */
    static int packMinC() {
        return navFresh() ? sPackTempC : Integer.MIN_VALUE;
    }

    /** DATC_OUT_TEMP_SNSR raw from a fresh packet, -1 if unknown. */
    static int outTempRaw() {
        int raw = sOutTempRaw;
        return navFresh() && raw >= 0 ? raw : -1;
    }

    /** Outside temp in C (raw x 0.5 - 40), NaN if unknown/invalid. */
    static float outsideTempC() {
        int raw = outTempRaw();
        if (raw < 0 || raw >= 0xfe) {
            return Float.NaN;
        }
        return raw * 0.5f - 40f;
    }

    /** EV_AVLBL_ENRG from a fresh packet, -1 if unknown. */
    static int navAvlblEnrg() {
        return navFresh() ? sAvlblEnrg : -1;
    }

    /** VCU_DTE_BASE + VCU_DTE_ADD (km) from a fresh packet, -1 if unknown. */
    static int dteRaw() {
        int b = sDteBase;
        return navFresh() && b >= 0 ? b + sDteAdd : -1;
    }

    static String navSummary() {
        return "pack=" + (navFresh() ? String.valueOf(sPackTempC) : "?")
                + "C, out raw " + sOutTempRaw + ", dte=" + sDteBase + "+" + sDteAdd + "km";
    }

    // ---- energy ----------------------------------------------------------------

    /**
     * BMS remaining usable energy raw (HEV ENERGYMAP_1000MS, 2 Wh steps;
     * falls back to a fresh nav packet's EV_AVLBL_ENRG, the same signal).
     * -1 if neither is valid.
     */
    static int energyRaw(long maxAgeMs, int maxRaw) {
        int raw = hev(0x31, maxAgeMs);
        if (raw <= 0 || raw >= HEV_INVALID16) {
            raw = navAvlblEnrg();
        }
        if (raw <= 0 || raw >= HEV_INVALID16 || raw > maxRaw) {
            return -1;
        }
        return raw;
    }

    // ---- DATC (climate) --------------------------------------------------------

    /**
     * Climate getter results are cached for CLIMATE_CACHE_MS: each call makes
     * the DATC service write a debug line to logcat, and at ~7 calls per row
     * that would flush the head unit's ~1 min log buffer. Settings change
     * rarely, so 30 s resolution is plenty.
     */
    private static final long CLIMATE_CACHE_MS = 30000;
    private static final java.util.HashMap<Integer, Float> sClimate =
            new java.util.HashMap<Integer, Float>();
    private static final java.util.HashMap<Integer, Long> sClimateAt =
            new java.util.HashMap<Integer, Long>();

    /** IDATCService.getVRClimateStatus(flag); NaN on failure or invalid (< 0). */
    static synchronized float climate(int flag) {
        long now = SystemClock.uptimeMillis();
        Integer k = Integer.valueOf(flag);
        Long at = sClimateAt.get(k);
        if (at != null && now - at.longValue() < CLIMATE_CACHE_MS) {
            return sClimate.get(k).floatValue();
        }
        float v;
        try {
            v = HmgCar.vrClimateStatus(flag);
            if (v < 0 || Float.isNaN(v) || Float.isInfinite(v)) {
                v = Float.NaN;
            }
        } catch (Throwable t) {
            v = Float.NaN;
        }
        sClimate.put(k, Float.valueOf(v));
        sClimateAt.put(k, Long.valueOf(now));
        return v;
    }

    /** IDATCService.isUnitCelsius(unit): 1 yes, 0 no, -1 unknown. */
    static int climateUnitIsCelsius(int unitRaw) {
        try {
            return HmgCar.isUnitCelsius(unitRaw) ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- BCM drive mode (passive listener) --------------------------------------

    private static volatile int sDriveMode = -1;
    private static volatile IBinder sBcmRegisteredWith;
    private static Binder sBcmListener;
    private static long sBcmTriedAt;

    /**
     * Last drive mode the BCM service pushed (BCMConstants.DriveMode: 0
     * default, 1 normal, 2 sport, 3 eco, 4 snow), -1 unknown. Registers the
     * listener on first use and again if the BCM service restarted (tried at
     * most once a minute).
     */
    static int driveMode() {
        try {
            IBinder reg = sBcmRegisteredWith;
            if (reg == null || !reg.isBinderAlive()) {
                long n = now();
                if (sBcmTriedAt == 0 || n - sBcmTriedAt >= 60000) {
                    sBcmTriedAt = n;
                    registerBcm();
                }
            }
        } catch (Throwable t) {
            // unknown
        }
        return sDriveMode;
    }

    private static void registerBcm() {
        try {
            sDriveMode = -1;
            HmgCar.reset("bcm");
            if (sBcmListener == null) {
                sBcmListener = new BcmListener(HmgCar.bcmListenerDescriptor(),
                        HmgCar.bcmDriveModeTransaction());
            }
            sBcmRegisteredWith = HmgCar.registerBcmListener(sBcmListener);
            Log.i(TAG, "BCM listener registered (drive mode " + sDriveMode + ")");
        } catch (Throwable t) {
            Log.w(TAG, "BCM listener not registered: " + t);
        }
    }

    /**
     * IBCMListener on the wire: only onDriveModeChanged(int) is read; every
     * other callback (all void) is acknowledged and ignored. Runs on binder
     * threads and only stores an int.
     */
    private static final class BcmListener extends Binder {
        private final String descriptor;
        private final int driveModeCode;

        BcmListener(String descriptor, int driveModeCode) {
            this.descriptor = descriptor;
            this.driveModeCode = driveModeCode;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION) {
                    if (code == driveModeCode) {
                        data.enforceInterface(descriptor);
                        int m = data.readInt();
                        sDriveMode = m >= 0 && m <= 0xff ? m : -1;
                    }
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable t) {
                return false;
            }
        }
    }

    // ---- GPS altitude ----------------------------------------------------------

    /**
     * Altitude (m) of the last GPS fix if it is under 10 s old and has one,
     * NaN otherwise. Only the altitude is used; the position and time of the
     * fix never leave this method.
     */
    static double gpsAltitudeM() {
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return Double.NaN;
            }
            LocationManager lm = (LocationManager) ctx.getSystemService(
                    Context.LOCATION_SERVICE);
            if (lm == null) {
                return Double.NaN;
            }
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null || !l.hasAltitude()) {
                return Double.NaN;
            }
            long age = SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos();
            if (age < 0 || age > GPS_FRESH_NS) {
                return Double.NaN;
            }
            return l.getAltitude();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    // ---- helpers ---------------------------------------------------------------

    static Context appContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
