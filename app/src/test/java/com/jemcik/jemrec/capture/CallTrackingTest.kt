package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallTracking.Tracked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telecom's account of a call held against a recording: which calls it holds,
 *  and which of its 20 ms packets belong to which. */
class CallTrackingTest {

    private val t = 1_795_000_000_000L
    private val s = 1_000L

    private fun call(id: Long, incoming: Boolean, vararg activations: Long, ended: Long = 0L) =
        Tracked(id, incoming, "+100", activations.toList(), ended)

    @Test fun anOutgoingCallStartsWhereItWasAnswered() {
        // Dialled at t, off-hook (recording) at t + 60 ms, answered 3.6 s on.
        val dialled = call(t, incoming = false, t + 3_660, ended = t + 17 * s)
        val calls = CallTracking.callsIn(listOf(dialled), t + 60, incoming = false)
        assertEquals(listOf(dialled), calls)
        val segments = CallTracking.segments(t + 60, calls)
        assertEquals(1, segments.size)
        // 3.6 s of ringing = 180 packets, not saved; nor anything after the
        // end, 17 s from dialling.
        assertEquals(180L until 847L, segments.single().second)
    }

    @Test fun nobodyAnsweredMeansNothingToSave() {
        val unanswered = call(t, incoming = false, ended = t + 30 * s)
        val calls = CallTracking.callsIn(listOf(unanswered), t + 50, incoming = false)
        assertTrue(CallTracking.segments(t + 50, calls).isEmpty())
    }

    @Test fun anIncomingCallIsKeptFromTheStart() {
        // Rang from t, answered (off-hook, recording) at t + 8 s.
        val rang = call(t, incoming = true, t + 8 * s, ended = t + 60 * s)
        val calls = CallTracking.callsIn(listOf(rang), t + 8 * s + 40, incoming = true)
        assertEquals(0L, CallTracking.segments(t + 8 * s + 40, calls).single().second.first)
    }

    @Test fun callWaitingSplitsTheRecordingPerCall() {
        val start = t + 2 * s
        val first = call(t, incoming = true, t + 2 * s, t + 50 * s, ended = t + 70 * s)
        // Came in at 20 s, answered at 30 s (first on hold), first back at 50 s.
        val second = call(t + 20 * s, incoming = true, t + 30 * s, ended = t + 50 * s)
        val calls = CallTracking.callsIn(listOf(first, second), start, incoming = true)
        assertEquals(listOf(first, second), calls)
        val segments = CallTracking.segments(start, calls)
        assertEquals(3, segments.size)
        assertEquals(first to (0L until 1400L), segments[0])
        assertEquals(second to (1400L until 2400L), segments[1])
        // Back to the first until it ended, at 70 s.
        assertEquals(first to (2400L until 3400L), segments[2])
    }

    @Test fun aCallWithNoAnswerAndNoEndIsNotTakenAsUnanswered() {
        // Telecom's account stopped short: the recording is saved as before.
        val unknown = call(t, incoming = false)
        assertTrue(CallTracking.callsIn(listOf(unknown), t + 50, incoming = false).isEmpty())
        // Still ringing as far as the record goes, but ended: not answered.
        val ended = call(t, incoming = false, ended = t + 20 * s)
        assertEquals(listOf(ended), CallTracking.callsIn(listOf(ended), t + 50, incoming = false))
    }

    @Test fun anEndBeforeTheAnswerCutsNothing() {
        // A clock that jumped back mid-call: keep the call rather than lose it.
        val odd = call(t, incoming = true, t + 5 * s, ended = t + 2 * s)
        // Not taken as the recording's call at all - saved whole, as before...
        assertTrue(CallTracking.callsIn(listOf(odd), t + 5 * s, incoming = true).isEmpty())
        // ...and the cut itself never cuts a stretch away to nothing.
        val segments = CallTracking.segments(t + 5 * s, listOf(odd))
        assertEquals(0L, segments.single().second.first)
        assertEquals(Long.MAX_VALUE, segments.single().second.last + 1)
    }

    @Test fun theRecordingsOwnCallComesFirst() {
        // Two rang together: B, the later one, was answered first and started
        // the recording; A was taken during it.
        val a = call(t, incoming = true, t + 30 * s, ended = t + 60 * s)
        val b = call(t + 5 * s, incoming = true, t + 10 * s, ended = t + 70 * s)
        assertEquals(listOf(b, a), CallTracking.callsIn(listOf(a, b), t + 10 * s, incoming = true))
    }

    @Test fun anIncomingCallTheDaemonCalledOutgoingIsStillFound() {
        // The call before - outgoing, never answered - ended at 9.8 s; the
        // next one rang inside the daemon's end check, which then forgot the
        // ring: its recording (answered at 14 s) came out labelled outgoing.
        val before = call(t + 7_800, incoming = false, ended = t + 9_800)
        val rang = call(t + 11 * s, incoming = true, t + 13_950, ended = t + 20 * s)
        val start = t + 13_970
        assertEquals(listOf(rang), CallTracking.callsIn(listOf(before, rang), start, incoming = false))
    }

