package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each When JemRec is off choice switches off, and what that costs. */
class DebugSwitchesOffTest {

    @Test fun keepingDebuggingSwitchesNothing() {
        assertEquals(emptySet<DebugSwitch>(), OffMode.KEEP_DEBUGGING.switchesOff())
    }

    @Test fun hidingDeveloperOptionsKeepsUsbDebuggingAndSoTheRecorder() {
        val off = OffMode.HIDE_DEVELOPER_OPTIONS.switchesOff()
        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS, DebugSwitch.WIRELESS_DEBUGGING), off)
        // Measured on the Honor: with USB debugging on, the recorder lived
        // through both of these going off, off the cable, and a call.
        assertFalse(off.takesTheRecorder())
    }

    @Test fun allDebuggingOffTakesTheRecorderWithIt() {
        val off = OffMode.ALL_DEBUGGING_OFF.switchesOff()
        assertEquals(DebugSwitch.entries.toSet(), off)
        assertTrue(off.takesTheRecorder())
    }

    @Test fun onlyAllDebuggingOffAwayFromWifiAsksFirst() {
        // On needs Wi-Fi only once the recorder is gone, so that is the one
        // off worth a question - and only where there is no Wi-Fi.
        assertTrue(OffMode.ALL_DEBUGGING_OFF.asksFirst(onWifi = false) { false })
        assertFalse(OffMode.ALL_DEBUGGING_OFF.asksFirst(onWifi = true) { false })
        OffMode.entries.filter { it != OffMode.ALL_DEBUGGING_OFF }.forEach {
            assertFalse(it.asksFirst(onWifi = false) { false })
            assertFalse(it.asksFirst(onWifi = true) { false })
        }
    }

    @Test fun startWithoutWifisPortAsksNothing() {
        // adbd on its classic port comes back with no Wi-Fi (the Honor,
        // 2026-10-27), so there is nothing to warn of.
        assertFalse(OffMode.ALL_DEBUGGING_OFF.asksFirst(onWifi = false) { true })
    }

    @Test fun thePortIsAskedOnlyWhereItCanChangeTheAnswer() {
        // It starts getprop, from the tile's tap on the main thread.
        var asked = false
        OffMode.ALL_DEBUGGING_OFF.asksFirst(onWifi = true) { asked = true; true }
        OffMode.HIDE_DEVELOPER_OPTIONS.asksFirst(onWifi = false) { asked = true; true }
        assertFalse(asked)
    }

    @Test fun usbDebuggingComesBackBeforeWirelessDebugging() {
        // On the Honor, Wireless debugging only sticks while USB debugging is on.
        assertEquals(
            listOf(DebugSwitch.USB_DEBUGGING, DebugSwitch.WIRELESS_DEBUGGING, DebugSwitch.DEVELOPER_OPTIONS),
            DebugSwitch.entries.toSet().inOrderBackOn(),
        )
        assertEquals(
            listOf(DebugSwitch.WIRELESS_DEBUGGING, DebugSwitch.DEVELOPER_OPTIONS),
            OffMode.HIDE_DEVELOPER_OPTIONS.switchesOff().inOrderBackOn(),
        )
    }

    @Test fun eachSwitchIsTheSettingAndroidKeepsIt() {
        assertEquals("development_settings_enabled", DebugSwitch.DEVELOPER_OPTIONS.setting)
        assertEquals("adb_wifi_enabled", DebugSwitch.WIRELESS_DEBUGGING.setting)
        assertEquals("adb_enabled", DebugSwitch.USB_DEBUGGING.setting)
    }
}
