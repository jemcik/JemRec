package com.jemcik.jemrec.capture

/**
 * Opus packets of one frame each, joined into packets of several (RFC 6716,
 * 3.2.5: a "code 3" packet) - up to 120 ms of sound, the most one packet may
 * hold.
 *
 * WHY. Android's decoder runs in a process of its own, and every packet
 * handed to it is a trip there and back. A 20 ms packet at a time, a
 * 58-second call took 1.0 s to decode on the Honor; six to a packet, 0.33 s
 * (2026-10-23) - the same sound, so two thirds of the time had been the
 * trips. The frames themselves are not touched: their bytes are copied,
 * framed differently, and decode to the same samples (measured: the same
 * CRC, the decoder only trimming the start by 312 samples less).
 *
 * Only frames that may share a packet are joined: one frame each, the same
 * configuration and channels (the TOC byte, its last two bits apart), and not
 * empty. Anything else is passed on as it came.
 */
internal object OpusPackets {

    /** The most a packet may hold, in 2.5 ms units: 120 ms. */
    private const val MAX_UNITS = 48

    /** The most frames one packet may hold. */
    private const val MAX_FRAMES = 48

    /** How long each frame of a packet with this TOC byte lasts, in 2.5 ms
     *  units: SILK 10, 20, 40, 60 ms; hybrid 10, 20; CELT 2.5, 5, 10, 20. */
    fun frameUnits(toc: Int): Int {
        val config = (toc and 0xff) shr 3
        return when {
            config < 12 -> intArrayOf(4, 8, 16, 24)[config and 3]
            config < 16 -> intArrayOf(4, 8)[config and 1]
            else -> intArrayOf(1, 2, 4, 8)[config and 3]
        }
    }

    /** Whether [packet] is one frame, with sound in it: one that can be joined. */
    fun joinable(packet: ByteArray): Boolean = packet.size >= 2 && packet[0].toInt() and 3 == 0

    /** Whether [packet] may go into a packet that already holds [frames] of
     *  [first]'s kind, [units] long. */
    fun fits(first: ByteArray, frames: Int, units: Int, packet: ByteArray): Boolean =
        joinable(packet) &&
            (packet[0].toInt() and 0xfc) == (first[0].toInt() and 0xfc) &&
            frames < MAX_FRAMES &&
            units + frameUnits(packet[0].toInt()) <= MAX_UNITS

    /** At most how many bytes [frames] joined take: what an input buffer
     *  must have room for. */
    fun joinedSize(frames: List<ByteArray>): Int = 2 + frames.sumOf { it.size - 1 + 2 }

    /**
     * [frames] - single-frame packets that [fits] said could share one - as
     * one packet: the TOC byte with code 3, the frame count (with the
     * variable-size flag when their sizes differ), each frame's size but the
     * last's (one byte under 252, two from there), then the frames. One
     * packet is returned as it is.
     */
    fun join(frames: List<ByteArray>): ByteArray {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0]
        val sizes = frames.map { it.size - 1 }
        val sameSize = sizes.all { it == sizes[0] }
        val out = java.io.ByteArrayOutputStream(joinedSize(frames))
        out.write((frames[0][0].toInt() and 0xfc) or 3)
        out.write((if (sameSize) 0 else 0x80) or frames.size)
        if (!sameSize) {
            for (size in sizes.dropLast(1)) {
                if (size < 252) {
                    out.write(size)
                } else {
                    val first = 252 + (size and 3)
                    out.write(first)
                    out.write((size - first) / 4)
                }
            }
        }
        for (frame in frames) out.write(frame, 1, frame.size - 1)
        return out.toByteArray()
    }
}
