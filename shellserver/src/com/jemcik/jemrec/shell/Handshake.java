package com.jemcik.jemrec.shell;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 *
 * This is the daemon's side, in plain Java over the connection's two
 * streams, so daemon-tests holds it to the handshake worked by hand in
 * shellserver/tests/fixtures/handshake.txt, which the app's tests hold
 * their side to too.
 */
final class Handshake {

    private Handshake() {
    }

    static final int NONCE_BYTES = 16;
    static final int MAC_BYTES = 32;
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final byte[] DAEMON_LABEL = "jemrec-daemon".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLIENT_LABEL = "jemrec-client".getBytes(StandardCharsets.UTF_8);

    /**
     * The handshake from the daemon's side, the client's 'A' read already:
     * null once the client has proved it knows [token]; otherwise why not,
     * for the refusal's log line - and nothing sent that helps anyone.
     * [random] chooses this side's nonce.
     */
    static @Nullable String prove(InputStream stream, OutputStream out, String token, Random random)
            throws IOException {
        DataInputStream in = new DataInputStream(stream);
        byte[] clientNonce = new byte[NONCE_BYTES];
        try {
            in.readFully(clientNonce);
        } catch (EOFException | SocketTimeoutException e) {
            return "hung up before sending a nonce";
        }

        if (token.isEmpty()) {
            // Started by hand with no token: nothing can prove itself to this
            // daemon, so nothing gets in. Fail closed rather than open.
            return "this daemon has no token to check against";
        }
        byte[] key = token.getBytes(StandardCharsets.UTF_8);

        byte[] daemonNonce = new byte[NONCE_BYTES];
        random.nextBytes(daemonNonce);
        out.write(daemonNonce);
        out.write(daemonProof(key, clientNonce, daemonNonce));
        out.flush();

        byte[] proof = new byte[MAC_BYTES];
        try {
            in.readFully(proof);
        } catch (EOFException | SocketTimeoutException e) {
            return "hung up during the handshake";
        }
        // Constant-time, so the comparison itself leaks nothing about how
        // much of a guess was right.
        if (!MessageDigest.isEqual(proof, clientProof(key, clientNonce, daemonNonce))) {
            return "wrong proof";
        }
        return null;
    }

    /** What this side sends to prove it knows the token. */
    static byte[] daemonProof(byte[] key, byte[] clientNonce, byte[] daemonNonce) {
        return mac(key, DAEMON_LABEL, clientNonce, daemonNonce);
    }

    /** What the client must send back to prove the same. */
    static byte[] clientProof(byte[] key, byte[] clientNonce, byte[] daemonNonce) {
        return mac(key, CLIENT_LABEL, clientNonce, daemonNonce);
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
}
