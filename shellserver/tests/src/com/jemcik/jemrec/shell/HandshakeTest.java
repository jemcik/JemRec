package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/**
 * The recorder's side of the handshake against the one worked in
 * fixtures/handshake.txt, which the app's tests check their side against too
 * (HandshakeTest there): the same bytes, or every command a phone sends is
 * refused.
 */
public class HandshakeTest {

    private static final Map<String, String> VECTOR = vector();

    private static Map<String, String> vector() {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = HandshakeTest.class.getResourceAsStream("/handshake.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    String[] parts = line.split("\t");
                    out.put(parts[0], parts[1]);
                }
            }
        } catch (IOException | NullPointerException e) {
            throw new AssertionError("fixtures/handshake.txt not readable", e);
        }
        return out;
    }

    private static byte[] hex(String name) {
        String s = VECTOR.get(name);
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static final String TOKEN = VECTOR.get("token");

    /** Hands out the vector's own nonce, as the recorder's random would one. */
    private static final class Fixed extends Random {
        private static final long serialVersionUID = 1L;

        @Override
        public void nextBytes(byte[] bytes) {
            byte[] nonce = hex("daemon_nonce");
            System.arraycopy(nonce, 0, bytes, 0, bytes.length);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void theRecordersProofIsTheVectors() {
        assertArrayEquals(hex("daemon_proof"), Handshake.daemonProof(
                TOKEN.getBytes(StandardCharsets.UTF_8), hex("client_nonce"), hex("daemon_nonce")));
    }

    @Test
    public void theProofTheRecorderWaitsForIsTheVectors() {
        assertArrayEquals(hex("client_proof"), Handshake.clientProof(
                TOKEN.getBytes(StandardCharsets.UTF_8), hex("client_nonce"), hex("daemon_nonce")));
    }

    @Test
    public void theWholeExchangeIsTheVectors() throws IOException {
        // The app's nonce and proof in, the recorder's nonce and proof out -
        // written, and flushed, before the client's proof is read: the client
        // sends its proof only once it has the recorder's, so written after
        // the read, every connection would wait out its timeout.
        ByteArrayInputStream in = new ByteArrayInputStream(concat(hex("client_nonce"), hex("client_proof")));
        int[] sentAt = {-1};
        int[] readAt = {-1};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                sentAt[0] = size();
                readAt[0] = Handshake.NONCE_BYTES + Handshake.MAC_BYTES - in.available();
            }
        };
        String refused = Handshake.prove(in, out, TOKEN, new Fixed());
        assertNull(refused);
        assertArrayEquals(concat(hex("daemon_nonce"), hex("daemon_proof")), out.toByteArray());
        assertEquals(Handshake.NONCE_BYTES + Handshake.MAC_BYTES, sentAt[0]);
        assertEquals("only the client's nonce read at the flush", Handshake.NONCE_BYTES, readAt[0]);
    }

    @Test
    public void aWrongProofIsRefused() throws IOException {
        byte[] proof = hex("client_proof");
        proof[0] ^= 1;
        assertEquals("wrong proof", Handshake.prove(new ByteArrayInputStream(concat(hex("client_nonce"), proof)),
                new ByteArrayOutputStream(), TOKEN, new Fixed()));
    }

    @Test
    public void withoutATokenNothingGetsInAndNothingIsSent() throws IOException {
        // Started by hand, with no token: fails closed, before a byte goes back.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals("this daemon has no token to check against", Handshake.prove(
                new ByteArrayInputStream(concat(hex("client_nonce"), hex("client_proof"))), out, "", new Fixed()));
        assertEquals(0, out.size());
    }

    @Test
    public void aCallerThatHangsUpIsRefusedForWhatItDidNotSend() throws IOException {
        assertEquals("hung up before sending a nonce", Handshake.prove(
                new ByteArrayInputStream(new byte[5]), new ByteArrayOutputStream(), TOKEN, new Fixed()));
        assertEquals("hung up during the handshake", Handshake.prove(
                new ByteArrayInputStream(concat(hex("client_nonce"), new byte[3])), new ByteArrayOutputStream(),
                TOKEN, new Fixed()));
    }
}
