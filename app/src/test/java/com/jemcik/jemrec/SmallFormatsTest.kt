package com.jemcik.jemrec

import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.WaveformCache
import com.jemcik.jemrec.ui.CallerColours
import com.jemcik.jemrec.ui.callLength
import com.jemcik.jemrec.ui.clock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app's small formats print, each where it is printed: m:ss,
 * written out in four places, and hex, in six. They are to be one function
 * each. These hold what m:ss prints in all four places, and hex where its
 * output is fixed - the caller colour's key and the waveform cache's name -
 * so the merge can be seen to change none of them.
 */
class SmallFormatsTest {

    /** A call's length on its notification: seconds under a minute. */
    @Test fun aCallsLengthOnItsNotification() {
        assertEquals("0 s", AfterCall.length(0.0))
        assertEquals("45 s", AfterCall.length(45.9))
        assertEquals("59 s", AfterCall.length(59.99))
        assertEquals("1:00", AfterCall.length(60.0))
        assertEquals("3:21", AfterCall.length(201.0))
        assertEquals("60:00", AfterCall.length(3600.0))
        assertEquals("125:07", AfterCall.length(7507.0))
    }

    /** The scrubber's two ends, from milliseconds: never below nothing. */
    @Test fun theScrubbersEnds() {
        assertEquals("0:00", clock(0))
        assertEquals("0:00", clock(999))
        assertEquals("0:01", clock(1_000))
        assertEquals("1:05", clock(65_000))
        assertEquals("0:00", clock(-5_000))
        assertEquals("60:00", clock(3_600_000))
    }

    /** A Calls tab row's length, from the call log's seconds. */
    @Test fun aCallRowsLength() {
        assertEquals("0:00", callLength(0))
        assertEquals("0:59", callLength(59))
        assertEquals("1:01", callLength(61))
        assertEquals("60:00", callLength(3600))
    }

    /** A recording's length on its row: nothing known is "--:--". */
    @Test fun aRecordingsLength() {
        assertEquals("--:--", Recordings.duration(0))
        assertEquals("--:--", Recordings.duration(-1))
        assertEquals("0:00", Recordings.duration(999))
        assertEquals("1:01", Recordings.duration(61_000))
        assertEquals("60:00", Recordings.duration(3_600_000))
    }

    /** Hashes cut to a length, in lower-case hex: values worked out now
     *  (SHA-256 by Python's hashlib), so any change to their form shows. */
    @Test fun hashesInHex() {
        assertEquals("74dfc82c81d4", CallerColours.keyOf("Olivia Bennett"))
        assertEquals("69d19261b4d7e6abfc94330e", WaveformCache.hashOf("20261020_203501_viber.ogg"))
    }
}
