package com.google.android.projection.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for the HMG-API mod's own pieces (hmgapi-mod/test/run.sh):
 *  - Precond.HmgVehicle: the real PrecondCore driving it through a route to a
 *    DC charger, with the reads faked and Relay's transport recorded, sends
 *    exactly start / route / stop to the relay service;
 *  - HmgApiEntry.withSensor: sensor 23 added once;
 *  - EvCar with HMG-API absent (no hook map on a desktop): every read is
 *    "unknown", nothing throws (the shared EvDriveLog row is SenderRowTest's).
 * Reuses PrecondTest's protobuf builders (gpm-ev/test).
 */
public final class ModTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    /** the relay service, as Relay's transport sees it */
    static final class FakeRelay implements Relay.Transport {
        final List<String> requests = new ArrayList<String>();
        String answer = "ok";

        @Override
        public String exchange(String request) {
            requests.add(request);
            return "ping".equals(request) ? "ok" : answer;
        }

        String drain() {
            String s = String.join(" | ", requests);
            requests.clear();
            return s;
        }
    }

    /** PrecondCore's vehicle: faked reads, HmgVehicle's writes */
    static final class Car implements PrecondCore.Vehicle {
        final Precond.HmgVehicle hmg = new Precond.HmgVehicle();
        int enabled = 2, ready = 1, indicator = 0;

        public boolean compatible() {
            return true;
        }

        public int readEnabled() {
            return enabled;
        }

        public int readReady() {
            return ready;
        }

        public int readIndicator() {
            return indicator;
        }

        public void write(int tx, int[] v) throws Exception {
            hmg.write(tx, v);
        }

        public void pause(long ms) {
        }
    }

    static void testWrites() throws Exception {
        FakeRelay relay = new FakeRelay();
        Relay.sTransport = relay;
        Car car = new Car();
        PrecondTest.FakeJournal journal = new PrecondTest.FakeJournal();
        PrecondCore core = new PrecondCore(car, journal, new PrecondCore.Logger() {
            public void log(String m) {
                if (System.getenv("VERBOSE") != null) System.out.println("    EvPrecond: " + m);
            }
        });
        long t = 1000000;
        core.recover(t);
        check("nothing sent before a route", relay.drain().isEmpty());

        // Maps guides to a 150 kW charger 30 km / 20 min away
        core.onNavStatus(1, t);
        NavProto.Dump s = NavProto.parse(PrecondTest.sMsg(new Object[] {"x", PrecondTest.DC150}));
        core.onDestinations(s.destinations, t);
        NavProto.Dump p = NavProto.parse(PrecondTest.pMsg(new int[][] {{30000, 1200}}));
        core.onRemaining(p.remaining, t);
        t += PrecondCore.REFRESH_MS;
        core.tick(t);
        String sent = relay.drain();
        check("start with the real distance and time: " + sent, sent.equals("start 30 20"));
        check("owned after start", journal.owned);

        // the car reports Active; each refresh keeps the values current
        car.indicator = 1;
        p = NavProto.parse(PrecondTest.pMsg(new int[][] {{25000, 1000}}));
        core.onRemaining(p.remaining, t);
        t += PrecondCore.REFRESH_MS;
        core.tick(t);
        sent = relay.drain();
        check("refresh as route values: " + sent, sent.contains("route 25 17") && !sent.contains("start"));

        // the route ends (guidance off): cancel
        core.onNavStatus(2, t);
        t += PrecondCore.REFRESH_MS;
        core.tick(t);
        sent = relay.drain();
        check("cancel as one stop: " + sent, sent.equals("stop"));
        check("no route values after the stop", !sent.contains("route"));

        // another mod (or the owner) already conditioning: the start fails, so we never own it
        Car car2 = new Car();
        relay.answer = "err already conditioning (launchpad)";
        boolean threw = false;
        try {
            car2.write(PrecondCore.TX_ROUTE_KM, new int[] {12});
            car2.write(PrecondCore.TX_REMAIN_MIN, new int[] {9});
            car2.write(PrecondCore.TX_STATION, new int[] {2, 3});
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("a refused start throws (PrecondCore doesn't own it)", threw);
        relay.answer = "ok";
        relay.drain();

        threw = false;
        try {
            new Precond.HmgVehicle().write(PrecondCore.TX_STATION, new int[] {2, 3});
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("no start without route values", threw && relay.drain().isEmpty());

        threw = false;
        try {
            new Precond.HmgVehicle().write(21, new int[] {1});
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("anything outside the profile is refused", threw && relay.drain().isEmpty());
        Relay.sTransport = Relay.LOCAL_SOCKET;
    }

    static void testSensor() {
        int[] in = {1, 10, 13};
        int[] out = HmgApiEntry.withSensor(in, 23);
        check("23 added", out.length == 4 && out[3] == 23);
        check("23 added once", HmgApiEntry.withSensor(out, 23) == out);
    }

    /** LiveData: the fuel and odometer reports Android Auto apps read (CarInfo) */
    static void testLiveData() throws Exception {
        final java.util.List<String> sent = new java.util.ArrayList<String>();
        LiveData.Out out = new LiveData.Out() {
            public void fuel(int percent, int rangeMeters, boolean low) { sent.add("fuel " + percent + " " + rangeMeters + " " + low); }

            public void odometer(double km) { sent.add("odo " + km); }
        };
        LiveData.send(out, 187, 412, 233201);
        check("93.5% shown as 94%, 412 km as m, odometer 0.1 km: " + sent,
                sent.equals(java.util.Arrays.asList("fuel 94 412000 false", "odo 23320.1")));
        sent.clear();
        LiveData.send(out, 19, -1, -1);
        check("9.5% (10%): low; range unknown left out (-1), no odometer: " + sent, sent.equals(java.util.Arrays.asList("fuel 10 -1 true")));
        sent.clear();
        LiveData.send(out, -1, 300, -1);
        check("no state of charge: no fuel report: " + sent, sent.isEmpty());
        check("a range past 999 km is unknown", LiveData.fuel(100, 1500)[1] == -1);
        check("200 raw is 100%", LiveData.fuel(200, 0)[0] == 100);
        check("sensors 6 and 5 offered once", java.util.Arrays.equals(
                HmgApiEntry.withSensor(HmgApiEntry.withSensor(HmgApiEntry.withSensor(new int[] {1, 13}, 6), 5), 6), new int[] {1, 13, 6, 5}));
    }

    static void testNoHmgApi() {
        check("no hook map: not ready", !HmgCar.ready());
        check("HEV read unknown", EvCar.hev(0x19, 0) == -1);
        check("speed unknown", Float.isNaN(EvCar.speedKph()));
        check("climate unknown", Float.isNaN(EvCar.climate(EvCar.DATC_TEMP)));
        check("unit unknown", EvCar.climateUnitIsCelsius(1) == -1);
        check("no VIN", EvCar.vinModel().isEmpty());
        check("flag falls back to its 260507 number", HmgCar.flag("ENERGYMAP_1000MS", 0x31) == 0x31);
        check("relay down: ping false", !Relay.ping());
        check("no car: the energy model stays off", EvCar.notAnEvWeFit() != null);
    }

    static void testEvCheck() {
        check("58 kWh fits", EvCar.packFits(29000));
        check("77.4 kWh fits", EvCar.packFits(38700));
        check("84 kWh fits", EvCar.packFits(42000));
        check("99.8 kWh (EV9) doesn't", !EvCar.packFits(49900));
        check("42 kWh doesn't", !EvCar.packFits(21000));
        EvCar.cacheHev(0x19, 0);
        check("no state of charge (not an EV): off", EvCar.notAnEvWeFit() != null);
        EvCar.cacheHev(0x19, 92);
        EvCar.cacheHev(0x35, 38700);
        check("Ioniq 5, 77.4 kWh nominal at 46%: on", EvCar.notAnEvWeFit() == null);
        EvCar.cacheHev(0x35, 49900);
        String why = EvCar.notAnEvWeFit();
        check("99.8 kWh nominal: off, and says why: " + why, why != null && why.contains("99.8 kWh"));
        EvCar.cacheHev(0x35, 0xffff);
        EvCar.cacheHev(0x31, 14462);
        check("no nominal size, energy / SOC = 62.9 kWh: on", EvCar.notAnEvWeFit() == null);
        EvCar.cacheHev(0x31, 23000);
        check("no nominal size, energy / SOC = 100 kWh: off", EvCar.notAnEvWeFit() != null);
        EvCar.cacheHev(0x19, 10);
        check("no nominal size, SOC 5%: too coarse to size, on", EvCar.notAnEvWeFit() == null);
    }

    public static void main(String[] args) throws Exception {
        testWrites();
        testSensor();
        testLiveData();
        testNoHmgApi();
        testEvCheck();
        System.out.println("ModTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
