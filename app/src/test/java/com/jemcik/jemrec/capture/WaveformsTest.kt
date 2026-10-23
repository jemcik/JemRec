package com.jemcik.jemrec.capture

import com.jemcik.jemrec.ui.loudestIn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** What the player's waveform is drawn from: a recording's loudness, made a
 *  few hundred levels, and kept. */
class WaveformsTest {

    /** RMS, in 16-bit units, of a sound [db] dBFS loud. */
    private fun rms(db: Double) = (32768 * 10.0.pow(db / 20)).toFloat()

    @Test fun silenceIsFlat() {
        val levels = Waveforms.reduce(FloatArray(1_500) { 3f }, Waveforms.LEVELS)
        assertEquals(Waveforms.LEVELS, levels.size)
        assertTrue(levels.all { it == 0f })
    }

    @Test fun wordsStandOutOfThePauseAroundThem() {
        // A minute of a quiet line, the caller speaking in its middle.
        val frames = FloatArray(3_000) { if (it in 1_400 until 1_600) rms(-12.0) else rms(-70.0) }
        val levels = Waveforms.reduce(frames, Waveforms.LEVELS)
        assertEquals(1f, levels[Waveforms.LEVELS / 2], 0.001f)
        assertEquals(0f, levels.first(), 0.001f)
        assertEquals(0f, levels.last(), 0.001f)
    }

    @Test fun aQuietRecordingIsNotStretchedToTheTop() {
        // Never louder than -40 dBFS: drawn as quiet as it is.
        val levels = Waveforms.reduce(FloatArray(500) { rms(-40.0) }, Waveforms.LEVELS)
        assertEquals(15f / 35, levels[0], 0.01f)
    }

    @Test fun aSliceIsAsLoudAsItsLoudestMoment() {
        // A word shorter than its slice still shows: 30 slices of 10 frames,
        // one frame of sound in the second.
        val frames = FloatArray(300) { rms(-70.0) }.also { it[15] = rms(-10.0) }
        val levels = Waveforms.reduce(frames, 30)
        assertEquals(1f, levels[1], 0.001f)
        assertEquals(0f, levels[0], 0.001f)
    }

    @Test fun aRecordingShorterThanItsLevelsFillsThemAll() {
        val levels = Waveforms.reduce(FloatArray(10) { i -> if (i == 9) rms(-10.0) else rms(-70.0) }, Waveforms.LEVELS)
        assertEquals(Waveforms.LEVELS, levels.size)
        assertEquals(1f, levels.last(), 0.001f)
        assertEquals(0f, levels.first(), 0.001f)
    }

    @Test fun nothingDecodedIsFlatToo() {
        assertTrue(Waveforms.reduce(FloatArray(0), 40).all { it == 0f })
    }

    @Test fun framesAreTwentiethsOfASecondOfBothChannels() {
        // A second of 48 kHz stereo: a 1 kHz tone on the left, silence on the
        // right - either side talking is a sound.
        val frames = Waveforms.FrameLevels(48_000, 2)
        val pcm = ShortArray(48_000 * 2) { i ->
            if (i % 2 == 0) (16_384 * sin(2 * PI * 1_000 * (i / 2) / 48_000)).toInt().toShort() else 0
        }
        // In uneven chunks, as a decoder hands them out.
        var at = 0
        for (size in generateSequence(1_234) { it + 777 }) {
            val n = minOf(size, pcm.size - at)
            frames.feed(ShortBuffer.wrap(pcm, at, n))
            at += n
            if (at == pcm.size) break
        }
        val peaks = frames.peaks()
        assertEquals(50, peaks.size)
        // RMS over both channels: the tone's 16384/sqrt(2), halved in power.
        assertEquals(16_384 / 2.0, peaks[25].toDouble(), 30.0)
    }

