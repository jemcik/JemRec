package com.jemcik.jemrec.shell;

/**
 * The read loop of a recording's capture made anew (Restarting), over
 * captures it knows only through [Captures]: plain Java, so daemon-tests
 * holds it (RestartLoopTest). The loop is where the decisions are: a read of
 * nothing read again for a while, a failed one stopped as it fails and made
 * anew after the policy's wait, a new capture that will not start not read -
 * it can only fail - but another made, one made after this was stopped let
 * go, each capture stopped exactly once, and past the policy's count the
 * recording said to have stopped and a capture made every SLOW_RETRY_MS
 * until one reads (RestartPolicy). Until the review of 2026-11-18 it was
 * Restarting's own, which no test could reach.
 *
 * THE FAILED ONE IS STOPPED FIRST. It was stopped once the new one had
 * started, and until then it went on: a phone call's mode thread swapped
 * captures through the restart's wait, under the one being made, and what
 * the new one took up from it - the microphone the guard gave back - was
 * read from what was still running, so the order was all that kept it right
 * (review, 2026-11-18). It is never read again either way; the new one is
 * given it (Captures.make) to take up what it knew, which stopping it does
 * not clear.
 */
final class RestartLoop<C> {

    /** What the loop does to a capture, and says. */
    interface Captures<C> {
        /** A new capture of the kind, not started, taking up where [failed] -
         *  the one read last, stopped, or a first one that would not start -
         *  left the call; null if none can be made. */
        @Nullable C make(C failed);

        void start(C capture) throws Exception;

        void stop(C capture);

        void sleep(long ms) throws InterruptedException;

        /** A line for the recorder's log; [error] for the end of a recording. */
        void log(String line, boolean error);

        /** A line for the call's report (Copy a report). */
        void note(String what);

        /** A monotonic clock in milliseconds, for the policy's period. */
        long nowMs();

        /** [failed] stopped as it fails, before a new one is made: what it
         *  held for the call is kept until [restartOver] - a messenger copy's
         *  policy, which with no claim on it the wait would have let go
         *  (Voip.holdPolicy; review, 2026-11-18). */
        default void stopFailed(C failed) {
            stop(failed);
        }

        /** The restart is over: a new capture started, or none. */
        default void restartOver() {
        }

        /** The recording has stopped: failed too often, it is tried again
         *  slowly (RestartPolicy.STOPPED); nothing has reached it since
         *  [sinceMs], on nowMs's clock - -1 when nothing ever did. On the
         *  reading thread. */
        default void stopped(long sinceMs) {
        }

        /** A read delivered at [atMs] while stopped, held for BACK
         *  (RestartPolicy.BACK_HOLD_MS): the loss to the end, if the call
         *  ends inside the hold, starts after it - not at the stop, which
         *  counted audio recorded since as missed (review round 10). */
        default void delivered(long atMs) {
        }

        /** It reads again after [stopped]: the recording goes on. */
        default void back() {
        }
    }

    /** One read of [capture]: bytes read, 0 for nothing yet, or an error. */
    interface Read<C> {
        int read(C capture);
    }

    private enum Made { STARTED, WOULD_NOT_START, NOT_MADE, NONE }

    /** The longest sleep between looks at whether this was stopped. In
     *  the recorder a call's end interrupts the wait at once - the encoder
     *  interrupts its input thread before it stops the capture
     *  (AudioEncoder.stop) - and the slices are for a stop with no
     *  interrupt (review, 2026-11-18). */
    static final long WAIT_SLICE_MS = 1_000L;

    /** The read a first capture that would not start stands for: an error
     *  no AudioRecord returns, said as such. */
    static final int FIRST_WOULD_NOT_START = -1000;

    private final Captures<C> captures;
    private final RestartPolicy policy;

    // Guarded by this: the capture read, whether it is stopped already (as
    // it failed), and whether this was stopped.
    private C current;
    private boolean currentStopped;
    private boolean stopped;
    /** The first capture would not start (Restarting.start): its first read
     *  is taken for a failed one, without reading it. */
    private boolean firstFailed;

    RestartLoop(C first, Captures<C> captures) {
        this.current = first;
        this.captures = captures;
        this.policy = new RestartPolicy(captures::nowMs);
    }

    synchronized C current() {
        return current;
    }

    /** The first capture would not start: the recording goes the restart's
     *  way - made anew, slowly past the policy's count - as one that fails
     *  later does. It ended the recording before, a failure one second on
     *  retried for the whole call (review, 2026-11-18). */
    synchronized void firstFailed() {
        firstFailed = true;
    }

    /** When a read last went to the encoder, on the clock; -1 for never.
     *  Any thread (StallWatch). */
    long lastDeliveredAt() {
        return policy.lastDeliveredAt();
    }

    /** Stopped by the policy, tried again slowly, not yet back. Any thread. */
    boolean slow() {
        return policy.slow();
    }

