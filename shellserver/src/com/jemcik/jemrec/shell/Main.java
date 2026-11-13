package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.os.Looper;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The JemRec shell-side capture daemon. This is the one file written rather
 * than inherited; everything else under com.genymobile.scrcpy is a trimmed fork
 * of scrcpy v4.1 (Apache-2.0). It replaces scrcpy's Server.java.
 *
 * It runs as the shell UID, started once over ADB via app_process, and that is
 * the entire reason this program exists as a separate process rather than a
 * class inside the app. Capturing call audio needs
 * CAPTURE_VOICE_COMMUNICATION_OUTPUT, which com.android.shell holds and an
 * ordinary app cannot obtain; and AudioDirectCapture's foreground workaround
 * calls startActivity() and forceStopPackage() on com.android.shell itself,
 * which only a process already running as that UID may do.
 *
 * WHY IT IS A LISTENING DAEMON RATHER THAN A ONE-SHOT
 *
 * scrcpy's Server is started per session by an ADB client that has already
 * arranged a socket, and it exits when that session ends. JemRec cannot work
 * that way, and the reason was measured rather than assumed: with Wi-Fi off,
 * adbd tears its listener down completely, and the shell UID is
 * not permitted to set service.adb.tcp.port to pin a stable one. So there is no
 * ADB to start anything at the moment a call arrives.
 *
 * Instead this process is started ONCE, while Wi-Fi happens to be up, and then
 * stays. A process spawned from an adb shell outlives the adb connection, so it
 * is still here long after adbd has stopped listening. The app then reaches it
 * over TCP on loopback, which needs no network at all.
 *
 * TCP specifically, and not an abstract unix socket: SELinux refuses an
 * untrusted_app connecting to the shell domain's abstract socket
 * ("IOException: Permission denied"), and a filesystem socket would have to
 * live under /data/local/tmp, which the app cannot traverse. Loopback TCP is
 * the one channel that is actually permitted between these two domains.
 *
 * WIRE FORMAT (unchanged from scrcpy, so its tooling still reads it)
 *
 *   4 bytes    codec id, "opus"
 *   then, repeating:
 *     8 bytes  pts, with flag bits in the high bits
 *     4 bytes  packet length
 *     n bytes  packet
 */
public final class Main {

    static final int DEFAULT_PORT = 28472;

    /**
     * Send uncompressed PCM instead of Opus. Diagnostics only, selected by
     * passing "raw" as the second argument.
     *
     * It exists because the question worth answering is not "did
     * audio arrive" but "are BOTH directions of the call present", and that is
     * a claim about the two channels of a stereo stream. Measuring per-channel
     * energy on 16-bit PCM answers it arithmetically; doing the same through an
     * Opus decoder means trusting the decoder as well as the capture. Opus
     * remains the default and is what recordings are actually made in.
     */
    static boolean rawMode;

    private Main() {
        // not instantiable
    }

