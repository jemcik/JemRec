package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.util.Ln;

import android.os.Looper;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The recorder's port on loopback: bound, each connection on a thread of its
 * own, who connects checked, and the handshake every command is proved with.
 * What each command does is Commands'.
 */
final class LoopbackServer {

    private LoopbackServer() {
    }

    /**
     * THE LOOPBACK PROTOCOL, VERSION 2: EVERY COMMAND IS AUTHENTICATED.
     *
     * Port 28472 is a plain TCP port on loopback, and loopback is shared by
     * every process on the phone. Version 1 took a bare command byte, so any
     * app could fetch a finished recording ('F'), delete one ('D'), open a
     * live capture of the call in progress ('R'), or stop the daemon ('Q').
     * For a call recorder that is not a rough edge; it is the whole game.
     *
     * Every connection now proves itself first, in both directions, and the
     * token never crosses the socket:
     *
     *   app    -> 'A' + Nc                                  (16 random bytes)
     *   daemon -> Nd + HMAC(token, "jemrec-daemon" | Nc | Nd)
     *   app    -> HMAC(token, "jemrec-client" | Nc | Nd) + command [+ payload]
     *
     * THE DAEMON PROVES ITSELF FIRST, and that is what protects the app: any
     * app can bind 28472 while this daemon is down - a reboot, an adbd
     * restart - and answer connections in its place. A scheme that sent the
     * token as a password would hand it to whoever answered. Here the app
     * sends nothing but a nonce until the far end has shown it knows the
     * token, and nothing sent afterwards can be replayed: both nonces are
     * fresh per connection and one of them is chosen here.
     *
     * 'P' on its own still answers PONG with no handshake. It reveals nothing
     * an open port does not, and the answer carries the protocol version, so
     * the app can tell an out-of-date daemon from a current one and replace
     * it. Why there is a ping at all: the app has to be able to ASK whether
     * the daemon is alive, and before there was a command byte the only way
     * to ask was to connect, which started a capture session and tore an
     * AudioRecord up and down on every health check.
     */
    static final int PROTOCOL_VERSION = 2;
    private static final int COMMAND_HELLO = 'A';
    private static final int NONCE_BYTES = 16;
    private static final int MAC_BYTES = 32;
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final byte[] DAEMON_LABEL = "jemrec-daemon".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLIENT_LABEL = "jemrec-client".getBytes(StandardCharsets.UTF_8);
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

        if (first == Commands.COMMAND_PING) {
            Commands.pong(client);
            return;
        }
        if (first != COMMAND_HELLO) {
            refuse(client, "no handshake (first byte " + first + ")");
            return;
        }
        if (!authenticate(client)) {
            return;
        }

        Commands.run(client);
    }

    /**
     * The handshake described above, from the daemon's side. True once the
     * client has proved it knows the token; false - already logged, nothing
     * sent that helps anyone - otherwise.
     */
    private static boolean authenticate(Socket client) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        byte[] clientNonce = new byte[NONCE_BYTES];
        try {
            in.readFully(clientNonce);
        } catch (EOFException | SocketTimeoutException e) {
            refuse(client, "hung up before sending a nonce");
            return false;
        }

        String token = Main.appToken;
        if (token.isEmpty()) {
            // Started by hand with no token: nothing can prove itself to this
            // daemon, so nothing gets in. Fail closed rather than open.
            refuse(client, "this daemon has no token to check against");
            return false;
        }
        byte[] key = token.getBytes(StandardCharsets.UTF_8);

        byte[] daemonNonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(daemonNonce);
        OutputStream out = client.getOutputStream();
        out.write(daemonNonce);
        out.write(mac(key, DAEMON_LABEL, clientNonce, daemonNonce));
        out.flush();

        byte[] proof = new byte[MAC_BYTES];
        try {
            in.readFully(proof);
        } catch (EOFException | SocketTimeoutException e) {
            refuse(client, "hung up during the handshake");
            return false;
        }
        // Constant-time, so the comparison itself leaks nothing about how
        // much of a guess was right.
        if (!MessageDigest.isEqual(proof, mac(key, CLIENT_LABEL, clientNonce, daemonNonce))) {
            refuse(client, "wrong proof");
            return false;
        }
        return true;
    }

    private static byte[] mac(byte[] key, byte[] label, byte[] a, byte[] b) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            mac.update(label);
            mac.update(a);
            mac.update(b);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(MAC_ALGORITHM + " is not available", e);
        }
    }

    /** Log a refused connection - throttled, counted, and with the uid that
     *  made it, which is the one fact worth having about a refusal. */
    static void refuse(Socket client, String why) {
        synchronized (Main.LOCK) {
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
