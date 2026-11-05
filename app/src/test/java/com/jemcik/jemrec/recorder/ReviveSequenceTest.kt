package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.adb.AdbTransport.ConnectOutcome
import com.jemcik.jemrec.recorder.Revive.Request
import com.jemcik.jemrec.recorder.StartWithoutWifi.PortStep
import com.jemcik.jemrec.system.AdbdState.State
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A revive's whole sequence under the recorder's lock (Revive.reviveLocked),
 * from the probe to the stand-down, on a phone of the test's own (FakePhone):
 * what it switches, connects and starts, in what order, and how long it waits
 * - the waits are the test's virtual time. As it is today; the parts nothing
 * could reach without adbd and a recorder, the connect on, included.
 */
class ReviveSequenceTest {

    private val wifiOff = "adb_wifi_enabled=0"
    private val wifiOn = "adb_wifi_enabled=1"

    /** One revive on [phone]: whether the recorder is up after it, and what
     *  its own connect found. */
    private suspend fun revive(
        phone: FakePhone,
        request: Request = Request.ROUTINE,
        onPortChange: (() -> Unit)? = null,
    ): Pair<Boolean, ConnectOutcome> {
        val found = Revive.Found()
        val up = Revive.reviveLocked(phone, onPortChange, request, found)
        return up to found.connect
    }

    private val started = listOf("start", "push", "stand down", "after start")

    private fun TestScope.ms() = testScheduler.currentTime

    @Test fun anUpToDateRecorderWithNoPortChangeIsLeftAsItIs() = runTest {
        val phone = FakePhone().apply { recorder = DaemonClient.Answer(2, "1a2b3c4d") }
        assertEquals(true to ConnectOutcome.NONE, revive(phone))
        assertEquals(listOf("probe"), phone.did)
        assertEquals(0L, ms())
    }

    @Test fun switchedOnAndRefusedWhileTheRecorderIsUpItWaitsForAnAllowThenGivesUp() = runTest {
        // Only the port is at stake: the recorder is up and current.
        val phone = FakePhone().apply {
            recorder = DaemonClient.Answer(2, "1a2b3c4d")
            step = PortStep.OPEN
            wireless = false
            refusing = true
        }

        assertEquals(true to ConnectOutcome.NONE, revive(phone))

        // On, the four seconds adbd gets, then twenty for Allow; refused.
        assertEquals(listOf("probe", wifiOn, "note switched on", "note refused"), phone.did)
        assertEquals(24_000L, ms())
        assertTrue("revive: Wireless debugging TURN_ON" in phone.log)
        assertEquals("revive: Android refused Wireless debugging on this Wi-Fi", phone.log.last())
        assertTrue(phone.refused)
    }

    @Test fun anAllowWhileItWaitsGoesOnToOpenThePort() = runTest {
        val phone = FakePhone().apply {
            recorder = DaemonClient.Answer(2, "1a2b3c4d")
            step = PortStep.OPEN
            wireless = false
            refusing = true
            connects += listOf(ConnectOutcome.CONNECTED, ConnectOutcome.CONNECTED)
        }
        // The user taps Allow ten seconds in.
        launch {
            delay(10_000)
            phone.refusing = false
            phone.wireless = true
        }
        var portChange = 0

        val (up, connect) = revive(phone, onPortChange = { portChange++ })

        assertTrue(up)
        // Its connect is the one after adbd's restart, on the port.
        assertEquals(ConnectOutcome.CONNECTED, connect)
        assertEquals(1, portChange)
        assertEquals(
            listOf(
                "probe", wifiOn, "note switched on", "autoConnect 5000",
                "restart tcpip:5555", "close", "note opened true", "connect 5555",
            ) + started,
            phone.did,
        )
        assertTrue("revive: port 5555 opened" in phone.log)
    }

