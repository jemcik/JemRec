package com.jemcik.jemrec.shell;

/**
 * Developer options' Default USB configuration, read for the app and set to
 * none - "No data transfer" - when it asks (Wire.COMMAND_USB_DEFAULT). Plain
 * Java, so each answer is tested off the phone (UsbDefaultTest); Android's
 * USB service is Platform's.
 *
 * WHY IT MATTERS (#131; tmp/issue-131/FINDINGS.md). With a PIN, pattern or
 * password and no cable, AOSP's UsbDeviceManager switches the USB functions
 * to charging at every lock and back to this default at every unlock:
 * "We are unlocked when the keyguard is down or non-secure"
 * (onKeyguardStateChanged), then MSG_UPDATE_SCREEN_LOCK - with no cable
 * check at the unlock. Where the default is a data mode, each switch sets
 * the USB config to none first (UsbHandlerLegacy, "Kick the USB stack"),
 * init's rule for none is `stop adbd`, and this process, in adbd's cgroup,
 * dies with it. Measured 2026-11-23 with File transfer as the default: on
 * the Android 12, 16 and 17 emulators the recorder was killed at every lock
 * and unlock, and nothing brought it back (no USB_STATE is sent for these);
 * a lock 5 s into a call left 5.64 s of a 15.80 s call. On the Honor
 * (MagicOS 11, Android 17) it was killed at both too, and brought back 16 s
 * later each time by the app's USB-state watch. With No data transfer, or a
 * lock that is not secure, nothing was killed.
 *
 * Only an explicit 1 writes: a byte that never came reads as -1, and is a
 * read. Nothing is written while a call is up or a fetch under way, nor
 * while Telecom holds any call - ringing, being dialled, held, or an app's
 * own (TelecomManager.isInCall) - as the write can restart adbd and end this
 * process, and a call answered in the seconds it takes to come back would
 * lose its start, or go unanswered by the answering machine (review rounds 2
 * and 3: Calls.busyLocked, shared with a quit, leaves a ring out) - nor where
 * the default is none
 * already: setting none again switches the current functions to charging
 * (MSG_SET_SCREEN_UNLOCKED_FUNCTIONS), which would cut a computer's file
 * transfer for nothing.
 */
final class UsbDefault {

    private UsbDefault() {
    }

    /** The command's byte: read the default. */
    static final int READ = 0;

    /** The command's byte: set it to none. */
    static final int SET_NONE = 1;

    /** UsbManager.FUNCTION_ADB (GadgetFunction.ADB): debugging, not data. A
     *  default Android lets be set never holds it (UsbManager
     *  .areSettableFunctions), but a value holding only it is no data mode
     *  either. */
    static final long FUNCTION_ADB = 1L;

    /** The last answer the log said: home's every reading asks, and a read
     *  is logged only when it says something new (review round 2). */
    private static @Nullable String lastLogged;

    /** Whether [answer] to [value] goes in the log - a set always, a read
     *  only when it differs from the last one said - noted as said if so:
     *  the check and the note in one call, under the class's lock, so two
     *  reads at once log it once, and the caller cannot leave the note out
     *  (review round 4). */
    static synchronized boolean logOnce(int value, String answer) {
        if (value != SET_NONE && answer.equals(lastLogged)) return false;
        lastLogged = answer;
        return true;
    }

    /** For the tests: nothing said yet. */
    static synchronized void forgetLogged() {
        lastLogged = null;
    }

    /** Whether [functions] carry no data function: No data transfer. */
    static boolean none(long functions) {
        return (functions & ~FUNCTION_ADB) == 0;
    }

    /**
     * The answer to [value] (READ, SET_NONE, or anything else as a read),
     * given the default as Android's USB service gave it - null where it
     * could not be asked - whether a call is up or a fetch under way
     * ([busy], Calls.busyLocked), and whether Telecom holds a call
     * ([telecomCall]: ringing, dialled, up or held, an app's own too; not
     * asked is none). USB setting means none is to be
     * written once this answer is sent: the write can end this process, so
     * the answer goes first, as a quit's BYE does.
     */
    static String decide(int value, @Nullable Long functions, boolean busy, boolean telecomCall) {
        if (functions == null) {
            return Wire.USB_UNREADABLE;
        }
        if (value != SET_NONE || none(functions)) {
            return Wire.usbDefault(functions);
        }
        return busy || telecomCall ? Wire.USB_BUSY : Wire.USB_SETTING;
    }
}
