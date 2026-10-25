package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioEncoder;
import com.genymobile.scrcpy.audio.AudioRawRecorder;
import com.genymobile.scrcpy.audio.AudioSource;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
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

    /**
     * Both directions of the call. This is the whole point of the project, and
     * whether it actually carries the far end is a property of the device's
     * audio HAL rather than of this code. The app's self-test checks it.
     */
    private static final AudioSource AUDIO_SOURCE = AudioSource.VOICE_CALL;

    /** Opus at 48 kHz stereo. scrcpy's default, and a sane one for speech. */
    private static final AudioCodec AUDIO_CODEC = AudioCodec.OPUS;
    private static final int BIT_RATE = 128_000;

    /** Send the codec id, and per-packet pts and length. The app needs the pts
     *  to feed MediaMuxer. */
    private static final boolean SEND_CODEC_META = true;
    private static final boolean SEND_FRAME_META = true;

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
            trackingOn = "1".equals(System.getenv(TRACK_ENV));
            answerSettings = AnswerSettings.parse(System.getenv(ANSWER_ENV));

            final int bound = port;
            listenPort = bound;
            Thread acceptor = new Thread(() -> acceptLoop(bound), "jemrec-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            Thread custodian = new Thread(Main::watchForUninstall, "jemrec-custodian");
            custodian.setDaemon(true);
            custodian.start();

            // The off-Wi-Fi shield, kept up by the process whose life depends
            // on it. See keepShieldUp for why the app doing it was not enough.
            Thread shield = new Thread(Main::keepShieldUp, "jemrec-shield");
            shield.setDaemon(true);
            shield.start();

            // The few seconds' warning a USB cable gives before adbd goes,
            // passed on to the app. See watchCable.
            Thread cable = new Thread(Main::watchCable, "jemrec-cable");
            cable.setDaemon(true);
            cable.start();

            startCallWatcher(token);
            startVoipWatcher(token);
            // Off the main thread: it runs `cmd appops`, which takes a moment.
            Thread tracking = new Thread(Main::applyTracking, "jemrec-tracking");
            tracking.setDaemon(true);
            tracking.start();

            // The daemon survives the app being killed in the background, so it
            // is where the app's own death can be seen and the user told. See
            // watchBackgroundKill.
            Thread killWatch = new Thread(Main::watchBackgroundKill, "jemrec-killwatch");
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

    /**
     * Watch call state from HERE, so the app does not have to stay alive to.
     *
     * WHY THE APP CANNOT SIMPLY BE WOKEN BY A BROADCAST
     *
     * The obvious design is a manifest receiver for PHONE_STATE. On this phone
     * it does not work: Honor's iAware drops that broadcast to any app whose
     * process is not already running, measured twice, and "not already
     * running" is precisely the state a dormant app is in when a call arrives.
     * The first answer to that was a permanent foreground service in the app -
     * alive at all times so the broadcast has somewhere to land - with the
     * permanent notification Android requires for one.
     *
     * That was the heaviest tool reached for first. This process already runs
     * permanently, is invisible, and runs as shell - which holds
     * READ_PRIVILEGED_PHONE_STATE and can register for call state directly. So
     * the daemon watches the phone, and when a call starts it wakes the app's
     * recording service with `am start-foreground-service`: something shell is
     * allowed to do from anywhere, and not a broadcast, so nothing filters it.
     * The app then holds a foreground service, and shows a notification, for
     * exactly the length of the call.
     *
     * THE TOKEN
     *
     * Waking the service means exporting it, and an exported service that
     * starts recording phone calls must not be startable by any app on the
     * phone. The app generates a random token, hands it to this process in its
     * environment at launch, and refuses the intent without it. The environment
     * of a shell-uid process is readable only by shell and root.
     */
    private static void startCallWatcher(String token) {
        if (token == null || token.isEmpty()) {
            Ln.w("callwatch: no token in the environment, not watching calls");
            return;
        }
        try {
            // TelephonyManager reaches the phone service through a static
            // "service registerer" that a normal app process is handed during
            // startup and this process never is - it was started from a shell,
            // not by the system. Left unset it is null and registration dies
            // in getITelephony() with an NPE (measured). The class that holds
            // it is hidden, so it is set the way scrcpy sets every other piece
            // of app-process furniture this daemon lacks: by reflection.
            Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
            Class<?> managerClass = Class.forName("android.os.TelephonyServiceManager");
            Object manager = managerClass.getDeclaredConstructor().newInstance();
            initializer.getDeclaredMethod("setTelephonyServiceManager", managerClass)
                    .invoke(null, manager);

            // The manager the system hands back is bound to the system
            // context, whose package is "android" - and telephony checks the
            // calling package belongs to the calling uid, so it throws
            // "Package android does not belong to 2000" (measured, the second
            // wall this hit). Its mContext is repointed at the FakeContext,
            // whose package is com.android.shell and DOES belong to shell -
            // the identical trick FakeContext already plays for the clipboard,
            // and better than the hidden TelephonyManager(Context) constructor,
            // which is not in the platform jar this compiles against.
            Context context = FakeContext.get();
            TelephonyManager telephony =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            try {
                Field mContext = TelephonyManager.class.getDeclaredField("mContext");
                mContext.setAccessible(true);
                mContext.set(telephony, context);
            } catch (ReflectiveOperationException e) {
                Ln.w("callwatch: could not rebind telephony context: " + e.getMessage());
            }
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // Handed out with no context at all: setMicrophoneMute reads the
            // caller's package from it, as setMode does - which died of an NPE
            // in the answering-machine probe (the Honor, 2026-10-22). getMode
            // swallows the same NPE, which is why the call watch never
            // noticed. Only the answering machine mutes, so a phone where this
            // fails loses that, not the watch.
            try {
                for (String name : new String[]{"mOriginalContext", "mApplicationContext"}) {
                    Field field = AudioManager.class.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(audio, context);
                }
                muteReady = true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Ln.w("answer: could not rebind the audio context, so no mute and no answering: " + e);
            }
            Handler handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
            callAudio = audio;
            phone = telephony;
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            try {
                Field mContext = TelecomManager.class.getDeclaredField("mContext");
                mContext.setAccessible(true);
                mContext.set(telecomManager, context);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // The answering machine's, not the watch's: whatever this
                // throws must not stop the call watch below from registering.
                Ln.w("answer: could not rebind the telecom context: " + e);
            }
            telecom = telecomManager;
            answerMachine = new AnswerMachine(new AnswerEffects());
            AnswerSettings settings = answerSettings;
            answerMachine.configure(settings.on, settings.maxMessageSeconds * 1000L);
            Ln.i("answer: " + (settings.on
                    ? "on, " + (settings.maxMessageSeconds == AnswerSettings.NO_LIMIT ? "no limit" : "up to " + settings.maxMessageSeconds + " s")
                        + ", route " + settings.route
                    : "off"));
            giveBackStaleMute();
            // Into a field, never inline: the framework only holds it weakly.
            // See callWatch.
            callWatch = new CallWatch(audio, handler);
            telephony.registerTelephonyCallback(handler::post, callWatch);
            watching = true;
            Ln.i("callwatch: watching call state as uid " + android.os.Process.myUid());
        } catch (Throwable t) {
            Ln.e("callwatch: could not register for call state", t);
        }
    }

    /**
     * THE CALL WATCH, HELD FOR THE LIFE OF THE PROCESS.
     *
     * The framework does not keep a TelephonyCallback alive. The binder stub it
     * registers refers to the callback through a WeakReference
     * (TelephonyCallback.IPhoneStateListenerStub), and once that is cleared
     * every call state change is dropped inside the stub - silently, before any
     * code here runs. This used to be registered inline with nothing else
     * referring to it, so the first garbage collection that found it unpinned
     * took it, and the daemon went deaf: answering pings, passing the
     * self-test, logging heartbeats, and never seeing another call. The
     * telephony registry still lists the listener, so nothing on the system
     * side shows it either.
     *
     * Reported as "only the first call is recorded" (Honor 90, MagicOS 9), and
     * measured on 2026-10-09. On an Android 15 emulator the daemon's first GC
     * came nine minutes after spawn, unprompted, and the next call was missed.
     * Forcing one with `kill -USR1` (ART answers it with a full GC) did the same
     * on an Honor running Android 17, with real calls. Held here, the watch
     * survived every forced GC and heard every call.
     */
    private static CallWatch callWatch;

    /**
     * WHAT THE CALL WATCH HAS HEARD, FOR THE APP'S SELF-TEST. See COMMAND_WATCH.
     *
     * A deaf watch is otherwise invisible: the daemon answers pings and the
     * self-test's live capture works, because neither involves the watch. These
     * let the app ask whether a call the phone logged ever reached it.
     */
    private static final long STARTED_AT = System.currentTimeMillis();
    private static volatile boolean watching;
    private static final AtomicInteger callEvents = new AtomicInteger();
    private static final AtomicInteger offHooks = new AtomicInteger();
    private static volatile long lastCallEventAt;
    private static volatile long lastOffHookAt;

    private static final class CallWatch extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {

        private final AudioManager audio;
        /** What the events mean - see CallStates; this class only feeds it. */
        private final CallStates states;

        boolean phoneCallUp() {
            return states.inCall();
        }

        boolean phoneCallRang() {
            return states.rang();
        }
        private final Runnable endCheck;

        CallWatch(AudioManager audio, Handler handler) {
            this.audio = audio;
            this.states = new CallStates(new CallStates.Effects() {
                @Override
                public void callStarted(boolean incoming) {
                    onCallStart(incoming);
                }

                @Override
                public void callEnded() {
                    onCallEnd();
                }

                @Override
                public void scheduleEndCheck(long delayMs) {
                    handler.postDelayed(endCheck, delayMs);
                }

                @Override
                public void cancelEndCheck() {
                    handler.removeCallbacks(endCheck);
                }

                @Override
                public boolean audioInCall() {
                    return CallWatch.this.audioInCall();
                }

                @Override
                public void callGoesOn() {
                    // The answering machine stopped listening at that idle.
                    AnswerMachine machine = answerMachine;
                    if (machine != null) {
                        machine.callGoesOn();
                    }
                }

                @Override
                public void log(String message) {
                    Ln.i(message);
                }
            });
            this.endCheck = states::endCheck;
        }

        @Override
        public void onCallStateChanged(int state) {
            // Before anything else, so the self-test sees every event that
            // arrived, whatever came of it.
            callEvents.incrementAndGet();
            lastCallEventAt = System.currentTimeMillis();
            AnswerMachine machine = answerMachine;
            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    states.ringing();
                    if (machine != null) {
                        machine.ringing();
                    }
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    offHooks.incrementAndGet();
                    lastOffHookAt = System.currentTimeMillis();
                    // First: the call start that follows asks whether this
                    // machine answered it.
                    if (machine != null) {
                        machine.offHook(states.rang());
                    }
                    states.offHook();
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    if (machine != null) {
                        machine.idleSignal();
                    }
                    states.idle();
                    break;
                default:
                    break;
            }
        }

        /**
         * Whether telecom still holds the call's audio mode. This is the
         * reliable "is a call up" signal on this device; getCallState() is not.
         * Logged at each poll so a premature or late stop can be read straight
         * from the daemon log.
         */
        private boolean audioInCall() {
            int mode;
            try {
                mode = audio.getMode();
            } catch (Throwable t) {
                Ln.w("callwatch: could not read the audio mode; treating the call as over");
                return false;
            }
            boolean up = mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION;
            Ln.i("callwatch: idle seen, audio mode=" + mode + (up ? " (still in a call)" : " (call is over)"));
            return up;
        }
    }

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
    private static void watchForUninstall() {
        while (true) {
            sleep(PACKAGE_CHECK_INTERVAL_MS);
            if (appIsInstalled()) {
                continue;
            }
            // Asked twice. A single failed check is far more likely to be a
            // busy package manager than an uninstall, and exiting by mistake
            // means a phone that silently stops recording calls.
            sleep(CONFIRM_DELAY_MS);
            if (appIsInstalled()) {
                continue;
            }
            AnswerMachine machine = answerMachine;
            if (machine != null && machine.takingMessage()) {
                // A message under way: its caller hung up on first, and the
                // microphone given back as the call ends - then the rest. A
                // call the user took over is theirs, left to go on.
                Ln.i("custodian: JemRec is no longer installed; ending the message first");
                try {
                    onMain(() -> {
                        machine.hangUpNow();
                        return null;
                    });
                } catch (Exception e) {
                    Ln.w("custodian: could not end the message: " + e);
                }
                continue;
            }
            if (!giveBackStaleMute()) {
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
            Process process = new ProcessBuilder("pm", "path", APP_PACKAGE)
                    .redirectErrorStream(true)
                    .start();
            String text = slurp(process);
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

    /** How often to look for the app having been killed in the background. A
     *  minute: the read is cheap and the warning is advisory, not time-critical
     *  the way the cable watch is. */
    private static final long BG_KILL_CHECK_INTERVAL_MS = 60_000L;

    /**
     * Watch for THIS PHONE killing the app in the background and, when it
     * happens, wake the app so it can tell the user how to stop it (see
     * BackgroundKill on the app side).
     *
     * Measured on the Honor (2026-10-25): with App launch on "Manage
     * automatically", the screen going off had the system kill the app
     * mid-service and then mark it stopped, so nothing brought the recorder
     * back until the app was next opened. The daemon runs as shell in its own
     * session and is NOT frozen or killed by that - it is the one part left
     * running that can notice and raise the alarm at once.
     *
     * `dumpsys activity exit-info` lists the app's past exits, newest first;
     * Checks.backgroundKillStamp picks out the newest system kill that cost
     * something - one while the app was working, or one that left it stopped -
     * passing over Android's routine clean-up of an idle app and the user's
     * own Force stop. This side reads the stopped flag straight away; the app,
     * running again, has only Android 15's record of its own start, so it
     * trusts the wake's judgement. The newest stop seen at startup is the baseline - one
     * from before the daemon began watching is not news - and any newer one is
     * reported once, keyed by its timestamp (Checks.newStop). The app dedups again its own way,
     * so a stop the app also notices on next open is not told twice.
     */
    private static void watchBackgroundKill() {
        java.util.Set<String> told = new java.util.HashSet<>();
        String baseline = backgroundKillStamp();
        Checks.newStop(told, baseline);
        Ln.i("killwatch: watching every " + (BG_KILL_CHECK_INTERVAL_MS / 1000)
                + "s, baseline " + baseline);
        while (true) {
            sleep(BG_KILL_CHECK_INTERVAL_MS);
            String stamp = backgroundKillStamp();
            if (Checks.newStop(told, stamp)) {
                Ln.w("killwatch: the system killed JemRec in the background (exit " + stamp
                        + ") - waking the app to warn");
                wake(ACTION_BACKGROUND_KILLED, false, null, null);
            }
        }
    }

    /** Checks.backgroundKillStamp on a fresh `dumpsys activity exit-info`, or
     *  null if the dump could not be read (runQuietly logs why). The package
     *  dump (36 ms on the Honor) is read only when the newest exit is an idle
     *  kill, the one case where stopped decides. */
    private static String backgroundKillStamp() {
        return Checks.backgroundKillStamp(runQuietly("dumpsys", "activity", "exit-info", APP_PACKAGE),
                () -> Checks.packageStopped(runQuietly("dumpsys", "package", APP_PACKAGE)));
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
        leftovers.add(new File(JAR_PATH));
        leftovers.add(new File(LOG_PATH));
        leftovers.add(new File(LOG_PATH + ".1"));
        // The user's greeting, a take, and the marker of a mute left
        // mid-message - given back already (watchForUninstall).
        leftovers.add(Greeting.FILE);
        leftovers.add(Greeting.TAKE);
        leftovers.add(MUTE_MARKER);
        File[] recordings = new File(REC_DIR).listFiles((d, n) -> n.startsWith(REC_PREFIX) && n.endsWith(REC_SUFFIX));
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
        for (String setting : new String[]{"adb_wifi_enabled", "adb_enabled"}) {
            try {
                new ProcessBuilder("settings", "put", "global", setting, "0")
                        .start().waitFor();
                Ln.i("custodian: turned " + setting + " off");
            } catch (Throwable t) {
                Ln.w("custodian: could not turn " + setting + " off");
            }
        }
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
    private static void keepShieldUp() {
        Ln.i("shield: minding adb_enabled every " + (SHIELD_CHECK_INTERVAL_MS / 1000)
                + "s, from " + new java.util.Date());
        int tick = 0;
        // Check FIRST, then sleep: the state at spawn is itself a data point.
        while (true) {
            boolean heartbeat = tick++ % HEARTBEAT_EVERY == 0;
            checkShield(heartbeat ? "heartbeat" : null);
            if (heartbeat) {
                ensureTracking();
            }
            sleep(SHIELD_CHECK_INTERVAL_MS);
        }
    }

    /** One check: put adb_enabled back to 1 if it is anything else, logging
     *  the restore with its time. With a non-null why, also a heartbeat line
     *  with both switches; otherwise a quiet check stays quiet. */
    private static void checkShield(String why) {
        try {
            String usb = readGlobal("adb_enabled");
            if (why != null) {
                Ln.i("shield: " + why + " adb_enabled=" + usb + " adb_wifi_enabled="
                        + readGlobal("adb_wifi_enabled") + " pings=" + pings.getAndSet(0)
                        + " sessions=" + sessions.get() + " at " + new java.util.Date());
            }
            if (usb != null && !"1".equals(usb)) {
                new ProcessBuilder("settings", "put", "global", "adb_enabled", "1")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor();
                // Stamped: WHEN the setting flips is the one thing nobody has
                // been able to see. Each of these is a data point about what
                // Honor does and when.
                Ln.w("shield: adb_enabled was " + usb + " at " + new java.util.Date()
                        + " - turned USB debugging back on");
            }
        } catch (Throwable t) {
            Ln.w("shield: could not check or restore adb_enabled: " + t.getMessage());
        }
    }

    /** `settings get global name`, trimmed; null if it could not be read. */
    private static String readGlobal(String name) {
        try {
            Process process = new ProcessBuilder("settings", "get", "global", name)
                    .redirectErrorStream(true)
                    .start();
            String value = slurp(process);
            process.waitFor();
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
     * settings observer is (keepShieldUp) - measured on the Android 17
     * emulator, "registerReceiverWithFeature: no app for null". Shell's
     * MANAGE_USB lets this read the USB port's status from the USB service
     * instead, which flips the moment the cable does (the battery status
     * does not: plugged in at 80 % it stayed "discharging").
     */
    private static void watchCable() {
        Object usb = null;
        java.lang.reflect.Method status = null;
        java.lang.reflect.Method mode = null;
        java.util.List<String> ports = new java.util.ArrayList<>();
        // Looked for until found: a recorder started just after boot can be
        // up before the USB service is. Said once, not every minute.
        boolean said = false;
        while (ports.isEmpty()) {
            try {
                Object binder = Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "usb");
                usb = Class.forName("android.hardware.usb.IUsbManager$Stub")
                        .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                java.util.List<?> found = (java.util.List<?>) usb.getClass().getMethod("getPorts").invoke(usb);
                status = usb.getClass().getMethod("getPortStatus", String.class);
                mode = Class.forName("android.hardware.usb.UsbPortStatus").getMethod("getCurrentMode");
                if (found != null) {
                    for (Object port : found) {
                        ports.add(portId(port));
                    }
                }
                if (ports.isEmpty() && !said) {
                    Ln.i("cable: no USB port to watch yet");
                    said = true;
                }
            } catch (Throwable t) {
                if (!said) {
                    Ln.w("cable: cannot read the USB ports yet: " + rootCause(t));
                    said = true;
                }
            }
            if (ports.isEmpty()) {
                sleep(CABLE_PORTS_RETRY_MS);
            }
        }
        Ln.i("cable: watching USB port(s) " + ports + " every " + CABLE_CHECK_INTERVAL_MS / 1000 + "s");
        Boolean before = null;
        while (true) {
            int[] modes = new int[ports.size()];
            for (int i = 0; i < modes.length; i++) {
                modes[i] = portMode(usb, status, mode, ports.get(i));
            }
            int reading = Checks.portsReading(modes);
            if (reading >= 0) {
                boolean in = Checks.cableIn(reading);
                String change = Checks.cableChange(before, in);
                if (change != null) {
                    String config = property("sys.usb.config");
                    Ln.i("cable: " + change + " at " + new java.util.Date() + " (port mode " + reading
                            + ", USB debugging " + (Checks.usbAdb(config) ? "on the cable" : "not on it")
                            + ") - waking the app to watch the recorder");
                    wake(ACTION_CABLE, false, "", null, "--es", "cable", change);
                }
                before = in;
            }
            sleep(CABLE_CHECK_INTERVAL_MS);
        }
    }

    /** A port's id: the method where there is one, the field where Android
     *  17 has only that, and the Honor's one port's name failing both. */
    private static String portId(Object parcelable) {
        try {
            return (String) parcelable.getClass().getMethod("getId").invoke(parcelable);
        } catch (Throwable ignored) {
            // Android 17's ParcelableUsbPort has no getter.
        }
        try {
            Field field = parcelable.getClass().getDeclaredField("mId");
            field.setAccessible(true);
            return (String) field.get(parcelable);
        } catch (Throwable ignored) {
            return "port0";
        }
    }

    /** UsbPortStatus.getCurrentMode for [port], or -1 if it could not be read.
     *  No status at all is a port the service does not know - unknown, not
     *  "no cable", which is a status with mode 0. */
    private static int portMode(Object usb, java.lang.reflect.Method status, java.lang.reflect.Method mode,
            String port) {
        try {
            Object reading = status.invoke(usb, port);
            return reading == null ? -1 : (Integer) mode.invoke(reading);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String property(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Throwable rootCause(Throwable t) {
        return t.getCause() != null ? t.getCause() : t;
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
                sleep(BIND_RETRY_MS);
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
                    closeQuietly(client);
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
            closeQuietly(client);
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
    private static final AtomicInteger sessions = new AtomicInteger();
    /** Pings since the last heartbeat line. Counted there rather than logged
     *  one by one: the keep-alive pings several times a visit, all day. */
    private static final AtomicInteger pings = new AtomicInteger();
    private static volatile int listenPort = DEFAULT_PORT;

    /** A refusal is logged at most this often, with a running count, so a
     *  process hammering the port cannot fill the log with itself. */
    private static final long REFUSAL_LOG_GAP_MS = 10 * 1000L;
    private static long lastRefusalLog;
    private static int refusals;

    private static final String APP_PACKAGE = "com.jemcik.jemrec";
    private static final String APP_SERVICE = "com.jemcik.jemrec/.capture.CallMonitorService";
    private static final String ACTION_CALL_STARTED = "com.jemcik.jemrec.CALL_STARTED";
    private static final String ACTION_CALL_ENDED = "com.jemcik.jemrec.CALL_ENDED";
    /** A USB cable went in or came out: adbd may be about to go. See watchCable. */
    private static final String ACTION_CABLE = "com.jemcik.jemrec.CABLE";
    private static final String ACTION_BACKGROUND_KILLED = "com.jemcik.jemrec.BACKGROUND_KILLED";
    private static final String TOKEN_ENV = "JEMREC_TOKEN";
    private static final String JAR_PATH = "/data/local/tmp/jemrec-capture.jar";
    private static final String LOG_PATH = "/data/local/tmp/jemrec-capture.out";

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
    private static final String BUILD = jarBuild();

    /** Five minutes. Frequent enough that an orphan is short-lived, rare
     *  enough that it costs nothing to run forever. */
    private static final long PACKAGE_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long CONFIRM_DELAY_MS = 10 * 1000L;

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

    private static final String REC_DIR = "/data/local/tmp";
    private static final String REC_PREFIX = "jemrec_rec_";
    private static final String REC_SUFFIX = ".dat";
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
    private static final int MODE_ON_DEMAND = 2;
    private static final String MODE_ENV = "JEMREC_MODE";
    private static volatile int recordingMode = MODE_OFF;

    /** The call being recorded right now, or null between calls. */
    private static volatile RecordingSession activeRecording;

    /** Whether a call is up, and its direction - so an on-demand yes that
     *  arrives mid-call knows what it is starting. */
    private static volatile boolean callActive;
    /** Calls begun, and the latest of them that has ended: a CALL_STARTED
     *  still being retried for a call already over is dropped (see wake). */
    private static volatile long callSeq;
    private static volatile long endedSeq;
    private static volatile boolean callIncoming;

    /** For waking the app, and for checking who connects. Set in main()
     *  before anything can connect; empty if this daemon was started by hand. */
    private static volatile String appToken = "";

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
     * The messenger apps whose calls to record - see Voip. In the environment
     * at spawn (VOIP_ENV) and sent again whenever the user changes them, the
     * same way the recording mode is. Empty, the default, records none and
     * watches nothing.
     */
    private static final int COMMAND_SET_VOIP = 'V';
    private static final String VOIP_ENV = "JEMREC_VOIP";
    /** In a messenger call's rec-file name, before the app: jemrec_rec_<ms>_voip-<app>.dat */
    private static final String VOIP_TAG = "_voip-";

    /** The messenger app of the call in progress; null for a phone call or none. */
    private static volatile String callVoipApp;

    /** For Voip.configure from a session thread: set once the watcher starts. */
    private static Handler voipHandler;
    private static AudioManager voipAudio;

    private static final Voip.Listener VOIP_LISTENER = new Voip.Listener() {
        @Override
        public void onVoipCallStart(String app) {
            onCallStart(false, app);
        }

        @Override
        public void onVoipCallEnd() {
            Main.onVoipCallEnd();
        }
    };

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
    private static volatile boolean trackingOn;

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
    private static final String ACTION_MESSAGE_STARTED = "com.jemcik.jemrec.MESSAGE_STARTED";
    private static final String ACTION_MESSAGE_TAKEN = "com.jemcik.jemrec.MESSAGE_TAKEN";
    /** Left while this process holds the microphone muted, so a restart in
     *  the middle of a message gives the microphone back. */
    private static final File MUTE_MARKER = new File("/data/local/tmp/jemrec-muted");

    private static volatile AnswerSettings answerSettings = AnswerSettings.OFF;
    /** The microphone can be muted from here (the audio context rebound).
     *  Without that, nothing is answered: the caller would hear the room. */
    private static volatile boolean muteReady;
    private static AnswerMachine answerMachine;
    private static Handler mainHandler;
    private static AudioManager callAudio;
    private static TelecomManager telecom;
    private static TelephonyManager phone;
    private static final GreetingPlayer greetingPlayer = new GreetingPlayer();
    /** When the recording in progress captured its first sample, on the
     *  answering machine's clock (SystemClock.elapsedRealtime): the
     *  message's start is counted from here, as the app counts packets. */
    private static volatile long captureStartedAt;

    /** The last diagnostics test call, for 'X' 'R'. */
    private static volatile String testState = "none";
    private static volatile int testRoute = -1;
    private static volatile long testRealMs;
    private static volatile long testAudioMs;
    private static volatile int testToneRun;
    private static volatile int testToneBest;
    private static volatile int testMuteHeld = -1;
    private static volatile int testVoice = -1;
    /** 0 when the test's greeting could not be played at all. */
    private static volatile int testPlayed = -1;
    /** Chunks of about 21 ms in a row with the check tone: 8 is ~170 ms. */
    private static final int TONE_CHUNKS = 8;
    /** The check tone counts above -50 dBFS, and as most of the chunk. */
    private static final double TONE_MIN_RMS = 32768 * Math.pow(10, -50 / 20.0);
    private static final double TONE_MIN_SHARE = 0.5;

    /** One applyTracking at a time - the start-up thread's and the app's 'T'
     *  can overlap - so the last to run applies what trackingOn says by then,
     *  not what it said before the other ran. Not Main.class: that is held
     *  at call start and end, and these take a moment. */
    private static final Object TRACKING_LOCK = new Object();

    private static void applyTracking() {
        synchronized (TRACKING_LOCK) {
            String mode = trackingOn ? "allow" : "default";
            runQuietly("cmd", "appops", "set", "--uid", APP_PACKAGE, "MANAGE_ONGOING_CALLS", mode);
            runQuietly("cmd", "appops", "set", APP_PACKAGE, "MANAGE_ONGOING_CALLS", mode);
            Ln.i("tracking: " + (trackingOn ? "on - Telecom may bind the app's call service" : "off"));
        }
    }

    /** At each heartbeat: still allowed, if it should be? */
    private static void ensureTracking() {
        if (!trackingOn) {
            return;
        }
        String now = runQuietly("cmd", "appops", "get", "--uid", APP_PACKAGE, "MANAGE_ONGOING_CALLS");
        if (!uidAllowed(now)) {
            Ln.w("tracking: the permission had been withdrawn (" + now + "); allowing it again");
            applyTracking();
        }
    }

    /** See Checks.uidAllowed. */
    static boolean uidAllowed(String said) {
        return Checks.uidAllowed(said);
    }

    /** Run a command, return what it printed, or null if it could not run. */
    private static String runQuietly(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String said = slurp(process);
            process.waitFor();
            return said;
        } catch (Throwable t) {
            Ln.w("could not run " + String.join(" ", command) + ": " + t);
            return null;
        }
    }

    /** Messenger calls: armed from the environment, if it names any apps. */
    private static void startVoipWatcher(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        voipHandler = new Handler(Looper.getMainLooper());
        voipAudio = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
        configureVoip(Voip.parse(System.getenv(VOIP_ENV)));
    }

    private static void configureVoip(Set<String> apps) {
        if (voipHandler == null || voipAudio == null) {
            Ln.w("voip: no watcher (no token), ignoring the app list");
            return;
        }
        Voip.configure(apps, voipHandler, voipAudio, VOIP_LISTENER);
    }

    /** Reading the command must not be able to hang the accept loop forever. */
    private static final int COMMAND_TIMEOUT_MS = 5000;

    /**
     * One call's recording, written straight to a file by the daemon.
     *
     * This is the same capture and encoder the app used to drive over the
     * socket, pointed at a FileDescriptor instead. The framed format the
     * Streamer writes is exactly what the app's reader already understands, so
     * the file IS the stream, just complete and at rest - the app muxes it to
     * .ogg later, offline, with no chance of losing the tail to a stall.
     */
    private static final class RecordingSession {
        final String name;
        final boolean incoming;
        final long startedAt;
        /** The messenger whose call this is, or null for a phone call. */
        final String app;
        private final File file;
        private final FileOutputStream out;
        private final AsyncProcessor recorder;

        private RecordingSession(String name, boolean incoming, long startedAt, String app, File file,
                                 FileOutputStream out, AsyncProcessor recorder) {
            this.name = name;
            this.incoming = incoming;
            this.app = app;
            this.startedAt = startedAt;
            this.file = file;
            this.out = out;
            this.recorder = recorder;
        }

        static RecordingSession start(boolean incoming, String app) throws Exception {
            // in/out is baked into the name so an orphan collected after a
            // reboot - when the daemon no longer remembers the call - still
            // knows which it was. A messenger call carries its app instead:
            // which way it went is not known here.
            long startedAt = System.currentTimeMillis();
            String name = REC_PREFIX + startedAt
                    + (app != null ? VOIP_TAG + app : incoming ? "_in" : "_out") + REC_SUFFIX;
            // A phone call this process answered, through the level tap: the
            // answering machine and its diagnostics look at what is recorded,
            // never change it. It answered before the off-hook that starts
            // this, so it is known now; any other call is not looked at.
            AnswerMachine machine = answerMachine;
            AudioCapture capture = app != null ? Voip.newCapture()
                    : machine != null && machine.answeredThisCall()
                    ? new LevelTap(phoneCapture(), Main::onChunk)
                    : phoneCapture();
            if (capture == null) {
                throw new IllegalStateException("messenger calls are not armed");
            }
            File file = new File(REC_DIR, name);
            FileOutputStream out = new FileOutputStream(file);
            // Fails fast and loudly on a device whose HAL will not give us this
            // source, rather than writing a silent or one-sided file.
            capture.checkCompatibility();
            Streamer streamer = new Streamer(out.getFD(), AUDIO_CODEC, SEND_CODEC_META, SEND_FRAME_META);
            AsyncProcessor recorder = new AudioEncoder(capture, streamer, BIT_RATE, null, null);
            recorder.start(fatalError -> {
                if (fatalError) {
                    Ln.e("recording: encoder terminated with an error");
                }
            });
            Ln.i("recording: started -> " + name);
            return new RecordingSession(name, incoming, startedAt, app, file, out, recorder);
        }

        void stop() {
            try {
                recorder.stop();
                recorder.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Ln.w("recording: error while stopping the encoder: " + t.getMessage());
            }
            closeQuietly(out);
            Ln.i("recording: stopped -> " + name + " (" + file.length() + " bytes)");
        }
    }

    /** A phone call's capture: VOICE_CALL at once, switched to the messenger
     *  capture if the call plays through the mixer (PhoneCapture). */
    private static AudioCapture phoneCapture() {
        return new PhoneCapture(new AudioDirectCapture(AUDIO_SOURCE), callAudio);
    }

    /** Start capturing the current call to a file. Returns its name, or null if
     *  it could not start or one is already running. Does NOT wake the app. */
    private static synchronized String beginRecording(boolean incoming, String app) {
        if (activeRecording != null) {
            Ln.w("recording: a call is already being recorded");
            return activeRecording.name;
        }
        try {
            captureStartedAt = 0;
            activeRecording = RecordingSession.start(incoming, app);
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
    private static void onCallStart(boolean incoming) {
        onCallStart(incoming, null);
    }

    /**
     * A call starting - a phone call ([app] null), or a messenger call in
     * [app]. One at a time: a phone call during a messenger call, or the other
     * way round, leaves the first one's recording running and is not recorded
     * itself, and neither one's end can stop the other's recording.
     */
    private static void onCallStart(boolean incoming, String app) {
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
            AnswerMachine machine = answerMachine;
            if (app == null && machine != null && machine.takingMessage()) {
                // A call this process answered: the recording IS the message,
                // whatever the mode - switching the answering machine on is
                // the consent to record one. No start-of-call wake: the
                // message's own (MESSAGE_STARTED) follows with the greeting.
                // Not once the user took the call: an off-hook then - a
                // waiting call - is theirs, asked about as in any call.
                beginRecording(incoming, null);
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
                String name = beginRecording(incoming, app);
                if (name != null) {
                    // The app shows a "recording" notification; no audio needed.
                    wake(ACTION_CALL_STARTED, incoming, name, app);
                }
                break;
            case MODE_ON_DEMAND:
                RecordingSession running = activeRecording;
                if (running != null) {
                    // Already said yes to, and recording: a second off-hook
                    // in the same call (call waiting) is not a new question -
                    // asked again, "Not now" would stop nothing.
                    wake(ACTION_CALL_STARTED, incoming, running.name, app);
                    break;
                }
                // Nothing is captured yet. Ask; an empty rec-file says "prompt".
                Ln.i("recording: on-demand, asking before recording");
                wake(ACTION_CALL_STARTED, incoming, "", app);
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
    private static void splitOffMessage(AnswerMachine.Outcome part) {
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
                File file = new File(REC_DIR, rec.name);
                if (part.speechAt == 0) {
                    Ln.i("answer: the call was taken before a word was left; " + rec.name + " dropped -> "
                            + file.delete());
                } else {
                    String renamed = Checks.messageRecfile(rec.name, part.messageStart(), captureStartedAt, false,
                            false);
                    if (file.renameTo(new File(REC_DIR, renamed))) {
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
        wake(ACTION_CALL_STARTED, callIncoming, "", null);
    }

    private static synchronized void stopRecording(AnswerMachine.Outcome outcome) {
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
            boolean gone = new File(REC_DIR, name).delete();
            Ln.i("answer: test call over, its recording deleted -> " + gone);
            activeRecording = null;
            wake(ACTION_CALL_ENDED, rec.incoming, "", null,
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
            if (new File(REC_DIR, name).renameTo(new File(REC_DIR, renamed))) {
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
        wake(ACTION_CALL_ENDED, rec.incoming, name, rec.app);
    }

    /** Call end handling. A recording that ran is finished and handed over; an
     *  on-demand call that was never said yes to just tells the app to clear its
     *  prompt, with nothing to fetch. */
    private static void onCallEnd() {
        if (callVoipApp != null) {
            Ln.i("callwatch: a phone call ended during a messenger call; that recording carries on");
            return;
        }
        endCall();
    }

    /** A messenger call's end. Only ends a messenger call. */
    private static void onVoipCallEnd() {
        if (callVoipApp == null) {
            return;
        }
        endCall();
        // A phone call answered during the messenger call - which is what
        // ended it - was turned away while that one ran; it is the call now.
        CallWatch watch = callWatch;
        if (watch != null && watch.phoneCallUp()) {
            Ln.i("recording: the messenger call gave way to a phone call; recording that");
            onCallStart(watch.phoneCallRang(), null);
        }
    }

    private static void endCall() {
        boolean wasRecording;
        String app;
        // The answering machine first: it gives the microphone back, and says
        // what became of the message. Only a phone call is ever its business.
        AnswerMachine machine = answerMachine;
        AnswerMachine.Outcome outcome = callVoipApp == null && machine != null ? machine.callEnded() : null;
        if (outcome == null) {
            // Not a call this process answered: a mute marker now is one a
            // predecessor left, kept while this call was up.
            giveBackStaleMute();
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
            wasRecording = activeRecording != null;
            if (wasRecording) {
                stopRecording(outcome);
            }
        }
        if (outcome != null && outcome.test) {
            // Recorded or not: its results are complete.
            testState = "done";
        }
        if (!wasRecording && recordingMode != MODE_OFF) {
            wake(ACTION_CALL_ENDED, callIncoming, "", app);
        }
    }

    /**
     * The hand-off at hang-up is the one that carries the recording, and it
     * has failed: measured 2026-09-10 19:40, `am start-foreground-service`
     * exited 255 for CALL_ENDED seconds after exiting 0 for CALL_STARTED of
     * the same call, and the finished file sat here until the app next
     * happened to open and its reconcile collected it. WHY it failed is not
     * known, because this logged the exit code and threw the output away -
     * so am's output is kept now, and the wake is tried a few times before
     * the reconcile is left to pick up the pieces.
     */
    private static final int WAKE_ATTEMPTS = 3;
    private static final long WAKE_RETRY_MS = 2000L;
    /** How long one `am` may take: one that hangs must not hold up for good
     *  the wakes queued behind it. */
    private static final long WAKE_TIMEOUT_MS = 10_000L;

    /**
     * The wakes, one at a time, in the order they were asked for. Each used
     * to have a thread and an `am` of its own, so two of them a moment apart
     * - a message's start and its call's end, a Record prompt and the end
     * that takes it down - could reach the app the other way round, and leave
     * "Taking a message", or the prompt, up for a call that was over.
     */
    private static final java.util.concurrent.ExecutorService WAKES =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "jemrec-wake");
                thread.setDaemon(true);
                return thread;
            });

    private static final java.util.concurrent.ExecutorService CABLE_WAKES =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "jemrec-cable-wake");
                thread.setDaemon(true);
                return thread;
            });

    /** Off the main thread: `am` takes a moment, and the looper must not wait for it. */
    private static void wake(String action, boolean incoming, String recfile, String app, String... extras) {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.Arrays.asList(
                "am", "start-foreground-service",
                "-n", APP_SERVICE,
                "-a", action,
                "--es", "token", appToken,
                "--es", "recfile", recfile == null ? "" : recfile,
                "--ez", "incoming", String.valueOf(incoming)));
        if (app != null) {
            // A messenger call: which one, for the app's notification.
            command.add("--es");
            command.add("app");
            command.add(app);
        }
        command.addAll(java.util.Arrays.asList(extras));
        long seq = callSeq;
        boolean start = ACTION_CALL_STARTED.equals(action) || ACTION_MESSAGE_STARTED.equals(action)
                || ACTION_MESSAGE_TAKEN.equals(action);
        // A cable's wake has its own queue: it has three seconds before adbd
        // goes, and must not wait them out behind a call's wake whose `am`
        // is slow (exit 255 has been seen; each try may take ten seconds).
        // It is ordered against nothing a call's wakes are.
        (ACTION_CABLE.equals(action) ? CABLE_WAKES : WAKES).execute(() -> {
            for (int attempt = 1; attempt <= WAKE_ATTEMPTS; attempt++) {
                if (start && endedSeq >= seq) {
                    // That call is over, and its CALL_ENDED is queued behind
                    // this: a start landing now would put up a prompt, a
                    // "Recording" or a "Taking a message" for a call that has
                    // ended - until the next one did.
                    Ln.i("wake: the call is over, not sending " + action);
                    return;
                }
                AnswerMachine machine = answerMachine;
                if (ACTION_MESSAGE_STARTED.equals(action) && (machine == null || !machine.takingMessage())) {
                    // Taken over already: the wake queued behind this one
                    // tells the app so.
                    Ln.i("wake: the message is over, not sending " + action);
                    return;
                }
                try {
                    Process process = new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .start();
                    // Its few lines of output wait in the pipe meanwhile.
                    if (!process.waitFor(WAKE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        Ln.w("wake: " + action + " (" + recfile + ") -> no answer in "
                                + WAKE_TIMEOUT_MS / 1000 + " s on attempt " + attempt);
                    } else {
                        String said = slurp(process);
                        int exit = process.exitValue();
                        if (exit == 0) {
                            Ln.i("wake: " + action + " (" + recfile + ") -> exit 0"
                                    + (attempt > 1 ? " on attempt " + attempt : ""));
                            return;
                        }
                        Ln.w("wake: " + action + " (" + recfile + ") -> exit " + exit
                                + " on attempt " + attempt + ": " + said);
                    }
                } catch (Throwable t) {
                    Ln.e("wake: could not wake the app", t);
                }
                sleep(WAKE_RETRY_MS);
            }
        });
    }

    /** Everything a finished child printed, one line, trimmed. */
    private static String slurp(Process process) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) {
                    out.append(" | ");
                }
                out.append(line);
            }
        }
        return out.toString().trim();
    }

    /** A rec-file name the app sent is only ever one of ours in REC_DIR. */
    private static File recFileFor(String name) {
        if (name == null || !name.startsWith(REC_PREFIX) || !name.endsWith(REC_SUFFIX)
                || name.contains("/") || name.contains("..")) {
            return null;
        }
        return new File(REC_DIR, name);
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
                // Not mid-call - recording, ringing, or waiting on an
                // ask-first answer - and not while a file is being fetched:
                // a fetch cut short by the exit reads as a complete, shorter
                // recording. The app asks this to replace an older build, and
                // will ask again; the call is worth more than a prompt update.
                // A ring with an answer on its way counts as mid-call.
                AnswerMachine machine = answerMachine;
                busy = activeRecording != null || callActive || fetches.get() > 0
                        || (machine != null && machine.state() != AnswerMachine.State.IDLE
                            && machine.state() != AnswerMachine.State.RINGING);
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
        if (command == COMMAND_RECORD) {
            // Live capture straight to the socket. Calls no longer use this - the
            // daemon writes them to a file (see startRecording) - but the app's
            // self-test still records a short clip this way to prove the HAL will
            // hand over voice-call audio at all.
            AudioCodec codec = rawMode ? AudioCodec.RAW : AUDIO_CODEC;
            Ln.i("session: live capture, source=" + AUDIO_SOURCE + " codec=" + codec);
            AudioCapture capture = new AudioDirectCapture(AUDIO_SOURCE);
            capture.checkCompatibility();
            ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(client);
            try {
                Streamer streamer = new Streamer(pfd.getFileDescriptor(), codec, SEND_CODEC_META, SEND_FRAME_META);
                AsyncProcessor recorder = rawMode
                        ? new AudioRawRecorder(capture, streamer)
                        : new AudioEncoder(capture, streamer, BIT_RATE, null, null);
                CountDownLatch finished = new CountDownLatch(1);
                recorder.start(fatalError -> finished.countDown());
                finished.await();
                recorder.stop();
                recorder.join();
            } finally {
                closeQuietly(pfd);
            }
            return;
        }
        if (command == COMMAND_LIST) {
            // Every recording still on disk, newest-relevant or not, one name per
            // line. The app fetches these on startup to collect anything a crash
            // or a reboot left behind before it could be saved.
            File dir = new File(REC_DIR);
            java.util.List<String> names = new java.util.ArrayList<>();
            // Under the lock a recording starts under: its file is created
            // before it is the active one, and in between it looked finished.
            synchronized (Main.class) {
                File[] files = dir.listFiles((d, n) -> n.startsWith(REC_PREFIX) && n.endsWith(REC_SUFFIX));
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
                    name = beginRecording(callIncoming, callVoipApp);
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
            trackingOn = value == 1;
            applyTracking();
            client.getOutputStream().write(("TRACK " + (trackingOn ? "on" : "off") + "\n")
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
                    boolean gone = new File(REC_DIR, rec.name).delete();
                    Ln.i("recording: the rules leave this call out; stopped and deleted " + rec.name + " -> " + gone);
                    wake(ACTION_CALL_ENDED, rec.incoming, "", null);
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
            configureVoip(apps);
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
        if (command == COMMAND_FETCH) {
            String name = readName(client);
            File file = recFileFor(name);
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
            File file = recFileFor(name);
            boolean ok = !name.equals(activeName()) && file != null && file.delete();
            Ln.i("session: delete " + name + " -> " + ok);
            client.getOutputStream().write((ok ? "OK\n" : "NO\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_ANSWER_SETTINGS) {
            AnswerSettings settings = AnswerSettings.parse(readName(client));
            answerSettings = settings;
            AnswerMachine machine = answerMachine;
            if (machine != null) {
                onMain(() -> {
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
            AnswerMachine machine = answerMachine;
            if (machine == null) {
                reply(client, "GO none");
                return;
            }
            String state = onMain(() -> {
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
            AnswerMachine machine = answerMachine;
            if (machine == null) {
                reply(client, "HAND none");
                return;
            }
            String state = onMain(() -> {
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
                AnswerMachine machine = answerMachine;
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
                if (!incoming && mayBeEmergencyNumber(number)) {
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
                testState = "armed";
                testRoute = -1;
                testRealMs = 0;
                testAudioMs = 0;
                testToneRun = 0;
                testToneBest = 0;
                testMuteHeld = -1;
                testVoice = -1;
                testPlayed = -1;
                onMain(() -> {
                    machine.armTest(incoming, incoming ? TEST_INCOMING_WINDOW_MS : TEST_OUTGOING_WINDOW_MS);
                    return null;
                });
                if (!incoming) {
                    runQuietly("am", "start", "-a", "android.intent.action.CALL",
                            "-d", "tel:" + number.replace("#", "%23"));
                }
                reply(client, "TEST armed");
                break;
            }
            case 'R':
                reply(client, "RESULT 1 state=" + testState
                        + " route=" + testRoute
                        + " realms=" + testRealMs
                        + " audioms=" + testAudioMs
                        + " tone=" + (testToneBest >= TONE_CHUNKS ? 1 : 0)
                        + " tonechunks=" + testToneBest
                        + " mute=" + testMuteHeld
                        + " voice=" + testVoice
                        + " played=" + testPlayed);
                break;
            case 'F':
                client.setSoTimeout(60_000);
                reply(client, "FWD " + Forwarding.readNoReply(phone));
                break;
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
                reply(client, "FWD " + Forwarding.setNoReply(phone, seconds));
                break;
            }
            default:
                Ln.w("session: unknown diagnostics command " + what);
        }
    }

    /** Three minutes for someone to call. A dialled call goes out within a
     *  second or two; the window is short, because until it closes the next
     *  call this phone places is taken for the test. */
    private static final long TEST_INCOMING_WINDOW_MS = 180_000L;
    private static final long TEST_OUTGOING_WINDOW_MS = 15_000L;

    /** Whether [number] is an emergency number here - or might be: one that
     *  cannot be checked is never dialled for a test either. */
    private static boolean mayBeEmergencyNumber(String number) {
        try {
            return phone.isEmergencyNumber(number);
        } catch (Throwable t) {
            Ln.w("answer: could not tell whether the test's number is an emergency number: " + t);
            return true;
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
        if (!muteReady) {
            // Not a permission, but as final: no mute, no answering.
            missing.add("mute");
        }
        try {
            // What a ring is refused on when it cannot be read: a phone where
            // it always throws would refuse every call, checks passed or not.
            Voip.callingUids(callAudio, true);
        } catch (Throwable t) {
            missing.add("calls");
        }
        line.append(" perms=").append(missing.isEmpty() ? "ok" : String.join(",", missing));
        line.append(" incallmusic=").append(Checks.inCallMusic(dump("media.audio_policy")));
        boolean interceptable = false;
        try {
            interceptable = (boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(callAudio);
        } catch (Throwable ignored) {
            // Not there before Android 13, or not allowed.
        }
        line.append(" screening=").append(interceptable ? 1 : 0);
        line.append(" greeting=").append(Greeting.present() ? Greeting.FILE.length() : 0);
        line.append(" route=").append(answerSettings.route);
        line.append(" sdk=").append(Build.VERSION.SDK_INT);
        int voice = -1;
        try {
            voice = phone.getVoiceNetworkType();
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

    /**
     * What the answering machine does to the phone. Every method runs on the
     * main looper, where the call watch delivers its events; the greeting's
     * callbacks come from its own thread and are posted back here.
     */
    private static final class AnswerEffects implements AnswerMachine.Effects {

        @Override
        public long now() {
            // Not the wall clock: a time correction mid-call would move a
            // message's start, or stretch a timer, by the size of the step.
            return SystemClock.elapsedRealtime();
        }

        @Override
        public Object schedule(Runnable task, long delayMs) {
            mainHandler.postDelayed(task, delayMs);
            return task;
        }

        @Override
        public void cancel(Object handle) {
            mainHandler.removeCallbacks((Runnable) handle);
        }

        @Override
        public boolean callUp() {
            CallWatch watch = callWatch;
            return watch != null && watch.phoneCallUp();
        }

        @Override
        public boolean otherAppCallUp() {
            if (callVoipApp != null) {
                // A messenger call this process is recording.
                return true;
            }
            try {
                // The mode is the VoIP app's while its call runs - but a phone
                // call ringing over it may hand the mode to the ringtone, so
                // the app playing and capturing call audio is asked too.
                // The shell's own uid counts too: this process never plays call
                // audio, and the emulator check's stand-in call runs as the shell.
                return callAudio.getMode() == AudioManager.MODE_IN_COMMUNICATION
                        || !Voip.callingUids(callAudio, true).isEmpty();
            } catch (Throwable t) {
                Ln.w("answer: could not tell whether another app is in a call: " + t);
                return true;
            }
        }

        @Override
        public boolean emergencyCallback() {
            try {
                // A SystemApi under READ_PRIVILEGED_PHONE_STATE, the shell's.
                return (boolean) TelephonyManager.class.getMethod("getEmergencyCallbackMode").invoke(phone);
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public boolean greetingPresent() {
            return Greeting.present();
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean answer() {
            if (!muteReady) {
                Ln.w("answer: the microphone cannot be muted from here; not answering");
                return false;
            }
            try {
                telecom.acceptRingingCall();
                return true;
            } catch (Throwable t) {
                Ln.e("answer: acceptRingingCall failed", t);
                return false;
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void hangUp() {
            try {
                Ln.i("answer: endCall -> " + telecom.endCall());
            } catch (Throwable t) {
                Ln.e("answer: endCall failed", t);
            }
        }

        @Override
        public boolean audioInCall() {
            return callAudio.getMode() == AudioManager.MODE_IN_CALL;
        }

        @Override
        public boolean callAudioUp() {
            int mode = callAudio.getMode();
            return mode == AudioManager.MODE_IN_CALL || Checks.phoneCallInMixer(mode, Build.VERSION.SDK_INT);
        }

        @Override
        public void setMuted(boolean on) {
            if (on) {
                // The marker first: a death between the two leaves a marker
                // for a mute that never happened, which costs an unneeded
                // unmute at the next start. The other order could leave a
                // muted phone and nothing to say so. A marker that cannot be
                // left does not stop the mute: the caller hearing the room is
                // worse, and the phone clears the mute as the call ends.
                try {
                    new FileOutputStream(MUTE_MARKER).close();
                } catch (Throwable t) {
                    Ln.e("answer: could not leave the mute marker", t);
                }
            }
            try {
                callAudio.setMicrophoneMute(on);
                if (!on) {
                    MUTE_MARKER.delete();
                }
                AnswerMachine machine = answerMachine;
                if (on && machine != null && machine.testing()) {
                    // A mute that did not take ends the test there (the
                    // machine hangs up): this is how the test says why.
                    testMuteHeld = callAudio.isMicrophoneMute() ? 1 : 0;
                }
                Ln.i("answer: microphone " + (on ? "muted" : "unmuted") + " -> " + callAudio.isMicrophoneMute());
            } catch (Throwable t) {
                Ln.e("answer: could not " + (on ? "mute" : "unmute") + " the microphone", t);
            }
        }

        @Override
        public boolean muted() {
            return callAudio.isMicrophoneMute();
        }

        @Override
        public void playGreeting(boolean checkTone) {
            AnswerMachine machine = answerMachine;
            String route = answerSettings.route;
            greetingPlayer.play(callAudio, route, checkTone, Greeting.FILE, new GreetingPlayer.Listener() {
                @Override
                public void checkToneDone() {
                    mainHandler.post(machine::checkToneDone);
                }

                @Override
                public void done(long realMs, long audioMs) {
                    mainHandler.post(() -> {
                        if (machine.testing()) {
                            // Not the mute again: the one that matters was
                            // read back as it was asked for (setMuted), and
                            // one lost since would have been taken for the
                            // user taking the call, which stops the greeting
                            // before this. Unmuted now, at the beep, is the
                            // user's own tap - not a mute that failed.
                            testRealMs = realMs;
                            testAudioMs = audioMs;
                            testPlayed = 1;
                        }
                        machine.greetingDone();
                    });
                }

                @Override
                public void failed(String why) {
                    mainHandler.post(() -> {
                        if (machine.testing()) {
                            testPlayed = 0;
                        }
                        machine.greetingFailed();
                    });
                }
            });
            if (checkTone) {
                // Two seconds in, the greeting's track is up: where did it go?
                mainHandler.postDelayed(Main::checkTestRoute, GREETING_ROUTE_CHECK_MS);
            }
        }

        @Override
        public void stopGreeting() {
            greetingPlayer.stop();
        }

        @Override
        public boolean greetingLoud() {
            return ownSideSounds && greetingPlayer.loudNow();
        }

        @Override
        public void messageStarted() {
            AnswerMachine machine = answerMachine;
            boolean test = machine != null && machine.testing();
            if (test) {
                testState = "running";
                try {
                    testVoice = phone.getVoiceNetworkType();
                } catch (Throwable t) {
                    testVoice = -1;
                }
            }
            // Only that a message is under way: where it starts, the
            // recording's name says at the end.
            wake(ACTION_MESSAGE_STARTED, true, "", null, "--ez", "test", String.valueOf(test));
        }

        @Override
        public void callTaken() {
            AnswerMachine machine = answerMachine;
            if (recordingMode == MODE_ON_DEMAND && machine != null && !machine.testing()) {
                // Ask first: the message ends where the user took the call,
                // and the call is theirs to record or not - asked, as at the
                // start of any call. Answering machines have always stopped
                // recording when picked up. Off the looper: stopping the
                // encoder can take a moment.
                AnswerMachine.Outcome part = machine.messageSoFar();
                new Thread(() -> splitOffMessage(part), "jemrec-split").start();
                return;
            }
            wake(ACTION_MESSAGE_TAKEN, true, "", null);
        }

        @Override
        public void log(String message) {
            Ln.i(message);
        }
    }

    private static final long GREETING_ROUTE_CHECK_MS = 3_000L;

    /** The test's greeting, mid-way: is it on the output the caller hears? */
    private static void checkTestRoute() {
        new Thread(() -> {
            String flinger = dump("media.audio_flinger");
            int on = Checks.trackOutput(flinger, android.os.Process.myPid());
            testRoute = AnswerSettings.ROUTE_MUSIC.equals(answerSettings.route) ? on : -1;
            Ln.i("answer: test greeting on the in-call music output: "
                    + (on == 1 ? "yes" : on == 0 ? "no" : "cannot tell"));
        }, "jemrec-route-check").start();
    }

    /** This phone's own side of the recording - the uplink, left - is not
     *  silent in the chunk being looked at. Muted, it is silent unless the
     *  phone mixes the greeting into the recording: see greetingLoud. */
    private static volatile boolean ownSideSounds;

    /** Every captured chunk of a phone call this process answered: a level
     *  for the message's start, and in a test, a look for the check tone on
     *  the uplink side (left). */
    // testToneRun++ below is not atomic, and need not be: only this thread
    // counts, and the TEST command zeroes it only while no test is running.
    @SuppressWarnings("NonAtomicVolatileUpdate")
    private static void onChunk(short[] pcm, int frames, long atMs) {
        long chunkMs = frames * 1000L / AudioConfig.SAMPLE_RATE;
        if (captureStartedAt == 0) {
            captureStartedAt = atMs - chunkMs;
        }
        AnswerMachine machine = answerMachine;
        if (machine == null || !machine.answeredThisCall()) {
            return;
        }
        double rms = Checks.rms(pcm, AudioConfig.CHANNELS, frames);
        // Before the level is looked at: greetingLoud asks it.
        ownSideSounds = Checks.channelRms(pcm, AudioConfig.CHANNELS, 0, frames) >= AnswerMachine.SOUND_RMS;
        machine.level(rms, chunkMs);
        if (machine.testing()) {
            double share = Checks.toneShare(pcm, AudioConfig.CHANNELS, 0, frames, GreetingPlayer.CHECK_TONE_HZ,
                    AudioConfig.SAMPLE_RATE);
            if (share >= TONE_MIN_SHARE && rms >= TONE_MIN_RMS) {
                testToneRun++;
                testToneBest = Math.max(testToneBest, testToneRun);
            } else {
                testToneRun = 0;
            }
        }
    }

    /** A marker left mid-message - by this process's predecessor, dead: the
     *  microphone may still be muted. Give it back. False while a call is up
     *  and the mute is left for its end; true when nothing is left to do. */
    private static boolean giveBackStaleMute() {
        if (!MUTE_MARKER.exists() || callAudio == null) {
            return true;
        }
        try {
            int mode = callAudio.getMode();
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                // A call is up: the message this mute was for, its caller
                // still on - muted, the room stays out - or a call of the
                // user's, muted by them. Not this process's to unmute: the
                // marker is looked at again as that call ends (endCall).
                Ln.i("answer: a mute left by a message, and a call up; the microphone stays until it ends");
                return false;
            }
        } catch (Throwable ignored) {
            // Cannot tell: as before, give it back.
        }
        try {
            callAudio.setMicrophoneMute(false);
            Ln.w("answer: the microphone was left muted by a message; unmuted it");
        } catch (Throwable t) {
            Ln.e("answer: could not unmute the microphone a previous run muted", t);
        }
        MUTE_MARKER.delete();
        return true;
    }

    /** The whole of `dumpsys <service>`, newlines kept, for the parsers;
     *  null if it could not be run - not an answer to read as "none". */
    private static String dump(String service) {
        try {
            Process process = new ProcessBuilder("dumpsys", service).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            try (java.io.InputStream in = process.getInputStream()) {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            process.waitFor();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Ln.w("could not run dumpsys " + service + ": " + t);
            return null;
        }
    }

    /** Run [task] on the main looper, where the answering machine lives, and
     *  wait for it. */
    private static <T> T onMain(java.util.concurrent.Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.call();
        }
        java.util.concurrent.FutureTask<T> future = new java.util.concurrent.FutureTask<>(task);
        mainHandler.post(future);
        return future.get(2, TimeUnit.SECONDS);
    }

    /** The name of the file being recorded into, or null. */
    private static String activeName() {
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
                + " watching=" + (watching ? 1 : 0)
                + " looper=" + (looperResponds() ? "ok" : "stuck")
                + " mode=" + recordingMode
                + " call=" + (callActive ? 1 : 0)
                + " events=" + callEvents.get()
                + " lastevent=" + lastCallEventAt
                + " offhooks=" + offHooks.get()
                + " lastoffhook=" + lastOffHookAt
                + " recording=" + (rec == null ? 0 : rec.startedAt)
                + " voip=" + Voip.apps().size()
                + " voiparmed=" + (Voip.armed() ? 1 : 0)
                + " tracking=" + (trackingOn ? 1 : 0)
                + " answer=" + (answerSettings.on ? 1 : 0)
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
            jar = JAR_PATH;
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

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // Nothing useful to do, and the caller is already on its way out.
            }
        }
    }
}
