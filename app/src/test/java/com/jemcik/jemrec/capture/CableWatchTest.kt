package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CableWatch.Step
import com.jemcik.jemrec.capture.CaptureDaemon.WirelessStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app does after the daemon says a USB cable went in or came out. */
class CableWatchTest {

    @Test fun aRecorderThatGoesIsBroughtBackAtOnce() {
        assertEquals(Step.REVIVE, CableWatch.next(sinceMs = 0, recorderUp = false))
        // The Honor: the kill lands about three seconds after the cable.
        assertEquals(Step.REVIVE, CableWatch.next(sinceMs = 1_500, recorderUp = false))
        // Even at the very end of the watch.
        assertEquals(Step.REVIVE, CableWatch.next(sinceMs = CableWatch.WATCH_MS, recorderUp = false))
    }

    @Test fun aRecorderThatStaysUpIsWatchedForTheWholeWindowThenLeft() {
        assertEquals(Step.WAIT, CableWatch.next(sinceMs = 0, recorderUp = true))
        assertEquals(Step.WAIT, CableWatch.next(sinceMs = CableWatch.WATCH_MS - 1, recorderUp = true))
        assertEquals(Step.DONE, CableWatch.next(sinceMs = CableWatch.WATCH_MS, recorderUp = true))
    }

    @Test fun theWindowCoversTheKillAndEndsBeforeAndroidShowsTheNotification() {
        // Kill measured 2.8 s after the cable on the Honor; the daemon reads
        // the port once a second, so the app hears of it up to a second late.
        assertTrue(CableWatch.WATCH_MS >= 2 * 3_000)
        // A foreground service's notification waits ten seconds (Android 12+).
        assertTrue(CableWatch.WATCH_MS + CableWatch.CHECK_MS < 10_000)
    }

    @Test fun thePhoneIsKeptAwakeForTheWholeWatchAndEveryAttempt() {
        // Each attempt at its slowest: off and on (1 s), adbd settling, a
        // connect that times out, the start itself (allow 5 s).
        val attempt = 1_000L + CaptureDaemon.ADBD_SETTLE_MS + CaptureDaemon.CONNECT_MS + 5_000L
        val confirmedDeath = 5 * 600L + 5 * 600L
        val worst = CableWatch.WATCH_MS + confirmedDeath +
            CableWatch.ATTEMPTS * attempt + (CableWatch.ATTEMPTS - 1) * CableWatch.RETRY_MS
        assertTrue("$worst ms of work, ${CableWatch.AWAKE_MS} ms awake", CableWatch.AWAKE_MS >= worst)
    }

    @Test fun aCableThatStoppedAdbdHasWirelessDebuggingSwitchedOffAndOn() {
        // On the Honor it read 1 with adbd stopped; only off-and-on started it.
        assertEquals(WirelessStep.CYCLE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = true, on = true))
        assertEquals(WirelessStep.CYCLE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = true, on = false))
    }

    @Test fun otherwiseWirelessDebuggingIsOnlyTurnedOnWhenOff() {
        // A reboot leaves it off.
        assertEquals(WirelessStep.TURN_ON, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = false))
        // On, with nothing advertised, may be a Wi-Fi Android has not allowed:
        // switching it again would put Android's question up unasked.
        assertEquals(WirelessStep.LEAVE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = true))
    }
}
