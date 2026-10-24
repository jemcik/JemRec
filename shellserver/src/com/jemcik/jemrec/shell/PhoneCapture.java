package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;
import android.media.MediaCodec;
import android.os.Build;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A phone call's capture: the modem's voice path, VOICE_CALL, from the first
 * moment, as every call was recorded - and the messenger capture instead
 * (Voip.newPhoneCapture) once the audio mode says the call plays through the
 * mixer (Checks.phoneCallInMixer: Wi-Fi Calling on a Galaxy S23), which has
 * both sides where VOICE_CALL has only silence.
 *
 * THE MODE IS ASKED ON ITS OWN THREAD, NEVER WAITED FOR. While Telecom moves
 * the phone into a call, AudioService answers getMode only once the move is
 * done: asked at off-hook, an incoming call on the Android 13 emulator was
 * told 0.87 s later (2026-10-24), and a recording that waited for it lost
 * its first 0.87 s. So VOICE_CALL starts at once, and the switch, if the mode
 * calls for it, follows when the mode answers. The two give the same PCM,
 * 48 kHz stereo, to the same encoder. On the S23 an outgoing Wi-Fi call's
 * mode answered 367 ms after off-hook and the switch followed: 21.45 s saved
 * for 21.49 s of talk, both voices (2026-10-24).
 */
final class PhoneCapture implements AudioCapture {

    private final AudioCapture voice;
    private final AudioManager audio;
    /** The messenger capture, once the mode called for it and it started. */
    private volatile AudioCapture mixer;
    private final AtomicBoolean voiceStopped = new AtomicBoolean();
    private volatile boolean stopped;

    PhoneCapture(AudioCapture voice, AudioManager audio) {
        this.voice = voice;
        this.audio = audio;
    }

    @Override
    public void checkCompatibility() throws AudioCaptureException {
        voice.checkCompatibility();
    }

    @Override
    public void start() throws AudioCaptureException {
        voice.start();
        if (audio == null) {
            return;
        }
        Thread ask = new Thread(this::askMode, "jemrec-call-mode");
        ask.setDaemon(true);
        ask.start();
    }

    private void askMode() {
        long asked = SystemClock.elapsedRealtime();
        int mode;
        try {
            mode = audio.getMode();
        } catch (Throwable t) {
            Ln.w("recording: could not read the audio mode; the call's voice path: " + t);
            return;
        }
        long took = SystemClock.elapsedRealtime() - asked;
        if (stopped || !Checks.phoneCallInMixer(mode, Build.VERSION.SDK_INT)) {
            Ln.i("recording: the call's voice path (audio mode " + mode + ", told in " + took + " ms)");
            return;
        }
        AudioCapture copy = Voip.newPhoneCapture();
        if (copy == null) {
            return;
        }
        try {
            copy.start();
        } catch (Throwable t) {
            Ln.w("recording: the mixer capture would not start; the call's voice path: " + t);
            copy.stop();
            return;
        }
        synchronized (this) {
            if (stopped) {
                copy.stop();
                return;
            }
            mixer = copy;
        }
        Ln.i("recording: the call plays through the mixer (audio mode " + mode + ", told in " + took
                + " ms); recording it as messenger calls are");
    }

    @Override
    public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
        AudioCapture m = mixer;
        if (m == null) {
            return voice.read(outDirectBuffer, outBufferInfo);
        }
        if (voiceStopped.compareAndSet(false, true)) {
            voice.stop();
        }
        return m.read(outDirectBuffer, outBufferInfo);
    }

    @Override
    public void stop() {
        AudioCapture m;
        synchronized (this) {
            stopped = true;
            m = mixer;
        }
        if (voiceStopped.compareAndSet(false, true)) {
            voice.stop();
        }
        if (m != null) {
            m.stop();
        }
    }
}
