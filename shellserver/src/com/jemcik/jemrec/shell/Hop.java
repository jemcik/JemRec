package com.jemcik.jemrec.shell;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * A task handed to another thread - the main looper, where the answering
 * machine lives and the call watch delivers its events - and waited for, as
 * a session thread does for the app's go-ahead, hand-over, settings and test
 * call, and the custodian for a message's hang-up. Plain Java, so
 * daemon-tests can hold it to what it does when that thread is late.
 */
final class Hop {

    private Hop() {
    }

    /** How long a hop to the main looper is waited for. */
    static final long WAIT_MS = 2_000L;

    /** Run [task] on [thread] and wait for its answer up to [timeoutMs]:
     *  a TimeoutException past it. */
    static @Nullable <T> T call(Executor thread, Callable<T> task, long timeoutMs) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        thread.execute(future);
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
