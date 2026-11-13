package com.jemcik.jemrec.shell;

import java.io.File;

/**
 * Where the recorder keeps its files, all in /data/local/tmp: shell's, not
 * the app's, so an uninstall leaves them (Custodian removes them), and each
 * name is one the app and the tools look for too.
 */
final class Paths {

    private Paths() {
    }

    static final String JAR_PATH = "/data/local/tmp/jemrec-capture.jar";
    static final String LOG_PATH = "/data/local/tmp/jemrec-capture.out";

    static final String REC_DIR = "/data/local/tmp";
    static final String REC_PREFIX = "jemrec_rec_";
    static final String REC_SUFFIX = ".dat";

    /** Left while this process holds the microphone muted, so a restart in
     *  the middle of a message gives the microphone back. */
    static final File MUTE_MARKER = new File("/data/local/tmp/jemrec-muted");

    static final File REPORT_FILE = new File(REC_DIR, "jemrec-lastcall.txt");

    /** A rec-file name the app sent is only ever one of ours in REC_DIR. */
    static @Nullable File recFileFor(String name) {
        if (name == null || !name.startsWith(REC_PREFIX) || !name.endsWith(REC_SUFFIX)
                || name.contains("/") || name.contains("..")) {
            return null;
        }
        return new File(REC_DIR, name);
    }
}
