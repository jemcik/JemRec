package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the reply field's text becomes before it is sent to adbd as a pairing code. */
class PairingCodeTest {

    @Test fun sixDigitsPassThroughWhateverSeparatesThem() {
        assertEquals("123456", pairingCodeFrom("123456"))
        assertEquals("123456", pairingCodeFrom("123 456"))
        assertEquals("123456", pairingCodeFrom("12-34-56"))
        assertEquals("123456", pairingCodeFrom(" 123456\n"))
        assertEquals("123456", pairingCodeFrom("code: 123456"))
    }

    /** The service stopped at Android's cap: its own "timed out" words
     *  stand, not "Something went wrong: Job was cancelled" over them. */
    @Test fun aCancelledPairingSaysNothingOverTheTimeout() {
        assertNull(failureText(kotlinx.coroutines.CancellationException("Job was cancelled")))
        assertNull(failureText(kotlin.coroutines.cancellation.CancellationException("cut")))
        assertEquals("Something went wrong: refused", failureText(java.io.IOException("refused")))
    }

    /** Out of time while pairing, connecting, or in setup's last step
     *  after the connect: each its own words. */
    @Test fun runningOutOfTimeSaysWhereItWas() {
        assertEquals(
            "Pairing did not finish in time. Reopen the dialog for a fresh code and try again.",
            timedOut(PairingService.Stage.PAIRING),
        )
        assertEquals(
            "Paired! I could not connect in time. Reopen JemRec to finish - it " +
                "will say which switch to turn on. No need to pair again.",
            timedOut(PairingService.Stage.CONNECTING),
        )
        assertEquals(
            "Paired, but setup did not finish in time. Reopen JemRec - it will finish " +
                "on its own. No need to pair again.",
            timedOut(PairingService.Stage.FINISHING),
        )
    }

    @Test fun anythingButSixDigitsIsNotACode() {
        assertNull(pairingCodeFrom("12345"))
        assertNull(pairingCodeFrom("1234567"))
        assertNull(pairingCodeFrom(""))
        assertNull(pairingCodeFrom("abcdef"))
        assertNull(pairingCodeFrom(null))
    }
}
