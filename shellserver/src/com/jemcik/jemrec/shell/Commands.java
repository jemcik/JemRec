package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioEncoder;
import com.genymobile.scrcpy.audio.AudioRawRecorder;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The commands the app sends once the handshake has proved it, and the
 * reply to each (see LoopbackServer for the connection itself).
 */
final class Commands {

    private Commands() {
    }

    static final int COMMAND_PING = 'P';
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

    /**
     * The rules on whose calls to keep said no to a phone call while it was
     * still ringing or being dialled (the app's CallTracker knows the number
     * by then): do not record it. A recording that started a moment ago for
     * it - dialling takes the phone off-hook at once - is stopped and deleted.
     */
    private static final int COMMAND_SKIP = 'K';

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

    /**
     * The last call's report, for Copy a report in the app's Diagnostics: see
     * CallReport. Kept in a file as well, from the moment its recording
     * stops, so a recorder started again since - an update, a revive -
     * still has it.
     */
    private static final int COMMAND_REPORT = 'C';
    static void run(Socket client) throws Exception {
        int command = client.getInputStream().read();
        if (command == COMMAND_PING) {
            pong(client);
            return;
        }
        if (command == COMMAND_QUIT) {
            boolean busy;
            synchronized (Main.LOCK) {
                // The app asks this to replace an older build, and will ask
                // again; the call is worth more than a prompt update.
                busy = Main.busyLocked();
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
                LoopbackServer.refuse(client, "debugging off without a 0 or 1 (" + go + ")");
                return;
            }
            String reply;
            synchronized (Main.LOCK) {
                if (go == 0 && DebuggingOff.switchingOff) {
                    reply = "OFF TOO LATE";
                } else if (go == 0) {
                    DebuggingOff.goingOff = false;
                    reply = "OFF CANCELLED";
                } else {
                    DebuggingOff.goingOff = true;
                    reply = Main.busyLocked() ? "OFF AFTER CALL" : "OFF NOW";
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
            AudioCodec codec = Main.rawMode ? AudioCodec.RAW : RecordingSession.AUDIO_CODEC;
            Ln.i("session: live capture, source=" + RecordingSession.AUDIO_SOURCE + " codec=" + codec);
            AudioCapture capture = new AudioDirectCapture(RecordingSession.AUDIO_SOURCE);
            capture.checkCompatibility();
            ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(client);
            try {
                Streamer streamer = new Streamer(pfd.getFileDescriptor(), codec, RecordingSession.SEND_CODEC_META, RecordingSession.SEND_FRAME_META);
                AsyncProcessor recorder = Main.rawMode
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
            synchronized (Main.LOCK) {
                File[] files = dir.listFiles((d, n) -> n.startsWith(Paths.REC_PREFIX) && n.endsWith(Paths.REC_SUFFIX));
                if (files != null) {
                    // Skip the one being written right now: it is not finished.
                    String activeName = Main.activeName();
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
            if (value >= Main.MODE_OFF && value <= Main.MODE_ON_DEMAND) {
                Main.recordingMode = value;
                Ln.i("session: recording mode = " + Main.recordingMode);
            } else {
                // Not a mode. Keep the one in force rather than guess - and
                // never guess AUTOMATIC, which is what this used to do.
                Ln.w("session: ignoring unusable recording mode " + value + ", still " + Main.recordingMode);
            }
            client.getOutputStream().write((Main.recordingMode + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return;
        }
        if (command == COMMAND_START) {
            // The user said yes to the on-demand start-of-call prompt. Begin
            // recording the call in progress, from now.
            String name = null;
            // Locked against onCallEnd - see there.
            synchronized (Main.LOCK) {
                if (Main.callActive && Main.recordingMode != Main.MODE_OFF) {
                    name = Main.beginRecording(Main.callIncoming, Main.callVoipApp, Main.callAppIncoming);
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
            synchronized (Main.LOCK) {
                RecordingSession rec = Main.activeRecording;
                if (value != 1) {
                    Main.skipUntil = 0;
                    what = "cancelled";
                } else if (rec != null && rec.app == null
                        && System.currentTimeMillis() - rec.startedAt < Main.SKIP_CURRENT_MS) {
                    // Dialling went off-hook before the app could say: stop
                    // it now, and leave nothing behind.
                    rec.stop();
                    Main.activeRecording = null;
                    boolean gone = new File(Paths.REC_DIR, rec.name).delete();
                    Ln.i("recording: the rules leave this call out; stopped and deleted " + rec.name + " -> " + gone);
                    Wakes.wake(Wakes.ACTION_CALL_ENDED, rec.incoming, "", null);
                    what = "current";
                } else {
                    Main.skipUntil = System.currentTimeMillis() + Main.SKIP_VALID_MS;
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
            if (name.equals(Main.activeName())) {
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
            Main.fetches.incrementAndGet();
            try (FileInputStream in = new FileInputStream(file)) {
                OutputStream os = client.getOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                Main.fetches.decrementAndGet();
            }
            return;
        }
        if (command == COMMAND_DELETE) {
            String name = readName(client);
            File file = Paths.recFileFor(name);
            boolean ok = !name.equals(Main.activeName()) && file != null && file.delete();
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

    static void pong(Socket client) throws IOException {
        LoopbackServer.pings.incrementAndGet();
        client.getOutputStream().write(
                ("PONG " + LoopbackServer.PROTOCOL_VERSION + " " + Main.BUILD + "\n").getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().flush();
    }

    /** The answer to COMMAND_WATCH. Times are wall-clock millis, 0 for never. */
    private static String watchReport() {
        RecordingSession rec = Main.activeRecording;
        return "WATCH " + WATCH_FORMAT
                + " since=" + Main.STARTED_AT
                + " watching=" + (CallWatch.watching ? 1 : 0)
                + " looper=" + (looperResponds() ? "ok" : "stuck")
                + " mode=" + Main.recordingMode
                + " call=" + (Main.callActive ? 1 : 0)
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
