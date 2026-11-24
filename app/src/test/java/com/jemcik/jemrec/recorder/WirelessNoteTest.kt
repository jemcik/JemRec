package com.jemcik.jemrec.recorder

import android.net.ConnectivityManager
import android.provider.Settings
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Whose Wireless debugging it is (NetworkRevive.noteJemRecs), one note not
 * tied to a Wi-Fi connection or a boot, and what When JemRec is off makes
 * of it (DebugSwitchesOff):
 * one JemRec switched on is never recorded for On to put back. Against the
 * real settings provider, on Robolectric's Wi-Fi, with no recorder to
 * answer - so an off with all debugging is written by the app.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
@Suppress("DEPRECATION") // NetworkInfo: Robolectric's Wi-Fi, as in RefusedWifiHealthTest
class WirelessNoteTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun put(name: String, value: Int) = Settings.Global.putInt(context.contentResolver, name, value)
    private fun get(name: String) = Settings.Global.getInt(context.contentResolver, name, -1)

    private val dev = GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED
    private val wifi = GlobalSettings.ADB_WIFI_ENABLED
    private val usb = GlobalSettings.ADB_ENABLED

    private fun onWifi(on: Boolean) {
        if (!on) return TestPhone.offWifi(context)
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true,
                android.net.NetworkInfo.State.CONNECTED,
            ),
        )
        assertTrue(NetworkRevive.onWifi(context))
    }

    @Before fun setUpOnWifi() {
        TestPhone.switchesOn(context)
        put(dev, 1)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        NetworkRevive.forgetJemRecs(context)
        onWifi(true)
        assertTrue(NetworkRevive.onWifi(context))
    }

    /** One note, not one per Wi-Fi connection or boot: Android 17 keeps
     *  the switch on across Wi-Fi changes and restarts (review round 1), so
     *  it outlives both, and goes only when forgotten. */
    @Test fun aNoteOutlivesARestartAndAWifiChange() {
        NetworkRevive.noteJemRecs(context)
        assertTrue(NetworkRevive.jemRecs(context))

        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 41)
        assertTrue("gone with a restart", NetworkRevive.jemRecs(context))
        onWifi(false)
        assertTrue("gone off Wi-Fi", NetworkRevive.jemRecs(context))
        onWifi(true)
        assertTrue("gone with a new join", NetworkRevive.jemRecs(context))

        NetworkRevive.forgetJemRecs(context)
        assertFalse(NetworkRevive.jemRecs(context))
    }

    /** Row 4: an install from before 1.5 takes it over once, on Wi-Fi or
     *  off it; one set up with 1.5 (markTakenOver at setup's end) never
     *  does. */
    @Test fun anOlderInstallTakesItOverOnce() {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        prefs.edit().remove("wireless_debugging_taken_over").commit()

        onWifi(false)
        NetworkRevive.takeOverOnce(context)
        assertTrue(NetworkRevive.jemRecs(context))

        NetworkRevive.forgetJemRecs(context)
        onWifi(true)
        NetworkRevive.takeOverOnce(context)
        assertFalse("taken over twice", NetworkRevive.jemRecs(context))

        prefs.edit().remove("wireless_debugging_taken_over").commit()
        NetworkRevive.markTakenOver(context)
        NetworkRevive.takeOverOnce(context)
        assertFalse("taken over after setup's end", NetworkRevive.jemRecs(context))
    }

    /** An open with the recorder answering marks setup complete again
     *  (Setup.decide), for installs from before the flag: it must not use
     *  up an update's takeover (review round 2). */
    @Test fun anOpenDoesNotUseUpTheTakeover() {
        com.jemcik.jemrec.Prefs.of(context).edit().remove("wireless_debugging_taken_over").commit()

        com.jemcik.jemrec.setup.Setup.markComplete(context)
        NetworkRevive.takeOverOnce(context)

        assertTrue(NetworkRevive.jemRecs(context))
    }

    /** 1.4's When JemRec is off record held Wireless debugging for On to
     *  put back - kept on by 1.4, JemRec's now: taken over too, so On does
     *  not put it back for good with no note (review round 2). */
    @Test fun theTakeoverTakesOverTheRecordToo() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS, DebugSwitch.WIRELESS_DEBUGGING), DebugSwitchesOff.switchedOff(context))
        com.jemcik.jemrec.Prefs.of(context).edit().remove("wireless_debugging_taken_over").commit()

        NetworkRevive.takeOverOnce(context)

        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS), DebugSwitchesOff.switchedOff(context))
        RecorderSwitch.set(context, true)
        DebugSwitchesOff.switchBackOn(context)
        assertEquals(1, get(dev))
        assertEquals(0, get(wifi))
    }

    /** A 1.4 install paired again before its first 1.5 revive: setup's end
     *  takes over, the record included, not only marks it (review round 3). */
    @Test fun setupsEndTakesOverTheRecordOfAnOlderInstall() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        com.jemcik.jemrec.Prefs.of(context).edit().remove("wireless_debugging_taken_over").commit()

        RecorderLifecycle.afterSetup(context)

        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS), DebugSwitchesOff.switchedOff(context))
        assertTrue(NetworkRevive.jemRecs(context))
    }

    /** The guard's count: two offs in a row that took the recorder keep
     *  Wireless debugging on this phone, across a restart of it and off
     *  Wi-Fi - the first alone does not - until an off it survives or Start
     *  fresh forgets it. */
    @Test fun twoLossesInARowKeepItOnUntilForgotten() {
        assertFalse(NetworkRevive.wirelessKeepsAdbd(context))
        assertFalse("one is not enough", NetworkRevive.noteOffLoss(context))
        assertFalse(NetworkRevive.wirelessKeepsAdbd(context))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 42)
        onWifi(false)
        assertTrue("the second in a row", NetworkRevive.noteOffLoss(context))
        assertTrue(NetworkRevive.wirelessKeepsAdbd(context))
        NetworkRevive.forgetOffLosses(context)
        assertFalse(NetworkRevive.wirelessKeepsAdbd(context))
        assertFalse("from the start", NetworkRevive.noteOffLoss(context))
    }

    /** The real phone's world keeps the guard's count in NetworkRevive: the
     *  stand-in's own copy of the count would not show a world miswired. */
    @Test fun theRealWorldKeepsTheGuardsCount() {
        val w = ReviveWorld.Live(context)
        assertFalse(w.keepsAdbd())
        assertFalse("once", w.noteOffLoss())
        assertFalse(w.keepsAdbd())
        assertTrue("twice", w.noteOffLoss())
        assertTrue(w.keepsAdbd())
        assertTrue("another revive's world reads the same", ReviveWorld.Live(context).keepsAdbd())
        w.forgetOffLosses()
        assertFalse(w.keepsAdbd())
    }

    /** Row 28: 1.5's and 1.6's note of one loss, for good, is not read: a
     *  phone it was set on gets the off back with 1.7. */
    @Test fun theOneLossNoteOf15IsNotRead() {
        com.jemcik.jemrec.Prefs.of(context).edit().putBoolean("wireless_debugging_keeps_adbd", true).commit()
        assertFalse(NetworkRevive.wirelessKeepsAdbd(context))
    }

    /** Row 26: Off with Developer options hidden, Wireless debugging still on
     *  and JemRec's (the recorder's off not done yet): switched off, and not
     *  recorded - On does not switch it on for nothing. */
    @Test fun hideDeveloperOptionsSwitchesJemRecsOffWithoutRecordingIt() = runBlocking {
        NetworkRevive.noteJemRecs(context)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)

        DebugSwitchesOff.switchOff(context)

        assertEquals(0, get(wifi))
        assertEquals(0, get(dev))
        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS), DebugSwitchesOff.switchedOff(context))

        RecorderSwitch.set(context, true)
        DebugSwitchesOff.switchBackOn(context)
        assertEquals(1, get(dev))
        assertEquals(0, get(wifi))
    }

    /** Row 26: the user's - no note - recorded as today, and put back. */
    @Test fun hideDeveloperOptionsRecordsTheUsersAndOnPutsItBack() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)

        DebugSwitchesOff.switchOff(context)

        assertEquals(0, get(wifi))
        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS, DebugSwitch.WIRELESS_DEBUGGING), DebugSwitchesOff.switchedOff(context))

        RecorderSwitch.set(context, true)
        DebugSwitchesOff.switchBackOn(context)
        assertEquals(1, get(wifi))
    }

    /** Row 26: only Wireless debugging on, and JemRec's: written off, and an
     *  empty record - nothing for On to put back. */
    @Test fun hideDeveloperOptionsWithOnlyJemRecsOnRecordsNothing() = runBlocking {
        put(dev, 0)
        NetworkRevive.noteJemRecs(context)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)

        DebugSwitchesOff.switchOff(context)

        assertEquals(0, get(wifi))
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
    }

    /** Row 27: a revive while off with Developer options hidden switched it
     *  on to start the recorder: the recorder's off, not this one's, and
     *  not recorded. */
    @Test fun afterAStartWhileOffJemRecsIsLeftToTheRecorder() = runBlocking {
        put(wifi, 0)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        val off = DebugSwitchesOff.switchedOff(context)
        // A restart later, the revive switched it on, as JemRec's.
        put(wifi, 1)
        NetworkRevive.noteJemRecs(context)

        DebugSwitchesOff.afterRecorderStarted(context)

        assertEquals(1, get(wifi))
        assertEquals(off, DebugSwitchesOff.switchedOff(context))
    }

    /** Row 27: one found on that is not JemRec's: off and recorded, as today. */
    @Test fun afterAStartWhileOffTheUsersGoesOffAndIsRecorded() = runBlocking {
        put(wifi, 0)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        put(wifi, 1)

        DebugSwitchesOff.afterRecorderStarted(context)

        assertEquals(0, get(wifi))
        assertTrue(DebugSwitch.WIRELESS_DEBUGGING in DebugSwitchesOff.switchedOff(context))
    }

    /** Row 28: Turn all debugging off with JemRec's on: all three off, and
     *  On puts back USB debugging and Developer options - Wireless debugging
     *  is the revive's to switch on, and off again. */
    @Test fun allDebuggingOffDoesNotRecordJemRecs() = runBlocking {
        NetworkRevive.noteJemRecs(context)
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)

        DebugSwitchesOff.switchOff(context)

        assertEquals(0, get(usb))
        assertEquals(0, get(wifi))
        assertEquals(setOf(DebugSwitch.DEVELOPER_OPTIONS, DebugSwitch.USB_DEBUGGING), DebugSwitchesOff.switchedOff(context))

        RecorderSwitch.set(context, true)
        DebugSwitchesOff.switchBackOn(context)
        assertEquals(1, get(usb))
        assertEquals(1, get(dev))
        assertEquals(0, get(wifi))
    }

    @Test fun theRecordLeavesOutOnlyJemRecsWireless() {
        val all = DebugSwitch.entries.toSet()
        assertEquals(all, all.toRecord(jemRecsWireless = false))
        assertEquals(all - DebugSwitch.WIRELESS_DEBUGGING, all.toRecord(jemRecsWireless = true))
        assertEquals(emptySet<DebugSwitch>(), setOf(DebugSwitch.WIRELESS_DEBUGGING).toRecord(jemRecsWireless = true))
    }
}
