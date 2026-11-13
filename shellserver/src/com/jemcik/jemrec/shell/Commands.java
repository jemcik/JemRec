package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioEncoder;
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

    /** How long the main looper gets to run a posted no-op before it counts as stuck. */
    private static final long LOOPER_CHECK_MS = 1000L;

    static void run(Socket client) throws Exception {
        int command = client.getInputStream().read();
        if (command == Wire.COMMAND_PING) {
            pong(client);
            return;
        }
        if (command == Wire.COMMAND_QUIT) {
            boolean busy;
            synchronized (Calls.LOCK) {
                // The app asks this to replace an older build, and will ask
                // again; the call is worth more than a prompt update.
                busy = Calls.busyLocked();
            }
            if (busy) {
                Wire.reply(client.getOutputStream(), Wire.BUSY);
                Ln.w("session: quit requested during a call or a fetch, refused");
                return;
            }
            Wire.reply(client.getOutputStream(), Wire.BYE);
            Ln.i("session: quit requested, exiting");
            // Flushed and acknowledged first, so the app learns it worked
            // rather than seeing a dropped connection and having to guess.
            System.exit(0);
        }
        if (command == Wire.COMMAND_DEBUGGING_OFF) {
            int go = client.getInputStream().read();
            if (go != 0 && go != 1) {
                // Only an explicit 1 switches debugging off: a byte that
                // never came reads as -1, and must not count as a go.
                LoopbackServer.refuse(client, "debugging off without a 0 or 1 (" + go + ")");
                return;
            }
            String reply;
            synchronized (Calls.LOCK) {
                if (go == 0 && DebuggingOff.switchingOff) {
                    reply = Wire.OFF_TOO_LATE;
                } else if (go == 0) {
                    DebuggingOff.goingOff = false;
                    reply = Wire.OFF_CANCELLED;
                } else {
                    DebuggingOff.goingOff = true;
                    reply = Calls.busyLocked() ? Wire.OFF_AFTER_CALL : Wire.OFF_NOW;
                }
            }
            Wire.reply(client.getOutputStream(), reply);
            Ln.i("session: debugging off - " + reply);
            if (go == 1) {
                DebuggingOff.goOffWhenFree();
            }
            return;
        }
        if (command == Wire.COMMAND_RECORD) {
            // Live capture straight to the socket. Calls no longer use this - the
            // daemon writes them to a file (see Calls.beginRecording) - but the app's
            // self-test still records a short clip this way to prove the HAL will
            // hand over voice-call audio at all.
            AudioCodec codec = RecordingSession.AUDIO_CODEC;
            Ln.i("session: live capture, source=" + RecordingSession.AUDIO_SOURCE + " codec=" + codec);
            AudioCapture capture = new AudioDirectCapture(RecordingSession.AUDIO_SOURCE);
            capture.checkCompatibility();
            ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(client);
            try {
                Streamer streamer = new Streamer(pfd.getFileDescriptor(), codec, RecordingSession.SEND_CODEC_META, RecordingSession.SEND_FRAME_META);
                AsyncProcessor recorder = new AudioEncoder(capture, streamer, RecordingSession.BIT_RATE, null, null);
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
        if (command == Wire.COMMAND_LIST) {
            // Every recording still on disk, newest-relevant or not, one name per
            // line. The app fetches these on startup to collect anything a crash
            // or a reboot left behind before it could be saved.
            File dir = new File(Paths.REC_DIR);
            java.util.List<String> names = new java.util.ArrayList<>();
            // Under the lock a recording starts under: its file is created
            // before it is the active one, and in between it looked finished.
            synchronized (Calls.LOCK) {
                File[] files = dir.listFiles((d, n) -> n.startsWith(Paths.REC_PREFIX) && n.endsWith(Paths.REC_SUFFIX));
                if (files != null) {
                    // Skip the one being written right now: it is not finished.
                    String activeName = Calls.activeName();
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
        if (command == Wire.COMMAND_SET_ENABLED) {
            int value = client.getInputStream().read();
            if (CallDecisions.isMode(value)) {
                Calls.recordingMode = value;
                Ln.i("session: recording mode = " + Calls.recordingMode);
            } else {
                // Not a mode. Keep the one in force rather than guess - and
                // never guess AUTOMATIC, which is what this used to do.
                Ln.w("session: ignoring unusable recording mode " + value + ", still " + Calls.recordingMode);
            }
            Wire.reply(client.getOutputStream(), Wire.mode(Calls.recordingMode));
            return;
        }
        if (command == Wire.COMMAND_START) {
            // The user said yes to the on-demand start-of-call prompt. Begin
            // recording the call in progress, from now.
            String name = null;
            // Locked against a call's end - see Calls.endCall.
            synchronized (Calls.LOCK) {
                if (CallDecisions.startOnDemand(Calls.callActive, Calls.recordingMode)) {
                    name = Calls.beginRecording(Calls.callIncoming, Calls.callVoipApp, Calls.callAppIncoming);
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
        if (command == Wire.COMMAND_SET_TRACKING) {
            int value = client.getInputStream().read();
            Tracking.trackingOn = value == 1;
            Tracking.applyTracking();
            Wire.reply(client.getOutputStream(), Wire.track(Tracking.trackingOn));
            return;
        }
        if (command == Wire.COMMAND_SKIP) {
            int value = client.getInputStream().read();
            String what;
            synchronized (Calls.LOCK) {
                RecordingSession rec = Calls.activeRecording;
                CallDecisions.Skip skip = CallDecisions.skip(value, rec != null && rec.app == null,
                        rec == null ? 0 : System.currentTimeMillis() - rec.startedAt);
                if (skip == CallDecisions.Skip.CANCELLED) {
                    Calls.skipUntil = 0;
                    what = "cancelled";
                } else if (skip == CallDecisions.Skip.CURRENT && rec != null) {
                    // Dialling went off-hook before the app could say: stop
                    // it now, and leave nothing behind.
                    rec.stop();
                    Calls.activeRecording = null;
                    boolean gone = new File(Paths.REC_DIR, rec.name).delete();
                    Ln.i("recording: the rules leave this call out; stopped and deleted " + rec.name + " -> " + gone);
                    Wakes.wake(Wakes.ACTION_CALL_ENDED, rec.incoming, "", null);
                    what = "current";
                } else {
                    Calls.skipUntil = System.currentTimeMillis() + CallDecisions.SKIP_VALID_MS;
                    what = "next";
                }
            }
            Wire.reply(client.getOutputStream(), Wire.skip(what));
            return;
        }
        if (command == Wire.COMMAND_SET_VOIP) {
            // The messenger apps whose calls to record, comma-separated.
            Set<String> apps = Voip.parse(Wire.readName(client.getInputStream()));
            CallWatch.configureVoip(apps);
            Wire.reply(client.getOutputStream(), Wire.voip(Voip.apps().size(), Voip.armed()));
            return;
        }
        if (command == Wire.COMMAND_WATCH) {
            Wire.reply(client.getOutputStream(), watchReport());
            return;
        }
        if (command == Wire.COMMAND_REPORT) {
            Wire.reply(client.getOutputStream(), Reports.reportText());
            return;
        }
        if (command == Wire.COMMAND_FETCH) {
            String name = Wire.readName(client.getInputStream());
            File file = Paths.recFileFor(name);
            if (name.equals(Calls.activeName())) {
                // Still being written: not to be collected - nor, by a
                // collector that then finds it empty, deleted. Its hang-up
                // brings it.
                Ln.w("session: fetch of the recording in progress, answering gone");
                client.getOutputStream().write(Wire.GONE.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                return;
            }
            if (file == null || !file.isFile()) {
                // Say so, in the slot the codec id would take. The app tells
                // "collected by another path already" from "the file was
                // empty" by it, and only the second is news for the user.
                Ln.w("session: fetch of unknown file '" + name + "', answering gone");
                client.getOutputStream().write(Wire.GONE.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                return;
            }
            Ln.i("session: fetching " + name + " (" + file.length() + " bytes)");
            Calls.fetches.incrementAndGet();
            try (FileInputStream in = new FileInputStream(file)) {
                OutputStream os = client.getOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } finally {
                Calls.fetches.decrementAndGet();
            }
            return;
        }
        if (command == Wire.COMMAND_DELETE) {
            String name = Wire.readName(client.getInputStream());
            File file = Paths.recFileFor(name);
            boolean ok = !name.equals(Calls.activeName()) && file != null && file.delete();
            Ln.i("session: delete " + name + " -> " + ok);
            Wire.reply(client.getOutputStream(), Wire.deleted(ok));
            return;
        }
        if (command == Wire.COMMAND_ANSWER_SETTINGS) {
            AnswerSettings settings = AnswerSettings.parse(Wire.readName(client.getInputStream()));
            AnswerMachine machine = Answering.answerMachine;
            if (machine == null) {
                Answering.answerSettings = settings;
                // A machine made meanwhile - the call watch starting, which
                // configures it from the settings it finds - may have found
                // the ones before these: given these too.
                AnswerMachine made = Answering.answerMachine;
                if (made != null) {
                    Answering.onMain(() -> {
                        made.configure(settings.on, settings.maxMessageSeconds * 1000L);
                        return null;
                    });
                }
            } else {
                // Taken with the machine's own, on its thread: a hop given
                // up on (Hop) takes none of it, where the route, and WATCH's
                // "answer=", changed while the machine stayed as it was.
                Answering.onMain(() -> {
                    Answering.answerSettings = settings;
                    machine.configure(settings.on, settings.maxMessageSeconds * 1000L);
                    return null;
                });
            }
            Ln.i("session: answering machine " + settings.wire());
            reply(client, Wire.answer(settings.wire()));
            return;
        }
        if (command == Wire.COMMAND_GO_AHEAD) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            int delay = in.readInt();
            // When the app saw the ring: eight more bytes, sent since 0.17.
            // On the machine's clock as it comes: SystemClock.elapsedRealtime,
            // since boot, is the same clock in the app and here - and a
            // wall-clock step between the ring and now cannot move it.
            final long rang = in.readLong();
            AnswerMachine machine = Answering.answerMachine;
            if (machine == null) {
                reply(client, Wire.go(null));
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
            reply(client, Wire.go(state));
            return;
        }
        if (command == Wire.COMMAND_HANDOVER) {
            int what = client.getInputStream().read();
            AnswerMachine machine = Answering.answerMachine;
            if (machine == null) {
                reply(client, Wire.hand(null));
                return;
            }
            String state = Answering.onMain(() -> {
                if (what == 'T') {
                    // The notification's Talk, or the app seeing a second call
                    // answered: the machine tells the app (AnswerEffects.callTaken).
                    machine.talk();
                } else if (what == 'H') {
                    machine.hangUpNow();
                }
                return machine.state().name();
            });
            Ln.i("answer: hand-over '" + (char) what + "' -> " + state);
            reply(client, Wire.hand(state));
            return;
        }
        if (command == Wire.COMMAND_GREETING) {
            greeting(client);
            return;
        }
        if (command == Wire.COMMAND_DIAGNOSE) {
            diagnose(client);
            return;
        }
        Ln.w("session: unknown command " + command + ", closing");
    }

    private static void reply(Socket client, String line) throws IOException {
        Wire.reply(client.getOutputStream(), line);
    }

    /**
     * 'G': the greeting. 'Q' how long it is; 'R' and 'S' start and stop a take
     * from the microphone; 'T' the take, as four bytes of length and the PCM;
     * 'P' a new greeting, the same way in; 'D' deletes.
     */
    private static void greeting(Socket client) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        int what = in.read();
        switch (what) {
            case 'Q':
                reply(client, Wire.greeting(Greeting.present() ? Greeting.FILE.length() : 0));
                break;
            case 'R':
                reply(client, Wire.take(Greeting.startTake()));
                break;
            case 'S':
                reply(client, Wire.taken(Greeting.stopTake()));
                break;
            case 'T':
                sendFile(client, Greeting.TAKE);
                break;
            case 'P':
                // A whole greeting over loopback takes a moment at most.
                client.setSoTimeout(30_000);
                Greeting.put(in, in.readInt());
                reply(client, Wire.greeting(Greeting.FILE.length()));
                break;
            case 'D':
                Greeting.delete();
                reply(client, Wire.greeting(0));
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
     *   'A' arm a test call: a byte (1 incoming, 0 outgoing), a name frame,
     *       the number to dial for an outgoing one, and eight bytes, the app's
     *       id for this arming, which 'R' names its results with.
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
                String number = Wire.readName(client.getInputStream());
                long arming = in.readLong();
                AnswerMachine machine = Answering.answerMachine;
                if (machine == null) {
                    reply(client, Wire.test("none"));
                    break;
                }
                // Before arming: an armed outgoing test takes the next call
                // this phone places, and a number that is not dialled leaves
                // that to be the user's own. Ending in '#' - *100# - or of two
                // digits, it is sent as a USSD code: no call, no off-hook.
                if (!incoming && (!number.matches("[0-9+*#]{3,32}") || number.endsWith("#"))) {
                    reply(client, Wire.test("bad-number"));
                    break;
                }
                // Never an emergency number - the app asks too. Telecom does
                // not place one for the shell but opens the dialer with it
                // (AOSP NewOutgoingCallIntentBroadcaster, read 2026-10-22),
                // and a Call there would be taken for the test's: the check
                // tone and the greeting, into an emergency call.
                if (!incoming && AnswerTest.mayBeEmergencyNumber(number)) {
                    reply(client, Wire.test("emergency"));
                    break;
                }
                // The test plays the greeting this process has: without one,
                // a caller is answered and hung up on, and the route blamed.
                if (!Greeting.present()) {
                    reply(client, Wire.test("no-greeting"));
                    break;
                }
                // On the machine's own thread, all of it: a test call still
                // up - its ring armed, or its call answered - would have its
                // results written over by the new test's, and be ended; asked
                // there, a ring for it coming in meanwhile is not missed. The
                // results reset with the arming: a hop given up on (Hop) arms
                // nothing, and leaves the last test's results as they were -
                // not "armed" for a test that never was.
                Boolean armed = Answering.onMain(() -> {
                    if (machine.testing()) {
                        return false;
                    }
                    AnswerTest.testArming = arming;
                    AnswerTest.testState = "armed";
                    AnswerTest.testRoute = -1;
                    AnswerTest.testRealMs = 0;
                    AnswerTest.testAudioMs = 0;
                    AnswerTest.testToneRun = 0;
                    AnswerTest.testToneBest = 0;
                    AnswerTest.testMuteHeld = -1;
                    AnswerTest.testVoice = -1;
                    AnswerTest.testPlayed = -1;
                    machine.armTest(incoming, incoming ? AnswerTest.TEST_INCOMING_WINDOW_MS : AnswerTest.TEST_OUTGOING_WINDOW_MS);
                    return true;
                });
                if (!Boolean.TRUE.equals(armed)) {
                    reply(client, Wire.test("busy"));
                    break;
                }
                // Answered before an outgoing test's call is placed: `am`
                // has no time limit here, and the app waited on it for the
                // answer - and, given up on, could not tell an arming that
                // took from one that did not.
                reply(client, Wire.test("armed"));
                if (!incoming) {
                    Shell.runQuietly("am", "start", "-a", "android.intent.action.CALL",
                            "-d", "tel:" + number.replace("#", "%23"));
                }
                break;
            }
            case 'R':
                reply(client, Wire.result(AnswerTest.testState, AnswerTest.testRoute, AnswerTest.testRealMs,
                        AnswerTest.testAudioMs, AnswerTest.testToneBest >= AnswerTest.TONE_CHUNKS ? 1 : 0,
                        AnswerTest.testToneBest, AnswerTest.testMuteHeld, AnswerTest.testVoice,
                        AnswerTest.testPlayed, AnswerTest.testArming));
                break;
            case 'F': {
                client.setSoTimeout(60_000);
                TelephonyManager telephony = Platform.phone;
                reply(client, Wire.fwd(telephony == null
                        ? "error no-telephony" : Forwarding.readNoReply(telephony)));
                break;
            }
            case 'P':
                reply(client, sims());
                break;
            case 'S': {
                int seconds = in.read();
                if (seconds < 5 || seconds > 30) {
                    reply(client, Wire.fwd("error bad-seconds"));
                    break;
                }
                client.setSoTimeout(90_000);
                TelephonyManager telephony = Platform.phone;
                reply(client, Wire.fwd(telephony == null
                        ? "error no-telephony" : Forwarding.setNoReply(telephony, seconds)));
                break;
            }
            default:
                Ln.w("session: unknown diagnostics command " + what);
        }
    }

    /** The no-call checks, as one line. */
    private static String stageOne() {
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
        String perms = missing.isEmpty() ? "ok" : String.join(",", missing);
        int inCallMusic = Checks.inCallMusic(Shell.dump("media.audio_policy"));
        boolean interceptable = Platform.pstnCallAudioInterceptable(Platform.callAudio);
        long greeting = Greeting.present() ? Greeting.FILE.length() : 0;
        String route = Answering.answerSettings.route;
        int voice = -1;
        TelephonyManager phoneNow = Platform.phone;
        try {
            if (phoneNow != null) {
                voice = phoneNow.getVoiceNetworkType();
            }
        } catch (Throwable ignored) {
            // unknown
        }
        return Wire.diag(perms, inCallMusic, interceptable ? 1 : 0, greeting, route, Build.VERSION.SDK_INT, voice,
                Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE, Build.MANUFACTURER, Build.MODEL);
    }

    /**
     * The phone's SIMs as Telecom has them, for the app's "Answer on" choice:
     * "SIMS default=<id> <id>|<label> ...". Listing them takes READ_PHONE_STATE,
     * which the app does not hold and should not ask for to fill one row.
     */
    private static String sims() {
        StringBuilder line = new StringBuilder(Wire.SIMS);
        TelecomManager telecom = Platform.telecom;
        if (telecom == null) {
            Ln.w("answer: could not list the SIMs: no telecom");
            return line.toString();
        }
        try {
            android.telecom.PhoneAccountHandle preferred = telecom.getDefaultOutgoingPhoneAccount("tel");
            line.append(Wire.simsDefault(preferred == null ? "" : Wire.word(preferred.getId())));
            for (android.telecom.PhoneAccountHandle handle : telecom.getCallCapablePhoneAccounts()) {
                android.telecom.PhoneAccount account = telecom.getPhoneAccount(handle);
                CharSequence label = account == null ? null : account.getLabel();
                line.append(Wire.sim(handle.getId(), label == null ? null : label.toString()));
            }
        } catch (Throwable t) {
            Ln.w("answer: could not list the SIMs: " + t);
        }
        return line.toString();
    }

    static void pong(Socket client) throws IOException {
        LoopbackServer.pings.incrementAndGet();
        Wire.reply(client.getOutputStream(), Wire.pong(Main.BUILD));
    }

    /** The answer to Wire.COMMAND_WATCH. Times are wall-clock millis, 0 for never. */
    private static String watchReport() {
        RecordingSession rec = Calls.activeRecording;
        return Wire.watch(Main.STARTED_AT, CallWatch.watching, looperResponds(), Calls.recordingMode,
                Calls.callActive, CallWatch.callEvents.get(), CallWatch.lastCallEventAt, CallWatch.offHooks.get(),
                CallWatch.lastOffHookAt, rec == null ? 0 : rec.startedAt, Voip.apps().size(), Voip.armed(),
                Tracking.trackingOn, Answering.answerSettings.on, Greeting.present());
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

}
