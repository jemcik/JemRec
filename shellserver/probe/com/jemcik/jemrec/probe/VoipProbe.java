package com.jemcik.jemrec.probe;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VoIP feasibility test. NOT part of the daemon, NOT shipped: built to its own
 * jar by probe/build-probe.sh and run by hand as the shell user.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar app_process / \
 *       com.jemcik.jemrec.probe.VoipProbe [minutes] [mic|vc|vr|none]
 *
 * It answers one question per messenger: can the shell user, with the
 * permissions it already holds, record both sides of that app's call without
 * disturbing the call?
 *
 * THE OTHER PERSON: an audio policy that copies every player using
 * USAGE_VOICE_COMMUNICATION into a stream this process reads, while the call
 * keeps playing (ROUTE_FLAG_LOOP_BACK_RENDER). Registered at start, before any
 * call: Android routes a stream when it is created, so a policy registered
 * mid-call matches nothing. Capturing that usage needs
 * CAPTURE_VOICE_COMMUNICATION_OUTPUT, which the shell holds, and the rule's
 * voiceCommunicationCaptureAllowed - which scrcpy's playback capture sets
 * after build(), where it does nothing. An app whose capture policy is
 * ALLOW_CAPTURE_BY_NONE is not matched whatever the permissions: that is
 * Android honouring the app, and the answer for that app is no.
 *
 * YOU: the microphone, opened as NOT privacy-sensitive, so it does not take
 * the input away from the call app - a recorder that leaves the other person
 * unable to hear you is worse than none. Whether the call app's own capture
 * stays live is logged from the active recording configurations
 * (isClientSilenced) - that is "can they still hear you", from the phone's
 * side.
 *
 * A call is the audio mode at MODE_IN_COMMUNICATION, which every VoIP app
 * sets and TelephonyCallback never reports. Each call is written to
 * /data/local/tmp/voip/<time>_far.wav and _near.wav, with a level per second
 * in the log.
 */
public final class VoipProbe {

    private static final String DIR = "/data/local/tmp/voip";
    private static final int RATE = 48000;

    private VoipProbe() {
    }

    public static void main(String[] args) throws Exception {
        int minutes = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        String near = args.length > 1 ? args[1] : "mic";
        // Before ANY mention of Workarounds: its static block builds an
        // ActivityThread, which needs a Looper (see Main.prepareMainLooper).
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        File dir = new File(DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + DIR);
        }
        AudioManager audio = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
        log("probe: sdk=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL
                + " build=" + Build.DISPLAY + " near=" + near + " minutes=" + minutes);

        Policy policy = Policy.register();

