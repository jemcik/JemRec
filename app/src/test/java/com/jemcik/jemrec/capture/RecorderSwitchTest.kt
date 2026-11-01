package com.jemcik.jemrec.capture

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.ui.RecorderTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The recorder switch (RecorderSwitch): what saving it does besides saving
 * it, and what [RecorderSwitch.turn] does to make the recorder agree - as
 * it is today. Off Wi-Fi with no recorder to answer, so an On's revive
 * takes about 2.5 s and finds the recorder down (TestPhone).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecorderSwitchTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork

    @Before fun aPhoneOffWifiWithJemRecOn() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.clearReviveLog(context)
        shadowOf(context).clearStartedServices()
        work = HeldWork(context)
    }

    @After fun closeWorkManager() = work.close()

    /**
     * What the tile is asked to look again for. TileService.requestListeningState
     * goes through StatusBarManager to the status bar service, which
     * Robolectric answers with nothing; a stand-in for that service, put
     * where StatusBarManager keeps it, hears the request.
     */
    private fun tileRequests(): List<ComponentName> {
        val heard = CopyOnWriteArrayList<ComponentName>()
        val service = Class.forName("com.android.internal.statusbar.IStatusBarService")
        val standIn = Proxy.newProxyInstance(service.classLoader, arrayOf(service)) { _, method, args ->
            if (method.name == "requestTileServiceListeningState") heard += args[0] as ComponentName
            null
        }
        ReflectionHelpers.setField(context.getSystemService(StatusBarManager::class.java), "mService", standIn)
        return heard
    }

    private val wifiWatch = "the Wi-Fi watch"

    private fun armed(): Set<String> =
        HeldWork.ALL.filter { work.unique(it).isNotEmpty() }.toSet() +
            (if (TestPhone.wifiWatches(context).isNotEmpty()) setOf(wifiWatch) else emptySet())

    private fun stopsSent(): List<Intent> = shadowOf(context).allStartedServices.filter {
        it.component == ComponentName(context, CallMonitorService::class.java)
    }

    @Test fun savingNotesTheCoverageEveryTimeEvenUnchanged() {
        val before = CoverageHistory.read(context).size
        val from = System.currentTimeMillis()
        RecorderSwitch.set(context, false)
        RecorderSwitch.set(context, false)
        RecorderSwitch.set(context, true)
        // One entry per save, a save of what it already was included.
        val history = CoverageHistory.read(context)
        assertEquals(before + 3, history.size)
        assertEquals(
            listOf(CoverageHistory.State.OFF, CoverageHistory.State.OFF, CoverageHistory.State.AUTOMATIC),
            history.takeLast(3).map { it.second },
        )
        // And the missed-call check counts from now.
        assertTrue(Prefs.of(context).getLong("calls_covered_since", 0L) >= from)
    }

    @Test fun savingAsksTheTileToLookAgainEveryTime() {
        val heard = tileRequests()
        RecorderSwitch.set(context, false)
        RecorderSwitch.set(context, false)
        RecorderSwitch.set(context, true)
        assertEquals(List(3) { ComponentName(context, RecorderTile::class.java) }, heard)
    }

    @Test fun anOffTheSwitchHasMovedOnFromIsDropped() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        val writes = TestPhone.Writes(context)
        // An Off that waited its turn behind an On made after it.
        RecorderSwitch.turn(context, false)
        assertEquals(emptyList<String>(), writes.seen())
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
        assertEquals(emptyList<Intent>(), stopsSent())
        writes.close()
    }

    @Test fun anOnTheSwitchHasMovedOnFromIsDropped() = runBlocking {
        TestPhone.setUp(context)
        RecorderSwitch.set(context, false)
        RecorderSwitch.turn(context, true)
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        assertEquals(emptySet<String>(), armed())
    }

    @Test fun offStopsTheCallServiceAndLeavesTheRecorderAndItsWatches() = runBlocking {
        TestPhone.setUp(context)
        RecorderKeepAlive.schedule(context)
        RecorderKeepAlive.kickNow(context)
        NetworkRevive.arm(context)
        val before = listOf(PERIODIC, USB_WATCH, NOW).associateWith { name -> work.unique(name).map { it.id to it.state } }
        val writes = TestPhone.Writes(context)

        RecorderSwitch.set(context, false)
        RecorderSwitch.turn(context, false)

        // The default choice keeps debugging: nothing written.
        assertEquals(emptyList<String>(), writes.seen())
        val stop = stopsSent().single()
        assertEquals(CallMonitorService.ACTION_STOP, stop.action)
        assertEquals(CaptureDaemon.token(context), stop.getStringExtra(CallMonitorService.EXTRA_TOKEN))
        // Soft: the keep-alive, the USB watch and the Wi-Fi watch stay armed,
        // as NetworkRevive's comment says of Off.
        assertEquals(before, listOf(PERIODIC, USB_WATCH, NOW).associateWith { name -> work.unique(name).map { it.id to it.state } })
        assertEquals(1, TestPhone.wifiWatches(context).size)
        writes.close()
    }

    @Test fun offWithAllDebuggingStopsTheServiceThenSwitchesThemOff() = runBlocking {
        TestPhone.setUp(context)
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        val writes = TestPhone.Writes(context)

        RecorderSwitch.set(context, false)
        RecorderSwitch.turn(context, false)

        assertEquals(1, stopsSent().size)
        // Developer options by the app; then, no recorder answering,
        // Wireless and USB debugging by the app too.
        assertEquals(listOf(TestPhone.DEV, TestPhone.WIFI, TestPhone.USB), writes.seen())
        assertEquals(DebugSwitch.entries.toSet(), DebugSwitchesOff.switchedOff(context))
        assertEquals(listOf("debugging off: the recorder says nothing"), TestPhone.reviveLog(context))
        writes.close()
    }

    @Test fun onPutsDebuggingBackRevivesAndArmsTheHeartbeatAndBothWatchesButNoKick() = runBlocking {
        TestPhone.setUp(context)
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        TestPhone.clearReviveLog(context)
        val writes = TestPhone.Writes(context)

        RecorderSwitch.set(context, true)
        RecorderSwitch.turn(context, true)

        assertEquals(listOf(TestPhone.USB, TestPhone.WIFI, TestPhone.DEV), writes.seen())
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
        val log = TestPhone.reviveLog(context)
        // Put back before the revive, so the revive has none to put back.
        assertEquals(
            listOf("debugging back on: USB_DEBUGGING, WIRELESS_DEBUGGING, DEVELOPER_OPTIONS", "revive: start"),
            log.take(2),
        )
        assertEquals("revive: recorder down (connect NONE)", log.last())
        assertEquals(setOf(PERIODIC, USB_WATCH, wifiWatch), armed())
        assertEquals(RecorderHealth.Problem.NEEDS_WIFI, RecorderHealth.cause(context))
        writes.close()
    }

    @Test fun turnInBackgroundSavesTheSwitchBeforeItReturns() = runBlocking {
        RecorderSwitch.turnInBackground(context, false)
        // Saved at once, so a second tap reads it and turns the other way.
        assertFalse(RecorderSwitch.isOn(context))
        // The turn itself in the process's own scope, after the call is over.
        withTimeout(5_000) { while (stopsSent().isEmpty()) delay(20) }
        assertEquals(CallMonitorService.ACTION_STOP, stopsSent().single().action)
    }
}
