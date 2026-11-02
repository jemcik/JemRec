package com.jemcik.jemrec.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call service's one rule for standing down (StandDown), driven by jobs
 * whose completion the test decides: so two ends at once, and a job stored
 * before it starts, happen here every time, in the order a phone may take.
 */
class StandDownTest {

    private class Task {
        var finished = false
    }

    private val rule = StandDown<Task> { it.finished }
    private val noCall = { false }

    @Test fun nothingUpNothingRunning() {
        assertTrue(rule.idle(null, noCall) { listOf(null, null) })
    }

    @Test fun aCallKeepsIt() {
        assertFalse(rule.idle(null, { true }) { emptyList() })
    }

    @Test fun aRunningJobKeepsItAndAFinishedOneDoesNot() {
        val save = Task()
        assertFalse(rule.idle(null, noCall) { listOf(save) })
        save.finished = true
        assertTrue(rule.idle(null, noCall) { listOf(save) })
    }

    @Test fun aJobStoredAndNotYetStartedKeepsIt() {
        // Not finished, so running: a lazy job is not active before it starts.
        assertFalse(rule.idle(null, noCall) { listOf(Task()) })
    }

    @Test fun aJobEndingDoesNotKeepItItself() {
        val save = Task()
        assertTrue(rule.idle(save, noCall) { listOf(save) })
    }

    @Test fun twoJobsEndingTogetherStandItDown() {
        val save = Task()
        val warning = Task()
        // The save looks as it ends, while the warning still runs.
        assertFalse(rule.idle(save, noCall) { listOf(save, warning) })
        // The warning ends before the save has returned: the save, marked as
        // ending, no longer counts.
        assertTrue(rule.idle(warning, noCall) { listOf(save, warning) })
    }

    @Test fun anEndingJobStillCountsForAnotherServiceLookUntilItIsMarked() {
        val save = Task()
        val warning = Task()
        // A look that is no job's end - a stray start - while both run.
        assertFalse(rule.idle(null, noCall) { listOf(save, warning) })
        assertFalse(rule.idle(save, noCall) { listOf(save, warning) })
        assertTrue(rule.idle(warning, noCall) { listOf(save, warning) })
    }

    @Test fun whatIsReadIsReadAfterTheMark() {
        val save = Task()
        var read = false
        rule.idle(save, { read = true; false }) { listOf(save) }
        assertTrue(read)
    }
}
