package com.google.android.projection.protocol;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Automatic battery preconditioning while Google Maps (Android Auto) guides
 * to a DC fast charger. See docs/PRECONDITIONING.md.
 *
 * Entry points (HMG-API build: called from HmgApiEntry's hooks, each wrapped
 * in a catch-all there too; none of them ever throws):
 *   onSessionStart(Context)  gpm.sensor_source.init (an AA connection): start
 *                            the thread, cancel a stale ownership journal
 *   onNavDump(String)        gpm.navigation_status.next_turn event 33: the
 *                            "S ..." / "P ..." route payloads, which the
 *                            Wireless CarPlay + Android Auto mod's library
 *                            patch hands over (without it there are none)
 *   onNavStatus(int)         gpm.navigation_status.status: AA NavigationState
 *   stop()                   gpm.receiver.destroy and
 *                            gpm.receiver_service.disconnected (AA session end)
 *
 * The mod's "preconditioning" setting turns it off; so does the car's own
 * "battery conditioning" EV setting (HEV EV_BATTERYCONDITIONING). Requests preconditioning for the next
 * stop when it is a charging stop above the minimum power (the mod's
 * settings, PrecondCore.Config; 50 kW by default).
 *
 * Everything runs on one HandlerThread; PrecondCore holds the logic.
 */
public final class Precond {
    static final String TAG = "EvPrecond";
    private static final String JOURNAL_NAME = "evprecond.owned";

    private static final Object LOCK = new Object();
    private static HandlerThread sThread;
    private static Handler sHandler;
    private static volatile PrecondCore sCore;
    private static boolean sTicking;
    private static boolean sLoggedParseError;
    /** the thresholds from the mod's settings (configure), for a core made later */
    private static volatile PrecondCore.Config sConfig = PrecondCore.DEFAULT_CONFIG;

    private Precond() {}

    // ---- read-only getters for other components (e.g. a logger) ------------

    /** PrecondCore.ST_* (ST_OFF before the first AA session). */
    public static int state() {
        PrecondCore c = sCore;
        return c == null ? PrecondCore.ST_OFF : c.state();
    }

    public static String stateName() {
        return PrecondCore.stateName(state());
    }

    public static boolean navActive() {
        PrecondCore c = sCore;
        return c != null && c.navActive();
    }

