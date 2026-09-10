package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which call-log entry a recording is labelled with. The window is three
 * minutes back and thirty seconds forward of the recording's start, and only
 * entries in the same direction count.
 */
class CallLogMatchTest {

    private val t = 1_757_512_433_000L
    private val second = 1_000L

    private fun entry(at: Long, incoming: Boolean, label: String) =
        CallLogLookup.Entry(at, incoming, label, number = null)

    @Test fun picksTheClosestEntryInTheSameDirection() {
        val entries = listOf(
            entry(t - 60 * second, incoming = true, "an earlier incoming"),
            entry(t - 10 * second, incoming = true, "the incoming that rang just before"),
            entry(t - 5 * second, incoming = false, "an outgoing even closer"),
        )
        assertEquals("the incoming that rang just before", CallLogLookup.closest(entries, t, incoming = true)?.label)
        assertEquals("an outgoing even closer", CallLogLookup.closest(entries, t, incoming = false)?.label)
    }

    @Test fun theOtherDirectionNeverMatches() {
        val outgoingOnly = listOf(entry(t, incoming = false, "outgoing"))
        assertNull(CallLogLookup.closest(outgoingOnly, t, incoming = true))
    }

    @Test fun theWindowIsThreeMinutesBackAndThirtySecondsForward() {
        fun matches(offsetSeconds: Long) =
            CallLogLookup.closest(listOf(entry(t + offsetSeconds * second, true, "x")), t, incoming = true) != null
        assertEquals(true, matches(-179))
        assertEquals(false, matches(-181))
        assertEquals(true, matches(29))
        assertEquals(false, matches(31))
    }

    @Test fun nothingInTheWindowMeansNoLabel() {
        assertNull(CallLogLookup.closest(emptyList(), t, incoming = true))
    }
}
