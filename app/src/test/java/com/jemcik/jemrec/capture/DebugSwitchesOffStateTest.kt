package com.jemcik.jemrec.capture

import android.app.NotificationManager
import android.provider.Settings
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * What the last off switched off, and what On and a revive make of it
 * (DebugSwitchesOff) - against the real settings provider, with no recorder
 * to answer. The revives at the end are CaptureDaemon.revive itself, on a
 * phone set up and off Wi-Fi: one that gets past its gate probes, finds
 * nothing and stops at "not on Wi-Fi" (about 2.5 s, TestPhone), having
 * written at most armShield's USB debugging, and never connects.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class DebugSwitchesOffStateTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun put(name: String, value: Int) = Settings.Global.putInt(context.contentResolver, name, value)
    private fun get(name: String) = Settings.Global.getInt(context.contentResolver, name, -1)

    private val dev = GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED
    private val wifi = GlobalSettings.ADB_WIFI_ENABLED
    private val usb = GlobalSettings.ADB_ENABLED

    @Before fun switchesOnAsAPhoneSetUp() {
        put(dev, 1)
        put(wifi, 1)
        put(usb, 1)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
    }

    @Test fun onlyWhatWasOnIsRecordedAndOnPutsBackExactlyThat() = runBlocking {
        // Away from Wi-Fi: Wireless debugging already off when JemRec goes.
        put(wifi, 0)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS), DebugSwitchesOff.switchedOff(context))
        assertEquals(0, get(dev))
        assertEquals(1, get(usb))
        DebugSwitchesOff.switchBackOn(context)
        assertEquals(1, get(dev))
        // Not switched on for nothing: Android would ask about the network.
        assertEquals(0, get(wifi))
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
    }

    @Test fun aReviveWhileOffTurnsWirelessDebuggingOffAgainAndRemembersIt() = runBlocking {
        put(wifi, 0)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        // A reboot later, the revive switched it on to start the recorder.
        put(wifi, 1)
        DebugSwitchesOff.afterRecorderStarted(context)
        assertEquals(0, get(wifi))
        assertTrue(DebugSwitch.WIRELESS_DEBUGGING in DebugSwitchesOff.switchedOff(context))
        // Now it was switched off by JemRec, so On puts it back.
        DebugSwitchesOff.switchBackOn(context)
        assertEquals(1, get(wifi))
    }

    @Test fun anOnCutShortIsNotTakenForAnOff() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        val off = DebugSwitchesOff.switchedOff(context)
        // On saved, the process gone before the switches came back; the
        // revive switches Wireless debugging on to start the recorder.
        RecorderSwitch.set(context, true)
        put(wifi, 1)
        DebugSwitchesOff.afterRecorderStarted(context)
        // Left on: the revive puts the rest back, not off again.
        assertEquals(1, get(wifi))
        assertEquals(off, DebugSwitchesOff.switchedOff(context))
    }

    @Test fun aTurnTheSwitchHasMovedOnFromIsDropped() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        // An On that waited its turn behind an off made after it.
        RecorderSwitch.turn(context, true)
        assertEquals(0, get(usb))
        assertTrue(DebugSwitchesOff.standingDown(context))
    }

    @Test fun allDebuggingOffStandsDownOnlyWhileJemRecIsOff() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        // No recorder answers here, so the app writes the switches itself.
        DebugSwitchesOff.switchOff(context)
        assertEquals(0, get(usb))
        assertEquals(0, get(wifi))
        assertTrue(DebugSwitchesOff.recorderGone(context))
        assertTrue(DebugSwitchesOff.standingDown(context))
        // The switch saved on, the process gone before the switches came back:
        // not standing down, or JemRec would say on and never record.
        RecorderSwitch.set(context, true)
        assertTrue(DebugSwitchesOff.recorderGone(context))
        assertFalse(DebugSwitchesOff.standingDown(context))
    }

    @Test fun usbDebuggingTheUserSwitchesOnWhileOffIsTheirs() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(0, get(usb))
        // The user plugs into a computer to debug something of their own.
        put(usb, 1)
        DebugSwitchesOff.finishOffLeftUndone(context)
        assertEquals(1, get(usb))
    }

    @Test fun anOffTheRecorderNeverFinishedIsFinishedOnce() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        // As if it had said "OFF AFTER CALL" and died in the call.
        put(usb, 1)
        put(wifi, 1)
        DebugSwitchesOff.notePending(context, true)
        DebugSwitchesOff.finishOffLeftUndone(context)
        assertEquals(0, get(usb))
        assertEquals(0, get(wifi))
        // Done once: USB debugging on after that is the user's.
        put(usb, 1)
        DebugSwitchesOff.finishOffLeftUndone(context)
        assertEquals(1, get(usb))
    }

    @Test fun anOffSeenFinishedIsNotFinishedAgainLater() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        // Pending, and the recorder did finish it: USB debugging is off.
        DebugSwitchesOff.notePending(context, true)
        DebugSwitchesOff.finishOffLeftUndone(context)
        put(usb, 1)
        DebugSwitchesOff.finishOffLeftUndone(context)
        assertEquals(1, get(usb))
    }

    @Test fun startFreshPutsDeveloperOptionsBack() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(0, get(dev))
        DebugSwitchesOff.forget(context)
        assertEquals(1, get(dev))
        assertEquals(OffMode.KEEP_DEBUGGING, DebugSwitchesOff.mode(context))
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
    }

    private val stoodDown = "revive: debugging is off with JemRec; nothing to do until it is on"

    private fun aPhoneSetUpOffWifi() {
        TestPhone.setUp(context)
        TestPhone.offWifi(context)
        TestPhone.clearReviveLog(context)
    }

    /** RecorderHealth's prefs (its keys are private there). */
    private fun health(): Map<String, Any?> = Prefs.of(context).all.filterKeys {
        it in setOf("recorder_down_since", "recorder_down_alerted", "recorder_down_cause", "adb_refused_in_a_row")
    }

    @Test fun aReviveWhileStayingOffStandsDownAndWritesNothing() = runBlocking {
        aPhoneSetUpOffWifi()
        // The recorder had been down past the grace, its alert up, when the
        // user turned JemRec off with all debugging.
        Prefs.of(context).edit().putLong("recorder_down_since", System.currentTimeMillis() - 3 * 60_000L).apply()
        RecorderHealth.onRevive(context, alive = false, connect = AdbTransport.ConnectOutcome.NONE)
        val alerts = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNotNull(alerts.getNotification(34))
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        val before = health()
        assertEquals(RecorderHealth.Problem.NEEDS_WIFI.name, before["recorder_down_cause"])
        TestPhone.clearReviveLog(context)
        val writes = TestPhone.Writes(context)

        assertFalse(CaptureDaemon.revive(context))

        // Not one switch written - armShield would have put USB debugging
        // back on - and the lock never taken: no "revive: start", no probe.
        assertEquals(emptyList<String>(), writes.seen())
        assertEquals(listOf(0, 0, 0), listOf(dev, wifi, usb).map(::get))
        assertEquals(listOf(stoodDown), TestPhone.reviveLog(context))
        // Not a failure, so RecorderHealth is not told.
        // Pins today's behaviour, which looks like a bug: the recorder-down
        // alert and its cause, from before the Off, outlive it - only a
        // revive's onRevive clears them, and every revive now stands down
        // before it (to be fixed in its own commit)
        assertEquals(before, health())
        assertNotNull(alerts.getNotification(34))
        writes.close()
    }

    @Test fun aReviveWhileStayingOffFinishesAnOffTheRecorderLeftUndone() = runBlocking {
        aPhoneSetUpOffWifi()
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        // As if it had said "OFF AFTER CALL" and died in the call.
        put(usb, 1)
        put(wifi, 1)
        DebugSwitchesOff.notePending(context, true)
        TestPhone.clearReviveLog(context)
        val writes = TestPhone.Writes(context)

        assertFalse(CaptureDaemon.revive(context))

        // The gate writes as it answers (stayingOff): Wireless debugging, then
        // USB debugging, once a confirmed probe finds no recorder.
        assertEquals(listOf(wifi, usb), writes.seen())
        assertEquals(
            listOf("debugging off: finished what the recorder left undone", stoodDown),
            TestPhone.reviveLog(context),
        )
        writes.close()
    }

    @Test fun aReviveWithJemRecOnPutsTheRecordBackUsbFirstBeforeItsLock() = runBlocking {
        aPhoneSetUpOffWifi()
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(DebugSwitch.entries.toSet(), DebugSwitchesOff.switchedOff(context))
        // On saved, the process gone before the switches came back.
        RecorderSwitch.set(context, true)
        TestPhone.clearReviveLog(context)
        val writes = TestPhone.Writes(context)

        assertFalse(CaptureDaemon.revive(context))

        // USB debugging first, which Wireless debugging needs on the Honor;
        // Developer options last. armShield then finds USB debugging on and
        // writes nothing more.
        assertEquals(listOf(usb, wifi, dev), writes.seen())
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
        val log = TestPhone.reviveLog(context)
        assertEquals(
            listOf(
                "revive: JemRec is on with debugging still recorded off; putting it back",
                "debugging back on: USB_DEBUGGING, WIRELESS_DEBUGGING, DEVELOPER_OPTIONS",
                "revive: start",
            ),
            log.take(3),
        )
        assertTrue("revive: not on Wi-Fi" in log)
        assertEquals("revive: recorder down (connect NONE)", log.last())
        writes.close()
    }

    @Test fun aReviveQueuedBeforeAnOffStillPutsUsbDebuggingBackOn() = runBlocking {
        aPhoneSetUpOffWifi()
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        // One revive holds the lock, probing a recorder that is not there
        // (about 2.4 s)...
        val first = async(Dispatchers.IO) { CaptureDaemon.revive(context) }
        withTimeout(5_000) { while ("revive: start" !in TestPhone.reviveLog(context)) delay(20) }
        // ...a second is past the staying-off gate - a few preference reads -
        // and waits for the lock. Nothing shows from outside when it is past,
        // so a second is given: the first holds the lock for at least 2.4 s
        // (its probe's four 600 ms gaps), which leaves both sides a second's
        // margin or more...
        val second = async(Dispatchers.IO) { CaptureDaemon.revive(context) }
        delay(1_000)
        // ...when JemRec goes off with all debugging.
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(0, get(usb))
        assertTrue(DebugSwitchesOff.standingDown(context))

        assertFalse(first.await())
        assertFalse(second.await())

        // Pins today's behaviour, which looks like a bug: the second revive
        // asked about staying off before the lock and not again under it, so
        // its armShield has USB debugging back on under a JemRec that is off
        // with all debugging - on Wi-Fi it would go on to switch Wireless
        // debugging on and start the recorder (to be fixed in its own commit)
        assertEquals(1, get(usb))
        assertTrue(DebugSwitchesOff.standingDown(context))
        assertEquals(2, TestPhone.reviveLog(context).count { it == "revive: start" })
    }
}
