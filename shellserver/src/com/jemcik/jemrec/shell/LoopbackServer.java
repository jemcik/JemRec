package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.util.Ln;

import android.os.Looper;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The recorder's port on loopback: bound, each connection on a thread of its
 * own, who connects checked, and the handshake every command is proved with.
 * What each command does is Commands'.
 */
final class LoopbackServer {

    private LoopbackServer() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Sessions run concurrently, one thread each. Eight is more than the app
     *  has callers; past it a connection is dropped rather than queued. */
    private static final int MAX_SESSIONS = 8;
    static final AtomicInteger sessions = new AtomicInteger();
    /** Pings since the last heartbeat line. Counted there rather than logged
     *  one by one: the keep-alive pings several times a visit, all day. */
    static final AtomicInteger pings = new AtomicInteger();
    static volatile int listenPort = Main.DEFAULT_PORT;

    /** A refusal is logged at most this often, with a running count, so a
     *  process hammering the port cannot fill the log with itself. */
    private static final long REFUSAL_LOG_GAP_MS = 10 * 1000L;
    private static long lastRefusalLog;
    private static int refusals;

    /** Six seconds of patience for a predecessor to let go of the port. */
    private static final int BIND_ATTEMPTS = 15;
    private static final long BIND_RETRY_MS = 400L;

    /** Reading the command must not be able to hang the accept loop forever. */
    private static final int COMMAND_TIMEOUT_MS = 5000;