    @Test fun aPartFrameAtTheEndCounts() {
        val frames = Waveforms.FrameLevels(48_000, 1)
        frames.feed(ShortBuffer.wrap(ShortArray(960 + 100) { 1_000 }))
        assertEquals(2, frames.peaks().size)
    }

    @Test fun theDecodersOwnFormatCountsOnlyBeforeAnySound() {
        val frames = Waveforms.FrameLevels(48_000, 2)
        frames.reshape(16_000, 1)
        frames.feed(ShortBuffer.wrap(ShortArray(320) { 100 }))
        frames.reshape(48_000, 2)
        frames.feed(ShortBuffer.wrap(ShortArray(320) { 100 }))
        assertEquals(2, frames.peaks().size)
    }

    @Test fun theCacheKeepsAByteALevel() {
        val levels = List(Waveforms.LEVELS) { it / (Waveforms.LEVELS - 1f) }
        val bytes = Waveforms.toBytes(levels)
        assertEquals(Waveforms.LEVELS, bytes.size)
        val back = Waveforms.fromBytes(bytes)!!
        assertArrayEquals(levels.toFloatArray(), back.toFloatArray(), 1 / 255f)
        // A file of another size is not one of them.
        assertNull(Waveforms.fromBytes(ByteArray(12)))
    }

    @Test fun aBarIsItsLoudestLevel() {
        val levels = listOf(0f, 0.2f, 0.9f, 0.1f, 0f, 0.5f)
        assertEquals(0.9f, loudestIn(levels, 0, 2), 0f)
        assertEquals(0.5f, loudestIn(levels, 1, 2), 0f)
        // More bars than levels: each bar shows its level.
        assertEquals(0.9f, loudestIn(levels, 5, 12), 0f)
        assertEquals(0f, loudestIn(emptyList(), 0, 3), 0f)
    }

    @Test fun aLongCallIsWorkedOutSeveralStretchesAtOnce() {
        // One decoder for a short call: each costs more to start than a
        // short call takes to read.
        assertEquals(1, Waveforms.workersFor(20_000_000L, 8))
        assertEquals(2, Waveforms.workersFor(30_000_000L, 8))
        assertEquals(4, Waveforms.workersFor(1_800_000_000L, 8))
        // Never more than half the cores.
        assertEquals(2, Waveforms.workersFor(1_800_000_000L, 4))
        assertEquals(1, Waveforms.workersFor(1_800_000_000L, 1))
    }

    @Test fun stretchesArePutWhereTheyFall() {
        // Two stretches of a 2 s recording: the second starts a little before
        // its share, where the file let its decoder start, and the first -
        // decoded straight through - wins where both have a frame.
        val first = Waveforms.Part(0, FloatArray(50) { 1f })
        val second = Waveforms.Part(48, FloatArray(52) { 2f })
        val frames = Waveforms.place(listOf(first, second))
        assertEquals(100, frames.size)
        assertEquals(1f, frames[49], 0f)
        assertEquals(2f, frames[50], 0f)
        assertEquals(2f, frames[99], 0f)
        assertEquals(1f, frames[48], 0f)
        // As long as the file says the recording is, decoded in one stretch or
        // several - or what was decoded, when it does not say.
        assertEquals(98, Waveforms.place(listOf(first, second), 98).size)
        assertEquals(2f, Waveforms.place(listOf(first, second), 98)[97], 0f)
        assertEquals(120, Waveforms.place(listOf(first), 120).size)
    }

    /** The sound at each sample of the recording - one channel's value
     *  repeated on both - so two ways of cutting it can be compared. */
    private fun sound(sample: Long): Short = (if ((sample / 2_000) % 2 == 0L) 400 else 6_000).toShort()

