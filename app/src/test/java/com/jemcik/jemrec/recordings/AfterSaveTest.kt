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

    @Test fun eachCollectorLogsUnderItsOwnTag() {
        assertEquals("monitor", Collector.HANG_UP.tag)
        assertEquals("reconcile", Collector.RECONCILE.tag)
    }

    @Test fun afterTheHangUp() {
        val table = Kind.entries.associateWith { line(it, Collector.HANG_UP) }
        assertEquals(
            mapOf(
                Kind.SAVED to "log \"monitor: saved F\", notice, missed-call check",
                Kind.EMPTY to "tell ${Tell.NO_AUDIO}, ledger empty",
                Kind.GONE to "log \"monitor: F was collected already\"",
                Kind.BUSY to "log \"monitor: F is being saved by the reconcile\"",
                Kind.SKIPPED to "log \"monitor: F was left out by the rules\"",
                Kind.NOT_ANSWERED to "log \"monitor: F was never answered\"",
                Kind.GREETING_HANG_UP to "log \"monitor: F - the caller hung up during the greeting\"",
                Kind.GREETING_FAILED to "tell ${Tell.GREETING_FAILED}",
                Kind.FAILED to "tell ${Tell.REASON}",
            ),
            table,
        )
    }

    @Test fun inAReconcile() {
        val table = Kind.entries.associateWith { line(it, Collector.RECONCILE) }
        assertEquals(
            mapOf(
                Kind.SAVED to "log \"reconcile: saved F\", notice, failing CLEAR",
                Kind.EMPTY to "log \"reconcile: dropped empty F\", failing CLEAR",
                Kind.GONE to "log \"reconcile: F was collected meanwhile\", failing CLEAR",
                Kind.BUSY to "log \"reconcile: F is being saved already, leaving it\"",
                Kind.SKIPPED to "log \"reconcile: F was a call the rules leave out\", failing CLEAR",
                Kind.NOT_ANSWERED to "log \"reconcile: F was never answered\", failing CLEAR",
                Kind.GREETING_HANG_UP to "log \"reconcile: F - hung up during the greeting\", failing CLEAR",
                Kind.GREETING_FAILED to "warn \"reconcile: F - the greeting could not be played\", tell GREETING_FAILED, failing CLEAR",
                Kind.FAILED to "failing COUNT",
            ),
            table,
        )
    }
}
