package com.google.android.projection.protocol;

import android.os.Binder;
import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import hmgapi.hook.Points;

/**
 * The car's services the way HMG-API reaches them: by the names in this car's
 * hook map (core/hookpoints/framework.json), never by binder transaction
 * number. HMG-API maps each name per car and firmware, so the same code runs
 * on every build HMG-API has a map for.
 *
 * Read-only: every call here is a getter, or (BCM) registering a listener that
 * only stores the drive mode. Car commands go through HMG-API's reviewed
 * commands from this mod's service instead (Relay).
 *
 * Mapped hook points used (all in HMG-API 1.0.0):
 *   framework.car_services.hev / .bcm / .datc     service names
 *   framework.hscan_adapter.service_name          tm_hscan_service
 *   framework.hev_service_stub.as_interface       and the hscan, bcm, datc and
 *   framework.hscan_service_stub.as_interface       bcm listener ones
 *   framework.hev_service.hev_status              getHEVStatus(flag)
 *   framework.hev_flag(.display_soc, .ev_ready, .battery_conditioning, .odometer)
 *   framework.hscan_service.car_speed, .hv_health
 *   framework.bcm_service.register_listener, framework.bcm_listener(_stub)
 * Not in HMG-API's catalogue yet (called by method name on the mapped
 * interface, see docs/HMG-API-COMPARISON.md): IDATCService.getVRClimateStatus,
 * isUnitCelsius, IBCMService.getBattCondIndSta, the HEVFlag names other than
 * the four above, IBCMListener.TRANSACTION_onDriveModeChanged.
 *
 * Every method may throw: callers (EvCar, Precond) catch everything, because
 * this runs inside GPM, where an uncaught exception kills Android Auto.
 */
final class HmgCar {
    private HmgCar() {}

    private static final String TAG = "EvCar";

    private static volatile Object sHev, sHscan, sDatc, sBcm;
    private static final Map<String, Integer> FLAGS = new HashMap<String, Integer>();
    private static final Map<String, Method> METHODS = new HashMap<String, Method>();

    /** HMG-API runs here with a good map that has the points the reads need. */
    static boolean ready() {
        return Points.ready()
                && Points.has("framework.hev_service.hev_status")
                && Points.has("framework.hev_service_stub.as_interface")
                && Points.has("framework.car_services.hev");
    }

    // ---- services -------------------------------------------------------------

    private static Object service(String namePoint, String asInterfacePoint) throws Exception {
        String name = (String) Points.get(null, namePoint, null);
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        if (b == null || !b.isBinderAlive()) {
            throw new IllegalStateException(name + " unavailable");
        }
        Object proxy = Points.call(null, asInterfacePoint, null, b);
        if (proxy == null) {
            throw new IllegalStateException(name + ": no interface");
        }
        return proxy;
    }

    static Object hev() throws Exception {
        Object s = sHev;
        if (s == null) {
            sHev = s = service("framework.car_services.hev", "framework.hev_service_stub.as_interface");
        }
        return s;
    }

    static Object hscan() throws Exception {
        Object s = sHscan;
        if (s == null) {
            sHscan = s = service("framework.hscan_adapter.service_name", "framework.hscan_service_stub.as_interface");
        }
        return s;
    }

    static Object datc() throws Exception {
        Object s = sDatc;
        if (s == null) {
            sDatc = s = service("framework.car_services.datc", "framework.datc_service_stub.as_interface");
        }
        return s;
    }

    static Object bcm() throws Exception {
        Object s = sBcm;
        if (s == null) {
            sBcm = s = service("framework.car_services.bcm", "framework.bcm_service_stub.as_interface");
        }
        return s;
    }

    /** Forget a service proxy after a failure (its process may have restarted). */
    static void reset(String which) {
        if ("hev".equals(which)) sHev = null;
        else if ("hscan".equals(which)) sHscan = null;
        else if ("datc".equals(which)) sDatc = null;
        else if ("bcm".equals(which)) sBcm = null;
    }

    // ---- HEV --------------------------------------------------------------------

    /** IHEVService.getHEVStatus(flag) through the mapped method. */
    static int hevStatus(int flag) throws Exception {
        try {
            return (int) num(Points.call(null, "framework.hev_service.hev_status", hev(), flag));
        } catch (Exception e) {
            reset("hev");
            throw e;
        }
    }

