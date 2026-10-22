package com.jemcik.jemrec.probe;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Can the answering machine's greeting reach a MESSENGER call? Feasibility
 * test - NOT part of the daemon, NOT shipped: built by probe/build-probe.sh and
 * run by hand as the shell user.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar app_process / \
 *       com.jemcik.jemrec.probe.VoipInjectProbe <uid:pkg[,pkg]|preset|both:pkg> <greeting.pcm> <seconds> \
 *       [mute] [once:<messageSeconds>]
 *
 * WHY THIS ROUTE. A phone call's greeting goes out as Qualcomm's in-call music,
 * which is mixed into the cellular uplink. A messenger sends whatever it reads
 * from its own AudioRecord, so the only way in is to change what that
 * AudioRecord reads. A dynamic audio policy can: a mix in the INJECTOR role
 * (Android 13+, a "recorders" mix before), matching the messenger's capture by
 * uid or by capture preset, with ROUTE_FLAG_LOOP_BACK, sends a matching
 * recorder to a remote-submix input fed by AudioPolicy.createAudioTrackSource.
 * Registering any policy takes MODIFY_AUDIO_ROUTING, which the shell holds.
 * Android routes an input when it is opened, so this registers first and the
 * call comes after.
 *
 * WHAT IT MEASURES, every two seconds into /data/local/tmp/voipinject/probe.log:
 * the active recordings - whose, which source, which device (a remote submix
 * is the injection, a built-in mic is the room), and whether Android silenced
 * them - and how far the greeting has played. Whether the caller heard it is
 * the user's to say. The greeting loops - lead-in, greeting, beep, pause - for
 * as long as it is armed, so a call answered at any moment hears it.
 *
 * With once:N it behaves as a feature would: nothing until a matched app opens
 * its capture, then the lead-in, the greeting and the beep once, then N
 * seconds of silence fed as the "message", and then the policy is taken away
 * mid-call - Talk to them - to see whether the app's capture goes back to the
 * microphone, so the caller hears the user.
 */
public final class VoipInjectProbe {

    private static final String DIR = "/data/local/tmp/voipinject";
    private static final int RATE = 48000;

    private static PrintWriter log;
    private static volatile long written;
    private static final java.util.Set<Integer> UIDS = new java.util.HashSet<>();
    private static volatile boolean unregistered;

