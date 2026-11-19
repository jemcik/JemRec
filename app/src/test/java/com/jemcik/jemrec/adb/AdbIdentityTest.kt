package com.jemcik.jemrec.adb

import android.content.Context
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayInputStream
import java.io.File
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The ADB identity's certificate, which BouncyCastle assembles: the one adbd
 * keeps when the user pairs, and the one every TLS connect after it shows.
 * No test built it until 1.2.1 moved BouncyCastle from 1.81 to 1.87 for the
 * advisories on 1.81 - a library update that changed it unseen would show
 * only as a phone that will not pair.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AdbIdentityTest {

    private val app: Context = RuntimeEnvironment.getApplication()

    @Before fun setUp() = AdbIdentity.forget(app)

    @After fun tearDown() = AdbIdentity.forget(app)

    @Test fun itsCertificateIsSelfSignedForItsOwnKey() {
        val identity = AdbIdentity.getOrCreate(app)
        val cert = identity.certificate as X509Certificate
        assertEquals(3, cert.version)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertTrue(cert.subjectX500Principal.name, cert.subjectX500Principal.name.contains("CN=JemRec"))
        // Signed by the key it carries: verify() throws otherwise.
        cert.verify(cert.publicKey)
        // And that key is the identity's: what the private key signs, the
        // certificate's key verifies.
        val data = "jemrec".toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").apply {
            initSign(identity.privateKey)
            update(data)
        }.sign()
        val checked = Signature.getInstance("SHA256withRSA").apply {
            initVerify(cert.publicKey)
            update(data)
        }.verify(signed)
        assertTrue("the certificate's key is not the identity's", checked)
    }

    /** Valid now, and for decades: it is never made again (see AdbIdentity),
     *  and an expired client certificate fails the TLS handshake. */
    @Test fun itsCertificateIsValidNowAndForDecades() {
        val cert = AdbIdentity.getOrCreate(app).certificate as X509Certificate
        cert.checkValidity()
        val years = (cert.notAfter.time - System.currentTimeMillis()) / (365L * 24 * 60 * 60 * 1000)
        assertTrue("valid for $years years", years >= 29)
    }

    /** Kept as the files the next start reads, and read back as the same
     *  certificate: the pairing lives in that key. */
    @Test fun itIsKeptAndReadsBackTheSame() {
        val cert = AdbIdentity.getOrCreate(app).certificate
        assertTrue(AdbIdentity.exists(app))
        val stored = File(app.filesDir, "adb_certificate.der").readBytes()
        val read = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(stored))
        assertArrayEquals(cert.encoded, read.encoded)
    }
}
