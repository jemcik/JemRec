package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

/**
 * Exact call tracking: the permission Telecom checks before it binds the
 * app's call service, which only the shell can allow (Wire.COMMAND_SET_TRACKING).
 */
final class Tracking {

    private Tracking() {
    }

    static volatile boolean trackingOn;

    /** One applyTracking at a time - the start-up thread's and the app's 'T'
     *  can overlap - so the last to run applies what trackingOn says by then,
     *  not what it said before the other ran. Not the call lock (Calls.LOCK):
     *  that is held at call start and end, and these take a moment. */
    private static final Object TRACKING_LOCK = new Object();

    static void applyTracking() {
        synchronized (TRACKING_LOCK) {
            String mode = trackingOn ? "allow" : "default";
            String said = Shell.runQuietly("cmd", "appops", "set", "--uid", Main.APP_PACKAGE, "MANAGE_ONGOING_CALLS", mode);
            Shell.runQuietly("cmd", "appops", "set", Main.APP_PACKAGE, "MANAGE_ONGOING_CALLS", mode);
            // Silent when it works. An OPPO, OnePlus or realme phone with its
            // Developer options switch off refuses the shell this (CallVault,
            // measured on a OnePlus 9, 2026-09-16; the app's ShellGrant): its
            // first lines, as the stack trace after them runs to twenty.
            if (said != null && !said.isEmpty()) {
                Ln.w("tracking: Android said " + said.substring(0, Math.min(said.length(), 300)));
            }
            Ln.i("tracking: " + (trackingOn ? "on - Telecom may bind the app's call service" : "off"));
        }
    }

    /** At each heartbeat: still allowed, if it should be? */
    static void ensureTracking() {
        if (!trackingOn) {
            return;
        }
        String now = Shell.runQuietly("cmd", "appops", "get", "--uid", Main.APP_PACKAGE, "MANAGE_ONGOING_CALLS");
        if (!uidAllowed(now)) {
            Ln.w("tracking: the permission had been withdrawn (" + now + "); allowing it again");
            applyTracking();
        }
    }

    /** See Checks.uidAllowed. */
    static boolean uidAllowed(@Nullable String said) {
        return Checks.uidAllowed(said);
    }
}
