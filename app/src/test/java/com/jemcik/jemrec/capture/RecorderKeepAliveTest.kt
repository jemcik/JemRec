package com.jemcik.jemrec.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a keep-alive run that left the recorder down tries again with backoff
 * (RecorderKeepAlive.triesAgain). The Wi-Fi run must not leave a retry
 * waiting off Wi-Fi: the watch it arms again would wake a new run, and a
 * retry still waiting would be taken for it, as the kick's took the watch's
 * until 0.25.3 (issue #69's Honor test, 2026-10-31).
 */
class RecorderKeepAliveTest {

    @Test fun aRunThatBroughtTheRecorderUpIsDone() {
        for (forWifi in listOf(false, true)) for (onWifi in listOf(false, true)) {
            assertFalse(RecorderKeepAlive.triesAgain(alive = true, forWifi = forWifi, onWifi = onWifi))
        }
    }

    @Test fun aKickOrTheHeartbeatTriesAgainWiFiOrNot() {
        assertTrue(RecorderKeepAlive.triesAgain(alive = false, forWifi = false, onWifi = false))
        assertTrue(RecorderKeepAlive.triesAgain(alive = false, forWifi = false, onWifi = true))
    }

    @Test fun theWiFiRunTriesAgainOnlyOnWiFi() {
        assertTrue(RecorderKeepAlive.triesAgain(alive = false, forWifi = true, onWifi = true))
        assertFalse(RecorderKeepAlive.triesAgain(alive = false, forWifi = true, onWifi = false))
    }
}
