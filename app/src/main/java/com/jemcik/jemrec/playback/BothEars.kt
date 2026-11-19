package com.jemcik.jemrec.playback

import java.nio.ShortBuffer

/**
 * Both sides of a call in both ears: the two channels of a recording mixed
 * into each, as it plays (RecordingPlayer, through Media3's
 * ChannelMixingAudioProcessor) and in the .m4a Share makes (Transcoder) - one
 * gain, one formula, so the copy sounds as JemRec plays it.
 *
 * WHY. A recording keeps the two sides apart - you on the left and the other
 * person on the right for messenger calls and on phones like the Honor - and
 * played as it is, each ear heard one person: one earbud, half the call, and
 * a message from the answering machine, its left side silent, in one ear
 * only (discussion #92). The file stays as it is; only what is heard is mixed.
 *
 * [GAIN]: each side's share, 1/sqrt(2) - the mix that keeps the loudness
 * (Media3 calls it constant power). Chosen by ear on the Honor, 2026-11-16,
 * over 0.5 (every voice 6 dB down) and 1.0 (a plain sum). Over the 101
 * recordings there (7.3 hours) a plain sum would have clipped 113,936
 * samples in 27 of them, 21 of those loud messenger calls; this one 922, in
 * 6 - about 19 ms in all - and none was heard. What does go past full scale
 * is held at it, as Media3 holds its 16-bit output (AudioMixingUtil.mix).
 */
internal object BothEars {

    const val GAIN = 0.70710677f

    /** Media3's matrix for two channels in, two out: each out channel the
     *  two in at [GAIN], row by row. */
    val MATRIX = floatArrayOf(GAIN, GAIN, GAIN, GAIN)

    /** Mix [pcm] - interleaved 16-bit, [channels] to a frame, from its
     *  position to its limit - in place: each channel of a stereo frame the
     *  two at [GAIN], held to full scale. Anything but stereo is left alone. */
    fun mix(pcm: ShortBuffer, channels: Int) {
        if (channels != 2) return
        var at = pcm.position()
        val end = pcm.limit() - 1
        while (at < end) {
            val mixed = held((pcm.get(at) + pcm.get(at + 1)) * GAIN)
            pcm.put(at, mixed)
            pcm.put(at + 1, mixed)
            at += 2
        }
    }

    /** [value] as a 16-bit sample: toward zero, as a cast takes it, and no
     *  further than full scale. */
    private fun held(value: Float): Short =
        value.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
}
