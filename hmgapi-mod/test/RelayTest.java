package dev.egmp.aaev;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for RelayService.answer: what the relay service asks HMG-API for,
 * and what it tells GPM, for each request (hmgapi-mod/test/run.sh). The fake
 * answers like HMG-API's Commands (hmgapi.vehicle.Commands) of 1.0.0-beta.6,
 * whose conditioning takes no route values and has no "route".
 */
public final class RelayTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    static final class FakeHmgApi implements RelayService.Commands {
        final List<String> calls = new ArrayList<String>();
        String holder;   // who conditions (null: nobody)
        boolean manualStop;

        @Override
        public String command(String name, String... a) throws Exception {
            calls.add(name + " " + String.join(" ", a));
            if (!"conditioning".equals(name)) throw new Exception("not declared in mod.json (vehicle commands): " + name);
            if ("stop".equals(a[0])) {
                if (holder == null) return "ok not conditioning";
                holder = null;
                manualStop = true;
                return "ok conditioning stopped";
            }
            boolean auto = "auto-start".equals(a[0]);
            if (!auto && !"start".equals(a[0])) throw new Exception("refused: conditioning start|auto-start|stop");
            if (auto && manualStop) throw new Exception("refused: stopped by hand; automatic starts wait for a manual start");
            if (holder != null) return "ok already conditioning (" + holder + ")";
            holder = "egmp-aa-ev";
            return "ok conditioning started";
        }
    }

    public static void main(String[] args) {
        FakeHmgApi api = new FakeHmgApi();
        RelayService.Owned own = new RelayService.Owned(null);
        RelayService.Owned own2 = new RelayService.Owned(null);
        check("a stop before any start isn't sent", RelayService.answer(api, own, "stop").equals("ok not ours to stop") && api.calls.isEmpty());
        check("ping", RelayService.answer(api, own, "ping").equals("ok") && api.calls.isEmpty());

        String r = RelayService.answer(api, own, "start 30 20");
        check("start -> auto-start with the route values: " + api.calls, api.calls.get(0).equals("conditioning auto-start 30 20"));
        check("start answered ok", r.equals("ok conditioning started"));

        r = RelayService.answer(api, own, "start 30 20");
        check("a second start, ours already: ok", r.startsWith("ok already conditioning (egmp-aa-ev)"));

        api.calls.clear();
        r = RelayService.answer(api, own, "route 25 17");
        check("route -> conditioning route: " + api.calls, api.calls.get(0).equals("conditioning route 25 17"));
        check("an HMG-API without route keeps conditioning: " + r, r.startsWith("ok kept"));

        r = RelayService.answer(api, own, "stop");
        check("stop", r.equals("ok conditioning stopped"));

        r = RelayService.answer(api, own, "start 30 20");
        check("after a stop, beta.6 refuses automatic starts (the latch the HMG-API patch fixes): " + r,
                r.startsWith("err refused: stopped by hand"));

        FakeHmgApi other = new FakeHmgApi();
        other.holder = "launchpad";
        r = RelayService.answer(other, own2, "start 30 20");
        check("someone else's conditioning is never ours: " + r, r.startsWith("err already conditioning (launchpad)"));
        other.calls.clear();
        r = RelayService.answer(other, own2, "stop");
        check("... so Precond's cancel after that refusal doesn't stop theirs: " + r, r.equals("ok not ours to stop") && other.calls.isEmpty() && "launchpad".equals(other.holder));

        // with the HMG-API patch: someone took over (the owner stopped ours and started their own)
        RelayService.Owned own3 = new RelayService.Owned(null);
        own3.set(true);
        RelayService.Commands patched = new RelayService.Commands() {
            public String command(String name, String... a) throws Exception {
                if ("route".equals(a[0])) throw new Exception("refused: only the mod conditioning changes its route (launchpad)");
                throw new Exception("unexpected " + a[0]);
            }
        };
        r = RelayService.answer(patched, own3, "route 20 12");
        check("a route refusal naming another holder: err, and not ours any more: " + r, r.startsWith("err refused: only") && !own3.owned);
        check("... so the cancel that follows isn't sent", RelayService.answer(patched, own3, "stop").equals("ok not ours to stop"));

        check("km out of range", RelayService.answer(api, own, "start 2047 20").startsWith("err"));
        check("minutes out of range", RelayService.answer(api, own, "route 10 1023").startsWith("err"));
        check("not a number", RelayService.answer(api, own, "start ten 20").startsWith("err"));
        check("unknown request", RelayService.answer(api, own, "charge_door open").startsWith("err unknown"));
        check("empty request", RelayService.answer(api, own, "").startsWith("err"));

        System.out.println("RelayTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
