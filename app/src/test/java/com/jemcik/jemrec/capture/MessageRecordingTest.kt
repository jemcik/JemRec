package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message from the recorder's file to the saved recording and the Calls tab. */
class MessageRecordingTest {

    @Test fun theRecordersNameSaysWhereTheMessageStarts() {
        assertEquals(8_120L, RecordingStore.messageStartMs("jemrec_rec_1792661198533_msg8120_in.dat"))
        assertEquals(RecordingStore.MESSAGE_NONE, RecordingStore.messageStartMs("jemrec_rec_1792661198533_msgnone_in.dat"))
        assertEquals(RecordingStore.MESSAGE_FAILED, RecordingStore.messageStartMs("jemrec_rec_1792661198533_msgfail_in.dat"))
        assertNull(RecordingStore.messageStartMs("jemrec_rec_1792661198533_in.dat"))
        assertNull(RecordingStore.messageStartMs("jemrec_rec_1792661198533_voip-viber.dat"))
        // A call the user took over: trimmed from where they took it, and not
        // a message.
        assertEquals(7_400L, RecordingStore.takenStartMs("jemrec_rec_1792661198533_from7400_in.dat"))
        assertNull(RecordingStore.messageStartMs("jemrec_rec_1792661198533_from7400_in.dat"))
        assertNull(RecordingStore.takenStartMs("jemrec_rec_1792661198533_msg8120_in.dat"))
        assertTrue(RecordingStore.isIncoming("jemrec_rec_1792661198533_from7400_in.dat"))
        assertEquals(1_792_661_198_533L, RecordingSaver.startMillis("jemrec_rec_1792661198533_from7400_in.dat"))
    }

    @Test fun aMessageNameStillReadsAsAnIncomingCallAtItsTime() {
        val name = "jemrec_rec_1792661198533_msg8120_in.dat"
        assertTrue(RecordingStore.isIncoming(name))
        assertEquals(1_792_661_198_533L, RecordingSaver.startMillis(name))
    }

    @Test fun aPartFromTheMessageStartSkipsTheGreeting() {
        val whole = RecordingSaver.Part.whole("a.ogg", true, 0L)
        val from = whole.from(8_120 / CallTracking.PACKET_MS)
        assertTrue(!from.holds(405) && from.holds(406) && from.holds(10_000))
        // Call waiting's split parts are trimmed too, and one wholly before
        // the start is left with nothing - so nothing of it is written.
        val split = RecordingSaver.Part("b.ogg", true, 0L, listOf(0L..99L, 300L..499L))
        assertEquals(listOf(406L..499L), split.from(406).packets)
        assertEquals(emptyList<LongRange>(), RecordingSaver.Part("c.ogg", true, 0L, listOf(0L..99L)).from(406).packets)
    }

    @Test fun theLedgerKeepsGreetingHangUps() {
        val ledger = CallLedger.parse(
            setOf(
                CallLedger.encodeRecording(CallLedger.Recorded(1_000L, true, 0), kind = "g"),
                CallLedger.encodeRecording(CallLedger.Recorded(2_000L, true, 30)),
            ),
        )
        assertEquals(listOf(CallLedger.Recorded(1_000L, true, 0)), ledger.greetingHangUps)
        assertEquals(listOf(CallLedger.Recorded(2_000L, true, 30)), ledger.recordings)
    }

    @Test fun theCallsTabSaysTheCallerHungUpDuringTheGreeting() {
        val t = 1_792_661_198_000L
        val call = CallHistory.Logged(t, 9, CallHistory.Kind.INCOMING, "Mum", "+380")
        val calls = CallHistory.classify(
            logged = listOf(call),
            recordings = emptyList(),
            trashed = emptyList(),
            ledger = CallLedger.Ledger(emptyList(), emptySet(), greetingHangUps = listOf(CallLedger.Recorded(t + 500, true, 0))),
            history = emptyList(),
            now = t + 60_000,
        )
        assertEquals(CallHistory.Status.GREETING_HANGUP, calls.single().status)
    }

