package com.jemcik.jemrec.capture

import android.net.Uri
import android.provider.CallLog
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Logged
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.capture.CoverageHistory.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import java.util.Date

/** What the Calls tab says about each call, and what it must not claim. */
class CallHistoryTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val t = 1_795_000_000_000L
    private val minute = 60_000L

    private fun rec(at: Long, incoming: Boolean = true, seconds: Long = 120) = Recording(
        name = RecordingStore.nameFor(Date(at), incoming), uri = uri, whenLabel = "", incoming = incoming,
        durationLabel = "", sizeLabel = "", durationMillis = seconds * 1000,
    )

    private fun call(at: Long, kind: Kind = Kind.INCOMING, seconds: Int = 120) =
        Logged(at, seconds, kind, "Someone", "+100")

    private fun classify(
        calls: List<Logged>,
        recordings: List<Recording> = emptyList(),
        trashed: List<Recording> = emptyList(),
        ledger: List<CallLedger.Recorded> = emptyList(),
        history: List<Pair<Long, State>> = listOf(0L to State.AUTOMATIC),
        skipped: List<CallLedger.Recorded> = emptyList(),
        now: Long = t + minute,
    ) = CallHistory.classify(calls, recordings, trashed, CallLedger.Ledger(ledger, emptySet(), skipped), history, now)

    @Test fun aRecordedCallPlays() {
        // Rang 20 s before it was answered and the recording began.
        val recording = rec(t + 20_000)
        val result = classify(listOf(call(t)), recordings = listOf(recording)).single()
        assertEquals(Status.RECORDED, result.status)
        assertEquals(recording, result.recording)
    }

    @Test fun aMissedCallJustBeforeNeverStealsTheAnsweredOnesRecording() {
        val missed = call(t, Kind.MISSED, seconds = 0)
        val answered = call(t + minute)
        val recording = rec(t + minute + 10_000)
        val result = classify(listOf(missed, answered), recordings = listOf(recording))
        assertEquals(Status.RECORDED, result.single { it.kind == Kind.INCOMING }.status)
        val m = result.single { it.kind == Kind.MISSED }
        assertEquals(Status.NOT_CONNECTED, m.status)
        assertNull(m.recording)
    }

    @Test fun twoAnsweredCallsAMinuteApartEachKeepTheirOwn() {
        val first = call(t)
        val second = call(t + minute)
        val r1 = rec(t + 5_000)
        val r2 = rec(t + minute + 5_000)
        val result = classify(listOf(first, second), recordings = listOf(r1, r2))
        assertEquals(r1, result.single { it.at == t }.recording)
        assertEquals(r2, result.single { it.at == t + minute }.recording)
    }

    @Test fun anOutgoingCallNobodyAnsweredIsNotARecordingQuestion() {
        assertEquals(Status.NOT_CONNECTED, classify(listOf(call(t, Kind.OUTGOING, seconds = 0))).single().status)
    }

    @Test fun aCallInTheTrashSaysSo() {
        assertEquals(Status.IN_TRASH, classify(listOf(call(t)), trashed = listOf(rec(t + 5_000))).single().status)
    }

    @Test fun aRecordingDeletedForGoodIsStillKnownForAWeek() {
        val ledger = listOf(CallLedger.Recorded(t + 5_000, true, 115))
        assertEquals(Status.DELETED, classify(listOf(call(t)), ledger = ledger).single().status)
    }

    @Test fun aCallTheRulesLeftOutSaysSo() {
        val skipped = listOf(CallLedger.Recorded(t + 5_000, true, 115))
        assertEquals(Status.SKIPPED, classify(listOf(call(t)), skipped = skipped).single().status)
    }

    @Test fun pastTheLedgersWeekAMissingRecordingIsNotCalledMissed() {
        // Deleted or left out more than a week ago looks exactly like missed:
        // the ledger that told them apart has let it go.
        val now = t + CallLedger.KEEP_MS + minute
        assertEquals(Status.NO_RECORDING, classify(listOf(call(t)), now = now).single().status)
        assertEquals(Status.NOT_RECORDED, classify(listOf(call(now - minute)), now = now).single().status)
    }

    @Test fun aCallTooShortToCountSaysThat() {
        assertEquals(
            Status.TOO_SHORT,
            classify(listOf(call(t, seconds = MissedCalls.MIN_SECONDS - 1))).single().status,
        )
    }

    @Test fun theSwitchAtTheTimeExplainsTheRest() {
        val history = listOf(t - 3 * minute to State.AUTOMATIC, t - 2 * minute to State.OFF, t - minute to State.ASK_FIRST)
        fun at(time: Long) = classify(listOf(call(time)), history = history).single().status
        assertEquals(Status.NOT_RECORDED, at(t - 150_000))
        assertEquals(Status.RECORDING_OFF, at(t - 90_000))
        assertEquals(Status.ASK_FIRST, at(t))
    }

    @Test fun beforeTheHistoryBeganNothingIsGuessed() {
        val history = listOf(t to State.AUTOMATIC)
        assertEquals(Status.NO_RECORDING, classify(listOf(call(t - minute)), history = history).single().status)
    }

    @Test fun aCallThatCameInDuringARecordingIsInIt() {
        // Call waiting: an incoming call 30 s into an outgoing recording.
        val outgoing = rec(t, incoming = false, seconds = 300)
        val waiting = call(t + 30_000, Kind.INCOMING)
        val result = classify(listOf(waiting), recordings = listOf(outgoing)).single()
        assertEquals(Status.RECORDED, result.status)
        assertEquals(outgoing, result.recording)
    }

    @Test fun theCallLogsTypesMapToKinds() {
        assertEquals(Kind.INCOMING, CallHistory.kindOf(CallLog.Calls.INCOMING_TYPE))
        assertEquals(Kind.OUTGOING, CallHistory.kindOf(CallLog.Calls.OUTGOING_TYPE))
        assertEquals(Kind.MISSED, CallHistory.kindOf(CallLog.Calls.MISSED_TYPE))
        assertEquals(Kind.DECLINED, CallHistory.kindOf(CallLog.Calls.REJECTED_TYPE))
        assertEquals(Kind.BLOCKED, CallHistory.kindOf(CallLog.Calls.BLOCKED_TYPE))
        assertEquals(Kind.OTHER, CallHistory.kindOf(CallLog.Calls.ANSWERED_EXTERNALLY_TYPE))
    }
}

/** The record of how calls were being recorded, read back. */
class CoverageHistoryTest {

    @Test fun theStateInForceIsTheLatestAtOrBefore() {
        val history = listOf(10L to State.AUTOMATIC, 20L to State.OFF, 30L to State.ASK_FIRST)
        assertNull(CoverageHistory.stateAt(history, 9))
        assertEquals(State.AUTOMATIC, CoverageHistory.stateAt(history, 10))
        assertEquals(State.AUTOMATIC, CoverageHistory.stateAt(history, 19))
        assertEquals(State.OFF, CoverageHistory.stateAt(history, 25))
        assertEquals(State.ASK_FIRST, CoverageHistory.stateAt(history, 99))
    }

    @Test fun storedEntriesParseInOrderAndRubbishIsDropped() {
        val parsed = CoverageHistory.parse(setOf("30:OFF", "10:AUTOMATIC", "x:OFF", "20:NOPE", "20:ASK_FIRST"))
        assertEquals(listOf(10L to State.AUTOMATIC, 20L to State.ASK_FIRST, 30L to State.OFF), parsed)
    }
}
