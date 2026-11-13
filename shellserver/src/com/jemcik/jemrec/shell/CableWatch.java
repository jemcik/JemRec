package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

/**
 * A USB cable going in or out, passed on to the app while adbd may still be
 * there: see watchCable.
 */
final class CableWatch {

    private CableWatch() {
    }

    /** One second: the warning is about three (see watchCable), and `am`
     *  takes a fraction of one. A read is one binder call, about 0.2 ms
     *  measured, and a sleeping phone runs none of them. */
    private static final long CABLE_CHECK_INTERVAL_MS = 1000L;

    /** How often to look again for a USB service, or ports, not there yet. */
    private static final long CABLE_PORTS_RETRY_MS = 60 * 1000L;

    /**
     * A USB CABLE IS THE ONE WARNING THIS PROCESS GETS THAT ADBD IS ABOUT TO GO.
     *
     * Unplugging the phone from a computer stops adbd, and stopping adbd
     * kills this process with it: init kills adbd's whole cgroup
     * (/system/uid_0/pid_<adbd>, a cgroup.kill file), this process is in it
     * whatever its session, and shell cannot leave - every cgroup.procs is
     * system:system and shell is not in group system. Measured on the Honor
     * (MagicOS 11, Android 17), 2026-10-24 18:44: the port read "no cable"
     * at 18:44:18.26 and a probe started the way this process is (a one-off,
     * not kept) ticked for the last time at 18:44:21.08 - Android's three-second debounce of a
     * USB disconnect, then the functions reset without "adb", and init
     * stopped adbd. And adbd STAYED stopped, both debugging switches still
     * on, until Wireless debugging was switched off and on three minutes
     * later. At 17:06 the same day nothing did that, and nothing recorded
     * for 74 minutes.
     *
     * So this watches the cable and hands over while it can: it wakes the
     * app, which outlives adbd (zygote started it, in a cgroup of its own),
     * and the app waits to see whether this process goes and, if it does,
     * starts adbd and this daemon again over Wireless debugging. A wall
     * charger, plugged or pulled, left adbd alone (measured the same
     * evening), and so does the app's watch: it ends after a few seconds,
     * before Android would show its notification. Both directions wake it,
     * because which of them restarts adbd is the phone's business: plugging
     * into a computer started adbd every time the event log shows, and adbd
     * is often already running then.
     *
     * Polled, not observed: a receiver for the power and USB broadcasts is
     * refused to a process the ActivityManager did not start, exactly as the
     * settings observer is (Shield.keepShieldUp) - measured on the Android 17
     * emulator, "registerReceiverWithFeature: no app for null". Shell's
     * MANAGE_USB lets this read the USB port's status from the USB service
     * instead, which flips the moment the cable does (the battery status
     * does not: plugged in at 80 % it stayed "discharging").
     */
    static void watchCable() {
        Platform.Usb usb = null;
        // Looked for until found: a recorder started just after boot can be
        // up before the USB service is. Said once, not every minute.
        boolean said = false;
        while (usb == null || usb.ports.isEmpty()) {
            try {
                usb = Platform.Usb.find();
                if (usb.ports.isEmpty() && !said) {
                    Ln.i("cable: no USB port to watch yet");
                    said = true;
                }
            } catch (Throwable t) {
                if (!said) {
                    Ln.w("cable: cannot read the USB ports yet: " + Shell.rootCause(t));
                    said = true;
                }
            }
            if (usb == null || usb.ports.isEmpty()) {
                Shell.sleep(CABLE_PORTS_RETRY_MS);
            }
        }
        java.util.List<String> ports = usb.ports;
        Ln.i("cable: watching USB port(s) " + ports + " every " + CABLE_CHECK_INTERVAL_MS / 1000 + "s");
        Boolean before = null;
        while (true) {
            int[] modes = new int[ports.size()];
            for (int i = 0; i < modes.length; i++) {
                modes[i] = usb.mode(ports.get(i));
            }
            int reading = Checks.portsReading(modes);
            if (reading >= 0) {
                boolean in = Checks.cableIn(reading);
                String change = Checks.cableChange(before, in);
                if (change != null) {
                    String config = Platform.property("sys.usb.config");
                    Ln.i("cable: " + change + " at " + new java.util.Date() + " (port mode " + reading
                            + ", USB debugging " + (Checks.usbAdb(config) ? "on the cable" : "not on it")
                            + ") - waking the app to watch the recorder");
                    Wakes.wake(Wakes.ACTION_CABLE, false, "", null, "--es", "cable", change);
                }
                before = in;
            }
            Shell.sleep(CABLE_CHECK_INTERVAL_MS);
        }
    }
}
