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
 * Settings (settings.json): energy_model, preconditioning, model_learning, ev_log, live_data, and the
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

    /** E-GMP model codes (ro.build.product's first field): the cars the model was made for */
    static final java.util.List<String> EGMP = java.util.Arrays.asList("NEEV", "NEEV23", "NEEV23SE", "CVEV", "CEEV");

    static boolean egmp(String product) {
        if (product == null || product.isEmpty()) {
            return true;   // unknown: as before
        }
        int dot = product.indexOf('.');
        return EGMP.contains(dot < 0 ? product : product.substring(0, dot));
    }

    static boolean modelOn() {
        return on("energy_model");
    }

    /**
     * Both builds have the "preconditioning" setting (on by default). The full build gets
     * Maps' route data from the Wireless mod's library patch; the standalone build carries
     * that patch in its own library recipes (hmgapi-mod/route/, variants.py) on the builds
     * it has one for. Without route data Precond never starts anything.
     */
    static final String FULL = "egmp-aa-ev", STANDALONE = "egmp-aa-ev-standalone";
    static final java.io.File STATE = new java.io.File("/data/hmgapi/public/state.json");

    /**
     * The standalone build stays off while the Wireless mod is on. Its library recipes carry
     * the Wireless mod's turn-by-turn patch, and HMG-API refuses the second of two mods'
     * recipes on the same bytes: whichever asked first would win, and the Wireless mod
     * could lose its own cluster directions. With the Wireless mod the full build is the
     * one to use (it also keeps the two builds from both running: HMG-API 1.1.0 checks
     * "conflicts" only for mods that change files). HMG-API's public state (its mods,
     * enabled or not) says.
     */
    static boolean standDown(String id, java.io.File state) {
        if (!STANDALONE.equals(id)) return false;
        try {
            byte[] b = new byte[(int) state.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(state);
            try {
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            } finally {
                in.close();
            }
            Object mods = hmgapi.engine.Json.object(new String(b, "UTF-8")).get("mods");
            return mods instanceof java.util.Map && enabledIn((java.util.Map<?, ?>) mods, "wireless-carplay-aa");
        } catch (Exception e) {
            return false;   // no state: run
        }
    }

    static boolean enabledIn(java.util.Map<?, ?> mods, String id) {
        Object m = mods.get(id);
        return m instanceof java.util.Map && Boolean.TRUE.equals(((java.util.Map<?, ?>) m).get("enabled"));
    }

    static boolean precondOn() {
        ModContext m = sMod;
        return m == null || (m.enabled() && m.settingBoolean("preconditioning", false));
    }

    static boolean batteryCurveOn() {
        return on("battery_curve");
    }

    /** settings arrival_charger_pct / arrival_dest_pct: Maps' minimum arrival % at charging stops and target % at the destination (0: Maps' own) */
    static void tripSettings(ModContext mod) {
        EvModelSender.setTripSettings(mod.settingInt("arrival_charger_pct", 0), mod.settingInt("arrival_dest_pct", 0));
    }

    static boolean tripSettingsSet(ModContext mod) {
        return mod.settingInt("arrival_charger_pct", 0) > 0 || mod.settingInt("arrival_dest_pct", 0) > 0;
    }

    static boolean liveDataOn() {
        return on("live_data");
    }

    /** The mod's library recipes, while its folder is readable (the zygote runs as root). */
    @Override
    public void zygoteInit(ModContext mod) {
        NativeCheck.load(new java.io.File(mod.dir, "recipes"), mod.id);
    }

    @Override
    public void appLoaded(final ModContext mod, final LoadedApp app) {
        if (!GPM.equals(app.packageName) || !GPM.equals(app.processName)) {
            return;
        }
        if (standDown(mod.id, STATE)) {
            mod.error("the Wireless CarPlay + Android Auto mod is on: this standalone build stays off (it would take"
                    + " the Wireless mod's library patch). Use " + FULL + " with the Wireless mod", null);
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
        RoadLearn.setEnabled(on("model_learning"));
        ChargeLearn.setEnabled(on("model_learning"));
        String product = EvDriveLog.prop("ro.build.product");
        if (egmp(product)) {
            RoadLearn.setPrior(RoadLearn.PRIOR);
            ChargeLearn.setPrior(ChargeLearn.PRIOR);
        } else {
            // another EV (mod.json "system"): E-GMP values to start from, learned faster
            RoadLearn.setPrior(RoadLearn.PRIOR_OTHER);
            ChargeLearn.setPrior(ChargeLearn.PRIOR_OTHER);
            mod.log("not an E-GMP car (" + product + "): the energy model starts from E-GMP values and learns"
                    + " this car as it drives (road load from " + RoadLearn.samples() + " highway stretches so far"
                    + (on("model_learning") ? "" : ", learning off") + "; capacity from charges above 60%; DC charge"
                    + " times from " + ChargeLearn.sessions() + " stops so far, E-GMP's until then: too fast for a 400 V car)");
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
                        // sensor 26 (the battery % curve and the arrival % settings), where this
                        // build's recipe lets the library take it
                        boolean can26 = NativeCheck.maxSensorType() >= SocCurve.SENSOR;
                        boolean curve = batteryCurveOn() && can26;
                        boolean trip = can26 && tripSettingsSet(mod);
                        if (curve || trip) {
                            p.args[2] = withSensor((int[]) p.args[2], SocCurve.SENSOR);
                        }
                        EvModelSender.setBatteryCurve(curve);
                        EvModelSender.setSensor26(curve || trip);
                        tripSettings(mod);
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
        // timeout) only onDisconnected. Both end the session's senders; the
        // native sensor object is freed earlier, in SensorSource.shutdown(),
        // which hookSensorShutdown covers.
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
        hookSensorShutdown(mod, cl);

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
                + (precondOn() ? "on" : "off") + ", EV log "
                + (mod.settingBoolean("ev_log", true) ? "on" : "off") + ", learning "
                + (mod.settingBoolean("model_learning", true) ? "on" : "off") + ", live data "
                + (mod.settingBoolean("live_data", true) ? "on" : "off") + ")");
    }

    /**
     * GalReceiver.stop() runs doStop(), which calls SensorSource.shutdown() and with it
     * nativeShutdown(), before destroy() runs (stock GPM, at least US 260507 and Canada
     * 260206). Without this hook the sender and live data could send on the freed native
     * object in between, and a native crash there ends GPM and Android Auto with it.
     * Stopping takes the senders' locks, so a send under way finishes first.
     *
     * HMG-API maps the class (gpm.sensor_source) but not this method. The class is a JNI
     * class that keeps its method names, so shutdown() is found by name and shape; a
     * "gpm.sensor_source.shutdown" point is used instead once a map has one.
     */
    static void hookSensorShutdown(final ModContext mod, ClassLoader cl) {
        MethodHook stop = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                try {
                    EvModelSender.stop();
                    LiveData.stop();
                } catch (Throwable t) {
                    mod.error("sensor shutdown", t);
                }
            }
        };
        try {
            if (Points.has("gpm.sensor_source.shutdown")) {
                mod.hook(cl, "gpm.sensor_source.shutdown", stop);
                return;
            }
            Method m = sensorShutdown(Points.cls(cl, "gpm.sensor_source"));
            if (m == null) {
                mod.error("no SensorSource.shutdown(): the senders stop at the session's end only", null);
                return;
            }
            hmgapi.hook.Bridge.hookMethod(m, stop);
        } catch (Throwable t) {
            mod.error("SensorSource.shutdown hook failed: the senders stop at the session's end only", t);
        }
    }

    /** public void shutdown() declared on the sensor source class, or null */
    static Method sensorShutdown(Class<?> source) {
        try {
            Method m = source.getDeclaredMethod("shutdown");
            int f = m.getModifiers();
            boolean ok = m.getReturnType() == void.class && java.lang.reflect.Modifier.isPublic(f)
                    && !java.lang.reflect.Modifier.isStatic(f) && !java.lang.reflect.Modifier.isAbstract(f)
                    && !java.lang.reflect.Modifier.isNative(f);
            return ok ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
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
        tripSettings(mod);   // at once when sensor 26 is already offered; else from the next session
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
