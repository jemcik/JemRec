package com.jemcik.jemrec.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

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
 * All of it runs through Android's own MediaCodec - no libraries, nothing
 * bundled - the same building blocks the recorder already uses.
 */
object Transcoder {

    private const val TAG = "JemRec"
    private const val OUTPUT_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L

    /**
     * Transcode [source] (Ogg/Opus) to AAC in [output] (.m4a).
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
                            if (encInfo.size > 0 && muxerStarted) {
                                val buf = enc.getOutputBuffer(idx)!!
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                mux.writeSampleData(muxTrack, buf, encInfo)
                            }
                            val eos = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            enc.releaseOutputBuffer(idx, false)
                            if (eos) { encoderDone = true; return }
                        }
                    }
                }
            }

            // Feed a block of PCM to the encoder, splitting it across as many
            // input buffers as it takes - a decoder buffer can be larger than an
            // encoder buffer - and draining the encoder whenever no input slot is
            // free, so the two can never deadlock waiting on each other.
            fun feedEncoderPcm(pcm: ByteBuffer, size: Int, basePts: Long) {
                var consumed = 0
                while (consumed < size) {
                    val idx = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (idx < 0) { drainEncoder(); continue }
                    val dst = enc.getInputBuffer(idx)!!
                    dst.clear()
                    val chunk = minOf(size - consumed, dst.remaining())
                    val savedLimit = pcm.limit()
                    pcm.limit(pcm.position() + chunk)
                    dst.put(pcm)
                    pcm.limit(savedLimit)
                    enc.queueInputBuffer(idx, 0, chunk, basePts + bytesToUs(consumed), 0)
                    consumed += chunk
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
            var extractorDone = false
            var decoderDone = false

            while (!encoderDone && !shouldCancel()) {
                // Ogg pages -> decoder.
                if (!extractorDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Decoded PCM -> encoder.
                if (!decoderDone) {
                    val idx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (idx >= 0) {
                        val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0) {
                            val pcm = dec.getOutputBuffer(idx)!!
                            pcm.position(decInfo.offset)
                            pcm.limit(decInfo.offset + decInfo.size)
                            feedEncoderPcm(pcm, decInfo.size, decInfo.presentationTimeUs)
                        }
                        dec.releaseOutputBuffer(idx, false)
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
