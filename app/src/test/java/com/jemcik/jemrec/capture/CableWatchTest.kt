package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CableWatch.Step
import com.jemcik.jemrec.capture.CaptureDaemon.WirelessStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(WirelessStep.CYCLE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = true, on = true, onWifi = true))
        assertEquals(WirelessStep.CYCLE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = true, on = false, onWifi = true))
        // Off Wi-Fi too, on Start without Wi-Fi's port: the switch going on
        // is what has Android start adbd, Wi-Fi or not.
        assertEquals(WirelessStep.CYCLE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = true, on = true, onWifi = false))
    }

    @Test fun otherwiseWirelessDebuggingIsOnlyTurnedOnWhenOff() {
        // A reboot leaves it off.
        assertEquals(WirelessStep.TURN_ON, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = false, onWifi = true))
        // On, with nothing advertised, may be a Wi-Fi Android has not allowed:
        // switching it again would put Android's question up unasked.
        assertEquals(WirelessStep.LEAVE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = true, onWifi = true))
        // Off where Android refused it on this Wi-Fi: Android 12 keeps it off
        // after Cancel, and switching it on again asked again at every revive
        // (the 12 AVD, 2026-10-28) - left to the Allow this Wi-Fi button.
        assertEquals(
            WirelessStep.LEAVE,
            CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = false, onWifi = true, refused = true),
        )
        // A cable's stopped adbd is still started: there is nothing else to.
        assertEquals(
            WirelessStep.CYCLE,
            CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = true, on = false, onWifi = true, refused = true),
        )
    }

    @Test fun offWifiWirelessDebuggingIsNotTurnedOnForNothing() {
        // On the port, there is no Wi-Fi for it to come on over.
        assertEquals(WirelessStep.LEAVE, CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = false, onWifi = false))
    }

    @Test fun aRestartInItsUsualTimeWasNotHeldBack() {
        // Measured on the Honor on "Manage manually": 12 to 16 seconds.
        assertFalse(CableWatch.heldBack(16_000))
        // The slowest a restart can take by itself is what the wake lock
        // covers, and reaching it exactly is still not a hold-up.
        assertFalse(CableWatch.heldBack(CableWatch.AWAKE_MS))
    }

    @Test fun aLongGapWarnsOnlyWithTheRecorderDown() {
        // Frozen for minutes after a cable that took the recorder down: warn.
        assertTrue(CableWatch.warnsAfterGap(205_000, recorderDown = true))
        // Frozen just as long after a charger pulled with the recorder fine:
        // nothing was held back that mattered, so no warning.
        assertFalse(CableWatch.warnsAfterGap(205_000, recorderDown = false))
        // An ordinary gap between looks never warns.
        assertFalse(CableWatch.warnsAfterGap(CableWatch.CHECK_MS + 3_000, recorderDown = true))
    }

    @Test fun aRestartFrozenForMinutesWasHeldBack() {
        // Measured on "Manage automatically": back 8.5 minutes after the cable.
        assertTrue(CableWatch.heldBack(510_000))
        assertTrue(CableWatch.heldBack(CableWatch.AWAKE_MS + 1))
    }
}
