package com.jemcik.jemrec.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a seek sends the player: 80 ms early once its decoder is up, so the
 *  decoder's own pre-roll drop ends at the target (RFC 7845 4.6). */
class OpusPreRollTest {

    @Test fun theDecoderUpItIsSentEightyMsEarly() {
        assertEquals(4_920L, OpusPreRoll.seekFrom(5_000, decoderUp = true))
        assertEquals(80L, OpusPreRoll.MS)
    }

    /** A fresh decoder drops only the pre-skip: sent where asked. */
    @Test fun beforeTheDecoderItIsSentWhereAsked() {
        assertEquals(5_000L, OpusPreRoll.seekFrom(5_000, decoderUp = false))
    }

    /** Nothing before the start to pre-roll from. */
    @Test fun nearTheStartItIsSentToTheStart() {
        assertEquals(0L, OpusPreRoll.seekFrom(40, decoderUp = true))
        assertEquals(0L, OpusPreRoll.seekFrom(0, decoderUp = true))
    }
}
