package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.adb.AdbTransport.ConnectOutcome
import com.jemcik.jemrec.recorder.DaemonClient.WirelessOff
import com.jemcik.jemrec.recorder.Revive.Request
import com.jemcik.jemrec.recorder.StartWithoutWifi.PortStep
import com.jemcik.jemrec.system.AdbdState.State
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wireless debugging on only while JemRec starts its recorder (plan 1.5,
 * tmp/wd-off-between-starts/PLAN.md): a revive on a FakePhone switches it
 * on where it is off, notes it as JemRec's, and asks the recorder to switch
 * it off again once the recorder is up and the session closed
 * (Revive.askWirelessOff); one the user switched on stays on. Each test
 * names the plan's state-table row it holds.
 */
class WirelessBetweenStartsTest {

    private val wifiOff = "adb_wifi_enabled=0"
    private val wifiOn = "adb_wifi_enabled=1"
    private val started = listOf("start", "push", "stand down", "after start")
    private val startedAndOff = listOf("start", "push", "stand down", "wireless off", "still up?", "forget jemrecs", "after start")
    private val up = DaemonClient.Answer(2, "1a2b3c4d")

    private suspend fun revive(phone: FakePhone, request: Request = Request.ROUTINE): Boolean =
        Revive.reviveLocked(phone, null, request, Revive.Found())

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.ms() = testScheduler.currentTime

