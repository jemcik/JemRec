package com.jemcik.jemrec.recorder

import android.net.ConnectivityManager
import android.provider.Settings
import com.jemcik.jemrec.adb.AdbTransport.ConnectOutcome
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.system.GlobalSettings
import org.junit.Assert.assertEquals
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
 * What the recorder's revives tell the header after Android refused Wireless
 * debugging on this Wi-Fi, as on the Android 16 AVD, 2026-11-03: the switch
 * read off, and mDNS still handing out the port of an earlier connection,
 * which turns each connect away at once (RecorderHealth.meant).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
@Suppress("DEPRECATION") // NetworkInfo: Robolectric's Wi-Fi, as in StartWithoutWifiStateTest
class RefusedWifiHealthTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before fun setUpOnWifi() {
        TestPhone.switchesOn(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true,
                android.net.NetworkInfo.State.CONNECTED,
            ),
        )
        assertTrue(NetworkRevive.onWifi(context))
    }

    private fun wireless(on: Boolean) =
        Settings.Global.putInt(context.contentResolver, GlobalSettings.ADB_WIFI_ENABLED, if (on) 1 else 0)

    @Test fun refusalsWithWirelessDebuggingRefusedHereAskToAllowTheWifiNotToPairAgain() {
        // Android refused it on this Wi-Fi: the switch off, the refusal noted.
        wireless(false)
        NetworkRevive.noteWirelessRefused(context)

        repeat(3) { RecorderHealth.onRevive(context, alive = false, connect = ConnectOutcome.REFUSED) }

        assertEquals(RecorderHealth.Problem.NETWORK_NOT_ALLOWED, RecorderHealth.cause(context))
    }

    @Test fun refusalsWithWirelessDebuggingOnAreStillThePairing() {
        wireless(true)

        repeat(2) { RecorderHealth.onRevive(context, alive = false, connect = ConnectOutcome.REFUSED) }

        assertEquals(RecorderHealth.Problem.CANNOT_CONNECT, RecorderHealth.cause(context))
    }
}
