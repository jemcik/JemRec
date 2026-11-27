package com.jemcik.jemrec.audio

import com.jemcik.jemrec.playback.BothEars
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.random.Random

/**
 * A codec's buffer is held for one copy only (CodecBuffers.kt): no packet is
 * read from the file while the decoder's input buffer is out, and what goes
 * into the decoder is, chunk for chunk, what 1.9 put there.
 */
class CodecBuffersTest {

    /** The recorder's packets: CELT, fullband, 20 ms (config 31), stereo,
     *  one frame (code 0). */
    private val toc = (31 shl 3) or (1 shl 2)

    /** What the Opus decoder's input buffers hold on the Android 12 AVD
     *  (max-input-size, 2026-11-25). */
    private val room = 5760

    private fun packet(size: Int, toc: Int = this.toc, seed: Int = size) =
        ByteArray(size) { if (it == 0) toc.toByte() else (seed * 31 + it).toByte() }

    /** [sizes] as packets 20 ms apart, the first at [fromUs]. */
    private fun stream(sizes: List<Int>, fromUs: Long = 0L, toc: (Int) -> Int = { this.toc }) =
        sizes.mapIndexed { i, size -> (fromUs + i * 20_000L) to packet(size, toc(i), seed = i) }

    /** The recorder's packets as a call gives them: 20 ms each, some
     *  quieter (smaller) than others. */
    private fun call(count: Int, random: Random = Random(7)) = stream(List(count) { 80 + random.nextInt(400) })

    /** Packets as MediaExtractor gives them. While [codec] holds one of its
     *  buffers, any of these calls - each may read the file - fails the
     *  test. */
    private class FakePackets(private val packets: List<Pair<Long, ByteArray>>, var codec: FakeCodec? = null) : Packets {
        private var at = 0
        var reads = 0
            private set

        private fun guard(what: String) {
            codec?.let { assertNull("$what while the codec's buffer ${it.open} was out", it.open) }
        }

        override val sampleTime get() = guard("sampleTime").let { if (at < packets.size) packets[at].first else -1L }
        override val sampleSize get() = guard("sampleSize").let { if (at < packets.size) packets[at].second.size.toLong() else -1L }

        override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
            guard("a packet read")
            if (at >= packets.size) return -1
            val packet = packets[at].second
            // As MediaExtractor: room is the buffer's whole capacity past
            // offset, and position and limit point at what was read.
            require(buffer.capacity() - offset >= packet.size) { "no room" }
            buffer.limit(buffer.capacity())
            buffer.position(offset)
            buffer.put(packet)
            buffer.limit(offset + packet.size)
            buffer.position(offset)
            reads++
            return packet.size
        }

