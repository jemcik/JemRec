package com.jemcik.jemrec.recorder

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.provider.Settings
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.system.SystemProperty
import com.jemcik.jemrec.recorder.StartWithoutWifi.PortStep
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File

/**
 * When a revive may change Start without Wi-Fi's port (CaptureDaemon
 * .portChangeDue), and what the screens take for an open port
 * (StartWithoutWifi.ready) - against the real settings provider. There is no
 * getprop on the JVM, so the property cannot be read here: the case of a build
 * that denies apps the read.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class StartWithoutWifiStateTest {

    private val context = RuntimeEnvironment.getApplication()
    private val identity = listOf("adb_private_key.der", "adb_certificate.der")

    @Before fun aPhoneSetUpWithItsSwitchOn() {
        listOf(GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED, GlobalSettings.ADB_WIFI_ENABLED, GlobalSettings.ADB_ENABLED)
            .forEach { Settings.Global.putInt(context.contentResolver, it, 1) }
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        Setup.markComplete(context)
        identity.forEach { File(context.filesDir, it).writeText("set up") }
        DebugSwitchesOff.forget(context)
        StartWithoutWifi.forget(context, stillOpen = false)
        RecorderSwitch.set(context, true)
        StartWithoutWifi.setEnabled(context, true)
    }

    /** JemRec's own number picked, and asked of adbd this boot: what a
     *  revive's arm leaves (PortChange.changePort). */
    private fun armed() {
        StartWithoutWifi.ensureOwnPort(context) { false }
        StartWithoutWifi.noteArmed(context)
    }

    @Test fun switchedOnOnWifiThePortIsOpened() {
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun notWhileWhenOffHasDebuggingSwitchedOff() = runBlocking {
        // Opening it would have the revive switch Wireless debugging on while
        // JemRec is off with Developer options hidden - for a bank - and
        // leave it on if the start then failed.
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
        // On puts debugging back, and the change is made after it.
        RecorderSwitch.set(context, true)
        DebugSwitchesOff.switchBackOn(context)
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun notWhereAndroidRefusedWirelessDebuggingAndItIsOff() {
        // Android 12 keeps the switch off after Cancel, and each switch-on for
        // the port asked again (the 12 AVD, 2026-10-28). On Robolectric's
        // Wi-Fi network: its default, mobile, has id 0 and so no handle.
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED,
            ),
        )
        assertFalse(NetworkRevive.wirelessRefusedHere(context))
        NetworkRevive.noteWirelessRefused(context)
        assertTrue(NetworkRevive.wirelessRefusedHere(context))
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_WIFI_ENABLED, 0)
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
        // Allowed after all - Wireless debugging on - the change goes ahead,
        // and the refusal is forgotten: off again later on this connection
        // (an adbd restart), it is switched on as anywhere else.
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_WIFI_ENABLED, 1)
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeDue(context, onWifi = true))
        assertFalse(NetworkRevive.wirelessRefusedHere(context))
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_WIFI_ENABLED, 0)
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeDue(context, onWifi = true))
        NetworkRevive.noteWirelessRefused(context)
        // The switch flipped by hand asks again (MainViewModel): its user is
        // there to answer.
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_WIFI_ENABLED, 0)
        NetworkRevive.forgetWirelessRefused(context)
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeDue(context, onWifi = true))
        NetworkRevive.noteWirelessRefused(context)
        // After a restart the same network id is a new connection: network
        // ids start again at each boot.
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_WIFI_ENABLED, 0)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertFalse(NetworkRevive.wirelessRefusedHere(context))
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun aWifiThatIsNotTheDefaultNetworkIsStillWifi() {
        // Robolectric's default network is its mobile one, with its Wi-Fi
        // joined beside it: a Wi-Fi without internet, which Wireless
        // debugging works on (the Android 12 AVD, 2026-10-28, said "not on
        // Wi-Fi" there when the default network was asked).
        val cm = context.getSystemService(ConnectivityManager::class.java)
        assertFalse(cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        assertTrue(NetworkRevive.onWifi(context))
        // No Wi-Fi joined at all: not on Wi-Fi.
        shadowOf(cm).clearAllNetworks()
        assertFalse(NetworkRevive.onWifi(context))
    }

    @Test fun notAwayFromWifiNorBeforeSetUp() {
        // The recorder could not be started again at once.
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = false))
        File(context.filesDir, identity.last()).delete()
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun switchedOffWithNothingOpenedThereIsNothingToDo() {
        StartWithoutWifi.setEnabled(context, false)
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun aPropertyThatCannotBeReadFallsBackOnThisBootsOpen() {
        assertFalse(StartWithoutWifi.ready(context))
        armed()
        assertTrue(StartWithoutWifi.ready(context))
        // ...and is not opened again at every revive, each one a restart of
        // the recorder.
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
        // Switched off, the one it opened is closed.
        StartWithoutWifi.setEnabled(context, false)
        assertEquals(PortStep.CLOSE, CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun whatTheScreensSayOfAPortThatCannotBeReadIsWhetherItAnswers() {
        // Opened this boot, then perhaps closed by a computer's `adb usb`,
        // which no property read here can show: with adbd running (USB
        // debugging on), the port answering is what says it - else turning
        // all debugging off away from Wi-Fi would go ahead unasked.
        armed()
        assertTrue(StartWithoutWifi.readyForUser(context) { true })
        assertFalse(StartWithoutWifi.readyForUser(context) { false })
        // adbd stopped (all debugging off): nothing answers anyway, and this
        // boot's open is all there is to go on, as for the revive.
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_ENABLED, 0)
        assertTrue(StartWithoutWifi.readyForUser(context) { false })
        // Never opened: answering or not, not JemRec's to count on.
        StartWithoutWifi.forget(context, stillOpen = false)
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_ENABLED, 1)
        assertFalse(StartWithoutWifi.readyForUser(context) { true })
    }

    @Test fun aResetThatCouldNotCloseItKeepsItJemRecsToClose() {
        armed()
        StartWithoutWifi.forget(context, stillOpen = true)
        assertFalse(StartWithoutWifi.enabled(context))
        assertEquals(PortStep.CLOSE, CaptureDaemon.portChangeDue(context, onWifi = true))
        StartWithoutWifi.forget(context, stillOpen = false)
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun theNumberIsPickedOnceAndKept() {
        assertNull(StartWithoutWifi.ownPort(context))
        val picked = StartWithoutWifi.ensureOwnPort(context) { false }
        assertTrue(picked in StartWithoutWifi.OWN_PORTS)
        // Asked again - something answering on it now or not - the same one.
        assertEquals(picked, StartWithoutWifi.ensureOwnPort(context) { true })
        // Start fresh keeps it: no secret, and the port it names may be open.
        StartWithoutWifi.forget(context, stillOpen = false)
        assertEquals(picked, StartWithoutWifi.ownPort(context))
        // Switched off with no port of JemRec's this boot: nothing to close.
        StartWithoutWifi.setEnabled(context, false)
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun a5555That110OpenedThisBootIsStillJemRecsToClose() {
        // Updated from 1.10 with its port open: its note, written by 1.10
        // and read since 1.11, until the restart that clears the port.
        com.jemcik.jemrec.Prefs.of(context).edit().putInt("port_opened_boot", 7).commit()
        assertTrue(StartWithoutWifi.ready(context))
        StartWithoutWifi.setEnabled(context, false)
        assertEquals(PortStep.CLOSE, CaptureDaemon.portChangeDue(context, onWifi = true))
        // Closed: forgotten with JemRec's own.
        StartWithoutWifi.noteClosed(context)
        assertFalse(StartWithoutWifi.ready(context))
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
        // An earlier boot's says nothing.
        com.jemcik.jemrec.Prefs.of(context).edit().putInt("port_opened_boot", 6).commit()
        assertFalse(StartWithoutWifi.ready(context))
    }

    @Test fun anOpenThatFailedLeavesNoPortOfJemRecs() {
        // The property reads empty after every restart until the first open -
        // "cannot be read" - so a note of the ask left behind would count as
        // an open port: revives off Wi-Fi starting over nothing, a switch-off
        // restarting adbd to close nothing (review round 1 of 1.11).
        armed()
        assertTrue(StartWithoutWifi.ready(context))
        StartWithoutWifi.noteOpenFailed(context)
        assertFalse(StartWithoutWifi.ready(context))
        assertFalse(StartWithoutWifi.readyForUser(context) { true })
        assertTrue(StartWithoutWifi.failedThisBoot(context))
        // Not asked again this boot, and nothing to close when switched off.
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
        StartWithoutWifi.setEnabled(context, false)
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @Test fun aNewOpenForgetsACloseThatDidNotTake() {
        // A close slow past its wait is noted as failed, and the port closes
        // after all. Switched on again, JemRec opens its port anew - and the
        // old note must not keep that one from being closed when switched off
        // (review round 2 of 1.11).
        armed()
        StartWithoutWifi.noteCloseFailed(context)
        assertTrue(StartWithoutWifi.closeFailedThisBoot(context))
        StartWithoutWifi.noteArmed(context)
        assertFalse(StartWithoutWifi.closeFailedThisBoot(context))
        StartWithoutWifi.setEnabled(context, false)
        assertEquals(PortStep.CLOSE, CaptureDaemon.portChangeDue(context, onWifi = true))
    }

    @After fun getpropAsItWas() {
        StartWithoutWifi.readProperty = SystemProperty::read
    }

    /** The phone's properties, as getprop prints them; unlisted, empty. */
    private fun properties(vararg values: Pair<String, String>) {
        val map = values.toMap()
        StartWithoutWifi.readProperty = { map[it] ?: "" }
    }

    @Test fun theClassicPortsAreReadFromThePhonesProperties() {
        // A phone, no port set (empty after every restart, or "0"): none.
        properties("ro.kernel.qemu" to "0")
        assertEquals(emptyList<Int>(), PortChange.classicPorts(context))
        properties("service.adb.tcp.port" to "0")
        assertEquals(emptyList<Int>(), PortChange.classicPorts(context))
        // A port set: that one alone.
        properties("service.adb.tcp.port" to "62817")
        assertEquals(listOf(62817), PortChange.classicPorts(context))
        // persist.adb.tcp.port, adbd's own fallback while the other is empty.
        properties("persist.adb.tcp.port" to "5555")
        assertEquals(listOf(5555), PortChange.classicPorts(context))
        // An emulator, no port set: 5555, its adbd's default.
        properties("ro.kernel.qemu" to "1")
        assertEquals(listOf(5555), PortChange.classicPorts(context))
        properties("ro.boot.qemu" to "1", "service.adb.tcp.port" to "0")
        assertEquals(listOf(5555), PortChange.classicPorts(context))
    }

    @Test fun setupsConnectsDialByTheSameRule() {
        // autoConnect's default, which setup's, the pairing's and the
        // screen's connects take: no 5555 on a guess (review round 3).
        properties()
        assertEquals(emptyList<Int>(), AdbTransport.classicPortsFor(context, null))
        properties("service.adb.tcp.port" to "62817")
        assertEquals(listOf(62817), AdbTransport.classicPortsFor(context, null))
        // A caller's own list is its own.
        assertEquals(listOf(4242), AdbTransport.classicPortsFor(context, listOf(4242)))
    }

    @Test fun aGetpropThatDidNotAnswerIsNotTakenForAPhone() {
        // No answer at all: not "not an emulator" for the rest of the
        // process - asked again (review round 3 of 1.11).
        StartWithoutWifi.readProperty = { null }
        assertFalse(StartWithoutWifi.adbdOnDefaultPort())
        StartWithoutWifi.readProperty = { if (it == "ro.kernel.qemu") "1" else "" }
        assertTrue(StartWithoutWifi.adbdOnDefaultPort())
    }

    @Test fun oneGetpropThatDidNotAnswerKeepsNoNo() {
        // The Android 12+ AVD images: ro.kernel.qemu empty, ro.boot.qemu 1.
        // A loaded one whose second read misses its time says "no" this once,
        // and the next read asks again (review round 4 of 1.11). The
        // answers change under one seam, which keeps what was cached.
        var answers = mapOf("ro.kernel.qemu" to "", "ro.boot.qemu" to null)
        StartWithoutWifi.readProperty = { answers[it] }
        assertFalse(StartWithoutWifi.adbdOnDefaultPort())
        answers = mapOf("ro.kernel.qemu" to "", "ro.boot.qemu" to "1")
        assertTrue(StartWithoutWifi.adbdOnDefaultPort())
        // A "yes" is kept: an emulator stays one.
        answers = mapOf("ro.kernel.qemu" to null, "ro.boot.qemu" to null)
        assertTrue(StartWithoutWifi.adbdOnDefaultPort())
    }

    @Test fun aPhoneWhoseTwoPropertiesAnsweredIsKeptAsOne() {
        var answers = mapOf("ro.kernel.qemu" to "", "ro.boot.qemu" to "")
        StartWithoutWifi.readProperty = { answers[it] }
        assertFalse(StartWithoutWifi.adbdOnDefaultPort())
        answers = mapOf("ro.kernel.qemu" to "1", "ro.boot.qemu" to "1")
        assertFalse(StartWithoutWifi.adbdOnDefaultPort())
    }
}