    /**
     * An HEVFlag value by its name in this car's framework (HEVManager$HEVFlag),
     * falling back to the value it has on US 260507 (where egmp read it) when
     * the name isn't there. Read-only flags only: a wrong one reads a wrong
     * value, it can't change anything.
     */
    static int flag(String name, int known) {
        synchronized (FLAGS) {
            Integer v = FLAGS.get(name);
            if (v != null) {
                return v.intValue();
            }
            int r = known;
            try {
                Field f = Points.cls(null, "framework.hev_flag").getField(name);
                r = f.getInt(null);
                if (r != known) {
                    android.util.Log.i(TAG, "HEV flag " + name + " is " + r + " here (" + known + " on 260507)");
                }
            } catch (Throwable t) {
                android.util.Log.i(TAG, "HEV flag " + name + " not found by name; using " + known);
            }
            FLAGS.put(name, r);
            return r;
        }
    }

    static int flagBatteryConditioning() {
        return mappedFlag("framework.hev_flag.battery_conditioning", "EV_BATTERYCONDITIONING", 94);
    }

    static int flagReady() {
        return mappedFlag("framework.hev_flag.ev_ready", "EV_RDY", 44);
    }

    /** A flag HMG-API maps itself (a point), else by name. */
    static int mappedFlag(String point, String name, int known) {
        try {
            if (Points.has(point)) {
                return ((Integer) Points.get(null, point, null)).intValue();
            }
        } catch (Throwable t) {
            // by name below
        }
        return flag(name, known);
    }

    // ---- hscan ------------------------------------------------------------------

    /** Display speed, km/h (getCarSpeed(0)). */
    static float speedKph() throws Exception {
        try {
            return num(Points.call(null, "framework.hscan_service.car_speed", hscan(), (byte) 0));
        } catch (Exception e) {
            reset("hscan");
            throw e;
        }
    }

    /** BMS SOH raw (getXEV_Bms_GDS_SOH). */
    static int sohRaw() throws Exception {
        try {
            return (int) num(Points.call(null, "framework.hscan_service.hv_health", hscan()));
        } catch (Exception e) {
            reset("hscan");
            throw e;
        }
    }

    // ---- climate (DATC) -----------------------------------------------------------

    static float vrClimateStatus(int flag) throws Exception {
        try {
            Object d = datc();
            return num(method(d, "getVRClimateStatus", int.class).invoke(d, flag));
        } catch (Exception e) {
            reset("datc");
            throw e;
        }
    }

    static boolean isUnitCelsius(int unitRaw) throws Exception {
        try {
            Object d = datc();
            return num(method(d, "isUnitCelsius", int.class).invoke(d, unitRaw)) != 0f;
        } catch (Exception e) {
            reset("datc");
            throw e;
        }
    }

    // ---- body control (BCM) -------------------------------------------------------

    /** IBCMService.getBattCondIndSta(): 0 off, 1 the car is conditioning the battery. */
    static int batteryConditioningIndicator() throws Exception {
        try {
            Object b = bcm();
            return (int) num(method(b, "getBattCondIndSta").invoke(b));
        } catch (Exception e) {
            reset("bcm");
            throw e;
        }
    }

    /** The interface descriptor IBCMListener callbacks carry. */
    static String bcmListenerDescriptor() {
        return Points.cls(null, "framework.bcm_listener").getName();
    }

    /** IBCMListener.Stub.TRANSACTION_onDriveModeChanged (0x11 on 260507). */
    static int bcmDriveModeTransaction() {
        try {
            Field f = Points.cls(null, "framework.bcm_listener_stub").getDeclaredField("TRANSACTION_onDriveModeChanged");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return 0x11;
        }
    }

    /** registerBCMListener(listener, token): our Binder answers the callbacks. */
    static IBinder registerBcmListener(Binder listener) throws Exception {
        Object b = bcm();
        Object asListener = Points.call(null, "framework.bcm_listener_stub.as_interface", null, listener);
        Points.call(null, "framework.bcm_service.register_listener", b, asListener, new Binder());
        Method asBinder = b.getClass().getMethod("asBinder");
        return (IBinder) asBinder.invoke(b);
    }

    // ---- helpers ------------------------------------------------------------------

    private static Method method(Object target, String name, Class<?>... params) throws Exception {
        String key = target.getClass().getName() + "." + name;
        synchronized (METHODS) {
            Method m = METHODS.get(key);
            if (m == null) {
                m = target.getClass().getMethod(name, params);
                METHODS.put(key, m);
            }
            return m;
        }
    }

    /** a getter's answer as a number (AIDL int, byte, float) or boolean (1/0) */
    static float num(Object v) {
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? 1f : 0f;
        }
        return ((Number) v).floatValue();
    }
}
