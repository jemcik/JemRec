package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.MessageHangUp.Decision
import com.jemcik.jemrec.capture.MessageHangUp.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answering machine's call hung up when its recorder is lost
 *  mid-message: that call, and no other (review round 13). */
class MessageHangUpTest {

    @Test fun theMessagesCallIsTheOneCallActiveAsItStarts() {
        assertEquals(42L, MessageHangUp.messageCall(listOf(Up(42, true))))
        assertNull("none: tracking off", MessageHangUp.messageCall(emptyList()))
        assertNull("not active", MessageHangUp.messageCall(listOf(Up(42, false))))
        assertNull("two: not the machine's to tell", MessageHangUp.messageCall(listOf(Up(41, false), Up(42, true))))
    }

    @Test fun itsCallStillUpActiveAndMutedIsEnded() {
        assertEquals(Decision.End(42), MessageHangUp.decide(42, listOf(Up(42, true)), micMuted = true))
    }

    @Test fun anythingElseIsLeftUp() {
        fun kept(d: Decision) = assertTrue(d.toString(), d is Decision.Keep)
        kept(MessageHangUp.decide(null, listOf(Up(42, true)), micMuted = true))
        kept(MessageHangUp.decide(42, emptyList(), micMuted = true))
        kept(MessageHangUp.decide(42, listOf(Up(43, true)), micMuted = true))
        kept(MessageHangUp.decide(42, listOf(Up(42, false), Up(43, true)), micMuted = true))
        kept(MessageHangUp.decide(42, listOf(Up(42, true)), micMuted = false))
    }
}