    /**
     * Like Looper.prepareMainLooper(), but leaving quitAllowed true.
     *
     * Taken from scrcpy's Server.prepareMainLooper() (Apache-2.0), because
     * dropping Server.java dropped this with it, and the omission is not subtle
     * when you hit it. Workarounds' STATIC INITIALISER constructs an
     * android.app.ActivityThread, whose constructor builds a Handler, which
     * needs a prepared Looper on the calling thread. Without it the process
     * dies before printing a word of its own:
     *
     *   java.lang.RuntimeException: Can't create handler inside thread
     *     Thread[main,5,main] that has not called Looper.prepare()
     *       at android.app.ActivityThread.&lt;init&gt;
     *       at com.genymobile.scrcpy.Workarounds.&lt;clinit&gt;
     *
     * and the shell reports only "Killed", because the crash handler ITSELF
     * then fails trying to reach the activity manager to report the crash.
     */
    // Looper's own lock: Android's prepareMainLooper sets sMainLooper holding
    // Looper.class, and this does the same job by reflection.
    @SuppressLint("DiscouragedPrivateApi")
    @SuppressWarnings("LockOnNonEnclosingClassLiteral")
    private static void prepareMainLooper() {
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new LinkageError(e.getMessage(), e);
            }
        }
    }

    public static void main(String... args) {
        int status = 0;
        try {
            // Order matters: the Looper must exist before ANY mention of
            // Workarounds, including the one that triggers its static block.
            prepareMainLooper();
            Ln.initLogLevel(Ln.Level.INFO);

            // AN EXCEPTION ESCAPING A THREAD MUST NOT TAKE THE RECORDER DOWN.
            // Android's default handler kills the process, and with no ADB
            // session around nothing starts it again until the next Wi-Fi: a
            // HAL that refuses VOICE_CALL for one call, throwing from the
            // encoder's thread, cost every call after it. A worker's failure
            // is logged and that worker ends. The main thread is the call
            // watch's looper: deaf is worse than down, so that one still ends
            // the process - and the app sees it down and says so.
            final Thread mainThread = Thread.currentThread();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                Ln.e("uncaught on " + thread.getName(), error);
                if (thread == mainThread) {
                    System.exit(1);
                }
            });

            // Two optional positional arguments, and deliberately not a parser:
            // scrcpy's Options is 707 lines of command-line handling that also
            // reaches into the video, camera and display types, and dropping it
            // is what makes this fork small enough to review.
            int port = DEFAULT_PORT;
            if (args.length > 0) {
                port = Integer.parseInt(args[0]);
            }
            if (args.length > 1) {
                rawMode = "raw".equalsIgnoreCase(args[1]);
            }

            // Installs the fake ActivityThread and app context that let a
            // process which is not an app construct an AudioRecord at all.
            Workarounds.apply();

            // Whatever this process creates - the recordings above all - is
            // its own. It inherits a 000 umask from the shell adbd ran it
            // from, which made every recording file world-readable (the log
            // that shell's redirect creates is -rw-rw-rw-, measured). SELinux
            // keeps other apps out of shell_data_file regardless; a recorded
            // phone call should not be one policy layer away from readable.
            Os.umask(0077);

            // Known before anything can connect, so the first connection is
            // checked against the same token as the last.
            String token = System.getenv(TOKEN_ENV);
            appToken = token == null ? "" : token;
            recordingMode = modeFromEnvironment(System.getenv(MODE_ENV));
            Tracking.trackingOn = "1".equals(System.getenv(TRACK_ENV));
            Answering.answerSettings = AnswerSettings.parse(System.getenv(ANSWER_ENV));

            final int bound = port;
            LoopbackServer.listenPort = bound;
            Thread acceptor = new Thread(() -> LoopbackServer.acceptLoop(bound), "jemrec-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            Thread custodian = new Thread(Custodian::watchForUninstall, "jemrec-custodian");
            custodian.setDaemon(true);
            custodian.start();

            // The off-Wi-Fi shield, kept up by the process whose life depends
            // on it. See keepShieldUp for why the app doing it was not enough.
            Thread shield = new Thread(Shield::keepShieldUp, "jemrec-shield");
            shield.setDaemon(true);
            shield.start();

            // The few seconds' warning a USB cable gives before adbd goes,
            // passed on to the app. See watchCable.
            Thread cable = new Thread(CableWatch::watchCable, "jemrec-cable");
            cable.setDaemon(true);
            cable.start();

            CallWatch.startCallWatcher(token);
            CallWatch.startVoipWatcher(token);
            // Off the main thread: it runs `cmd appops`, which takes a moment.
            Thread tracking = new Thread(Tracking::applyTracking, "jemrec-tracking");
            tracking.setDaemon(true);
            tracking.start();

            // The daemon survives the app being killed in the background, so it
            // is where the app's own death can be seen and the user told. See
            // watchBackgroundKill.
            Thread killWatch = new Thread(KillWatch::watchBackgroundKill, "jemrec-killwatch");
            killWatch.setDaemon(true);
            killWatch.start();

            // The main thread loops instead of blocking in accept(), so the
            // fake ActivityThread's handler has a thread to run on. The
            // acceptor quits this loop if it cannot carry on.
            Looper.loop();
        } catch (Throwable t) {
            Ln.e("jemrec-capture died", t);
            status = 1;
        } finally {
            // The Android SDK starts non-daemon threads of its own, which would
            // otherwise keep this process alive after the loop ends.
            System.exit(status);
        }
    }

    /** When this process started: the self-test's "since" (COMMAND_WATCH).
     *  Here, so it is set as Main is loaded, as the process starts. */
    static final long STARTED_AT = System.currentTimeMillis();

    /**
     * The one lock of the call and recording state: Main.class, the monitor
     * Main's own static synchronized methods hold. Named so the code moved
     * out of Main takes the same one.
     */
    static final Object LOCK = Main.class;

    /**
     * Mid-call - recording, ringing with an answer on its way, or waiting on
     * an ask-first answer - or a file being fetched: what a QUIT, or
     * debugging off, waits out. A fetch cut short by the exit reads as a
     * complete, shorter recording. Call with Main.class held.
     */
    static boolean busyLocked() {
        AnswerMachine machine = Answering.answerMachine;
        return CallDecisions.busy(activeRecording != null, callActive, fetches.get(),
                machine == null ? null : machine.state());
    }

    static final String APP_PACKAGE = "com.jemcik.jemrec";
    private static final String TOKEN_ENV = "JEMREC_TOKEN";

    /**
     * The first eight hex digits of the SHA-256 of the jar this process runs
     * from, answered with every ping. The protocol version says whether the
     * app and the daemon can talk; this says whether the daemon is the code
     * the app shipped with. The app compares it with the jar it bundles and
     * replaces a daemon that is behind at its next chance - without it a
     * daemon started from an older APK kept running its older code until the
     * phone rebooted, however many updates the app had had since. "unknown"
     * if the jar cannot be read, which the app treats as current rather than
     * churning.
     */
    static final String BUILD = jarBuild();

    private static final String MODE_ENV = "JEMREC_MODE";
    /** The mode in force: one of CallDecisions.MODE_*, OFF until told
     *  otherwise (see there). */
    static volatile int recordingMode = CallDecisions.MODE_OFF;

    /** The call being recorded right now, or null between calls. */
    static volatile @Nullable RecordingSession activeRecording;

    /** Whether a call is up, and its direction - so an on-demand yes that
     *  arrives mid-call knows what it is starting. */
    static volatile boolean callActive;
    /** Calls begun, and the latest of them that has ended: a CALL_STARTED
     *  still being retried for a call already over is dropped (see wake). */
    static volatile long callSeq;
    static volatile long endedSeq;
    static volatile boolean callIncoming;

    /** For waking the app, and for checking who connects. Set in main()
     *  before anything can connect; empty if this daemon was started by hand. */
    static volatile String appToken = "";

    static final String VOIP_ENV = "JEMREC_VOIP";

    /** The messenger app of the call in progress; null for a phone call or none. */
    static volatile @Nullable String callVoipApp;
    /** Which way that app's call went, where Telecom said (Google Meet's);
     *  null where it did not, or for a phone call. */
    static volatile @Nullable Boolean callAppIncoming;

    private static final String TRACK_ENV = "JEMREC_TRACK";

    static volatile long skipUntil;

    private static final String ANSWER_ENV = "JEMREC_ANSWER";

    /** When the recording in progress captured its first sample, on the
     *  answering machine's clock (SystemClock.elapsedRealtime): the
     *  message's start is counted from here, as the app counts packets. */
    static volatile long captureStartedAt;

    /** Start capturing the current call to a file. Returns its name, or null if
     *  it could not start or one is already running. Does NOT wake the app. */
    static synchronized @Nullable String beginRecording(boolean incoming, @Nullable String app,
            @Nullable Boolean appIncoming) {
        if (activeRecording != null) {
            Ln.w("recording: a call is already being recorded");
            return activeRecording.name;
        }
        try {
            captureStartedAt = 0;
            activeRecording = RecordingSession.start(incoming, app, appIncoming);
        } catch (Throwable t) {
            Ln.e("recording: could not start", t);
            activeRecording = null;
            return null;
        }
        return activeRecording.name;
    }

    /** The mode the app put in the environment at spawn; OFF if there is
     *  none, or it is not a mode. See recordingMode for why OFF. */
    private static int modeFromEnvironment(String value) {
        if (value != null) {
            try {
                int mode = Integer.parseInt(value.trim());
                if (CallDecisions.isMode(mode)) {
                    Ln.i("recording: mode " + mode + " from the environment");
                    return mode;
                }
            } catch (NumberFormatException ignored) {
                // Falls through: not a mode.
            }
        }
        Ln.w("recording: no usable mode in the environment ('" + value
                + "'), capturing nothing until the app says otherwise");
        return CallDecisions.MODE_OFF;
    }

    /** Off-hook handling, by mode: automatic records at once; on-demand asks
     *  first and records nothing until the yes comes back as COMMAND_START. */
    static void onCallStart(boolean incoming) {
        onCallStart(incoming, null, null);
    }

    /**
     * A call starting - a phone call ([app] null), or a messenger call in
     * [app]. One at a time: a phone call during a messenger call, or the other
     * way round, leaves the first one's recording running and is not recorded
     * itself, and neither one's end can stop the other's recording.
     * [appIncoming]: which way an app's call went, where Telecom said.
     */
    static void onCallStart(boolean incoming, @Nullable String app, @Nullable Boolean appIncoming) {
        synchronized (Main.class) {
            AnswerMachine machine = Answering.answerMachine;
            CallDecisions.Start start = CallDecisions.start(callActive, app != null, callVoipApp != null,
                    machine != null && machine.takingMessage(),
                    CallDecisions.skipOpen(skipUntil, System.currentTimeMillis()));
            if (start == CallDecisions.Start.IGNORED) {
                Ln.w("recording: a " + (app != null ? "messenger" : "phone") + " call started during "
                        + (callVoipApp != null ? "a messenger call" : "a phone call") + "; not recording it");
                return;
            }
            if (!callActive) {
                callSeq++;
            }
            callActive = true;
            callIncoming = incoming;
            callVoipApp = app;
            callAppIncoming = app != null ? appIncoming : null;
            if (start == CallDecisions.Start.MESSAGE) {
                // A call this process answered: the recording IS the message,
                // whatever the mode - switching the answering machine on is
                // the consent to record one. No start-of-call wake: the
                // message's own (MESSAGE_STARTED) follows with the greeting.
                // Not once the user took the call: an off-hook then - a
                // waiting call - is theirs, asked about as in any call.
                beginRecording(incoming, null, null);
                return;
            }
            if (start == CallDecisions.Start.SKIPPED) {
                skipUntil = 0;
                Ln.i("recording: the rules leave this call out; not recording it");
                return;
            }
        }
        RecordingSession running = activeRecording;
        switch (CallDecisions.byMode(recordingMode, running != null)) {
            case RECORD:
                String name = beginRecording(incoming, app, appIncoming);
                if (name != null) {
                    // The app shows a "recording" notification; no audio needed.
                    Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, name, app);
                }
                break;
            case RECORDING_ALREADY:
                // Already said yes to, and recording: a second off-hook
                // in the same call (call waiting) is not a new question -
                // asked again, "Not now" would stop nothing. (It runs: the
                // decision is this only while one does.)
                if (running != null) {
                    Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, running.name, app);
                }
                break;
            case ASK:
                // Nothing is captured yet. Ask; an empty rec-file says "prompt".
                Ln.i("recording: on-demand, asking before recording");
                Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, "", app);
                break;
            default:
                Ln.i("recording: switched off, not recording this call");
                break;
        }
    }

    /**
     * Ask first, and the user took over a call the answering machine answered:
     * the recording so far is the message - kept as one, or dropped with no
     * word in it - and nothing more is recorded unless the user says so at the
     * Record prompt this puts up. A recording started there is the user's
     * own: at the call's end it is named as taken, from before its first
     * sample (Checks.takenRecfile: "_from0"), and kept whole.
     */
    static void splitOffMessage(AnswerMachine.Outcome part) {
        synchronized (Main.class) {
            RecordingSession rec = activeRecording;
            CallDecisions.Split split = CallDecisions.split(callActive, rec != null, part.speechAt != 0);
            if (split == CallDecisions.Split.NOTHING) {
                // The call ended as it was taken: its end has named it.
                Ln.i("answer: the call was taken as it ended; nothing to split");
                return;
            }
            if (rec == null) {
                // NO_RECORDING
                Ln.w("answer: the call was taken; there was no recording of it to split");
            } else {
                rec.stop();
                File file = new File(Paths.REC_DIR, rec.name);
                if (split == CallDecisions.Split.DROP) {
                    Ln.i("answer: the call was taken before a word was left; " + rec.name + " dropped -> "
                            + file.delete());
                } else {
                    String renamed = Checks.messageRecfile(rec.name, part.messageStart(), captureStartedAt, false,
                            false);
                    if (file.renameTo(new File(Paths.REC_DIR, renamed))) {
                        Ln.i("answer: the call was taken; the message so far -> " + renamed);
                    } else {
                        Ln.w("answer: the call was taken; could not name " + rec.name + " as a message");
                    }
                }
                activeRecording = null;
            }
        }
        // Even with nothing recorded - a recording that could not start: the
        // prompt is what tells the app the message is over.
        Wakes.wake(Wakes.ACTION_CALL_STARTED, callIncoming, "", null);
    }

    private static synchronized void stopRecording(@Nullable AnswerMachine.Outcome outcome) {
        RecordingSession rec = activeRecording;
        if (rec == null) {
            return;
        }
        rec.stop();
        String name = rec.name;
        CallDecisions.Ending ending = CallDecisions.ending(outcome != null, outcome != null && outcome.test,
                outcome != null && outcome.isMessage());
        if (ending == CallDecisions.Ending.TEST) {
            // A diagnostics test: what it showed is kept for 'X' 'R'; the
            // recording is not the user's, and goes. The app is told when the
            // call began, to account for it in the call log.
            boolean gone = new File(Paths.REC_DIR, name).delete();
            Ln.i("answer: test call over, its recording deleted -> " + gone);
            activeRecording = null;
            Wakes.wake(Wakes.ACTION_CALL_ENDED, rec.incoming, "", null,
                    "--ez", "test", "true", "--el", "started", String.valueOf(rec.startedAt));
            return;
        }
        if (outcome != null) {
            // A message: named with where it starts, so whichever collector
            // takes it - the hang-up's, or a reconcile days later - trims it
            // the same way and knows it for a message. A call the user took
            // over: theirs, an ordinary recording, from when they took it -
            // or the caller's first words, if earlier; in Ask first, all of
            // what they said Record to (see splitOffMessage).
            boolean taken = ending == CallDecisions.Ending.TAKEN;
            String renamed = taken
                    ? Checks.takenRecfile(name, outcome.takenStart(), captureStartedAt)
                    : Checks.messageRecfile(name, outcome.messageStart(), captureStartedAt, outcome.nothingLeft(),
                            outcome.greetingFailed);
            if (new File(Paths.REC_DIR, name).renameTo(new File(Paths.REC_DIR, renamed))) {
                name = renamed;
            } else {
                Ln.w("answer: could not name " + name + (taken ? " with where the call was taken" : " as a message"));
            }
            Ln.i(taken ? "answer: the user took the call - " + name + " is an ordinary recording"
                    : "answer: message recorded -> " + name);
        }
        // ONLY NOW. LIST hides the active recording so the app never collects
        // a file that is still being written - and until stop() has returned,
        // it still is. Clearing this first, as it used to, opened a window in
        // which a list from the app's reconcile would have offered it a
        // truncated file, and a truncated file reads as a complete one.
        activeRecording = null;
        // Now the file is complete and at rest, tell the app to come and get it.
        Wakes.wake(Wakes.ACTION_CALL_ENDED, rec.incoming, name, rec.app);
    }

    /** Call end handling. A recording that ran is finished and handed over; an
     *  on-demand call that was never said yes to just tells the app to clear its
     *  prompt, with nothing to fetch. */
    static void onCallEnd() {
        if (callVoipApp != null) {
            Ln.i("callwatch: a phone call ended during a messenger call; that recording carries on");
            return;
        }
        endCall();
        // The other way round from onVoipCallEnd: a messenger or Meet call
        // that began during the phone call - taken as the phone call was hung
        // up - was turned away while that one ran; it is the call now.
        String app = Voip.followedApp();
        if (app != null) {
            Ln.i("recording: the phone call gave way to a call in " + app + "; recording that");
            onAppCallStart(app);
        }
    }

    /** A messenger or Meet call starting, and which way it went: known for an
     *  app whose calls go through Telecom - Meet's ring reaches the call
     *  watch - and for no other. */
    static void onAppCallStart(String app) {
        CallWatch watch = CallWatch.callWatch;
        Boolean incoming = watch != null ? watch.appCallIncoming() : null;
        onCallStart(Boolean.TRUE.equals(incoming), app, incoming);
    }

    /** A messenger call's end. Only ends a messenger call. */
    static void onVoipCallEnd() {
        if (callVoipApp == null) {
            return;
        }
        endCall();
        // A phone call answered during the messenger call - which is what
        // ended it - was turned away while that one ran; it is the call now.
        CallWatch watch = CallWatch.callWatch;
        if (watch != null && watch.phoneCallUp()) {
            Ln.i("recording: the messenger call gave way to a phone call; recording that");
            onCallStart(watch.phoneCallRang(), null, null);
        }
    }

    private static void endCall() {
        boolean wasRecording;
        String app;
        // The answering machine first: it gives the microphone back, and says
        // what became of the message. Only a phone call is ever its business.
        AnswerMachine machine = Answering.answerMachine;
        AnswerMachine.Outcome outcome = callVoipApp == null && machine != null ? machine.callEnded() : null;
        if (outcome == null) {
            // Not a call this process answered: a mute marker now is one a
            // predecessor left, kept while this call was up.
            Answering.giveBackStaleMute();
        }
        // Under the same lock as an on-demand start, so a Record tap landing as
        // the call ends either starts before the end - and is stopped here - or
        // finds the call over and is refused. Between the two, a recording
        // would start with nothing left to ever stop it: this end has already
        // happened, and the next one is a whole call away.
        synchronized (Main.class) {
            callActive = false;
            endedSeq = callSeq;
            app = callVoipApp;
            callVoipApp = null;
            callAppIncoming = null;
            wasRecording = activeRecording != null;
            if (wasRecording) {
                stopRecording(outcome);
            }
        }
        if (outcome != null && outcome.test) {
            // Recorded or not: its results are complete.
            AnswerTest.testState = "done";
        }
        if (CallDecisions.endWakes(wasRecording, recordingMode)) {
            Wakes.wake(Wakes.ACTION_CALL_ENDED, callIncoming, "", app);
        }
    }

    /** Fetches under way, which QUIT waits out. */
    static final AtomicInteger fetches = new AtomicInteger();

    /** The name of the file being recorded into, or null. */
    static @Nullable String activeName() {
        RecordingSession active = activeRecording;
        return active == null ? null : active.name;
    }

    private static String jarBuild() {
        // The jar this process was started from - CLASSPATH is how app_process
        // was pointed at it - falling back to where the app stages it.
        String jar = System.getenv("CLASSPATH");
        if (jar == null || jar.isEmpty()) {
            jar = Paths.JAR_PATH;
        }
        try (FileInputStream in = new FileInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            byte[] sum = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", sum[i]));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

}
