package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The self-test's judgement of whether the daemon heard the calls the phone logged. */
class UnheardCallTest {

    private val start = 1_791_544_738_000L

    @Test fun noCallsMeansNothingMissed() {
        assertNull(SelfTest.unheardCall(emptyList(), lastEvent = start))
    }

    @Test fun aCallHeardAtOrAfterItsStartWasHeard() {
        assertNull(SelfTest.unheardCall(listOf(start + 60_000), lastEvent = start + 60_000))
        assertNull(SelfTest.unheardCall(listOf(start + 60_000), lastEvent = start + 120_000))
    }

    @Test fun anEventJustBeforeTheLoggedStartStillCounts() {
        val call = start + 60_000
        assertNull(SelfTest.unheardCall(listOf(call), lastEvent = call - SelfTest.HEARD_SLACK_MS))
        assertEquals(call, SelfTest.unheardCall(listOf(call), lastEvent = call - SelfTest.HEARD_SLACK_MS - 1))
    }

    @Test fun onlyTheLatestCallNeedsHearing() {
        val calls = listOf(start + 60_000, start + 600_000, start + 300_000)
        assertNull(SelfTest.unheardCall(calls, lastEvent = start + 610_000))
    }

    @Test fun aCallSoonAfterTheLastHeardOneIsStillJudged() {
        // Measured on the emulator: the first call's hang-up was the watch's
        // last event, and the next call rang nine seconds later, unheard.
        val hangUp = start + 69_000
        val next = hangUp + 9_000
        assertEquals(next, SelfTest.unheardCall(listOf(start + 60_000, next), lastEvent = hangUp))
    }

    @Test fun theFieldReportFirstCallHeardThenDeaf() {
        // The watch heard the first call, then was garbage-collected: its last
        // event stays at the first call's hang-up while the log keeps going.
        val first = start + 60_000
        val second = start + 900_000
        assertEquals(second, SelfTest.unheardCall(listOf(first, second), lastEvent = first + 75_000))
    }
}
