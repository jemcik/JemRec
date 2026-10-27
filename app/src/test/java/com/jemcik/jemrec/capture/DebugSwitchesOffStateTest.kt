package com.jemcik.jemrec.capture

import android.provider.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * What the last off switched off, and what On and a revive make of it
 * (DebugSwitchesOff) - against the real settings provider, with no recorder
 * to answer.
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
}