    /**
     * Take the port, allowing for a predecessor that has not finished dying.
     *
     * One attempt was enough to lose the recorder outright. The app pkills the
     * old daemon and starts this one; if the kernel has not reaped the old
     * process yet the bind fails with EADDRINUSE, the accept loop stops and
     * this process exits - so the kill worked and the replacement never
     * arrived, leaving a phone that silently records nothing. Retrying costs a
     * few seconds in the one case that used to be fatal.
     */
    private static ServerSocket bind(int port) throws Exception {
        BindException last = null;
        for (int attempt = 0; attempt < BIND_ATTEMPTS; attempt++) {
            try {
                // Backlog 8, not 1. The accept loop is single-threaded, so while
                // one session runs every other connect waits in this queue - and
                // with a backlog of one, a second caller (the keep-alive and a
                // resume arriving together) was REFUSED, which the app read as
                // "dead" and "port free" and answered by spawning a duplicate
                // that then died on EADDRINUSE. Eight covers every caller the
                // app has, several times over, and costs nothing.
                return new ServerSocket(port, 8, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            } catch (BindException e) {
                last = e;
                Ln.w("port " + port + " still held, retrying");
                Shell.sleep(BIND_RETRY_MS);
            }
        }
        throw last;
    }

    static void acceptLoop(int port) {
        try {
            // Bound explicitly to loopback, so this is never exposed to a
            // network even momentarily, on any interface, in any Wi-Fi state.
            ServerSocket server = bind(port);
            Ln.i("jemrec-capture build " + Main.BUILD + " listening on 127.0.0.1:" + port
                    + " (uid " + android.os.Process.myUid() + ")");

            while (true) {
                Socket client = server.accept();
                // EACH CONNECTION ON ITS OWN THREAD. Sessions used to run here
                // one after another, so a slow client held every other one in
                // the backlog: a FETCH to an app the OS had frozen mid-copy
                // blocked the pings queued behind it, and unanswered pings are
                // what the app reads as a dead recorder. Bounded, so a process
                // opening connections and going quiet ties up a handful of
                // threads for a few seconds each, not the daemon.
                if (sessions.get() >= MAX_SESSIONS) {
                    Ln.w("session: " + MAX_SESSIONS + " already open, dropping a connection");
                    Shell.closeQuietly(client);
                    continue;
                }
                sessions.incrementAndGet();
                Thread worker = new Thread(() -> serve(client), "jemrec-session");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable t) {
            Ln.e("acceptor stopped", t);
            Looper.getMainLooper().quitSafely();
        }
    }

    private static void serve(Socket client) {
        try {
            if (!fromJemRec(client)) {
                refuse(client, "not JemRec");
                return;
            }
            session(client);
        } catch (AudioCaptureException e) {
            // Already logged by the capture layer with a specific reason. One
            // failed session must not kill the daemon: there is no ADB around
            // to restart it.
            Ln.e("session failed to start capture");
        } catch (Exception e) {
            Ln.e("session ended with an error", e);
        } finally {
            Shell.closeQuietly(client);
            sessions.decrementAndGet();
        }
    }

    private static void session(Socket client) throws Exception {
        // The timeout stays on for the whole session. Every read here is a
        // header - a command, a proof, a name - and the one long transfer,
        // FETCH, is a write. It used to be lifted after the first byte, which
        // let a client send 'F' and nothing more to park the acceptor for good.
        client.setSoTimeout(COMMAND_TIMEOUT_MS);
        int first = client.getInputStream().read();

        if (first == Wire.COMMAND_PING) {
            Commands.pong(client);
            return;
        }
        if (first != Wire.COMMAND_HELLO) {
            refuse(client, "no handshake (first byte " + first + ")");
            return;
        }
        if (!authenticate(client)) {
            return;
        }

        Commands.run(client);
    }

    /**
     * The handshake (see Handshake), from the daemon's side. True once the
     * client has proved it knows the token; false - already logged, nothing
     * sent that helps anyone - otherwise.
     */
    private static boolean authenticate(Socket client) throws IOException {
        String refused = Handshake.prove(client.getInputStream(), client.getOutputStream(), Main.appToken, RANDOM);
        if (refused != null) {
            refuse(client, refused);
            return false;
        }
        return true;
    }

    /** Log a refused connection - throttled, counted, and with the uid that
     *  made it, which is the one fact worth having about a refusal. */
    static void refuse(Socket client, String why) {
        synchronized (Calls.LOCK) {
            refusals++;
            long now = System.currentTimeMillis();
            if (now - lastRefusalLog < REFUSAL_LOG_GAP_MS) {
                return;
            }
            lastRefusalLog = now;
            Ln.w("session: refused (" + refusals + " so far) from uid " + peerUid(client) + " - " + why);
        }
    }

    /**
     * Who is on the other end of a loopback connection: the uid owning the
     * client's socket, from /proc/net/tcp, which shell may read and which lists
     * every socket on the phone with its owner. Diagnostic only - the refusal
     * is decided by then - but "which app" is the whole question a refusal
     * raises. -1 if the table cannot be read or the socket is not in it.
     */
    private static int peerUid(Socket client) {
        int clientPort = client.getPort();
        for (String table : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(table), StandardCharsets.UTF_8))) {
                String line = reader.readLine(); // the header
                while ((line = reader.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 8) {
                        continue;
                    }
                    // The CLIENT's row: its local port is what we see as the
                    // remote one, and its remote port is ours.
                    if (portOf(f[1]) == clientPort && portOf(f[2]) == listenPort) {
                        return Integer.parseInt(f[7]);
                    }
                }
            } catch (Throwable ignored) {
                // Not readable, or not in the shape expected: unknown it is.
            }
        }
        return -1;
    }

    /**
     * JemRec itself, or the shell (adb, and the tools run over it): the only
     * callers this daemon has. Anything else is turned away before a byte is
     * read. Unknown - the table could not be read, or the app's uid not found
     * - is let through to the handshake.
     */
    private static boolean fromJemRec(Socket client) {
        int uid = peerUid(client);
        if (Checks.callerAllowed(uid, appUid(false))) {
            return true;
        }
        // Reinstalled since this daemon started, perhaps: a new uid. Asked
        // again once before turning the caller away.
        return Checks.callerAllowed(uid, appUid(true));
    }

    private static volatile int appUid = -1;

    private static int appUid(boolean fresh) {
        if (fresh || appUid < 0) {
            try {
                appUid = FakeContext.get().getPackageManager().getPackageUid(Main.APP_PACKAGE, 0);
            } catch (Throwable t) {
                appUid = -1;
            }
        }
        return appUid;
    }

    /** The port half of a /proc/net/tcp address, "0100007F:6F38" -> 28472. */
    private static int portOf(String address) {
        return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1), 16);
    }
}