    /** A stretch fed chunks of [chunk] samples (per channel) from timeline
     *  sample [from], the first trimmed by [trim], each stamped with when its
     *  packet starts. */
    private fun stretch(from: Long, chunk: Int, trim: Int, until: Long): Waveforms.Part {
        val stretch = Waveforms.Stretch(48_000, 2)
        var at = from
        var first = true
        while (at < until) {
            val start = if (first) at + trim else at
            val n = (at + chunk - start).toInt()
            val pcm = ShortArray(n * 2) { sound(start + it / 2) }
            stretch.chunk(ShortBuffer.wrap(pcm), at * 1_000_000 / 48_000)
            at += chunk
            first = false
        }
        return stretch.finish()
    }

    @Test fun aStretchIsPutOnTheRecordingsGridFromItsSecondChunk() {
        // Joined packets (120 ms) with the file's pre-skip trimmed off the
        // first: the stretch's sound begins at sample 312, so its first
        // whole twentieth is the recording's second.
        val part = stretch(from = 0, chunk = 5_760, trim = 312, until = 48_000)
        assertEquals(1, part.firstFrame)
        // Frame 1 is samples 960 to 1919: all the quiet first half-period.
        assertEquals(400f, part.frames[0], 0.5f)
    }

    @Test fun twoTrimsOfTheSameSoundGiveTheSameFrames() {
        // Measured on the Honor, 2026-10-23: one packet at a time the decoder
        // trims 624 samples off the start, joined six to a packet 312 - the
        // same sound otherwise. On the grid, the frames are the same.
        val plain = stretch(from = 0, chunk = 960, trim = 624, until = 96_000)
        val joined = stretch(from = 0, chunk = 5_760, trim = 312, until = 96_000)
        assertEquals(plain.firstFrame, joined.firstFrame)
        assertArrayEquals(plain.frames, joined.frames.copyOf(plain.frames.size), 0.01f)
    }

    @Test fun aStretchDecodedApartFitsTheOneBefore() {
        // The same sound decoded straight through, and as a stretch whose
        // decoder started at 19.0535 s (where the file let it), trimmed again.
        val whole = stretch(from = 0, chunk = 5_760, trim = 312, until = 1_200_000)
        val later = stretch(from = 914_568, chunk = 5_760, trim = 312, until = 1_200_000)
        val placed = Waveforms.place(listOf(whole), 0)
        for (f in later.frames.indices.drop(1).take(200)) {
            assertEquals("frame ${later.firstFrame + f}", placed[later.firstFrame + f], later.frames[f], 0.01f)
        }
    }

    @Test fun aStretchOfOneChunkIsWhereItsTimeSays() {
        val stretch = Waveforms.Stretch(48_000, 2)
        stretch.chunk(ShortBuffer.wrap(ShortArray(1_920) { 100 }), 400_000)
        assertEquals(20, stretch.finish().firstFrame)
    }

    @Test fun aStretchsOwnTimeIsCheckedAgainstTheOneBefore() {
        // Frames of speech, all different; the second stretch really starts
        // at frame 150, but its time says 149 - a packet early, as Android
        // says of the app's own files after a jump.
        val sound = FloatArray(400) { (200 + (it * 7_919 % 97) * 30).toFloat() }
        val first = Waveforms.Part(0, sound.copyOf(200))
        val second = Waveforms.Part(149, sound.copyOfRange(150, 400))
        assertArrayEquals(sound, Waveforms.place(listOf(first, second), 400), 0f)
        // And a frame late, the other way.
        val late = Waveforms.Part(151, sound.copyOfRange(150, 400))
        assertArrayEquals(sound.copyOf(399), Waveforms.place(listOf(first, late), 400).copyOf(399), 0f)
    }

    @Test fun aQuietOverlapLeavesAStretchWhereItsTimeSays() {
        // Nothing in it to line up by.
        val first = Waveforms.Part(0, FloatArray(200) { 5f })
        val second = Waveforms.Part(149, FloatArray(251) { if (it < 60) 5f else 900f })
        val placed = Waveforms.place(listOf(first, second), 400)
        assertEquals(900f, placed[149 + 60], 0f)
        assertEquals(5f, placed[149 + 59], 0f)
    }
}
