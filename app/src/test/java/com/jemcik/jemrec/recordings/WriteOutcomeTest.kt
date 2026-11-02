package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.recordings.WriteOutcome.Ending
import com.jemcik.jemrec.recordings.WriteOutcome.Read
import org.junit.Assert.assertEquals
import org.junit.Test

/** What becomes of a recording once read: the whole table, every row. */
class WriteOutcomeTest {

    @Test fun everyRow() {
        val bools = listOf(false, true)
        for (read in Read.entries) for (anything in bools) for (written in bools) for (whole in bools) for (off in bools) {
            val expected = when (read) {
                // A read that did not end cleanly decides alone.
                Read.GONE -> Ending.GONE
                Read.NOWHERE -> Ending.NOWHERE
                Read.BROKE_OFF -> Ending.BROKE_OFF
                Read.COMPLETE -> when {
                    // Switched off before it ended: dropped, whatever it holds.
                    off -> Ending.SWITCHED_OFF
                    // Audio that would not finish or copy keeps the recorder's copy.
                    anything && !written -> Ending.NOT_WRITTEN
                    anything -> Ending.PUBLISH
                    // Packets, none in the cut: whole, when one call allows it.
                    whole -> Ending.WHOLE
                    else -> Ending.EMPTY
                }
            }
            assertEquals(
                "$read anything=$anything written=$written whole=$whole off=$off",
                expected,
                WriteOutcome.of(read, anything, written, whole, off),
            )
        }
    }

    @Test fun theRowsThatMatter() {
        assertEquals(Ending.PUBLISH, WriteOutcome.of(Read.COMPLETE, anything = true, written = true, whole = true))
        assertEquals(Ending.NOT_WRITTEN, WriteOutcome.of(Read.COMPLETE, anything = true, written = false, whole = true))
        assertEquals(Ending.WHOLE, WriteOutcome.of(Read.COMPLETE, anything = false, written = false, whole = true))
        assertEquals(Ending.EMPTY, WriteOutcome.of(Read.COMPLETE, anything = false, written = true, whole = false))
        assertEquals(Ending.BROKE_OFF, WriteOutcome.of(Read.BROKE_OFF, anything = true, written = true, whole = true))
        assertEquals(Ending.GONE, WriteOutcome.of(Read.GONE, anything = false, written = false, whole = true))
        assertEquals(Ending.NOWHERE, WriteOutcome.of(Read.NOWHERE, anything = true, written = true, whole = false))
        assertEquals(Ending.SWITCHED_OFF, WriteOutcome.of(Read.COMPLETE, anything = true, written = true, whole = false, switchedOff = true))
        assertEquals(Ending.BROKE_OFF, WriteOutcome.of(Read.BROKE_OFF, anything = true, written = true, whole = false, switchedOff = true))
    }
}
