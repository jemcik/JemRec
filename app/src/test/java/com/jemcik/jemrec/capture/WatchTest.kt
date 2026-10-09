package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the app reads out of the daemon's report on its call watch (Main.COMMAND_WATCH). */
class WatchTest {

    private val line = "WATCH 1 since=1791544738000 watching=1 looper=ok mode=1 call=0 " +
        "events=5 lastevent=1791545000000 offhooks=2 lastoffhook=1791544900000 recording=0"

    @Test fun everyFieldIsRead() {
        assertEquals(
            CaptureDaemon.Watch(
                since = 1791544738000,
                watching = true,
                looperOk = true,
                mode = 1,
                inCall = false,
                events = 5,
                lastEvent = 1791545000000,
                offHooks = 2,
                lastOffHook = 1791544900000,
                recordingSince = 0,
            ),
            CaptureDaemon.parseWatch(line),
        )
    }

    @Test fun aStuckLooperAndAStuckRecordingReadAsSuch() {
        val watch = CaptureDaemon.parseWatch(
            line.replace("looper=ok", "looper=stuck")
                .replace("call=0", "call=1")
                .replace("recording=0", "recording=1791544800000"),
        )
        assertEquals(false, watch?.looperOk)
        assertEquals(true, watch?.inCall)
        assertEquals(1791544800000, watch?.recordingSince)
    }

    @Test fun orderDoesNotMatterAndUnknownFieldsAreIgnored() {
        val shuffled = "WATCH 1 recording=0 lastoffhook=1791544900000 offhooks=2 " +
            "lastevent=1791545000000 events=5 call=0 mode=1 looper=ok watching=1 " +
            "since=1791544738000 future=yes"
        assertEquals(CaptureDaemon.parseWatch(line), CaptureDaemon.parseWatch(shuffled))
    }

    @Test fun aDaemonTooOldForTheCommandHangsUpAndThatIsNoReport() {
        assertNull(CaptureDaemon.parseWatch(null))
        assertNull(CaptureDaemon.parseWatch(""))
    }

    @Test fun anotherFormatOrAMissingOrBrokenFieldIsNoReport() {
        assertNull(CaptureDaemon.parseWatch(line.replace("WATCH 1", "WATCH 2")))
        assertNull(CaptureDaemon.parseWatch(line.replace("WATCH 1", "PONG 2")))
        assertNull(CaptureDaemon.parseWatch(line.replace("since=1791544738000 ", "")))
        assertNull(CaptureDaemon.parseWatch(line.replace("events=5", "events=five")))
    }
}
