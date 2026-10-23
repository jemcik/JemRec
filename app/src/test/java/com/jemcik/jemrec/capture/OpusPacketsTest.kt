package com.jemcik.jemrec.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opus frames joined six to a packet, for the waveform's decoding: framed
 *  as RFC 6716 says, and every frame's bytes kept as they were. */
class OpusPacketsTest {

    /** The recorder's packets: CELT, fullband, 20 ms (config 31), stereo,
     *  one frame (code 0). */
    private val toc = (31 shl 3) or (1 shl 2)

    private fun packet(size: Int, toc: Int = this.toc, fill: Int = 7) =
        ByteArray(size) { if (it == 0) toc.toByte() else (fill + it).toByte() }

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
            assertTrue("frame $i fits", OpusPackets.fits(six[0], i, units, p))
            units += OpusPackets.frameUnits(p[0].toInt())
        }
        // A seventh would be past 120 ms.
        assertFalse(OpusPackets.fits(six[0], 6, units, packet(320)))
        val joined = OpusPackets.join(six)
        // All the same size: no sizes written, the frames shared evenly.
        assertEquals(2 + 6 * 319, joined.size)
        assertEquals(0, joined[1].toInt() and 0x80)
        frames(joined).forEachIndexed { i, frame -> assertArrayEquals("frame $i", six[i], frame) }
    }

    @Test fun framesOfDifferentSizesSayTheirSizes() {
        // One under 252, one of 252 itself and one over: the one- and
        // two-byte forms, and the last frame's size left to the rest.
        val three = listOf(packet(200), packet(253), packet(700), packet(90))
        val joined = OpusPackets.join(three)
        assertEquals(0x80, joined[1].toInt() and 0x80)
        assertTrue(joined.size <= OpusPackets.joinedSize(three))
        frames(joined).forEachIndexed { i, frame -> assertArrayEquals("frame $i", three[i], frame) }
    }

    @Test fun theLargestFrameStillFits() {
        val big = listOf(packet(1276), packet(1276), packet(10))
        frames(OpusPackets.join(big)).forEachIndexed { i, frame -> assertArrayEquals(big[i], frame) }
    }

    @Test fun onlyFramesThatMayShareAPacketAreJoined() {
        val first = packet(320)
        // Another configuration (CELT 10 ms), or mono: not with these.
        assertFalse(OpusPackets.fits(first, 1, 8, packet(320, toc = (30 shl 3) or (1 shl 2))))
        assertFalse(OpusPackets.fits(first, 1, 8, packet(320, toc = 31 shl 3)))
        // A packet of two frames already (code 1), or an empty one - silence
        // the encoder left out: each goes as it came.
        assertFalse(OpusPackets.joinable(packet(320, toc = toc or 1)))
        assertFalse(OpusPackets.joinable(ByteArray(1) { toc.toByte() }))
        assertTrue(OpusPackets.joinable(first))
    }

    @Test fun onePacketIsPassedOnAsItIs() {
        val one = packet(320)
        assertSame(one, OpusPackets.join(listOf(one)))
    }
}
