package com.jemcik.jemrec.probe;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which capture route carries a phone call's sound? Feasibility test - NOT part
 * of the daemon, NOT shipped: built by probe/build-probe.sh and run by hand as
 * the shell user.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar setsid nohup app_process / \
 *       com.jemcik.jemrec.probe.RouteProbe [secondsPerRoute] [rounds] [settleSeconds] \
 *       < /dev/null > /data/local/tmp/routeprobe.log 2>&1 &
 *
 * WHY. Over Wi-Fi Calling on a Galaxy S23 (One UI, Android 16, 2026-10-24) the
 * daemon's capture - AudioRecord(VOICE_CALL), 48 kHz stereo - delivered packets
 * at the normal rate for the whole call, every one of them silence. That is one
 * route. This tries each of the others a shell process can open, in turn,
 * during one call.
 *
 * It waits for a call (audio mode IN_CALL or IN_COMMUNICATION), lets it settle,
 * then opens each route for [secondsPerRoute] and logs once a second: how many
 * frames came against the clock (nothing and silence are different answers),
 * how loud each channel was, whether Android silenced the client, and the
 * device it was routed from. Also who is playing with USAGE_VOICE_COMMUNICATION:
 * a call whose far side is a player in the mixer can be copied by an audio
 * policy, as the daemon does for messenger calls (Voip).
 *
 * Run it with the recorder switched off, so nothing else is capturing.
 */
public final class RouteProbe {

    private RouteProbe() {
    }

    /** One way of asking for the call's sound. */
    private static final class Route {
        final String name;
        final int source;
        final int rate;
        final int channelMask;
        final boolean policy;

        Route(String name, int source, int rate, int channelMask, boolean policy) {
            this.name = name;
            this.source = source;
            this.rate = rate;
            this.channelMask = channelMask;
            this.policy = policy;
        }

        int channels() {
            return channelMask == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1;
        }
    }

    private static final int STEREO = AudioFormat.CHANNEL_IN_STEREO;
    private static final int MONO = AudioFormat.CHANNEL_IN_MONO;

    private static List<Route> routes() {
        List<Route> r = new ArrayList<>();
        r.add(new Route("voice_call 48k stereo (the daemon's)", MediaRecorder.AudioSource.VOICE_CALL, 48000, STEREO, false));
        r.add(new Route("voice_call 16k mono", MediaRecorder.AudioSource.VOICE_CALL, 16000, MONO, false));
        r.add(new Route("voice_call 8k mono", MediaRecorder.AudioSource.VOICE_CALL, 8000, MONO, false));
        r.add(new Route("voice_downlink 16k mono", MediaRecorder.AudioSource.VOICE_DOWNLINK, 16000, MONO, false));
        r.add(new Route("voice_uplink 16k mono", MediaRecorder.AudioSource.VOICE_UPLINK, 16000, MONO, false));
        r.add(new Route("voice_communication 16k mono", MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, MONO, false));
        r.add(new Route("mic 48k mono", MediaRecorder.AudioSource.MIC, 48000, MONO, false));
        r.add(new Route("policy copy of USAGE_VOICE_COMMUNICATION 48k stereo", 0, 48000, STEREO, true));
        return r;
    }

    public static void main(String[] args) throws Exception {
        int perRoute = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int settle = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        // Before ANY mention of Workarounds: its static block builds an
        // ActivityThread, which needs a Looper (see Main.prepareMainLooper).
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        AudioManager audio = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
        log("probe: sdk=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL
                + " build=" + Build.DISPLAY + " perRoute=" + perRoute + "s rounds=" + rounds + " settle=" + settle + "s");

        // Registered before the call, as the daemon does for messenger calls.
        VoipProbe.Policy policy = null;
        try {
            policy = VoipProbe.Policy.register();
        } catch (Throwable t) {
            log("policy: could not register: " + t);
        }

        long until = SystemClock.elapsedRealtime() + 15 * 60_000L;
        int mode = audio.getMode();
        while (mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION) {
            if (SystemClock.elapsedRealtime() > until) {
                log("no call in 15 minutes");
                return;
            }
            SystemClock.sleep(200);
            mode = audio.getMode();
        }
        log("call: audio mode " + mode + "; settling " + settle + " s");
        SystemClock.sleep(settle * 1000L);

        for (int round = 1; round <= rounds; round++) {
            for (Route route : routes()) {
                if (audio.getMode() == AudioManager.MODE_NORMAL) {
                    log("call: over (audio mode normal)");
                    finish(policy);
                    return;
                }
                probe(audio, policy, route, perRoute, round);
            }
        }
        log("done");
        finish(policy);
    }

