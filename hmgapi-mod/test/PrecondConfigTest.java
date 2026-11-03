package com.google.android.projection.protocol;

/**
 * The preconditioning thresholds as the HMG-API mod's settings set them
 * (PrecondCore.Config, through Precond.configure / setConfig): the minimum
 * charger power, the start window, the arrival distance and the starts per
 * route, clamping, and a change on the current route. Reuses PrecondTest's rig
 * and protobuf builders (gpm-ev/test). hmgapi-mod/test/run.sh runs it.
 */
public final class PrecondConfigTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    static PrecondCore.Config cfg(int kw, int windowMin, int arrivalM, int starts) {
        return new PrecondCore.Config(kw, windowMin * 60, arrivalM, starts);
    }

    static final String START_30_20 = PrecondTest.START_30_20;

    public static void main(String[] a) throws Exception {
        PrecondCore.Config d = PrecondCore.DEFAULT_CONFIG;
        check("defaults are the NEMESIS build's", d.minDcKw == 50 && d.windowSeconds == 3600
                && d.arrivalMeters == 200 && d.maxStartsPerRoute == 3);

        PrecondCore.Config lo = new PrecondCore.Config(-5, 60, 10, 0);
        check("clamped up: 0 kW, 5 min, 50 m, 1 start: " + lo,
                lo.minDcKw == 0 && lo.windowSeconds == 300 && lo.arrivalMeters == 50 && lo.maxStartsPerRoute == 1);
        PrecondCore.Config hi = new PrecondCore.Config(1000, 1000 * 60, 99999, 99);
        check("clamped down: 400 kW, 180 min, 2000 m, 10 starts: " + hi,
                hi.minDcKw == 400 && hi.windowSeconds == 180 * 60 && hi.arrivalMeters == 2000 && hi.maxStartsPerRoute == 10);

        // minimum power: 150 kW is below 200, so no target; lowered on the same route, it starts
        PrecondTest.Rig kw = new PrecondTest.Rig();
        kw.c.setConfig(cfg(200, 60, 200, 3), kw.t);
        kw.route(PrecondTest.DC150, "C", 30000, 1200);
        kw.ticks(5);
        check("150 kW charger, minimum 200 kW: no start (" + PrecondCore.stateName(kw.c.state()) + ")", kw.v.drain().isEmpty() && kw.c.state() == PrecondCore.ST_IDLE);
        kw.c.setConfig(cfg(100, 60, 200, 3), kw.t);
        kw.ticks(1);
        check("minimum lowered to 100 kW on the same route: starts", kw.v.drain().equals(START_30_20));

        // a Level 2 stop counts once the minimum is 0
        PrecondTest.Rig l2 = new PrecondTest.Rig();
        l2.c.setConfig(cfg(0, 60, 200, 3), l2.t);
        l2.route(new int[] {35000, 11000, -1}, "Level 2", 30000, 1200);
        l2.ticks(1);
        check("11 kW stop with minimum 0: starts", l2.v.drain().equals(START_30_20));

        // start window: 30 min
        PrecondTest.Rig win = new PrecondTest.Rig();
        win.c.setConfig(cfg(50, 30, 200, 3), win.t);
        win.route(PrecondTest.DC150, "C", 60000, 40 * 60);
        win.ticks(5);
        check("40 min away, window 30 min: waits", win.v.drain().isEmpty() && win.c.state() == PrecondCore.ST_WAITING);
        win.dump(PrecondTest.pMsg(new int[][] {{40000, 25 * 60}}));
        win.ticks(1);
        check("25 min away: starts", win.v.drain().equals("65(40) 66(25) sleep10 62(2,3) sleep130"));

        PrecondTest.Rig wide = new PrecondTest.Rig();
        wide.c.setConfig(cfg(50, 120, 200, 3), wide.t);
        wide.route(PrecondTest.DC150, "C", 150000, 90 * 60);
        wide.ticks(1);
        check("90 min away, window 120 min: starts", wide.v.drain().equals("65(150) 66(90) sleep10 62(2,3) sleep130"));

        // arrival distance: 500 m
        PrecondTest.Rig arr = new PrecondTest.Rig();
        arr.c.setConfig(cfg(50, 60, 500, 3), arr.t);
        arr.route(PrecondTest.DC150, "C", 30000, 1200);
        arr.ticks(1);
        arr.v.drain();
        arr.dump(PrecondTest.pMsg(new int[][] {{400, 30}}));
        check("400 m from the charger, arrival 500 m: cancelled", arr.v.drain().equals(PrecondTest.CANCEL));
        PrecondTest.Rig near = new PrecondTest.Rig();
        near.c.setConfig(cfg(50, 60, 500, 3), near.t);
        near.route(PrecondTest.DC150, "C", 450, 60);
        near.ticks(3);
        check("450 m away, arrival 500 m: no start", near.v.drain().isEmpty());

        // starts per route: 1, with the charger flipping in and out of Maps' list
        PrecondTest.Rig q = new PrecondTest.Rig();
        q.c.setConfig(cfg(50, 60, 200, 1), q.t);
        q.route(PrecondTest.DC150, "C", 30000, 1200);
        for (int i = 0; i < 10; i++) {
            q.ticks(1);
            q.dump(PrecondTest.sMsg(new Object[] {"X", null}));
            q.v.indicator = 0;
            q.ticks(1);
            q.dump(PrecondTest.sMsg(new Object[] {"C", PrecondTest.DC150}));
        }
        String s = q.v.drain();
        int starts = s.split("62\\(2,3\\)", -1).length - 1;
        check("one start per route (" + starts + ")", starts == 1);

        // a minimum raised while conditioning: the stop no longer counts, so it's cancelled
        PrecondTest.Rig up = new PrecondTest.Rig();
        up.route(PrecondTest.DC150, "C", 30000, 1200);
        up.ticks(1);
        up.v.drain();
        up.c.setConfig(cfg(200, 60, 200, 3), up.t);
        check("minimum raised above the stop while conditioning: cancelled", up.v.drain().equals(PrecondTest.CANCEL));

        // the same settings again change nothing
        PrecondTest.Rig same = new PrecondTest.Rig();
        same.route(PrecondTest.DC150, "C", 30000, 1200);
        same.ticks(1);
        same.v.drain();
        same.c.setConfig(PrecondCore.DEFAULT_CONFIG, same.t);
        same.c.setConfig(new PrecondCore.Config(50, 3600, 200, 3), same.t);
        check("unchanged settings: nothing sent", same.v.drain().isEmpty());

        System.out.println("PrecondConfigTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
