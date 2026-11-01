package com.jemcik.jemrec.capture

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WIFI
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * What each trigger arms, as it is: which of the keep-alive's runs - the
 * heartbeat, a kick, the Wi-Fi run - the USB watch, and the system-held
 * Wi-Fi watch. The subsets differ by trigger, and on purpose or not they are
 * what the phones do today. The keep-alive's and the USB watch's own runs
 * are in their tests, RecorderSwitch.turn's in RecorderSwitchTest; the home
 * screen's check (MainViewModel.recheckSetup) is not here.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class ReviveTriggersTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork

    @Before fun aPhoneWithJemRecOn() {
        TestPhone.switchesOn(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
    }

    @After fun closeWorkManager() = work.close()

    private val wifiWatch = "the Wi-Fi watch"

    /** Every unique name with work under it, and the Wi-Fi watch if armed. */
    private fun armed(): Set<String> =
        HeldWork.ALL.filter { work.unique(it).isNotEmpty() }.toSet() +
            (if (TestPhone.wifiWatches(context).isNotEmpty()) setOf(wifiWatch) else emptySet())

    private fun kickInput() = work.inputOf(work.unique(NOW).single().id)

    private fun boot(action: String = Intent.ACTION_BOOT_COMPLETED) = BootReceiver().onReceive(context, Intent(action))

    @Test fun bootSetUpArmsTheHeartbeatTheWatchesAndAKick() {
        TestPhone.setUp(context)
        boot()
        assertEquals(setOf(PERIODIC, USB_WATCH, NOW, wifiWatch), armed())
        assertEquals(mapOf("for_wifi" to false), kickInput())
        // It only enqueues: the kick's run does the reviving.
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
    }

    @Test fun bootArmsWhateverTheSwitchSays() {
        // Tied to setup, not the switch: the recorder is kept for an On
        // without Wi-Fi.
        TestPhone.setUp(context)
        RecorderSwitch.set(context, false)
        boot()
        assertEquals(setOf(PERIODIC, USB_WATCH, NOW, wifiWatch), armed())
    }

    @Test fun bootNotSetUpArmsNothing() {
        boot()
        assertEquals(emptySet<String>(), armed())
    }

    @Test fun aLockedBootArmsNothing() {
        // The identity is still encrypted then; BOOT_COMPLETED follows.
        TestPhone.setUp(context)
        boot(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        assertEquals(emptySet<String>(), armed())
    }

    @Test fun wifiBackSetUpStartsTheWifiRunAndNothingElse() {
        TestPhone.setUp(context)
        NetworkReviveReceiver().onReceive(context, Intent("com.jemcik.jemrec.WIFI_BACK"))
        assertEquals(setOf(WIFI), armed())
        assertEquals(mapOf("for_wifi" to true), work.inputOf(work.unique(WIFI).single().id))
    }

    @Test fun wifiBackNotSetUpStartsNothing() {
        NetworkReviveReceiver().onReceive(context, Intent("com.jemcik.jemrec.WIFI_BACK"))
        assertEquals(emptySet<String>(), armed())
    }

    @Test fun wifiBackWhateverTheSwitchSays() {
        TestPhone.setUp(context)
        RecorderSwitch.set(context, false)
        NetworkReviveReceiver().onReceive(context, Intent("com.jemcik.jemrec.WIFI_BACK"))
        assertEquals(setOf(WIFI), armed())
    }

    @Test fun openingTheAppWithJemRecOnArmsTheHeartbeatTheUsbWatchAndAKick() {
        TestPhone.setUp(context)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        // Not the Wi-Fi watch: the screen's own check arms that.
        assertEquals(setOf(PERIODIC, USB_WATCH, NOW), armed())
        assertEquals(mapOf("for_wifi" to false), kickInput())
        activity.destroy()
    }

    @Test fun openingTheAppWithJemRecOffArmsNothing() {
        // Unlike boot and the watches, which ask setup and not the switch.
        TestPhone.setUp(context)
        RecorderSwitch.set(context, false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        assertEquals(emptySet<String>(), armed())
        activity.destroy()
    }

    @Test fun openingTheAppAsksTheSwitchNotSetup() {
        // Not set up, as after an install: armed all the same, and the
        // kick's run stops at its own setup gate.
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        assertEquals(setOf(PERIODIC, USB_WATCH, NOW), armed())
        activity.destroy()
    }

    @Test fun theWifiWatchIsOnePendingIntentToTheReceiver() {
        NetworkRevive.arm(context)
        NetworkRevive.arm(context)
        // Armed again, the same PendingIntent replaces the first.
        val watch: PendingIntent = TestPhone.wifiWatches(context).single()
        val shadow = shadowOf(watch)
        assertTrue(shadow.isBroadcast)
        assertEquals(0, shadow.requestCode)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, shadow.flags)
        assertEquals("com.jemcik.jemrec.WIFI_BACK", shadow.savedIntent.action)
        assertEquals(ComponentName(context, NetworkReviveReceiver::class.java), shadow.savedIntent.component)
    }
}
