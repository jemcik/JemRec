package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.recordings.AfterSave.Collector
import com.jemcik.jemrec.recordings.AfterSave.Failing
import com.jemcik.jemrec.recordings.AfterSave.Kind
import com.jemcik.jemrec.recordings.AfterSave.Tell
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What follows each outcome of a save, for each collector: the whole table,
 * as one line a row, so a difference between the two collectors is a
 * difference between two lines here.
 */
class AfterSaveTest {

    private fun line(kind: Kind, by: Collector): String {
        val row = AfterSave.row(kind, by)
        return listOfNotNull(
            row.log?.invoke("F")?.let { if (row.warn) "warn \"$it\"" else "log \"$it\"" },
            "notice".takeIf { row.notice },
            "missed-call check".takeIf { row.missedCheck },
            row.tell?.let { "tell $it" },
            "ledger empty".takeIf { row.noteEmpty },
            row.failing.takeIf { it != Failing.LEAVE }?.let { "failing $it" },
        ).joinToString(", ")
    }

    /** Nothing of a call saved: told by a failure, kept to try again,
     *  before an empty end - "No call audio" said of a call whose minutes
     *  failed to save (review round 15). */
    @Test fun aCallWithNothingSavedIsToldByItsFailure() {
        assertEquals("a", AfterSave.toldOf(listOf(Triple("a", Kind.FAILED, 0.0), Triple("own", Kind.EMPTY, 0.0)), "own"))
        // Kept by another collector: told by it, not "Call not recorded" of
        // a call saved (review round 16).
        assertEquals("a", AfterSave.toldOf(listOf(Triple("a", Kind.GONE, 0.0), Triple("own", Kind.EMPTY, 0.0)), "own"))
        assertEquals("own", AfterSave.toldOf(listOf(Triple("a", Kind.FAILED, 0.0), Triple("own", Kind.GONE, 0.0)), "own"))
        assertEquals("both failed: its own", "own", AfterSave.toldOf(listOf(Triple("a", Kind.FAILED, 0.0), Triple("own", Kind.FAILED, 0.0)), "own"))
        assertEquals("the most audio", "b", AfterSave.toldOf(listOf(Triple("a", Kind.SAVED, 3.0), Triple("b", Kind.SAVED, 300.0)), "a"))
    }

    @Test fun eachCollectorLogsUnderItsOwnTag() {
        assertEquals("monitor", Collector.HANG_UP.tag)
        assertEquals("reconcile", Collector.RECONCILE.tag)
        assertEquals("part", Collector.PART.tag)
    }

    /** A call's part other than the one it is told by: no notice of its
     *  own, no "not recorded"; a failure said as a part not saved and
     *  counted toward its week (review rounds 13 and 14). */
    @Test fun forAPartOfACall() {
        val table = Kind.entries.associateWith { line(it, Collector.PART) }
        assertEquals(
            mapOf(
                Kind.SAVED to "log \"part: saved F, a part of its call\", failing CLEAR",
                Kind.EMPTY to "log \"part: dropped empty F, a part of its call\", failing CLEAR",
                Kind.GONE to "log \"part: F was collected already\", failing CLEAR",
                Kind.BUSY to "log \"part: F is being saved by another collector\"",
                Kind.SKIPPED to "log \"part: F was left out (the rules, or recording switched off)\", failing CLEAR",
                Kind.NOT_ANSWERED to "log \"part: F was never answered\", failing CLEAR",
                Kind.GREETING_HANG_UP to "log \"part: F - the caller hung up during the greeting\", failing CLEAR",
                Kind.GREETING_FAILED to "tell ${Tell.GREETING_FAILED}, failing CLEAR",
                Kind.FAILED to "tell ${Tell.REASON}, failing COUNT",
            ),
            table,
        )
    }

    @Test fun afterTheHangUp() {
        val table = Kind.entries.associateWith { line(it, Collector.HANG_UP) }
        assertEquals(
            mapOf(
                Kind.SAVED to "log \"monitor: saved F\", notice, missed-call check",
                Kind.EMPTY to "tell ${Tell.NO_AUDIO}, ledger empty",
                Kind.GONE to "log \"monitor: F was collected already\"",
                Kind.BUSY to "log \"monitor: F is being saved by the reconcile\"",
                Kind.SKIPPED to "log \"monitor: F was left out (the rules, or recording switched off)\"",
                Kind.NOT_ANSWERED to "log \"monitor: F was never answered\"",
                Kind.GREETING_HANG_UP to "log \"monitor: F - the caller hung up during the greeting\"",
                Kind.GREETING_FAILED to "tell ${Tell.GREETING_FAILED}",
                Kind.FAILED to "tell ${Tell.REASON}",
            ),
            table,
        )
    }

    /** A reconcile's group's part: its failure counted, said nowhere -
     *  told at the hang-up (review rounds 15 and 16). */
    @Test fun forAPartLater() {
        assertEquals("failing COUNT", line(Kind.FAILED, Collector.PART_LATER))
        // Never told before: dropped at its first collection (review round 17).
        assertEquals(line(Kind.GREETING_FAILED, Collector.RECONCILE), line(Kind.GREETING_FAILED, Collector.PART_LATER))
        Kind.entries.filter { it != Kind.FAILED && it != Kind.GREETING_FAILED }
            .forEach { assertEquals(it.name, line(it, Collector.PART), line(it, Collector.PART_LATER)) }
    }

    @Test fun inAReconcile() {
        val table = Kind.entries.associateWith { line(it, Collector.RECONCILE) }
        assertEquals(
            mapOf(
                Kind.SAVED to "log \"reconcile: saved F\", notice, failing CLEAR",
                Kind.EMPTY to "log \"reconcile: dropped empty F\", failing CLEAR",
                Kind.GONE to "log \"reconcile: F was collected meanwhile\", failing CLEAR",
                Kind.BUSY to "log \"reconcile: F is being saved already, leaving it\"",
                Kind.SKIPPED to "log \"reconcile: F was left out (the rules, or recording switched off)\", failing CLEAR",
                Kind.NOT_ANSWERED to "log \"reconcile: F was never answered\", failing CLEAR",
                Kind.GREETING_HANG_UP to "log \"reconcile: F - hung up during the greeting\", failing CLEAR",
                Kind.GREETING_FAILED to "warn \"reconcile: F - the greeting could not be played\", tell GREETING_FAILED, failing CLEAR",
                Kind.FAILED to "failing COUNT",
            ),
            table,
        )
    }
}
