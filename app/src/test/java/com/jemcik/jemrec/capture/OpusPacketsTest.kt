package com.jemcik.jemrec.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Opus frames joined six to a packet, for the waveform's decoding: framed
 *  as RFC 6716 says, and every frame's bytes kept as they were. */
class OpusPacketsTest {

    /** The recorder's packets: CELT, fullband, 20 ms (config 31), stereo,
     *  one frame (code 0). */
    private val toc = (31 shl 3) or (1 shl 2)

    private fun packet(size: Int, toc: Int = this.toc, fill: Int = 7) =
        ByteArray(size) { if (it == 0) toc.toByte() else (fill + it).toByte() }

    /** [packets] joined as the decoder joins them: read back to back into
     *  one array, then written by joinInto - into a buffer of the room it
     *  says it needs, so writing more fails the test. */
    private fun join(packets: List<ByteArray>): ByteArray {
        val all = ByteArray(packets.sumOf { it.size })
        var at = 0
        for (p in packets) {
            p.copyInto(all, at)
            at += p.size
        }
        val out = ByteBuffer.allocate(2 + packets.sumOf { it.size + 1 })
        OpusPackets.joinInto(all, packets.map { it.size }.toIntArray(), packets.size, out)
        return out.array().copyOf(out.position())
    }

    private fun fits(first: ByteArray, frames: Int, units: Int, packet: ByteArray) =
        OpusPackets.fits(first[0].toInt(), frames, units, packet[0].toInt(), packet.size)

    private fun joinable(packet: ByteArray) = OpusPackets.joinable(packet[0].toInt(), packet.size)

    /** A code 3 packet read back as RFC 6716, 3.2.5 lays it out: its frames,
     *  each with the TOC byte it would have alone. Written from the RFC, not
     *  from OpusPackets. */
    private fun frames(packet: ByteArray): List<ByteArray> {
        val tocByte = packet[0].toInt() and 0xff
        assertEquals("code 3", 3, tocByte and 3)
        val count = packet[1].toInt() and 0xff
        val vbr = count and 0x80 != 0
        assertFalse("no padding", count and 0x40 != 0)
        val m = count and 0x3f
        var at = 2
        val sizes = IntArray(m)
        if (vbr) {
            for (i in 0 until m - 1) {
                val first = packet[at++].toInt() and 0xff
                sizes[i] = if (first < 252) first else first + 4 * (packet[at++].toInt() and 0xff)
            }
            sizes[m - 1] = packet.size - at - sizes.take(m - 1).sum()
        } else {
            assertEquals("CBR frames share the rest evenly", 0, (packet.size - 2) % m)
            sizes.fill((packet.size - 2) / m)
        }
        return sizes.map { size ->
            ByteArray(size + 1).also {
                it[0] = (tocByte and 0xfc).toByte()
                packet.copyInto(it, 1, at, at + size)
                at += size
            }
        }
    }

    @Test fun eachConfigurationHasItsFrameLength() {
        // In 2.5 ms units: SILK 10/20/40/60, hybrid 10/20, CELT 2.5/5/10/20.
        assertEquals(listOf(4, 8, 16, 24), (0..3).map { OpusPackets.frameUnits(it shl 3) })
        assertEquals(listOf(4, 8), (12..13).map { OpusPackets.frameUnits(it shl 3) })
        assertEquals(listOf(1, 2, 4, 8), (28..31).map { OpusPackets.frameUnits(it shl 3) })
        assertEquals(8, OpusPackets.frameUnits(toc))
    }

    @Test fun sixFramesOfTheRecordersAreOnePacket() {
        val six = List(6) { packet(320, fill = it) }
        var units = 0
        six.forEachIndexed { i, p ->
            assertTrue("frame $i fits", fits(six[0], i, units, p))
            units += OpusPackets.frameUnits(p[0].toInt())
        }
        // A seventh would be past 120 ms.
        assertFalse(fits(six[0], 6, units, packet(320)))
        val joined = join(six)
        // All the same size: no sizes written, the frames shared evenly.
        assertEquals(2 + 6 * 319, joined.size)
        assertEquals(0, joined[1].toInt() and 0x80)
        frames(joined).forEachIndexed { i, frame -> assertArrayEquals("frame $i", six[i], frame) }
    }

    @Test fun framesOfDifferentSizesSayTheirSizes() {
        // One under 252, one of 252 itself and one over: the one- and
        // two-byte forms, and the last frame's size left to the rest.
        val three = listOf(packet(200), packet(253), packet(700), packet(90))
        val joined = join(three)
        assertEquals(0x80, joined[1].toInt() and 0x80)
        frames(joined).forEachIndexed { i, frame -> assertArrayEquals("frame $i", three[i], frame) }
    }

    @Test fun theLargestFrameStillFits() {
        val big = listOf(packet(1276), packet(1276), packet(10))
        frames(join(big)).forEachIndexed { i, frame -> assertArrayEquals(big[i], frame) }
    }

    @Test fun onlyFramesThatMayShareAPacketAreJoined() {
        val first = packet(320)
        // Another configuration (CELT 10 ms), or mono: not with these.
        assertFalse(fits(first, 1, 8, packet(320, toc = (30 shl 3) or (1 shl 2))))
        assertFalse(fits(first, 1, 8, packet(320, toc = 31 shl 3)))
        // A packet of two frames already (code 1), or an empty one - silence
        // the encoder left out: each goes as it came.
        assertFalse(joinable(packet(320, toc = toc or 1)))
        assertFalse(joinable(ByteArray(1) { toc.toByte() }))
        assertTrue(joinable(first))
    }

    @Test fun onePacketIsPassedOnAsItIs() {
        val one = packet(320)
        assertArrayEquals(one, join(listOf(one)))
    }

    // The edges PIT found no test standing on (2026-11-01).

    @Test fun hybridEndsAndCeltBeginsAtConfigSixteen() {
        // Hybrid 20 ms, then CELT 2.5 ms. (At config 12, SILK 10 ms and hybrid
        // 10 ms are both 4 units, so that edge cannot be told apart.)
        assertEquals(8, OpusPackets.frameUnits(15 shl 3))
        assertEquals(1, OpusPackets.frameUnits(16 shl 3))
    }

    @Test fun fortyEightFramesFillAPacket() {
        // CELT 2.5 ms frames, so the count runs out before the 120 ms do.
        val short = (16 shl 3) or (1 shl 2)
        val first = packet(40, toc = short)
        assertTrue(fits(first, 47, 47, packet(40, toc = short)))
        assertFalse(fits(first, 48, 10, packet(40, toc = short)))
    }

    @Test fun aTocAndOneByteOfSoundIsAFrameToJoin() {
        assertTrue(joinable(packet(2)))
    }

    @Test fun theJoinedTocKeepsTheFramesConfiguration() {
        // Not the recorder's TOC: its bits are all set where the mask keeps
        // them, which would hide a mask that kept nothing.
        val mono = 16 shl 3
        val joined = join(listOf(packet(40, toc = mono), packet(40, toc = mono)))
        assertEquals((mono and 0xfc) or 3, joined[0].toInt() and 0xff)
    }

    @Test fun nothingToJoinIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            OpusPackets.joinInto(ByteArray(0), IntArray(0), 0, ByteBuffer.allocate(8))
        }
    }
}
