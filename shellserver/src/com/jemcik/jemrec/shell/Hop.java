package com.jemcik.jemrec.shell;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** How long a hop to the main looper is waited for: under the second and
     *  a half the app waits for 'Y' and 'H' (DaemonClient.goAhead, handOver),
     *  so what is given up on here is given up on before the app says so. */
    static final long WAIT_MS = 1_000L;

    /** How long a hop nothing is waiting on - the custodian's - is waited
     *  for before it is left to run when it may. */
    static final long EVENTUALLY_WAIT_MS = 2_000L;

    /**
     * Run [task] on [thread] and wait for its answer up to [timeoutMs]. Not
     * started by then, it is called off and never runs: a TimeoutException,
     * and the session ends with no reply, which the app reads as not done -
     * a go-ahead, a hand-over, a test call armed later would have made that
     * false. Started by then, it is waited for: on the main looper it runs
     * to its end, and a quick one. Without [callOff], a task not started in
     * time is left to run when its thread comes to it, and the caller gets
     * the TimeoutException: for one no app waits on.
     */
    static @Nullable <T> T call(Executor thread, Callable<T> task, long timeoutMs, boolean callOff)
            throws Exception {
        // Whichever comes first wins: the task starting, or the wait being
        // given up. (FutureTask.cancel cannot tell the two apart: it
        // succeeds on a task already running, which then runs on.)
        AtomicInteger state = new AtomicInteger(WAITING);
        FutureTask<T> future = new FutureTask<>(() ->
                state.compareAndSet(WAITING, STARTED) ? task.call() : null);
        thread.execute(future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            if (!callOff || state.compareAndSet(WAITING, CALLED_OFF)) {
                throw late;
            }
            return future.get();
        }
    }

    /**
     * Run [task] on a thread of its own and wait up to [timeoutMs]: its
     * answer; a TimeoutException, the thread left to finish on its own; or
     * what the task threw. For a call with no time limit of its own - a
     * binder call - that an answer must not wait on for long.
     */
    static <T> T inTime(String name, Callable<T> task, long timeoutMs) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        Thread thread = new Thread(future, name);
        thread.setDaemon(true);
        thread.start();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static final int WAITING = 0;
    private static final int STARTED = 1;
    private static final int CALLED_OFF = 2;
}
