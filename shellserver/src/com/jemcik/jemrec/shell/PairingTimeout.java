package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Android's pairing timeout, turned off and put back for the app's Keep
 * JemRec paired (Wire.COMMAND_KEEP_PAIRED), and put back by the Custodian
 * when the app is uninstalled.
 *
 * Android forgets a debugging pairing some days after it was made: a key's
 * time is set when it pairs, and by connections signed in adb's older way
 * (USB, port 5555) - never by a Wireless debugging connection - and a key older than adb_allowed_connection_time
 * (7 days when nobody set it) is deleted at the next write of Android's key
 * list, which a computer's cable plugged in or out makes (AdbDebuggingManager,
 * android11-release and main, read 2026-11-15). The recorder runs on, but its
 * next start - after a restart, or an unplug on the Honor - is refused, and
 * JemRec must be paired again: on the Honor, 2026-11-15, every connection
 * after a 19:44 unplug was refused, and Android no longer listed JemRec's key
 * (when it went was not seen: the log had rolled over). 0 is what
 * Developer options' "Disable adb authorization timeout" writes, where a
 * phone shows it (not the Honor): no pairing is forgotten, a computer's
 * neither. On the Android 17 emulator the same day,
 * with the time cut to two minutes, JemRec's key went three seconds later -
 * one second after JemRec had connected - and at 0 it stayed.
 *
 * What it was before is kept in a file of this process's own (Paths), so it
 * can be put back when this process is the only one left to do it.
 */
final class PairingTimeout {

    private PairingTimeout() {
    }

    /** Off, what it was recorded first. True once it reads off. Each
     *  command has a thread of its own (LoopbackServer), and the app may send
     *  two at once - its screen's and a refresh's - so one at a time: a put
     *  back between this record and this write would take the record and
     *  leave the timeout off for good. */
    static synchronized boolean keep() {
        String current = Shield.readGlobal(Checks.PAIRING_TIMEOUT);
        if ("0".equals(current)) {
            Ln.i("pairing: the timeout is off already");
            return true;
        }
        String before = Checks.pairingTimeoutToRecord(current);
        if (before == null) {
            Ln.w("pairing: could not read the timeout (" + current + ")");
            return false;
        }
        // The record first: an off without it could never be put back.
        if (!record(before)) {
            return false;
        }
        // Read again where the write's read-back failed: a write that landed
        // keeps its record, or nothing could put the timeout back.
        boolean off = Shield.putGlobal(Checks.PAIRING_TIMEOUT, "0") || "0".equals(Shield.readGlobal(Checks.PAIRING_TIMEOUT));
        Ln.i(off ? "pairing: timeout turned off, was " + before : "pairing: could not turn the timeout off");
        if (!off) {
            forget();
        }
        return off;
    }

    /** What was recorded put back, while the timeout still reads off. True
     *  once nothing is left to put back. One at a time with keep(). */
    static synchronized boolean giveBack() {
        if (!Paths.PAIRING_TIMEOUT.exists()) {
            Ln.i("pairing: nothing to put back");
            return true;
        }
        String recorded = recorded();
        if (recorded == null) {
            // Kept for a later try: unread is not empty.
            return false;
        }
        String current = Shield.readGlobal(Checks.PAIRING_TIMEOUT);
        if (current == null) {
            // Kept for a later try: unread, it may be off still.
            Ln.w("pairing: could not read the timeout to put it back");
            return false;
        }
        String value = Checks.pairingTimeoutToGiveBack(recorded, current);
        boolean done = value == null
                || ("null".equals(value) ? Shield.deleteGlobal(Checks.PAIRING_TIMEOUT)
                        : Shield.putGlobal(Checks.PAIRING_TIMEOUT, value));
        if (done) {
            forget();
        }
        Ln.i(value == null ? "pairing: timeout left as it is (" + current + ", recorded " + recorded + ")"
                : done ? "pairing: timeout put back to " + value : "pairing: could not put the timeout back");
        return done;
    }

    private static boolean record(String value) {
        try {
            Files.write(Paths.PAIRING_TIMEOUT.toPath(), value.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            Ln.w("pairing: could not record the timeout: " + t.getMessage());
            return false;
        }
    }

    private static @Nullable String recorded() {
        try {
            return new String(Files.readAllBytes(Paths.PAIRING_TIMEOUT.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            Ln.w("pairing: could not read the record: " + t.getMessage());
            return null;
        }
    }

    private static void forget() {
        File file = Paths.PAIRING_TIMEOUT;
        if (file.exists() && !file.delete()) {
            Ln.w("pairing: could not remove the record");
        }
    }
}
