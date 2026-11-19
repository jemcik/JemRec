package com.jemcik.jemrec.shell;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A recording that stops without a read failing - a capture whose read only
 * blocks: on the Android 12 and 17 emulators, the audio server held down,
 * AudioRecord came back by itself after 22.5 and 23.7 s with no read failing
 * (ARCHITECTURE, 2026-11-18) - said as any stop is: nothing delivered for
 * [STALL_MS] is told stopped, and back once reads flow again. Until round
 * 10 of 1.2's reviews only a failed read was ever told, and through such a
 * gap the call's notice said "Recording call", the answering machine was not
 * told, and the call's report had nothing of it. Not while the restart loop
 * has said it already (its slow mode, [loopStopped]), nor once the recording
 * is ending or its encoder has ended ([over]: that end is told for good by
 * the recording, and a stop said after it turned "The rest of this call is
 * not recorded" into "Trying again" - review round 11). Back only once reads
 * have flowed for [BACK_HOLD_MS], as the restart policy's own BACK: a capture
 * that delivers now and then and blocks in between would flip it every few
 * seconds. Plain, looked at each second by the recording's thread
 * (RecordingSession), so daemon-tests holds it (StallWatchTest).
 */
final class StallWatch {

    /** Nothing delivered for this long is a stop: reads come every 20 ms. */
    static final long STALL_MS = 5_000L;

    /** Reads flowing this long after a stop is back (RestartPolicy's). */
    static final long BACK_HOLD_MS = RestartPolicy.BACK_HOLD_MS;

    /** A read this recent counts as flowing: the looks come each second. */
    private static final long FLOWING_MS = 1_500L;

    /** What a stall says. */
    interface Saying {
        /** Nothing has reached the recording since [sinceMs]. */
        void stopped(long sinceMs);

        void back();

        /** A read since the stop was said, at [atMs], that was no way back -
         *  none flowing at the end, or the restart loop's stop since: what
         *  the recording misses to its end starts after it. */
        void lastRead(long atMs);
    }

    private final LongSupplier lastDelivered;
    private final BooleanSupplier loopStopped;
    private final BooleanSupplier over;
    private final LongSupplier now;
    private final Saying saying;
    /** The last delivery it said a stop after; -1 for none said. */
    private long saidAfter = -1;
    /** Since when reads have flowed again after a stop said; -1 for not. */
    private long flowingSince = -1;

    StallWatch(LongSupplier lastDelivered, BooleanSupplier loopStopped, BooleanSupplier over, LongSupplier now,
               Saying saying) {
        this.lastDelivered = lastDelivered;
        this.loopStopped = loopStopped;
        this.over = over;
        this.now = now;
        this.saying = saying;
    }

    /** One look. */
    synchronized void look() {
        if (over.getAsBoolean()) {
            return;
        }
        long last = lastDelivered.getAsLong();
        long t = now.getAsLong();
        if (saidAfter >= 0) {
            if (loopStopped.getAsBoolean()) {
                // The loop has it now, and says its own way back. Reads that
                // came between were no way back: the loss starts after the
                // last - the loop's own stop is not kept over this one's
                // (RecordingLoss.stalled), and the early end was overstated
                // by the time between (review round 13).
                if (last > saidAfter) {
                    saying.lastRead(last);
                }
                saidAfter = -1;
                flowingSince = -1;
            } else if (last > saidAfter && t - last <= FLOWING_MS) {
                if (flowingSince < 0) {
                    flowingSince = t;
                }
                if (t - flowingSince >= BACK_HOLD_MS) {
                    saidAfter = -1;
                    flowingSince = -1;
                    saying.back();
                }
            } else {
                flowingSince = -1;
            }
            return;
        }
        // Not before the first read: a call's audio may take its time.
        if (last >= 0 && !loopStopped.getAsBoolean() && t - last >= STALL_MS) {
            saidAfter = last;
            saying.stopped(last);
        }
    }

    /** The recording is being stopped. A stop said whose reads flow again,
     *  the hold not yet over, is no loss to the end - not a recording that
     *  ran to its end named "_early" (review round 11). Reads that came and
     *  stopped again, or a loop gone slow since, are no way back: the loss
     *  starts after the last read - one read 3 s after the stop and 52 s of
     *  nothing to the hang-up was said back, and the file went unmarked
     *  (review round 12). */
    synchronized void closeOut() {
        long last = lastDelivered.getAsLong();
        if (saidAfter >= 0 && last > saidAfter) {
            if (!loopStopped.getAsBoolean() && now.getAsLong() - last <= FLOWING_MS) {
                saying.back();
            } else {
                saying.lastRead(last);
            }
        }
        saidAfter = -1;
        flowingSince = -1;
    }
}
