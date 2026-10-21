package com.jemcik.jemrec.shell;

/**
 * Small decisions of the daemon's, in plain Java so they can be tested
 * (shellserver/tests: ChecksTest) - each has been got wrong once, or is the
 * kind that would be.
 */
final class Checks {

    private Checks() {
    }

    /** The shell's uid: adb, and the tools run over it. */
    static final int SHELL_UID = 2000;

    /**
     * Whether `cmd appops get --uid` says the UID is allowed: its own line,
     * "Uid mode: MANAGE_ONGOING_CALLS: allow". The package's line follows it
     * and says "allow" too when only the package is allowed - which is not
     * enough - so "allow" anywhere in the output proves nothing. Lines may
     * come joined with " | " (Main.slurp) or with newlines.
     */
    static boolean uidAllowed(String said) {
        if (said == null) {
            return false;
        }
        for (String line : said.split("[|\n]")) {
            String l = line.trim();
            if (l.startsWith("Uid mode:") && l.endsWith(": allow")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a caller with [peer] uid may talk to the daemon, the app's uid
     * being [app]: JemRec itself, the shell, or root. Unknown on either side
     * (negative) is let through, to the handshake.
     */
    static boolean callerAllowed(int peer, int app) {
        return peer < 0 || peer == 0 || peer == SHELL_UID || app < 0 || peer == app;
    }
}
