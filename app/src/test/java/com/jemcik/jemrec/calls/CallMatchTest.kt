package com.jemcik.jemrec.calls

import com.jemcik.jemrec.calls.CallLedger.Recorded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared rules for which call a recording is, written once (CallMatch). */
class CallMatchTest {

    private val t = 1_795_000_000_000L

    @Test fun theWindowIsThreeMinutesBackAndHalfAMinuteOn() {
        assertTrue(CallMatch.sameCall(t - CallMatch.BEFORE_MS, t))
        assertFalse(CallMatch.sameCall(t - CallMatch.BEFORE_MS - 1, t))
        assertTrue(CallMatch.sameCall(t + CallMatch.AFTER_MS, t))
        assertFalse(CallMatch.sameCall(t + CallMatch.AFTER_MS + 1, t))
    }

    @Test fun theClosestOfTheSameWayInsideTheWindow() {
        data class Call(val at: Long, val incoming: Boolean)
        val calls = listOf(Call(t - 60_000, true), Call(t - 10_000, false), Call(t - 20_000, true), Call(t + 40_000, true))
        assertEquals(Call(t - 20_000, true), CallMatch.closest(calls, t, true, { it.at }, { it.incoming }))
        assertEquals(Call(t - 10_000, false), CallMatch.closest(calls, t, false, { it.at }, { it.incoming }))
        assertNull(CallMatch.closest(calls.filter { it.at > t }, t, true, { it.at }, { it.incoming }))
    }

    @Test fun aLedgerEntryAccountsForItsOwnCallAndForOneDuringIt() {
        val r = Recorded(t, true, 60)
        assertTrue("its own, rung 20 s before", CallMatch.accounts(r, t - 20_000, true))
        assertFalse("the other way, outside its span", CallMatch.accounts(r, t - 20_000, false))
        assertTrue("during it, either way", CallMatch.accounts(r, t + 30_000, false))
        assertTrue(CallMatch.accounts(r, t + 60_000 + CallMatch.SPAN_SLACK_MS, false))
        assertFalse(CallMatch.accounts(r, t + 60_000 + CallMatch.SPAN_SLACK_MS + 1, false))
        // A pending one, length unknown, by its start only.
        assertFalse(CallMatch.accounts(Recorded(t, true, -1), t + 30_000, false))
    }

    @Test fun theRecordersFilesAsTheLedgerWouldHaveThem() {
        assertEquals(
            listOf(Recorded(t, true, -1), Recorded(t + 1, false, -1)),
            CallMatch.pendingRecorded(
                listOf(
                    "jemrec_rec_${t}_in.dat",
                    "jemrec_rec_${t + 1}_msg100_out.dat",
                    "jemrec_rec_${t + 2}_voip-com.whatsapp.dat",
                    "jemrec_rec_${t + 3}_in_voip-com.google.android.apps.tachyon.dat",
                    "not_a_recording.dat",
                ),
            ),
        )
    }
}
