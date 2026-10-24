package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The messenger recorder (Voip.Capture: a copy of the call's voice-communication
 * playback for the other side, the microphone for this one) run on a PHONE
 * call. Feasibility test - NOT part of the daemon, NOT shipped: it sits in the
 * daemon's package only to reach Voip's package-private classes, and is built
 * into the probe jar alone by probe/build-probe.sh.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar setsid nohup app_process / \
 *       com.jemcik.jemrec.shell.VoipRouteProbe [seconds] [settleSeconds] \
 *       < /dev/null > /data/local/tmp/voiproute.log 2>&1 &
 *
 * WHY. Over Wi-Fi Calling on a Galaxy S23 (Android 16, 2026-10-24) the call ran
 * in audio mode IN_COMMUNICATION with its far side played by voice-
 * communication players, and every VOICE_CALL route gave zeros while a policy
 * copy of USAGE_VOICE_COMMUNICATION carried sound (RouteProbe). The question
 * now is whether the whole messenger capture works there - and, above all,
 * that it never silences the call's own microphone: each second this lists
 * every capture and whether Android silenced it.
 *
 * Run it with the recorder switched off. Writes no audio anywhere.
 */
public final class VoipRouteProbe {

    private VoipRouteProbe() {
    }

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 70;
        int settle = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        // Before ANY mention of Workarounds: its static block builds an
        // ActivityThread, which needs a Looper (see Main.prepareMainLooper).
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        AudioManager audio = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
        log("probe: sdk=" + Build.VERSION.SDK_INT + " " + Build.MANUFACTURER + " " + Build.MODEL
                + " build=" + Build.DISPLAY + " seconds=" + seconds + " settle=" + settle);

        Voip.Policy policy = Voip.Policy.register();
        long until = SystemClock.elapsedRealtime() + 15 * 60_000L;
        while (audio.getMode() != AudioManager.MODE_IN_CALL && audio.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
            if (SystemClock.elapsedRealtime() > until) {
                log("no call in 15 minutes");
                policy.unregister();
                System.exit(0);
            }
            SystemClock.sleep(200);
        }
        log("call: audio mode " + audio.getMode() + "; settling " + settle + " s");
        SystemClock.sleep(settle * 1000L);
        log("players: " + players(audio));
        log("captures: " + captures(audio));

        Voip.Capture capture = new Voip.Capture(policy, true);
        capture.start();
        log("capture: started, mic " + (capture.hasMic() ? "on" : "off"));
        ByteBuffer buf = ByteBuffer.allocate(16384);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long start = SystemClock.elapsedRealtime();
        for (int s = 1; s <= seconds; s++) {
            double far = 0;
            double near = 0;
            long frames = 0;
            long farZeros = 0;
            long nearZeros = 0;
            while (SystemClock.elapsedRealtime() < start + s * 1000L) {
                buf.clear();
                int n = capture.read(buf, info);
                if (n < 0) {
                    log("capture: read ended (" + n + ")");
                    finish(capture, policy);
                    return;
                }
                buf.flip();
                buf.order(ByteOrder.nativeOrder());
                while (buf.remaining() >= 4) {
                    short f = buf.getShort();
                    short m = buf.getShort();
                    far += (double) f * f;
                    near += (double) m * m;
                    if (f == 0) {
                        farZeros++;
                    }
                    if (m == 0) {
                        nearZeros++;
                    }
                    frames++;
                }
            }
            log(String.format(Locale.US, "t=%2d far %6.1f dB (zeros %3d%%) | near %6.1f dB (zeros %3d%%) mic %s | %s",
                    s, db(far, frames), pct(farZeros, frames), db(near, frames), pct(nearZeros, frames),
                    capture.hasMic() ? "on" : "OFF", captures(audio)));
            if (audio.getMode() == AudioManager.MODE_NORMAL) {
                log("call: over (audio mode normal)");
                break;
            }
        }
        finish(capture, policy);
    }

    private static void finish(Voip.Capture capture, Voip.Policy policy) {
        try {
            capture.stop();
        } catch (Throwable t) {
            log("capture: stop failed: " + t);
        }
        policy.unregister();
        log("done");
        System.exit(0);
    }

    private static double db(double sumSquares, long frames) {
        if (frames == 0 || sumSquares == 0) {
            return -120;
        }
        return 20 * Math.log10(Math.sqrt(sumSquares / frames) / 32768.0);
    }

    private static int pct(long part, long whole) {
        return whole == 0 ? 100 : (int) (100 * part / whole);
    }

    /** Every active capture: package:source, and SILENCED where Android cut it off. */
    private static String captures(AudioManager audio) {
        StringBuilder out = new StringBuilder();
        for (AudioRecordingConfiguration r : audio.getActiveRecordingConfigurations()) {
            out.append(out.length() == 0 ? "" : ", ").append(name(uid(r))).append(':').append(r.getClientAudioSource())
                    .append(r.isClientSilenced() ? " SILENCED" : "");
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** Every active player with USAGE_VOICE_COMMUNICATION, by package. */
    private static String players(AudioManager audio) {
        StringBuilder out = new StringBuilder();
        for (AudioPlaybackConfiguration p : audio.getActivePlaybackConfigurations()) {
            if (p.getAudioAttributes().getUsage() == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                Integer uid = null;
                try {
                    uid = (Integer) p.getClass().getMethod("getClientUid").invoke(p);
                } catch (ReflectiveOperationException ignored) {
                    // unnamed below
                }
                out.append(out.length() == 0 ? "" : ", ").append(name(uid));
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static Integer uid(AudioRecordingConfiguration r) {
        try {
            return (Integer) r.getClass().getMethod("getClientUid").invoke(r);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String name(Integer uid) {
        if (uid == null) {
            return "?";
        }
        if (uid == android.os.Process.myUid()) {
            return "us";
        }
        String[] pkgs = FakeContext.get().getPackageManager().getPackagesForUid(uid);
        return uid + "/" + (pkgs == null || pkgs.length == 0 ? "-" : pkgs[0]);
    }

    private static void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        System.out.println(stamp + " " + line);
        System.out.flush();
    }
}
