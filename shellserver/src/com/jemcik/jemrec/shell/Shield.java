package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

/**
 * The off-Wi-Fi shield - USB debugging, kept on by this process - and the
 * heartbeat line in its log: see keepShieldUp. And the two global settings
 * this process writes, through Android's settings tool.
 */
final class Shield {

    private Shield() {
    }

    /**
     * Ten seconds. This bounds how long the shield can be down while adbd is
     * still alive - the case that matters, see keepShieldUp. Cheap: each check
     * is one tiny native `cmd` binary and a binder call, no wakelock, and a
     * plain sleep does not wake a sleeping phone, it just runs late. About a
     * minute of low-clock CPU a day, spread thin.
     */
    private static final long SHIELD_CHECK_INTERVAL_MS = 10 * 1000L;

    /** Every twelfth check - once every two minutes - a heartbeat line with
     *  both switches and the time. This log is the only durable record on this
     *  phone (logcat's main buffer rolls in about a minute), and the state of
     *  the two switches right up to whatever kills this process is the one
     *  thing every investigation so far had been missing. ~720 lines a day. */
    private static final int HEARTBEAT_EVERY = 12;

    /**
     * KEEP THE OFF-WI-FI SHIELD UP, FROM THE ONE PROCESS THAT NEEDS IT.
     *
     * This daemon dies when adbd restarts (the restart cgroup-kills adbd's
     * children), and adbd restarts on Wi-Fi loss unless USB debugging - the
     * global setting adb_enabled, no cable involved - is on to keep a transport
     * "enabled" in the framework's eyes. The app turns it on at setup and
     * re-arms it whenever it revives this daemon, and that was believed to be
     * enough. It is not: on this phone USB debugging switches ITSELF off some
     * time after being turned on with no cable attached (measured repeatedly),
     * and the app only looks again on a keep-alive tick or an open. A Wi-Fi
     * drop landing in that gap restarts adbd and kills this process. Measured:
     * a call recorded at 14:19, Wi-Fi off, adbd restarted at 14:21:07, every
     * call after that unrecorded - and adb_enabled read 1 again by the time
     * anyone looked, because the app had re-armed it afterwards.
     *
     * So this process minds its own shield. Shell can write the setting (the
     * custodian above writes adb_wifi_enabled the same way), this thread is
     * never frozen, and it checks every ten seconds - so the shield is down
     * for seconds at most while adbd is alive. It cannot help once adbd has
     * already restarted (this process is gone by then; the app's revive covers
     * that); what it does is stop a drop from ever finding the shield down.
     *
     * WHY THIS POLLS, WHEN A ContentObserver IS THE OBVIOUS ANSWER
     *
     * An observer on the two settings was built and does not work, and the
     * platform says exactly why. Since Android 12 the ContentService validates
     * every registration through ActivityManager's checkContentProviderAccess,
     * which looks the caller up by PID in its process table - and a process
     * started from a shell with app_process was never started by the
     * ActivityManager, so it has no record there. registerContentObserver
     * returns normally and registers nothing: `dumpsys content` showed the
     * observer tree without it, and logcat had the refusal, verbatim:
     *
     *   W/ContentService: Ignoring content changes for
     *     content://settings/global/adb_enabled from 2000: Failed to find PID 28158
     *
     * No permission, property or context changes that; it is a question of
     * whether the ActivityManager knows the process, and it does not. The app
     * could observe - it is a real app - but the app is frozen between calls
     * on this phone, which is the whole reason this daemon exists. So the
     * process that is always alive polls, and the one that could observe is
     * not always alive. Ten seconds it is.
     *
     * A read that fails is treated as unknown and skipped, not as "off": writing
     * on a transient hiccup would be harmless but noisy, and a wrong "off" costs
     * nothing more than one idempotent write ten seconds later.
     */
    static void keepShieldUp() {
        Ln.i("shield: minding adb_enabled every " + (SHIELD_CHECK_INTERVAL_MS / 1000)
                + "s, from " + new java.util.Date());
        int tick = 0;
        // Check FIRST, then sleep: the state at spawn is itself a data point.
        while (true) {
            boolean heartbeat = tick++ % HEARTBEAT_EVERY == 0;
            checkShield(heartbeat ? "heartbeat" : null);
            if (heartbeat) {
                Tracking.ensureTracking();
            }
            Shell.sleep(SHIELD_CHECK_INTERVAL_MS);
        }
    }

    /** One check: put adb_enabled back to 1 if it is anything else, logging
     *  the restore with its time. With a non-null why, also a heartbeat line
     *  with both switches; otherwise a quiet check stays quiet. */
    private static void checkShield(@Nullable String why) {
        try {
            String usb = readGlobal("adb_enabled");
            if (why != null) {
                Ln.i("shield: " + why + " adb_enabled=" + usb + " adb_wifi_enabled="
                        + readGlobal("adb_wifi_enabled") + " pings=" + Main.pings.getAndSet(0)
                        + " sessions=" + Main.sessions.get() + " at " + new java.util.Date());
            }
            // Not while debugging is going off by the user's choice
            // (COMMAND_DEBUGGING_OFF): putting it back would undo that.
            if (usb != null && !"1".equals(usb) && !DebuggingOff.switchingOff) {
                boolean on = putGlobal("adb_enabled", "1");
                // Stamped: WHEN the setting flips is the one thing nobody has
                // been able to see. Each of these is a data point about what
                // Honor does and when. Refused, the app's own write - it holds
                // the permission - is what puts it back (UsbDebuggingWatch).
                Ln.w("shield: adb_enabled was " + usb + " at " + new java.util.Date()
                        + (on ? " - turned USB debugging back on" : " - could not turn USB debugging back on"));
            }
        } catch (Throwable t) {
            Ln.w("shield: could not check or restore adb_enabled: " + t.getMessage());
        }
    }

    /** By its full path, not looked up on PATH (CodeQL, code-scanning alert
     *  69), as the app's getprop is (SystemProperty): Android's own. */
    private static final String SETTINGS = "/system/bin/settings";

    /**
     * `settings put global name value`, read back: true if it now says so. An
     * OPPO, OnePlus or realme phone with its Developer options switch off
     * refuses the shell this write (the app's ShellGrant; CallVault measured
     * `settings put` refused on a OnePlus 9, 2026-09-16), and the write's
     * exit says nothing - so what the phone said is logged, its first lines.
     * Writing USB debugging off ends this process with adbd: no read-back.
     */
    static boolean putGlobal(String name, String value) {
        try {
            Process put = new ProcessBuilder(SETTINGS, "put", "global", name, value)
                    .redirectErrorStream(true)
                    .start();
            String said = Shell.slurp(put);
            put.waitFor();
            if (value.equals(readGlobal(name))) {
                return true;
            }
            Ln.w("settings: could not set " + name + " to " + value
                    + (said.isEmpty() ? "" : " - Android said " + said.substring(0, Math.min(said.length(), 300))));
        } catch (Throwable t) {
            Ln.w("settings: could not set " + name + " to " + value + ": " + t.getMessage());
        }
        return false;
    }

    /** `settings get global name`, trimmed; null if it could not be read. */
    private static @Nullable String readGlobal(String name) {
        try {
            Process process = new ProcessBuilder(SETTINGS, "get", "global", name)
                    .redirectErrorStream(true)
                    .start();
            String value = Shell.slurp(process);
            process.waitFor();
            return value;
        } catch (Throwable t) {
            return null;
        }
    }
}
