package com.google.android.projection.protocol;

import java.lang.reflect.Method;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import hmgapi.hook.Points;

/**
 * egmp-aa-ev as an HMG-API mod: what the NEMESIS build did by editing GPM's
 * smali (tools/patch_gpm_smali.py), done with HMG-API's hook points, its
 * Android Auto settings and an in-memory library patch. GPM's files stay stock.
 *
 *   NEMESIS build (smali edit)                    HMG-API mod
 *   init(): fuel {10}, connectors {4, 1}          mod.json projection aa_fuel_types,
 *                                                 aa_ev_connectors (HMG-API's own)
 *   init(): nativeRegisterSensor(23)              gpm.sensor_source.init (change):
 *                                                 23 added to the offered sensors,
 *                                                 only with the library patch in place
 *   init(): EvModelSender.start(this)             gpm.sensor_source.init (after)
 *   shutdown(): EvModelSender.stop()              gpm.receiver.destroy,
 *                                                 gpm.receiver_service.disconnected
 *   sendRawMessage -> nativeReportDiagnosticsData the same native method, by name
 *   GpmNaviTBTManager.<init> -> Precond           gpm.sensor_source.init
 *   onNextTurn NAVDUMP -> Precond.onNavDump       gpm.navigation_status.next_turn,
 *                                                 event 33 (the Wireless mod's
 *                                                 library patch makes these)
 *   onStatus -> Precond.onNavStatus               gpm.navigation_status.status
 *   destroy -> Precond.stop                       the two session-end points
 *   StartReceiver/GalReceiverService -> EvDriveLog GPM's process starting (here)
 *   library file delta (patch_library.py)         hooks.native recipe
 *   persist.gpm.ev.off                            the mod's switch and settings
 *   (new) battery level, range, odometer          gpm.sensor_source.init: sensors 6 and 5
 *                                                 offered, LiveData sends them
 *
 * Settings (settings.json): energy_model, preconditioning, ev_log, live_data, and the
 * preconditioning thresholds precond_min_kw, precond_window_min,
 * precond_arrival_m, precond_max_starts (PrecondCore.Config). The thresholds
 * apply at once (HMG-API hands every running app a saved change); turning the
 * model or preconditioning off stops them at once; the rest applies when GPM
 * restarts.
 */
public final class HmgApiEntry extends ModEntry {
    static final String GPM = "com.google.android.projection.sink";
    /** the Wireless mod's library hands Maps' route messages over as this event */
    static final int EV_NAV_DUMP = 33;

    static volatile ModContext sMod;

    static boolean on(String key) {
        ModContext m = sMod;
        return m == null || (m.enabled() && m.settingBoolean(key, true));
    }

    static boolean evLogOn() {
        return on("ev_log");
    }

    /** this Android Auto session's EvCar.notAnEvWeFit() == null (set when GPM's sensors start) */
    static volatile boolean sEvFits;
    private static String sNotEvLogged;

    /**
     * Whether this car is an EV the energy model fits (EvCar.notAnEvWeFit), logged when
     * the answer changes. Off: no sensor 23 and no live battery %, so Maps gets nothing
     * that could be wrong for the car; preconditioning and the logs aren't affected.
     */
    static boolean evFits(ModContext mod) {
        String why;
        try {
            why = EvCar.notAnEvWeFit();
        } catch (Throwable t) {
            why = "check failed: " + t;
        }
        String key = why == null ? "" : why;
        if (!key.equals(sNotEvLogged)) {
            sNotEvLogged = key;
            if (why == null) {
                mod.log("EV check: battery state of charge and pack size fit the energy model");
            } else {
                mod.error("energy model and live battery data off on this car: " + why, null);
            }
        }
        return why == null;
    }

    static boolean modelOn() {
        return on("energy_model");
    }

    static boolean precondOn() {
        return on("preconditioning");
    }

    static boolean liveDataOn() {
        return on("live_data");
    }

