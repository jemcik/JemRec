package com.jemcik.jemrec.shell;

/**
 * When a capture whose read failed is made anew (Restarting), and when it is
 * given up on. Plain Java, so daemon-tests holds it.
 *
 * WHY. AudioRecord.read gives "zero or the positive number of bytes", or an
 * error, and ERROR_DEAD_OBJECT means the record "needs to be recreated"
 * (developer.android.com, AudioRecord). The recorder, scrcpy's encoder as it
 * came, took any read but a positive one for the end of the recording: one
 * failed read mid-call and the rest of the call went unrecorded. (An audio
 * server killed mid-call did not make one on the Android 12 and 17
 * emulators, 2026-11-18: AudioRecord restored itself, gaps of 1.1 to 1.5 s.) Now a
 * read of nothing is read again for a while, a failed one makes the capture
 * anew, a few times a minute at most, and then once every SLOW_RETRY_MS for
 * the rest of the call: the recording is said to have stopped (the app shows
 * it during the call, the answering machine hangs up on a message it can no
 * longer keep), and goes on, a gap in it, once a capture reads again. Until
 * 2026-11-18 it ended there for good, and the rest of the call went
 * unrecorded for a failure that may pass.
 *
 * A FEW TIMES A MINUTE, NOT A CALL. Three a recording, as this first was,
 * never came back: an hour's call whose audio server went down four times,
 * far apart, would have ended at the fourth (read from the code; not seen). A supervisor in Erlang/OTP - the practice
 * this follows - gives up on a child after more than its intensity of
 * restarts within a period ("If more than MaxR number of restarts occur in
 * the last MaxT seconds", OTP Design Principles, Supervisor Behaviour), so a
 * failure that keeps coming ends it and one now and then does not (review,
 * 2026-11-18). Here a failure that keeps coming slows the restarts down
 * rather than ending them: the call is not this process's to end, and what a
 * restart costs is a capture made, once in ten seconds.
 */
final class RestartPolicy {

    /** Made anew at most this often within RESTART_PERIOD_MS. */
    static final int MOST_RESTARTS = 3;
    /** A restart longer ago than this no longer counts. */
    static final long RESTART_PERIOD_MS = 60_000L;

    /** Reads of nothing in a row, read again PAUSE_MS apart, before the
     *  capture counts as failed: 20 x 10 ms, longer than any one read. */
    static final int MOST_EMPTY_READS = 20;
    static final long PAUSE_MS = 10L;

    /** The wait before the first restart within the period; the second
     *  waits twice it, the third three times: 250, 500 and 750 ms, 1.5 s in
     *  all before the recording is said to have stopped. Without one, a capture made while the audio server
     *  was still coming back would fail at once, and the three restarts go
     *  in milliseconds (read from the code in review, 2026-11-18; not
     *  measured). */
    static final long RESTART_PAUSE_MS = 250L;

    /** Past MOST_RESTARTS within the period, a capture is made anew this
     *  often until one reads. */
    static final long SLOW_RETRY_MS = 10_000L;

    /** Stopped, reads must hold this long before the recording is said to
     *  go on (BACK): a capture that reads once and dies, again and again,
     *  flipped the call's notification and sent two wakes every ten seconds
     *  (review, 2026-11-18). */
    static final long BACK_HOLD_MS = 5_000L;

    enum Next {
        /** What was read goes to the encoder. */
        DELIVER,
        /** What was read goes to the encoder, reads having held BACK_HOLD_MS
         *  since the recording stopped (STOPPED): it goes on - say so. */
        BACK,
        /** Nothing yet: read again after PAUSE_MS. */
        READ_AGAIN,
        /** Failed: make the capture anew and read from that. */
        MAKE_ANEW,
        /** Failed too often: the recording has stopped - say so - and the
         *  capture is made anew every SLOW_RETRY_MS (MAKE_ANEW from here)
         *  until one reads (BACK). */
        STOPPED,
    }

    /** A monotonic clock in milliseconds: SystemClock.elapsedRealtime on
     *  the phone, through its sleep. */
    private final java.util.function.LongSupplier clock;
    /** When each restart within the period was decided, oldest first. */
    private final java.util.ArrayDeque<Long> recent = new java.util.ArrayDeque<>();
    private int empties;
    /** Stopped (STOPPED), and not yet back (BACK). Volatile: the stall
     *  watch reads it from its own thread (StallWatch). */
    private volatile boolean slow;
    /** Since when reads have held while stopped; -1 for not. */
    private long holdingSince = -1;
    /** When a read last went to the encoder; -1 for never: where what the
     *  recording misses begins, once it stops (RecordingSession). */
    private volatile long lastDeliveredAt = -1;

    RestartPolicy(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** What to do after a read that gave [read]. */
    Next after(int read) {
        if (read > 0) {
            empties = 0;
            long now = clock.getAsLong();
            lastDeliveredAt = now;
            if (slow) {
                if (holdingSince < 0) {
                    holdingSince = now;
                }
                if (now - holdingSince < BACK_HOLD_MS) {
                    return Next.DELIVER;
                }
                // Back. The restarts within the period still count, as an
                // OTP supervisor's do - its window rolls, no good run resets
                // it: one more failure soon is slow at once.
                slow = false;
                holdingSince = -1;
                return Next.BACK;
            }
            return Next.DELIVER;
        }
        holdingSince = -1;
        if (read == 0 && ++empties < MOST_EMPTY_READS) {
            return Next.READ_AGAIN;
        }
        empties = 0;
        return restart();
    }

    /** The capture made anew would not start: another - the failed one is
     *  not read again, as it can only fail - soon, or, past the policy's
     *  count, slowly. */
    Next failedToStart() {
        return restart();
    }

    private Next restart() {
        if (slow) {
            return Next.MAKE_ANEW;
        }
        long now = clock.getAsLong();
        while (!recent.isEmpty() && now - recent.peekFirst() >= RESTART_PERIOD_MS) {
            recent.pollFirst();
        }
        if (recent.size() >= MOST_RESTARTS) {
            slow = true;
            return Next.STOPPED;
        }
        recent.addLast(now);
        return Next.MAKE_ANEW;
    }

    /** How long to wait before the restart just decided: longer for each
     *  within the period, and SLOW_RETRY_MS once stopped. */
    long pauseBeforeRestartMs() {
        return slow ? SLOW_RETRY_MS : RESTART_PAUSE_MS * recent.size();
    }

    /** Stopped, and trying slowly. */
    boolean slow() {
        return slow;
    }

    /** When a read last went to the encoder, on the policy's clock; -1 for
     *  never. */
    long lastDeliveredAt() {
        return lastDeliveredAt;
    }

    /** How many times it has been made anew within the period. */
    int restarts() {
        return recent.size();
    }
}
