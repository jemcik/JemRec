package com.jemcik.jemrec.contract

import com.jemcik.jemrec.recorder.DaemonClient
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The app's side of the handshake against the one worked in
 * shellserver/tests/fixtures/handshake.txt, which the recorder's own tests
 * check their side against too. The two are written apart, in Kotlin and in
 * Java: a label, the order of the nonces or the key's encoding changed on one
 * side compiles, passes that side's tests, and gets every command refused.
 */
class HandshakeTest {

    private val vector: Map<String, String> = Repo.text("shellserver/tests/fixtures/handshake.txt").lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { it.substringBefore('\t') to it.substringAfter('\t') }

    private fun hex(name: String): ByteArray =
        vector.getValue(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The key as open() takes it: the token's bytes. */
    private val key = vector.getValue("token").toByteArray()

    @Test
    fun theProofTheAppWaitsForIsTheVectors() {
        assertArrayEquals(hex("daemon_proof"), DaemonClient.daemonProof(key, hex("client_nonce"), hex("daemon_nonce")))
    }

    @Test
    fun theProofTheAppSendsIsTheVectors() {
        assertArrayEquals(hex("client_proof"), DaemonClient.clientProof(key, hex("client_nonce"), hex("daemon_nonce")))
    }
}