    /** The mod's library recipes, while its folder is readable (the zygote runs as root). */
    @Override
    public void zygoteInit(ModContext mod) {
        NativeCheck.load(new java.io.File(mod.dir, "recipes"));
    }

    @Override
    public void appLoaded(final ModContext mod, final LoadedApp app) {
        if (!GPM.equals(app.packageName) || !GPM.equals(app.processName)) {
            return;
        }
        sMod = mod;
        Precond.configure(precondConfig(mod));
        if (!Points.complete("gpm")) {
            mod.error("GPM isn't fully mapped on this car: EV routing stays off", null);
            return;
        }
        if (mod.settingBoolean("energy_model", true)) {
            mod.nativePatch(app, "libautoreceiver_infobank.so");
        }
        if (evLogOn()) {
            EvDriveLog.start();
        }
        final ClassLoader cl = app.classLoader;

        mod.hook(cl, "gpm.sensor_source.init", new MethodHook() {
            /**
             * Sensor 23 is offered here rather than through mod.json's projection
             * settings: only when the library patch that lets the library accept
             * and answer it is really in place on this car's library build.
             */
            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    sEvFits = evFits(mod);
                    if (sEvFits && modelOn() && p.args[2] instanceof int[] && NativeCheck.patched()) {
                        p.args[2] = withSensor((int[]) p.args[2], SENSOR_EV_MODEL);
                    }
                    if (sEvFits && liveDataOn() && p.args[2] instanceof int[]) {
                        // types the stock library takes (1..21): no patch needed
                        p.args[2] = withSensor(withSensor((int[]) p.args[2], LiveData.SENSOR_FUEL), LiveData.SENSOR_ODOMETER);
                    }
                } catch (Throwable t) {
                    mod.error("sensor 23", t);
                }
            }

            @Override
            protected void afterHookedMethod(Param p) {
                try {
                    if (!Boolean.TRUE.equals(p.getResult())) {
                        return;   // GPM's own init failed: no session to add to
                    }
                    if (precondOn()) {
                        Precond.onSessionStart(app.context);
                    }
                    if (sEvFits && modelOn()) {
                        startSender(mod, p.thisObject);
                    }
                    if (sEvFits && liveDataOn()) {
                        startLiveData(p.thisObject);
                    }
                } catch (Throwable t) {
                    mod.error("session start", t);
                }
            }
        });

        // A clean end calls destroy; a phone that's just gone (Wi-Fi off, ping
        // timeout) only onDisconnected. Both stop the sender before GPM frees
        // the connection's native objects.
        MethodHook ended = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    EvModelSender.stop();
                    LiveData.stop();
                    Precond.stop();
                } catch (Throwable t) {
                    mod.error("session end", t);
                }
            }
        };
        mod.hook(cl, "gpm.receiver.destroy", ended);
        mod.hook(cl, "gpm.receiver_service.disconnected", ended);

        mod.hook(cl, "gpm.navigation_status.next_turn", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                // (road, side, event, image, angle, number)
                if (p.args[2] instanceof Integer && ((Integer) p.args[2]).intValue() == EV_NAV_DUMP
                        && p.args[0] instanceof String && precondOn()) {
                    Precond.onNavDump((String) p.args[0]);
                }
            }
        });
        mod.hook(cl, "gpm.navigation_status.status", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (p.args[0] instanceof Integer && precondOn()) {
                    Precond.onNavStatus(((Integer) p.args[0]).intValue());
                }
            }
        });
        mod.log("EV routing hooks in " + app.processName + " (energy model "
                + (mod.settingBoolean("energy_model", true) ? "on" : "off") + ", preconditioning "
                + (mod.settingBoolean("preconditioning", true) ? "on" : "off") + ", EV log "
                + (mod.settingBoolean("ev_log", true) ? "on" : "off") + ", live data "
                + (mod.settingBoolean("live_data", true) ? "on" : "off") + ")");
    }

    /**
     * The energy model goes out through GPM's SensorSource.nativeReportDiagnosticsData,
     * which the library recipe turns into a raw send. Only once the recipe's
     * bytes are really in place: unpatched, that call would send our bytes as a
     * diagnostics report.
     */
    static void startSender(ModContext mod, final Object sensorSource) throws Exception {
        if (!NativeCheck.patched()) {
            mod.error("energy model off: the Android Auto library patch isn't in place ("
                    + NativeCheck.why() + ")", null);
            return;
        }
        final Method send = reportDiagnostics(sensorSource);
        EvModelSender.start(new EvModelSender.Sink() {
            @Override
            public int send(byte[] msg) throws Exception {
                Object r = send.invoke(sensorSource, (Object) msg);
                return r instanceof Integer ? ((Integer) r).intValue() : 0;
            }
        });
    }

    /**
     * The battery level, range and odometer to the phone through GPM's own senders: the
     * fuel report by its native call (so an unknown range is left out rather than sent
     * as 0), the odometer by its Java wrapper (km).
     */
    static void startLiveData(final Object sensorSource) throws Exception {
        final Method fuel = declared(sensorSource, "nativeReportFuelData",
                boolean.class, int.class, boolean.class, int.class, boolean.class, boolean.class);
        final Method odo = declared(sensorSource, "reportOdometerData", double.class);
        LiveData.start(new LiveData.Out() {
            @Override
            public void fuel(int percent, int rangeMeters, boolean low) throws Exception {
                fuel.invoke(sensorSource, true, percent, rangeMeters >= 0, Math.max(rangeMeters, 0), true, low);
            }

            @Override
            public void odometer(double km) throws Exception {
                odo.invoke(sensorSource, km);
            }
        });
    }

    /** a method of the sensor source's class (or a superclass), by name */
    static Method declared(Object o, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // superclass
            }
        }
        throw new NoSuchMethodException("SensorSource." + name);
    }

    /** SENSOR_VEHICLE_ENERGY_MODEL_DATA */
    static final int SENSOR_EV_MODEL = 23;

    /** the offered sensor list with one more type (once) */
    static int[] withSensor(int[] in, int type) {
        for (int s : in) {
            if (s == type) {
                return in;
            }
        }
        int[] out = new int[in.length + 1];
        System.arraycopy(in, 0, out, 0, in.length);
        out[in.length] = type;
        return out;
    }

    /** SensorSource.nativeReportDiagnosticsData([B)I: HMG-API's point if it has one, else by name. */
    static Method reportDiagnostics(Object sensorSource) throws Exception {
        if (Points.has("gpm.sensor_source.report_diagnostics")) {
            return Points.method(sensorSource.getClass().getClassLoader(), "gpm.sensor_source.report_diagnostics");
        }
        for (Class<?> c = sensorSource.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("nativeReportDiagnosticsData", byte[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // superclass
            }
        }
        throw new NoSuchMethodException("SensorSource.nativeReportDiagnosticsData([B)I");
    }

    @Override
    public void onEnabled(ModContext mod, boolean enabled) {
        if (!enabled) {
            EvModelSender.stop();
            LiveData.stop();
            Precond.stop();
        }
    }

    /** the preconditioning thresholds from the settings (defaults where unset; PrecondCore clamps) */
    static PrecondCore.Config precondConfig(ModContext mod) {
        PrecondCore.Config d = PrecondCore.DEFAULT_CONFIG;
        return new PrecondCore.Config(
                mod.settingInt("precond_min_kw", d.minDcKw),
                mod.settingInt("precond_window_min", d.windowSeconds / 60) * 60,
                mod.settingInt("precond_arrival_m", d.arrivalMeters),
                mod.settingInt("precond_max_starts", d.maxStartsPerRoute));
    }

    @Override
    public void onSettingsChanged(ModContext mod) {
        Precond.configure(precondConfig(mod));
        if (!mod.settingBoolean("energy_model", true)) {
            EvModelSender.stop();
        }
        if (!mod.settingBoolean("live_data", true)) {
            LiveData.stop();
        }
        if (!mod.settingBoolean("preconditioning", true)) {
            Precond.stop();
        }
    }
}
