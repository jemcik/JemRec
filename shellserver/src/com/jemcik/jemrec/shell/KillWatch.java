package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

/**
 * The app killed in the background by the phone, seen from here and told to
 * the app: see watchBackgroundKill.
 */
final class KillWatch {

    private KillWatch() {
    }

    /** How often to look for the app having been killed in the background. A
     *  minute: the read is cheap and the warning is advisory, not time-critical
     *  the way the cable watch is. */
    private static final long BG_KILL_CHECK_INTERVAL_MS = 60_000L;

    /**
     * Watch for THIS PHONE killing the app in the background and, when it
     * happens, wake the app so it can tell the user how to stop it (see
     * BackgroundKill on the app side).
     *
     * Measured on the Honor (2026-10-25): with App launch on "Manage
     * automatically", the screen going off had the system kill the app
     * mid-service and then mark it stopped, so nothing brought the recorder
     * back until the app was next opened. The daemon runs as shell in its own
     * session and is NOT frozen or killed by that - it is the one part left
     * running that can notice and raise the alarm at once.
     *
     * `dumpsys activity exit-info` lists the app's past exits, newest first;
     * Checks.backgroundKillStamp picks out the newest system kill that cost
     * something - one while the app was working, or one that left it stopped -
     * passing over Android's routine clean-up of an idle app and the user's
     * own Force stop. This side reads the stopped flag straight away; the app,
     * running again, has only Android 15's record of its own start, so it
     * trusts the wake's judgement. The newest stop seen at startup is the baseline - one
     * from before the daemon began watching is not news - and any newer one is
     * reported once, keyed by its timestamp (Checks.newStop). The app dedups again its own way,
     * so a stop the app also notices on next open is not told twice.
     */
    static void watchBackgroundKill() {
        java.util.Set<String> told = new java.util.HashSet<>();
        String baseline = backgroundKillStamp();
        Checks.newStop(told, baseline);
        Ln.i("killwatch: watching every " + (BG_KILL_CHECK_INTERVAL_MS / 1000)
                + "s, baseline " + baseline);
        while (true) {
            Shell.sleep(BG_KILL_CHECK_INTERVAL_MS);
            String stamp = backgroundKillStamp();
            if (Checks.newStop(told, stamp)) {
                Ln.w("killwatch: the system killed JemRec in the background (exit " + stamp
                        + ") - waking the app to warn");
                Wakes.wake(Wakes.ACTION_BACKGROUND_KILLED, false, null, null);
            }
        }
    }

    /** Checks.backgroundKillStamp on a fresh `dumpsys activity exit-info`, or
     *  null if the dump could not be read (Shell.runQuietly logs why). The package
     *  dump (36 ms on the Honor) is read only when the newest exit is an idle
     *  kill, the one case where stopped decides. */
    private static @Nullable String backgroundKillStamp() {
        return Checks.backgroundKillStamp(Shell.runQuietly("dumpsys", "activity", "exit-info", Main.APP_PACKAGE),
                () -> Checks.packageStopped(Shell.runQuietly("dumpsys", "package", Main.APP_PACKAGE)));
    }
}
