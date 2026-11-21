package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ogg.OggExtractor
import com.jemcik.jemrec.audio.OpusOggWriter
import com.jemcik.jemrec.audio.PcmDecoder
import com.jemcik.jemrec.playback.OpusPreRoll
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

// Where the sound after a seek begins, against where it was sent: the
// release check seek_preroll (tools/emulator_checks.py). Android's Opus
// decoder drops its 80 ms pre-roll after every flush, and Media3 feeds it from
// the target's own packet, so until 1.4 the sound began 54-93 ms after the
// target (the 12 and 17 emulators, 2026-11-20); OpusPreRoll sends the player
// 80 ms early once its decoder is up.
//
// Twenty seconds of noise - a different sample every time, so a match is
// found in one place only - encoded by Android's Opus encoder and written by
// JemRec's own OpusOggWriter, as a recording is; decoded straight through
// (PcmDecoder) for the reference; then played by an ExoPlayer built as
// RecordingPlayer builds its own (one MediaCodecAudioRenderer, OggExtractor,
// SeekParameters.EXACT) with a tee as its one processor, and sent where
// OpusPreRoll says: seeks while it plays, seeks while it is paused and then
// played; and opened at a place, as a reopen is (SessionPlayer.reopen,
// RecordingPlayer.open) - opened and then sent there, as reopens were until
// 1.4, one of three in a run on the 17 emulator lost 74 ms (2026-11-20): the decoder
// was up before the seek arrived. Where each seek's sound begins is found by
// matching 50 ms of it from 20 ms in against the reference, within 150 ms
// either way of its target: past its first packet, which a fresh decoder (a
// reopen) decodes with no pre-roll, so not quite as a straight decode does -
// the first reopen in the first run, on a packet's start, matched 0.85-0.87
// from its first sample (the 12 and 17 emulators, 2026-11-20). That first
// match is said too, as r0. RecordingPlayer's own use of OpusPreRoll is
// unit-tested (RecordingPlayerTest).
//   --es op seekcheck
// Out: one line per seek, "seek <way> <target ms> skip <ms> r <match> r0
// <first 50 ms' match>", and "seekcheck: done" - or what went wrong.
@OptIn(UnstableApi::class)
internal object SeekCheckOp : DiagOp {
    private const val RATE = 48_000
    private const val SECONDS = 20
    private val PLAYING = listOf(2_000L, 4_010L, 6_015L, 8_019L, 10_007L, 12_345L)
    private val PAUSED = listOf(14_000L, 15_013L, 16_505L)
    private val REOPENED = listOf(3_000L, 7_013L, 11_519L)

    override suspend fun run(appContext: Context, intent: Intent) {
        val file = File(appContext.cacheDir, "seekcheck.ogg")
        encodeNoise(file)
        val reference = decodeLeft(appContext, Uri.fromFile(file))
        val lines = StringBuilder("file ${file.length()} bytes, reference ${reference.size} frames\n")
        val match = Matcher(reference)
        Probe(appContext, Uri.fromFile(file)).use { probe ->
            probe.start()
            for (target in PLAYING) {
                val segment = probe.seek(target)
                Thread.sleep(700)
                lines.append(match.line("playing", target, probe.after(segment))).append('\n')
            }
            for (target in PAUSED) {
                probe.pause()
                Thread.sleep(200)
                val segment = probe.seek(target)
                Thread.sleep(300)
                probe.play()
                Thread.sleep(600)
                lines.append(match.line("paused", target, probe.after(segment))).append('\n')
            }
        }
        for (target in REOPENED) {
            Probe(appContext, Uri.fromFile(file), fromMs = target).use { probe ->
                probe.play()
                Thread.sleep(900)
                lines.append(match.line("reopened", target, probe.after(0))).append('\n')
            }
        }
        report(appContext, "seekcheck", lines.append("seekcheck: done").toString())
    }

