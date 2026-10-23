package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/** Where the playhead is drawn between the player's readings (see
 *  PlayheadGlide): smooth, never back but for a seek, and with the player. */
class PlayheadGlideTest {

    private val long = 252_000

    /**
     * Frames every 16 ms from [from] to [to], the reading at each being what
     * the view model last read - it reads every 300 ms - of a player at
     * [player] (ms from the start of the run to where the player is).
     */
    private fun frames(glide: PlayheadGlide, from: Long, to: Long, player: (Long) -> Int): List<Float> =
        (from..to step 16).map { t -> glide.frame(t, player(t - t % 300), long) }

    private fun neverBack(heads: List<Float>) =
        heads.zipWithNext().all { (a, b) -> b >= a }

    @Test fun aJustStartedPlayerIsWaitedFor() {
        val glide = PlayheadGlide(0).apply { resume() }
        // Reports 0 for its first 200 ms, then plays: the head waits, then
        // catches up, and never steps back.
        val heads = frames(glide, 0, 3_000) { t -> max(0, (t - 200).toInt()) }
        assertTrue(neverBack(heads))
        assertEquals(0f, heads[5], 0f)
        assertEquals(2_800f, heads.last(), 150f)
    }

    @Test fun atThePlayersPaceItKeepsUpInSmallSteps() {
        val glide = PlayheadGlide(10_000).apply { resume() }
        val heads = frames(glide, 0, 5_000) { t -> 10_000 + t.toInt() }
        assertTrue(neverBack(heads))
        assertEquals(15_000f, heads.last(), 150f)
        // A frame's worth at a time once going, not a reading's.
        assertTrue(heads.drop(60).zipWithNext().all { (a, b) -> b - a < 40 })
    }

    @Test fun aheadOfThePlayerItSlowsRatherThanStepBack() {
        val glide = PlayheadGlide(10_000).apply { resume() }
        frames(glide, 0, 1_000) { t -> 10_000 + t.toInt() }
        // The player falls 200 ms behind where the head has got to.
        val heads = frames(glide, 1_016, 3_000) { t -> 10_000 + t.toInt() - 200 }
        assertTrue(neverBack(heads))
        assertEquals(12_800f, heads.last(), 150f)
    }

    @Test fun aTapGoesThereAndOlderReadingsDoNotPullItBack() {
        val glide = PlayheadGlide(10_000).apply { resume() }
        frames(glide, 0, 900) { t -> 10_000 + t.toInt() }
        glide.soughtTo(40_000)
        assertEquals(40_000f, glide.head, 0f)
        // Two readings from before the player got there, then the player
        // there and playing on.
        val heads = frames(glide, 916, 3_000) { t ->
            if (t < 1_500) 10_900 + (t - 900).toInt() else 40_000 + (t - 1_500).toInt()
        }
        assertTrue(heads.all { it >= 40_000f })
        assertTrue(neverBack(heads))
        assertEquals(41_500f, heads.last(), 150f)
    }

    @Test fun aSeekMadeElsewhereIsFollowed() {
        val glide = PlayheadGlide(30_000).apply { resume() }
        frames(glide, 0, 900) { t -> 30_000 + t.toInt() }
        val heads = frames(glide, 916, 1_500) { t -> 2_000 + (t - 900).toInt() }
        assertEquals(2_600f, heads.last(), 150f)
    }

    @Test fun pausedItStaysWhereItWasDrawnAndResumesFromThere() {
        val glide = PlayheadGlide(0).apply { resume() }
        frames(glide, 0, 2_000) { t -> t.toInt() }
        val paused = glide.head
        glide.resume()
        // Resumed, the player at the reading it paused on: the head waits
        // for it rather than jump back to it.
        val heads = frames(glide, 5_000, 5_600) { t -> 1_800 + max(0, (t - 5_100).toInt()) }
        assertTrue(heads.all { it >= paused })
        assertTrue(neverBack(heads))
    }
}
