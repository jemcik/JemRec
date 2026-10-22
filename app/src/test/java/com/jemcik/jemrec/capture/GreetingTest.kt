package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** A take made into a greeting: the silence trimmed, the level evened. */
class GreetingTest {

    private val rate = Greeting.RATE

    /** [silence] s of hiss, [speech] s of a "voice" at [amplitude], [silence] s of hiss. */
    private fun take(silence: Double, speech: Double, amplitude: Double, hiss: Double = 20.0): ByteArray {
        val quiet = (silence * rate).toInt()
        val loud = (speech * rate).toInt()
        val samples = ShortArray(quiet * 2 + loud) { i ->
            val inSpeech = i in quiet until quiet + loud
            val v = if (inSpeech) amplitude * sin(2 * PI * 220 * i / rate) else hiss * sin(2 * PI * 50 * i / rate)
            v.toInt().toShort()
        }
        return Greeting.toBytes(samples)
    }

    private fun rmsDb(pcm: ByteArray): Double {
        val s = Greeting.toShorts(pcm)
        return Greeting.db(sqrt(s.sumOf { it.toDouble() * it } / s.size))
    }

    @Test fun silenceAtBothEndsIsTrimmed() {
        val result = Greeting.process(take(silence = 2.0, speech = 3.0, amplitude = 3_000.0))
        assertTrue(result is Greeting.Take.Usable)
        val seconds = (result as Greeting.Take.Usable).seconds
        // Three seconds of speech, with a short lead and tail - not seven.
        assertTrue("$seconds", seconds in 3.0..3.6)
    }

    @Test fun aQuietTakeIsBroughtUpToSpeechLevel() {
        val result = Greeting.process(take(silence = 0.5, speech = 3.0, amplitude = 500.0)) as Greeting.Take.Usable
        assertTrue(rmsDb(result.pcm) > -24)
    }

    @Test fun aLoudTakeIsBroughtDownAndNeverClips() {
        val result = Greeting.process(take(silence = 0.5, speech = 3.0, amplitude = 30_000.0)) as Greeting.Take.Usable
        val peak = Greeting.toShorts(result.pcm).maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak <= 32768 * 0.71 + 1)
    }

    @Test fun nothingSaidIsSilent() {
        assertEquals(Greeting.Take.Silent, Greeting.process(take(silence = 2.0, speech = 0.0, amplitude = 0.0)))
        assertEquals(Greeting.Take.Silent, Greeting.process(ByteArray(10)))
    }

    /** A rustle 13 dB over a quiet room is still not a voice: under -50 dBFS
     *  nothing is, however quiet the room. */
    @Test fun aRustleInAQuietRoomIsSilent() {
        assertEquals(Greeting.Take.Silent, Greeting.process(take(silence = 2.0, speech = 0.6, amplitude = 90.0)))
    }

    @Test fun aCoughIsTooShort() {
        assertEquals(Greeting.Take.TooShort, Greeting.process(take(silence = 1.0, speech = 0.2, amplitude = 5_000.0)))
    }

    @Test fun bytesAndSamplesRoundTrip() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234)
        assertTrue(samples.contentEquals(Greeting.toShorts(Greeting.toBytes(samples))))
    }
}
