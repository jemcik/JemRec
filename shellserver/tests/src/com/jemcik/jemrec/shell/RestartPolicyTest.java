package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A capture's failed reads: read again, made anew, or - failed too often -
 *  the recording said to have stopped and tried again slowly. */
public class RestartPolicyTest {

    /** The policy's clock, moved by hand. */
    private long now = 1_000_000L;
    private final java.util.function.LongSupplier clock = () -> now;

    @Test
    public void whatWasReadIsDelivered() {
        assertEquals(RestartPolicy.Next.DELIVER, new RestartPolicy(clock).after(4096));
    }

    @Test
    public void nothingYetIsReadAgainForAWhile() {
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 1; i < RestartPolicy.MOST_EMPTY_READS; i++) {
            assertEquals("empty read " + i, RestartPolicy.Next.READ_AGAIN, policy.after(0));
        }
        // Then nothing for too long: failed.
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(0));
    }

    @Test
    public void somethingReadBetweenEmptiesStartsTheirCountAgain() {
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 1; i < RestartPolicy.MOST_EMPTY_READS; i++) policy.after(0);
        policy.after(4096);
        assertEquals(RestartPolicy.Next.READ_AGAIN, policy.after(0));
    }

    @Test
    public void anErrorMakesItAnewAFewTimesThenStopsAndTriesSlowly() {
        // AudioRecord.ERROR_DEAD_OBJECT: "needs to be recreated".
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 1; i <= RestartPolicy.MOST_RESTARTS; i++) {
            assertEquals("failure " + i, RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
            assertEquals(i, policy.restarts());
            // Reads well in between: still counted, within the period.
            assertEquals(RestartPolicy.Next.DELIVER, policy.after(4096));
            now += 1_000;
        }
        assertEquals(RestartPolicy.Next.STOPPED, policy.after(-6));
        assertTrue(policy.slow());
        assertEquals(RestartPolicy.SLOW_RETRY_MS, policy.pauseBeforeRestartMs());
        // Never given up on: made anew, slowly, as often as it fails.
        for (int i = 0; i < 100; i++) {
            assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
            assertEquals(RestartPolicy.SLOW_RETRY_MS, policy.pauseBeforeRestartMs());
            now += RestartPolicy.SLOW_RETRY_MS;
        }
        // Back - said once, after reads have held BACK_HOLD_MS.
        assertEquals("delivered, not yet back", RestartPolicy.Next.DELIVER, policy.after(4096));
        now += RestartPolicy.BACK_HOLD_MS - 1;
        assertEquals(RestartPolicy.Next.DELIVER, policy.after(4096));
        now += 1;
        assertEquals(RestartPolicy.Next.BACK, policy.after(4096));
        assertFalse(policy.slow());
        assertEquals(RestartPolicy.Next.DELIVER, policy.after(4096));
        // The restarts long past have aged out of the period: one more is a
        // restart, not a stop.
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
        assertEquals(RestartPolicy.RESTART_PAUSE_MS, policy.pauseBeforeRestartMs());
    }

    /** A capture that reads once and dies, again and again, is not back:
     *  reads must hold, or the notification flipped and two wakes went every
     *  ten seconds (review, 2026-11-18). */
    @Test
    public void readsThatDoNotHoldAreNotBack() {
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            policy.after(-6);
        }
        assertEquals(RestartPolicy.Next.STOPPED, policy.after(-6));
        for (int i = 0; i < 20; i++) {
            assertEquals(RestartPolicy.Next.DELIVER, policy.after(4096));
            now += 1_000;
            assertEquals("slow still, no second STOPPED", RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
            now += RestartPolicy.SLOW_RETRY_MS;
        }
        assertTrue(policy.slow());
    }

    /** Back within the period, a failure soon after is slow at once: the
     *  restarts still count, as an OTP supervisor's window rolls. */
    @Test
    public void backWithinThePeriodAFailureIsSlowAtOnce() {
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            policy.after(-6);
        }
        assertEquals(RestartPolicy.Next.STOPPED, policy.after(-6));
        policy.after(4096);
        now += RestartPolicy.BACK_HOLD_MS;
        assertEquals(RestartPolicy.Next.BACK, policy.after(4096));
        assertEquals(RestartPolicy.Next.STOPPED, policy.after(-6));
    }

    /** Where what the recording misses begins: the last read delivered. */
    @Test
    public void theLastDeliveredReadIsKept() {
        RestartPolicy policy = new RestartPolicy(clock);
        assertEquals(-1, policy.lastDeliveredAt());
        policy.after(4096);
        long delivered = now;
        now += 3_000;
        policy.after(-6);
        assertEquals(delivered, policy.lastDeliveredAt());
    }

    /** A capture made anew that would not start counts as a restart, and
     *  the next is decided without reading the failed one again. */
    @Test
    public void aNewCaptureThatWouldNotStartUsesARestart() {
        RestartPolicy policy = new RestartPolicy(clock);
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.failedToStart());
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.failedToStart());
        assertEquals(RestartPolicy.MOST_RESTARTS, policy.restarts());
        assertEquals(RestartPolicy.Next.STOPPED, policy.failedToStart());
        assertEquals("said once, then slowly", RestartPolicy.Next.MAKE_ANEW, policy.failedToStart());
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
        assertEquals(RestartPolicy.SLOW_RETRY_MS, policy.pauseBeforeRestartMs());
    }

    /** Each restart waits longer than the last: 1.5 s in all for three, so
     *  an audio server still coming back has the time to. */
    @Test
    public void eachRestartWaitsLongerThanTheLast() {
        RestartPolicy policy = new RestartPolicy(clock);
        long waited = 0;
        long last = 0;
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
            long pause = policy.pauseBeforeRestartMs();
            assertTrue(pause > last);
            last = pause;
            waited += pause;
        }
        assertEquals(1_500L, waited);
    }

    /** A restart longer ago than the period no longer counts: an hour's
     *  call whose audio server went down four times, far apart, ended at
     *  the fourth when three were all a recording had. */
    @Test
    public void restartsFarApartNeverRunOut() {
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 1; i <= 10; i++) {
            assertEquals("failure " + i, RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
            assertEquals("the wait starts again with the count", RestartPolicy.RESTART_PAUSE_MS,
                    policy.pauseBeforeRestartMs());
            now += RestartPolicy.RESTART_PERIOD_MS;
        }
    }

    /** Within the period they count, the oldest dropping out of it first. */
    @Test
    public void onlyThoseWithinThePeriodCount() {
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
            now += 10_000;
        }
        assertEquals(RestartPolicy.Next.STOPPED, policy.after(-6));
        // Stopped, it stays slow until a capture reads, the period or not.
        now += RestartPolicy.RESTART_PERIOD_MS;
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
        assertEquals(RestartPolicy.SLOW_RETRY_MS, policy.pauseBeforeRestartMs());
    }

    /** Within the period, one restart a period old drops out first: a
     *  fourth then is a restart, not a stop. */
    @Test
    public void theOldestDropsOutOfThePeriodFirst() {
        RestartPolicy policy = new RestartPolicy(clock);
        for (int i = 0; i < RestartPolicy.MOST_RESTARTS; i++) {
            assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
            now += 10_000;
        }
        now += RestartPolicy.RESTART_PERIOD_MS - 3 * 10_000;
        assertEquals(RestartPolicy.Next.MAKE_ANEW, policy.after(-6));
        assertEquals(RestartPolicy.MOST_RESTARTS, policy.restarts());
        assertFalse(policy.slow());
    }
}