    private VoipInjectProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: VoipInjectProbe <uid:pkg|preset|both:pkg> <greeting.pcm> <seconds> [mute]");
            System.exit(2);
        }
        String ruleArg = args[0];
        File greetingFile = new File(args[1]);
        int seconds = Integer.parseInt(args[2]);
        boolean mute = false;
        int onceMessage = -1;
        for (int i = 3; i < args.length; i++) {
            mute |= args[i].contains("mute");
            if (args[i].startsWith("once:")) {
                onceMessage = Integer.parseInt(args[i].substring(5));
            }
        }

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
        log = new PrintWriter(new FileWriter(new File(dir, "probe.log"), true), true);

        Context context = FakeContext.get();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        for (String name : new String[] {"mOriginalContext", "mApplicationContext"}) {
            Field field = AudioManager.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(audio, context);
        }
        say("start: sdk=" + Build.VERSION.SDK_INT + " rule=" + ruleArg + " greeting=" + greetingFile
                + " (" + greetingFile.length() + " bytes) seconds=" + seconds + " mute=" + mute
                + " once=" + onceMessage);

        // --- the rule: whose capture to feed ---------------------------------------
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
        if (Build.VERSION.SDK_INT >= 33) {
            ruleBuilderClass.getMethod("setTargetMixRole", int.class)
                    .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_INJECTOR").getInt(null));
        }
        Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        if (ruleArg.startsWith("uid:") || ruleArg.startsWith("both:")) {
            // Several, comma-separated: rules of one kind are or-ed.
            for (String pkg : ruleArg.substring(ruleArg.indexOf(':') + 1).split(",")) {
                int uid = context.getPackageManager().getApplicationInfo(pkg, 0).uid;
                UIDS.add(uid);
                addMixRule.invoke(ruleBuilder, ruleClass.getField("RULE_MATCH_UID").getInt(null), Integer.valueOf(uid));
                say("rule: uid " + uid + " (" + pkg + ")");
            }
        }
        if (ruleArg.equals("preset") || ruleArg.startsWith("both:")) {
            AudioAttributes.Builder attrs = new AudioAttributes.Builder();
            AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class)
                    .invoke(attrs, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            addMixRule.invoke(ruleBuilder, ruleClass.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null),
                    attrs.build());
            say("rule: capture preset VOICE_COMMUNICATION");
        }
        Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

        // --- the mix and the policy -------------------------------------------------
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        mixBuilderClass.getMethod("setRouteFlags", int.class)
                .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
        Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
        Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
        Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
        register.setAccessible(true);
        int result = (int) register.invoke(null, policy);
        say("policy: registerAudioPolicyStatic -> " + result + (result == 0 ? " (ok)" : " (REFUSED)"));
        if (result != 0) {
            System.exit(1);
        }

        AudioTrack track = null;
        try {
            track = (AudioTrack) policyClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix);
            say("track: state=" + track.getState() + " rate=" + track.getSampleRate()
                    + " channels=" + track.getChannelCount() + " device=" + describe(track.getRoutedDevice()));
            if (mute) {
                audio.setMicrophoneMute(true);
                say("mic: muted -> " + audio.isMicrophoneMute());
            }
            track.play();
            byte[] cycle = cycle(GreetingCycle.read(greetingFile));
            say("greeting cycle: " + cycle.length / 2 * 1000L / RATE + " ms");

            AudioTrack tr = track;
            long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
            int message = onceMessage;
            Object pol = policy;
            Class<?> polClass = policyClass;
            Thread writer = new Thread(() -> {
                if (message >= 0) {
                    once(audio, tr, cycle, message, deadline, pol, polClass);
                    return;
                }
                while (SystemClock.elapsedRealtime() < deadline) {
                    int off = 0;
                    while (off < cycle.length && SystemClock.elapsedRealtime() < deadline) {
                        int n = tr.write(cycle, off, Math.min(RATE / 5 * 2, cycle.length - off));
                        if (n < 0) {
                            say("write: " + n + ", stopping");
                            return;
                        }
                        off += n;
                        written += n / 2;
                    }
                }
            }, "greeting-writer");
            writer.setDaemon(true);
            writer.start();

            String last = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(2000);
                String now = "mode=" + audio.getMode()
                        + (unregistered ? " POLICY GONE" : "")
                        + " head=" + head(tr) + "ms"
                        + " written=" + written * 1000L / RATE + "ms"
                        + " underruns=" + tr.getUnderrunCount()
                        + " track-device=" + describe(tr.getRoutedDevice())
                        + " recorders=" + recorders(audio);
                // Every line while something records; otherwise only changes.
                if (!now.equals(last) || now.contains("uid=")) {
                    say(now);
                }
                last = now;
            }
        } finally {
            if (mute) {
                audio.setMicrophoneMute(false);
                say("mic: unmuted -> " + audio.isMicrophoneMute());
            }
            if (track != null) {
                try {
                    track.stop();
                    track.release();
                } catch (Throwable ignored) {
                    // done anyway
                }
            }
            if (!unregistered) {
                unregister(policy, policyClass);
            }
            say("end");
        }
        System.exit(0);
    }

    private static void unregister(Object policy, Class<?> policyClass) throws Exception {
        Method unregister = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass);
        unregister.setAccessible(true);
        unregister.invoke(null, policy);
        unregistered = true;
        say("policy: unregistered");
    }

    private static long head(AudioTrack tr) {
        try {
            return (tr.getPlaybackHeadPosition() & 0xffffffffL) * 1000L / RATE;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** As a feature would: the greeting once, from when the app's capture
     *  opens; silence for the message; then the policy goes - Talk to them. */
    private static void once(AudioManager audio, AudioTrack tr, byte[] cycle, int messageSeconds, long deadline,
                             Object policy, Class<?> policyClass) {
        try {
            while (SystemClock.elapsedRealtime() < deadline && !matchedRecording(audio)) {
                SystemClock.sleep(50);
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return;
            }
            say("once: the app opened its capture - greeting starts");
            // The cycle without its 3 s tail: lead-in, greeting, gap, beep.
            int length = cycle.length - RATE * 3 * 2;
            int off = 0;
            while (off < length) {
                int n = tr.write(cycle, off, Math.min(RATE / 5 * 2, length - off));
                if (n < 0) {
                    say("once: write " + n);
                    return;
                }
                off += n;
                written += n / 2;
            }
            say("once: greeting and beep written; feeding " + messageSeconds + " s of silence as the message");
            byte[] quiet = new byte[RATE / 5 * 2];
            long until = SystemClock.elapsedRealtime() + messageSeconds * 1000L;
            while (SystemClock.elapsedRealtime() < until) {
                int n = tr.write(quiet, 0, quiet.length);
                if (n < 0) {
                    break;
                }
                written += n / 2;
            }
            say("once: message over - taking the policy away mid-call (Talk to them)");
            unregister(policy, policyClass);
        } catch (Throwable t) {
            say("once: failed " + t);
        }
    }

    /** Whether one of the matched apps is recording now. */
    private static boolean matchedRecording(AudioManager audio) {
        for (AudioRecordingConfiguration c : audio.getActiveRecordingConfigurations()) {
            Object uid = call(c, "getClientUid");
            if (uid instanceof Integer && UIDS.contains(uid)) {
                return true;
            }
        }
        return false;
    }

    /** Who is recording, from which source, on which device, silenced or not. */
    private static String recorders(AudioManager audio) {
        StringBuilder out = new StringBuilder("[");
        try {
            List<AudioRecordingConfiguration> configs = audio.getActiveRecordingConfigurations();
            for (AudioRecordingConfiguration c : configs) {
                if (out.length() > 1) {
                    out.append("; ");
                }
                out.append("uid=").append(call(c, "getClientUid"))
                        .append(" pkg=").append(call(c, "getClientPackageName"))
                        .append(" source=").append(c.getClientAudioSource())
                        .append(" device=").append(describe(c.getAudioDevice()))
                        .append(" silenced=").append(Build.VERSION.SDK_INT >= 29 ? c.isClientSilenced() : "?");
            }
        } catch (Throwable t) {
            out.append("error ").append(t);
        }
        return out.append(']').toString();
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describe(AudioDeviceInfo device) {
        if (device == null) {
            return "none";
        }
        String type;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:
                type = "REMOTE_SUBMIX";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                type = "BUILTIN_MIC";
                break;
            case AudioDeviceInfo.TYPE_TELEPHONY:
                type = "TELEPHONY";
                break;
            default:
                type = "type" + device.getType();
        }
        return type + "(" + device.getAddress() + ")";
    }

    /** Lead-in, the greeting, a pause, a soft beep, a longer pause: looped. */
    private static byte[] cycle(byte[] greeting) {
        int lead = RATE * 3 / 2;
        int gap = RATE / 2;
        int beep = RATE * 6 / 10;
        int tail = RATE * 3;
        byte[] out = new byte[(lead + greeting.length / 2 + gap + beep + tail) * 2];
        System.arraycopy(greeting, 0, out, lead * 2, greeting.length);
        int at = (lead + greeting.length / 2 + gap) * 2;
        for (int i = 0; i < beep; i++) {
            double fade = Math.min(1.0, Math.min(i, beep - 1 - i) / (RATE / 100.0));
            short s = (short) (Math.sin(2 * Math.PI * 1000 * i / RATE) * 4000 * fade);
            out[at + 2 * i] = (byte) s;
            out[at + 2 * i + 1] = (byte) (s >> 8);
        }
        return out;
    }

    private static final class GreetingCycle {
        static byte[] read(File file) throws IOException {
            byte[] pcm = new byte[(int) file.length() & ~1];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < pcm.length) {
                    int n = in.read(pcm, off, pcm.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
            }
            return pcm;
        }
    }

    private static void say(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + line;
        System.out.println(stamped);
        if (log != null) {
            log.println(stamped);
        }
    }
}
