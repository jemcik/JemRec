package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.BackgroundKill.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background-kill decision, off the phone. The rule: the newest exit that
 * was a system kill (REASON_OTHER or REASON_FREEZER) while JemRec was working,
 * and happened after the last one we warned about. Android's clean-up of an
 * idle JemRec is not it, nor is anything the user did on purpose - Force
 * stop, swipe-away, an app update.
 */
class BackgroundKillTest {

    // ApplicationExitInfo reason codes used in the tests.
    private val OTHER = BackgroundKill.REASON_OTHER      // 13: vendor power-manager kill
    private val FREEZER = BackgroundKill.REASON_FREEZER  // 14
    private val USER_REQUESTED = 10                      // Force stop / swipe-away
    private val CRASH = 4
    private val PACKAGE_UPDATED = 16

    // Importance when the process ended: the Honor's harmful kill was at 125,
    // a foreground service; Android's clean-up of idle apps is at 400.
    private val WORKING = 125
    private val IDLE = BackgroundKill.IMPORTANCE_CACHED

    @Test fun aSystemKillAfterTheLastWarningIsReported() {
        val exits = listOf(Exit(OTHER, 1_000, WORKING))
        assertEquals(1_000L, BackgroundKill.systemKill(exits, sinceMs = 0)?.timestampMs)
    }

    @Test fun aKillAtOrBeforeTheLastWarningIsNot() {
        val exits = listOf(Exit(OTHER, 1_000, WORKING))
        assertNull(BackgroundKill.systemKill(exits, sinceMs = 1_000))
        assertNull(BackgroundKill.systemKill(exits, sinceMs = 2_000))
    }

    @Test fun theUsersOwnActionsAreNotBackgroundKills() {
        val exits = listOf(
            Exit(USER_REQUESTED, 1_000, WORKING),  // Force stop / swipe
            Exit(CRASH, 1_100, WORKING),
            Exit(PACKAGE_UPDATED, 1_200, WORKING), // an app update
        )
        assertNull(BackgroundKill.systemKill(exits, sinceMs = 0))
    }

    @Test fun freezerCountsToo() {
        val exits = listOf(Exit(FREEZER, 500, WORKING))
        assertEquals(500L, BackgroundKill.systemKill(exits, sinceMs = 0)?.timestampMs)
    }

    @Test fun theNewestQualifyingKillWins() {
        val exits = listOf(
            Exit(OTHER, 1_000, WORKING),
            Exit(USER_REQUESTED, 3_000, WORKING), // newer, but not a system kill
            Exit(OTHER, 2_000, WORKING),          // the newest system kill
            Exit(FREEZER, 1_500, WORKING),
        )
        assertEquals(2_000L, BackgroundKill.systemKill(exits, sinceMs = 0)?.timestampMs)
    }

    @Test fun anIdleAppCleanedUpIsNotReported() {
        // Measured on the Honor: 2,015 of 2,062 reason-13 exits were idle
        // apps reclaimed, which costs JemRec nothing.
        assertNull(BackgroundKill.systemKill(listOf(Exit(OTHER, 1_000, IDLE)), sinceMs = 0))
        // Anything busier than idle - a service running - is.
        assertEquals(1_000L, BackgroundKill.systemKill(listOf(Exit(OTHER, 1_000, 300)), sinceMs = 0)?.timestampMs)
    }

    @Test fun anIdleKillBehindIsPassedOverForAnOlderOneWhileWorking() {
        val exits = listOf(Exit(OTHER, 2_000, IDLE), Exit(OTHER, 1_000, WORKING))
        assertEquals(1_000L, BackgroundKill.systemKill(exits, sinceMs = 0)?.timestampMs)
    }

    @Test fun theDaemonsWakeVouchesForAnIdleKill() {
        // The daemon saw that the kill left JemRec stopped, which the app
        // cannot see once it runs: any system kill counts then...
        val idle = listOf(Exit(OTHER, 1_000, IDLE))
        assertEquals(1_000L, BackgroundKill.systemKill(idle, sinceMs = 0, vouched = true)?.timestampMs)
        // ...but never the user's own Force stop.
        assertNull(BackgroundKill.systemKill(listOf(Exit(USER_REQUESTED, 1_000, IDLE)), sinceMs = 0, vouched = true))
    }

    @Test fun anIdleKillThatLeftJemRecStoppedIsReportedOnOpen() {
        // Android 15's start record said this open found JemRec stopped, as
        // the Honor's 14:00 open after the 13:52 kill was marked.
        val idle = listOf(Exit(OTHER, 1_000, IDLE))
        assertEquals(1_000L, BackgroundKill.systemKill(idle, sinceMs = 0, leftStopped = true)?.timestampMs)
        assertNull(BackgroundKill.systemKill(idle, sinceMs = 0, leftStopped = false))
    }

