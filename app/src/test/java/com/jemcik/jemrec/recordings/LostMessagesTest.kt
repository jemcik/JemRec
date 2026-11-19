package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.recordings.LostMessages.Lost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which recorder's file a message lost with its recorder is (review round
 *  14): the one begun between its call's start and its greeting. */
class LostMessagesTest {

    private val t = 1_795_000_000_000L

    @Test fun theFileBegunBetweenTheCallAndTheGreetingIsIt() {
        val lost = listOf(Lost(greetingAt = t + 10_000, callAt = t, test = false))
        assertEquals(lost[0], LostMessages.of(lost, t + 4_000))
        assertNull("before the call", LostMessages.of(lost, t - 1))
        assertNull("after the greeting", LostMessages.of(lost, t + 10_001))
    }

    /** Only the latest file begun before the greeting: an earlier call's,
     *  retried later, was taken for it (review round 15). */
    @Test fun anEarlierFileIsNotIt() {
        val lost = listOf(Lost(greetingAt = t + 10_000, callAt = null, test = false))
        assertNull(LostMessages.of(lost, t - 30_000, others = listOf(t + 2_000)))
        assertEquals(lost[0], LostMessages.of(lost, t + 2_000, others = listOf(t - 30_000)))
    }

    @Test fun withNoCallStartKnownAMinuteBeforeTheGreeting() {
        val lost = listOf(Lost(greetingAt = t + 10_000, callAt = null, test = false))
        assertEquals(lost[0], LostMessages.of(lost, t - 45_000))
        assertNull(LostMessages.of(lost, t - 60_001))
    }
}