    /** [SECONDS] of stereo noise through Android's Opus encoder (64 kbps, 20 ms
     *  packets), written by OpusOggWriter. */
    private fun encodeNoise(file: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 960 * 4)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val random = java.util.Random(SystemClock.elapsedRealtimeNanos())
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray()).put(1).put(2).putShort(312).putInt(RATE).putShort(0).put(0).array()
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            FileOutputStream(file).use { out ->
                val writer = OpusOggWriter(out.fd, head)
                val info = MediaCodec.BufferInfo()
                val frames = SECONDS * RATE
                var fed = 0
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val index = encoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = checkNotNull(encoder.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            val chunk = minOf(960, frames - fed)
                            if (chunk <= 0) {
                                encoder.queueInputBuffer(index, 0, 0, fed * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                repeat(chunk * 2) { buffer.putShort((random.nextGaussian() * 4_000).toInt().coerceIn(-32_000, 32_000).toShort()) }
                                encoder.queueInputBuffer(index, 0, chunk * 4, fed * 1_000_000L / RATE, 0)
                                fed += chunk
                            }
                        }
                    }
                    val index = encoder.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            checkNotNull(encoder.getOutputBuffer(index)).apply { position(info.offset) }.get(bytes)
                            writer.write(bytes, info.presentationTimeUs)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
                check(writer.finish()) { "the test file was not finished" }
            }
        } finally {
            encoder.release()
        }
    }

    /** The left channel, decoded straight through from the start. */
    private fun decodeLeft(context: Context, uri: Uri): DoubleArray {
        val out = DoubleArray((SECONDS + 1) * RATE)
        var size = 0
        var channels = 2
        PcmDecoder.decode(
            context, uri,
            onFormat = { _, c -> channels = c },
            onPcm = { pcm, _ ->
                var i = 0
                while (pcm.hasRemaining()) {
                    val sample = pcm.get()
                    if (i++ % channels == 0 && size < out.size) out[size++] = sample.toDouble()
                }
                true
            },
        )
        return out.copyOf(size)
    }

    /** Normalised correlation of a seek's first 50 ms against the reference. */
    private class Matcher(private val reference: DoubleArray) {
        private val n = 2_400
        private val sums = DoubleArray(reference.size + 1).also { for (i in reference.indices) it[i + 1] = it[i] + reference[i] }
        private val squares = DoubleArray(reference.size + 1).also { for (i in reference.indices) it[i + 1] = it[i] + reference[i] * reference[i] }

        fun line(way: String, targetMs: Long, left: DoubleArray?): String {
            if (left == null) return "seek $way $targetMs no sound"
            val lead = left.indexOfFirst { it != 0.0 }.takeIf { it >= 0 } ?: return "seek $way $targetMs no sound"
            if (left.size - lead < IN + n) return "seek $way $targetMs too little sound (${left.size - lead} frames)"
            val target = (targetMs * RATE / 1000).toInt()
            val (at, r) = find(left, lead + IN, target + IN)
            val (_, r0) = find(left, lead, target)
            return "seek $way $targetMs skip ${"%.1f".format((at - IN - target) * 1000.0 / RATE)} " +
                "r ${"%.3f".format(r)} r0 ${"%.3f".format(r0)}"
        }

        /** Where [n] frames of [left] from [from] match the reference best,
         *  near [around]; and how well. */
        private fun find(left: DoubleArray, from: Int, around: Int): Pair<Int, Double> {
            val head = DoubleArray(n) { left[from + it] }
            val mean = head.average()
            for (i in head.indices) head[i] -= mean
            val norm = sqrt(head.sumOf { it * it })
            var best = -2.0
            var at = -1
            val first = maxOf(0, around - 7_200)
            val until = minOf(reference.size - n, around + 7_200)
            for (o in first until until) {
                var dot = 0.0
                for (i in 0 until n) dot += head[i] * reference[o + i]
                val sum = sums[o + n] - sums[o]
                val energy = squares[o + n] - squares[o] - sum * sum / n
                val r = dot / (norm * sqrt(maxOf(energy, 1e-9)))
                if (r > best) {
                    best = r
                    at = o
                }
            }
            return at to best
        }

        companion object {
            /** Twenty ms - a packet - into the sound before matching. */
            const val IN = 960
        }
    }

    /** An ExoPlayer built as RecordingPlayer builds its own, with a tee, on a
     *  thread of its own, sent where OpusPreRoll says. */
    private class Probe(context: Context, uri: Uri, fromMs: Long = 0) : AutoCloseable {
        private val lock = Any()
        private val segments = mutableListOf<ByteArrayOutputStream>()
        private val thread = HandlerThread("seekcheck").apply { start() }
        private val handler = Handler(thread.looper)

        @Volatile private var decoderUp = false

        private val player: ExoPlayer = on {
            val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                    synchronized(lock) { segments += ByteArrayOutputStream() }
                }

                override fun handleBuffer(buffer: ByteBuffer) {
                    val bytes = ByteArray(buffer.remaining())
                    buffer.duplicate().get(bytes)
                    synchronized(lock) { (segments.lastOrNull() ?: ByteArrayOutputStream().also { segments += it }).write(bytes) }
                }
            })
            val sink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(tee)).build()
            val renderers = RenderersFactory { h, _, audio, _, _ ->
                arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, h, audio, sink))
            }
            val sources = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context), ExtractorsFactory { arrayOf(OggExtractor()) })
            ExoPlayer.Builder(context, renderers, sources)
                .setLooper(thread.looper)
                .setSeekParameters(SeekParameters.EXACT)
                .build().apply {
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long,
                        ) {
                            decoderUp = true
                        }

                        override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                            decoderUp = false
                        }
                    })
                    setMediaItem(MediaItem.fromUri(uri), fromMs)
                    prepare()
                }
        }

        private fun <T> on(block: () -> T): T {
            var result: Result<T>? = null
            val done = CountDownLatch(1)
            handler.post {
                result = runCatching(block)
                done.countDown()
            }
            check(done.await(10, TimeUnit.SECONDS)) { "the player's thread did not answer" }
            return result!!.getOrThrow()
        }

        /** Played from the start, and on long enough to be well under way. */
        fun start() {
            play()
            val began = SystemClock.elapsedRealtime()
            while (!on { player.isPlaying } && SystemClock.elapsedRealtime() - began < 10_000) Thread.sleep(50)
            Thread.sleep(1_200)
        }

        fun play() = on { player.play() }

        fun pause() = on { player.pause() }

        /** Sent to [targetMs] as RecordingPlayer sends it; the segments that
         *  were before it, for [after]. */
        fun seek(targetMs: Long): Int {
            val before = synchronized(lock) { segments.size }
            on { player.seekTo(OpusPreRoll.seekFrom(targetMs, decoderUp)) }
            return before
        }

        /** The left channel of the longest segment begun since [before]: what
         *  the sink was given after the seek's flush. */
        fun after(before: Int): DoubleArray? {
            val bytes = synchronized(lock) { segments.drop(before).maxByOrNull { it.size() }?.toByteArray() } ?: return null
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return DoubleArray(samples.remaining() / 2) { samples.get(it * 2).toDouble() }
        }

        override fun close() {
            runCatching { on { player.release() } }
            thread.quitSafely()
        }
    }
}