    @Test fun aRoutineReviveSwitchesOffAndOnOncePerConnection() = runTest {
        // Both switches on and adbd stopped: after a restart of the Honor.
        val phone = FakePhone().apply {
            adbd[0] = State.STOPPED
            connects += ConnectOutcome.CONNECTED
        }

        assertEquals(true to ConnectOutcome.CONNECTED, revive(phone))

        // Two seconds for an adbd starting by itself, then off, a second,
        // on - which starts it - and four seconds for it to advertise.
        assertEquals(
            listOf("probe", wifiOff, wifiOn, "forget cable", "note switched on", "autoConnect 5000") + started,
            phone.did,
        )
        assertEquals(7_000L, ms())
        assertTrue("revive: adbd STOPPED" in phone.log)
        assertTrue("revive: Wireless debugging CYCLE" in phone.log)

        // Stopped again on the same connection: left alone, and down.
        phone.did.clear()
        phone.adbd[0] = State.STOPPED
        phone.connected = false
        assertEquals(false to ConnectOutcome.NOT_FOUND, revive(phone))
        assertEquals(listOf("probe", "autoConnect 5000"), phone.did)
        assertTrue("revive: Wireless debugging LEAVE" in phone.log)

        // The user's Tap to start again is not held to that.
        phone.did.clear()
        phone.connects += ConnectOutcome.CONNECTED
        assertEquals(true to ConnectOutcome.CONNECTED, revive(phone, Request.USER))
        assertEquals(listOf(wifiOff, wifiOn), phone.did.filter { it.startsWith("adb_wifi") })
    }

    @Test fun aCableThatStoppedAdbdHasItSwitchedOffAndOnEvenAfterOneHere() = runTest {
        val phone = FakePhone().apply {
            cable = true
            switchedOn = true
            adbd[0] = State.STOPPED
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone).first)

