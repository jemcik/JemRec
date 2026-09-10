package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * The two name schemes - the daemon's jemrec_rec_<millis>_<in|out>.dat and the
 * saved yyyyMMdd_HHmmss_<in|out>.ogg - and the one reader of direction they
 * now share.
 */
class RecordingNamesTest {

    @Test fun directionIsReadTheSameWayFromBothSchemes() {
        assertTrue(RecordingStore.isIncoming("jemrec_rec_1757512433000_in.dat"))
        assertFalse(RecordingStore.isIncoming("jemrec_rec_1757512433000_out.dat"))
        assertTrue(RecordingStore.isIncoming("20260910_145353_in.ogg"))
        assertFalse(RecordingStore.isIncoming("20260910_145353_out.ogg"))
        assertTrue(RecordingSaver.isIncoming("jemrec_rec_1_in.dat"))
    }

    @Test fun aTagInsideAWordIsNotADirection() {
        // One of the two old readers matched "_in" and would have said yes here.
        assertFalse(RecordingStore.isIncoming("jemrec_rec_1_input.dat"))
        assertFalse(RecordingStore.isIncoming("milestone1.txt"))
    }

    @Test fun theDaemonNameCarriesTheCallsStartTime() {
        assertEquals(1_757_512_433_000L, RecordingSaver.startMillis("jemrec_rec_1757512433000_in.dat"))
        assertNull(RecordingSaver.startMillis("jemrec_rec_soon_in.dat"))
        assertNull(RecordingSaver.startMillis("20260910_145353_in.ogg"))
    }

    @Test fun theSavedNameKeepsTheDirectionItWasGiven() {
        val at = Date(1_757_512_433_000L)
        assertTrue(RecordingStore.isIncoming(RecordingStore.nameFor(at, incoming = true)))
        assertFalse(RecordingStore.isIncoming(RecordingStore.nameFor(at, incoming = false)))
    }
}
