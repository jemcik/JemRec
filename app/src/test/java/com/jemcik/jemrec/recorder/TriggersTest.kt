package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.recorder.Keeper.HEARTBEAT
import com.jemcik.jemrec.recorder.Keeper.KICK
import com.jemcik.jemrec.recorder.Keeper.USB_WATCH
import com.jemcik.jemrec.recorder.Keeper.WIFI_RUN
import com.jemcik.jemrec.recorder.Keeper.WIFI_WATCH
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What each of the recorder's triggers arms (Triggers.kt), as a table: one
 * row a trigger, in the order it arms. The entry points' own tests hold each
 * row on Android - ReviveTriggersTest (boot, Wi-Fi, opening the app),
 * MainViewModelRecorderTest (the home screen's check), RecorderSwitchTest
 * (On), RecorderKeepAliveWorkTest and UsbDebuggingWatchTest (the runs),
 * CableWakeTest (the cable's last try).
 */
class TriggersTest {

    @Test fun whatEachTriggerArms() {
        val table = Trigger.entries.associateWith(::armedBy)
        assertEquals(
            mapOf(
                Trigger.BOOT to listOf(USB_WATCH, HEARTBEAT, KICK, WIFI_WATCH),
                Trigger.WIFI_BACK to listOf(WIFI_RUN),
                Trigger.APP_OPEN to listOf(USB_WATCH, HEARTBEAT, KICK),
                Trigger.SETUP_CHECK to listOf(USB_WATCH, HEARTBEAT, KICK, WIFI_WATCH),
                Trigger.TURN_ON to listOf(USB_WATCH, HEARTBEAT, WIFI_WATCH),
                Trigger.TURN_OFF to emptyList(),
                Trigger.KEEP_ALIVE to listOf(USB_WATCH),
                Trigger.USB_CHANGED to emptyList(),
                Trigger.CABLE to emptyList(),
                Trigger.SELF_TEST to listOf(KICK),
                Trigger.REVIVE to emptyList(),
            ),
            table,
        )
    }

    @Test fun aWatchsFailedReviveHandsOverToTheKeepAlive() {
        // The Wi-Fi watch first, and only where there is no Wi-Fi to wait for
        // otherwise; then a kick.
        assertEquals(listOf(WIFI_WATCH, KICK), afterFailedRevive(onWifi = false))
        assertEquals(listOf(KICK), afterFailedRevive(onWifi = true))
    }
}