        // No wait for an adbd starting by itself: a cable's is not.
        assertEquals(
            listOf("probe", wifiOff, wifiOn, "forget cable", "note switched on", "autoConnect 5000") + started,
            phone.did,
        )
        assertEquals(5_000L, ms())
        assertFalse(phone.cable)
    }

    @Test fun nothingAdvertisedWithAdbdUnreadableIsSwitchedOffAndOnOnceThenLookedForAgain() = runTest {
        val phone = FakePhone().apply {
            adbd[0] = State.UNKNOWN
            connects += listOf(ConnectOutcome.NOT_FOUND, ConnectOutcome.CONNECTED)
        }

        assertEquals(true to ConnectOutcome.CONNECTED, revive(phone))

        assertEquals(
            listOf(
                "probe", "autoConnect 5000", wifiOff, wifiOn, "forget cable", "note switched on", "autoConnect 5000",
            ) + started,
            phone.did,
        )
        assertTrue("revive: nothing advertised, adbd unreadable; switching off and on once on this Wi-Fi" in phone.log)

        // Once on this connection.
        phone.did.clear()
        phone.connected = false
        assertEquals(false to ConnectOutcome.NOT_FOUND, revive(phone))
        assertEquals(listOf("probe", "autoConnect 5000"), phone.did)
    }

    @Test fun allowThisWifiAsksAgainLooksTwentySecondsAndNotesACancel() = runTest {
        // Refused here before; Android 12 keeps the switch off while it asks,
        // and after Cancel.
        val phone = FakePhone().apply {
            refused = true
            refusing = true
        }

        assertEquals(false to ConnectOutcome.NOT_FOUND, revive(phone, Request.ALLOW_WIFI))

        // Off and on whatever else, the refusal forgotten first; no refusal
        // noted while the user may be answering; the connect looks twenty
        // seconds; then, still off, Cancel is noted.
        assertEquals(
            listOf(
                "probe", "forget refused", wifiOff, wifiOn, "forget cable", "note switched on",
                "autoConnect 20000", "note refused",
            ),
            phone.did,
        )
        assertEquals(1_000L + 4_000L, ms())
        assertTrue(phone.refused)
    }

    @Test fun aCloseOfThePortIsNotMadeOverTheConnectionThatCameOverIt() = runTest {
        val phone = FakePhone().apply {
            step = PortStep.CLOSE
            autoPort = 5555
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone).first)

        assertEquals(listOf("probe", "autoConnect 5000") + started, phone.did)
        assertTrue("revive: port 5555 left open: connected over it, no Wireless debugging here" in phone.log)
    }

    @Test fun aCloseOverWirelessDebuggingIsMadeAndReadBack() = runTest {
        val phone = FakePhone().apply {
            step = PortStep.CLOSE
            set = true
            connects += listOf(ConnectOutcome.CONNECTED, ConnectOutcome.CONNECTED)
        }
        var portChange = 0

        assertTrue(revive(phone, onPortChange = { portChange++ }).first)

        assertEquals(1, portChange)
        assertEquals(
            listOf("probe", "autoConnect 5000", "restart usb:", "close", "note closed", "autoConnect 5000") + started,
            phone.did,
        )
        assertTrue("revive: port 5555 closed" in phone.log)
    }

    @Test fun offWifiWithThePortOpenItConnectsOverIt() = runTest {
        val phone = FakePhone().apply {
            wifi = false
            ready = true
            answers = true
            connects += listOf(ConnectOutcome.REFUSED, ConnectOutcome.REFUSED, ConnectOutcome.CONNECTED)
        }

        assertEquals(true to ConnectOutcome.CONNECTED, revive(phone))

        // Three tries a second apart; Wireless debugging left as it is.
        assertEquals(listOf("probe", "connect 5555", "connect 5555", "connect 5555") + started, phone.did)
        assertEquals(2_000L, ms())
        assertTrue("revive: connecting on port 5555, no Wi-Fi" in phone.log)
    }

    @Test fun offWifiWithoutThePortItStopsBeforeAnything() = runTest {
        val phone = FakePhone().apply { wifi = false }
        assertEquals(false to ConnectOutcome.NONE, revive(phone))
        assertEquals(listOf("probe"), phone.did)
        assertEquals("revive: not on Wi-Fi", phone.log.last())
    }

    @Test fun anOldBuildUpWithNoSessionToReplaceItOverCountsAsUp() = runTest {
        val phone = FakePhone().apply {
            recorder = DaemonClient.Answer(2, "00000000")
            currentBuild = false
        }

        assertEquals(true to ConnectOutcome.NOT_FOUND, revive(phone))

        // No start: there is no session to start the next one over.
        assertEquals(listOf("probe", "autoConnect 5000"), phone.did)
    }

    @Test fun beforeSetupNothingIsWrittenAndNothingConnects() = runTest {
        val phone = FakePhone().apply {
            setUp = false
            usb = com.jemcik.jemrec.system.GlobalSettings.Reading.OFF
        }
        assertEquals(false to ConnectOutcome.NONE, revive(phone))
        assertEquals(listOf("probe"), phone.did)
    }

    @Test fun setUpTheShieldGoesBackOnFirstEvenWithTheRecorderUp() = runTest {
        val phone = FakePhone().apply {
            usb = com.jemcik.jemrec.system.GlobalSettings.Reading.OFF
            recorder = DaemonClient.Answer(2, "1a2b3c4d")
        }
        assertTrue(revive(phone).first)
        assertEquals(listOf("adb_enabled=1", "probe"), phone.did)
    }

    @Test fun aFailedStartIsDownAndStandsNothingDown() = runTest {
        val phone = FakePhone().apply {
            connects += ConnectOutcome.CONNECTED
            startResult = Result.failure(IllegalStateException("daemon did not start listening"))
        }
        assertEquals(false to ConnectOutcome.CONNECTED, revive(phone))
        assertEquals(listOf("probe", "autoConnect 5000", "start"), phone.did)
        assertEquals("revive: start failed: daemon did not start listening", phone.log.last())
    }
}
