package com.google.android.projection.protocol;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * GPM's side of the link to this mod's "relay" service (service/RelayService):
 * HMG-API's vehicle commands are answered only for a mod's own service or app,
 * never for code inside another app's process, so Precond's conditioning
 * requests go there and the service sends them as this mod.
 *
 * One line per call on the abstract socket SOCKET, one line back: "ok ..." or
 * "err ...". The service only answers GPM (it checks the caller's uid).
 * Called from Precond's handler thread; blocks up to TIMEOUT_MS.
 */
final class Relay {
    private Relay() {}

    static final String SOCKET = "egmp-aa-ev.relay";
    static final int TIMEOUT_MS = 5000;

    /** one request line out, one answer line back (null: none) */
    interface Transport {
        String exchange(String request) throws Exception;
    }

    static final Transport LOCAL_SOCKET = new Transport() {
        @Override
        public String exchange(String request) throws Exception {
            LocalSocket s = new LocalSocket();
            try {
                s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
                s.setSoTimeout(TIMEOUT_MS);
                OutputStream out = s.getOutputStream();
                out.write((request + "\n").getBytes("UTF-8"));
                out.flush();
                return new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
            } finally {
                try {
                    s.close();
                } catch (Exception e) {
                    // closed
                }
            }
        }
    };

    /** the service's socket; the host test puts a fake here */
    static volatile Transport sTransport = LOCAL_SOCKET;

    /** The relay service is up (a "ping" answered). */
    static boolean ping() {
        try {
            return call("ping").startsWith("ok");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sends one request; returns the "ok ..." answer, throws on "err ..." or no answer. */
    static String call(String request) throws Exception {
        String answer = sTransport.exchange(request);
        if (answer == null) {
            throw new IllegalStateException("relay: no answer to " + request);
        }
        if (!answer.startsWith("ok")) {
            throw new IllegalStateException("relay: " + request + ": " + answer);
        }
        return answer;
    }
}
