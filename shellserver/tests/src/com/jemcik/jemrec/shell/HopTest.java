package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * The hop to the main looper (Hop), as the answering machine's commands take
 * it: the task run there, its answer waited for, and what happens when that
 * thread comes to it late.
 */
public class HopTest {

    /** A thread that runs what it is given only when told to. */
    private static final class Late implements Executor {
        final List<Runnable> waiting = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            waiting.add(task);
        }

        void runAll() {
            for (Runnable task : new ArrayList<>(waiting)) {
                task.run();
            }
            waiting.clear();
        }
    }

    @Test
    public void theTaskRunsOnTheThreadItIsGivenAndItsAnswerComesBack() throws Exception {
        Thread[] ranOn = new Thread[1];
        Executor thread = task -> {
            Thread t = new Thread(task, "main-looper");
            t.start();
        };
        String answer = Hop.call(thread, () -> {
            ranOn[0] = Thread.currentThread();
            return "ARMED";
        }, 2_000);
        assertEquals("ARMED", answer);
        assertEquals("main-looper", ranOn[0].getName());
    }

    @Test
    public void aTaskNotRunInTimeIsGivenUpOn() throws Exception {
        Late thread = new Late();
        try {
            Hop.call(thread, () -> "ARMED", 50);
            fail("no timeout");
        } catch (TimeoutException expected) {
            // The session ends without a reply.
        }
    }

    @Test
    public void aTaskGivenUpOnNeverRuns() throws Exception {
        // Given up on, the app told nothing - and it has said "not taken" by
        // then: a go-ahead, a hand-over or a test call's arming run later
        // would make that false.
        Late thread = new Late();
        AtomicInteger ran = new AtomicInteger();
        try {
            Hop.call(thread, () -> ran.incrementAndGet(), 50);
            fail("no timeout");
        } catch (TimeoutException expected) {
            // Given up on.
        }
        thread.runAll();
        assertEquals(0, ran.get());
    }

    @Test
    public void aTaskStartedAsItIsGivenUpOnIsWaitedFor() throws Exception {
        // Running by then, it cannot be called off: its answer is the reply.
        CountDownLatch started = new CountDownLatch(1);
        Executor thread = task -> {
            new Thread(task).start();
            try {
                started.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        };
        String answer = Hop.call(thread, () -> {
            started.countDown();
            Thread.sleep(300);
            return "TALKING";
        }, 50);
        assertEquals("TALKING", answer);
    }

    @Test
    public void theWaitIsShorterThanTheAppsForAGoAheadOrAHandOver() {
        // The app reads 'Y' and 'H' for 1.5 s (DaemonClient.goAhead,
        // handOver): given up on here a half second before, the session
        // ends while the app still listens.
        assertTrue(Hop.WAIT_MS <= 1_000);
    }
}
