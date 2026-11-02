package com.jemcik.jemrec.calls

import com.jemcik.jemrec.calls.CallTracking.Tracked
import com.jemcik.jemrec.calls.TelecomAccount.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telecom's account of a call as its states come in, and the early skip's guards. */
class TelecomAccountTest {

    private val t = 1_795_000_000_000L
    private val ringing = Tracked(t, true, null, emptyList(), 0L)

    private fun change(
        base: Tracked,
        change: Change,
        isNew: Boolean = false,
        connectedAt: Long = 0L,
        wasActive: Boolean = false,
        now: Long = t + 20_000,
    ) = TelecomAccount.changed(base, isNew, change, connectedAt, wasActive, now)

    @Test fun theFirstAnswerIsTelecomsConnectTimeElseNow() {
        assertEquals(listOf(t + 5_123), change(ringing, Change.ACTIVE, connectedAt = t + 5_123).activations)
        assertEquals(listOf(t + 20_000), change(ringing, Change.ACTIVE).activations)
    }

    @Test fun aReturnFromHoldIsAnotherAnswerAtNow() {
        val answered = ringing.copy(activations = listOf(t + 5_000))
        assertEquals(listOf(t + 5_000, t + 20_000), change(answered, Change.ACTIVE).activations)
    }

    @Test fun activeAgainWithNoHoldBetweenIsNotAnotherAnswer() {
        val answered = ringing.copy(activations = listOf(t + 5_000))
        assertEquals(answered, change(answered, Change.ACTIVE, wasActive = true))
    }

    @Test fun aCallFirstSeenAnsweredKeepsTelecomsConnectTime() {
        // Tracking switched on mid-call: its account took the connect time already.
        val seen = ringing.copy(activations = listOf(t + 5_000))
        assertEquals(seen, change(seen, Change.ACTIVE, isNew = true))
    }

    @Test fun theFirstEndCounts() {
        assertEquals(t + 20_000, change(ringing, Change.DISCONNECTED).endedAt)
        val ended = ringing.copy(endedAt = t + 9_000)
        assertEquals(t + 9_000, change(ended, Change.DISCONNECTED).endedAt)
        assertEquals(t + 9_000, TelecomAccount.ended(ended, t + 30_000).endedAt)
        assertEquals(t + 30_000, TelecomAccount.ended(ringing, t + 30_000).endedAt)
    }

    @Test fun anyOtherStateLeavesItAsItWas() {
        assertEquals(ringing, change(ringing, Change.OTHER))
    }

    // ---- the early skip ---------------------------------------------------------

    private val asked = mutableListOf<String>()

    private fun skip(
        callsUp: Int = 1,
        others: List<Tracked> = emptyList(),
        automatic: Boolean = true,
        keeps: Boolean? = false,
    ) = TelecomAccount.skipEarly(
        id = t,
        callsUp = callsUp,
        now = t,
        tracked = { asked += "tracked"; others },
        automatic = { asked += "automatic"; automatic },
        keeps = { asked += "keeps"; keeps },
    )

    @Test fun aCallTheRulesLeaveOutIsSkippedOnItsOwnInRecordEveryCall() {
        assertTrue(skip())
        assertEquals(listOf("tracked", "automatic", "keeps"), asked)
    }

    @Test fun onlyNoSkips() {
        assertFalse(skip(keeps = true))
        assertFalse("cannot tell: kept", skip(keeps = null))
    }

    @Test fun notWithAnotherCallUpAndNothingElseAsked() {
        assertFalse(skip(callsUp = 2))
        assertTrue(asked.isEmpty())
    }

    @Test fun notWithinTenSecondsOfAnotherCallsEnd() {
        val other = Tracked(t - 60_000, false, null, listOf(t - 50_000), t - TelecomAccount.RECENT_END_MS + 1)
        assertFalse(skip(others = listOf(other)))
        assertEquals(listOf("tracked"), asked)
        assertTrue(skip(others = listOf(other.copy(endedAt = t - TelecomAccount.RECENT_END_MS))))
        // Its own account does not count.
        assertTrue(skip(others = listOf(ringing.copy(endedAt = t))))
    }

    @Test fun notInAskFirstAndTheRulesNotAsked() {
        assertFalse(skip(automatic = false))
        assertEquals(listOf("tracked", "automatic"), asked)
    }
}