    @Test fun theMissedCallCheckDoesNotReportIt() {
        val t = 1_792_661_198_000L
        val logged = CallLogLookup.LoggedCall(t, 9, true, "Mum")
        val missed = MissedCalls.unrecorded(
            listOf(logged),
            CallLedger.Ledger(emptyList(), emptySet(), greetingHangUps = listOf(CallLedger.Recorded(t + 500, true, 0))),
            emptyList(),
            t - 60 * 60 * 1000L,
            t + 60 * 60 * 1000L,
        )
        assertEquals(emptyList<CallLogLookup.LoggedCall>(), missed)
    }

    @Test fun aGreetingThatCouldNotPlayIsSaidSoNotBlamedOnTheCaller() {
        val t = 1_792_661_198_000L
        val ledger = CallLedger.parse(
            setOf(CallLedger.encodeRecording(CallLedger.Recorded(t + 500, true, 0), kind = "f")),
        )
        assertEquals(listOf(CallLedger.Recorded(t + 500, true, 0)), ledger.greetingFailures)
        assertEquals(emptyList<CallLedger.Recorded>(), ledger.greetingHangUps)
        val call = CallHistory.Logged(t, 3, CallHistory.Kind.INCOMING, "Mum", "+380")
        val calls = CallHistory.classify(
            logged = listOf(call),
            recordings = emptyList(),
            trashed = emptyList(),
            ledger = ledger,
            history = emptyList(),
            now = t + 60_000,
        )
        assertEquals(CallHistory.Status.GREETING_FAILED, calls.single().status)
        // The user was told when it was collected: not again as a missed call.
        assertEquals(
            emptyList<CallLogLookup.LoggedCall>(),
            MissedCalls.unrecorded(
                listOf(CallLogLookup.LoggedCall(t, 9, true, "Mum")), ledger, emptyList(),
                t - 60 * 60 * 1000L, t + 60 * 60 * 1000L,
            ),
        )
    }

    @Test fun theAnsweringMachinesCallsAreAccountedFor() {
        // Saving the next call must not take their call-log entries for its
        // own (RecordingSaver.PhoneLookups), any more than the missed-call
        // check takes them for lost ones.
        val ledger = CallLedger.parse(
            setOf(
                CallLedger.encodeRecording(CallLedger.Recorded(1_000L, true, 0), kind = "g"),
                CallLedger.encodeRecording(CallLedger.Recorded(2_000L, true, 0), kind = "f"),
                CallLedger.encodeRecording(CallLedger.Recorded(3_000L, false, 20), kind = "t"),
            ),
        )
        assertEquals(setOf(1_000L, 2_000L, 3_000L), ledger.accounted.map { it.startMillis }.toSet())
    }

    @Test fun theLedgerKeepsTestCalls() {
        val ledger = CallLedger.parse(
            setOf(CallLedger.encodeRecording(CallLedger.Recorded(1_000L, true, 25), kind = "t")),
        )
        assertEquals(listOf(CallLedger.Recorded(1_000L, true, 25)), ledger.testCalls)
        assertEquals(emptyList<CallLedger.Recorded>(), ledger.recordings)
    }

    @Test fun aTestCallIsNotAMissedRecording() {
        // Answered by the recorder, its recording deleted: in the call log it
        // is an answered call of 25 s with nothing saved.
        val t = 1_792_661_198_000L
        val logged = CallLogLookup.LoggedCall(t, 25, true, "Someone")
        val ledger = CallLedger.Ledger(emptyList(), emptySet(), testCalls = listOf(CallLedger.Recorded(t + 3_100, true, 22)))
        assertEquals(
            emptyList<CallLogLookup.LoggedCall>(),
            MissedCalls.unrecorded(listOf(logged), ledger, emptyList(), t - 60 * 60 * 1000L, t + 60 * 60 * 1000L),
        )
        val call = CallHistory.Logged(t, 25, CallHistory.Kind.INCOMING, "Someone", "+380")
        val calls = CallHistory.classify(
            logged = listOf(call),
            recordings = emptyList(),
            trashed = emptyList(),
            ledger = ledger,
            history = emptyList(),
            now = t + 60_000,
        )
        assertEquals(CallHistory.Status.TEST_CALL, calls.single().status)
    }
}
