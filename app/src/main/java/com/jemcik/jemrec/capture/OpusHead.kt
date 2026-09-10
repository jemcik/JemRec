package com.jemcik.jemrec.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The OpusHead the daemon sends as its config packet, parsed.
 *
 *   4f 70 75 73 48 65 61 64  "OpusHead"
 *   01                       version
 *   02                       channel count
 *   38 01                    pre-skip, little endian (312)
 *   80 bb 00 00              input sample rate (48000)
 *   00 00                    output gain
 *   00                       channel mapping family
 *
 * Nineteen bytes, and every field the container needs is in them. Parsed here
 * rather than inside the Ogg writer so the parsing can be checked without a
 * MediaMuxer, which only exists on a device.
 */
internal class OpusHead private constructor(
    val channels: Int,
    /** In 48 kHz samples, whatever the input rate: that is how Opus defines it. */
    val preSkip: Int,
    val inputRate: Int,
) {
    /**
     * Codec delay in nanoseconds, which is what MediaMuxer wants as csd-1.
     * Derived from the header's own pre-skip: hardcoding 6.5 ms happens to be
     * right for a pre-skip of 312 and silently wrong for anything else, and
     * the failure is a file that plays with the wrong offset rather than one
     * that refuses to open.
     */
    val codecDelayNs: Long
        get() = preSkip.toLong() * 1_000_000_000L / OPUS_RATE

    companion object {
        const val MIN_SIZE = 19
        private const val OPUS_RATE = 48_000

        fun parse(bytes: ByteArray): OpusHead {
            require(bytes.size >= MIN_SIZE && String(bytes, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                "not an OpusHead: ${bytes.size} bytes"
            }
            val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return OpusHead(
                channels = head.get(9).toInt() and 0xff,
                preSkip = head.getShort(10).toInt() and 0xffff,
                inputRate = head.getInt(12),
            )
        }
    }
}
