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
import com.genymobile.scrcpy.audio.AudioSource;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
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
import java.util.concurrent.CountDownLatch;
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
     * audio HAL rather than of this code. See the README's 30-second check.
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
    @SuppressLint("DiscouragedPrivateApi")
    private static void prepareMainLooper() {
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
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

            startCallWatcher(token);

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
            Handler handler = new Handler(Looper.getMainLooper());
            telephony.registerTelephonyCallback(
                    handler::post, new CallWatch(token, telephony, audio, handler));
            Ln.i("callwatch: watching call state as uid " + android.os.Process.myUid());
        } catch (Throwable t) {
            Ln.e("callwatch: could not register for call state", t);
        }
    }

    private static final class CallWatch extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {

        private final String token;
        private final TelephonyManager telephony;
        private final AudioManager audio;
        private final Handler handler;
        private boolean sawRinging;
        private boolean inCall;
        private Runnable pendingIdle;

        CallWatch(String token, TelephonyManager telephony, AudioManager audio, Handler handler) {
            this.token = token;
            this.telephony = telephony;
            this.audio = audio;
            this.handler = handler;
        }

        @Override
        public void onCallStateChanged(int state) {
            switch (state) {
                case TelephonyManager.CALL_STATE_RINGING:
                    sawRinging = true;
                    Ln.i("callwatch: ringing");
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    cancelPendingIdle();
                    inCall = true;
                    Ln.i("callwatch: off-hook (" + (sawRinging ? "incoming" : "outgoing") + ")");
                    onCallStart(sawRinging);
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    onIdle();
                    break;
                default:
                    break;
            }
        }

        /**
         * AN IDLE IS A CLAIM, NOT A FACT.
         *
         * Ending a recording is destructive - the app closes the socket, the
         * encoder dies of ECONNRESET mid-frame, and whatever was left of the
         * call is gone - so it must not be done on one unconfirmed signal.
         *
         * Measured on this phone: a twenty-second outgoing call recorded six
         * seconds. The daemon's own log shows off-hook, the client connecting
         * on VOICE_CALL, and then "idle" arriving while the call was still up,
         * followed immediately by the encoder's ECONNRESET. The call was not
         * over; the state said it was.
         *
         * So an idle before any off-hook is ignored outright - that one is just
         * the current state being handed over at registration, which used to
         * fire a CALL_ENDED at every daemon start. And a real idle is confirmed
         * against the AUDIO MODE rather than the call state - see below for why.
         */
        private void onIdle() {
            if (!inCall) {
                if (sawRinging) {
                    // It rang and was never answered - missed, or declined.
                    // Forget the ring, or the next OUTGOING call is labelled
                    // incoming: the flag only ever cleared when a call ended.
                    sawRinging = false;
                    Ln.i("callwatch: ringing ended without a call");
                } else {
                    Ln.i("callwatch: idle before any call, ignoring");
                }
                return;
            }
            cancelPendingIdle();
            scheduleEndConfirm();
        }

        /**
         * CONFIRM THE END AGAINST THE AUDIO MODE, AND POLL - DO NOT TRUST ONE
         * IDLE.
         *
         * Measured on this phone: with some dialers (Google Phone here)
         * telephony CALL_STATE goes IDLE partway through a live call and STAYS
         * idle - a 37s call reported idle at 11s, a 30s call at 6s - so a single
         * re-read of getCallState() confirms an end that has not happened, and
         * because the state never leaves idle there is no later edge to correct
         * it. The recording was cut ~25s short, twice.
         *
         * Telecom, though, holds the audio mode at MODE_IN_CALL for the real
         * length of the call: its dumpsys setMode history lines up to the second
         * with the true spans. So the audio mode is the signal to trust, and
         * because telephony gives no second idle edge we POLL it here rather
         * than checking once - re-arming until telecom actually leaves in-call,
         * then ending. A momentary idle with the audio still in-call simply
         * keeps the recording running, which is the whole point.
         */
        private void scheduleEndConfirm() {
            pendingIdle = () -> {
                pendingIdle = null;
                if (audioInCall()) {
                    // Still up. Telephony won't tell us again; keep watching the
                    // audio mode, which will.
                    scheduleEndConfirm();
                    return;
                }
                inCall = false;
                sawRinging = false;
                Ln.i("callwatch: end confirmed (audio mode left in-call)");
                onCallEnd();
            };
            handler.postDelayed(pendingIdle, IDLE_CONFIRM_MS);
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

        private void cancelPendingIdle() {
            if (pendingIdle != null) {
                handler.removeCallbacks(pendingIdle);
                pendingIdle = null;
            }
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

    /**
     * Put back the two things the app changed that outlive it.
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
        for (String path : new String[]{JAR_PATH, LOG_PATH}) {
            try {
                if (new File(path).delete()) {
                    Ln.i("custodian: removed " + path);
                }
            } catch (Throwable t) {
                Ln.w("custodian: could not remove " + path);
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
            checkShield(tick++ % HEARTBEAT_EVERY == 0 ? "heartbeat" : null);
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
                return new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
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

    /** Long enough to outlast a momentary idle, short enough not to
     *  leave a finished call recording into silence. */
    private static final long IDLE_CONFIRM_MS = 2000L;

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
        private final File file;
        private final FileOutputStream out;
        private final AsyncProcessor recorder;

        private RecordingSession(String name, boolean incoming, File file, FileOutputStream out, AsyncProcessor recorder) {
            this.name = name;
            this.incoming = incoming;
            this.file = file;
            this.out = out;
            this.recorder = recorder;
        }

        static RecordingSession start(boolean incoming) throws Exception {
            // in/out is baked into the name so an orphan collected after a
            // reboot - when the daemon no longer remembers the call - still
            // knows which it was.
            String name = REC_PREFIX + System.currentTimeMillis() + (incoming ? "_in" : "_out") + REC_SUFFIX;
            File file = new File(REC_DIR, name);
            FileOutputStream out = new FileOutputStream(file);
            AudioCapture capture = new AudioDirectCapture(AUDIO_SOURCE);
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
            return new RecordingSession(name, incoming, file, out, recorder);
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

    /** Start capturing the current call to a file. Returns its name, or null if
     *  it could not start or one is already running. Does NOT wake the app. */
    private static synchronized String beginRecording(boolean incoming) {
        if (activeRecording != null) {
            Ln.w("recording: a call is already being recorded");
            return activeRecording.name;
        }
        try {
            activeRecording = RecordingSession.start(incoming);
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
        synchronized (Main.class) {
            callActive = true;
            callIncoming = incoming;
        }
        switch (recordingMode) {
            case MODE_AUTOMATIC:
                String name = beginRecording(incoming);
                if (name != null) {
                    // The app shows a "recording" notification; no audio needed.
                    wake(ACTION_CALL_STARTED, incoming, name);
                }
                break;
            case MODE_ON_DEMAND:
                // Nothing is captured yet. Ask; an empty rec-file says "prompt".
                Ln.i("recording: on-demand, asking before recording");
                wake(ACTION_CALL_STARTED, incoming, "");
                break;
            default:
                Ln.i("recording: switched off, not recording this call");
                break;
        }
    }

    private static synchronized void stopRecording() {
        RecordingSession rec = activeRecording;
        if (rec == null) {
            return;
        }
        rec.stop();
        // ONLY NOW. LIST hides the active recording so the app never collects
        // a file that is still being written - and until stop() has returned,
        // it still is. Clearing this first, as it used to, opened a window in
        // which a list from the app's reconcile would have offered it a
        // truncated file, and a truncated file reads as a complete one.
        activeRecording = null;
        // Now the file is complete and at rest, tell the app to come and get it.
        wake(ACTION_CALL_ENDED, rec.incoming, rec.name);
    }

    /** Call end handling. A recording that ran is finished and handed over; an
     *  on-demand call that was never said yes to just tells the app to clear its
     *  prompt, with nothing to fetch. */
    private static void onCallEnd() {
        boolean wasRecording;
        // Under the same lock as an on-demand start, so a Record tap landing as
        // the call ends either starts before the end - and is stopped here - or
        // finds the call over and is refused. Between the two, a recording
        // would start with nothing left to ever stop it: this end has already
        // happened, and the next one is a whole call away.
        synchronized (Main.class) {
            callActive = false;
            wasRecording = activeRecording != null;
            if (wasRecording) {
                stopRecording();
            }
        }
        if (!wasRecording && recordingMode != MODE_OFF) {
            wake(ACTION_CALL_ENDED, callIncoming, "");
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

    /** Off the main thread: `am` takes a moment, and the looper must not wait for it. */
    private static void wake(String action, boolean incoming, String recfile) {
        new Thread(() -> {
            for (int attempt = 1; attempt <= WAKE_ATTEMPTS; attempt++) {
                try {
                    Process process = new ProcessBuilder(
                            "am", "start-foreground-service",
                            "-n", APP_SERVICE,
                            "-a", action,
                            "--es", "token", appToken,
                            "--es", "recfile", recfile == null ? "" : recfile,
                            "--ez", "incoming", String.valueOf(incoming))
                            .redirectErrorStream(true)
                            .start();
                    String said = slurp(process);
                    int exit = process.waitFor();
                    if (exit == 0) {
                        Ln.i("wake: " + action + " (" + recfile + ") -> exit 0"
                                + (attempt > 1 ? " on attempt " + attempt : ""));
                        return;
                    }
                    Ln.w("wake: " + action + " (" + recfile + ") -> exit " + exit
                            + " on attempt " + attempt + ": " + said);
                } catch (Throwable t) {
                    Ln.e("wake: could not wake the app", t);
                }
                sleep(WAKE_RETRY_MS);
            }
        }, "jemrec-wake").start();
    }

    /** Everything a finished child printed, one line, trimmed. */
    private static String slurp(Process process) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
            if (activeRecording != null) {
                // Not mid-call. The app asks this to replace an older build,
                // and will ask again; the call being recorded is worth more
                // than the update being prompt.
                client.getOutputStream().write("BUSY\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                Ln.w("session: quit requested during a recording, refused");
                return;
            }
            client.getOutputStream().write("BYE\n".getBytes("UTF-8"));
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
            File[] files = dir.listFiles((d, n) -> n.startsWith(REC_PREFIX) && n.endsWith(REC_SUFFIX));
            OutputStream os = client.getOutputStream();
            int count = 0;
            if (files != null) {
                // Skip the one being written right now: it is not finished.
                RecordingSession active = activeRecording;
                String activeName = active == null ? null : active.name;
                for (File f : files) {
                    if (f.getName().equals(activeName)) {
                        continue;
                    }
                    os.write((f.getName() + "\n").getBytes("UTF-8"));
                    count++;
                }
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
            client.getOutputStream().write((recordingMode + "\n").getBytes("UTF-8"));
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
                    name = beginRecording(callIncoming);
                }
            }
            if (name != null) {
                Ln.i("session: on-demand start -> " + name);
            } else {
                Ln.w("session: on-demand start refused (no call in progress, or it could not start)");
            }
            client.getOutputStream().write(((name != null ? name : "") + "\n").getBytes("UTF-8"));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_FETCH) {
            String name = readName(client);
            File file = recFileFor(name);
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
            try (FileInputStream in = new FileInputStream(file)) {
                OutputStream os = client.getOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            }
            return;
        }
        if (command == COMMAND_DELETE) {
            String name = readName(client);
            File file = recFileFor(name);
            boolean ok = file != null && file.delete();
            Ln.i("session: delete " + name + " -> " + ok);
            client.getOutputStream().write((ok ? "OK\n" : "NO\n").getBytes("UTF-8"));
            client.getOutputStream().flush();
            return;
        }
        Ln.w("session: unknown command " + command + ", closing");
    }

    private static void pong(Socket client) throws IOException {
        pings.incrementAndGet();
        client.getOutputStream().write(
                ("PONG " + PROTOCOL_VERSION + " " + BUILD + "\n").getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().flush();
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
            try (BufferedReader reader = new BufferedReader(new FileReader(table))) {
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
        return new String(b, "UTF-8");
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
