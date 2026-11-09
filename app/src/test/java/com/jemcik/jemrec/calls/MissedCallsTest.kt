package com.jemcik.jemrec.calls

import com.jemcik.jemrec.calls.CallLedger.Ledger
import com.jemcik.jemrec.calls.CallLedger.Recorded
import com.jemcik.jemrec.calls.CallLogLookup.LoggedCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** Which logged calls the missed-call check reports, and which it must not. */
class MissedCallsTest {

    private val covered = 1_791_500_000_000L
    private val call = covered + 60 * 60 * 1000L
    private val now = call + 60 * 60 * 1000L

    private fun logged(at: Long = call, seconds: Int = 120, incoming: Boolean = true) =
        LoggedCall(at, seconds, incoming, "Mum")

    private fun missed(
        calls: List<LoggedCall>,
        recordings: List<Recorded> = emptyList(),
        reported: Set<Long> = emptySet(),
        pending: List<Recorded> = emptyList(),
        now: Long = this.now,
        skipped: List<Recorded> = emptyList(),
    ) = MissedCalls.unrecorded(calls, Ledger(recordings, reported, skipped), pending, covered, now)

    /** Its times in Latin digits and English months in any language, as the
     *  rest of JemRec's: until 0.30 a month in the phone's language among
     *  English words ("3 Okt." in German), and in Arabic or Persian digits. */
    @Test fun itsTimesAreWrittenAsTheAppsOthersInAnyLanguage() {
        val locale = Locale.getDefault()
        val today = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 21, 14, 5) }.timeInMillis
        val before = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 3, 14, 5) }.timeInMillis
        try {
            for (language in listOf("de", "uk", "ar", "fa")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                assertEquals(language, "14:05", MissedCalls.at(today, now = today + 60_000))
                assertEquals(language, "3 Oct 14:05", MissedCalls.at(before, now = today))
            }
        } finally {
            Locale.setDefault(locale)
        }
    }

    @Test fun aCallWithNoRecordingIsMissed() {
        assertEquals(listOf(logged()), missed(listOf(logged())))
    }

    @Test fun aRecordedCallIsNot() {
        // Answered 20 s after it started ringing, which is when the log dates it.
        assertTrue(missed(listOf(logged()), listOf(Recorded(call + 20_000, true, 100))).isEmpty())
    }

    @Test fun aRecordingTheUserDeletedStillCounts() {
        // The ledger is written at save time, so a deleted file is not a gap.
        val ledgerOnly = listOf(Recorded(call + 5_000, true, 115))
        assertTrue(missed(listOf(logged()), ledgerOnly).isEmpty())
    }

    @Test fun aCallTheRulesLeftOutIsNotMissed() {
        val skipped = listOf(Recorded(call + 5_000, true, 115))
        assertTrue(missed(listOf(logged()), skipped = skipped).isEmpty())
    }

    @Test fun aRecordingTheOtherWayRoundDoesNotCount() {
        val wrongWay = listOf(Recorded(call + 5_000, false, 115))
        assertEquals(1, missed(listOf(logged(incoming = true)), wrongWay).size)
    }

    @Test fun aSecondCallDuringARecordingIsPartOfIt() {
        // Call waiting: the second call lands in the first one's file.
        val first = Recorded(call - 60_000, false, 300)
        assertTrue(missed(listOf(logged(at = call, incoming = true)), listOf(first)).isEmpty())
    }

    @Test fun aPendingRecordingCountsByItsStart() {
        assertTrue(missed(listOf(logged()), pending = listOf(Recorded(call + 3_000, true, -1))).isEmpty())
    }

    @Test fun aCallAlreadyReportedIsNotReportedAgain() {
        assertTrue(missed(listOf(logged()), reported = setOf(call)).isEmpty())
    }

    @Test fun shortCallsAreLeftAlone() {
        assertTrue(missed(listOf(logged(seconds = MissedCalls.MIN_SECONDS - 1))).isEmpty())
    }

    @Test fun aCallThatHasJustEndedGetsItsChanceToBeSaved() {
        val endsAt = call + 120_000
        assertTrue(missed(listOf(logged()), now = endsAt + MissedCalls.SETTLE_MS - 1).isEmpty())
        assertEquals(1, missed(listOf(logged()), now = endsAt + MissedCalls.SETTLE_MS).size)
    }

    @Test fun callsBeforeCoverageAreNotThisChecksBusiness() {
        // Made while recording was off, or in ask-first mode.
        assertTrue(missed(listOf(logged(at = covered - 1))).isEmpty())
    }
}
