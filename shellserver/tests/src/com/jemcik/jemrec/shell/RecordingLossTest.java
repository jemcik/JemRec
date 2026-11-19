package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Where what a recording misses begins (RecordingLoss): a stall
 *  it never came back from marks it as an encoder's early end does - the
 *  encoder goes on through the slow restarts, and such a file went unmarked,
 *  messenger and tracking-off calls never told (review, 2026-11-18). */
public class RecordingLossTest {

    @Test
    public void anEarlyEndAndAStallTheEarlierCounts() {
        RecordingLoss loss = new RecordingLoss();
        loss.stalled(7_000L);
        loss.endedEarly(9_000L);
        assertEquals(7_000L, loss.since());
        RecordingLoss other = new RecordingLoss();
        other.endedEarly(4_000L);
        other.stalled(6_000L);
        assertEquals(4_000L, other.since());
    }

    /** Reads delivered while stalled - held for BACK, a capture that reads
     *  and dies again - move the loss's start on: what they brought is not
     *  missed (review round 10). */
    @Test
    public void aReadDeliveredWhileStalledMovesTheLossOn() {
        RecordingLoss loss = new RecordingLoss();
        loss.delivered(2_000L);
        assertEquals("not stalled: nothing moved", -1, loss.since());
        loss.stalled(1_000L);
        loss.delivered(3_000L);
        assertEquals(3_000L, loss.since());
        loss.delivered(2_500L);
        assertEquals("never back", 3_000L, loss.since());
    }

    @Test
    public void nothingMissedIsMinusOne() {
        assertEquals(-1, new RecordingLoss().since());
    }

    @Test
    public void aStallNeverBackIsWhereItBegan() {
        RecordingLoss ending = new RecordingLoss();
        ending.stalled(5_000L);
        ending.stalled(9_000L);
        assertEquals("the first of a stall", 5_000L, ending.since());
    }

    @Test
    public void aStallThatCameBackIsNothing() {
        RecordingLoss ending = new RecordingLoss();
        ending.stalled(5_000L);
        ending.back();
        assertEquals(-1, ending.since());
        ending.stalled(12_000L);
        assertEquals(12_000L, ending.since());
    }
}
