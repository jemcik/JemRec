package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The nineteen bytes the daemon sends first, and what the container is told from them. */
class OpusHeadTest {

    private val documented = byteArrayOf(
        0x4f, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, // "OpusHead"
        0x01, // version
        0x02, // channels
        0x38, 0x01, // pre-skip 312, little endian
        0x80.toByte(), 0xbb.toByte(), 0x00, 0x00, // 48000
        0x00, 0x00, // output gain
        0x00, // mapping family
    )

    @Test fun theDocumentedHeaderParsesToTheDocumentedValues() {
        val head = OpusHead.parse(documented)
        assertEquals(2, head.channels)
        assertEquals(312, head.preSkip)
        assertEquals(48_000, head.inputRate)
        // 312 samples at 48 kHz = 6.5 ms, the value that used to be hardcoded.
        assertEquals(6_500_000L, head.codecDelayNs)
    }

    @Test fun theDelayFollowsThePreSkipRatherThanAConstant() {
        val other = documented.copyOf().also { it[10] = 0x40; it[11] = 0x01 } // 320
        assertEquals(320, OpusHead.parse(other).preSkip)
        assertEquals(320L * 1_000_000_000L / 48_000, OpusHead.parse(other).codecDelayNs)
    }

    @Test fun notAHeaderIsRefusedNotGuessed() {
        assertThrows(IllegalArgumentException::class.java) { OpusHead.parse(documented.copyOf(18)) }
        val wrongMagic = documented.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { OpusHead.parse(wrongMagic) }
    }
}
