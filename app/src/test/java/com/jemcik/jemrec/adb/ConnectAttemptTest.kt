package com.jemcik.jemrec.adb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

/**
 * A connect's failure as JemRec now says it: libadb's message-less wrapping
 * taken off (#140's "refused us - null"), and an attempt's one line.
 */
class ConnectAttemptTest {

    /** libadb wraps a socket it could not open as `new
     *  IOException().initCause(th)` (AdbConnection.java, 3.1.1). */
    @Test fun libadbsWrappedSocketFailureSaysItsCause() {
        val wrapped = IOException().initCause(ConnectException("failed to connect to /127.0.0.1 (port 5555): ECONNREFUSED"))
        assertEquals(
            "ConnectException: failed to connect to /127.0.0.1 (port 5555): ECONNREFUSED",
            AdbTransport.causeOf(wrapped),
        )
    }

    @Test fun aFailureWithWordsKeepsThem() {
        assertEquals(
            "SSLProtocolException: SSLV3_ALERT_CERTIFICATE_UNKNOWN",
            AdbTransport.causeOf(SSLProtocolException("SSLV3_ALERT_CERTIFICATE_UNKNOWN")),
        )
        assertEquals("IOException: no detail", AdbTransport.causeOf(IOException()))
    }

    @Test fun anAttemptInOneLine() {
        val probe = LoopbackProbe.Result(null, 28_233, 2, 0, 1_219)
        val found = AdbdFinder.Found(null, null, listOf(AdbdFinder.Candidate(36_467, AdbdCheck.Answer.CLOSED)), probe)
        val attempt = AdbTransport.Attempt(0, AdbTransport.ConnectOutcome.NOT_FOUND, found, 0, null, "ConnectException: ECONNREFUSED")
        assertEquals(
            "NOT_FOUND; mDNS gave 36467 (closed); probe: 28233 ports in 1219 ms, adbd not among them, " +
                "2 other listeners; port 5555: ConnectException: ECONNREFUSED",
            attempt.line(),
        )
    }

    @Test fun aRefusalNamesAdbdsPortAndWhy() {
        val found = AdbdFinder.Found(33_469, AdbdFinder.By.PROBE, emptyList(), LoopbackProbe.Result(33_469, 400, 0, 0, 300))
        val attempt = AdbTransport.Attempt(
            0, AdbTransport.ConnectOutcome.REFUSED, found, 33_469,
            "AdbPairingRequiredException: ADB pairing is required.", null,
        )
        assertEquals(
            "REFUSED on 33469; mDNS gave no port; found by the probe on 33469 after 400 ports in 300 ms; " +
                "adbd: AdbPairingRequiredException: ADB pairing is required.",
            attempt.line(),
        )
    }

    /** A connect adbd turned away by its name clash, made again (the Android
     *  13 AVD under load): the line says how many it took. */
    @Test fun aConnectMadeAgainSaysHowManyTries() {
        val found = AdbdFinder.Found(39_145, AdbdFinder.By.MDNS, listOf(AdbdFinder.Candidate(39_145, AdbdCheck.Answer.ADBD)))
        val attempt = AdbTransport.Attempt(
            0, AdbTransport.ConnectOutcome.CONNECTED, found, 39_145, "IOException: Connection failed", null, tries = 2,
        )
        assertEquals(
            "CONNECTED on 39145 (2 tries); mDNS gave 39145 (adbd); adbd: IOException: Connection failed",
            attempt.line(),
        )
    }
}
