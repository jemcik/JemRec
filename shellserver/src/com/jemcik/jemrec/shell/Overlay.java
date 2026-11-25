package com.jemcik.jemrec.shell;

/**
 * "Display over other apps" for the app's Record button over calls, allowed
 * or taken back when the user flips its switch (Wire.COMMAND_SET_OVERLAY).
 * Plain Java, so each answer is tested off the phone (OverlayTest); the app
 * op is Android's, through `cmd appops`.
 *
 * The permission is SYSTEM_ALERT_WINDOW, held by app op: nothing shows until
 * it is allowed (Settings.canDrawOverlays). Its other way on is Android's own
 * page, which from Android 11 is a list of every app to find this one in
 * (developer.android.com, "Permissions updates in Android 11"), and from
 * Android 15 one of the settings an app Android does not trust the installer
 * of - a downloaded APK's always, a store's where the phone lists its own - is
 * turned away from until its restricted settings are allowed (AOSP
 * EnhancedConfirmationService, isPackageEcmGuarded); the recorder is the
 * shell, which sets the op, as it allows exact call tracking's (Tracking).
 * Measured on the Android 12, 16 and 17 emulators
 * (2026-11-24): `appops set <package> SYSTEM_ALERT_WINDOW allow`, the
 * package's op and no uid's, and a window the app was refused before was
 * shown.
 *
 * Only at the switch, never again by itself: where the user takes it back on
 * Android's page, that is theirs to say, and the switch says what it costs
 * (the app's CallButton). Off puts it back to default, as an app never given
 * it has.
 */
final class Overlay {

    private Overlay() {
    }

    /** `cmd appops` for the app's SYSTEM_ALERT_WINDOW, as Shell runs it. */
    interface Op {
        /** Set the op's mode; what it printed, or null if it could not run. */
        @Nullable String set(String mode);

        /** Read it: what it printed, or null if it could not run. */
        @Nullable String get();
    }

    static final String NAME = "SYSTEM_ALERT_WINDOW";

    /**
     * The op set as [value] asks - 1 to allow, 0 to take back; anything else,
     * a byte that never came among them, writes nothing - and read back:
     * whether it now reads as asked. One at a time: each command has a thread
     * of its own (LoopbackServer).
     */
    static synchronized boolean set(int value, Op op) {
        if (value != 0 && value != 1) {
            return false;
        }
        op.set(value == 1 ? "allow" : "default");
        String said = op.get();
        return said != null && Checks.overlayAllowed(said) == (value == 1);
    }
}
