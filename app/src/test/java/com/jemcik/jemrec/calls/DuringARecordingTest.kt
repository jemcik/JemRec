package com.jemcik.jemrec.calls

import android.net.Uri
import com.jemcik.jemrec.calls.CallHistory.Kind
import com.jemcik.jemrec.calls.CallHistory.Logged
import com.jemcik.jemrec.calls.CallHistory.Status
import com.jemcik.jemrec.calls.CallLedger.Ledger
import com.jemcik.jemrec.calls.CallLedger.Recorded
import com.jemcik.jemrec.calls.CoverageHistory.State
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.RecordingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.Date

/**
 * A call that began while a recording ran is in that recording - call waiting
 * puts the second call in the first one's file. Asked of a recording on the
 * list (the Calls tab, CallHistory.classify) from just after its first
 * millisecond, and of the ledger (the missed-call check, and the Calls tab
 * reading the ledger) from its first millisecond on: two edges, kept as they
 * are. Both reach to the recording's end and SPAN_SLACK_MS past it.
 *
 * The call here went the other way from the recording, so the closest-call
 * rule (CallLogLookup.sameCall), which matches by direction, cannot claim it:
 * what holds it, or does not, is the rule for a call during a recording.
 */
class DuringARecordingTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val t = 1_795_000_000_000L
    private val seconds = 120
    private val end = t + seconds * 1000L + MissedCalls.SPAN_SLACK_MS
    private val now = end + MissedCalls.SETTLE_MS + 60 * 60 * 1000L

    private fun outgoing(at: Long) = Logged(at, 30, Kind.OUTGOING, "Someone", "+100")

    private fun onTheList(at: Long): Status {
        val recording = Recording(
            name = RecordingStore.nameFor(Date(t), true), uri = uri, whenLabel = "", incoming = true,
            durationLabel = "", sizeLabel = "", durationMillis = seconds * 1000L,
        )
        return CallHistory.classify(
            listOf(outgoing(at)), listOf(recording), emptyList(), Ledger(emptyList(), emptySet()),
            listOf(0L to State.AUTOMATIC), now,
        ).single().status
    }

    private fun inTheLedger(at: Long): Status = CallHistory.classify(
        listOf(outgoing(at)), emptyList(), emptyList(), Ledger(listOf(Recorded(t, true, seconds)), emptySet()),
        listOf(0L to State.AUTOMATIC), now,
    ).single().status

    private fun missed(at: Long): Boolean = MissedCalls.unrecorded(
        listOf(CallLogLookup.LoggedCall(at, 30, false, "Someone")),
        Ledger(listOf(Recorded(t, true, seconds)), emptySet()), emptyList(), coveredSince = 0L, now = now,
    ).isNotEmpty()

    @Test fun aRecordingOnTheListHoldsACallFromJustAfterItsStart() {
        assertEquals(Status.NOT_RECORDED, onTheList(t))
        assertEquals(Status.RECORDED, onTheList(t + 1))
        assertEquals(Status.RECORDED, onTheList(end))
        assertEquals(Status.NOT_RECORDED, onTheList(end + 1))
    }

    @Test fun aLedgerEntryHoldsACallFromItsStart() {
        assertEquals(Status.NOT_RECORDED, inTheLedger(t - 1))
        assertEquals(Status.DELETED, inTheLedger(t))
        assertEquals(Status.DELETED, inTheLedger(end))
        assertEquals(Status.NOT_RECORDED, inTheLedger(end + 1))

        assertTrue(missed(t - 1))
        assertTrue(!missed(t))
        assertTrue(!missed(end))
        assertTrue(missed(end + 1))
    }
}
