package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.EmergencyPause.PAUSE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A day after a call to an emergency number the answering machine stands
 *  down, so a callback rings on - unless the user ends that, knowingly. */
class EmergencyPauseTest {

    private val now = 1_795_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun until(lastCall: Long = 0L, telecomLast: Long = 0L, resumedAt: Long = 0L) =
        EmergencyPause.pausedUntil(lastCall, telecomLast, resumedAt, now)

    @Test fun noEmergencyCallNoPause() {
        assertNull(until())
    }

    @Test fun aDayFromTheEmergencyCall() {
        assertEquals(now - hour + PAUSE_MS, until(lastCall = now - hour))
        assertNull(until(lastCall = now - PAUSE_MS - 1))
    }

    @Test fun telecomsRecordCountsToo() {
        // The emergency call this app did not see - made before it was
        // bound - as Telecom puts it on the ringing call.
        assertEquals(now - 2 * hour + PAUSE_MS, until(telecomLast = now - 2 * hour))
        // The later of the two.
        assertEquals(now - hour + PAUSE_MS, until(lastCall = now - hour, telecomLast = now - 3 * hour))
    }

    @Test fun theUserCanEndItEarly() {
        assertNull(until(lastCall = now - hour, resumedAt = now - 30 * 60 * 1000L))
        // Telecom's record of the same call does not bring it back.
        assertNull(until(lastCall = now - hour, telecomLast = now - 2 * hour, resumedAt = now - 30 * 60 * 1000L))
        // A new emergency call after that does.
        assertEquals(now - 10 * 60 * 1000L + PAUSE_MS, until(lastCall = now - 10 * 60 * 1000L, resumedAt = now - hour))
    }
}
