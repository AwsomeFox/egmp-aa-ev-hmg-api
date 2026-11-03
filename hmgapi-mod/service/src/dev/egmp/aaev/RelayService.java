package dev.egmp.aaev;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

import hmgapi.client.HmgApi;

/**
 * The mod's "relay" service (mod.json services, user system): sends the
 * battery-conditioning requests Precond makes inside GPM as HMG-API's
 * "conditioning" command, as this mod.
 *
 * HMG-API answers vehicle commands only for a mod's own service or app (it
 * knows a service by its session), never for code running inside another
 * app such as GPM. So GPM's side (Relay) connects to this service's abstract
 * socket and asks:
 *
 *   ping                      -> ok
 *   start <km> <min>          -> conditioning auto-start <km> <min>
 *   route <km> <min>          -> conditioning route <km> <min>
 *   stop                      -> conditioning stop
 *
 * and gets one line back, "ok ..." or "err ...". Only GPM may ask (the peer's
 * uid is checked).
 *
 * A stop goes to HMG-API only while this mod holds the conditioning request
 * (Owned, kept in the service's data folder across restarts): HMG-API lets any
 * mod stop it, and Precond also cancels after a start that was refused because
 * someone else (Launchpad, the owner) is conditioning, which must not end
 * theirs. A route refusal naming another holder clears it too. HMG-API keeps the guards: one conditioning state for the
 * unit, a manual stop wins over automatic starts, everything stopped when the
 * mod is turned off. An HMG-API whose command takes no route values ignores
 * them and keeps its own fixed ones; one without "route" refuses it, which is
 * answered "ok" here, since the conditioning keeps running either way.
 */
public final class RelayService {
    static final String MOD = "egmp-aa-ev";
    static final String SOCKET = "egmp-aa-ev.relay";
    static final String GPM = "com.google.android.projection.sink";
    static final int SYSTEM_UID = 1000;
    static final int KM_MAX = 2046, MIN_MAX = 1022;

    private RelayService() {}

    /** "we hold the conditioning request", kept in a file (null: in memory only, the host test) */
    static final class Owned {
        final java.io.File file;
        boolean owned;

        Owned(java.io.File file) {
            this.file = file;
            owned = file != null && file.isFile();
        }

        void set(boolean o) {
            owned = o;
            if (file == null) return;
            try {
                if (o) new java.io.FileOutputStream(file).close();
                else if (file.isFile() && !file.delete()) log("can't clear " + file);
            } catch (Exception e) {
                log("can't record ownership: " + e);
            }
        }
    }

    static Owned sOwned;

    public static void main(String[] args) throws Exception {
        HmgApi api = HmgApi.connect();
        String data = System.getenv("HMGAPI_DATA");
        sOwned = new Owned(data == null ? null : new java.io.File(data, "conditioning.owned"));
        String who = api.mod();
        int gpm = uidOf(GPM);
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        log("relay up as " + who + " on @" + SOCKET + " (GPM uid " + gpm + ")");
        while (true) {
            LocalSocket c = server.accept();
            try {
                serve(api, c, gpm);
            } catch (Throwable t) {
                log("request failed: " + t);
            } finally {
                try {
                    c.close();
                } catch (Exception e) {
                    // closed
                }
            }
        }
    }

    static void serve(HmgApi api, LocalSocket c, int gpm) throws Exception {
        c.setSoTimeout(5000);
        OutputStream out = c.getOutputStream();
        Credentials cr = c.getPeerCredentials();
        int uid = cr == null ? -1 : cr.getUid();
        if (uid != gpm && uid != SYSTEM_UID && uid != 0) {
            out.write(("err not allowed for uid " + uid + "\n").getBytes("UTF-8"));
            log("refused a request from uid " + uid);
            return;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String line = in.readLine();
        String answer = answer(api, sOwned, line == null ? "" : line.trim());
        if (!"ping".equals(line == null ? "" : line.trim())) {
            log(line + " -> " + answer);
        }
        out.write((answer + "\n").getBytes("UTF-8"));
        out.flush();
    }

    /** One request: the answer line. Plain logic around the HMG-API calls (tested in test/RelayTest). */
    static String answer(Commands api, Owned owned, String line) {
        String[] w = line.split("\\s+");
        try {
            if ("ping".equals(w[0]) && w.length == 1) {
                return "ok";
            }
            if (("start".equals(w[0]) || "route".equals(w[0])) && w.length == 3) {
                int km = Integer.parseInt(w[1]);
                int min = Integer.parseInt(w[2]);
                if (km < 0 || km > KM_MAX || min < 0 || min > MIN_MAX) {
                    return "err route values out of range";
                }
                if ("start".equals(w[0])) {
                    String r = api.command("conditioning", "auto-start", w[1], w[2]);
                    if (r.startsWith("ok already conditioning") && !r.contains("(" + MOD + ")")) {
                        return "err " + r.substring(3);   // another mod (or the owner) holds it: not ours to stop
                    }
                    owned.set(true);
                    return r;
                }
                try {
                    return api.command("conditioning", "route", w[1], w[2]);
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.startsWith("refused: conditioning start|auto-start|stop")) {
                        return "ok kept (this HMG-API keeps its own route values)";
                    }
                    if (m.startsWith("refused: only the mod conditioning")) {
                        owned.set(false);   // not ours (any more): the owner or another mod took over
                    }
                    throw e;
                }
            }
            if ("stop".equals(w[0]) && w.length == 1) {
                if (!owned.owned) {
                    return "ok not ours to stop";
                }
                String r = api.command("conditioning", "stop");
                owned.set(false);
                return r;
            }
            return "err unknown request";
        } catch (Exception e) {
            return "err " + e.getMessage();
        }
    }

    static String answer(final HmgApi api, Owned owned, String line) {
        return answer(new Commands() {
            @Override
            public String command(String name, String... a) throws Exception {
                return api.command(name, a);
            }
        }, owned, line);
    }

    /** HMG-API's command call, behind an interface for the host test. */
    interface Commands {
        String command(String name, String... args) throws Exception;
    }

    /** an app's uid from /data/system/packages.list (readable by system), -1 if not found */
    static int uidOf(String pkg) {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/data/system/packages.list"));
            try {
                String l;
                while ((l = r.readLine()) != null) {
                    String[] w = l.split(" ");
                    if (w.length > 1 && pkg.equals(w[0])) {
                        return Integer.parseInt(w[1]);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            log("packages.list: " + e);
        }
        return -1;
    }

    /** to the service's log (HMG-API keeps its output: hmgapid service-log relay) */
    static void log(String s) {
        System.out.println(s);
    }
}
