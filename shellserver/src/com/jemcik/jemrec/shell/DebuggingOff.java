package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

/**
 * "Turn all debugging off": the wait for nothing to be in progress, then both
 * switches written off (Wire.COMMAND_DEBUGGING_OFF).
 */
final class DebuggingOff {

    private DebuggingOff() {
    }

    /** Set by COMMAND_DEBUGGING_OFF: the switches go off the first moment
     *  nothing is in progress. */
    static volatile boolean goingOff;

    /** Set once that moment has come and the switches are being written - or
     *  the custodian writes them, the app uninstalled: only then does the
     *  shield stand down. Not for the call waited out before - USB debugging
     *  is what keeps adbd, and that call's recording, alive. */
    static volatile boolean switchingOff;

    /** The thread waiting for that moment, while there is one. */
    private static @Nullable Thread offWaiter;

    /** Start the wait for debugging off, unless one is waiting already. */
    static void goOffWhenFree() {
        synchronized (Calls.LOCK) {
            if (offWaiter != null) {
                return;
            }
            Thread waiter = new Thread(DebuggingOff::waitThenGoOff, "jemrec-off");
            waiter.setDaemon(true);
            offWaiter = waiter;
            waiter.start();
        }
    }

    /**
     * Wait for nothing to be in progress, then switch Wireless debugging off
     * and USB debugging after it. The second write stops adbd, and adbd's
     * cgroup goes with it - this process included, so nothing after it is
     * expected to run unless adbd lived on.
     */
    private static void waitThenGoOff() {
        while (true) {
            synchronized (Calls.LOCK) {
                if (!goingOff) {
                    offWaiter = null;
                    Ln.i("debugging off: called off before it happened");
                    return;
                }
                if (!Calls.busyLocked()) {
                    break;
                }
            }
            Shell.sleep(1_000L);
        }
        // A mute a message left behind is given back before anything goes.
        Answering.giveBackStaleMute();
        // And asked once more, last thing: an On whose cancel came after the
        // wait above ended would otherwise find debugging switched off under
        // it, the switches it had just put back gone again.
        synchronized (Calls.LOCK) {
            if (!goingOff) {
                offWaiter = null;
                Ln.i("debugging off: called off just before it happened");
                return;
            }
            switchingOff = true;
        }
        Ln.i("debugging off: Wireless debugging, then USB debugging - adbd stops, and this process with it");
        for (String setting : new String[]{"adb_wifi_enabled", "adb_enabled"}) {
            Shield.putGlobal(setting, "0");
        }
        // Still here: adbd lived on, or the writes were refused - and then the
        // app finishes the off (DebugSwitchesOff.switchOff). The app asked
        // for the recorder gone too.
        Shell.sleep(3_000L);
        Ln.i("debugging off: still running after both switches, exiting");
        System.exit(0);
    }
}