        long until = SystemClock.elapsedRealtime() + minutes * 60_000L;
        Session session = null;
        int lastMode = Integer.MIN_VALUE;
        long leftAt = 0;
        while (SystemClock.elapsedRealtime() < until) {
            int mode = audio.getMode();
            if (mode != lastMode) {
                log("mode: " + modeName(lastMode) + " -> " + modeName(mode));
                lastMode = mode;
            }
            boolean inCall = mode == AudioManager.MODE_IN_COMMUNICATION;
            if (inCall) {
                leftAt = 0;
                if (session == null) {
                    session = Session.start(policy, audio, near);
                }
            } else if (session != null) {
                // A moment out of the mode is not the end: some apps flip it
                // while switching the route.
                if (leftAt == 0) {
                    leftAt = SystemClock.elapsedRealtime();
                } else if (SystemClock.elapsedRealtime() - leftAt > 1500) {
                    session.stop();
                    session = null;
                    leftAt = 0;
                }
            }
            if (session != null) {
                session.tick(audio);
            }
            SystemClock.sleep(100);
        }
        if (session != null) {
            session.stop();
        }
        policy.unregister();
        log("probe: done");
    }

    static void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        System.out.println(stamp + " " + line);
        System.out.flush();
    }

    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            case Integer.MIN_VALUE: return "-";
            default: return String.valueOf(mode);
        }
    }

    /** A hidden getter, by name, or null. */
    static Object hidden(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The audio policy: one mix copying voice-call playback. */
    static final class Policy {
        private final Object policy;
        private final Object mix;
        private final Class<?> policyClass;
        private final Class<?> mixClass;

        private Policy(Object policy, Object mix, Class<?> policyClass, Class<?> mixClass) {
            this.policy = policy;
            this.mix = mix;
            this.policyClass = policyClass;
            this.mixClass = mixClass;
        }

        /** Privileged first (may also reach apps that allow capture by the
         *  system only); without it if the platform refuses. */
        static Policy register() throws Exception {
            try {
                return register(true);
            } catch (Throwable t) {
                log("policy: privileged registration failed (" + t + "); trying without");
                return register(false);
            }
        }

        @SuppressLint("PrivateApi")
        private static Policy register(boolean privileged) throws Exception {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
            ruleBuilderClass.getMethod("setTargetMixRole", int.class)
                    .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null));
            AudioAttributes voice = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build();
            ruleBuilderClass.getMethod("addMixRule", int.class, Object.class)
                    .invoke(ruleBuilder, ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null), voice);
            // BEFORE build(): on the builder it is a property of the rule built.
            ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(ruleBuilder, true);
            if (privileged) {
                ruleBuilderClass.getMethod("allowPrivilegedPlaybackCapture", boolean.class).invoke(ruleBuilder, true);
            }
            Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
            // Copied AND still played: the call must sound as it always does.
            mixBuilderClass.getMethod("setRouteFlags", int.class)
                    .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null));
            Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(FakeContext.get());
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
            Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

            Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
            register.setAccessible(true);
            int result = (int) register.invoke(null, policy);
            if (result != 0) {
                throw new IllegalStateException("registerAudioPolicyStatic returned " + result);
            }
            log("policy: registered (privileged=" + privileged + "), copying USAGE_VOICE_COMMUNICATION");
            return new Policy(policy, mix, policyClass, mixClass);
        }

        AudioRecord sink() throws Exception {
            return (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
        }

        void unregister() {
            try {
                Method m = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass);
                m.setAccessible(true);
                m.invoke(null, policy);
                log("policy: unregistered");
            } catch (Throwable t) {
                log("policy: left to die with the process (" + t + ")");
            }
        }
    }

    /** One call: two streams, two files. */
    static final class Session {
        private final String stamp;
        private final Stream far;
        private final Stream near;
        private final long startedAt = SystemClock.elapsedRealtime();
        private long lastReport;
        private boolean reportedApps;

        private Session(String stamp, Stream far, Stream near) {
            this.stamp = stamp;
            this.far = far;
            this.near = near;
        }

        static Session start(Policy policy, AudioManager audio, String near) {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            log("call: start " + stamp);
            Stream far = null;
            try {
                AudioRecord sink = policy.sink();
                far = Stream.start("far", sink, 2, new File(DIR, stamp + "_far.wav"));
            } catch (Throwable t) {
                log("far: could not start: " + t);
            }
            Stream mine = null;
            if (!"none".equals(near)) {
                try {
                    mine = Stream.start("near", mic(near), 1, new File(DIR, stamp + "_near.wav"));
                } catch (Throwable t) {
                    log("near: could not start: " + t);
                }
            }
            Session session = new Session(stamp, far, mine);
            session.report(audio);
            return session;
        }

        @SuppressLint({"WrongConstant", "MissingPermission"})
        private static AudioRecord mic(String which) {
            int source;
            switch (which) {
                case "vc": source = MediaRecorder.AudioSource.VOICE_COMMUNICATION; break;
                case "vr": source = MediaRecorder.AudioSource.VOICE_RECOGNITION; break;
                default: source = MediaRecorder.AudioSource.MIC; break;
            }
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    // Not privacy-sensitive: never take the input from the call.
                    .setPrivacySensitive(false)
                    .setBufferSizeInBytes(8 * AudioRecord.getMinBufferSize(
                            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setContext(FakeContext.get());
            }
            log("near: source=" + which + " (" + source + "), privacy-sensitive=false");
            return builder.build();
        }

        void tick(AudioManager audio) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastReport >= 1000) {
                lastReport = now;
                long t = (now - startedAt) / 1000;
                log(String.format(Locale.US, "level t=%3ds far %s | near %s", t,
                        far == null ? "-" : far.takeLevel(), near == null ? "-" : near.takeLevel()));
                // Again once the call has settled, then every ten seconds: who
                // plays, who records, and whether Android silenced anyone.
                if ((!reportedApps && t >= 3) || t % 10 == 0) {
                    reportedApps = true;
                    report(audio);
                }
            }
        }

        void report(AudioManager audio) {
            try {
                List<AudioPlaybackConfiguration> players = audio.getActivePlaybackConfigurations();
                for (AudioPlaybackConfiguration p : players) {
                    AudioAttributes a = p.getAudioAttributes();
                    log("  player uid=" + hidden(p, "getClientUid") + " usage=" + a.getUsage()
                            + " content=" + a.getContentType() + " state=" + hidden(p, "getPlayerState")
                            + " allowedCapture=" + a.getAllowedCapturePolicy());
                }
                List<AudioRecordingConfiguration> recorders = audio.getActiveRecordingConfigurations();
                for (AudioRecordingConfiguration r : recorders) {
                    log("  recorder uid=" + hidden(r, "getClientUid") + " pkg=" + hidden(r, "getClientPackageName")
                            + " source=" + r.getClientAudioSource() + " silenced=" + r.isClientSilenced());
                }
            } catch (Throwable t) {
                log("  report failed: " + t);
            }
        }

        void stop() {
            log("call: end " + stamp);
            if (far != null) {
                far.stop();
            }
            if (near != null) {
                near.stop();
            }
        }
    }

    /** One AudioRecord drained to a WAV on its own thread, with levels. */
    static final class Stream implements Runnable {
        private final String name;
        private final AudioRecord record;
        private final int channels;
        private final File file;
        private final RandomAccessFile out;
        private final Thread thread;
        private volatile boolean running = true;
        private long dataBytes;
        // The second being measured: sum of squares, peak, sample count.
        private double sumSquares;
        private int peak;
        private long samples;
        private long reads;
        private long zeroReads;

        private Stream(String name, AudioRecord record, int channels, File file) throws IOException {
            this.name = name;
            this.record = record;
            this.channels = channels;
            this.file = file;
            this.out = new RandomAccessFile(file, "rw");
            out.setLength(0);
            out.write(new byte[44]);
            this.thread = new Thread(this, "probe-" + name);
        }

        static Stream start(String name, AudioRecord record, int channels, File file) throws IOException {
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException(name + ": AudioRecord not initialized");
            }
            record.startRecording();
            log(name + ": recording, state=" + record.getRecordingState() + " rate=" + record.getSampleRate()
                    + " channels=" + record.getChannelCount() + " -> " + file.getName());
            Stream stream = new Stream(name, record, channels, file);
            stream.thread.start();
            return stream;
        }

        @Override
        public void run() {
            byte[] buf = new byte[RATE / 10 * 2 * channels];
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n < 0) {
                    log(name + ": read error " + n);
                    break;
                }
                if (n == 0) {
                    continue;
                }
                boolean allZero = true;
                synchronized (this) {
                    for (int i = 0; i + 1 < n; i += 2) {
                        int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                        if (s != 0) {
                            allZero = false;
                        }
                        sumSquares += (double) s * s;
                        peak = Math.max(peak, Math.abs(s));
                        samples++;
                    }
                    reads++;
                    if (allZero) {
                        zeroReads++;
                    }
                }
                try {
                    out.write(buf, 0, n);
                    dataBytes += n;
                } catch (IOException e) {
                    log(name + ": write failed: " + e);
                    break;
                }
            }
        }

        /** RMS and peak of what arrived since the last call, in dBFS. */
        synchronized String takeLevel() {
            String level;
            if (samples == 0) {
                level = "nothing";
            } else {
                double rms = Math.sqrt(sumSquares / samples);
                level = String.format(Locale.US, "rms %6.1f dB peak %6.1f dB%s",
                        db(rms), db(peak), zeroReads == reads ? " (all zeros)" : "");
            }
            sumSquares = 0;
            peak = 0;
            samples = 0;
            reads = 0;
            zeroReads = 0;
            return level;
        }

        private static double db(double v) {
            return v <= 0 ? -120.0 : 20 * Math.log10(v / 32768.0);
        }

        void stop() {
            running = false;
            try {
                record.stop();
            } catch (Throwable ignored) {
                // already stopped
            }
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            record.release();
            try {
                writeHeader();
                out.close();
            } catch (IOException e) {
                log(name + ": could not finish " + file.getName() + ": " + e);
            }
            log(name + ": saved " + file.getName() + " (" + dataBytes + " bytes, "
                    + String.format(Locale.US, "%.1f", dataBytes / (2.0 * channels * RATE)) + " s)");
        }

        private void writeHeader() throws IOException {
            out.seek(0);
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes((int) (36 + dataBytes)));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) channels));
            out.writeInt(Integer.reverseBytes(RATE));
            out.writeInt(Integer.reverseBytes(RATE * 2 * channels));
            out.writeShort(Short.reverseBytes((short) (2 * channels)));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes((int) dataBytes));
        }
    }
}
