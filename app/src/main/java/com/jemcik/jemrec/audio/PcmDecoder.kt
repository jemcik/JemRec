package com.jemcik.jemrec.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ShortBuffer

/**
 * A recording decoded to 16-bit PCM, chunk by chunk, with Android's own
 * decoder - Opus included, so no library and no native code: what the silence
 * check measures ([AudioCheck]) and the player's waveform draws ([Waveforms]).
 */
internal object PcmDecoder {

    private const val TIMEOUT_US = 10_000L
    private const val OPUS = "audio/opus"

    /** A recording's audio track, as the file describes it. */
    data class Track(val mime: String, val durationUs: Long, val channels: Int)

    /** The first audio track of [uri], without decoding it; null if there is
     *  none. Throws what Android throws for a file it cannot read. */
    fun probe(context: Context, uri: Uri): Track? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val format = audioTrack(extractor)?.let(extractor::getTrackFormat) ?: return null
            return Track(
                mime = format.getString(MediaFormat.KEY_MIME)!!,
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L,
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    /**
     * Called before each packet is read from the file, and before each chunk
     * goes to onPcm: null in a shipped build. The debug build sets both to
     * hold the decode up while the checks stop the app (tools/
     * emulator_checks.py, decoder_pause): where either sat between taking a
     * codec's buffer and handing it back, a release of the codec under it
     * crashed the process (CodecBuffers.kt).
     */
    @Volatile
    var beforeRead: (() -> Unit)? = null

    @Volatile
    var beforePcm: (() -> Unit)? = null

    /**
     * Decode the first audio track of [uri] - or the part of it from [fromUs]
     * (where the file can start, just before) to [untilUs]. [onFormat] gets
     * its sample rate and channels - from the file first, then again if the
     * decoder says otherwise; [onPcm] each chunk, interleaved, with when in
     * the recording it starts, and says whether to go on - the chunk is ours,
     * good until the next ([PcmCopy]). With [join], Opus packets go to the
     * decoder several to a packet (OpusPackets): the same sound, in fewer
     * trips to the decoder's process. Returns at the end, when told to stop,
     * or when a decoder that has taken the end of the input goes quiet.
     * Throws what Android throws for a file it cannot read.
     */
    fun decode(
        context: Context,
        uri: Uri,
        onFormat: (sampleRate: Int, channels: Int) -> Unit,
        onPcm: (pcm: ShortBuffer, atUs: Long) -> Boolean,
        fromUs: Long = 0L,
        untilUs: Long = Long.MAX_VALUE,
        join: Boolean = false,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = audioTrack(extractor) ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // Held before it is configured, so the finally releases it if
            // configure or start throws: it leaked until garbage collection,
            // and the list's fill-in met the same file at every refresh.
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(format, null, null, 0)
            dec.start()
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            onFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
            val packets = FilePackets(extractor)
            // More than two channels are several streams to a packet, framed
            // otherwise: those go as they come.
            val input = if (join && mime == OPUS && channels <= 2) Joining(packets, untilUs) else Plain(packets, untilUs)
            val feeder = Feeder(input, MediaCodecInput(dec, 0))
            val copy = PcmCopy()

            val info = MediaCodec.BufferInfo()
            // A decoder that has taken the end of the input and then goes
            // quiet must not hold this loop for ever.
            var idleAfterInput = 0
            // EVERY FREE INPUT BUFFER AT ONCE, then every chunk that is
            // ready. The decoder runs in a process of its own: handed one
            // packet and waited on for that packet's sound, it stood idle in
            // between. Only when neither side has anything does this wait.
            decoding@ while (idleAfterInput < 500) {
                var worked = feeder.feed()
                while (true) {
                    val out = dec.dequeueOutputBuffer(info, if (worked) 0 else TIMEOUT_US)
                    when {
                        out == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (feeder.done && !worked) idleAfterInput++
                            break
                        }
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val actual = dec.outputFormat
                            onFormat(
                                actual.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            )
                            worked = true
                        }
                        out >= 0 -> {
                            // Copied out and given back before anything
                            // reads it.
                            val pcm = if (info.size > 0) copy.of(info.offset, info.size) { dec.getOutputBuffer(out)!! } else null
                            dec.releaseOutputBuffer(out, false)
                            if (pcm != null) beforePcm?.invoke()
                            val goOn = pcm == null || onPcm(pcm, info.presentationTimeUs)
                            if (!goOn || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@decoding
                            worked = true
                        }
                        // The deprecated INFO_OUTPUT_BUFFERS_CHANGED: nothing
                        // to do with getOutputBuffer(index).
                        else -> worked = true
                    }
                }
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }
}