    @Test fun aCallThatEndedBeforeTheRecordingIsNotItsOwn() {
        // Ended a second before this recording began: the call before, not
        // this one - which would otherwise be deleted as never answered.
        val before = call(t, incoming = false, ended = t + 9 * s)
        assertTrue(CallTracking.callsIn(listOf(before), t + 10 * s, incoming = false).isEmpty())
    }

    @Test fun aRedialAfterACallNobodyAnsweredIsInTheSameFile() {
        // Rang out unanswered for 10 s; redialled a second later - inside the
        // daemon's two-second end check, so the same file - and answered.
        val first = call(t, incoming = false, ended = t + 10 * s)
        val redial = call(t + 11 * s, incoming = false, t + 15 * s, ended = t + 30 * s)
        val calls = CallTracking.callsIn(listOf(first, redial), t + 60, incoming = false)
        assertEquals(listOf(first, redial), calls)
        // The redial's conversation is kept, from its answer to its end.
        assertEquals(listOf(redial to ((15 * s - 60) / 20 until (30 * s - 60) / 20)), CallTracking.segments(t + 60, calls))
    }

    @Test fun aWaitingCallAnsweredAfterTheFirstEndedIsInTheSameFile() {
        // B rang during A; A hung up, B went on ringing - never idle - and
        // was answered after A ended.
        val a = call(t, incoming = true, t + 2 * s, ended = t + 20 * s)
        val b = call(t + 10 * s, incoming = true, t + 25 * s, ended = t + 40 * s)
        val start = t + 2 * s
        val calls = CallTracking.callsIn(listOf(a, b), start, incoming = true)
        assertEquals(listOf(a, b), calls)
        val segments = CallTracking.segments(start, calls)
        assertEquals(a to (0L until 900L), segments[0])
        assertEquals(b to (1150L until 1900L), segments[1])
    }

    @Test fun aCallStartedSecondsAfterTheEndIsNotJoined() {
        // Three seconds after: past the end check, so a file of its own.
        val a = call(t, incoming = false, t + 2 * s, ended = t + 20 * s)
        val later = call(t + 23 * s, incoming = false, t + 26 * s, ended = t + 40 * s)
        assertEquals(listOf(a), CallTracking.callsIn(listOf(a, later), t + 60, incoming = false))
    }

    @Test fun theOwnCallIsTheOneThatWentOffHookAtTheStart() {
        // A answered at 10 s, starting the recording; B rang in a second
        // later, was never answered. B is newer, but A is the recording's.
        val a = call(t, incoming = true, t + 10 * s, ended = t + 60 * s)
        val b = call(t + 11 * s, incoming = true, ended = t + 15 * s)
        val calls = CallTracking.callsIn(listOf(a, b), t + 10 * s + 40, incoming = true)
        assertEquals(listOf(a), calls)
    }

    @Test fun anotherCallLongBeforeIsNotThisOne() {
        val earlier = call(t - 10 * 60 * s, incoming = false, t - 9 * 60 * s, ended = t - 8 * 60 * s)
        assertTrue(CallTracking.callsIn(listOf(earlier), t, incoming = false).isEmpty())
        // Nor one the other way round that went off-hook seconds away.
        val other = call(t, incoming = true, t + 5 * s, ended = t + 30 * s)
        assertTrue(CallTracking.callsIn(listOf(other), t + 50, incoming = false).isEmpty())
    }

    @Test fun theRecordReadsBack() {
        listOf(
            call(t, incoming = true, t + s, t + 5 * s, ended = t + 9 * s),
            Tracked(t, false, null, emptyList(), 0L),
            // A dial string can hold pauses and waits.
            Tracked(t, false, "+100,,22;3", listOf(t + s), t + 9 * s),
            Tracked(t, false, "+100", listOf(t + s), t + 9 * s, clockOffset = t - 4_241 * s),
        ).forEach { assertEquals(it, CallTracking.decode(CallTracking.encode(it))) }
        assertNull(CallTracking.decode("rubbish"))
    }

    @Test fun aRecordKeptBeforeTheClockOffsetReadsBackWithout() {
        // As 0.19.2 and before wrote it: five fields.
        assertEquals(
            call(t, incoming = false, t + s, ended = t + 9 * s),
            CallTracking.decode("$t|out|+100|${t + s}|${t + 9 * s}"),
        )
    }

    @Test fun secondsAreFromTheAnswer() {
        assertEquals(14, call(t, false, t + 3 * s, ended = t + 17 * s).seconds)
        assertEquals(0, call(t, false, ended = t + 17 * s).seconds)
    }
}
