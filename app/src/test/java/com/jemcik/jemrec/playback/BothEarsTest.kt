package com.jemcik.jemrec.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ShortBuffer

/** Both sides in both ears: each ear the two at 1/sqrt(2), held at full
 *  scale, the way Media3's mixer plays it. */
class BothEarsTest {

    private fun mixed(vararg samples: Int, channels: Int = 2): ShortArray {
        val pcm = ShortBuffer.wrap(ShortArray(samples.size) { samples[it].toShort() })
        BothEars.mix(pcm, channels)
        return pcm.array()
    }

    @Test fun eachEarHearsBothSides() {
        // You alone on the left, then the other person alone on the right.
        assertArrayEquals(shortArrayOf(7071, 7071, 7071, 7071), mixed(10_000, 0, 0, 10_000))
    }

    @Test fun oneVoiceKeepsItsLoudnessAcrossTwoEars() {
        // Its power over the two ears is what it had in one.
        val one = 20_000.0
        val each = mixed(20_000, 0)[0].toDouble()
        assertEquals(one * one, 2 * each * each, one * one * 0.001)
    }

    @Test fun bothTalkingIsHeldAtFullScale() {
        assertArrayEquals(shortArrayOf(32767, 32767), mixed(30_000, 30_000))
        assertArrayEquals(shortArrayOf(-32768, -32768), mixed(-30_000, -30_000))
    }

    @Test fun towardZeroAsACastTakesIt() {
        // 3 x 0.7071 = 2.12 and -2.12: 2 and -2, as Media3's (short) cast.
        assertArrayEquals(shortArrayOf(2, 2, -2, -2), mixed(1, 2, -1, -2))
    }

    @Test fun onlyStereoIsMixed() {
        assertArrayEquals(shortArrayOf(100, 200, 300), mixed(100, 200, 300, channels = 1))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6), mixed(1, 2, 3, 4, 5, 6, channels = 3))
    }

    @Test fun fromThePositionToTheLimitOnly() {
        val pcm = ShortBuffer.wrap(shortArrayOf(1000, 0, 1000, 0, 1000, 0))
        pcm.position(2).limit(4)
        BothEars.mix(pcm, 2)
        assertArrayEquals(shortArrayOf(1000, 0, 707, 707, 1000, 0), pcm.array())
    }

    @Test fun anOddSampleAtTheEndIsLeftAlone() {
        assertArrayEquals(shortArrayOf(707, 707, 1000), mixed(1000, 0, 1000))
    }

    @Test fun theMatrixIsTheSameGain() {
        assertArrayEquals(floatArrayOf(BothEars.GAIN, BothEars.GAIN, BothEars.GAIN, BothEars.GAIN), BothEars.MATRIX, 0f)
        assertEquals(0.7071f, BothEars.GAIN, 0.0001f)
    }
}
