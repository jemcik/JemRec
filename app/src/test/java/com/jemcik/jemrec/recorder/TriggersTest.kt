package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.recorder.Keeper.HEARTBEAT
import com.jemcik.jemrec.recorder.Keeper.KICK
import com.jemcik.jemrec.recorder.Keeper.USB_WATCH
import com.jemcik.jemrec.recorder.Keeper.WIFI_RUN
import com.jemcik.jemrec.recorder.Keeper.WIFI_WATCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's triggers (Triggers.kt) as tables: what each arms, in its
 * order; what each may do by what it finds (admit); and what each does
 * after its revive (followUp). The entry points' own tests hold each row on
 * Android - ReviveTriggersTest (boot, Wi-Fi, opening the app),
 * MainViewModelRecorderTest (the home screen's check), RecorderSwitchTest
 * (On, Off), RecorderKeepAliveWorkTest and UsbDebuggingWatchTest (the
 * runs), CableWakeTest (the cable), DebugSwitchesOffStateTest and
 * ReviveTest (a revive's own gate).
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

    private fun gates(setUp: Boolean = true, on: Boolean = true, recorded: Boolean = false, gone: Boolean = false) =
        Gates(setUp = setUp, switchOn = on, recorded = recorded, recorderGone = gone)

    /** JemRec off with all debugging, the recorder gone with it. */
    private val offWithAll = gates(on = false, recorded = true, gone = true)

    @Test fun bootAndWifiAskSetupNotTheSwitch() {
        for (trigger in listOf(Trigger.BOOT, Trigger.WIFI_BACK)) {
            assertEquals(Admit.Go(), admit(trigger, offWithAll))
            assertEquals(Admit.Skip, admit(trigger, gates(setUp = false)))
        }
    }

    @Test fun theAppsOwnAskTheSwitchNotSetup() {
        for (trigger in listOf(Trigger.APP_OPEN, Trigger.SETUP_CHECK)) {
            assertEquals(Admit.Go(), admit(trigger, gates(setUp = false)))
            assertEquals(Admit.Skip, admit(trigger, gates(on = false)))
        }
    }

    @Test fun theRunsAskSetupThenWhetherJemRecStandsDown() {
        for (trigger in listOf(Trigger.KEEP_ALIVE, Trigger.USB_CHANGED)) {
            // Setup first: standing down unset up is still nothing at all.
            assertEquals(Admit.Skip, admit(trigger, offWithAll.copy(setUp = false)))
            assertEquals(Admit.StandDown, admit(trigger, offWithAll))
            // Hide Developer options keeps the recorder: not standing down.
            assertEquals(Admit.Go(), admit(trigger, gates(on = false, recorded = true)))
            // On, a record is an On cut short, which their revive puts back.
            assertEquals(Admit.Go(), admit(trigger, gates(recorded = true, gone = true)))
        }
    }

    @Test fun everyReviveAsksWhetherJemRecStandsDownFirstAndPutsBackAnOnCutShort() {
        assertEquals(Admit.StandDown, admit(Trigger.REVIVE, offWithAll))
        assertEquals(Admit.StandDown, admit(Trigger.REVIVE, offWithAll.copy(setUp = false)))
        assertEquals(Admit.Go(putBack = true), admit(Trigger.REVIVE, gates(recorded = true, gone = true)))
        assertEquals(Admit.Go(putBack = true), admit(Trigger.REVIVE, gates(recorded = true)))
        // Off with the recorder kept: nothing put back, a revive like any.
        assertEquals(Admit.Go(), admit(Trigger.REVIVE, gates(on = false, recorded = true)))
        // Setup is the revive's own question, after its probe.
        assertEquals(Admit.Go(), admit(Trigger.REVIVE, gates(setUp = false)))
    }

    @Test fun aTurnGoesOnlyWhereTheSwitchStillSaysSo() {
        assertEquals(Admit.Go(putBack = true), admit(Trigger.TURN_ON, gates(recorded = true, gone = true)))
        assertEquals(Admit.Go(), admit(Trigger.TURN_ON, gates(setUp = false)))
        assertEquals(Admit.Skip, admit(Trigger.TURN_ON, offWithAll))
        assertEquals(Admit.Go(), admit(Trigger.TURN_OFF, offWithAll))
        assertEquals(Admit.Skip, admit(Trigger.TURN_OFF, gates()))
    }

    @Test fun theCableAndTheSelfTestAlwaysGoOn() {
        for (trigger in listOf(Trigger.CABLE, Trigger.SELF_TEST)) {
            assertEquals(Admit.Go(), admit(trigger, offWithAll.copy(setUp = false)))
        }
    }

    @Test fun standingDownIsTheRecorderGoneWithJemRecOff() {
        assertTrue(standingDown(recorderGone = true, switchOn = false))
        assertFalse(standingDown(recorderGone = true, switchOn = true))
        assertFalse(standingDown(recorderGone = false, switchOn = false))
    }

    @Test fun theKeepAliveArmsTheWifiWatchOffWifiCollectsFromARecorderUpAndTriesAgainDown() {
        assertEquals(FollowUp(arm = listOf(WIFI_WATCH), collect = true), followUp(Trigger.KEEP_ALIVE, alive = true, onWifi = false))
        assertEquals(FollowUp(collect = true), followUp(Trigger.KEEP_ALIVE, alive = true, onWifi = true))
        assertEquals(FollowUp(arm = listOf(WIFI_WATCH), retry = true), followUp(Trigger.KEEP_ALIVE, alive = false, onWifi = false))
        assertEquals(FollowUp(retry = true), followUp(Trigger.KEEP_ALIVE, alive = false, onWifi = true))
        // The Wi-Fi run off Wi-Fi does not: the watch armed again wakes the next.
        assertEquals(
            FollowUp(arm = listOf(WIFI_WATCH)),
            followUp(Trigger.KEEP_ALIVE, alive = false, onWifi = false, forWifi = true),
        )
        assertEquals(FollowUp(retry = true), followUp(Trigger.KEEP_ALIVE, alive = false, onWifi = true, forWifi = true))
    }

    @Test fun theWatchesHandOverOnlyARecorderStillDown() {
        for (trigger in listOf(Trigger.USB_CHANGED, Trigger.CABLE)) {
            assertEquals(FollowUp(), followUp(trigger, alive = true, onWifi = false))
            assertEquals(FollowUp(arm = listOf(WIFI_WATCH, KICK)), followUp(trigger, alive = false, onWifi = false))
            assertEquals(FollowUp(arm = listOf(KICK)), followUp(trigger, alive = false, onWifi = true))
        }
    }

    @Test fun aFollowUpAndAnAdmissionSayWhatTheyHoldOneByOne() {
        // As their users read them, field by field (RecorderKeepAlive,
        // RecorderLifecycle, RecorderSwitch), not whole.
        val up = followUp(Trigger.KEEP_ALIVE, alive = true, onWifi = false)
        assertEquals(listOf(WIFI_WATCH), up.arm)
        assertTrue(up.collect)
        assertFalse(up.retry)
        val down = followUp(Trigger.KEEP_ALIVE, alive = false, onWifi = true)
        assertFalse(down.collect)
        assertTrue(down.retry)
        assertTrue((admit(Trigger.REVIVE, gates(recorded = true)) as Admit.Go).putBack)
        assertFalse((admit(Trigger.REVIVE, gates()) as Admit.Go).putBack)
    }

    @Test fun theRestDoNothingAfter() {
        val rest = Trigger.entries - setOf(Trigger.KEEP_ALIVE, Trigger.USB_CHANGED, Trigger.CABLE)
        for (trigger in rest) {
            assertEquals(trigger.name, FollowUp(), followUp(trigger, alive = false, onWifi = false))
        }
    }
}
