package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the app reads out of a daemon's answer to a ping, across every daemon it may meet. */
class PongTest {

    @Test fun theBareAnswerOfTheFirstDaemonsIsVersionOne() {
        assertEquals(CaptureDaemon.Answer(1, null), CaptureDaemon.parsePong("PONG"))
    }

    @Test fun versionAndBuildAreReadWhenGiven() {
        assertEquals(CaptureDaemon.Answer(2, null), CaptureDaemon.parsePong("PONG 2"))
        assertEquals(CaptureDaemon.Answer(2, "1a2b3c4d"), CaptureDaemon.parsePong("PONG 2 1a2b3c4d"))
        assertEquals(CaptureDaemon.Answer(2, "unknown"), CaptureDaemon.parsePong("PONG 2 unknown"))
    }

    @Test fun anythingElseIsNotAnAnswer() {
        assertNull(CaptureDaemon.parsePong(null))
        assertNull(CaptureDaemon.parsePong(""))
        assertNull(CaptureDaemon.parsePong("NOPE"))
        assertNull(CaptureDaemon.parsePong("PONGO 2"))
    }
}
