package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.Test;

/** The restart loop's decisions (RestartLoop), over captures that fail as
 *  a dead one does: their reads give an error, and every read after it. */
public class RestartLoopTest {

    /** A capture: what its reads give, in turn - then its last, for good. */
    static final class Capture {
        final String name;
        final Deque<Integer> reads = new ArrayDeque<>();
        int last;
        boolean startFails;
        int reads_made;
        int stops;

        Capture(String name, int... reads) {
            this.name = name;
            for (int r : reads) {
                this.reads.add(r);
            }
            this.last = reads.length == 0 ? 0 : reads[reads.length - 1];
        }

        int read() {
            reads_made++;
            Integer r = reads.poll();
            return r != null ? r : last;
        }
    }

    static final class Fakes implements RestartLoop.Captures<Capture> {
        final Deque<Capture> toMake = new ArrayDeque<>();
        final List<Long> slept = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<Capture> madeFrom = new ArrayList<>();
        /** What happened, in order: "stop <name>", "make", "start <name>". */
        final List<String> order = new ArrayList<>();
        Runnable onMake = () -> { };
        boolean interruptSleep;
        long now;

        @Override
        public @Nullable Capture make(Capture failed) {
            order.add("make");
            madeFrom.add(failed);
            onMake.run();
            return toMake.poll();
        }

        @Override
        public void start(Capture capture) throws Exception {
            order.add("start " + capture.name);
            if (capture.startFails) {
                throw new IllegalStateException("audio server not back");
            }
        }

        @Override
        public void stop(Capture capture) {
            order.add("stop " + capture.name);
            capture.stops++;
        }

        @Override
        public void sleep(long ms) throws InterruptedException {
            slept.add(ms);
            now += ms;
            if (interruptSleep) {
                throw new InterruptedException();
            }
            if (stoppeds > 0) {
                onSlowSleep.run();
            }
        }

        @Override
        public void log(String line, boolean error) {
            if (error) {
                errors.add(line);
            }
        }

        @Override
        public void note(String what) {
            notes.add(what);
        }

        /** Each look at the clock a read's worth on: the policy looks at
         *  each read delivered. */
        @Override
        public long nowMs() {
            now += 20;
            return now;
        }

        int restartsOver;

        @Override
        public void restartOver() {
            restartsOver++;
            order.add("over");
        }

        int stoppeds;
        int backs;
        Runnable onSlowSleep = () -> { };

        long stoppedSince = -2;
        final List<Long> deliveredWhileStopped = new ArrayList<>();

        @Override
        public void delivered(long atMs) {
            deliveredWhileStopped.add(atMs);
        }

        @Override
        public void stopped(long sinceMs) {
            stoppeds++;
            stoppedSince = sinceMs;
            order.add("stopped");
        }

        @Override
        public void back() {
            backs++;
            order.add("back");
        }
    }

    /** Reads on until the loop says the recording is back: reads held
     *  BACK_HOLD_MS, each a read's worth on the fake clock. */
    private static void readUntilBack(RestartLoop<Capture> loop, Fakes fakes) {
        for (int i = 0; i < 1_000 && fakes.backs == 0; i++) {
            assertEquals(960, loop.read(Capture::read));
        }
    }

    /** The first capture would not start: made anew, not the end of the
     *  recording - it is not read, and the new one is made from it. */
    @Test
    public void aFirstCaptureThatWouldNotStartIsMadeAnew() {
        Capture first = new Capture("first", 960);
        Capture fresh = new Capture("fresh", 960);
        Fakes fakes = new Fakes();
        fakes.toMake.add(fresh);
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        loop.firstFailed();
        assertEquals(960, loop.read(Capture::read));
        assertEquals("never read", 0, first.reads_made);
        assertEquals(List.of(first), fakes.madeFrom);
        assertEquals(1, fresh.reads_made);
    }