        override fun advance(): Boolean {
            guard("advance")
            at++
            return at < packets.size
        }
    }

    /** What the codec was handed: a chunk's bytes and time, or the end. */
    private data class Queued(val bytes: List<Byte>, val atUs: Long, val end: Boolean)

    /** A codec whose input buffers are [capacity] bytes, [free] of them free
     *  at each round ([round]). */
    private class FakeCodec(private val capacity: Int) : CodecInput {
        var open: Int? = null
            private set
        val queued = mutableListOf<Queued>()
        private var free = 0
        private var next = 0
        private var current: ByteBuffer? = null

        fun round(free: Int) {
            this.free = free
        }

        override fun dequeue(): Int {
            assertNull("a buffer taken while ${open} was out", open)
            if (free == 0) return -1
            free--
            return next++
        }

        override fun capacity(index: Int) = capacity

        override fun buffer(index: Int): ByteBuffer {
            open = index
            return ByteBuffer.allocateDirect(capacity).also { current = it }
        }

        override fun queue(index: Int, size: Int, atUs: Long) {
            assertEquals(open, index)
            val bytes = ByteArray(size)
            current!!.duplicate().apply { clear() }.get(bytes)
            queued += Queued(bytes.toList(), atUs, false)
            open = null
        }

        override fun end(index: Int) {
            queued += Queued(emptyList(), 0, true)
            open = null
        }
    }

    /**
     * The decoder's input as 1.9 had it (PcmDecoder.kt at 8a57b2a), on the
     * same [Packets]: each packet read into the codec's own buffer, between
     * taking it and handing it back - the window the crashes were in. The
     * reference the new input must give the same chunks as.
     */
    private object Before {
        interface Input {
            fun next(buffer: ByteBuffer): Long
        }

        class Plain(private val extractor: Packets, private val untilUs: Long) : Input {
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

        class Joining(private val extractor: Packets, private val untilUs: Long) : Input {
            private val read = ByteBuffer.allocate(64 * 1024)
            private val sizes = IntArray(OpusPackets.MAX_FRAMES)
            private var held = 0
            private var heldAtUs = 0L

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

        /** 1.9's loop: every free buffer at once, each filled by [input]. */
        fun run(input: Input, codec: FakeCodec, rounds: Iterator<Int>) {
            var inputDone = false
            while (!inputDone) {
                codec.round(rounds.next())
                while (!inputDone) {
                    val idx = codec.dequeue()
                    if (idx < 0) break
                    val buffer = codec.buffer(idx)
                    val atUs = input.next(buffer)
                    if (atUs < 0) {
                        codec.end(idx)
                        inputDone = true
                    } else {
                        codec.queue(idx, buffer.position(), atUs)
                    }
                }
            }
        }
    }

    /** Free buffers round by round: none at times, a few, many. */
    private fun rounds(seed: Int = 3) = Random(seed).let { r -> generateSequence { listOf(0, 1, 1, 2, 3, 8)[r.nextInt(6)] }.iterator() }

    private fun before(packets: List<Pair<Long, ByteArray>>, join: Boolean, untilUs: Long = Long.MAX_VALUE, capacity: Int = room): List<Queued> {
        val codec = FakeCodec(capacity)
        val extractor = FakePackets(packets)
        Before.run(if (join) Before.Joining(extractor, untilUs) else Before.Plain(extractor, untilUs), codec, rounds())
        return codec.queued
    }

    /** The new input, with the guard on: a read while a buffer is out fails. */
    private fun after(
        packets: List<Pair<Long, ByteArray>>,
        join: Boolean,
        untilUs: Long = Long.MAX_VALUE,
        capacity: Int = room,
        seed: Int = 5,
    ): List<Queued> {
        val codec = FakeCodec(capacity)
        val extractor = FakePackets(packets, codec)
        val feeder = Feeder(if (join) Joining(extractor, untilUs) else Plain(extractor, untilUs), codec)
        val rounds = rounds(seed)
        while (!feeder.done) {
            codec.round(rounds.next())
            feeder.feed()
        }
        return codec.queued
    }

    private fun assertSame(packets: List<Pair<Long, ByteArray>>, join: Boolean, untilUs: Long = Long.MAX_VALUE) {
        val was = before(packets, join, untilUs)
        val now = after(packets, join, untilUs)
        assertEquals("chunks", was.size, now.size)
        for (i in was.indices) assertEquals("chunk $i", was[i], now[i])
        assertEquals("the end handed over once, last", 1, now.count { it.end })
        assertTrue(now.last().end)
    }

    @Test
    fun `the old input read packets while the codec's buffer was out`() {
        // The guard itself, proved on 1.9's code: this is the window.
        val codec = FakeCodec(room)
        val extractor = FakePackets(call(30), codec)
        assertThrows(AssertionError::class.java) { Before.run(Before.Joining(extractor, Long.MAX_VALUE), codec, rounds()) }
        val plainCodec = FakeCodec(room)
        val plain = FakePackets(call(30), plainCodec)
        assertThrows(AssertionError::class.java) { Before.run(Before.Plain(plain, Long.MAX_VALUE), plainCodec, rounds()) }
    }

    @Test
    fun `a call's packets are joined six to one, as before`() {
        val packets = call(600)
        assertSame(packets, join = true)
        // Six 20 ms frames to a chunk: 120 ms, the most one packet holds.
        val chunks = after(packets, join = true).filterNot { it.end }
        assertEquals(100, chunks.size)
        assertEquals((0 until 100).map { it * 120_000L }, chunks.map { it.atUs })
    }

    @Test
    fun `mixed sizes, carried over when the buffer is full, as before`() {
        val random = Random(11)
        // Up to 1,500 bytes: six of those are more than a buffer takes.
        val packets = stream(List(400) { 2 + random.nextInt(1500) })
        assertSame(packets, join = true)
        val chunks = after(packets, join = true).filterNot { it.end }
        assertTrue("some carried over", chunks.size > 400 / 6 + 1)
        assertTrue("none past the buffer", chunks.all { it.bytes.size <= room })
    }

    @Test
    fun `a packet carried over keeps its own time`() {
        // Five that fill the buffer, a sixth that does not fit.
        val packets = stream(listOf(1100, 1100, 1100, 1100, 1100, 900, 300))
        val chunks = after(packets, join = true).filterNot { it.end }
        assertEquals(listOf(0L, 100_000L), chunks.map { it.atUs })
        assertSame(packets, join = true)
    }

    @Test
    fun `packets that may not share one go as they came, as before`() {
        val random = Random(13)
        // A change of configuration every so often, empty and two-frame
        // packets among them.
        val packets = List(300) { i ->
            val size = listOf(1, 2, 3, 200, 300)[random.nextInt(5)]
            val code = if (i % 17 == 0) 1 else 0
            val config = if (i % 50 < 25) 31 else 30
            (i * 20_000L) to packet(size, (config shl 3) or (1 shl 2) or code, seed = i)
        }
        assertSame(packets, join = true)
    }

    @Test
    fun `untilUs is kept, as before`() {
        val packets = call(200)
        assertSame(packets, join = true, untilUs = 1_234_567L)
        assertSame(packets, join = false, untilUs = 1_234_567L)
        val chunks = after(packets, join = true, untilUs = 1_234_567L).filterNot { it.end }
        assertTrue(chunks.last().atUs < 1_234_567L)
    }

    @Test
    fun `a stretch from the middle starts where its packets do`() {
        assertSame(call(120).drop(37), join = true)
    }

    @Test
    fun `unjoined, a packet to a chunk, as before`() {
        val random = Random(17)
        val packets = stream(List(300) { 1 + random.nextInt(4000) })
        assertSame(packets, join = false)
        assertEquals(300, after(packets, join = false).count { !it.end })
    }

    @Test
    fun `nothing to read, only the end`() {
        assertEquals(listOf(Queued(emptyList(), 0, true)), after(emptyList(), join = true))
        assertEquals(listOf(Queued(emptyList(), 0, true)), after(emptyList(), join = false))
    }

    @Test
    fun `however the buffers come free, the same chunks`() {
        val packets = call(300)
        val once = after(packets, join = true, seed = 1)
        for (seed in 2..6) assertEquals(once, after(packets, join = true, seed = seed))
    }

    @Test
    fun `nothing is read while no buffer is free`() {
        val packets = call(12)
        val codec = FakeCodec(room)
        val extractor = FakePackets(packets, codec)
        val feeder = Feeder(Joining(extractor, Long.MAX_VALUE), codec)
        codec.round(0)
        assertFalse("nothing queued", feeder.feed())
        assertEquals("nothing read", 0, extractor.reads)
        codec.round(1)
        assertTrue(feeder.feed())
        // Six packets joined, and the seventh read to find it does not fit.
        assertEquals(7, extractor.reads)
        assertEquals(listOf(0L), codec.queued.map { it.atUs })
        codec.round(5)
        assertTrue(feeder.feed())
        assertEquals(listOf(0L, 120_000L), codec.queued.filterNot { it.end }.map { it.atUs })
        assertTrue(feeder.done)
        assertFalse("nothing after the end", feeder.feed())
    }

    @Test
    fun `packets are joined up to what each buffer holds`() {
        // Twice what a call's six take, half: 2 + 6 x 401 is 2,408.
        val packets = stream(List(60) { 400 })
        for (capacity in listOf(5760, 2408, 2407, 1300, 800)) {
            val codec = FakeCodec(capacity)
            val feeder = Feeder(Joining(FakePackets(packets, codec), Long.MAX_VALUE), codec)
            while (!feeder.done) {
                codec.round(4)
                feeder.feed()
            }
            val chunks = codec.queued.filterNot { it.end }
            assertTrue("$capacity: none past the buffer", chunks.all { it.bytes.size <= capacity })
            val expected = (capacity - 2) / 401
            assertEquals("$capacity: packets a chunk", maxOf(1, minOf(6, expected)), 60 / chunks.size)
        }
    }

    @Test
    fun `most limits a round, as the Transcoder pumps one`() {
        val packets = stream(List(5) { 100 })
        val codec = FakeCodec(room)
        val feeder = Feeder(Plain(FakePackets(packets, codec), Long.MAX_VALUE), codec)
        codec.round(8)
        assertTrue(feeder.feed(most = 1))
        assertEquals(1, codec.queued.size)
        assertTrue(feeder.feed(most = 1))
        assertEquals(2, codec.queued.size)
    }

    @Test
    fun `the output is copied whole, and stays when the buffer goes`() {
        val samples = ShortArray(1920) { (it * 37 - 20_000).toShort() }
        val codec = ByteBuffer.allocateDirect(8192).order(ByteOrder.nativeOrder())
        codec.position(64)
        codec.asShortBuffer().put(samples)
        val copy = PcmCopy()
        val pcm = copy.of(64, samples.size * 2) { codec }
        // The codec's memory taken back and written over.
        codec.clear()
        for (i in 0 until codec.capacity()) codec.put(i, 0x5a)
        assertEquals(0, pcm.position())
        assertEquals(samples.size, pcm.remaining())
        assertArrayEquals(samples, ShortArray(pcm.remaining()).also { pcm.get(it) })
    }

    @Test
    fun `the copy is reused, good until the next`() {
        val copy = PcmCopy()
        val big = ByteBuffer.allocateDirect(400).order(ByteOrder.nativeOrder())
        big.asShortBuffer().put(ShortArray(200) { 1 })
        val first = copy.of(0, 400) { big }
        val small = ByteBuffer.allocateDirect(10).order(ByteOrder.nativeOrder())
        small.asShortBuffer().put(ShortArray(5) { 2 })
        val second = copy.of(0, 10) { small }
        assertEquals(5, second.remaining())
        assertArrayEquals(ShortArray(5) { 2 }, ShortArray(5).also { second.get(it) })
        assertTrue("the same memory", first.array() === second.array())
    }

    @Test
    fun `room for the copy is made before the buffer is taken`() {
        val copy = PcmCopy()
        val codec = ByteBuffer.allocateDirect(8000).order(ByteOrder.nativeOrder())
        for (size in listOf(4000, 2000, 8000)) {
            // As the buffer is taken, the copy already has its room.
            copy.of(0, size) {
                assertTrue("$size bytes: room for ${size / 2} samples first", copy.room >= size / 2)
                codec
            }
        }
    }

    @Test
    fun `the encoder's pieces are as 1,9 split them`() {
        // 1.9's split, in bytes: whole frames of what the encoder's buffer
        // holds, or all of it where not one frame fits.
        fun was(remaining: Int, channels: Int, leftBytes: Int): Int {
            val frameBytes = channels * 2
            val whole = remaining / frameBytes * frameBytes
            return minOf(leftBytes, if (whole > 0) whole else remaining)
        }
        for (channels in 1..2) {
            for (remaining in channels * 2..9000 step 7) {
                for (left in listOf(1, 2, 3, 960, 1919, 1920, 3840, 4097)) {
                    assertEquals("$remaining bytes, $channels channels, $left samples left",
                        was(remaining, channels, left * 2), encoderPiece(remaining, channels, left) * 2)
                }
            }
        }
    }

    @Test
    fun `an encoder buffer under one frame`() {
        // Under one frame, 1.9 put the bytes that fitted; samples, a whole
        // one fits from 2 bytes. No encoder's buffers are that small.
        assertEquals(1, encoderPiece(2, 2, 100))
        assertEquals(1, encoderPiece(3, 2, 100))
        // One taking no sample would have the feed wait on it for ever.
        assertThrows(IllegalStateException::class.java) { encoderPiece(1, 2, 100) }
        assertThrows(IllegalStateException::class.java) { encoderPiece(0, 1, 100) }
    }

    @Test
    fun `mixed on the copy, then split on whole frames, as mixed piece by piece in the encoder`() {
        val random = Random(19)
        val pcm = ShortArray(1920 * 2) { (random.nextInt(65_536) - 32_768).toShort() }
        for (room in listOf(4096, 2048, 1000, 6)) {
            // 1.9: split on whole frames, each piece mixed in the encoder's
            // buffer.
            val was = ArrayList<Short>()
            var at = 0
            while (at < pcm.size) {
                val whole = room / 4 * 4 / 2
                val n = minOf(pcm.size - at, whole)
                val encoder = ByteBuffer.allocateDirect(room).order(ByteOrder.nativeOrder())
                encoder.asShortBuffer().put(pcm, at, n)
                BothEars.mix(encoder.asShortBuffer().apply { limit(n) }, 2)
                val piece = ShortArray(n).also { encoder.asShortBuffer().get(it) }
                was.addAll(piece.toList())
                at += n
            }
            // Now: mixed once, on our copy.
            val now = pcm.copyOf()
            BothEars.mix(ShortBuffer.wrap(now), 2)
            assertEquals("encoder buffers of $room bytes", was, now.toList())
        }
    }
}
