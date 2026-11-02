package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.calls.CallLedger.Recorded
import com.jemcik.jemrec.calls.CallTracking.Tracked
import com.jemcik.jemrec.calls.CoverageHistory.State
import com.jemcik.jemrec.recordings.SavePlan.Drop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the saver decides before it fetches a recording, and how it cuts a kept one. */
class SavePlanCutTest {

    private val t = 1_795_000_000_000L
    private fun file(tag: String = "") = DaemonFile.parse("jemrec_rec_${t}${tag}_in.dat")

    @Test fun aRecordingIsDroppedByTheLedgerFirstThenByItsMessage() {
        val accounted = listOf(Recorded(t, true, 30))
        assertEquals(Drop.DUPLICATE, SavePlan.drop(file("_msgfail"), t, accounted))
        assertEquals(Drop.GREETING_FAILED, SavePlan.drop(file("_msgfail"), t, emptyList()))
        assertEquals(Drop.GREETING_HANG_UP, SavePlan.drop(file("_msgnone"), t, emptyList()))
        assertNull(SavePlan.drop(file("_msg100"), t, emptyList()))
        assertNull(SavePlan.drop(file(), t, listOf(Recorded(t + 1, true, 30))))
    }

    @Test fun switchedOffIsWhatTheSwitchWasWhenTelecomSaysTheCallEnded() {
        val history = listOf(0L to State.AUTOMATIC, t + 10_000 to State.OFF)
        fun call(ended: Long) = listOf(Tracked(t, true, null, listOf(t), ended))
        assertTrue(SavePlan.switchedOff(call(t + 20_000), history))
        assertFalse(SavePlan.switchedOff(call(t + 5_000), history))
        assertFalse("not ended", SavePlan.switchedOff(call(0), history))
        assertFalse("no account", SavePlan.switchedOff(emptyList(), history))
        assertFalse("no history", SavePlan.switchedOff(call(t + 20_000), emptyList()))
        val onAgain = history + (t + 15_000 to State.AUTOMATIC)
        assertFalse("on again before the end", SavePlan.switchedOff(call(t + 20_000), onAgain))
    }

    private val own = RecordingSaver.Part("own.ogg", true, t, listOf(0L until 500L))
    private val waiting = RecordingSaver.Part("waiting.ogg", true, t + 10_000, listOf(500L until 750L))
    private val tracked = listOf(Tracked(t - 10_000, true, null, listOf(t), t + 20_000, clockOffset = 42))

    @Test fun aCallIsWrittenAsDecidedOnTheClocksOffset() {
        val cut = SavePlan.cut(SavePlan.Decision.Save(listOf(own), emptyList()), file(), t, tracked)
        assertEquals(listOf(own), cut.parts)
        assertFalse(cut.message)
        assertEquals(42L, cut.clockOffset)
        assertEquals(listOf(0L until Long.MAX_VALUE), cut.orWhole!!.packets)
        assertEquals(0, cut.notKept)
    }

    @Test fun aMessageKeepsItsOwnCallFromItsStartAndCounts() {
        val cut = SavePlan.cut(SavePlan.Decision.Save(listOf(own, waiting), emptyList()), file("_msg2000"), t, tracked)
        assertEquals(listOf(100L until 500L), cut.parts.single().packets)
        assertEquals("own.ogg", cut.parts.single().name)
        assertTrue(cut.message)
        assertNull(cut.clockOffset)
        assertNull(cut.orWhole)
        assertEquals(1, cut.notKept)
    }

    @Test fun aCallTakenOverIsTrimmedButNoMessage() {
        val cut = SavePlan.cut(SavePlan.Decision.Save(listOf(own, waiting), emptyList()), file("_from2000"), t, tracked)
        assertEquals(listOf(listOf(100L until 500L), listOf(500L until 750L)), cut.parts.map { it.packets })
        assertFalse(cut.message)
        assertNull(cut.clockOffset)
        assertNull(cut.orWhole)
        assertEquals(0, cut.notKept)
    }
}
