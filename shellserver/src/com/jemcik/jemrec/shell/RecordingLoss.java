package com.jemcik.jemrec.shell;

/**
 * Where what a recording misses begins: the earlier of its encoder's early
 * end and a stall it never came back from - stopped, and tried again slowly
 * (RestartPolicy), the encoder goes on, so its early end never comes, and
 * such a file went unmarked: messenger and tracking-off calls were never told
 * their recording stopped early (review, 2026-11-18). Plain, so daemon-tests
 * holds it (RecordingLossTest); RecordingSession.Ending keeps one.
 */
final class RecordingLoss {

    private long endedEarlyAt = -1;
    private long stalledSince = -1;

    /** The encoder ended before the recording was stopped, at [atMs]. */
    synchronized void endedEarly(long atMs) {
        if (endedEarlyAt < 0) {
            endedEarlyAt = atMs;
        }
    }

    /** Stopped and tried again slowly; nothing reached it since [sinceMs]. */
    synchronized void stalled(long sinceMs) {
        if (stalledSince < 0) {
            stalledSince = sinceMs;
        }
    }

    /** A read reached it at [atMs], stalled still - held for BACK, or a
     *  capture that reads and dies again: the loss to the end starts after
     *  it (review round 10). */
    synchronized void delivered(long atMs) {
        if (stalledSince >= 0 && atMs > stalledSince) {
            stalledSince = atMs;
        }
    }

    /** It reads again: the stall is no loss to the end. */
    synchronized void back() {
        stalledSince = -1;
    }

    /** Where the loss to the end began; -1 for none. */
    synchronized long since() {
        if (endedEarlyAt < 0) {
            return stalledSince;
        }
        return stalledSince < 0 ? endedEarlyAt : Math.min(endedEarlyAt, stalledSince);
    }
}
