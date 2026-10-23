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
 * framed differently, and decode to the same samples - measured on the
 * Honor, CRC for CRC: a messenger call's whole file once its first 312
 * samples a channel are left out (2026-10-22), and two phone calls' middle
 * stretches (2026-10-23). Those 312 are the encoder's delay at the start,
 * 6.5 ms, which the decoder trims from a single frame and keeps from a
 * joined one; over a whole phone call they moved the waveform's levels
 * where speech starts, never by a bar - each is 16 ms or more.
 *
 * Only frames that may share a packet are joined: one frame each, the same
 * configuration and channels (the TOC byte, its last two bits apart), and not
 * empty. Anything else is passed on as it came.
 */
internal object OpusPackets {

    /** The most a packet may hold, in 2.5 ms units: 120 ms. */
    private const val MAX_UNITS = 48

    /** The most frames one packet may hold. */
    const val MAX_FRAMES = 48

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

    /** Whether a packet [size] bytes long, with this [toc] byte, is one
     *  frame with sound in it: one that can be joined. */
    fun joinable(toc: Int, size: Int): Boolean = size >= 2 && toc and 3 == 0

    /** Whether a packet [size] bytes long, with this [toc] byte, may go into
     *  one that already holds [frames] of [firstToc]'s kind, [units] long. */
    fun fits(firstToc: Int, frames: Int, units: Int, toc: Int, size: Int): Boolean =
        joinable(toc, size) &&
            (toc and 0xfc) == (firstToc and 0xfc) &&
            frames < MAX_FRAMES &&
            units + frameUnits(toc) <= MAX_UNITS

    /**
     * The first [count] of [packets] - single-frame packets back to back,
     * [sizes] long, that [fits] said could share one - written into [buffer]
     * as one packet: the TOC byte with code 3, the frame count (with the
     * variable-size flag when their sizes differ), each frame's size but the
     * last's (one byte under 252, two from there), then the frames. One
     * packet is written as it is. At most 2 bytes, and a byte more than its
     * size for each packet, are written: what the buffer must have room for.
     *
     * From the packets as they were read, back to back in one array: joined
     * from a list of arrays, a half-hour call's decode made ninety thousand
     * of them - and, before that, a list, a stream and two copies a packet.
     */
    fun joinInto(packets: ByteArray, sizes: IntArray, count: Int, buffer: java.nio.ByteBuffer) {
        require(count > 0)
        if (count == 1) {
            buffer.put(packets, 0, sizes[0])
            return
        }
        var sameSize = true
        for (i in 1 until count) if (sizes[i] != sizes[0]) sameSize = false
        buffer.put(((packets[0].toInt() and 0xfc) or 3).toByte())
        buffer.put(((if (sameSize) 0 else 0x80) or count).toByte())
        if (!sameSize) {
            for (i in 0 until count - 1) {
                val size = sizes[i] - 1
                if (size < 252) {
                    buffer.put(size.toByte())
                } else {
                    val first = 252 + (size and 3)
                    buffer.put(first.toByte())
                    buffer.put(((size - first) / 4).toByte())
                }
            }
        }
        // Each frame without its own TOC byte.
        var at = 0
        for (i in 0 until count) {
            buffer.put(packets, at + 1, sizes[i] - 1)
            at += sizes[i]
        }
    }
}
