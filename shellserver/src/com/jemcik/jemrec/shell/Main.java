package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.os.Looper;
import android.system.Os;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;

/**
 * The JemRec shell-side capture daemon, and its start. The recorder's own code
 * is the package com.jemcik.jemrec.shell; everything under com.genymobile.scrcpy
 * is a trimmed fork of scrcpy v4.1 (Apache-2.0), and Main replaces its
 * Server.java (shellserver/PATCHES.md lists what was kept and what changed).
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
 * Each part has a class of its own: the port and the handshake
 * (LoopbackServer, Handshake), the commands and their bytes (Commands, Wire),
 * the call and its recording (CallWatch, Calls, CallDecisions,
 * RecordingSession, Wakes), the answering machine (Answering, AnswerEffects,
 * AnswerTest), the background jobs (Custodian, Shield, CableWatch, KillWatch,
 * Tracking, DebuggingOff), and what is reached by reflection (Platform).
 *
 * THE AUDIO'S FORMAT, a live capture's stream and a recording's file alike
 * (scrcpy's, unchanged, so its tooling still reads it):
 *
 *   4 bytes    codec id, "opus"
 *   then, repeating:
 *     8 bytes  pts, with flag bits in the high bits
 *     4 bytes  packet length
 *     n bytes  packet
 */
public final class Main {

    static final int DEFAULT_PORT = 28472;

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

            // One optional positional argument, and deliberately not a parser:
            // scrcpy's Options is 707 lines of command-line handling that also
            // reaches into the video, camera and display types, and dropping it
            // is what makes this fork small enough to review.
            int port = DEFAULT_PORT;
            if (args.length > 0) {
                port = Integer.parseInt(args[0]);
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
            Calls.recordingMode = modeFromEnvironment(System.getenv(MODE_ENV));
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
            // on it. See Shield.keepShieldUp for why the app doing it was not enough.
            Thread shield = new Thread(Shield::keepShieldUp, "jemrec-shield");
            shield.setDaemon(true);
            shield.start();

            // The few seconds' warning a USB cable gives before adbd goes,
            // passed on to the app. See CableWatch.watchCable.
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
            // KillWatch.watchBackgroundKill.
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

    /** When this process started: the self-test's "since" (Wire.COMMAND_WATCH).
     *  Here, so it is set as Main is loaded, as the process starts. */
    static final long STARTED_AT = System.currentTimeMillis();

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

    /** For waking the app, and for checking who connects. Set in main()
     *  before anything can connect; empty if this daemon was started by hand. */
    static volatile String appToken = "";

    static final String VOIP_ENV = "JEMREC_VOIP";

    private static final String TRACK_ENV = "JEMREC_TRACK";

    private static final String ANSWER_ENV = "JEMREC_ANSWER";

    /** The mode the app put in the environment at spawn; OFF if there is
     *  none, or it is not a mode. See CallDecisions' modes for why OFF. */
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
