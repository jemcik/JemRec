package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The last call's report, for Copy a report in the app's Diagnostics (see
 * CallReport): the looks into what plays during a call, and the report kept
 * in a file once its recording stops, so a recorder started since still has
 * it.
 */
final class Reports {

    private Reports() {
    }

    static volatile @Nullable CallReport lastReport;
    /** Its own thread for the looks: they ask Android for several lists,
     *  and the main looper has the calls to follow. Posted there, the first
     *  look of a call on the Android 17 emulator came five seconds late, once
     *  in four calls (2026-10-27). */
    private static final ScheduledExecutorService LOOKS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jemrec-report");
        t.setDaemon(true);
        return t;
    });

    /** A look into [report] a moment from now, on the looks' own thread.
     *  Nothing waits on it: AudioScene says in the report what went wrong. */
    @SuppressWarnings("FutureReturnValueIgnored")
    static void lookSoon(CallReport report) {
        LOOKS.schedule(() -> lookAround(report), PhoneCapture.LOOK_AFTER_MS, TimeUnit.MILLISECONDS);
    }

    /** What is playing and capturing, into [report], while its call is
     *  still the one recorded. */
    static void lookAround(CallReport report) {
        AudioManager audio = Platform.callAudio;
        RecordingSession rec = Calls.activeRecording;
        if (audio != null && rec != null && rec.report == report) {
            AudioScene.lookInto(report, audio);
        }
    }

    static void keepReport(CallReport report) {
        try (FileOutputStream file = new FileOutputStream(Paths.REPORT_FILE)) {
            // With the build that recorded it: read back by a recorder
            // updated since, the header's build is not this call's.
            file.write(("Recorded by build " + Main.BUILD + "\n" + report.text()).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Ln.w("report: could not keep the last call's report: " + t);
        }
    }

    /** The answer to COMMAND_REPORT: this recorder's build, then the call in
     *  memory, or the one kept. */
    static String reportText() {
        return "Recorder: build " + Main.BUILD + "\n" + lastCallText();
    }

    private static String lastCallText() {
        CallReport report = lastReport;
        if (report != null) {
            return report.text();
        }
        try {
            String kept = new String(java.nio.file.Files.readAllBytes(Paths.REPORT_FILE.toPath()), StandardCharsets.UTF_8);
            if (!kept.isEmpty()) {
                return kept;
            }
        } catch (Throwable ignored) {
            // None kept: said below.
        }
        return "Last call: none recorded yet.";
    }
}
