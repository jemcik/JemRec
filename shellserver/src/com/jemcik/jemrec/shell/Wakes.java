package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

/**
 * Waking the app's service with `am start-foreground-service`: a call's start
 * and end, a message, the cable, a background kill. One queue for the calls'
 * wakes, so they reach the app in the order they were asked for.
 */
final class Wakes {

    private Wakes() {
    }

    private static final String APP_SERVICE = "com.jemcik.jemrec/.capture.CallMonitorService";
    static final String ACTION_CALL_STARTED = "com.jemcik.jemrec.CALL_STARTED";
    static final String ACTION_CALL_ENDED = "com.jemcik.jemrec.CALL_ENDED";
    /** A USB cable went in or came out: adbd may be about to go. See watchCable. */
    static final String ACTION_CABLE = "com.jemcik.jemrec.CABLE";
    static final String ACTION_BACKGROUND_KILLED = "com.jemcik.jemrec.BACKGROUND_KILLED";

    static final String ACTION_MESSAGE_STARTED = "com.jemcik.jemrec.MESSAGE_STARTED";
    static final String ACTION_MESSAGE_TAKEN = "com.jemcik.jemrec.MESSAGE_TAKEN";

    /**
     * The hand-off at hang-up is the one that carries the recording, and it
     * has failed: measured 2026-09-10 19:40, `am start-foreground-service`
     * exited 255 for CALL_ENDED seconds after exiting 0 for CALL_STARTED of
     * the same call, and the finished file sat here until the app next
     * happened to open and its reconcile collected it. WHY it failed is not
     * known, because this logged the exit code and threw the output away -
     * so am's output is kept now, and the wake is tried a few times before
     * the reconcile is left to pick up the pieces.
     */
    private static final int WAKE_ATTEMPTS = 3;
    private static final long WAKE_RETRY_MS = 2000L;
    /** How long one `am` may take: one that hangs must not hold up for good
     *  the wakes queued behind it. */
    private static final long WAKE_TIMEOUT_MS = 10_000L;

    /**
     * The wakes, one at a time, in the order they were asked for. Each used
     * to have a thread and an `am` of its own, so two of them a moment apart
     * - a message's start and its call's end, a Record prompt and the end
     * that takes it down - could reach the app the other way round, and leave
     * "Taking a message", or the prompt, up for a call that was over.
     */
    private static final java.util.concurrent.ExecutorService WAKES =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "jemrec-wake");
                thread.setDaemon(true);
                return thread;
            });

    private static final java.util.concurrent.ExecutorService CABLE_WAKES =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "jemrec-cable-wake");
                thread.setDaemon(true);
                return thread;
            });

    /** Off the main thread: `am` takes a moment, and the looper must not wait for it. */
    static void wake(String action, boolean incoming, @Nullable String recfile, @Nullable String app,
            String... extras) {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.Arrays.asList(
                "am", "start-foreground-service",
                "-n", APP_SERVICE,
                "-a", action,
                "--es", "token", Main.appToken,
                "--es", "recfile", recfile == null ? "" : recfile,
                "--ez", "incoming", String.valueOf(incoming)));
        if (app != null) {
            // A messenger call: which one, for the app's notification.
            command.add("--es");
            command.add("app");
            command.add(app);
        }
        command.addAll(java.util.Arrays.asList(extras));
        long seq = Main.callSeq;
        boolean start = ACTION_CALL_STARTED.equals(action) || ACTION_MESSAGE_STARTED.equals(action)
                || ACTION_MESSAGE_TAKEN.equals(action);
        // A cable's wake has its own queue: it has three seconds before adbd
        // goes, and must not wait them out behind a call's wake whose `am`
        // is slow (exit 255 has been seen; each try may take ten seconds).
        // It is ordered against nothing a call's wakes are.
        (ACTION_CABLE.equals(action) ? CABLE_WAKES : WAKES).execute(() -> {
            for (int attempt = 1; attempt <= WAKE_ATTEMPTS; attempt++) {
                if (CallDecisions.staleStart(start, Main.endedSeq, seq)) {
                    // That call is over, and its CALL_ENDED is queued behind
                    // this: a start landing now would put up a prompt, a
                    // "Recording" or a "Taking a message" for a call that has
                    // ended - until the next one did.
                    Ln.i("wake: the call is over, not sending " + action);
                    return;
                }
                AnswerMachine machine = Answering.answerMachine;
                if (ACTION_MESSAGE_STARTED.equals(action) && (machine == null || !machine.takingMessage())) {
                    // Taken over already: the wake queued behind this one
                    // tells the app so.
                    Ln.i("wake: the message is over, not sending " + action);
                    return;
                }
                try {
                    Process process = new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start();
                    // Its few lines of output wait in the pipe meanwhile.
                    if (!process.waitFor(WAKE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        Ln.w("wake: " + action + " (" + recfile + ") -> no answer in "
                                + WAKE_TIMEOUT_MS / 1000 + " s on attempt " + attempt);
                    } else {
                        String said = Shell.slurp(process);
                        int exit = process.exitValue();
                        if (exit == 0) {
                            Ln.i("wake: " + action + " (" + recfile + ") -> exit 0"
                                    + (attempt > 1 ? " on attempt " + attempt : ""));
                            return;
                        }
                        Ln.w("wake: " + action + " (" + recfile + ") -> exit " + exit
                                + " on attempt " + attempt + ": " + said);
                    }
                } catch (Throwable t) {
                    Ln.e("wake: could not wake the app", t);
                }
                Shell.sleep(WAKE_RETRY_MS);
            }
        });
    }
}
