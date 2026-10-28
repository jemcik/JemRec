package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.AdbdState.State
import com.jemcik.jemrec.capture.CaptureDaemon.Request
import com.jemcik.jemrec.capture.CaptureDaemon.WirelessStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Wireless debugging on and adbd not running: the Honor after a restart,
 * 2026-10-28. Every revive read adbd stopped, left the switch alone and found
 * nothing, until a tap switched it off and on.
 */
class AdbdStoppedTest {

    @Test fun initsNotesSayWhetherAdbdRuns() {
        assertEquals(State.RUNNING, AdbdState.state("running") { "running" })
        assertEquals(State.STOPPED, AdbdState.state("stopped") { "running" })
    }

    @Test fun anUnsetNoteIsAdbdNotStartedWhereZygotesCanBeRead() {
        // Same label as init.svc.zygote (init_service_status_prop), which is
        // set at every boot: readable, so adbd's "" is unset.
        assertEquals(State.STOPPED, AdbdState.state("") { "running" })
        // Neither readable, or getprop not run: nothing to go on.
        assertEquals(State.UNKNOWN, AdbdState.state("") { "" })
        assertEquals(State.UNKNOWN, AdbdState.state("") { null })
        assertEquals(State.UNKNOWN, AdbdState.state(null) { "running" })
    }

    @Test fun zygoteIsAskedOnlyForAnUnsetNote() {
        var asked = false
        AdbdState.state("running") { asked = true; "running" }
        AdbdState.state("stopped") { asked = true; "running" }
        assertFalse(asked)
    }

    @Test fun onTheWayIsNoReading() {
        assertEquals(State.UNKNOWN, AdbdState.state("restarting") { "running" })
        assertEquals(State.UNKNOWN, AdbdState.state("starting") { "running" })
    }

    @Test fun anAdbdAndroidIsStartingIsWaitedFor() = runBlocking {
        // A switch written just before (armShield, On): stopped, then running.
        val readings = ArrayDeque(listOf(State.STOPPED, State.RUNNING))
        assertEquals(State.RUNNING, AdbdState.settled(State.STOPPED, 2_000) { readings.removeFirst() })
        // On its way counts as not stopped: no off and on for it.
        assertEquals(State.UNKNOWN, AdbdState.settled(State.STOPPED, 2_000) { State.UNKNOWN })
    }

    @Test fun anAdbdThatStaysStoppedIsStopped() = runBlocking {
        // The Honor after a restart: nothing starts it.
        var reads = 0
        assertEquals(State.STOPPED, AdbdState.settled(State.STOPPED, 600) { reads++; State.STOPPED })
        assertTrue("read again while waiting: $reads", reads >= 1)
    }

    @Test fun onlyStoppedIsWaitedOn() = runBlocking {
        for (first in listOf(State.RUNNING, State.UNKNOWN)) {
            assertEquals(first, AdbdState.settled(first, 2_000) { error("read again for $first") })
        }
    }

    @Test fun onWithAdbdStoppedIsSwitchedOffAndOn() {
        assertEquals(
            WirelessStep.CYCLE,
            CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = true, onWifi = true, adbdStopped = true),
        )
        // Running, with nothing advertised, is a Wi-Fi not allowed: left to
        // the header's question.
        assertEquals(
            WirelessStep.LEAVE,
            CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = true, onWifi = true, adbdStopped = false),
        )
        // Off Wi-Fi Wireless debugging cannot come on.
        assertEquals(
            WirelessStep.LEAVE,
            CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = true, onWifi = false, adbdStopped = true),
        )
        // Off, it is simply switched on, which starts adbd as well.
        assertEquals(
            WirelessStep.TURN_ON,
            CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = false, onWifi = true, adbdStopped = true),
        )
    }

    @Test fun aRoutineReviveSwitchesOffAndOnOncePerWifi() {
        val again = { request: Request ->
            CaptureDaemon.wirelessDebuggingStep(
                cableStoppedAdbd = false, on = true, onWifi = true, adbdStopped = true,
                switchedOnHere = true, request = request,
            )
        }
        // Still stopped after a switch here: no reading gone wrong may put
        // Android's question up again and again.
        assertEquals(WirelessStep.LEAVE, again(Request.ROUTINE))
        // The user's tap is not held to it.
        assertEquals(WirelessStep.CYCLE, again(Request.USER))
        assertEquals(WirelessStep.CYCLE, again(Request.ALLOW_WIFI))
    }

    @Test fun allowThisWifiSwitchesOffAndOnWhateverIsFound() {
        // SystemUI asks when the switch goes on, not while it stays on.
        for (on in listOf(true, false)) {
            for (stopped in listOf(true, false)) {
                assertEquals(
                    WirelessStep.CYCLE,
                    CaptureDaemon.wirelessDebuggingStep(
                        cableStoppedAdbd = false, on = on, onWifi = true, refused = true,
                        adbdStopped = stopped, switchedOnHere = true, request = Request.ALLOW_WIFI,
                    ),
                )
            }
        }
        // Off Wi-Fi there is no Wi-Fi to allow.
        assertEquals(
            WirelessStep.LEAVE,
            CaptureDaemon.wirelessDebuggingStep(cableStoppedAdbd = false, on = true, onWifi = false, request = Request.ALLOW_WIFI),
        )
    }

    @Test fun unreadableAdbdIsSwitchedOffAndOnOnceAfterNothingIsFound() {
        val after = { adbd: State, step: WirelessStep, on: Boolean, onWifi: Boolean, here: Boolean, request: Request ->
            CaptureDaemon.cycleAfterNotFound(adbd, step, on, onWifi, here, request)
        }
        assertTrue(after(State.UNKNOWN, WirelessStep.LEAVE, true, true, false, Request.ROUTINE))
        // Once per connection for a routine revive; not for the user.
        assertFalse(after(State.UNKNOWN, WirelessStep.LEAVE, true, true, true, Request.ROUTINE))
        assertTrue(after(State.UNKNOWN, WirelessStep.LEAVE, true, true, true, Request.USER))
        // A reading decides it instead: stopped is switched before connecting,
        // running is a Wi-Fi not allowed.
        assertFalse(after(State.STOPPED, WirelessStep.LEAVE, true, true, false, Request.ROUTINE))
        assertFalse(after(State.RUNNING, WirelessStep.LEAVE, true, true, false, Request.ROUTINE))
        // Not after a revive that switched it already, not with it off (refused
        // there), not off Wi-Fi.
        assertFalse(after(State.UNKNOWN, WirelessStep.CYCLE, true, true, false, Request.ROUTINE))
        assertFalse(after(State.UNKNOWN, WirelessStep.TURN_ON, true, true, false, Request.ROUTINE))
        assertFalse(after(State.UNKNOWN, WirelessStep.LEAVE, false, true, false, Request.ROUTINE))
        assertFalse(after(State.UNKNOWN, WirelessStep.LEAVE, true, false, false, Request.ROUTINE))
    }
}
