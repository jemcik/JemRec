package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which call-log entry a recording is labelled with. The window is three
 * minutes back and thirty seconds forward of the recording's start, and only
 * entries in the same direction count.
 */
class CallLogMatchTest {

    private val t = 1_757_512_433_000L
    private val second = 1_000L

    private fun entry(at: Long, incoming: Boolean, label: String) =
        CallLogLookup.Entry(at, incoming, label, number = null)

    @Test fun picksTheClosestEntryInTheSameDirection() {
        val entries = listOf(
            entry(t - 60 * second, incoming = true, "an earlier incoming"),
            entry(t - 10 * second, incoming = true, "the incoming that rang just before"),
            entry(t - 5 * second, incoming = false, "an outgoing even closer"),
        )
        assertEquals("the incoming that rang just before", CallLogLookup.closest(entries, t, incoming = true)?.label)
        assertEquals("an outgoing even closer", CallLogLookup.closest(entries, t, incoming = false)?.label)
    }

    @Test fun theOtherDirectionNeverMatches() {
        val outgoingOnly = listOf(entry(t, incoming = false, "outgoing"))
        assertNull(CallLogLookup.closest(outgoingOnly, t, incoming = true))
    }

    @Test fun theWindowIsThreeMinutesBackAndThirtySecondsForward() {
        fun matches(offsetSeconds: Long) =
            CallLogLookup.closest(listOf(entry(t + offsetSeconds * second, true, "x")), t, incoming = true) != null
        assertEquals(true, matches(-179))
        assertEquals(false, matches(-181))
        assertEquals(true, matches(29))
        assertEquals(false, matches(31))
    }

    @Test fun aNameWithTheNumberIsMatchedInsideThatCall() {
        // Honor's format: the number, and a time anywhere in the call.
        val call = CallLogLookup.Entry(t, incoming = false, "477", number = "477", seconds = 41)
        val other = CallLogLookup.Entry(t + 20 * second, incoming = true, "someone else", number = "+100", seconds = 60)
        val entries = listOf(call, other)
        assertEquals(call, CallLogLookup.withNumber(entries, "477", t + 30 * second))
        assertNull(CallLogLookup.withNumber(entries, "478", t + 30 * second))
        assertNull(CallLogLookup.withNumber(entries, "477", t + 10 * 60 * second))
    }

    @Test fun theCallBeforeIsNeverTakenForOneTheLogHasNotWrittenYet() {
        // Mum at t - 90 s was recorded (the ledger has it). At t the next call
        // is saved before its own entry reaches the log: the only entry in the
        // window is Mum's, and it is hers, not this call's.
        val mum = CallLogLookup.Entry(t - 90 * second, incoming = false, "Mum", number = "+1", seconds = 60)
        val earlier = listOf(CallLedger.Recorded(t - 89 * second, incoming = false, seconds = 61))
        assertNull(CallLogLookup.unclaimed(listOf(mum), t, incoming = false, earlier))
        // Once the log has it, this call's entry is found.
        val boss = CallLogLookup.Entry(t, incoming = false, "Boss", number = "+2", seconds = 30)
        assertEquals(boss, CallLogLookup.unclaimed(listOf(mum, boss), t, incoming = false, earlier))
        // And without a ledger entry for Mum, the old answer stands.
        assertEquals(mum, CallLogLookup.unclaimed(listOf(mum), t, incoming = false, emptyList()))
    }

    @Test fun nothingInTheWindowMeansNoLabel() {
        assertNull(CallLogLookup.closest(emptyList(), t, incoming = true))
    }

    @Test fun aNumberWrittenTwoWaysIsTheSameNumber() {
        // Telecom's handle, the call log's number, and a person's writing of it
        // (issue #30's example).
        assertTrue(CallLogLookup.sameNumber("+13034997111", "3034997111"))
        assertTrue(CallLogLookup.sameNumber("(303) 499-7111", "+1 303 499 7111"))
        assertFalse(CallLogLookup.sameNumber("+13034997111", "+13034997112"))
        // Short codes compare whole.
        assertTrue(CallLogLookup.sameNumber("477", "477"))
        assertFalse(CallLogLookup.sameNumber("477", "1477"))
        // Nothing to compare is no match.
        assertFalse(CallLogLookup.sameNumber(null, "477"))
        assertFalse(CallLogLookup.sameNumber("", ""))
    }

    @Test fun onlyThePhonesOwnCallsArePhoneCalls() {
        // As the Honor's call log has them, 2026-10-26.
        assertTrue(CallLogLookup.isPhoneCall("com.android.phone/com.android.services.telephony.TelephonyConnectionService"))
        assertFalse(
            "Google Meet's calls are Meet's",
            CallLogLookup.isPhoneCall(
                "com.google.android.apps.tachyon/com.google.android.apps.tachyon.telecom.TachyonTelecomConnectionService",
            ),
        )
        // A row from before Android kept the account: a phone call's, as all were.
        assertTrue(CallLogLookup.isPhoneCall(null))
        assertTrue(CallLogLookup.isPhoneCall(""))
        assertFalse("not a prefix match", CallLogLookup.isPhoneCall("com.android.phonex/x"))
    }
}