    private static void finish(VoipProbe.Policy policy) {
        if (policy != null) {
            policy.unregister();
        }
        System.exit(0);
    }

    @SuppressLint({"WrongConstant", "MissingPermission"})
    private static void probe(AudioManager audio, VoipProbe.Policy policy, Route route, int seconds, int round) {
        log("route " + round + ": " + route.name + " - mode " + audio.getMode() + ", voice players: " + voicePlayers(audio));
        AudioRecord record;
        try {
            if (route.policy) {
                if (policy == null) {
                    log("  no policy");
                    return;
                }
                record = policy.sink();
            } else {
                int min = AudioRecord.getMinBufferSize(route.rate, route.channelMask, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord.Builder builder = new AudioRecord.Builder()
                        .setAudioSource(route.source)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(route.rate)
                                .setChannelMask(route.channelMask)
                                .build());
                if (min > 0) {
                    builder.setBufferSizeInBytes(8 * min);
                }
                builder.setContext(FakeContext.get());
                record = builder.build();
            }
        } catch (Throwable t) {
            log("  could not build: " + t);
            return;
        }
        try {
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                log("  not initialized");
                return;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                log("  did not start (state " + record.getRecordingState() + ")");
                return;
            }
            int rate = record.getSampleRate();
            int channels = record.getChannelCount();
            short[] buf = new short[rate * channels / 10];
            long start = SystemClock.elapsedRealtime();
            for (int s = 1; s <= seconds; s++) {
                long frames = 0;
                double[] sum = new double[channels];
                int[] peak = new int[channels];
                long zeros = 0;
                long secondEnd = start + s * 1000L;
                while (SystemClock.elapsedRealtime() < secondEnd) {
                    int n = record.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) {
                        log("  read error " + n);
                        return;
                    }
                    if (n == 0) {
                        SystemClock.sleep(5);
                        continue;
                    }
                    for (int i = 0; i < n; i++) {
                        int c = i % channels;
                        int v = buf[i];
                        sum[c] += (double) v * v;
                        peak[c] = Math.max(peak[c], Math.abs(v));
                        if (v == 0) {
                            zeros++;
                        }
                    }
                    frames += n / channels;
                }
                StringBuilder level = new StringBuilder();
                for (int c = 0; c < channels; c++) {
                    double rms = frames > 0 ? Math.sqrt(sum[c] / frames) : 0;
                    level.append(String.format(Locale.US, " ch%d %6.1f dB (peak %5d)", c, db(rms), peak[c]));
                }
                log(String.format(Locale.US, "  t=%d frames %5d/%d%s zeros %3d%%%s", s, frames, rate, level,
                        frames > 0 ? (int) (100 * zeros / (frames * channels)) : 100,
                        s == 1 ? " " + context(audio, record) : ""));
            }
        } catch (Throwable t) {
            log("  failed: " + t);
        } finally {
            try {
                record.stop();
            } catch (Throwable ignored) {
                // released below either way
            }
            record.release();
        }
    }

    private static double db(double rms) {
        return rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0);
    }

    /** Silenced or not, and the device the record was routed from. */
    private static String context(AudioManager audio, AudioRecord record) {
        String silenced = "?";
        for (AudioRecordingConfiguration config : audio.getActiveRecordingConfigurations()) {
            if (config.getClientAudioSessionId() == record.getAudioSessionId()) {
                silenced = config.isClientSilenced() ? "SILENCED" : "not silenced";
            }
        }
        AudioDeviceInfo device = record.getRoutedDevice();
        return "[" + silenced + ", from " + (device == null ? "?" : device.getType() + " " + device.getProductName()) + "]";
    }

    /** uid:usage of every active player with USAGE_VOICE_COMMUNICATION. */
    private static String voicePlayers(AudioManager audio) {
        StringBuilder out = new StringBuilder();
        for (AudioPlaybackConfiguration config : audio.getActivePlaybackConfigurations()) {
            if (config.getAudioAttributes().getUsage() == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                Object uid;
                try {
                    uid = config.getClass().getMethod("getClientUid").invoke(config);
                } catch (ReflectiveOperationException e) {
                    uid = "?";
                }
                out.append(out.length() == 0 ? "" : ",").append(uid);
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static void log(String line) {
        VoipProbe.log(line);
    }
}
