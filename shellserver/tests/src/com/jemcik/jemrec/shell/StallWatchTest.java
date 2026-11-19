package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** A stall no read fails in, said as any stop (review round 10). */
public class StallWatchTest {

    private long now = 100_000L;
    private long last = -1;
    private boolean loopStopped;
    private boolean over;
    private final List<String> said = new ArrayList<>();

    private final StallWatch watch = new StallWatch(() -> last, () -> loopStopped, () -> over, () -> now,
            new StallWatch.Saying() {
                @Override
                public void stopped(long sinceMs) {
                    said.add("stopped since " + sinceMs);
                }

                @Override
                public void back() {
                    said.add("back");
                }

                @Override
                public void lastRead(long atMs) {
                    said.add("last read at " + atMs);
                }
            });

    @Test
    public void nothingDeliveredForTheStallIsAStopAndReadsFlowingAgainAreBack() {
        last = now;
        now += StallWatch.STALL_MS - 1;
        watch.look();
        assertEquals("not yet", List.of(), said);
        now += 1;
        watch.look();
        watch.look();
        long stalled = last;
        assertEquals("once", List.of("stopped since " + stalled), said);
        // Reads flow again: back only once they have held (review round 11).
        for (int i = 0; i < 5; i++) {
            now += 1_000;
            last = now;
            watch.look();
        }
        assertEquals("held", List.of("stopped since " + stalled), said);
        now += 1_000;
        last = now;
        watch.look();
        assertEquals(List.of("stopped since " + stalled, "back"), said);
    }

    /** A read now and then, blocking in between, is no back: it flipped
     *  stopped and back every few seconds (review round 11). */
    @Test
    public void aReadNowAndThenIsNoBack() {
        last = now;
        now += StallWatch.STALL_MS;
        watch.look();
        for (int i = 0; i < 10; i++) {
            last = now;
            for (int j = 0; j < 3; j++) {
                now += 1_000;
                watch.look();
            }
        }
        assertEquals(1, said.size());
    }

    /** Stopping with reads back since the stop was said, the hold not over:
     *  no loss - not a recording run to its end named _early. */
    @Test
    public void reachedTheStopReadingAgainIsBack() {
        last = now;
        now += StallWatch.STALL_MS;
        watch.look();
        last = now + 20;
        watch.closeOut();
        assertEquals("back", said.get(said.size() - 1));
        watch.closeOut();
        assertEquals("once", 2, said.size());
    }

    /** A read after the stop that stopped again is no way back at the end:
     *  the loss starts after it - one read 3 s after the stop, 50 s of
     *  nothing to the hang-up, was said back and the file went unmarked
     *  (review round 12). */
    @Test
    public void aReadThatStoppedAgainIsNoBackAtTheEnd() {
        last = now;
        now += StallWatch.STALL_MS;
        watch.look();
        now += 3_000;
        last = now;
        for (int i = 0; i < 50; i++) {
            now += 1_000;
            watch.look();
        }
        watch.closeOut();
        assertEquals(List.of("stopped since " + (last - 3_000 - StallWatch.STALL_MS), "last read at " + last), said);
    }

    /** Reads flowing at the stop, but the loop gone slow since: its stop
     *  stands, from the last read (review round 12). */
    @Test
    public void aLoopGoneSlowBeforeTheStopIsNoBack() {
        last = now;
        now += StallWatch.STALL_MS;
        watch.look();
        now += 1_000;
        last = now;
        loopStopped = true;
        watch.closeOut();
        assertEquals("last read at " + last, said.get(said.size() - 1));
    }

    /** Not before the first read: a call's audio may take its time. */
    @Test
    public void nothingBeforeTheFirstRead() {
        now += 10 * StallWatch.STALL_MS;
        watch.look();
        assertEquals(List.of(), said);
    }

    /** The loop's own stop is its to say, and its way back; nor any once
     *  the recording is ending - the encoder's end is told for good. */
    @Test
    public void notOverTheLoopsOwnStopNorOnceEnding() {
        last = now;
        now += StallWatch.STALL_MS;
        loopStopped = true;
        watch.look();
        assertEquals(List.of(), said);
        loopStopped = false;
        over = true;
        watch.look();
        assertEquals(List.of(), said);
    }

    /** Said, then the loop takes it over: no back of its own, and the
     *  reads between were no way back - the loss starts after the last
     *  (review round 13: the early end overstated by the time between). */
    @Test
    public void aStopTheLoopTakesOverIsNotSaidBackHere() {
        last = now;
        now += StallWatch.STALL_MS;
        watch.look();
        loopStopped = true;
        last = now + 1;
        watch.look();
        assertEquals(List.of("stopped since " + (now - StallWatch.STALL_MS), "last read at " + last), said);
        watch.look();
        assertEquals("once", 2, said.size());
    }
}
