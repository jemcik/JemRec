package com.jemcik.jemrec.adb

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The app's ADB identity: an RSA keypair, plus a self-signed X.509 certificate
 * that wraps its public key.
 *
 * WHY THIS IS PERSISTED, AND WHY THAT MATTERS MORE THAN IT LOOKS
 *
 * Pairing is not a login. When the user types the six-digit code, adbd runs a
 * SPAKE2 exchange and, on success, permanently stores THIS public key in its
 * list of trusted keys. Nothing about the pairing is remembered by us; the
 * whole result of it lives on the adbd side, keyed by this certificate.
 *
 * So the key is the pairing. Lose the file and the phone still trusts a key we
 * can no longer prove we hold, and the user has to walk the pairing wizard
 * again. Regenerating a key "because it is cheap" would silently un-pair the
 * app. That is also why the certificate is issued for 30 years rather than the
 * one day the upstream sample uses: the sample regenerates on every run, we do
 * not, and a TLS handshake with an expired client certificate fails.
 *
 * Both files live in the app's private filesDir. They are readable by root and
 * by nothing else.
 */
internal class AdbIdentity private constructor(
    val privateKey: PrivateKey,
    val certificate: Certificate,
) {
    companion object {
        private const val TAG = "JemRec"
        private const val KEY_FILE = "adb_private_key.der"
        private const val CERT_FILE = "adb_certificate.der"

        private const val KEY_SIZE = 2048
        private const val SUBJECT = "CN=JemRec, O=JemRec, C=US"

        /** SHA-256 rather than the sample's SHA-512: universally supported by
         *  adbd's TLS stack and by Android's own JCA providers. */
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

        private const val THIRTY_YEARS_MS = 30L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var cached: AdbIdentity? = null

        /**
         * Load the stored identity, generating and saving one on first use.
         * Safe to call from any thread; generation takes a second or so, so
         * callers should be off the main thread.
         */
        @Synchronized
        fun getOrCreate(context: Context): AdbIdentity {
            cached?.let { return it }

            val keyFile = File(context.filesDir, KEY_FILE)
            val certFile = File(context.filesDir, CERT_FILE)

            val loaded = tryLoad(keyFile, certFile)
            if (loaded != null) {
                Log.i(TAG, "ADB identity: loaded existing keypair")
                cached = loaded
                return loaded
            }

            Log.i(TAG, "ADB identity: no stored keypair, generating a new one")
            val created = generate()
            // Write the key first. If we crash between the two writes, the next
            // tryLoad sees a key with no certificate, fails its pair check, and
            // regenerates both - rather than loading a mismatched pair.
            keyFile.writeBytes(created.privateKey.encoded)
            certFile.writeBytes(created.certificate.encoded)
            cached = created
            return created
        }

        /**
         * Throw away the keypair, so the next pairing starts from nothing.
         *
         * This is as close to "unpair" as an unprivileged app can get. The
         * phone's own list of authorised keys lives in /data/misc/adb, which is
         * root's, and nothing here can edit it - so the OLD key stays trusted
         * by adbd forever. What changes is that this app no longer holds it, so
         * it can no longer connect, and setup has to run again from the pairing
         * code. Practically that is a fresh start; literally it is amnesia
         * rather than revocation, and the difference is worth being honest
         * about.
         */
        /** Whether this app has ever paired. False after a reset. */
        fun exists(context: Context): Boolean =
            File(context.filesDir, KEY_FILE).exists() &&
                File(context.filesDir, CERT_FILE).exists()

        @Synchronized
        fun forget(context: Context) {
            cached = null
            listOf(KEY_FILE, CERT_FILE).forEach { name ->
                val file = File(context.filesDir, name)
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "ADB identity: could not delete $name")
                }
            }
            Log.i(TAG, "ADB identity: forgotten, pairing will be needed again")
        }

        private fun tryLoad(keyFile: File, certFile: File): AdbIdentity? {
            if (!keyFile.exists() || !certFile.exists()) return null
            return try {
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
                AdbIdentity(privateKey, certificate)
            } catch (t: Throwable) {
                // A corrupt or half-written pair is not worth recovering: the
                // cost of regenerating is one re-pairing, the cost of loading a
                // broken identity is a handshake failure with no clear message.
                Log.w(TAG, "ADB identity: stored keypair unreadable, regenerating", t)
                null
            }
        }

        private fun generate(): AdbIdentity {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(KEY_SIZE, SecureRandom())
            val keyPair = generator.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24 * 60 * 60 * 1000) // tolerate clock skew
            val notAfter = Date(now + THIRTY_YEARS_MS)
            val subject = X500Name(X500Principal(SUBJECT).name)

            val builder = JcaX509v3CertificateBuilder(
                subject,                                  // issuer: self-signed
                BigInteger(64, SecureRandom()),           // serial
                notBefore,
                notAfter,
                subject,                                  // subject
                keyPair.public,
            )

            // No .setProvider() anywhere in here on purpose. Registering
            // BouncyCastle as a JCA provider on Android shadows the platform's
            // own crypto and is a well known source of subtle breakage; letting
            // the default provider do the signing keeps Android's implementation
            // in charge and uses BouncyCastle only to assemble the ASN.1.
            val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
            val holder = builder.build(signer)

            // Convert via the platform CertificateFactory rather than
            // JcaX509CertificateConverter, for the same reason: it avoids
            // asking for a named provider that may not be registered.
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(holder.encoded))

            return AdbIdentity(keyPair.private, certificate)
        }
    }
}
