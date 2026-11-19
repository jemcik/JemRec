package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the playhead is drawn from where the sound is, every frame (see
 *  PlayheadGlide): with the sound, never a small step back, and not pulled
 *  back by readings from before a seek landed. */
class PlayheadGlideTest {

    private val long = 252_000

    /** Frames every 16 ms from [from] to [to], the sound at each being at
     *  [sound] (ms from the start of the run to where the sound is). */
    private fun frames(glide: PlayheadGlide, from: Long, to: Long, sound: (Long) -> Int): List<Float> =
        (from..to step 16).map { t -> glide.frame(t, sound(t), long) }

    private fun neverBack(heads: List<Float>) =
        heads.zipWithNext().all { (a, b) -> b >= a }

    @Test fun itIsWhereTheSoundIs() {
        val glide = PlayheadGlide(10_000)
        val heads = frames(glide, 0, 5_000) { t -> 10_000 + t.toInt() }
        assertEquals((0L..5_000L step 16).map { 10_000f + it }, heads)
    }

    @Test fun beforeTheSoundStartsItStays() {
        // The Honor with an earbud, 2026-11-17: the sound started 672 ms
        // after Play, and the player said 0 until then.
        val glide = PlayheadGlide(0)
        val heads = frames(glide, 0, 2_000) { t -> maxOf(0, (t - 672).toInt()) }
        assertTrue(heads.takeWhile { it == 0f }.size >= 672 / 16)
        assertTrue(neverBack(heads))
        assertEquals(2_000f - 672, heads.last(), 16f)
    }

    @Test fun aLittleBehindItWaitsRatherThanStepBack() {
        val glide = PlayheadGlide(0)
        frames(glide, 0, 1_000) { t -> t.toInt() }
        // The clock takes over 30 ms behind ExoPlayer's own word: drawn at
        // 992, the head waits there for it.
        val heads = frames(glide, 1_016, 1_200) { t -> t.toInt() - 30 }
        assertTrue(neverBack(heads))
        assertEquals(992f, heads.first(), 0f)
        assertEquals(1_162f, heads.last(), 0f)
    }

    @Test fun aSeekMadeElsewhereIsFollowed() {
        val glide = PlayheadGlide(30_000)
        frames(glide, 0, 900) { t -> 30_000 + t.toInt() }
        val heads = frames(glide, 916, 1_500) { t -> 2_000 + (t - 900).toInt() }
        assertEquals(2_016f, heads.first(), 0f)
        assertEquals(2_592f, heads.last(), 0f)
    }

    @Test fun aTapGoesThereAndReadingsFromBeforeItLandedDoNotPullItBack() {
        val glide = PlayheadGlide(10_000)
        frames(glide, 0, 896) { t -> 10_000 + t.toInt() }
        glide.soughtTo(10_400)
        assertEquals(10_400f, glide.head, 0f)
        // The player still where it was for three frames, then there, the
        // sound starting 100 ms on.
        val heads = frames(glide, 912, 2_000) { t ->
            when {
                t < 960 -> 10_000 + t.toInt()
                t < 1_060 -> 10_400
                else -> 10_400 + (t - 1_060).toInt()
            }
        }
        assertTrue(heads.all { it >= 10_400f })
        assertTrue(neverBack(heads))
        assertEquals(10_400f + 2_000 - 1_060, heads.last(), 16f)
    }

    @Test fun aSeekThatNeverLandsIsLetGoAfterASecond() {
        val glide = PlayheadGlide(10_000)
        glide.soughtTo(200_000)
        val heads = frames(glide, 0, 2_000) { t -> 10_000 + t.toInt() }
        assertTrue(heads.takeWhile { it == 200_000f }.size >= PlayheadGlide.STALE_MS / 16)
        assertEquals(12_000f, heads.last(), 0f)
    }

