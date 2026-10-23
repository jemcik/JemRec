package com.jemcik.jemrec.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
     * Decode the first audio track of [uri] - or the part of it from [fromUs]
     * (where the file can start, just before) to [untilUs]. [onFormat] gets
     * its sample rate and channels - from the file first, then again if the
     * decoder says otherwise; [onPcm] each chunk, interleaved, with when in
     * the recording it starts, and says whether to go on. With [join], Opus
     * packets go to the decoder several to a packet (OpusPackets): the same
     * sound, in fewer trips to the decoder's process. Returns at the end,
     * when told to stop, or when a decoder that has taken the end of the
     * input goes quiet. Throws what Android throws for a file it cannot read.
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
            // More than two channels are several streams to a packet, framed
            // otherwise: those go as they come.
            val input = if (join && mime == OPUS && channels <= 2) Joining(extractor, untilUs) else Plain(extractor, untilUs)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            // A decoder that has taken the end of the input and then goes
            // quiet must not hold this loop for ever.
            var idleAfterInput = 0
            // EVERY FREE INPUT BUFFER AT ONCE, then every chunk that is
            // ready. The decoder runs in a process of its own: handed one
            // packet and waited on for that packet's sound, it stood idle in
            // between. Only when neither side has anything does this wait.
            decoding@ while (idleAfterInput < 500) {
                var worked = false
                while (!inputDone) {
                    val idx = dec.dequeueInputBuffer(0)
                    if (idx < 0) break
                    val buffer = dec.getInputBuffer(idx)!!
                    val atUs = input.next(buffer)
                    if (atUs < 0) {
                        dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        dec.queueInputBuffer(idx, 0, buffer.position(), atUs, 0)
                    }
                    worked = true
                }
                while (true) {
                    val out = dec.dequeueOutputBuffer(info, if (worked) 0 else TIMEOUT_US)
                    when {
                        out == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputDone && !worked) idleAfterInput++
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
                            var goOn = true
                            if (info.size > 0) {
                                val pcm = dec.getOutputBuffer(out)!!
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                goOn = onPcm(pcm.order(ByteOrder.nativeOrder()).asShortBuffer(), info.presentationTimeUs)
                            }
                            dec.releaseOutputBuffer(out, false)
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

    /** What goes into the decoder's input buffers. */
    private interface Input {
        /** Fill [buffer] - its position left at the end of what was put - and
         *  say when in the recording that starts; -1 at the end. */
        fun next(buffer: ByteBuffer): Long
    }

    /** The file's packets, one to a buffer, as they are. */
    private class Plain(private val extractor: MediaExtractor, private val untilUs: Long) : Input {
        override fun next(buffer: ByteBuffer): Long {
            val atUs = extractor.sampleTime
            if (atUs < 0 || atUs >= untilUs) return -1
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return -1
            buffer.position(size)
            extractor.advance()
            return atUs
        }
    }

    /**
     * The file's Opus packets, joined while they may share one (OpusPackets):
     * read back to back into one array, with their sizes, and written into
     * the decoder's buffer from there.
     */
    private class Joining(private val extractor: MediaExtractor, private val untilUs: Long) : Input {
        private val read = ByteBuffer.allocate(64 * 1024)
        private val sizes = IntArray(OpusPackets.MAX_FRAMES)
        /** A packet read ahead that did not fit the one before, moved to the
         *  start of [read]: its size, or 0, and when it starts. */
        private var held = 0
        private var heldAtUs = 0L

        /** The next packet, read in at [at]: its size, or -1 at the end - or
         *  where there is no room for it after the others, to be read first
         *  the next time. */
        private fun readAt(at: Int): Int {
            val atUs = extractor.sampleTime
            if (atUs < 0 || atUs >= untilUs) return -1
            if (at > 0 && extractor.sampleSize > read.capacity() - at) return -1
            val size = extractor.readSampleData(read, at)
            if (size < 0) return -1
            extractor.advance()
            return size
        }

        override fun next(buffer: ByteBuffer): Long {
            val atUs: Long
            if (held > 0) {
                sizes[0] = held
                atUs = heldAtUs
                held = 0
            } else {
                atUs = extractor.sampleTime
                val size = readAt(0)
                if (size < 0) return -1
                sizes[0] = size
            }
            val packets = read.array()
            val firstToc = packets[0].toInt()
            var count = 1
            var end = sizes[0]
            var units = OpusPackets.frameUnits(firstToc)
            // What joinInto may write, kept as it goes: 2, and a byte more
            // than each packet.
            var joined = 2 + sizes[0] + 1
            var heldAt = 0
            if (OpusPackets.joinable(firstToc, sizes[0])) {
                while (true) {
                    val nextAtUs = extractor.sampleTime
                    val size = readAt(end)
                    if (size < 0) break
                    val toc = packets[end].toInt()
                    if (!OpusPackets.fits(firstToc, count, units, toc, size) || joined + size + 1 > buffer.capacity()) {
                        held = size
                        heldAtUs = nextAtUs
                        heldAt = end
                        break
                    }
                    sizes[count++] = size
                    end += size
                    units += OpusPackets.frameUnits(toc)
                    joined += size + 1
                }
            }
            buffer.clear()
            OpusPackets.joinInto(packets, sizes, count, buffer)
            if (held > 0) System.arraycopy(packets, heldAt, packets, 0, held)
            return atUs
        }
    }
}
