package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioEncoder;
import com.genymobile.scrcpy.audio.AudioRawRecorder;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    private static final int DEFAULT_PORT = 28472;

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
    private static boolean rawMode;

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
            listenPort = bound;
            Thread acceptor = new Thread(() -> acceptLoop(bound), "jemrec-acceptor");
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
    private static final long STARTED_AT = System.currentTimeMillis();

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
        return activeRecording != null || callActive || fetches.get() > 0
                || (machine != null && machine.state() != AnswerMachine.State.IDLE
                    && machine.state() != AnswerMachine.State.RINGING);
    }

    /**
     * Take the port, allowing for a predecessor that has not finished dying.
     *
     * One attempt was enough to lose the recorder outright. The app pkills the
     * old daemon and starts this one; if the kernel has not reaped the old
     * process yet the bind fails with EADDRINUSE, the accept loop stops and
     * this process exits - so the kill worked and the replacement never
     * arrived, leaving a phone that silently records nothing. Retrying costs a
     * few seconds in the one case that used to be fatal.
     */
    private static ServerSocket bind(int port) throws Exception {
        BindException last = null;
        for (int attempt = 0; attempt < BIND_ATTEMPTS; attempt++) {
            try {
                // Backlog 8, not 1. The accept loop is single-threaded, so while
                // one session runs every other connect waits in this queue - and
                // with a backlog of one, a second caller (the keep-alive and a
                // resume arriving together) was REFUSED, which the app read as
                // "dead" and "port free" and answered by spawning a duplicate
                // that then died on EADDRINUSE. Eight covers every caller the
                // app has, several times over, and costs nothing.
                return new ServerSocket(port, 8, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            } catch (BindException e) {
                last = e;
                Ln.w("port " + port + " still held, retrying");
                Shell.sleep(BIND_RETRY_MS);
            }
        }
        throw last;
    }

    private static void acceptLoop(int port) {
        try {
            // Bound explicitly to loopback, so this is never exposed to a
            // network even momentarily, on any interface, in any Wi-Fi state.
            ServerSocket server = bind(port);
            Ln.i("jemrec-capture build " + BUILD + " listening on 127.0.0.1:" + port
                    + " (uid " + android.os.Process.myUid() + ")");

            while (true) {
                Socket client = server.accept();
                // EACH CONNECTION ON ITS OWN THREAD. Sessions used to run here
                // one after another, so a slow client held every other one in
                // the backlog: a FETCH to an app the OS had frozen mid-copy
                // blocked the pings queued behind it, and unanswered pings are
                // what the app reads as a dead recorder. Bounded, so a process
                // opening connections and going quiet ties up a handful of
                // threads for a few seconds each, not the daemon.
                if (sessions.get() >= MAX_SESSIONS) {
                    Ln.w("session: " + MAX_SESSIONS + " already open, dropping a connection");
                    Shell.closeQuietly(client);
                    continue;
                }
                sessions.incrementAndGet();
                Thread worker = new Thread(() -> serve(client), "jemrec-session");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable t) {
            Ln.e("acceptor stopped", t);
            Looper.getMainLooper().quitSafely();
        }
    }

    private static void serve(Socket client) {
        try {
            if (!fromJemRec(client)) {
                refuse(client, "not JemRec");
                return;
            }
            session(client);
        } catch (AudioCaptureException e) {
            // Already logged by the capture layer with a specific reason. One
            // failed session must not kill the daemon: there is no ADB around
            // to restart it.
            Ln.e("session failed to start capture");
        } catch (Exception e) {
            Ln.e("session ended with an error", e);
        } finally {
            Shell.closeQuietly(client);
            sessions.decrementAndGet();
        }
    }

    /**
     * THE LOOPBACK PROTOCOL, VERSION 2: EVERY COMMAND IS AUTHENTICATED.
     *
     * Port 28472 is a plain TCP port on loopback, and loopback is shared by
     * every process on the phone. Version 1 took a bare command byte, so any
     * app could fetch a finished recording ('F'), delete one ('D'), open a
     * live capture of the call in progress ('R'), or stop the daemon ('Q').
     * For a call recorder that is not a rough edge; it is the whole game.
     *
     * Every connection now proves itself first, in both directions, and the
     * token never crosses the socket:
     *
     *   app    -> 'A' + Nc                                  (16 random bytes)
     *   daemon -> Nd + HMAC(token, "jemrec-daemon" | Nc | Nd)
     *   app    -> HMAC(token, "jemrec-client" | Nc | Nd) + command [+ payload]
     *
     * THE DAEMON PROVES ITSELF FIRST, and that is what protects the app: any
     * app can bind 28472 while this daemon is down - a reboot, an adbd
     * restart - and answer connections in its place. A scheme that sent the
     * token as a password would hand it to whoever answered. Here the app
     * sends nothing but a nonce until the far end has shown it knows the
     * token, and nothing sent afterwards can be replayed: both nonces are
     * fresh per connection and one of them is chosen here.
     *
     * 'P' on its own still answers PONG with no handshake. It reveals nothing
     * an open port does not, and the answer carries the protocol version, so
     * the app can tell an out-of-date daemon from a current one and replace
     * it. Why there is a ping at all: the app has to be able to ASK whether
     * the daemon is alive, and before there was a command byte the only way
     * to ask was to connect, which started a capture session and tore an
     * AudioRecord up and down on every health check.
     */
    private static final int PROTOCOL_VERSION = 2;
    private static final int COMMAND_HELLO = 'A';
    private static final int NONCE_BYTES = 16;
    private static final int MAC_BYTES = 32;
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final byte[] DAEMON_LABEL = "jemrec-daemon".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLIENT_LABEL = "jemrec-client".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Sessions run concurrently, one thread each. Eight is more than the app
     *  has callers; past it a connection is dropped rather than queued. */
    private static final int MAX_SESSIONS = 8;
    static final AtomicInteger sessions = new AtomicInteger();
    /** Pings since the last heartbeat line. Counted there rather than logged
     *  one by one: the keep-alive pings several times a visit, all day. */
    static final AtomicInteger pings = new AtomicInteger();
    private static volatile int listenPort = DEFAULT_PORT;

    /** A refusal is logged at most this often, with a running count, so a
     *  process hammering the port cannot fill the log with itself. */
    private static final long REFUSAL_LOG_GAP_MS = 10 * 1000L;
    private static long lastRefusalLog;
    private static int refusals;

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

    /** Six seconds of patience for a predecessor to let go of the port. */
    private static final int BIND_ATTEMPTS = 15;
    private static final long BIND_RETRY_MS = 400L;

    private static final int COMMAND_PING = 'P';
    private static final int COMMAND_RECORD = 'R';

    /**
     * THE DAEMON RECORDS TO A FILE; THE APP COLLECTS IT AFTERWARDS.
     *
     * The app used to read the live audio over this socket and mux it as the
     * call ran. That put a backgrounded, throttleable app process on the
     * real-time path: on this phone Honor's iAware freezes JemRec while another
     * app (the dialer) is in front, its socket reader stalls, and the tail of
     * the call is lost when the socket closes. Measured - a 57s call arrived as
     * 35s, ~620 packets discarded.
     *
     * So the daemon - shell uid, outside app management, never frozen - now
     * writes the whole call to /data/local/tmp itself, driven by its own call
     * watch, with no app in the loop. Afterwards the app FETCHes the finished
     * file over this socket at whatever pace the OS allows: a freeze there only
     * delays the copy, it cannot lose audio, because the audio is already on
     * disk complete.
     */
    private static final int COMMAND_FETCH = 'F';
    private static final int COMMAND_DELETE = 'D';
    private static final int COMMAND_SET_ENABLED = 'E';
    private static final int COMMAND_LIST = 'L';
    /** On-demand: the app tells the daemon to start recording the call in
     *  progress, once the user has said yes to the start-of-call prompt. */
    private static final int COMMAND_START = 'S';

    /**
     * Report on the call watch, for the app's self-test.
     *
     * The self-test proved that this daemon answers and that the HAL hands over
     * voice-call audio, and on that alone it said "a call would be recorded" -
     * while the daemon was deaf to every call, its watch garbage-collected (see
     * callWatch). Measured: PASS straight after a call it had missed. Neither
     * check touches the watch, so the app now asks after it directly: when this
     * process started, whether the watch registered, whether the main looper
     * that delivers its events still runs, the mode, whether a call is up, how
     * many events and off-hooks there have been and when the last of each came,
     * and since when the current recording has run. The app holds that against
     * the phone's call log. One line, "WATCH 1 key=value ..."; a daemon older
     * than this hangs up on the command, which the app reads as too old to say.
     */
    private static final int COMMAND_WATCH = 'W';
    private static final int WATCH_FORMAT = 1;
    /** How long the main looper gets to run a posted no-op before it counts as stuck. */
    private static final long LOOPER_CHECK_MS = 1000L;

    /** Four bytes where the codec id goes: the file the app asked for is not here. */
    private static final String GONE = "gone";

    /**
     * How calls are recorded: from the environment at spawn, then pushed by
     * the app whenever the user changes it.
     *   OFF       - captures nothing (the recorder switch is off).
     *   AUTOMATIC - records from off-hook, every call.
     *   ON_DEMAND - records NOTHING until the user answers a start-of-call
     *               prompt with yes, which arrives as COMMAND_START. So a
     *               declined call is never written to disk at all.
     *
     * OFF UNTIL TOLD OTHERWISE. This defaulted to AUTOMATIC, with the app
     * pushing the real mode over the socket a moment after the spawn - and
     * that push is best-effort. A call in the gap, or after a push that did
     * not land, was recorded whatever the user had chosen, including "ask me
     * first" and "off". A recorder that fails must fail towards not
     * recording: a missed call gets noticed and fixed, a call recorded
     * against the user's setting is neither. So the mode now arrives in the
     * environment with the token, and a daemon that has not been told
     * anything captures nothing.
     */
    private static final int MODE_OFF = 0;
    private static final int MODE_AUTOMATIC = 1;
    static final int MODE_ON_DEMAND = 2;
    private static final String MODE_ENV = "JEMREC_MODE";
    static volatile int recordingMode = MODE_OFF;

    /** The call being recorded right now, or null between calls. */
    static volatile @Nullable RecordingSession activeRecording;

    /** Whether a call is up, and its direction - so an on-demand yes that
     *  arrives mid-call knows what it is starting. */
    private static volatile boolean callActive;
    /** Calls begun, and the latest of them that has ended: a CALL_STARTED
     *  still being retried for a call already over is dropped (see wake). */
    static volatile long callSeq;
    static volatile long endedSeq;
    private static volatile boolean callIncoming;

    /** For waking the app, and for checking who connects. Set in main()
     *  before anything can connect; empty if this daemon was started by hand. */
    static volatile String appToken = "";

    /**
     * Exit.
     *
     * The app used to stop this process with `pkill` over ADB, which works
     * right up until the moment it is most needed. Starting fresh closes the
     * ADB session as part of what it does, so by the time it wanted the daemon
     * gone it had already thrown away the only way it had of asking - and the
     * daemon carried on running as an orphan until the phone was rebooted.
     *
     * Over loopback there is no such dependency. The socket is the one channel
     * that is always there, which is the entire premise of this daemon.
     */
    private static final int COMMAND_QUIT = 'Q';

    /**
     * Switch Wireless and USB debugging off, and go: "Turn all debugging off"
     * in the app's When JemRec is off, for apps that will not open while
     * debugging is on (issue #37). One byte follows: 1 to go, 0 to call off a
     * go still waiting. It waits, as QUIT does, while a call or a fetch is in
     * progress - so this process, never frozen, is the one that waits, rather
     * than an app that may be. USB debugging going off stops adbd, and adbd
     * takes this process with it (see keepShieldUp): starting it again is the
     * app's, on its On, over Wi-Fi. Replies OFF NOW, OFF AFTER CALL, OFF
     * CANCELLED, or OFF TOO LATE for a 0 that came once the switches were
     * being written: the app's On then waits for this process to be gone
     * before it puts them back, or these writes would land on its.
     */
    private static final int COMMAND_DEBUGGING_OFF = 'O';

    /**
     * The messenger apps whose calls to record - see Voip. In the environment
     * at spawn (VOIP_ENV) and sent again whenever the user changes them, the
     * same way the recording mode is. Empty, the default, records none and
     * watches nothing.
     */
    private static final int COMMAND_SET_VOIP = 'V';
    static final String VOIP_ENV = "JEMREC_VOIP";

    /** The messenger app of the call in progress; null for a phone call or none. */
    static volatile @Nullable String callVoipApp;
    /** Which way that app's call went, where Telecom said (Google Meet's);
     *  null where it did not, or for a phone call. */
    private static volatile @Nullable Boolean callAppIncoming;

    /**
     * EXACT CALL TRACKING. Telecom binds the app's in-call service
     * (CallTracker) for every phone call once MANAGE_ONGOING_CALLS is allowed
     * for the app's UID - allowing it for the package alone was not enough on
     * an Honor (measured: Telecom checked the op and bound nothing). Only
     * shell can allow it, so this process does: at start, when the app says
     * (COMMAND_SET_TRACKING), and again at each heartbeat in case the system
     * took it back - some ROMs revert it. Off withdraws it, and nothing is
     * bound.
     */
    private static final int COMMAND_SET_TRACKING = 'T';
    private static final String TRACK_ENV = "JEMREC_TRACK";

    /**
     * The rules on whose calls to keep said no to a phone call while it was
     * still ringing or being dialled (the app's CallTracker knows the number
     * by then): do not record it. A recording that started a moment ago for
     * it - dialling takes the phone off-hook at once - is stopped and deleted.
     */
    private static final int COMMAND_SKIP = 'K';
    /** A skip is for the call starting now, not for one an hour later. */
    private static final long SKIP_VALID_MS = 60_000L;
    private static final long SKIP_CURRENT_MS = 8_000L;
    private static volatile long skipUntil;

    /**
     * THE ANSWERING MACHINE - see AnswerMachine. Its settings ("on,seconds,
     * route") arrive in the environment at spawn and over 'N' when the user
     * changes them, the way the mode does, and default to off. The app's
     * go-ahead for a ringing call is 'Y' (four bytes: the delay in ms, or -1
     * to take it back); 'H' is Talk to them ('T') or Hang up ('H') during a
     * message; 'G' handles the greeting and 'X' the diagnostics.
     */
    private static final int COMMAND_ANSWER_SETTINGS = 'N';
    private static final int COMMAND_GO_AHEAD = 'Y';
    private static final int COMMAND_HANDOVER = 'H';
    private static final int COMMAND_GREETING = 'G';
    private static final int COMMAND_DIAGNOSE = 'X';
    private static final String ANSWER_ENV = "JEMREC_ANSWER";

    /** When the recording in progress captured its first sample, on the
     *  answering machine's clock (SystemClock.elapsedRealtime): the
     *  message's start is counted from here, as the app counts packets. */
    static volatile long captureStartedAt;

    /** Reading the command must not be able to hang the accept loop forever. */
    private static final int COMMAND_TIMEOUT_MS = 5000;

    /**
     * The last call's report, for Copy a report in the app's Diagnostics: see
     * CallReport. Kept in a file as well, from the moment its recording
     * stops, so a recorder started again since - an update, a revive -
     * still has it.
     */
    private static final int COMMAND_REPORT = 'C';

    /** Start capturing the current call to a file. Returns its name, or null if
     *  it could not start or one is already running. Does NOT wake the app. */
    private static synchronized @Nullable String beginRecording(boolean incoming, @Nullable String app,
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
                if (mode >= MODE_OFF && mode <= MODE_ON_DEMAND) {
                    Ln.i("recording: mode " + mode + " from the environment");
                    return mode;
                }
            } catch (NumberFormatException ignored) {
                // Falls through: not a mode.
            }
        }
        Ln.w("recording: no usable mode in the environment ('" + value
                + "'), capturing nothing until the app says otherwise");
        return MODE_OFF;
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
            if (callActive && (app != null || callVoipApp != null)) {
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
            AnswerMachine machine = Answering.answerMachine;
            if (app == null && machine != null && machine.takingMessage()) {
                // A call this process answered: the recording IS the message,
                // whatever the mode - switching the answering machine on is
                // the consent to record one. No start-of-call wake: the
                // message's own (MESSAGE_STARTED) follows with the greeting.
                // Not once the user took the call: an off-hook then - a
                // waiting call - is theirs, asked about as in any call.
                beginRecording(incoming, null, null);
                return;
            }
            if (app == null && skipUntil > System.currentTimeMillis()) {
                skipUntil = 0;
                Ln.i("recording: the rules leave this call out; not recording it");
                return;
            }
        }
        switch (recordingMode) {
            case MODE_AUTOMATIC:
                String name = beginRecording(incoming, app, appIncoming);
                if (name != null) {
                    // The app shows a "recording" notification; no audio needed.
                    Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, name, app);
                }
                break;
            case MODE_ON_DEMAND:
                RecordingSession running = activeRecording;
                if (running != null) {
                    // Already said yes to, and recording: a second off-hook
                    // in the same call (call waiting) is not a new question -
                    // asked again, "Not now" would stop nothing.
                    Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, running.name, app);
                    break;
                }
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
            if (!callActive) {
                // The call ended as it was taken: its end has named it.
                Ln.i("answer: the call was taken as it ended; nothing to split");
                return;
            }
            RecordingSession rec = activeRecording;
            if (rec == null) {
                Ln.w("answer: the call was taken; there was no recording of it to split");
            } else {
                rec.stop();
                File file = new File(Paths.REC_DIR, rec.name);
                if (part.speechAt == 0) {
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
        if (outcome != null && outcome.test) {
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
            boolean taken = !outcome.isMessage();
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
        if (!wasRecording && recordingMode != MODE_OFF) {
            Wakes.wake(Wakes.ACTION_CALL_ENDED, callIncoming, "", app);
        }
    }

    private static void session(Socket client) throws Exception {
        // The timeout stays on for the whole session. Every read here is a
        // header - a command, a proof, a name - and the one long transfer,
        // FETCH, is a write. It used to be lifted after the first byte, which
        // let a client send 'F' and nothing more to park the acceptor for good.
        client.setSoTimeout(COMMAND_TIMEOUT_MS);
        int first = client.getInputStream().read();

        if (first == COMMAND_PING) {
            pong(client);
            return;
        }
        if (first != COMMAND_HELLO) {
            refuse(client, "no handshake (first byte " + first + ")");
            return;
        }
        if (!authenticate(client)) {
            return;
        }

        int command = client.getInputStream().read();
        if (command == COMMAND_PING) {
            pong(client);
            return;
        }
        if (command == COMMAND_QUIT) {
            boolean busy;
            synchronized (Main.class) {
                // The app asks this to replace an older build, and will ask
                // again; the call is worth more than a prompt update.
                busy = busyLocked();
            }
            if (busy) {
                client.getOutputStream().write("BUSY\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                Ln.w("session: quit requested during a call or a fetch, refused");
                return;
            }
            client.getOutputStream().write("BYE\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            Ln.i("session: quit requested, exiting");
            // Flushed and acknowledged first, so the app learns it worked
            // rather than seeing a dropped connection and having to guess.
            System.exit(0);
        }
        if (command == COMMAND_DEBUGGING_OFF) {
            int go = client.getInputStream().read();
            if (go != 0 && go != 1) {
                // Only an explicit 1 switches debugging off: a byte that
                // never came reads as -1, and must not count as a go.
                refuse(client, "debugging off without a 0 or 1 (" + go + ")");
                return;
            }
            String reply;
            synchronized (Main.class) {
                if (go == 0 && DebuggingOff.switchingOff) {
                    reply = "OFF TOO LATE";
                } else if (go == 0) {
                    DebuggingOff.goingOff = false;
                    reply = "OFF CANCELLED";
                } else {
                    DebuggingOff.goingOff = true;
                    reply = busyLocked() ? "OFF AFTER CALL" : "OFF NOW";
                }
            }
            client.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            Ln.i("session: debugging off - " + reply);
            if (go == 1) {
                DebuggingOff.goOffWhenFree();
            }
            return;
        }
        if (command == COMMAND_RECORD) {
            // Live capture straight to the socket. Calls no longer use this - the
            // daemon writes them to a file (see startRecording) - but the app's
            // self-test still records a short clip this way to prove the HAL will
            // hand over voice-call audio at all.
            AudioCodec codec = rawMode ? AudioCodec.RAW : RecordingSession.AUDIO_CODEC;
            Ln.i("session: live capture, source=" + RecordingSession.AUDIO_SOURCE + " codec=" + codec);
            AudioCapture capture = new AudioDirectCapture(RecordingSession.AUDIO_SOURCE);
            capture.checkCompatibility();
            ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(client);
            try {
                Streamer streamer = new Streamer(pfd.getFileDescriptor(), codec, RecordingSession.SEND_CODEC_META, RecordingSession.SEND_FRAME_META);
                AsyncProcessor recorder = rawMode
                        ? new AudioRawRecorder(capture, streamer)
                        : new AudioEncoder(capture, streamer, RecordingSession.BIT_RATE, null, null);
                CountDownLatch finished = new CountDownLatch(1);
                recorder.start(fatalError -> finished.countDown());
                finished.await();
                recorder.stop();
                recorder.join();
            } finally {
                Shell.closeQuietly(pfd);
            }
            return;
        }
        if (command == COMMAND_LIST) {
            // Every recording still on disk, newest-relevant or not, one name per
            // line. The app fetches these on startup to collect anything a crash
            // or a reboot left behind before it could be saved.
            File dir = new File(Paths.REC_DIR);
            java.util.List<String> names = new java.util.ArrayList<>();
            // Under the lock a recording starts under: its file is created
            // before it is the active one, and in between it looked finished.
            synchronized (Main.class) {
                File[] files = dir.listFiles((d, n) -> n.startsWith(Paths.REC_PREFIX) && n.endsWith(Paths.REC_SUFFIX));
                if (files != null) {
                    // Skip the one being written right now: it is not finished.
                    String activeName = activeName();
                    for (File f : files) {
                        if (!f.getName().equals(activeName)) {
                            names.add(f.getName());
                        }
                    }
                }
            }
            OutputStream os = client.getOutputStream();
            int count = 0;
            for (String name : names) {
                os.write((name + "\n").getBytes(StandardCharsets.UTF_8));
                count++;
            }
            os.flush();
            Ln.i("session: listed " + count + " pending recording(s)");
            return;
        }
        if (command == COMMAND_SET_ENABLED) {
            int value = client.getInputStream().read();
            if (value >= MODE_OFF && value <= MODE_ON_DEMAND) {
                recordingMode = value;
                Ln.i("session: recording mode = " + recordingMode);
            } else {
                // Not a mode. Keep the one in force rather than guess - and
                // never guess AUTOMATIC, which is what this used to do.
                Ln.w("session: ignoring unusable recording mode " + value + ", still " + recordingMode);
            }
            client.getOutputStream().write((recordingMode + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_START) {
            // The user said yes to the on-demand start-of-call prompt. Begin
            // recording the call in progress, from now.
            String name = null;
            // Locked against onCallEnd - see there.
            synchronized (Main.class) {
                if (callActive && recordingMode != MODE_OFF) {
                    name = beginRecording(callIncoming, callVoipApp, callAppIncoming);
                }
            }
            if (name != null) {
                Ln.i("session: on-demand start -> " + name);
            } else {
                Ln.w("session: on-demand start refused (no call in progress, or it could not start)");
            }
            client.getOutputStream().write(((name != null ? name : "") + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_SET_TRACKING) {
            int value = client.getInputStream().read();
            Tracking.trackingOn = value == 1;
            Tracking.applyTracking();
            client.getOutputStream().write(("TRACK " + (Tracking.trackingOn ? "on" : "off") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_SKIP) {
            int value = client.getInputStream().read();
            String what;
            synchronized (Main.class) {
                RecordingSession rec = activeRecording;
                if (value != 1) {
                    skipUntil = 0;
                    what = "cancelled";
                } else if (rec != null && rec.app == null
                        && System.currentTimeMillis() - rec.startedAt < SKIP_CURRENT_MS) {
                    // Dialling went off-hook before the app could say: stop
                    // it now, and leave nothing behind.
                    rec.stop();
                    activeRecording = null;
                    boolean gone = new File(Paths.REC_DIR, rec.name).delete();
                    Ln.i("recording: the rules leave this call out; stopped and deleted " + rec.name + " -> " + gone);
                    Wakes.wake(Wakes.ACTION_CALL_ENDED, rec.incoming, "", null);
                    what = "current";
                } else {
                    skipUntil = System.currentTimeMillis() + SKIP_VALID_MS;
                    what = "next";
                }
            }
            client.getOutputStream().write(("SKIP " + what + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_SET_VOIP) {
            // The messenger apps whose calls to record, comma-separated.
            Set<String> apps = Voip.parse(readName(client));
            CallWatch.configureVoip(apps);
            client.getOutputStream().write(("VOIP " + Voip.apps().size() + " "
                    + (Voip.armed() ? "armed" : "off") + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_WATCH) {
            client.getOutputStream().write((watchReport() + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_REPORT) {
            client.getOutputStream().write((Reports.reportText() + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_FETCH) {
            String name = readName(client);
            File file = Paths.recFileFor(name);
            if (name.equals(activeName())) {
                // Still being written: not to be collected - nor, by a
                // collector that then finds it empty, deleted. Its hang-up
                // brings it.
                Ln.w("session: fetch of the recording in progress, answering gone");
                client.getOutputStream().write(GONE.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                return;
            }
            if (file == null || !file.isFile()) {
                // Say so, in the slot the codec id would take. The app tells
                // "collected by another path already" from "the file was
                // empty" by it, and only the second is news for the user.
                Ln.w("session: fetch of unknown file '" + name + "', answering gone");
                client.getOutputStream().write(GONE.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                return;
            }
            Ln.i("session: fetching " + name + " (" + file.length() + " bytes)");
            fetches.incrementAndGet();
            try (FileInputStream in = new FileInputStream(file)) {
                OutputStream os = client.getOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                fetches.decrementAndGet();
            }
            return;
        }
        if (command == COMMAND_DELETE) {
            String name = readName(client);
            File file = Paths.recFileFor(name);
            boolean ok = !name.equals(activeName()) && file != null && file.delete();
            Ln.i("session: delete " + name + " -> " + ok);
            client.getOutputStream().write((ok ? "OK\n" : "NO\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_ANSWER_SETTINGS) {
            AnswerSettings settings = AnswerSettings.parse(readName(client));
            Answering.answerSettings = settings;
            AnswerMachine machine = Answering.answerMachine;
            if (machine != null) {
                Answering.onMain(() -> {
                    machine.configure(settings.on, settings.maxMessageSeconds * 1000L);
                    return null;
                });
            }
            Ln.i("session: answering machine " + settings.wire());
            reply(client, "ANSWER " + settings.wire());
            return;
        }
        if (command == COMMAND_GO_AHEAD) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            int delay = in.readInt();
            // When the app saw the ring - eight more bytes, from 0.17 on.
            long rangAt;
            try {
                rangAt = in.readLong();
            } catch (java.io.EOFException e) {
                rangAt = 0;
            }
            // On the machine's clock as it comes: SystemClock.elapsedRealtime,
            // since boot, is the same clock in the app and here - and a
            // wall-clock step between the ring and now cannot move it.
            final long rang = rangAt;
            AnswerMachine machine = Answering.answerMachine;
            if (machine == null) {
                reply(client, "GO none");
                return;
            }
            String state = Answering.onMain(() -> {
                if (delay < 0) {
                    machine.cancelGoAhead();
                } else {
                    machine.goAhead(delay, rang);
                }
                return machine.state().name();
            });
            reply(client, "GO " + state);
            return;
        }
        if (command == COMMAND_HANDOVER) {
            int what = client.getInputStream().read();
            AnswerMachine machine = Answering.answerMachine;
            if (machine == null) {
                reply(client, "HAND none");
                return;
            }
            String state = Answering.onMain(() -> {
                if (what == 'T') {
                    // The notification's Talk, or the app seeing a second call
                    // answered: the machine tells the app (callTaken).
                    machine.talk();
                } else if (what == 'H') {
                    machine.hangUpNow();
                }
                return machine.state().name();
            });
            Ln.i("answer: hand-over '" + (char) what + "' -> " + state);
            reply(client, "HAND " + state);
            return;
        }
        if (command == COMMAND_GREETING) {
            greeting(client);
            return;
        }
        if (command == COMMAND_DIAGNOSE) {
            diagnose(client);
            return;
        }
        Ln.w("session: unknown command " + command + ", closing");
    }

    private static void reply(Socket client, String line) throws IOException {
        client.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().flush();
    }

    /**
     * 'G': the greeting. 'Q' how long it is; 'R' and 'S' start and stop a take
     * from the microphone; 'T' the take, 'F' the greeting, each as four bytes
     * of length and the PCM; 'P' a new greeting, the same way in; 'D' deletes.
     */
    private static void greeting(Socket client) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        int what = in.read();
        switch (what) {
            case 'Q':
                reply(client, "GREETING " + (Greeting.present() ? Greeting.FILE.length() : 0));
                break;
            case 'R':
                reply(client, Greeting.startTake() ? "TAKE recording" : "TAKE busy");
                break;
            case 'S':
                reply(client, "TAKE " + Greeting.stopTake());
                break;
            case 'T':
            case 'F':
                sendFile(client, what == 'T' ? Greeting.TAKE : Greeting.FILE);
                break;
            case 'P':
                // A whole greeting over loopback takes a moment at most.
                client.setSoTimeout(30_000);
                Greeting.put(in, in.readInt());
                reply(client, "GREETING " + Greeting.FILE.length());
                break;
            case 'D':
                Greeting.delete();
                reply(client, "GREETING 0");
                break;
            default:
                Ln.w("session: unknown greeting command " + what);
        }
    }

    private static void sendFile(Socket client, File file) throws IOException {
        java.io.DataOutputStream out = new java.io.DataOutputStream(client.getOutputStream());
        if (!file.isFile()) {
            out.writeInt(0);
            out.flush();
            return;
        }
        out.writeInt((int) file.length());
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        out.flush();
    }

    /**
     * 'X': the answering machine's diagnostics - see the app's diagnostics
     * screen for what each answer means to the user.
     *   'C' stage one, no call: one line of key=value.
     *   'A' arm a test call: a byte (1 incoming, 0 outgoing) and a name frame,
     *       the number to dial for an outgoing one.
     *   'R' the last test's signals.
     *   'F' the carrier's no-reply forwarding, as the network has it - slow.
     *   'S' a byte of seconds: lengthen it, keeping its number.
     */
    private static void diagnose(Socket client) throws Exception {
        DataInputStream in = new DataInputStream(client.getInputStream());
        int what = in.read();
        switch (what) {
            case 'C':
                reply(client, stageOne());
                break;
            case 'A': {
                boolean incoming = in.read() == 1;
                String number = readName(client);
                AnswerMachine machine = Answering.answerMachine;
                if (machine == null) {
                    reply(client, "TEST none");
                    break;
                }
                // Before arming: an armed outgoing test takes the next call
                // this phone places, and a number that is not dialled leaves
                // that to be the user's own. Ending in '#' - *100# - or of two
                // digits, it is sent as a USSD code: no call, no off-hook.
                if (!incoming && (!number.matches("[0-9+*#]{3,32}") || number.endsWith("#"))) {
                    reply(client, "TEST bad-number");
                    break;
                }
                // Never an emergency number - the app asks too. Telecom does
                // not place one for the shell but opens the dialer with it
                // (AOSP NewOutgoingCallIntentBroadcaster, read 2026-10-22),
                // and a Call there would be taken for the test's: the check
                // tone and the greeting, into an emergency call.
                if (!incoming && AnswerTest.mayBeEmergencyNumber(number)) {
                    reply(client, "TEST emergency");
                    break;
                }
                // The test plays the greeting this process has: without one,
                // a caller is answered and hung up on, and the route blamed.
                if (!Greeting.present()) {
                    reply(client, "TEST no-greeting");
                    break;
                }
                // A test call still up would write its results over the new
                // test's, and end it.
                if (machine.testing()) {
                    reply(client, "TEST busy");
                    break;
                }
                AnswerTest.testState = "armed";
                AnswerTest.testRoute = -1;
                AnswerTest.testRealMs = 0;
                AnswerTest.testAudioMs = 0;
                AnswerTest.testToneRun = 0;
                AnswerTest.testToneBest = 0;
                AnswerTest.testMuteHeld = -1;
                AnswerTest.testVoice = -1;
                AnswerTest.testPlayed = -1;
                Answering.onMain(() -> {
                    machine.armTest(incoming, incoming ? AnswerTest.TEST_INCOMING_WINDOW_MS : AnswerTest.TEST_OUTGOING_WINDOW_MS);
                    return null;
                });
                if (!incoming) {
                    Shell.runQuietly("am", "start", "-a", "android.intent.action.CALL",
                            "-d", "tel:" + number.replace("#", "%23"));
                }
                reply(client, "TEST armed");
                break;
            }
            case 'R':
                reply(client, "RESULT 1 state=" + AnswerTest.testState
                        + " route=" + AnswerTest.testRoute
                        + " realms=" + AnswerTest.testRealMs
                        + " audioms=" + AnswerTest.testAudioMs
                        + " tone=" + (AnswerTest.testToneBest >= AnswerTest.TONE_CHUNKS ? 1 : 0)
                        + " tonechunks=" + AnswerTest.testToneBest
                        + " mute=" + AnswerTest.testMuteHeld
                        + " voice=" + AnswerTest.testVoice
                        + " played=" + AnswerTest.testPlayed);
                break;
            case 'F': {
                client.setSoTimeout(60_000);
                TelephonyManager telephony = Platform.phone;
                reply(client, "FWD " + (telephony == null
                        ? "error no-telephony" : Forwarding.readNoReply(telephony)));
                break;
            }
            case 'P':
                reply(client, sims());
                break;
            case 'S': {
                int seconds = in.read();
                if (seconds < 5 || seconds > 30) {
                    reply(client, "FWD error bad-seconds");
                    break;
                }
                client.setSoTimeout(90_000);
                TelephonyManager telephony = Platform.phone;
                reply(client, "FWD " + (telephony == null
                        ? "error no-telephony" : Forwarding.setNoReply(telephony, seconds)));
                break;
            }
            default:
                Ln.w("session: unknown diagnostics command " + what);
        }
    }

    /** The no-call checks, as one line. */
    private static String stageOne() {
        StringBuilder line = new StringBuilder("DIAG 1");
        java.util.List<String> missing = new java.util.ArrayList<>();
        try {
            android.content.pm.PackageManager pm = FakeContext.get().getPackageManager();
            for (String permission : new String[]{
                    "android.permission.MODIFY_PHONE_STATE",
                    "android.permission.ANSWER_PHONE_CALLS",
                    "android.permission.MODIFY_AUDIO_SETTINGS",
                    "android.permission.READ_CALL_LOG",
                    "android.permission.RECORD_AUDIO"}) {
                if (pm.checkPermission(permission, "com.android.shell")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission.substring(permission.lastIndexOf('.') + 1));
                }
            }
        } catch (Throwable t) {
            missing.add("unknown");
        }
        if (!Answering.muteReady) {
            // Not a permission, but as final: no mute, no answering.
            missing.add("mute");
        }
        AudioManager audioNow = Platform.callAudio;
        try {
            // What a ring is refused on when it cannot be read: a phone where
            // it always throws would refuse every call, checks passed or not.
            if (audioNow == null) {
                throw new IllegalStateException("the call watch is not up");
            }
            Voip.someoneInCall(audioNow);
        } catch (Throwable t) {
            missing.add("calls");
        }
        line.append(" perms=").append(missing.isEmpty() ? "ok" : String.join(",", missing));
        line.append(" incallmusic=").append(Checks.inCallMusic(Shell.dump("media.audio_policy")));
        boolean interceptable = false;
        try {
            interceptable = (boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(Platform.callAudio);
        } catch (Throwable ignored) {
            // Not there before Android 13, or not allowed.
        }
        line.append(" screening=").append(interceptable ? 1 : 0);
        line.append(" greeting=").append(Greeting.present() ? Greeting.FILE.length() : 0);
        line.append(" route=").append(Answering.answerSettings.route);
        line.append(" sdk=").append(Build.VERSION.SDK_INT);
        int voice = -1;
        TelephonyManager phoneNow = Platform.phone;
        try {
            if (phoneNow != null) {
                voice = phoneNow.getVoiceNetworkType();
            }
        } catch (Throwable ignored) {
            // unknown
        }
        line.append(" voice=").append(voice);
        line.append(" soc=").append(word(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE));
        line.append(" maker=").append(word(Build.MANUFACTURER));
        line.append(" model=").append(word(Build.MODEL));
        return line.toString();
    }

    /**
     * The phone's SIMs as Telecom has them, for the app's "Answer on" choice:
     * "SIMS default=<id> <id>|<label> ...". Listing them takes READ_PHONE_STATE,
     * which the app does not hold and should not ask for to fill one row.
     */
    private static String sims() {
        StringBuilder line = new StringBuilder("SIMS");
        TelecomManager telecom = Platform.telecom;
        if (telecom == null) {
            Ln.w("answer: could not list the SIMs: no telecom");
            return line.toString();
        }
        try {
            android.telecom.PhoneAccountHandle preferred = telecom.getDefaultOutgoingPhoneAccount("tel");
            line.append(" default=").append(preferred == null ? "" : word(preferred.getId()));
            for (android.telecom.PhoneAccountHandle handle : telecom.getCallCapablePhoneAccounts()) {
                android.telecom.PhoneAccount account = telecom.getPhoneAccount(handle);
                CharSequence label = account == null ? null : account.getLabel();
                line.append(' ').append(word(handle.getId())).append('|')
                        .append(word(label == null ? "SIM" : label.toString()));
            }
        } catch (Throwable t) {
            Ln.w("answer: could not list the SIMs: " + t);
        }
        return line.toString();
    }

    /** A value for a key=value line: no spaces, no '='. */
    private static String word(String value) {
        return value == null || value.isEmpty() ? "unknown" : value.replaceAll("[\\s=]+", "_");
    }

    /** Fetches under way, which QUIT waits out. */
    private static final AtomicInteger fetches = new AtomicInteger();

    /** The name of the file being recorded into, or null. */
    private static @Nullable String activeName() {
        RecordingSession active = activeRecording;
        return active == null ? null : active.name;
    }

    private static void pong(Socket client) throws IOException {
        pings.incrementAndGet();
        client.getOutputStream().write(
                ("PONG " + PROTOCOL_VERSION + " " + BUILD + "\n").getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().flush();
    }

    /** The answer to COMMAND_WATCH. Times are wall-clock millis, 0 for never. */
    private static String watchReport() {
        RecordingSession rec = activeRecording;
        return "WATCH " + WATCH_FORMAT
                + " since=" + STARTED_AT
                + " watching=" + (CallWatch.watching ? 1 : 0)
                + " looper=" + (looperResponds() ? "ok" : "stuck")
                + " mode=" + recordingMode
                + " call=" + (callActive ? 1 : 0)
                + " events=" + CallWatch.callEvents.get()
                + " lastevent=" + CallWatch.lastCallEventAt
                + " offhooks=" + CallWatch.offHooks.get()
                + " lastoffhook=" + CallWatch.lastOffHookAt
                + " recording=" + (rec == null ? 0 : rec.startedAt)
                + " voip=" + Voip.apps().size()
                + " voiparmed=" + (Voip.armed() ? 1 : 0)
                + " tracking=" + (Tracking.trackingOn ? 1 : 0)
                + " answer=" + (Answering.answerSettings.on ? 1 : 0)
                + " greeting=" + (Greeting.present() ? 1 : 0);
    }

    /**
     * Whether the main looper still runs what is posted to it. Call events and
     * the end-of-call handling all run there, so a looper that does not is a
     * daemon that has stopped handling calls, however well it answers pings -
     * those are served on other threads.
     */
    private static boolean looperResponds() {
        CountDownLatch ran = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(ran::countDown);
        try {
            return ran.await(LOOPER_CHECK_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    /**
     * The handshake described above, from the daemon's side. True once the
     * client has proved it knows the token; false - already logged, nothing
     * sent that helps anyone - otherwise.
     */
    private static boolean authenticate(Socket client) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        byte[] clientNonce = new byte[NONCE_BYTES];
        try {
            in.readFully(clientNonce);
        } catch (EOFException | SocketTimeoutException e) {
            refuse(client, "hung up before sending a nonce");
            return false;
        }

        String token = appToken;
        if (token.isEmpty()) {
            // Started by hand with no token: nothing can prove itself to this
            // daemon, so nothing gets in. Fail closed rather than open.
            refuse(client, "this daemon has no token to check against");
            return false;
        }
        byte[] key = token.getBytes(StandardCharsets.UTF_8);

        byte[] daemonNonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(daemonNonce);
        OutputStream out = client.getOutputStream();
        out.write(daemonNonce);
        out.write(mac(key, DAEMON_LABEL, clientNonce, daemonNonce));
        out.flush();

        byte[] proof = new byte[MAC_BYTES];
        try {
            in.readFully(proof);
        } catch (EOFException | SocketTimeoutException e) {
            refuse(client, "hung up during the handshake");
            return false;
        }
        // Constant-time, so the comparison itself leaks nothing about how
        // much of a guess was right.
        if (!MessageDigest.isEqual(proof, mac(key, CLIENT_LABEL, clientNonce, daemonNonce))) {
            refuse(client, "wrong proof");
            return false;
        }
        return true;
    }

    private static byte[] mac(byte[] key, byte[] label, byte[] a, byte[] b) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            mac.update(label);
            mac.update(a);
            mac.update(b);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(MAC_ALGORITHM + " is not available", e);
        }
    }

    /** Log a refused connection - throttled, counted, and with the uid that
     *  made it, which is the one fact worth having about a refusal. */
    private static synchronized void refuse(Socket client, String why) {
        refusals++;
        long now = System.currentTimeMillis();
        if (now - lastRefusalLog < REFUSAL_LOG_GAP_MS) {
            return;
        }
        lastRefusalLog = now;
        Ln.w("session: refused (" + refusals + " so far) from uid " + peerUid(client) + " - " + why);
    }

    /**
     * Who is on the other end of a loopback connection: the uid owning the
     * client's socket, from /proc/net/tcp, which shell may read and which lists
     * every socket on the phone with its owner. Diagnostic only - the refusal
     * is decided by then - but "which app" is the whole question a refusal
     * raises. -1 if the table cannot be read or the socket is not in it.
     */
    private static int peerUid(Socket client) {
        int clientPort = client.getPort();
        for (String table : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(table), StandardCharsets.UTF_8))) {
                String line = reader.readLine(); // the header
                while ((line = reader.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 8) {
                        continue;
                    }
                    // The CLIENT's row: its local port is what we see as the
                    // remote one, and its remote port is ours.
                    if (portOf(f[1]) == clientPort && portOf(f[2]) == listenPort) {
                        return Integer.parseInt(f[7]);
                    }
                }
            } catch (Throwable ignored) {
                // Not readable, or not in the shape expected: unknown it is.
            }
        }
        return -1;
    }

    /**
     * JemRec itself, or the shell (adb, and the tools run over it): the only
     * callers this daemon has. Anything else is turned away before a byte is
     * read. Unknown - the table could not be read, or the app's uid not found
     * - is let through to the handshake.
     */
    private static boolean fromJemRec(Socket client) {
        int uid = peerUid(client);
        if (Checks.callerAllowed(uid, appUid(false))) {
            return true;
        }
        // Reinstalled since this daemon started, perhaps: a new uid. Asked
        // again once before turning the caller away.
        return Checks.callerAllowed(uid, appUid(true));
    }

    private static volatile int appUid = -1;

    private static int appUid(boolean fresh) {
        if (fresh || appUid < 0) {
            try {
                appUid = FakeContext.get().getPackageManager().getPackageUid(APP_PACKAGE, 0);
            } catch (Throwable t) {
                appUid = -1;
            }
        }
        return appUid;
    }

    /** The port half of a /proc/net/tcp address, "0100007F:6F38" -> 28472. */
    private static int portOf(String address) {
        return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1), 16);
    }

    /** A length-prefixed UTF-8 name: two big-endian bytes, then that many bytes. */
    private static String readName(Socket client) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        int len = in.readUnsignedShort();
        if (len > 512) {
            throw new IOException("implausible name length " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

}
