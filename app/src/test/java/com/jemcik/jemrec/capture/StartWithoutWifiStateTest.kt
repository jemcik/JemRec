package com.jemcik.jemrec.capture

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.provider.Settings
import com.jemcik.jemrec.capture.StartWithoutWifi.PortStep
import com.jemcik.jemrec.ui.Setup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
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
        StartWithoutWifi.noteOpened(context, opened = true)
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
        StartWithoutWifi.noteOpened(context, opened = true)
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
        StartWithoutWifi.noteOpened(context, opened = true)
        StartWithoutWifi.forget(context, stillOpen = true)
        assertFalse(StartWithoutWifi.enabled(context))
        assertEquals(PortStep.CLOSE, CaptureDaemon.portChangeDue(context, onWifi = true))
        StartWithoutWifi.forget(context, stillOpen = false)
        assertNull(CaptureDaemon.portChangeDue(context, onWifi = true))
    }
}