    @Test fun placedAwayFromASeekTheSeekIsOver() {
        // Tapped just before the end: the recording finished, went back to
        // 0:00, and played again from there.
        val glide = PlayheadGlide(10_000)
        glide.soughtTo(251_900)
        glide.place(0)
        val heads = frames(glide, 0, 500) { t -> t.toInt() }
        assertEquals(500f, heads.last(), 16f)
    }

    @Test fun pausedThenPlayedItGoesOnFromThePause() {
        val glide = PlayheadGlide(0)
        frames(glide, 0, 2_000) { t -> t.toInt() }
        // Paused where the clock had the sound; played again, the player
        // says a little further on and waits for the sound.
        glide.place(2_005)
        val heads = frames(glide, 5_000, 6_000) { t -> 2_015 + maxOf(0, (t - 5_300).toInt()) }
        assertTrue(neverBack(heads))
        // The 10 ms made up in a frame, not jumped.
        assertEquals(listOf(2_005f, 2_015f), heads.take(2))
    }

    @Test fun aCorrectionAheadIsMadeUpATenthFaster() {
        // The earbud, 2026-11-17: Media3 says when the sound started, and
        // the word goes 104 ms on at once.
        val glide = PlayheadGlide(0)
        val heads = frames(glide, 0, 3_000) { t -> t.toInt() + if (t >= 1_008) 104 else 0 }
        val steps = heads.zipWithNext { a, b -> b - a }
        assertTrue("${steps.max()}", steps.all { it <= 16 * PlayheadGlide.FASTER + 0.01f })
        assertTrue(neverBack(heads))
        // Made up within about a second and a half.
        assertEquals(2_400f + 104, heads[2_400 / 16], 0f)
        assertEquals(3_000f - 8 + 104, heads.last(), 0f)
    }

    @Test fun aCorrectionBehindWhileTheSoundGoesOnIsMadeUpATenthSlower() {
        val glide = PlayheadGlide(0)
        val heads = frames(glide, 0, 3_000) { t -> t.toInt() - if (t >= 1_008) 100 else 0 }
        assertTrue(neverBack(heads))
        // It goes on all the while, at nine tenths at the least, but for the
        // one frame the word went back in, which it waits out...
        val steps = heads.zipWithNext { a, b -> b - a }
        assertEquals(1, steps.count { it < 16 * PlayheadGlide.SLOWER - 0.01f })
        // ...and is with the sound again after about a second.
        assertEquals(3_000f - 8 - 100, heads.last(), 0f)
    }

    @Test fun aFrameThatCameLateGoesWhereTheSoundIs() {
        val glide = PlayheadGlide(0)
        frames(glide, 0, 1_000) { t -> t.toInt() }
        // The screen held up 300 ms, the word moved on only 30 of them.
        assertEquals(1_022f, glide.frame(1_292, 1_022, long), 0f)
        val heads = frames(glide, 1_308, 1_600) { t -> 1_022 + (t - 1_292).toInt() }
        assertTrue(neverBack(listOf(1_022f) + heads))
        assertEquals(1_022f + 1_596 - 1_292, heads.last(), 0f)
    }

    @Test fun aWordInCoarseStepsIsDrawnInSmallOnes() {
        // ExoPlayer's own word before Media3 says when the sound started:
        // on 24 ms at a time, read every 8 ms frame.
        val glide = PlayheadGlide(0)
        val heads = (0L..2_000L step 8).map { t -> glide.frame(t, (t / 24 * 24).toInt(), long) }
        val steps = heads.zipWithNext { a, b -> b - a }
        assertTrue("${steps.max()}", steps.all { it <= 8 * PlayheadGlide.FASTER + 0.01f })
        assertTrue(neverBack(heads))
        assertEquals(1_992f, heads.last(), 24f)
    }

    @Test fun neverPastTheEnd() {
        val glide = PlayheadGlide(251_900)
        val heads = frames(glide, 0, 400) { t -> 251_900 + t.toInt() }
        assertEquals(long.toFloat(), heads.last(), 0f)
    }
}
