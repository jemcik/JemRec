package com.jemcik.jemrec.probe;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Answering-machine feasibility test. NOT part of the daemon, NOT shipped:
 * built by probe/build-probe.sh and run by hand as the shell user.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar app_process / \
 *       com.jemcik.jemrec.probe.CallProbe <digits|any|out> <answerSec> <messageSec> \
 *       <incall|redirect>[+music][+mute][+check] [armedMinutes]
 *
 * It answers ONE incoming phone call - only from a number ending in the given
 * digits, never one whose number it was not told - after answerSec of
 * ringing, plays /data/local/tmp/callprobe/greeting.wav (48 kHz mono PCM16)
 * and a beep into the call, records for messageSec and hangs up. "out" instead
 * takes the first call this phone places, answerSec after it goes off-hook.
 *
 * +music plays through in-call music (the route that works on the Honor),
 * +mute mutes this phone's microphone, +check first plays a 1400 Hz tone
 * unmuted while recording VOICE_CALL to <time>_voicecall.wav: whether the tone
 * shows up on the uplink side is what a self-test could rely on.
 *
 * The greeting goes through AudioManager.getCallUplinkInjectionAudioTrack, the
 * call-screening API behind CALL_AUDIO_INTERCEPTION, which the shell holds.
 * The caller's side comes from getCallDownlinkExtractionAudioRecord, to
 * <time>_downlink.wav. "redirect" also switches the audio mode to
 * MODE_CALL_REDIRECT, which should keep this phone's microphone and earpiece
 * out of the call; "incall" leaves telecom's MODE_IN_CALL alone, so the
 * JemRec daemon (which reads that mode) records the call as it would any.
 */
public final class CallProbe {

    private static final String DIR = "/data/local/tmp/callprobe";
    private static final int RATE = 48000;

    private static String digits;
    private static int answerSec;
    private static int messageSec;
    private static boolean redirect;
    private static boolean music;
    private static boolean mute;
    private static boolean check;
    private static boolean outgoing;

    private static Handler handler;
    private static AudioManager audio;
    private static TelephonyManager telephony;
    private static TelecomManager telecom;
    private static boolean used;
    private static boolean rang;
    private static volatile boolean callOver;
    private static Runnable pendingAnswer;
    // Strong reference: the framework only holds listeners weakly.
    private static PhoneStateListener listener;

