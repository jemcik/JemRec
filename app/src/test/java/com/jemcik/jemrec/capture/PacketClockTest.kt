package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallTracking.Tracked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a daemon recording's packets fall in time, held against Telecom's
 * account of the call: the recording issue #40 lost, and the ones that must
 * come out as they always did.
 */
class PacketClockTest {

    /** The recording's start, on the wall clock. */
    private val t = 1_795_000_000_000L
    private val s = 1_000L

    /** Wall minus monotonic: the monotonic clock read 4241 s at [t]. */
    private val offset = t - 4_241 * s

    /** The daemon's stamp, in µs on the monotonic clock, for wall time [at]. */
    private fun stamp(at: Long) = (at - offset) * 1000

    /** [n] packets captured one every 20 ms from wall time [from], each
     *  stamped [late] ms after it was captured. */
    private fun captured(from: Long, n: Int, late: (Int) -> Long = { 30 }) =
        (0 until n).map { stamp(from + it * 20L + late(it)) }

    private fun slots(stamps: List<Long>, clock: PacketClock = PacketClock(t, offset)) = stamps.map(clock::next)

    @Test fun aCaptureThatRanThroughIsCountedAsBefore() {
        // From 120 ms after the start, stamps late by 10 to 250 ms.
        val clock = PacketClock(t, offset)
        val slots = slots(captured(t + 120, 3000) { 10 + (it * 37 % 241).toLong() }, clock)
        assertEquals((0L until 3000L).toList(), slots)
        assertEquals(0, clock.gaps)
        assertEquals(130L, clock.firstMs)
    }

    @Test fun nothingWhileItRangPutsTheCallWhereItWas() {
        // Issue #40's call: dialled, 32 s of ringing with nothing captured,
        // answered, 20.5 s of call, and the daemon's two seconds after it.
        val answered = t + 32 * s
        val call = Tracked(t - 400, false, "+100", listOf(answered), answered + 20_500, offset)
        val stamps = captured(answered + 50, 1_122)
        val part = CallTracking.segments(t, listOf(call)).single().second

        // Counted from the dialling, the cut to the answer fell past the
        // last packet: nothing kept, "no call audio".
        assertEquals(0, (0L until stamps.size).count { it in part })
        // Placed where it was captured, the call is all there: from 80 ms
        // after the answer - the capture's 50, the stamp's 30 - to its end.
        val clock = PacketClock(t, offset)
        assertEquals(1_021, slots(stamps, clock).count { it in part })
        assertEquals(32_080L, clock.firstMs)
        assertEquals(0, clock.gaps)
    }

    @Test fun aLongerCallLostItsStartWithoutAWord() {
        // The same ringing before a minute of call: counted, the first 32 s
        // of the call were cut away and the two seconds after it kept.
        val answered = t + 32 * s
        val call = Tracked(t - 400, false, "+100", listOf(answered), answered + 60 * s, offset)
        val stamps = captured(answered, 3_100)
        val part = CallTracking.segments(t, listOf(call)).single().second
        assertEquals(3_100 - 1_600, (0L until stamps.size).count { it in part })
        assertEquals(2_999, slots(stamps).count { it in part })
    }

    @Test fun aGapLaterStartsANewRunWhereItEnded() {
        // 100 packets, the daemon stopped for 2.5 s, then what the capture
        // had buffered - 8 packets, stamped together as it was let go - and
        // on, one every 20 ms (the emulator's shape).
        val before = captured(t + 40, 100)
        val burst = List(8) { stamp(t + 4_520) }
        val after = captured(t + 4_540, 200)
        val clock = PacketClock(t, offset)
        val slots = slots(before + burst + after, clock)
        assertEquals((0L until 100L).toList(), slots.take(100))
        // The burst placed by the jump over the capture's delay before it -
        // 30 ms, its stamps late by that, a slot and a half - and counted on
        // from there: stamps behind the count never move it back.
        assertEquals((223L until 223L + 208).toList(), slots.drop(100))
        assertEquals(1, clock.gaps)
        assertEquals(123 * 20L, clock.lostMs)
    }

    @Test fun jitterIsNotAGap() {
        // Now and then a read held up 400 ms - and no packet lost.
        val slots = slots(captured(t + 40, 1_000) { if (it % 97 == 0) 400 else 30 })
        assertEquals((0L until 1_000L).toList(), slots)
    }

    @Test fun stampsBehindTheCountNeverMoveItBack() {
        val slots = slots(List(200) { stamp(t + 100) })
        assertEquals((0L until 200L).toList(), slots)
    }

    @Test fun withoutTheOffsetEveryPacketIsCounted() {
        // A call tracked before the offset was kept.
        val clock = PacketClock(t, null)
        val slots = slots(captured(t + 32 * s, 100), clock)
        assertEquals((0L until 100L).toList(), slots)
        assertNull(clock.firstMs)
    }

    @Test fun aStampThatCannotBeRightIsCounted() {
        // Before the recording started, and half a day after it.
        val nonsense = listOf(0L, stamp(t - 5 * s), stamp(t + 13 * 60 * 60 * s))
        val clock = PacketClock(t, offset)
        assertEquals(listOf(0L, 1L, 2L), slots(nonsense, clock))
        assertNull(clock.firstMs)
    }

    @Test fun whatTheCaptureDidNotDeliverIsCountedPerPart() {
        // Answered at slot 100; the first packet came at 149 (an incoming
        // call's capture starting late), and 0.5 s went missing later.
        val part = RecordingSaver.Part("x.ogg", true, t, listOf(100L until 400L))
        assertEquals(49L + 25L, part.undeliveredSlots(first = 149, last = 399, written = 400 - 149 - 25))
        // A capture that ran through: nothing.
        assertEquals(0L, part.undeliveredSlots(first = 100, last = 399, written = 300))
        // Nothing written: nothing to say.
        assertEquals(0L, part.undeliveredSlots(first = -1, last = -1, written = 0))
        // Call waiting: the other call's stretch between this part's two is not missing.
        val waiting = RecordingSaver.Part("y.ogg", true, t, listOf(0L until 1400L, 2400L until 3400L))
        assertEquals(0L, waiting.undeliveredSlots(first = 0, last = 3399, written = 2400))
        // A whole recording, counted: never anything.
        val whole = RecordingSaver.Part.whole("z.ogg", false, t)
        assertEquals(0L, whole.undeliveredSlots(first = 0, last = 999, written = 1000))
    }

    @Test fun callWaitingWithoutAGapIsCutAsBefore() {
        // CallTrackingTest's call waiting, its packets placed by the clock.
        val start = t + 2 * s
        val first = Tracked(t, true, "+100", listOf(t + 2 * s, t + 50 * s), t + 70 * s, offset)
        val second = Tracked(t + 20 * s, true, "+100", listOf(t + 30 * s), t + 50 * s, offset)
        val segments = CallTracking.segments(start, listOf(first, second))
        val stamps = captured(start + 60, 3_500)
        val counted = (0L until stamps.size).map { slot -> segments.indexOfFirst { slot in it.second } }
        val placed = slots(stamps, PacketClock(start, offset)).map { slot -> segments.indexOfFirst { slot in it.second } }
        assertEquals(counted, placed)
    }
}
