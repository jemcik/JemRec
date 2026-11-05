package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.contract.Repo
import com.jemcik.jemrec.recorder.DaemonClient.OffReply
import com.jemcik.jemrec.recorder.DaemonClient.TestArming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recorder's replies the app reads into kinds (DaemonClient): to Turn all
 * debugging off and its call-off, and to arming a test call - each example in
 * shellserver/tests/fixtures/protocol.txt, and what the app makes of a reply
 * it does not know.
 */
class DaemonRepliesTest {

    /** The fixture's examples for [command]. */
    private fun examples(command: String): List<String> =
        Repo.text("shellserver/tests/fixtures/protocol.txt").lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it[0] == command }
            .map { it[3] }

    @Test fun everyOffTheRecorderSendsHasItsKind() {
        val kinds = examples("O").associateWith { DaemonClient.parseOffReply(it)?.kind }
        assertEquals(
            mapOf(
                "OFF NOW" to OffReply.Kind.NOW,
                "OFF AFTER CALL" to OffReply.Kind.AFTER_CALL,
                "OFF TOO LATE" to OffReply.Kind.TOO_LATE,
                "OFF CANCELLED" to OffReply.Kind.CANCELLED,
            ),
            kinds,
        )
    }

    @Test fun anOffNotKnownHereIsStillAnAnswerAndSaysWhatItSaid() {
        val reply = DaemonClient.parseOffReply("OFF LATER")!!
        assertEquals(OffReply.Kind.OTHER, reply.kind)
        assertEquals("OFF LATER", "$reply")
        assertEquals("OFF NOW", "${DaemonClient.parseOffReply("OFF NOW")}")
    }

    @Test fun anythingButAnOffIsNoAnswer() {
        assertNull(DaemonClient.parseOffReply(null))
        assertNull(DaemonClient.parseOffReply("BUSY"))
        assertNull(DaemonClient.parseOffReply(""))
        assertNull(DaemonClient.parseOffReply("off now"))
    }

    @Test fun everyArmingTheRecorderSendsHasItsKindButNone() {
        val kinds = examples("XA").associateWith { DaemonClient.parseTestArming(it) }
        assertEquals(
            mapOf(
                "TEST armed" to TestArming.ARMED,
                "TEST bad-number" to TestArming.BAD_NUMBER,
                "TEST emergency" to TestArming.EMERGENCY,
                "TEST no-greeting" to TestArming.NO_GREETING,
                "TEST busy" to TestArming.BUSY,
                // Nothing armed, for no reason the app shows: as no answer.
                "TEST none" to null,
            ),
            kinds,
        )
        assertNull(DaemonClient.parseTestArming(null))
        assertNull(DaemonClient.parseTestArming("TEST later"))
    }
}