    @Test fun stoppedLendsOnlyToTheNewestExit() {
        // The user's own Force stop came last and left JemRec stopped: the
        // idle kill before it is still routine.
        val exits = listOf(Exit(USER_REQUESTED, 2_000, WORKING), Exit(OTHER, 1_000, IDLE))
        assertNull(BackgroundKill.systemKill(exits, sinceMs = 0, leftStopped = true))
        // Nor does it reach an idle kill older than the newest one.
        val two = listOf(Exit(OTHER, 2_000, IDLE), Exit(OTHER, 1_000, IDLE))
        assertEquals(2_000L, BackgroundKill.systemKill(two, sinceMs = 0, leftStopped = true)?.timestampMs)
        assertNull(BackgroundKill.systemKill(two, sinceMs = 2_000, leftStopped = true))
    }

    @Test fun thisProcessesOwnColdStartDecides() {
        val cold = BackgroundKill.Start(pid = 7, processStart = true, launchedNs = 100, wasStopped = true)
        // Opened later in the same process: a warm start on top, never marked.
        val warm = BackgroundKill.Start(pid = 7, processStart = false, launchedNs = 200, wasStopped = false)
        assertTrue(BackgroundKill.ownStartWasStopped(listOf(warm, cold), myPid = 7))
    }

    @Test fun aKeptStartWithARecycledPidIsNotThisOne() {
        // An old start, marked, with the pid this process has now (pids wrap
        // within hours on the Honor). This process's own start is newer and
        // was not stopped...
        val old = BackgroundKill.Start(pid = 7, processStart = true, launchedNs = 100, wasStopped = true)
        val mine = BackgroundKill.Start(pid = 7, processStart = true, launchedNs = 900, wasStopped = false)
        assertFalse(BackgroundKill.ownStartWasStopped(listOf(old, mine), myPid = 7))
        // ...or not kept at all, and the newest cold start is another's.
        val other = BackgroundKill.Start(pid = 9, processStart = true, launchedNs = 900, wasStopped = false)
        assertFalse(BackgroundKill.ownStartWasStopped(listOf(old, other), myPid = 7))
        assertFalse(BackgroundKill.ownStartWasStopped(emptyList(), myPid = 7))
    }

    @Test fun aServiceStartWithItsPidNotFilledInIsThisOne() {
        // The Honor's record of the recorder's wake, 2026-10-25 19:24: pid=0,
        // SERVICE, COLD, wasForceStopped=true - newer than the earlier start
        // that had a pid.
        val earlier = BackgroundKill.Start(pid = 30914, processStart = true, launchedNs = 84_246_181_443_331, wasStopped = false)
        val wake = BackgroundKill.Start(pid = 0, processStart = true, launchedNs = 84_577_802_945_548, wasStopped = true)
        assertTrue(BackgroundKill.ownStartWasStopped(listOf(wake, earlier), myPid = 14111))
    }

    @Test fun thisProcessesStartStillInProgressIsTheNewest() {
        // Read as the process starts: its own record has no launch time yet,
        // and an older finished one (another pid) must not win over it.
        val old = BackgroundKill.Start(pid = 9, processStart = true, launchedNs = 900, wasStopped = false)
        val mine = BackgroundKill.Start(pid = 7, processStart = true, launchedNs = null, wasStopped = true)
        assertTrue(BackgroundKill.ownStartWasStopped(listOf(old, mine), myPid = 7))
    }

    @Test fun nothingToReportIsNull() {
        assertNull(BackgroundKill.systemKill(emptyList(), sinceMs = 0))
    }

    @Test fun afterAWarningOnlyNewerKillsCountForBothCallers() {
        assertEquals(5_000L, BackgroundKill.countFrom(5_000, fromDaemon = false, nowMs = 9_000_000))
        assertEquals(5_000L, BackgroundKill.countFrom(5_000, fromDaemon = true, nowMs = 9_000_000))
    }

    @Test fun beforeAnyWarningTheOpenTakesABaselineAndTheDaemonCountsTheLastHour() {
        // The open says nothing the first time: a kill from before this
        // version must never be reported as news.
        assertNull(BackgroundKill.countFrom(null, fromDaemon = false, nowMs = 9_000_000))
        // The daemon woke us because it just saw a kill: the last hour counts,
        // an old one re-printed in a new time zone does not.
        assertEquals(9_000_000L - 3_600_000L, BackgroundKill.countFrom(null, fromDaemon = true, nowMs = 9_000_000))
    }

    @Test fun aHoldUpIsToldInWholeMinutesNeverZero() {
        // The Honor's: 8.5 minutes reads as about 9.
        assertEquals(9L, BackgroundKill.heldBackMinutes(510_000))
        // The shortest stall that warns is over a minute and a half.
        assertEquals(2L, BackgroundKill.heldBackMinutes(91_000))
        assertEquals(1L, BackgroundKill.heldBackMinutes(10_000))
    }
}
