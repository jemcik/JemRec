package com.jemcik.jemrec.playback

/**
 * Where to send the player for a seek to a place, so that the first sound
 * heard after it is that place's.
 *
 * RFC 7845 (4.6): decode from at least 3840 samples - 80 ms at 48 kHz -
 * before a seek's target, and drop what comes before it. Android's Opus
 * decoder drops those 80 ms itself after every flush - every seek once it
 * has decoded (AOSP C2SoftOpusDec.onFlush_sm: mSamplesToDiscard =
 * mSeekPreRoll, Media3 handing it the 80 ms in csd-2) - but Media3's Ogg
 * reader feeds it from the packet that holds the target, none before
 * (StreamReader, Media3 1.11.1). So the 80 ms dropped were the target's own:
 * measured by the sound itself on the Android 12 and 17 emulators
 * (2026-11-20, a probe matching what the sink was given after each of 20
 * seeks against a straight decode), the sound after a seek began 53.5-73.5 ms
 * after the target on 12 and 73.5-92.5 ms after it on 17. Sent 80 ms early,
 * the decoder's own drop ends at the target.
 *
 * Only once the decoder is up: a seek before it has decoded anything starts
 * a fresh decoder, which drops only the stream's pre-skip, and lands where it
 * is sent. Which is also why a file opened again where it was (a move to the
 * earpiece, the both-ears switch) is opened there, not opened and then sent:
 * a decoder up before the seek arrived was flushed by it - on the Android 17
 * emulator one reopen of three in a run so lost 74 ms (RecordingPlayer.open,
 * 2026-11-20).
 * Near the start there is nothing before to send it to: a seek to
 * the first 80 ms, a replay from the start included, still loses up to 80 ms
 * (measured: heard from 73.5 ms after a seek to 0). The decoder's own rule
 * for a seek to zero - drop only the pre-skip - never applies: it is keyed on
 * an input timestamp of 0, and ExoPlayer stamps every sample past its
 * renderer offset of 1e12 us (Media3 1.11.1 MediaPeriodQueue).
 */
internal object OpusPreRoll {

    /** RFC 7845's 3840 samples at 48 kHz. */
    const val MS = 80L

    /** Where to send the player for [targetMs], its decoder [decoderUp]. */
    fun seekFrom(targetMs: Long, decoderUp: Boolean): Long =
        if (decoderUp) maxOf(0L, targetMs - MS) else targetMs
}
