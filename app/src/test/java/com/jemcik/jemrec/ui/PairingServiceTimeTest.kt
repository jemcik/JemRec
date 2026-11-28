package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing service's tries end by its deadline, under shortService's
 * cap (review round 1, #140): a try, since 1.9, can probe for adbd's port
 * as well, so one starts only where a whole one fits.
 */
class PairingServiceTimeTest {

    @Test fun aTryStartsOnlyWhereAWholeOneFits() {
        assertEquals("look, probe budget, libadb's connect, the proof, a second more", 36_000L, PairingService.TRY_MS)
        assertTrue(PairingService.timeForATry(now = 100_000, deadline = 136_000))
        assertFalse(PairingService.timeForATry(now = 100_001, deadline = 136_000))
    }
}
