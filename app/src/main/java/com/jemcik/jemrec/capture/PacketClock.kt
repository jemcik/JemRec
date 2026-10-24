package com.jemcik.jemrec.capture

/**
 * Where each packet of a daemon recording falls in time, in 20 ms slots from
 * the recording's start: what the cut to a call (CallTracking.segments) is
 * reckoned in.
 *
 * COUNTED, UNLESS THE CAPTURE WENT QUIET. The encoder makes a packet every
 * 20 ms while the capture runs, so a packet belongs one slot after the one
 * before it - exact, and unmoved by when anything reads it (OpusOggWriter).
 * Counting was all there was. But a capture can deliver nothing for a while,
 * and counting then puts everything after it too early. Over Wi-Fi Calling on
 * a Pixel 11 Pro XL (Android 17, issue #40) nothing came while an outgoing
 * call rang: 32 s of ringing, then 20.5 s of call, and the cut to the answer,
 * counted from the dialling, fell past the last packet - the call was deleted
 * as "no call audio". A call longer than its ringing would have lost its
 * first 32 s instead, without a word.
 *
 * So the daemon's own stamp on each packet is read too: the monotonic clock
 * as the packet came from the encoder (scrcpy's AudioRecordReader for the
 * first, AudioEncoder.fixTimestamp after). How far a stamp runs ahead of the
 * count is the capture's delay; when that lead jumps by more than [GAP_MS]
 * over the least seen so far, the capture lost what came between, and the
 * packet is placed by the jump - the delay itself left as the count had it,
 * or everything after a gap lands that much late, and the cut at the call's
 * end drops it (the Android 13 emulator: a 0.64 s loss came out 0.2 s too
 * long, 2026-10-24). Short of a jump the count stands - stamps jitter, the
 * count does not - and never goes back: a burst of what was buffered while
 * the daemon could not read comes stamped close together, behind the count.
 * The first packet is placed at its own stamp when it came late: measured on
 * the emulator (API 37, 2026-10-24), the daemon stopped for 1.4 s from its
 * first moment, the first packet came 50 ms after it was let go, the rest
 * one every 20 ms after that.
 *
 * The stamps are on the monotonic clock and the calls on the wall clock;
 * [offset] is the one minus the other, as CallTracker measured it during the
 * call (Tracked.clockOffset). Without it - a call tracked by an older version
 * - every packet is counted, as before.
 */
internal class PacketClock(private val start: Long, private val offset: Long?) {

    private var last = -1L

    /** The least lead - stamp less count, in slots - seen since the last
     *  gap: the capture's delay, which a gap is measured over. */
    private var baseline = 0L

    /** Where the first packet's stamp put it, in ms from the start: null
     *  without one. For the log. */
    var firstMs: Long? = null
        private set

    /** Gaps after the first packet, and the time they cover. For the log. */
    var gaps = 0
        private set
    var lostMs = 0L
        private set

    /** The slot of the next packet, which the daemon stamped [ptsUs]. */
    fun next(ptsUs: Long): Long {
        val counted = last + 1
        val stampedMs = stampedMs(ptsUs)
        val stamped = stampedMs?.div(CallTracking.PACKET_MS)
        val gap = GAP_MS / CallTracking.PACKET_MS
        val slot = when {
            stamped == null -> counted
            last < 0 -> {
                // The first: at its stamp if the capture started late - the
                // Pixel's ringing - else counted from the start, its lead the
                // delay the rest is measured against.
                firstMs = stampedMs
                val lead = stamped - counted
                if (lead > gap) {
                    baseline = 0
                    stamped
                } else {
                    baseline = lead
                    counted
                }
            }
            stamped - counted - baseline > gap -> {
                val jump = stamped - counted - baseline
                gaps++
                lostMs += jump * CallTracking.PACKET_MS
                counted + jump
            }
            else -> {
                baseline = minOf(baseline, stamped - counted)
                counted
            }
        }
        last = slot
        return slot
    }

    /** A stamp in ms from the start; null without the offset, and for one
     *  that cannot be right - before the start, or half a day after it. */
    private fun stampedMs(ptsUs: Long): Long? {
        val ms = ptsUs / 1000 + (offset ?: return null) - start
        return ms.takeIf { it in 0..MAX_MS }
    }

    companion object {
        /** Further past the count than any stamp jitters - the encoder's
         *  output thread waiting, a read held up - and well short of the
         *  ringing that went missing. */
        const val GAP_MS = 500L

        private const val MAX_MS = 12 * 60 * 60 * 1000L
    }
}