    /** Stopped, the time nothing has reached the recording since is the
     *  last read delivered: what the file is said to miss. */
    @Test
    public void aStopSaysSinceTheLastReadDelivered() {
        Capture first = new Capture("first", 960, DEAD);
        Fakes fakes = new Fakes();
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            fakes.toMake.add(new Capture("dead too " + i, DEAD));
        }
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        long delivered = fakes.now;
        fakes.onSlowSleep = loop::stop;
        loop.read(Capture::read);
        assertEquals(delivered, fakes.stoppedSince);
    }

    /** Nothing ever delivered - a first capture that dies at once: -1, and
     *  the recording's own start is where its loss began, not the stop's
     *  moment (review round 10). */
    @Test
    public void aStopWithNothingEverDeliveredSaysMinusOne() {
        Capture first = new Capture("first", DEAD);
        Fakes fakes = new Fakes();
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            fakes.toMake.add(new Capture("dead too " + i, DEAD));
        }
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        fakes.onSlowSleep = loop::stop;
        loop.read(Capture::read);
        assertEquals(-1, fakes.stoppedSince);
    }

    /** Stopped, the reads that come before it is back are told: a call
     *  ending inside the hold misses only what came after them (review
     *  round 10). */
    @Test
    public void readsDeliveredWhileStoppedAreTold() {
        Capture first = new Capture("first", 960, DEAD);
        Fakes fakes = new Fakes();
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            fakes.toMake.add(new Capture("dead too " + i, DEAD));
        }
        fakes.toMake.add(new Capture("reads", 960, 960, 960));
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        assertEquals(960, loop.read(Capture::read));
        assertTrue(fakes.stoppeds >= 1);
        assertEquals(960, loop.read(Capture::read));
        assertFalse(fakes.deliveredWhileStopped.isEmpty());
        assertEquals(0, fakes.backs);
    }

    /** The call's end interrupts the slow wait, as the encoder interrupts
     *  its input thread: the read ends there and nothing more is made. */
    @Test
    public void anInterruptDuringTheSlowWaitEndsTheRead() {
        Capture first = new Capture("first", DEAD);
        Fakes fakes = new Fakes();
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            fakes.toMake.add(new Capture("dead too " + i, DEAD));
        }
        fakes.toMake.add(new Capture("never made", 960));
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        fakes.onSlowSleep = () -> fakes.interruptSleep = true;
        try {
            assertEquals(DEAD, loop.read(Capture::read));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("nothing made after it", 1, fakes.toMake.size());
        } finally {
            Thread.interrupted();
        }
    }

    /** The waits the loop slept, joined back into the pauses it meant: its
     *  sleeps come in slices of at most WAIT_SLICE_MS. */
    private static long waited(Fakes fakes) {
        return fakes.slept.stream().mapToLong(Long::longValue).sum();
    }

    private static final int DEAD = -6; // AudioRecord.ERROR_DEAD_OBJECT

    @Test
    public void aFailedReadMakesTheCaptureAnewAndReadsFromThat() {
        Capture first = new Capture("first", 960, DEAD);
        Capture fresh = new Capture("fresh", 960);
        Fakes fakes = new Fakes();
        fakes.toMake.add(fresh);
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        assertEquals(960, loop.read(Capture::read));
        assertEquals(1, first.stops);
        assertEquals(0, fresh.stops);
        assertEquals(1, fakes.notes.size());
        assertEquals(List.of(RestartPolicy.RESTART_PAUSE_MS), fakes.slept);
        loop.stop();
        assertEquals("stopped once", 1, first.stops);
        assertEquals(1, fresh.stops);
    }

    /** The failed one is stopped before the new one is made, and the new
     *  one is made from it: run on through the wait, a phone call's capture
     *  swapped under the one being made, and what the new one took up was
     *  read from what was still running (review, 2026-11-18). */
    @Test
    public void theFailedOneIsStoppedFirstAndTheNewOneMadeFromIt() {
        Capture first = new Capture("first", DEAD);
        Capture wontStart = new Capture("won't start", 960);
        wontStart.startFails = true;
        Capture fresh = new Capture("fresh", 960);
        Fakes fakes = new Fakes();
        fakes.toMake.add(wontStart);
        fakes.toMake.add(fresh);
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        assertEquals(List.of("stop first", "make", "start won't start", "stop won't start", "make", "start fresh",
                "over"), fakes.order);
        assertEquals("each from the one read last, not one that never started", List.of(first, first),
                fakes.madeFrom);
        loop.stop();
        assertEquals(1, first.stops);
        assertEquals(1, fresh.stops);
    }

    /** No new capture to be made - a messenger's copy gone: the recording
     *  ends, said, the failed one stopped once. */
    @Test
    public void noCaptureToBeMadeEndsTheRecordingSaid() {
        Capture first = new Capture("first", DEAD);
        Fakes fakes = new Fakes();
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(DEAD, loop.read(Capture::read));
        assertEquals(1, fakes.errors.size());
        assertTrue(fakes.errors.get(0).startsWith("no new capture could be made"));
        loop.stop();
        assertEquals(1, first.stops);
    }

    /** One that would not start is not read - a dead capture can only fail
     *  again - and another is made while the policy has one. */
    @Test
    public void aNewCaptureThatWouldNotStartIsNotReadAndAnotherIsMade() {
        Capture first = new Capture("first", DEAD);
        Capture wontStart = new Capture("won't start", 960);
        wontStart.startFails = true;
        Capture fresh = new Capture("fresh", 960);
        Fakes fakes = new Fakes();
        fakes.toMake.add(wontStart);
        fakes.toMake.add(fresh);
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        assertEquals("the first read once", 1, first.reads_made);
        assertEquals("never read", 0, wontStart.reads_made);
        assertEquals(1, wontStart.stops);
        assertEquals(1, first.stops);
        assertTrue(fakes.notes.stream().anyMatch(n -> n.startsWith("the new capture would not start")));
        // Waited longer before the second than the first.
        assertEquals(List.of(RestartPolicy.RESTART_PAUSE_MS, 2 * RestartPolicy.RESTART_PAUSE_MS), fakes.slept);
    }

    /** No new capture that will start, past the policy's few: the
     *  recording said to have stopped, once, and captures made every
     *  SLOW_RETRY_MS - until one starts and reads, said too; until 1.2's
     *  reviews it ended there, the rest of the call unrecorded. */
    @Test
    public void noNewCaptureThatWillStartStopsTheRecordingAndTriesSlowlyUntilOneReads() {
        Capture first = new Capture("first", DEAD);
        Fakes fakes = new Fakes();
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS + 3; i++) {
            Capture c = new Capture("won't start " + i, 960);
            c.startFails = true;
            fakes.toMake.add(c);
        }
        Capture fresh = new Capture("fresh", 960);
        fakes.toMake.add(fresh);
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        assertEquals("the first read once, never again", 1, first.reads_made);
        assertEquals(1, fakes.stoppeds);
        assertEquals("not back at its first read", 0, fakes.backs);
        readUntilBack(loop, fakes);
        assertEquals(1, fakes.backs);
        assertEquals(1, fakes.errors.size());
        assertTrue(fakes.errors.get(0).endsWith("the recording has stopped - trying again every 10 s"));
        // 250 + 500 + 750 ms, then 10 s before each of the four slow ones.
        assertEquals(1_500L + 4 * RestartPolicy.SLOW_RETRY_MS, waited(fakes));
        assertTrue("no sleep longer than a slice", fakes.slept.stream().allMatch(s -> s <= RestartLoop.WAIT_SLICE_MS));
        assertEquals("the stop and the way back noted, not each slow try", 2,
                fakes.notes.stream().filter(n -> n.contains("stopped there") || n.contains("read again")).count());
        assertTrue(fakes.notes.stream().noneMatch(n -> n.contains("in 10 s")));
        // The fast tries' would-not-start noted, the slow ones' not: each
        // slow try noted grew the report without bound (review round 10).
        assertEquals(5, fakes.notes.stream().filter(n -> n.startsWith("the new capture would not start")).count());
        loop.stop();
        assertEquals(1, fresh.stops);
    }

    @Test
    public void readsThatKeepFailingStopTheRecordingAndGoOnOnceOneReads() {
        Capture first = new Capture("first", DEAD);
        Fakes fakes = new Fakes();
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS + 2; i++) {
            fakes.toMake.add(new Capture("dead too " + i, DEAD));
        }
        fakes.toMake.add(new Capture("fresh", 960, 960));
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        readUntilBack(loop, fakes);
        assertEquals(RestartPolicy.MOST_RESTARTS, fakes.notes.stream().filter(n -> n.contains("making it anew")).count());
        assertEquals(List.of("stopped", "back"),
                fakes.order.stream().filter(o -> o.equals("stopped") || o.equals("back")).toList());
        // Then as before: read on, nothing said.
        assertEquals(960, loop.read(Capture::read));
        assertEquals(1, fakes.backs);
    }

    /** The call ends during the slow wait: the read ends within a slice, and
     *  nothing more is made - the encoder's end, and the file's, wait on it. */
    @Test
    public void aStopDuringTheSlowWaitEndsTheReadWithinASlice() {
        Capture first = new Capture("first", DEAD);
        Fakes fakes = new Fakes();
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            fakes.toMake.add(new Capture("dead too " + i, DEAD));
        }
        fakes.toMake.add(new Capture("never made", 960));
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        fakes.onSlowSleep = loop::stop;
        assertEquals(DEAD, loop.read(Capture::read));
        assertEquals("one slice of the slow wait, then nothing", 1_500L + RestartLoop.WAIT_SLICE_MS, waited(fakes));
        assertEquals("nothing made after the stop", 1, fakes.toMake.size());
        assertEquals(-1, loop.read(Capture::read));
    }

    /** Stopped while a new one is being made: the new one is let go, never
     *  read, and the loop ends. */
    @Test
    public void aCaptureMadeAfterTheStopIsLetGo() {
        Capture first = new Capture("first", DEAD);
        Capture fresh = new Capture("fresh", 960);
        Fakes fakes = new Fakes();
        fakes.toMake.add(fresh);
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        fakes.onMake = loop::stop;
        assertEquals(DEAD, loop.read(Capture::read));
        assertEquals(1, fresh.stops);
        assertEquals(0, fresh.reads_made);
        assertEquals("the failed one stopped once", 1, first.stops);
        assertEquals(-1, loop.read(Capture::read));
    }

    /** Stopped between the failed read and the restart: nothing made, and
     *  the failed one stopped once - by the stop, not again by the restart. */
    @Test
    public void aStopJustAsAReadFailsStopsItOnce() {
        Capture first = new Capture("first", DEAD);
        Fakes fakes = new Fakes();
        fakes.toMake.add(new Capture("fresh", 960));
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(DEAD, loop.read(c -> {
            int r = c.read();
            loop.stop();
            return r;
        }));
        assertEquals(1, first.stops);
        assertEquals("nothing made", 1, fakes.toMake.size());
    }

    /** What the failed one held is let go once the restart is over, one
     *  way or another - once, though a new one would not start between
     *  (Voip's policy, held over the wait). */
    @Test
    public void aRestartIsOverOnceWhateverComesOfIt() {
        Capture first = new Capture("first", DEAD);
        Capture wontStart = new Capture("won't start", 960);
        wontStart.startFails = true;
        Fakes fakes = new Fakes();
        fakes.toMake.add(wontStart);
        fakes.toMake.add(new Capture("fresh", 960));
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        assertEquals(1, fakes.restartsOver);
        assertEquals("let go only once the new one has started",
                List.of("stop first", "make", "start won't start", "stop won't start", "make", "start fresh", "over"),
                fakes.order);
        assertTrue("the retry said as one", fakes.notes.stream().anyMatch(n -> n.startsWith("the new capture would not start; making another")));
        Fakes none = new Fakes();
        new RestartLoop<>(new Capture("alone", DEAD), none).read(Capture::read);
        assertEquals(1, none.restartsOver);
    }

    @Test
    public void nothingYetIsReadAgainWithoutMakingAnything() {
        Capture first = new Capture("first", 0, 0, 960);
        Fakes fakes = new Fakes();
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        assertEquals(960, loop.read(Capture::read));
        assertTrue(fakes.notes.isEmpty());
        assertEquals(List.of(RestartPolicy.PAUSE_MS, RestartPolicy.PAUSE_MS), fakes.slept);
        assertFalse(first.stops > 0);
    }

    /** Interrupted while it waits to make the capture anew - the recording
     *  being stopped: it ends there, says so to the thread, and makes
     *  nothing; swallowed, it went on making captures after the call. */
    @Test
    public void anInterruptWhileWaitingEndsTheReadAndMakesNothing() {
        Capture first = new Capture("first", DEAD);
        Capture fresh = new Capture("fresh", 960);
        Fakes fakes = new Fakes();
        fakes.toMake.add(fresh);
        fakes.interruptSleep = true;
        RestartLoop<Capture> loop = new RestartLoop<>(first, fakes);
        try {
            assertEquals(DEAD, loop.read(Capture::read));
            assertTrue("the interrupt kept", Thread.currentThread().isInterrupted());
            assertEquals("nothing made", 1, fakes.toMake.size());
            assertEquals("stopped as it failed", 1, first.stops);
            loop.stop();
            assertEquals("and once only", 1, first.stops);
        } finally {
            Thread.interrupted();
        }
    }
}
