package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

/**
 * Notification access for the app's listener, granted by this process, the
 * shell (Wire.COMMAND_ALLOW_LISTENER): the one component named here, which
 * the app declares and must not rename (its FrozenNamesTest).
 */
final class Listener {

    private Listener() {
    }

    /** The app's NotificationListenerService, written out as Android keeps it
     *  in its list of allowed listeners. */
    static final String APP_LISTENER = "com.jemcik.jemrec/com.jemcik.jemrec.capture.DebugNotificationCleaner";

    /** How long Android's list is read for the grant. Android writes it a
     *  quarter of a second after it allows the listener: NotificationManager
     *  saves its policy on a delay, and the list is written with that save
     *  (read in AOSP, android12-release to main, not measured). The app
     *  waits ten seconds for the answer. */
    private static final long READ_BACK_MS = 2_000;

    /** Grants it, and reads Android's list back until it is there or the
     *  time is up: true if it is there. On the monotonic clock: a clock set
     *  back would have it read on and on. */
    static boolean allow() {
        String said = Shell.runQuietly("cmd", "notification", "allow_listener", APP_LISTENER);
        long until = System.nanoTime() + READ_BACK_MS * 1_000_000L;
        boolean allowed = listed();
        while (!allowed && System.nanoTime() - until < 0) {
            Shell.sleep(100);
            allowed = listed();
        }
        // `cmd` prints nothing when it works and one line on a refusal,
        // still ending with 0 (AOSP, android12-release to main); the first
        // words of whatever another ROM prints, in case it runs long.
        if (said != null && !said.isEmpty()) {
            Ln.w("listener: Android said " + said.substring(0, Math.min(said.length(), 300)));
        }
        // Whole lines, as the emulator checks match them.
        Ln.i(allowed ? "listener: notification access allowed for the app"
                : "listener: notification access not allowed for the app");
        return allowed;
    }

    private static boolean listed() {
        return Checks.listenerAllowed(
                Shell.runQuietly("settings", "get", "secure", "enabled_notification_listeners"), APP_LISTENER);
    }
}
