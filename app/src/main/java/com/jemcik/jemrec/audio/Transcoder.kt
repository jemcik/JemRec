package com.jemcik.jemrec.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.jemcik.jemrec.playback.BothEars
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Convert a recording's Ogg/Opus to AAC in an .m4a, on demand, for sharing.
 *
 * WHY THIS EXISTS
 *
 * Recordings are kept as Opus in an Ogg container - the best codec for speech,
 * and the only one Android's MediaMuxer will write. That plays natively on
 * Android, Linux, the web and Windows, but NOT in Apple's stock apps: a Mac's
 * Quick Look and QuickTime, and an iPhone's default player, will not open a
 * .ogg. So when a recording is SHARED - and only then - it is transcoded to
 * AAC in an .m4a, which every platform plays without a third-party app. The
 * Opus original on disk is never touched; the .m4a is a throwaway the share
 * grant reads from.
 *
 * It is a full decode-then-encode: there is no lossless path from Opus to AAC,
 * so the audio is decoded to PCM and re-encoded. One extra generation of lossy
 * compression on a phone call is inaudible, and the price of universal playback.
 *
 * With Settings' "Both sides in both ears" on, the copy has them so, mixed as
 * JemRec plays them (BothEars): whoever it is sent to hears the call as the
 * user does, one earbud included. The original keeps the sides apart.
 *
 * All of it runs through Android's own MediaCodec - no libraries, nothing
 * bundled - the same building blocks the recorder already uses. Each codec
 * buffer is held for one copy only, as PcmDecoder's are (CodecBuffers.kt):
 * the file read, the mix, the waits for the encoder and the muxer's all work
 * on our own copies.
 */
object Transcoder {

    private const val TAG = "JemRec"
    private const val OUTPUT_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L

    /**
     * Transcode [source] (Ogg/Opus) to AAC in [output] (.m4a), both sides in
     * both ears if [bothEars].
     *
     * Returns true on success. On failure OR cancellation it deletes the
     * half-written file and returns false, so a broken .m4a is never left to be
     * shared. [shouldCancel] is polled once per pump - checked between short
     * blocking codec calls, so a cancel takes effect in milliseconds.
     */
    fun toM4a(
        context: Context,
        source: Uri,
        output: File,
        bothEars: Boolean,
        shouldCancel: () -> Boolean = { false },
    ): Boolean {
        output.parentFile?.mkdirs()
        var completed = false
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                Log.w(TAG, "transcode: no audio track in $source")
                return false
            }
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
                .apply { configure(inFormat, null, null, 0); start() }
            decoder = dec

            // Match the source's rate and channels so nothing is resampled; only
            // the codec changes.
            val outFormat = MediaFormat.createAudioFormat(OUTPUT_MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            val enc = MediaCodec.createEncoderByType(OUTPUT_MIME)
                .apply { configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            encoder = enc

            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            var muxTrack = -1
            var muxerStarted = false
            var encoderDone = false
            val encInfo = MediaCodec.BufferInfo()
            // An encoded frame, copied out of the encoder for the muxer:
            // MediaMuxer keeps the buffer it is given until its writer's
            // thread has taken it (MediaAdapter::pushBuffer waits, AOSP 12).
            var frame = ByteBuffer.allocateDirect(0)

            // How long, in microseconds, a run of interleaved 16-bit PCM lasts -
            // used to date each chunk fed to the encoder.
            fun bytesToUs(bytes: Int): Long =
                bytes.toLong() * 1_000_000L / (sampleRate.toLong() * channels * 2)

            // Move whatever the encoder has ready into the muxer. Starts the
            // muxer on the format change that carries the AAC csd, and marks the
            // encoder done on end-of-stream. Non-blocking: returns when the
            // encoder has nothing more for now.
            fun drainEncoder() {
                while (true) {
                    val idx = enc.dequeueOutputBuffer(encInfo, 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrack = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        idx >= 0 -> {
                            // The codec-config buffer is metadata, already taken
                            // into the track's format; it must not be written as a
                            // sample.
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encInfo.size = 0
                            }
                            val write = encInfo.size > 0 && muxerStarted
                            if (write) {
                                // Room first: a buffer taken is held for the copy alone.
                                if (frame.capacity() < encInfo.size) frame = ByteBuffer.allocateDirect(encInfo.size)
                                val buf = enc.getOutputBuffer(idx)!!
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                frame.clear()
                                frame.put(buf)
                                frame.flip()
                            }
                            val eos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            enc.releaseOutputBuffer(idx, false)
                            if (write) {
                                encInfo.set(0, encInfo.size, encInfo.presentationTimeUs, encInfo.flags)
                                mux.writeSampleData(muxTrack, frame, encInfo)
                            }
                            if (eos) { encoderDone = true; return }
                        }
                    }
                }
            }

            // Feed a block of PCM - our copy of the decoder's - to the
            // encoder, splitting it across as many input buffers as it takes
            // - a decoder buffer can be larger than an encoder buffer - and
            // draining the encoder whenever no input slot is free, so the two
            // can never deadlock waiting on each other. Split on whole frames,
            // as when each piece was mixed on its own.
            fun feedEncoderPcm(samples: ShortArray, count: Int, basePts: Long) {
                var consumed = 0
                while (consumed < count) {
                    val idx = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (idx < 0) { drainEncoder(); continue }
                    val dst = enc.getInputBuffer(idx)!!
                    dst.clear()
                    val n = encoderPiece(dst.remaining(), channels, count - consumed)
                    dst.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, consumed, n)
                    enc.queueInputBuffer(idx, 0, n * 2, basePts + bytesToUs(consumed * 2), 0)
                    consumed += n
                }
            }

            fun signalEncoderEos(pts: Long) {
                while (true) {
                    val idx = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        return
                    }
                    drainEncoder()
                }
            }

            val decInfo = MediaCodec.BufferInfo()
            var decoderDone = false
            // The file's packets, one to a buffer, each read before the
            // buffer is taken; one buffer a pump, as long as one is free in
            // 10 ms.
            val feeder = Feeder(Plain(FilePackets(extractor), Long.MAX_VALUE), MediaCodecInput(dec, TIMEOUT_US))
            val copy = PcmCopy()

            while (!encoderDone && !shouldCancel()) {
                // Ogg pages -> decoder.
                if (!feeder.done) feeder.feed(most = 1)

                // Decoded PCM -> encoder: copied out, the decoder's buffer
                // given back, then mixed and fed from the copy.
                if (!decoderDone) {
                    val idx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (idx >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pcm = if (decInfo.size > 0) copy.of(decInfo.offset, decInfo.size) { dec.getOutputBuffer(idx)!! } else null
                        dec.releaseOutputBuffer(idx, false)
                        if (pcm != null) {
                            if (bothEars) BothEars.mix(pcm, channels)
                            feedEncoderPcm(pcm.array(), pcm.remaining(), decInfo.presentationTimeUs)
                        }
                        if (eos) {
                            decoderDone = true
                            signalEncoderEos(decInfo.presentationTimeUs)
                        }
                    }
                }

                // Encoded AAC -> muxer.
                drainEncoder()
            }

            // Natural completion only if the encoder actually drained to EOS -
            // a cancelled loop breaks with encoderDone still false.
            completed = encoderDone
            return completed
        } catch (t: Throwable) {
            Log.w(TAG, "transcode to m4a failed", t)
            return false
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            // Cancelled or failed: no whole .m4a exists, so leave none behind.
            if (!completed) runCatching { output.delete() }
        }
    }
}