    private CallProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: CallProbe <digits|any|out> <answerSec> <messageSec> <incall|redirect>[+music][+mute][+check] [armedMinutes]");
            System.exit(2);
        }
        outgoing = args[0].equals("out");
        digits = args[0].equals("any") || outgoing ? "" : args[0].replaceAll("[^0-9]", "");
        answerSec = Integer.parseInt(args[1]);
        messageSec = Integer.parseInt(args[2]);
        redirect = args[3].startsWith("redirect");
        music = args[3].contains("+music");
        mute = args[3].contains("+mute");
        check = args[3].contains("+check");
        int minutes = args.length > 4 ? Integer.parseInt(args[4]) : 10;

        // Before ANY mention of Workarounds - see VoipProbe.
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        File dir = new File(DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + DIR);
        }

        Context context = FakeContext.get();
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // Handed out with no context at all; setMode reads its package name
        // and dies with an NPE (measured). getMode swallows that NPE.
        for (String name : new String[] {"mOriginalContext", "mApplicationContext"}) {
            Field field = AudioManager.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(audio, context);
        }
        // Telephony needs its service manager set, and both managers need a
        // context whose package belongs to shell - see Main.startCallWatch.
        Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
        Class<?> managerClass = Class.forName("android.os.TelephonyServiceManager");
        initializer.getDeclaredMethod("setTelephonyServiceManager", managerClass)
                .invoke(null, managerClass.getDeclaredConstructor().newInstance());
        telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        rebind(telephony, TelephonyManager.class, context);
        telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        rebind(telecom, TelecomManager.class, context);

        log("probe: sdk=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL
                + " build=" + Build.DISPLAY + " match="
                + (outgoing ? "outgoing" : digits.isEmpty() ? "any" : "*" + digits)
                + " answer=" + answerSec + "s message=" + messageSec + "s mode=" + args[3]
                + " armed=" + minutes + "min");
        log("probe: isPstnCallAudioInterceptable=" + interceptable());

        handler = new Handler(Looper.myLooper());
        listener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String number) {
                onCallState(state, number);
            }
        };
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
        handler.postDelayed(() -> {
            log("probe: armed time is up");
            Looper.myLooper().quitSafely();
        }, minutes * 60_000L);
        Looper.loop();
        telephony.listen(listener, PhoneStateListener.LISTEN_NONE);
        log("probe: done");
        System.exit(0);
    }

    private static void onCallState(int state, String number) {
        String name = state == TelephonyManager.CALL_STATE_RINGING ? "RINGING"
                : state == TelephonyManager.CALL_STATE_OFFHOOK ? "OFFHOOK" : "IDLE";
        boolean known = number != null && !number.isEmpty();
        log("call: " + name + " number=" + (known ? "*" + tail(number) : "(none)") + " mode=" + audio.getMode());
        if (state == TelephonyManager.CALL_STATE_OFFHOOK && outgoing && !rang && !used) {
            // A call this phone placed: nothing to answer, the far end does.
            used = true;
            log("call: outgoing, starting in " + answerSec + " s");
            handler.postDelayed(() -> new Thread(CallProbe::session, "probe-session").start(),
                    answerSec * 1000L);
        } else if (state == TelephonyManager.CALL_STATE_RINGING) {
            rang = true;
            if (outgoing) {
                log("call: not answering - this probe watches outgoing calls");
            } else if (used) {
                log("call: not answering - this probe answers one call only");
            } else if (!digits.isEmpty() && !(known && number.replaceAll("[^0-9]", "").endsWith(digits))) {
                log("call: not answering - " + (known ? "number does not match" : "no number given to the shell"));
            } else {
                pendingAnswer = CallProbe::answer;
                handler.postDelayed(pendingAnswer, answerSec * 1000L);
                log("call: will answer in " + answerSec + " s");
            }
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            if (pendingAnswer != null) {
                handler.removeCallbacks(pendingAnswer);
                pendingAnswer = null;
                log("call: stopped ringing before the answer");
            }
            // Not the IDLE every listener is handed when it registers.
            if (used) {
                callOver = true;
            }
            rang = false;
        }
    }

    private static void answer() {
        pendingAnswer = null;
        // Spent only on a call that is answered, not one that stops ringing.
        used = true;
        log("answer: acceptRingingCall");
        try {
            telecom.acceptRingingCall();
        } catch (Throwable t) {
            log("answer: acceptRingingCall failed: " + t);
            return;
        }
        new Thread(CallProbe::session, "probe-session").start();
    }

    private static void session() {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (audio.getMode() != AudioManager.MODE_IN_CALL && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50);
        }
        log("session: mode=" + audio.getMode() + " after answering");
        if (redirect) {
            audio.setMode(AudioManager.MODE_CALL_REDIRECT);
            SystemClock.sleep(300);
            log("session: asked for MODE_CALL_REDIRECT, mode is now " + audio.getMode());
        }
        if (mute && !check) {
            // Global and sticky: unmuted again at the end of the session.
            // With the check, play() mutes once the check tone has gone out.
            muteMic();
        }
        log("session: isPstnCallAudioInterceptable=" + interceptable());
        logNetwork("answered");

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        // What JemRec records: both sides, uplink on the left. The check tone
        // should show up on the left if the uplink tap is after the mix.
        VoipProbe.Stream voiceCall = null;
        try {
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setContext(FakeContext.get());
            voiceCall = VoipProbe.Stream.start("voicecall", builder.build(), 2,
                    new File(DIR, stamp + "_voicecall.wav"));
        } catch (Throwable t) {
            log("session: no voice-call record: " + cause(t));
        }
        VoipProbe.Stream downlink = null;
        try {
            AudioRecord record = (AudioRecord) AudioManager.class
                    .getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class)
                    .invoke(audio, format(AudioFormat.CHANNEL_IN_MONO));
            downlink = VoipProbe.Stream.start("downlink", record, 1, new File(DIR, stamp + "_downlink.wav"));
        } catch (Throwable t) {
            log("session: no downlink record: " + cause(t));
        }

        AudioTrack track = null;
        try {
            track = music ? inCallMusicTrack() : (AudioTrack) AudioManager.class
                    .getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class)
                    .invoke(audio, format(AudioFormat.CHANNEL_OUT_MONO));
            log("session: " + (music ? "in-call music" : "injection") + " track state=" + track.getState()
                    + " rate=" + track.getSampleRate());
        } catch (Throwable t) {
            log("session: no track: " + cause(t));
        }

        long start = SystemClock.elapsedRealtime();
        Thread greeting = null;
        if (track != null) {
            AudioTrack playing = track;
            greeting = new Thread(() -> play(playing), "probe-greeting");
            greeting.start();
        }
        int ticks = 0;
        while (!callOver && SystemClock.elapsedRealtime() - start < messageSec * 1000L) {
            SystemClock.sleep(1000);
            log("tick: mode=" + audio.getMode() + " mute=" + audio.isMicrophoneMute()
                    + " route=" + device(audio.getCommunicationDevice()) + " downlink "
                    + (downlink != null ? downlink.takeLevel() : "-")
                    + " | voicecall " + (voiceCall != null ? voiceCall.takeLevel() : "-"));
            if (++ticks == 2) {
                logNetwork("mid-call");
                // Mid-greeting: where Android sent the track, from both sides.
                if (track != null) {
                    log("route: injection track -> " + device(track.getRoutedDevice()));
                }
                dump("media.audio_flinger", new File(DIR, stamp + "_flinger.txt"));
                dump("media.audio_policy", new File(DIR, stamp + "_policy.txt"));
            }
        }
        log("session: " + (callOver ? "caller hung up" : "time is up, hanging up"));
        if (!callOver) {
            try {
                log("session: endCall=" + telecom.endCall());
            } catch (Throwable t) {
                log("session: endCall failed: " + t);
            }
        }
        if (redirect) {
            audio.setMode(AudioManager.MODE_NORMAL);
        }
        if (mute) {
            audio.setMicrophoneMute(false);
            log("session: microphone muted=" + audio.isMicrophoneMute());
        }
        if (track != null) {
            try {
                greeting.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            track.release();
        }
        if (downlink != null) {
            downlink.stop();
        }
        if (voiceCall != null) {
            voiceCall.stop();
        }
        handler.post(() -> Looper.myLooper().quitSafely());
    }

    /**
     * Optionally the check tone (unmuted, so the uplink tap can carry it), then
     * the mute, the greeting, a moment of quiet and the beep.
     */
    private static void play(AudioTrack track) {
        try {
            track.play();
            byte[] greeting = readWav(new File(DIR, "greeting.wav"));
            log("greeting: " + (greeting.length / 2 / (double) RATE) + " s");
            // The caller's side of the call takes a moment to come up: the
            // first test lost the start of the greeting.
            int frames = RATE * 3 / 2;
            write(track, new byte[frames * 2]);
            if (check) {
                // 1400 Hz, not the 1000 Hz beep, so the two are told apart.
                write(track, tone(1400, RATE));
                write(track, new byte[RATE / 2 * 2]);
                frames += RATE + RATE / 2;
                while (!callOver && track.getPlaybackHeadPosition() < frames) {
                    SystemClock.sleep(20);
                }
                log("check: tone played (head=" + track.getPlaybackHeadPosition() + ")");
                if (mute) {
                    muteMic();
                }
            }
            write(track, greeting);
            write(track, new byte[RATE / 2 * 2]);
            write(track, tone(1000, RATE * 6 / 10));
            log("greeting: played, beep done; track underruns=" + track.getUnderrunCount());
        } catch (Throwable t) {
            log("greeting: failed: " + t);
        }
    }

    private static byte[] tone(int hz, int frames) {
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short s = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * 16000);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    private static void muteMic() {
        audio.setMicrophoneMute(true);
        log("session: microphone muted=" + audio.isMicrophoneMute());
    }

    /** VoLTE shows as LTE (13), Wi-Fi Calling as IWLAN (18), VoNR as NR (20). */
    private static void logNetwork(String when) {
        String voice;
        try {
            voice = String.valueOf(telephony.getVoiceNetworkType());
        } catch (Throwable t) {
            voice = "? " + cause(t);
        }
        String route;
        try {
            route = device(audio.getCommunicationDevice());
        } catch (Throwable t) {
            route = "? " + cause(t);
        }
        log("network (" + when + "): voice type " + voice + ", call audio on " + route);
    }

    private static void write(AudioTrack track, byte[] data) {
        int off = 0;
        while (off < data.length && !callOver) {
            int n = track.write(data, off, data.length - off);
            if (n < 0) {
                log("greeting: write error " + n);
                return;
            }
            off += n;
        }
    }

    /** The data chunk of a PCM WAV, or a second of silence if there is none. */
    private static byte[] readWav(File file) throws IOException {
        if (!file.isFile()) {
            log("greeting: no " + file + ", beep only");
            return new byte[RATE * 2];
        }
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(12);
            while (in.getFilePointer() + 8 <= in.length()) {
                byte[] id = new byte[4];
                in.readFully(id);
                int size = Integer.reverseBytes(in.readInt());
                if (new String(id, "US-ASCII").equals("data")) {
                    byte[] data = new byte[Math.min(size, (int) (in.length() - in.getFilePointer()))];
                    in.readFully(data);
                    return data;
                }
                in.seek(in.getFilePointer() + size + (size & 1));
            }
        }
        throw new IOException("no data chunk in " + file);
    }

    /**
     * The other way in: ordinary media aimed at the call's uplink device.
     * AudioPolicyManager gives such a track AUDIO_OUTPUT_FLAG_INCALL_MUSIC -
     * the output Qualcomm mixes into the uplink - only when its stream is
     * MUSIC or its usage VOICE_COMMUNICATION, the device was requested
     * explicitly, and the mode is IN_CALL, CALL_SCREEN or CALL_REDIRECT.
     */
    private static AudioTrack inCallMusicTrack() {
        android.media.AudioDeviceInfo tx = null;
        for (android.media.AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == android.media.AudioDeviceInfo.TYPE_TELEPHONY) {
                tx = d;
            }
        }
        log("session: uplink device " + device(tx));
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        log("session: setPreferredDevice=" + track.setPreferredDevice(tx));
        return track;
    }

    private static String device(android.media.AudioDeviceInfo info) {
        return info == null ? "(none)" : "type " + info.getType() + " \"" + info.getProductName() + "\"";
    }

    private static void dump(String service, File file) {
        try {
            new ProcessBuilder("dumpsys", service).redirectErrorStream(true)
                    .redirectOutput(file).start().waitFor();
            log("dump: " + service + " -> " + file.getName());
        } catch (Exception e) {
            log("dump: " + service + " failed: " + e);
        }
    }

    private static AudioFormat format(int channelMask) {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(channelMask)
                .build();
    }

    private static String interceptable() {
        try {
            return String.valueOf(AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(audio));
        } catch (Throwable t) {
            return "error " + cause(t);
        }
    }

    private static void rebind(Object manager, Class<?> type, Context context) {
        try {
            Field field = type.getDeclaredField("mContext");
            field.setAccessible(true);
            field.set(manager, context);
        } catch (ReflectiveOperationException e) {
            log("probe: could not rebind " + type.getSimpleName() + ": " + e);
        }
    }

    private static String tail(String number) {
        String d = number.replaceAll("[^0-9]", "");
        return d.length() <= 4 ? d : d.substring(d.length() - 4);
    }

    private static String cause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.toString();
    }

    private static void log(String line) {
        VoipProbe.log(line);
    }
}