    /** A read of the capture now, made anew as the policy says: what it
     *  read, or the failed read the recording ends with. */
    int read(Read<C> reading) {
        while (true) {
            C capture;
            boolean failedFirst;
            synchronized (this) {
                if (stopped) {
                    return -1;
                }
                capture = current;
                failedFirst = firstFailed;
                firstFailed = false;
            }
            int r = failedFirst ? FIRST_WOULD_NOT_START : reading.read(capture);
            switch (policy.after(r)) {
                case DELIVER:
                    if (policy.slow()) {
                        captures.delivered(policy.lastDeliveredAt());
                    }
                    return r;
                case BACK:
                    captures.log("a capture reads again; the recording goes on", false);
                    captures.note("a capture read again, " + RestartPolicy.BACK_HOLD_MS / 1000
                            + " s since: the recording went on from there");
                    captures.back();
                    return r;
                case READ_AGAIN:
                    try {
                        captures.sleep(RestartPolicy.PAUSE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return r;
                    }
                    continue;
                case STOPPED:
                    stoppedHere(r == FIRST_WOULD_NOT_START
                            ? "the first capture would not start"
                            : "the capture failed again (read " + r + ")");
                    // fall through: made anew, slowly, as below.
                case MAKE_ANEW:
                default:
                    try {
                        Made made = anew(r, false);
                        // One that would not start is not read: it can only
                        // fail again. Another, slowly once the policy says so.
                        while (made == Made.WOULD_NOT_START) {
                            if (policy.failedToStart() == RestartPolicy.Next.STOPPED) {
                                stoppedHere("no new capture would start");
                            }
                            made = anew(r, true);
                        }
                        if (made == Made.NOT_MADE) {
                            return end(r, "no new capture could be made");
                        }
                        if (made == Made.NONE) {
                            return r;
                        }
                    } finally {
                        captures.restartOver();
                    }
            }
        }
    }

    /** The recording ends at a failed read, [why]: said. The encoder ends
     *  with it, which marks the file (RecordingSession). */
    private int end(int read, String why) {
        captures.log(why + "; the recording ends here", true);
        captures.note(why + ": the recording ended there");
        return read;
    }

    /** Failed too often, [why]: the recording has stopped, said, and is
     *  tried again every SLOW_RETRY_MS. */
    private void stoppedHere(String why) {
        captures.log(why + "; the recording has stopped - trying again every "
                + RestartPolicy.SLOW_RETRY_MS / 1000 + " s", true);
        captures.note(why + ": the recording stopped there, tried again every "
                + RestartPolicy.SLOW_RETRY_MS / 1000 + " s");
        // -1, nothing ever delivered: the recording's own start is where its
        // loss began, which the stop's moment understated by its tries.
        captures.stopped(policy.lastDeliveredAt());
    }

    /** A new capture in place of the one that failed, after the policy's
     *  wait: started, one that would not start, none to be made, or none -
     *  this stopped meanwhile. */
    private Made anew(int read, boolean again) {
        boolean slowly = policy.slow();
        String what = (again ? "the new capture would not start; making another, "
                : "the capture failed (read " + read + "); making it anew, ")
                + (slowly ? "in " + RestartPolicy.SLOW_RETRY_MS / 1000 + " s"
                        : policy.restarts() + " of " + RestartPolicy.MOST_RESTARTS + " within a minute");
        captures.log(what, false);
        // Once a try, not once every ten seconds of a long call: the stop and
        // the way back are the report's (stoppedHere, BACK).
        if (!slowly) {
            captures.note(what);
        }
        C failed;
        boolean stopIt;
        synchronized (this) {
            if (stopped) {
                return Made.NONE;
            }
            failed = current;
            stopIt = !currentStopped;
            currentStopped = true;
        }
        if (stopIt) {
            captures.stopFailed(failed);
        }
        // In slices, so a stop - the call's end - is not held for the slow
        // wait: the encoder's end, and the file's, wait on this thread.
        long left = policy.pauseBeforeRestartMs();
        while (left > 0) {
            synchronized (this) {
                if (stopped) {
                    return Made.NONE;
                }
            }
            long slice = Math.min(left, WAIT_SLICE_MS);
            try {
                captures.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Made.NONE;
            }
            left -= slice;
        }
        synchronized (this) {
            if (stopped) {
                return Made.NONE;
            }
        }
        C fresh = captures.make(failed);
        if (fresh == null) {
            return Made.NOT_MADE;
        }
        try {
            captures.start(fresh);
        } catch (Throwable t) {
            captures.log("the new capture would not start: " + t.getMessage(), false);
            // Once a try, not each slow one (review, 2026-11-18).
            if (!slowly) {
                captures.note("the new capture would not start: " + t.getMessage());
            }
            captures.stop(fresh);
            return Made.WOULD_NOT_START;
        }
        synchronized (this) {
            if (!stopped) {
                current = fresh;
                currentStopped = false;
                return Made.STARTED;
            }
        }
        captures.stop(fresh);
        return Made.NONE;
    }

    /** Stopped: what is read now is stopped, unless it was as it failed,
     *  and no new one is made. */
    void stop() {
        C reading;
        boolean stopIt;
        synchronized (this) {
            stopped = true;
            reading = current;
            stopIt = !currentStopped;
            currentStopped = true;
        }
        if (stopIt) {
            captures.stop(reading);
        }
    }
}