    /** Row 5: the recorder lost on an allowed Wi-Fi, Wireless debugging off. */
    @Test fun aStartSwitchesItOnAndTheRecorderOffAgainOnceTheSessionIsClosed() = runTest {
        val phone = FakePhone().apply {
            wireless = false
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf("probe", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000") + startedAndOff,
            phone.did,
        )
        // Asked after the session that started it is closed, not before.
        assertTrue(phone.did.indexOf("stand down") < phone.did.indexOf("wireless off"))
        assertFalse(phone.wireless)
        assertFalse(phone.jemRecs)
        // The fixed four seconds after the switch-on, and the guard's after
        // the off.
        assertEquals(Revive.ADBD_SETTLE_MS + Revive.OFF_GUARD_MS, ms())
        assertTrue("revive: Wireless debugging off - the recorder says SWITCHED_OFF" in phone.log)
    }

    /** Row 1: up, current, Wireless debugging off: the heartbeat leaves it so. */
    @Test fun aRecorderUpWithItOffIsLeftAlone() = runTest {
        val phone = FakePhone().apply {
            recorder = up
            wireless = false
        }

        assertTrue(revive(phone))

        assertEquals(listOf("probe"), phone.did)
    }

    /** Row 2: up, current, and JemRec's still on - the app stopped between
     *  a start and its off: the next look switches it off. */
    @Test fun theNextLookThatFindsTheRecorderUpSwitchesJemRecsOff() = runTest {
        val phone = FakePhone().apply {
            recorder = up
            jemRecs = true
        }

        assertTrue(revive(phone))

        assertEquals(listOf("probe", "wireless off", "still up?", "forget jemrecs"), phone.did)
        assertFalse(phone.wireless)
    }

    /** Row 3: on, and not JemRec's - the user's computer, another app's start:
     *  used, and left on. */
    @Test fun aReviveThatFindsTheUsersOnUsesItAndLeavesItOn() = runTest {
        val phone = FakePhone().apply { connects += ConnectOutcome.CONNECTED }

        assertTrue(revive(phone))

        assertEquals(listOf("probe", "autoConnect 5000") + started, phone.did)
        assertTrue(phone.wireless)

        // Up and current: left on too.
        phone.did.clear()
        phone.recorder = up
        assertTrue(revive(phone))
        assertEquals(listOf("probe"), phone.did)
        assertTrue(phone.wireless)
    }

    /** Row 3: the user's, switched off and on for an adbd stopped: still the
     *  user's, and on. */
    @Test fun anOffAndOnOfTheUsersLeavesItTheUsers() = runTest {
        val phone = FakePhone().apply {
            adbd[0] = State.STOPPED
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf("probe", wifiOff, wifiOn, "forget cable", "note switched on", "autoConnect 5000") + started,
            phone.did,
        )
        assertTrue(phone.wireless)
        assertFalse(phone.jemRecs)
    }

    /** Row 14: a cable stopped adbd with it off: switched off and on, which
     *  makes it JemRec's, and off again once the recorder is up. */
    @Test fun aCablesOffAndOnFromOffIsJemRecsAndGoesOffAgain() = runTest {
        val phone = FakePhone().apply {
            wireless = false
            cable = true
            adbd[0] = State.STOPPED
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf("probe", wifiOff, wifiOn, "forget cable", "note switched on", "note jemrecs", "autoConnect 5000") +
                startedAndOff,
            phone.did,
        )
        assertFalse(phone.wireless)
    }

    /** Row 4: an install from before 1.5, Wireless debugging on with no note:
     *  taken over once, and off with the recorder up. */
    @Test fun anInstallFromBeforeTakesItOverOnce() = runTest {
        val phone = FakePhone().apply {
            takenOver = false
            recorder = up
        }

        assertTrue(revive(phone))

        assertEquals(listOf("take over", "probe", "wireless off", "still up?", "forget jemrecs"), phone.did)
        assertFalse(phone.wireless)
        assertTrue(phone.takenOver)

        // Once: switched on by the user after it, it stays on.
        phone.wireless = true
        phone.did.clear()
        assertTrue(revive(phone))
        assertEquals(listOf("probe"), phone.did)
        assertTrue(phone.wireless)
    }

    /** Row 4: off Wi-Fi too - Android 17 keeps the switch across Wi-Fi
     *  changes - and the recorder says it is off already where it is. */
    @Test fun offWifiItIsTakenOverToo() = runTest {
        val phone = FakePhone().apply {
            takenOver = false
            wifi = false
            wireless = false
            recorder = up
        }

        assertTrue(revive(phone))

        assertEquals(listOf("take over", "probe", "wireless off", "forget jemrecs"), phone.did)
        assertTrue(phone.takenOver)
        assertFalse(phone.jemRecs)
    }

    /** Row 4: not before setup has finished - setup's end asks itself. */
    @Test fun beforeSetupNothingIsTakenOverOrAsked() = runTest {
        val phone = FakePhone().apply {
            setUp = false
            takenOver = false
            jemRecs = true
            recorder = up
        }

        assertTrue(revive(phone))

        assertEquals(listOf("probe"), phone.did)
        assertTrue(phone.wireless)
    }

    /** Row 10: on Wi-Fi with Start without Wi-Fi's port open, the connect
     *  falling back on it: switched on, and off again all the same. */
    @Test fun withThePortOpenOnWifiItIsSwitchedOnAndOffAsAnywhere() = runTest {
        val phone = FakePhone().apply {
            wireless = false
            ready = true
            set = true
            answers = true
            autoPort = StartWithoutWifi.PORT
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf("probe", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000") + startedAndOff,
            phone.did,
        )
    }

    /** Row 11: a revive that fails leaves it as the revive left it, for the
     *  health reading and the next try. */
    @Test fun aReviveThatFailsLeavesItOn() = runTest {
        val phone = FakePhone().apply { wireless = false }

        assertFalse(revive(phone))

        assertEquals(listOf("probe", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000"), phone.did)
        assertTrue(phone.wireless)
        assertTrue(phone.jemRecs)
    }

    /** Row 12: Start without Wi-Fi switched on: the port opened over it, and
     *  off at the revive's end. */
    @Test fun aPortChangeEndsWithItOff() = runTest {
        val phone = FakePhone().apply {
            wireless = false
            step = PortStep.OPEN
            connects += listOf(ConnectOutcome.CONNECTED, ConnectOutcome.CONNECTED)
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf(
                "probe", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000",
                "restart tcpip:5555", "close", "note opened true", "connect 5555",
            ) + startedAndOff,
            phone.did,
        )
        assertFalse(phone.wireless)
    }

    /** Row 15: the Honor's Developer options switched USB debugging off,
     *  adbd and the recorder gone: the shield first, then as row 5. */
    @Test fun afterDeveloperOptionsTheShieldThenOnThenOff() = runTest {
        val phone = FakePhone().apply {
            usb = GlobalSettings.Reading.OFF
            wireless = false
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf("adb_enabled=1", "probe", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000") +
                startedAndOff,
            phone.did,
        )
        assertFalse(phone.wireless)
    }

    /** Rows 17, 19, 20, 21: what the recorder answers decides whether the
     *  note goes - off already (LineageOS's own off), yes; USB debugging
     *  read off (the Honor's Settings in between), refused (OPPO and the
     *  like), or no answer (a recorder before 1.5), no: asked again at the
     *  next look, which finds the recorder up. */
    @Test fun onlyAnOffOrOffAlreadyForgetsTheNote() = runTest {
        for (answer in listOf(WirelessOff.ALREADY_OFF, WirelessOff.USB_OFF, WirelessOff.REFUSED, null)) {
            val phone = FakePhone().apply {
                recorder = up
                jemRecs = true
                wirelessOffAnswer = { answer }
            }

            assertTrue(revive(phone))

            val forgets = answer == WirelessOff.ALREADY_OFF
            assertEquals("$answer", forgets, !phone.jemRecs)
            assertTrue("$answer", "revive: Wireless debugging off - the recorder says ${answer ?: "nothing"}" in phone.log)

            // The next look asks again where the note stayed.
            phone.did.clear()
            phone.wirelessOffAnswer = null
            assertTrue(revive(phone))
            assertEquals("$answer", if (forgets) listOf("probe") else listOf("probe", "wireless off", "still up?", "forget jemrecs"), phone.did)
        }
    }

    /** Row 20: an older recorder is replaced first, and the new one asked. */
    @Test fun anOlderRecorderIsReplacedBeforeItIsAsked() = runTest {
        val phone = FakePhone().apply {
            recorder = DaemonClient.Answer(2, "00000000")
            currentBuild = false
            jemRecs = true
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(listOf("probe", "autoConnect 5000") + startedAndOff, phone.did)
    }

    /** Row 23: a call up when the off is due: left for the next look - the
     *  call's end has the keep-alive look again. */
    @Test fun aCallUpLeavesItForTheNextLook() = runTest {
        val phone = FakePhone().apply {
            recorder = up
            jemRecs = true
            call = true
        }

        assertTrue(revive(phone))

        assertEquals(listOf("probe"), phone.did)
        assertTrue(phone.wireless)
        assertEquals("revive: Wireless debugging left on: a call is up; asked at the next look", phone.log.last())

        phone.call = false
        phone.did.clear()
        assertTrue(revive(phone))
        assertEquals(listOf("probe", "wireless off", "still up?", "forget jemrecs"), phone.did)
    }

    /** An ADB session left open - the stand-down's one ping missed a
     *  recorder that is up - would be cut by the off, and kept, would be
     *  found open by every look after: closed first, then asked. */
    @Test fun aSessionLeftOpenIsClosedBeforeTheOff() = runTest {
        val phone = FakePhone().apply {
            wireless = false
            standDownMisses = true
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf("start", "push", "stand down", "close", "wireless off", "still up?", "forget jemrecs", "after start"),
            phone.did.takeLast(8),
        )
        assertFalse(phone.connected)
        assertFalse(phone.wireless)

        // And at a look that finds the recorder up, one left from before.
        phone.recorder = up
        phone.jemRecs = true
        phone.wireless = true
        phone.connected = true
        phone.did.clear()
        assertTrue(revive(phone))
        assertEquals(listOf("probe", "close", "wireless off", "still up?", "forget jemrecs"), phone.did)
    }

    /** Rows 3 and 41: on Android 12 and 13 a revive that finds it on, adbd
     *  running and nothing advertised - mdnsd stopped 10 s after the last
     *  look - switches it off and on once on this Wi-Fi, which announces it
     *  afresh; the user's stays the user's, and on. */
    @Test fun onAndroid12And13NothingFoundWithItOnIsSwitchedOffAndOnOnce() = runTest {
        val phone = FakePhone().apply {
            holdsMdns = true
            connects += listOf(ConnectOutcome.NOT_FOUND, ConnectOutcome.CONNECTED)
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf(
                "probe", "hold mdns", "autoConnect 5000", wifiOff, wifiOn, "forget cable", "note switched on",
                "autoConnect 5000", "release mdns",
            ) + started,
            phone.did,
        )
        assertTrue("revive: nothing advertised, mDNS may have stopped; switching off and on once on this Wi-Fi" in phone.log)
        assertTrue(phone.wireless)
        assertFalse(phone.jemRecs)

        // Once on this connection.
        phone.did.clear()
        phone.connected = false
        assertFalse(revive(phone))
        assertFalse(wifiOff in phone.did)
    }

    /** ...and not on Android 14 and later, where it stays announced: as
     *  before, nothing found with adbd running is left to the header. */
    @Test fun fromAndroid14NothingFoundWithAdbdRunningIsNotSwitched() = runTest {
        val phone = FakePhone()

        assertFalse(revive(phone))

        assertEquals(listOf("probe", "autoConnect 5000"), phone.did)
    }

    /** Row 41: Android 12 and 13 - the look held from before the switch-on to
     *  after the connect, so mdnsd is not stopped in between. */
    @Test fun onAndroid12And13TheLookIsHeldAcrossTheSwitchOn() = runTest {
        val phone = FakePhone().apply {
            holdsMdns = true
            wireless = false
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf(
                "probe", "hold mdns", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000", "release mdns",
            ) + startedAndOff,
            phone.did,
        )
    }

    /** ...and let go when the connect fails, as when it does not. */
    @Test fun aHeldLookIsLetGoWhenTheConnectFails() = runTest {
        val phone = FakePhone().apply {
            holdsMdns = true
            wireless = false
        }

        assertFalse(revive(phone))

        assertEquals("release mdns", phone.did.last())
    }

    /** ...and none off Wi-Fi, where the connect is on the port. */
    @Test fun offWifiNoLookIsHeld() = runTest {
        val phone = FakePhone().apply {
            holdsMdns = true
            wifi = false
            ready = true
            answers = true
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertFalse("hold mdns" in phone.did)
    }

    /** Row 18, the guard: on a phone where the off takes the recorder with
     *  it, the revive goes round once more - Wireless debugging on, the
     *  recorder started - and no off is asked on this phone again. */
    @Test fun anOffThatTakesTheRecorderIsNotMadeAgain() = runTest {
        val phone = FakePhone().apply {
            wireless = false
            offKillsRecorder = true
            connects += listOf(ConnectOutcome.CONNECTED, ConnectOutcome.CONNECTED)
        }

        assertTrue(revive(phone))

        assertEquals(
            listOf(
                "probe", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000",
                "start", "push", "stand down", "wireless off", "still up?", "note keeps adbd",
                "probe", wifiOn, "note switched on", "note jemrecs", "autoConnect 5000",
                "start", "push", "stand down", "after start",
            ),
            phone.did,
        )
        assertTrue(phone.wireless)
        assertTrue(phone.keepsAdbd)
        assertTrue("revive: the recorder went with Wireless debugging; it stays on on this phone from now" in phone.log)

        // And at the next look: left on.
        phone.recorder = up
        phone.did.clear()
        assertTrue(revive(phone))
        assertEquals(listOf("probe"), phone.did)
        assertEquals("revive: Wireless debugging left on: on this phone it keeps adbd up", phone.log.last())
    }

    /** ...and a recorder that went before it could answer - no answer - is
     *  read the same way. */
    @Test fun noAnswerAndNoRecorderAfterTheOffIsTheSame() = runTest {
        val phone = FakePhone().apply {
            recorder = up
            jemRecs = true
            offKillsRecorder = true
            wirelessOffAnswer = {
                wireless = false
                recorder = null
                null
            }
            connects += ConnectOutcome.CONNECTED
        }

        assertTrue(revive(phone))

        assertTrue(phone.keepsAdbd)
        assertEquals(listOf("probe", "wireless off", "still up?", "note keeps adbd", "probe"), phone.did.take(5))
        assertEquals("after start", phone.did.last())
    }

    /** Rows 30 and 37: a process that cannot switch it on again - setup's
     *  own, whose permission is live only from the next, or one whose
     *  permission was taken back - leaves it on and JemRec's, for a later
     *  look; the start after it then needs no switch-on. */
    @Test fun aProcessThatCannotSwitchItOnAgainLeavesItOn() = runTest {
        val phone = FakePhone().apply {
            recorder = up
            jemRecs = true
            canSwitchOn = false
        }

        assertTrue(revive(phone))

        assertEquals(listOf("probe"), phone.did)
        assertTrue(phone.wireless)
        assertTrue(phone.jemRecs)
        assertEquals(
            "revive: Wireless debugging left on: this process could not switch it on again; asked at a later look",
            phone.log.last(),
        )

        // A later process, with the permission: off.
        phone.canSwitchOn = true
        phone.did.clear()
        assertTrue(revive(phone))
        assertEquals(listOf("probe", "wireless off", "still up?", "forget jemrecs"), phone.did)
    }

    @Test fun whatHoldsTheOffBack() {
        for (jemRecs in listOf(true, false)) for (keeps in listOf(true, false))
            for (inCall in listOf(true, false)) for (can in listOf(true, false)) {
                val expected = when {
                    !jemRecs -> Revive.OffHeld.NOT_JEMRECS
                    keeps -> Revive.OffHeld.KEEPS_ADBD
                    inCall -> Revive.OffHeld.IN_CALL
                    !can -> Revive.OffHeld.CANNOT_SWITCH_ON
                    else -> null
                }
                assertEquals("$jemRecs $keeps $inCall $can", expected, Revive.wirelessOffHeld(jemRecs, keeps, inCall, can))
            }
    }
}
