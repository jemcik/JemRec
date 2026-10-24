package com.jemcik.jemrec.probe;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Can the answering machine's greeting reach a call through Android's
 * call-screening route (AudioManager.getCallUplinkInjectionAudioTrack)?
 * Feasibility test - NOT part of the daemon, NOT shipped: built by
 * probe/build-probe.sh and run by hand as the shell user.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar setsid nohup app_process / \
 *       com.jemcik.jemrec.probe.GreetProbe [attempts] [greeting.pcm] \
 *       < /dev/null > /data/local/tmp/greetprobe.log 2>&1 &
 *
 * WHY. On a Galaxy S23 over Wi-Fi Calling (Android 16, 2026-10-24) the call
 * runs in audio mode IN_COMMUNICATION, not through the modem, so the greeting's
 * usual route - Qualcomm's in-call music, mixed into the modem's uplink - has
 * nothing to mix into, and an injector audio policy matched by capture preset
 * left the call stack's capture on the microphone (VoipInjectProbe). This
 * tries the remaining SystemApi: it says first whether the platform claims
 * the mode at all, then, once a call is up, plays [greeting.pcm] (48 kHz mono
 * PCM16) through the route [attempts] times, ten seconds apart, logging any
 * refusal and where the track was routed. Whether the caller heard it is the
 * user's to say.
 */
public final class GreetProbe {

    private static final int RATE = 48000;

    private GreetProbe() {
    }

    public static void main(String[] args) throws Exception {
        int attempts = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        String pcm = args.length > 1 ? args[1] : "/data/local/tmp/voipinject/greeting.pcm";
        // Before ANY mention of Workarounds: its static block builds an
        // ActivityThread, which needs a Looper (see Main.prepareMainLooper).
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        AudioManager audio = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
        byte[] greeting = read(new File(pcm));
        log("probe: sdk=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL
                + " greeting=" + greeting.length + " bytes attempts=" + attempts);
        try {
            Object claims = AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(audio);
            log("screening: isPstnCallAudioInterceptable=" + claims);
        } catch (Throwable t) {
            log("screening: cannot ask: " + t);
        }

        long until = SystemClock.elapsedRealtime() + 15 * 60_000L;
        while (audio.getMode() != AudioManager.MODE_IN_CALL && audio.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
            if (SystemClock.elapsedRealtime() > until) {
                log("no call in 15 minutes");
                System.exit(0);
            }
            SystemClock.sleep(200);
        }
        log("call: audio mode " + audio.getMode() + "; first attempt in 8 s");
        SystemClock.sleep(8000);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        for (int i = 1; i <= attempts; i++) {
            if (audio.getMode() == AudioManager.MODE_NORMAL) {
                log("call: over");
                break;
            }
            log("attempt " + i + ": mode " + audio.getMode());
            AudioTrack track;
            try {
                track = (AudioTrack) AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class)
                        .invoke(audio, format);
            } catch (InvocationTargetException e) {
                log("  refused: " + e.getCause());
                SystemClock.sleep(10_000);
                continue;
            } catch (Throwable t) {
                log("  refused: " + t);
                SystemClock.sleep(10_000);
                continue;
            }
            try {
                log("  track: state=" + track.getState() + " rate=" + track.getSampleRate());
                track.play();
                long start = SystemClock.elapsedRealtime();
                int written = track.write(greeting, 0, greeting.length);
                AudioDeviceInfo device = track.getRoutedDevice();
                log(String.format(Locale.US, "  played: wrote %d bytes in %d ms, head %d frames, routed to %s",
                        written, SystemClock.elapsedRealtime() - start, track.getPlaybackHeadPosition(),
                        device == null ? "?" : device.getType() + " " + device.getProductName()));
                SystemClock.sleep(500);
                track.stop();
            } catch (Throwable t) {
                log("  failed: " + t);
            } finally {
                track.release();
            }
            SystemClock.sleep(4000);
        }
        log("done");
        System.exit(0);
    }

    private static byte[] read(File file) throws IOException {
        byte[] out = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return out;
    }

    private static void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        System.out.println(stamp + " " + line);
        System.out.flush();
    }
}
