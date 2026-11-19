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

    /** Part 1 of a call, saved after its own joined part 55 s in: the call's
     *  entry is part 1's - the joined part's start is no nearer to it - not
     *  claimed by it, part 1 left unnamed and unruled (review round 11). A
     *  recording nearer an entry still claims it: the call before. */
    @Test fun aLaterPartOfTheSameCallDoesNotClaimItsEntry() {
        val call = CallLogLookup.Entry(t, incoming = false, "477", number = "477", seconds = 120)
        // As 1.2 saves it: with its call's start (review round 15: without
        // it, the test held a part 1.2 never saves).
        val joined = Recorded(t + 55_000, false, 65, callAt = t)
        assertEquals(call, CallMatch.unclaimed(listOf(call), t + 300, incoming = false, listOf(joined)))
        val before = Recorded(t - 2_000, false, 10)
        assertNull(CallMatch.unclaimed(listOf(call), t + 60_000, incoming = false, listOf(before)))
    }

    /** A logged call, for [accounted]. */
    private data class Call(val at: Long, val incoming: Boolean, val seconds: Int)

    private fun accounted(entries: List<Recorded>, vararg calls: Call): Set<Call> =
        CallMatch.accounted(entries, calls.toList(), { it.at }, { it.seconds }, { it.incoming })

    /** A waiting call ringing 15 s after the answer of a call that rang
     *  25 s is nearer that call's entry than its own date - its ring start -
     *  but dated after the recording began: not its call (AOSP
     *  CallLogManager dates a call by its creation). It took the entry, and
     *  the call answered was "not recorded" (review round 13). */
    @Test fun aCallDatedAfterARecordingBeganIsTakenOnlyWithNoneBefore() {
        val c = Call(t, true, 60)
        val w = Call(t + 40_000, true, 30)
        assertEquals(c, CallMatch.closest(listOf(c, w), t + 25_000, true, { it.at }, { it.incoming }))
        assertEquals(setOf(c, w), accounted(listOf(Recorded(t + 25_000, true, 60), Recorded(t + 50_000, true, 30)), c, w))
        assertEquals("none before it: the later one", w, CallMatch.closest(listOf(w), t + 25_000, true, { it.at }, { it.incoming }))
    }

    /** A part saved from a recorder joined mid-call also holds the other
     *  call up at its start - call waiting's, whose audio after the swap is
     *  in it; a joined file waiting on the recorder picks no call by its own
     *  start and guessed way (review round 13). */
    @Test fun aJoinedPartHoldsTheOtherCallUpAtItsStart() {
        val held = Call(t, true, 400)
        val waiting = Call(t + 100_000, false, 60)
        val part = Recorded(t + 120_000, false, 100, callAt = t + 100_000)
        assertEquals(setOf(waiting, held), accounted(listOf(part), held, waiting))
        val before = Call(t - 90_000, false, 20)
        val pending = Recorded(t, false, -1, joined = true)
        assertEquals(emptySet<Call>(), accounted(listOf(pending), before))
    }

    /** A saved joined part claims a log entry from its call's start, as it
     *  accounts for it - from its own, a call created soon after the revive
     *  was claimed, its recording saved unnamed (review round 14). */
    @Test fun aJoinedPartClaimsFromItsCallsStart() {
        val joinedCall = CallLogLookup.Entry(t, incoming = true, "1", number = "1", seconds = 400)
        val next = CallLogLookup.Entry(t + 310_000, incoming = true, "2", number = "2", seconds = 30)
        val part = Recorded(t + 300_000, true, 100, callAt = t)
        assertEquals(next, CallMatch.unclaimed(listOf(joinedCall, next), t + 330_000, incoming = true, listOf(part)))
    }

    /** A part joined 5 minutes into a call - the call's only recording -
     *  accounts for it by the call's start the saver found, the same way;
     *  not for another call (review rounds 11 and 12). */
    @Test fun aJoinedPartAccountsForItsCallByTheCallsStart() {
        // Its way decided by the saver from the call: incoming, as the call.
        val joined = Recorded(t + 5 * 60_000L, true, 120, callAt = t)
        val call = Call(t, true, 420)
        assertEquals(setOf(call), accounted(listOf(joined), call, Call(t + 8 * 60_000L, true, 60)))
        assertEquals("the other way, over before it began", emptySet<Call>(), accounted(listOf(joined), Call(t, false, 200)))
    }

    /** A redial's recording is its own call's, not the call before's too -
     *  two minutes before, inside the window; nor, begun 20 s after the
     *  call before's logged end - the log's length leaves out its ringing -
     *  is it that call's by its span: it gave the call before its status and
     *  silenced its missed-call alarm (review round 12). */
    @Test fun aRedialsRecordingIsNotTheCallBefores() {
        val before = Call(t, false, 120)
        val redial = Call(t + 130_000L, false, 60)
        assertEquals(setOf(redial), accounted(listOf(Recorded(t + 140_000L, false, 60)), before, redial))
        val long = Call(t, false, 600)
        val after = Call(t + 610_000L, false, 60)
        assertEquals(setOf(after), accounted(listOf(Recorded(t + 620_000L, false, 60)), long, after))
    }

    /** A file joined mid-call still on the recorder: its way a guess, its
     *  call not decided - counted for a call it began during, either way,
     *  while it waits; not by its start alone (review round 12: its call
     *  was "not recorded" while its only recording waited). */
    @Test fun aPendingJoinedFileCountsForTheCallItBeganIn() {
        val pending = CallMatch.pendingRecorded(listOf("jemrec_rec_${t}_in.dat", "jemrec_rec_${t + 5 * 60_000L}_joined_out.dat"))
        assertEquals(listOf(Recorded(t, true, -1), Recorded(t + 5 * 60_000L, false, -1, joined = true)), pending)
        val joined = pending[1]
        val call = Call(t, true, 420)
        assertEquals(setOf(call), accounted(listOf(joined), call, Call(t + 6 * 60_000L, false, 60)))
    }

    /** The ledger keeps a joined part's call start, and reads its older
     *  entries as before. */
    @Test fun theLedgerKeepsAJoinedPartsCallStart() {
        val joined = Recorded(t + 5 * 60_000L, true, 120, callAt = t)
        assertEquals("r:${t + 300_000}:in:120:$t", CallLedger.encodeRecording(joined))
        assertEquals(
            listOf(joined, Recorded(t, false, 30)),
            CallLedger.parse(setOf(CallLedger.encodeRecording(joined), "r:$t:out:30")).recordings.sortedByDescending { it.startMillis },
        )
    }

    @Test fun aLedgerEntryAccountsForItsOwnCallAndForOneDuringIt() {
        val r = Recorded(t, true, 60)
        val own = Call(t - 20_000, true, 60)
        assertEquals("its own, rung 20 s before", setOf(own), accounted(listOf(r), own))
        assertEquals("the other way, outside its span", emptySet<Call>(), accounted(listOf(r), Call(t - 20_000, false, 60)))
        val during = Call(t + 30_000, false, 20)
        assertEquals("during it, either way", setOf(own, during), accounted(listOf(r), own, during))
        val atTheEdge = Call(t + 60_000 + CallMatch.SPAN_SLACK_MS, false, 20)
        assertEquals(setOf(atTheEdge), accounted(listOf(r), atTheEdge))
        assertEquals(emptySet<Call>(), accounted(listOf(r), Call(t + 60_000 + CallMatch.SPAN_SLACK_MS + 1, false, 20)))
        // A pending one, length unknown, by its start only.
        assertEquals(emptySet<Call>(), accounted(listOf(Recorded(t, true, -1)), Call(t + 30_000, false, 20)))
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