    /** Metres to the selected charger, else to the next stop; -1 unknown. */
    public static int remainingMeters() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.remainingMeters();
    }

    /** Seconds to the selected charger, else to the next stop; -1 unknown. */
    public static int remainingSeconds() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.remainingSeconds();
    }

    public static boolean isCharger() {
        PrecondCore c = sCore;
        return c != null && c.isCharger();
    }

    /** Charger power of that stop in kW (from Maps' W); -1 unknown. */
    public static int chargerKw() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.chargerKw();
    }

    /** Maps' planned departure energy at that charging stop, Wh; -1 unknown. */
    public static int chargerDepartureWh() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.chargerDepartureWh();
    }

    /** Maps' planned charging time at that charging stop, s; -1 unknown. */
    public static int chargerChargeSeconds() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.chargerChargeSeconds();
    }

    // ---- entry points ----------------------------------------------------------

    /**
     * The owner's thresholds (HmgApiEntry, from the mod's settings, at start and on
     * every change). Applied on Precond's thread; the charging stop is picked again.
     */
    public static void configure(final PrecondCore.Config c) {
        try {
            sConfig = c;
            Handler h;
            synchronized (LOCK) {
                h = sHandler;
            }
            if (h == null) {
                return;   // ensure() applies it when the core is made
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        sCore.setConfig(c, now());
                        scheduleTick();
                    } catch (Throwable t) {
                        Log.w(TAG, "settings failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    public static void onSessionStart(Context ctx) {
        try {
            ensure(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "init failed: " + t);
        }
    }

    public static void onNavDump(final String line) {
        try {
            if (line == null || line.length() < 2
                    || (line.charAt(0) != 'S' && line.charAt(0) != 'P')) {
                return;
            }
            Handler h = ensure(null);
            if (h == null) {
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        handleDump(line);
                    } catch (Throwable t) {
                        Log.w(TAG, "dump failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    public static void onNavStatus(final int status) {
        try {
            Handler h = ensure(null);
            if (h == null) {
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        sCore.onNavStatus(status, now());
                        scheduleTick();
                    } catch (Throwable t) {
                        Log.w(TAG, "status failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    /** AA session end: cancel if we own a request. Waits up to 1.5 s. */
    public static void stop() {
        try {
            Handler h;
            synchronized (LOCK) {
                h = sHandler;
            }
            if (h == null) {
                return;
            }
            final CountDownLatch done = new CountDownLatch(1);
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        sCore.stop(now());
                        scheduleTick();  // keeps going only while cancelling
                    } catch (Throwable t) {
                        Log.w(TAG, "stop failed: " + t);
                    } finally {
                        done.countDown();
                    }
                }
            });
            if (Thread.currentThread() != h.getLooper().getThread()) {
                done.await(1500, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    // ---- internals (handler thread unless noted) -------------------------------

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    /** Any thread. Creates the thread and core once; returns the handler. */
    private static Handler ensure(Context ctx) {
        synchronized (LOCK) {
            if (sHandler != null) {
                return sHandler;
            }
            if (ctx == null) {
                ctx = appContext();
            }
            File dir = ctx == null ? null : ctx.getFilesDir();
            HandlerThread t = new HandlerThread(TAG);
            t.start();
            Handler h = new Handler(t.getLooper());
            sCore = new PrecondCore(new HmgVehicle(), new FileJournal(dir),
                    new PrecondCore.Logger() {
                        @Override
                        public void log(String msg) {
                            Log.i(TAG, msg);
                        }
                    });
            sThread = t;
            sHandler = h;
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        long n = now();
                        sCore.setConfig(sConfig, n);
                        sCore.recover(n);
                        scheduleTick();
                    } catch (Throwable t) {
                        Log.w(TAG, "recover failed: " + t);
                    }
                }
            });
            Log.i(TAG, "started");
            return h;
        }
    }

    private static Context appContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void handleDump(String line) {
        long n = now();
        NavProto.Dump d;
        try {
            d = NavProto.parse(line);
        } catch (NavProto.ParseException e) {
            if (!sLoggedParseError) {
                sLoggedParseError = true;
                Log.w(TAG, "NAVDUMP " + line.charAt(0) + " not parsed: "
                        + e.getMessage() + " (logged once)");
            }
            return;
        }
        if (d == null) {
            return;
        }
        if (d.kind == 'S') {
            sCore.onDestinations(d.destinations, n);
        } else {
            sCore.onRemaining(d.remaining, n);
        }
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            sTicking = false;
            try {
                long n = now();
                sCore.tick(n);
                scheduleTick();
            } catch (Throwable t) {
                Log.w(TAG, "tick failed: " + t);
            }
        }
    };

    /** Handler thread. Ticks every 5 s while guiding, owning or cancelling. */
    private static void scheduleTick() {
        if (sTicking || !sCore.busy()) {
            return;
        }
        sTicking = true;
        sHandler.postDelayed(TICK, PrecondCore.REFRESH_MS);
    }


    // ---- ownership journal -----------------------------------------------------

    /** A small file in GPM's files dir: present = we may own a request. */
    static final class FileJournal implements PrecondCore.Journal {
        private final File file;

        FileJournal(File dir) {
            file = dir == null ? null : new File(dir, JOURNAL_NAME);
        }

        @Override
        public boolean pending() throws Exception {
            if (file == null) {
                throw new IllegalStateException("no files dir");
            }
            return file.exists();
        }

        @Override
        public void set(boolean owned) throws Exception {
            if (file == null) {
                throw new IllegalStateException("no files dir");
            }
            if (owned) {
                FileOutputStream out = new FileOutputStream(file);
                try {
                    out.write('1');
                    out.flush();
                    out.getFD().sync();
                } finally {
                    out.close();
                }
            } else if (file.exists() && !file.delete()) {
                throw new IllegalStateException("delete failed");
            }
        }
    }

    // ---- car access (HMG-API) ---------------------------------------------------

    /**
     * Reads through HMG-API's hook map (HmgCar: the car's HEV and BCM
     * services by method and flag name, mapped per car and firmware), so no
     * binder transaction number or firmware fingerprint is needed here.
     *
     * Writes go through HMG-API's reviewed "conditioning" command, which only
     * a mod's own service may send: Relay hands them to this mod's "relay"
     * service. PrecondCore's command profile maps onto it as:
     *   Start    (65 km, 66 min, 62 = 2,3) -> conditioning auto-start <km> <min>
     *   Maintain (65 km, 66 min every 5 s) -> conditioning route <km> <min>
     *   Cancel   (65 2047, 66 1023, 62 = 0,0) -> conditioning stop
     * An HMG-API without route values ignores them (it keeps its fixed
     * 9 km / 18 min); see docs/HMG-API-COMPARISON.md.
     */
    static final class HmgVehicle implements PrecondCore.Vehicle {
        private int km = -1;
        private int min = -1;
        private boolean started;

        @Override
        public boolean compatible() {
            // the hook map has the reads, and the relay service answers
            return HmgCar.ready() && Relay.ping();
        }

        @Override
        public int readEnabled() throws Exception {
            return HmgCar.hevStatus(HmgCar.flagBatteryConditioning());
        }

        @Override
        public int readReady() throws Exception {
            return HmgCar.hevStatus(HmgCar.flagReady());
        }

        @Override
        public int readIndicator() throws Exception {
            return HmgCar.batteryConditioningIndicator();
        }

        @Override
        public void write(int transaction, int[] values) throws Exception {
            if (transaction == PrecondCore.TX_ROUTE_KM && values.length == 1) {
                km = values[0];
                return;
            }
            if (transaction == PrecondCore.TX_REMAIN_MIN && values.length == 1) {
                min = values[0];
                // Maintain: the pair is complete once the minutes come
                if (started && km >= 0 && km <= PrecondCore.KM_MAX
                        && min >= 0 && min <= PrecondCore.MIN_MAX) {
                    Relay.call("route " + km + " " + min);
                }
                return;
            }
            if (transaction == PrecondCore.TX_STATION && values.length == 2) {
                if (values[0] == 2 && values[1] == 3) {
                    if (km < 0 || km > PrecondCore.KM_MAX || min < 0 || min > PrecondCore.MIN_MAX) {
                        throw new IllegalStateException("start without route values");
                    }
                    Relay.call("start " + km + " " + min);
                    started = true;
                    return;
                }
                if (values[0] == 0 && values[1] == 0) {
                    started = false;
                    Relay.call("stop");
                    return;
                }
            }
            throw new IllegalArgumentException("command outside the preconditioning profile");
        }

        @Override
        public void pause(long ms) {
            SystemClock.sleep(ms);
        }
    }
}
