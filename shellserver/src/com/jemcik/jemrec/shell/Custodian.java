package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import java.io.File;

/**
 * The recorder letting itself out when the app that started it is
 * uninstalled: see watchForUninstall.
 */
final class Custodian {

    private Custodian() {
    }

    /** Five minutes. Frequent enough that an orphan is short-lived, rare
     *  enough that it costs nothing to run forever. */
    private static final long PACKAGE_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long CONFIRM_DELAY_MS = 10 * 1000L;

    /**
     * Exit when the app that started this daemon is uninstalled.
     *
     * WHY THE APP CANNOT DO THIS ITSELF
     *
     * Android tells a package nothing about its own removal. There is no
     * broadcast, no callback, no last chance to run: the process is killed and
     * the package is gone. So an app cannot clean up after being uninstalled,
     * and anything of its that is not owned by the package simply stays.
     *
     * This daemon is exactly that. It runs as shell, in its own session, from a
     * jar in /data/local/tmp - deliberately, so the system will not reap it
     * along with the app. Uninstall JemRec from the launcher or from Settings
     * and this process carries on holding port 28472 until the phone restarts.
     *
     * That is not just untidy, and this is the part that decides it. Setup asks
     * whether a recorder is already running before it asks about pairing,
     * because a phone that is recording is set up whatever else is true. So a
     * daemon left behind makes the NEXT install skip Wireless debugging and
     * pairing and call itself ready - while serving a jar from the version that
     * was uninstalled. Silently.
     *
     * The app has a Remove button that stops the daemon first, but a user is
     * perfectly entitled to long-press the icon and tap Uninstall instead, and
     * most will. Correctness cannot depend on them taking the tidy route. So
     * the daemon watches for its own app going away, and lets itself out.
     *
     * `pm path` rather than looking for files: it is unambiguous. It prints a
     * path for an installed package and nothing at all for one that is gone.
     */
    static void watchForUninstall() {
        while (true) {
            Shell.sleep(PACKAGE_CHECK_INTERVAL_MS);
            if (appIsInstalled()) {
                continue;
            }
            // Asked twice. A single failed check is far more likely to be a
            // busy package manager than an uninstall, and exiting by mistake
            // means a phone that silently stops recording calls.
            Shell.sleep(CONFIRM_DELAY_MS);
            if (appIsInstalled()) {
                continue;
            }
            AnswerMachine machine = Answering.answerMachine;
            if (machine != null && machine.takingMessage()) {
                // A message under way: its caller hung up on first, and the
                // microphone given back as the call ends - then the rest. A
                // call the user took over is theirs, left to go on.
                Ln.i("custodian: JemRec is no longer installed; ending the message first");
                try {
                    Answering.onMain(() -> {
                        machine.hangUpNow();
                        return null;
                    });
                } catch (Exception e) {
                    Ln.w("custodian: could not end the message: " + e);
                }
                continue;
            }
            if (!Answering.giveBackStaleMute()) {
                // A mute a predecessor left mid-message, and a call up: given
                // back once it is over. Cleaning up now would take its marker
                // with the rest, and nothing would look again.
                Ln.i("custodian: JemRec is no longer installed; cleaning up once the call is over");
                continue;
            }
            Ln.i("custodian: JemRec is no longer installed, cleaning up and exiting");
            cleanUpAfterApp();
            System.exit(0);
        }
    }

    private static boolean appIsInstalled() {
        try {
            Process process = new ProcessBuilder("pm", "path", Main.APP_PACKAGE)
                    .redirectErrorStream(true)
                    .start();
            String text = Shell.slurp(process);
            int exit = process.waitFor();
            if (text.contains("package:")) {
                return true;
            }
            if (exit != 0 && text.isEmpty()) {
                // The one shape "not installed" has, measured on the device:
                // no path, nothing else said, exit 1.
                return false;
            }
            // Anything else is pm complaining - a busy package manager, a
            // service not up yet - and used to read as an uninstall, which
            // turns both debugging switches off and exits. Not on a hiccup.
            Ln.w("custodian: pm path said '" + text + "' (exit " + exit + "), assuming the app is still there");
            return true;
        } catch (Throwable t) {
            // Cannot tell, so assume it is there. Guessing "uninstalled" would
            // stop a working recorder over a transient failure, and the cost of
            // guessing wrong the other way is one extra orphan until reboot.
            Ln.w("custodian: could not check whether the app is installed");
            return true;
        }
    }

    /**
     * Put back what the app changed that outlives it: both debugging
     * switches, a microphone left muted by a message, and its files in
     * /data/local/tmp - the jar, the logs, recordings never collected, the
     * greeting and its take.
     *
     * Wireless debugging was turned on for setup and is a secure setting only
     * something with shell rights can write - which this is, and the app no
     * longer exists to do it. The jar and the log sit in /data/local/tmp, which
     * belongs to shell rather than to the package, so an uninstall does not
     * touch them either.
     *
     * Deleting the jar this process is running FROM is safe: the file is
     * unlinked and the running image stays valid until exit, which is a moment
     * away.
     */
    private static void cleanUpAfterApp() {
        // The files first: turning the switches off can stop adbd, and this
        // process with it. The jar, the logs, and any call audio the app
        // never collected - nobody else ever will.
        java.util.List<File> leftovers = new java.util.ArrayList<>();
        leftovers.add(new File(Paths.JAR_PATH));
        leftovers.add(new File(Paths.LOG_PATH));
        leftovers.add(new File(Paths.LOG_PATH + ".1"));
        // The user's greeting, a take, and the marker of a mute left
        // mid-message - given back already (watchForUninstall).
        leftovers.add(Greeting.FILE);
        leftovers.add(Greeting.TAKE);
        leftovers.add(Paths.MUTE_MARKER);
        // The last call's report (Copy a report).
        leftovers.add(Paths.REPORT_FILE);
        File[] recordings = new File(Paths.REC_DIR).listFiles((d, n) -> n.startsWith(Paths.REC_PREFIX) && n.endsWith(Paths.REC_SUFFIX));
        if (recordings != null) {
            leftovers.addAll(java.util.Arrays.asList(recordings));
        }
        for (File file : leftovers) {
            try {
                if (file.delete()) {
                    Ln.i("custodian: removed " + file.getName());
                }
            } catch (Throwable t) {
                Ln.w("custodian: could not remove " + file.getName());
            }
        }
        // Both debugging switches. Wireless was turned on for setup; USB is the
        // off-Wi-Fi shield this daemon keeps up (see keepShieldUp) - and with the
        // app gone, nothing else will ever turn either of them back off.
        // Refused where an OPPO, OnePlus or realme phone's switch is off
        // (putGlobal), and then nothing is left to do it: the app is gone.
        for (String setting : new String[]{"adb_wifi_enabled", "adb_enabled"}) {
            if (Shield.putGlobal(setting, "0")) {
                Ln.i("custodian: turned " + setting + " off");
            }
        }
    }
}
