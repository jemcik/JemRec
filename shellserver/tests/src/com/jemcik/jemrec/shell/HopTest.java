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
        }, 2_000, true);
        assertEquals("ARMED", answer);
        assertEquals("main-looper", ranOn[0].getName());
    }

    @Test
    public void aTaskNotRunInTimeIsGivenUpOn() throws Exception {
        Late thread = new Late();
        try {
            Hop.call(thread, () -> "ARMED", 50, true);
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
            Hop.call(thread, () -> ran.incrementAndGet(), 50, true);
            fail("no timeout");
        } catch (TimeoutException expected) {
            // Given up on.
        }
        thread.runAll();
        assertEquals(0, ran.get());
    }

    @Test
    public void aTaskNoOneWaitsOnIsLeftToRunLate() throws Exception {
        // The custodian's hang-up after an uninstall: no app has said
        // anything about it, so a late one still ends the message.
        Late thread = new Late();
        AtomicInteger ran = new AtomicInteger();
        try {
            Hop.call(thread, () -> ran.incrementAndGet(), 50, false);
            fail("no timeout");
        } catch (TimeoutException expected) {
            // The custodian logs it, and looks again later.
        }
        thread.runAll();
        assertEquals(1, ran.get());
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
        }, 50, true);
        assertEquals("TALKING", answer);
    }

    @Test
    public void theWaitIsShorterThanTheAppsForAGoAheadOrAHandOver() {
        // The app reads 'Y' and 'H' for 1.5 s (DaemonClient.goAhead,
        // handOver): given up on here a half second before, the session
        // ends while the app still listens.
        assertTrue(Hop.WAIT_MS <= 1_000);
    }

    @Test
    public void aCallAnsweredInTimeGivesItsAnswer() throws Exception {
        assertEquals(Boolean.FALSE, Hop.inTime("check", () -> false, 2_000));
        // On a daemon thread: one never answered must not keep the process.
        assertEquals(Boolean.TRUE, Hop.inTime("check", () -> Thread.currentThread().isDaemon(), 2_000));
    }

    @Test
    public void aCallNotAnsweredInTimeIsGivenUpOnAndLeftToFinish() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        try {
            Hop.inTime("check", () -> {
                release.await();
                return finished.incrementAndGet();
            }, 50);
            fail("no timeout");
        } catch (TimeoutException expected) {
            // The emergency check counts this as "may be".
        }
        release.countDown();
        for (int i = 0; i < 100 && finished.get() == 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(1, finished.get());
    }

    @Test
    public void whatTheCallThrowsComesBackAsItself() throws Exception {
        try {
            Hop.inTime("check", () -> {
                throw new SecurityException("no READ_PRIVILEGED_PHONE_STATE");
            }, 2_000);
            fail("nothing thrown");
        } catch (SecurityException expected) {
            assertEquals("no READ_PRIVILEGED_PHONE_STATE", expected.getMessage());
        }
    }
}
