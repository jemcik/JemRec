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
        // What it does besides, after its build: fetches that start with
        // their length (1.2). A word not known is passed over.
        assertEquals(CaptureDaemon.Answer(2, "1a2b3c4d", fetchLength = true), CaptureDaemon.parsePong("PONG 2 1a2b3c4d fetch-length"))
        // And a LIST that ends with its count (1.2, review round 11): an
        // older recorder's, without the word, is read to its end as before.
        assertEquals(
            CaptureDaemon.Answer(2, "1a2b3c4d", fetchLength = true, listEnd = true),
            CaptureDaemon.parsePong("PONG 2 1a2b3c4d fetch-length list-end"),
        )
        assertEquals(CaptureDaemon.Answer(2, "1a2b3c4d"), CaptureDaemon.parsePong("PONG 2 1a2b3c4d something-later"))
    }

    @Test fun anythingElseIsNotAnAnswer() {
        assertNull(CaptureDaemon.parsePong(null))
        assertNull(CaptureDaemon.parsePong(""))
        assertNull(CaptureDaemon.parsePong("NOPE"))
        assertNull(CaptureDaemon.parsePong("PONGO 2"))
    }
}
