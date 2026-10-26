package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Plays the answering machine's greeting into the call, where the caller hears
 * it: lead-in, an optional check tone, the user's greeting, a pause, the beep.
 *
 * THE ROUTE. Measured on an Honor (Snapdragon, Android 17), 2026-10-22, with a
 * caller on another phone:
 *
 *  - "music": a plain media track with the call's uplink device as its
 *    preferred device. AudioPolicyManager gives such a track - stream MUSIC,
 *    device requested explicitly, phone in a call - the output flag
 *    AUDIO_OUTPUT_FLAG_INCALL_MUSIC, and Qualcomm mixes that output into the
 *    uplink. The caller heard it in full. Writing to the uplink device takes
 *    MODIFY_PHONE_STATE (AudioPolicyInterfaceImpl), which the shell holds and
 *    no app can be granted.
 *  - "screening": AudioManager.getCallUplinkInjectionAudioTrack, the
 *    call-screening SystemApi. On the Honor it landed on a plain "voice_tx"
 *    output that Qualcomm's HAL routes nowhere: the caller heard nothing, at
 *    real-time pace, with no error anywhere. Reported to work on a Pixel; kept
 *    for phones where the diagnostics find it is the one that works.
 *
 * The first heard greeting lost its opening: the call's audio takes a moment
 * to come up at the far end. Hence the lead-in.
 */
final class GreetingPlayer {

    static final int RATE = 48_000;
    static final long LEAD_IN_MS = 1_500L;
    static final long CHECK_TONE_MS = 1_000L;
    static final long AFTER_TONE_MS = 500L;
    static final long GAP_MS = 500L;
    /** The check tone: apart from the chime's notes, so the two are told apart. */
    static final int CHECK_TONE_HZ = 1_400;
    /**
     * About -18 dBFS peak: the level of a spoken greeting. The call's uplink
     * plays at full volume with no control over it, and a first try at -6
     * dBFS had the caller call the check tone "very loud" (2026-10-22).
     */
    private static final double TONE_AMPLITUDE = 4_000;

    /** The player's own sound is kept in 20 ms blocks, as the recording's
     *  levels come. */
    static final int BLOCK_FRAMES = RATE / 50;
    /** The capture lags the playback head, and the head the uplink: a loud
     *  block this far either side of the head still counts as now. */
    private static final int MARGIN_BLOCKS = 15;

    interface Listener {
        void checkToneDone();

        /** The beep has played. [realMs] it took, [audioMs] it should have:
         *  far apart means the audio went somewhere that is not a call. */
        void done(long realMs, long audioMs);

        /** It could not be played, or stopped moving: nothing else will come
         *  from this greeting. */
        void failed(String why);
    }

    /**
     * One greeting played, with its own stop flag and track. A stop never
     * waits for the player's thread - it runs on the recorder's main looper,
     * which the call watch shares - so the thread can outlive it; with its
     * own session it can then only ever touch its own track, never the next
     * call's greeting.
     */
    private static final class Session {
        volatile boolean stopped;
        volatile @Nullable AudioTrack track;
        /** Which 20 ms blocks of what has been written are loud, in order. */
        volatile boolean[] loud = new boolean[0];
    }

    private volatile @Nullable Session current;

    void play(AudioManager audio, String route, boolean checkTone, File greeting, Listener listener) {
        stop();
        Session session = new Session();
        current = session;
        Thread t = new Thread(() -> run(session, audio, route, checkTone, greeting, listener), "jemrec-greeting");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        Session session = current;
        current = null;
        if (session == null) {
            return;
        }
        session.stopped = true;
        AudioTrack tr = session.track;
        if (tr != null) {
            try {
                tr.pause();
                tr.flush();
            } catch (Throwable ignored) {
                // released already
            }
        }
    }

    /**
     * Whether the greeting is making a sound about now. A phone that mixes the
     * in-call music into what the call's recording hears would have the
     * answering machine take its own greeting for the caller's first word.
     */
    boolean loudNow() {
        Session session = current;
        if (session == null || session.stopped) {
            return false;
        }
        AudioTrack tr = session.track;
        if (tr == null) {
            return false;
        }
        try {
            long head = tr.getPlaybackHeadPosition() & 0xffffffffL;
            return Checks.loudAround(session.loud, head / BLOCK_FRAMES, MARGIN_BLOCKS);
        } catch (Throwable t) {
            return false;
        }
    }

    private void run(Session session, AudioManager audio, String route, boolean checkTone, File greeting,
                     Listener listener) {
        AudioTrack tr = null;
        try {
            tr = AnswerSettings.ROUTE_SCREENING.equals(route) ? screeningTrack(audio) : musicTrack(audio);
            session.track = tr;
            if (session.stopped) {
                return;
            }
            // Whole blocks, so the loudness of what follows lines up with them.
            byte[] words = read(greeting);
            words = java.util.Arrays.copyOf(words, (words.length + BLOCK_FRAMES * 2 - 1) / (BLOCK_FRAMES * 2) * BLOCK_FRAMES * 2);
            tr.play();
            long started = SystemClock.elapsedRealtime();
            long frames = write(session, tr, new byte[framesOf(LEAD_IN_MS) * 2]);
            if (checkTone) {
                frames += write(session, tr, Checks.tone(RATE, CHECK_TONE_HZ, framesOf(CHECK_TONE_MS), TONE_AMPLITUDE));
                frames += write(session, tr, new byte[framesOf(AFTER_TONE_MS) * 2]);
                if (!waitPlayed(session, tr, frames)) {
                    failUnlessStopped(session, listener, "the check tone did not play");
                    return;
                }
                listener.checkToneDone();
            }
            frames += write(session, tr, words);
            frames += write(session, tr, new byte[framesOf(GAP_MS) * 2]);
            frames += write(session, tr, Checks.chime(RATE, TONE_AMPLITUDE));
            if (!waitPlayed(session, tr, frames)) {
                failUnlessStopped(session, listener, "the greeting did not play to the end");
                return;
            }
            long realMs = SystemClock.elapsedRealtime() - started;
            Ln.i("greeting: played " + frames * 1000 / RATE + " ms in " + realMs + " ms on route " + route
                    + ", underruns " + tr.getUnderrunCount());
            listener.done(realMs, frames * 1000 / RATE);
        } catch (Throwable t) {
            if (!session.stopped) {
                Ln.e("greeting: could not play", t);
                listener.failed(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } finally {
            session.track = null;
            if (tr != null) {
                try {
                    tr.release();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static void failUnlessStopped(Session session, Listener listener, String why) {
        if (!session.stopped) {
            Ln.w("greeting: " + why);
            listener.failed(why);
        }
    }

    /** True once [frames] have actually played; false if stopped or stalled. */
    private static boolean waitPlayed(Session session, AudioTrack tr, long frames) {
        long deadline = SystemClock.elapsedRealtime() + frames * 1000 / RATE + 5_000;
        while (!session.stopped && (tr.getPlaybackHeadPosition() & 0xffffffffL) < frames) {
            if (SystemClock.elapsedRealtime() > deadline) {
                Ln.w("greeting: playback stalled at " + tr.getPlaybackHeadPosition() + " of " + frames);
                return false;
            }
            SystemClock.sleep(20);
        }
        return !session.stopped;
    }

    /** Writes [data], noting first which of its blocks are loud; its frames. */
    private static long write(Session session, AudioTrack tr, byte[] data) {
        boolean[] before = session.loud;
        boolean[] added = Checks.loudBlocks(data, BLOCK_FRAMES, AnswerMachine.SOUND_RMS);
        boolean[] all = java.util.Arrays.copyOf(before, before.length + added.length);
        System.arraycopy(added, 0, all, before.length, added.length);
        session.loud = all;
        int off = 0;
        while (off < data.length && !session.stopped) {
            int n = tr.write(data, off, data.length - off);
            if (n < 0) {
                throw new IllegalStateException("AudioTrack.write returned " + n);
            }
            off += n;
        }
        return data.length / 2;
    }

    private static AudioFormat mono() {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
    }

    /** A media track with the uplink device requested: in-call music. */
    private static AudioTrack musicTrack(AudioManager audio) {
        AudioDeviceInfo uplink = null;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                uplink = d;
            }
        }
        if (uplink == null) {
            throw new IllegalStateException("no call uplink device on this phone");
        }
        AudioTrack tr = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(mono())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (!tr.setPreferredDevice(uplink)) {
            tr.release();
            throw new IllegalStateException("could not aim the greeting at the call uplink");
        }
        return tr;
    }

    /** The call-screening SystemApi's own track (CALL_AUDIO_INTERCEPTION). */
    private static AudioTrack screeningTrack(AudioManager audio) throws ReflectiveOperationException {
        return (AudioTrack) AudioManager.class
                .getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class)
                .invoke(audio, mono());
    }

    /** Frames as every caller uses them: an array's size. */
    static int framesOf(long ms) {
        return (int) (ms * RATE / 1000);
    }

    /** The greeting as stored: raw 16-bit little-endian mono at 48 kHz. */
    static byte[] read(File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > Greeting.MAX_BYTES) {
            throw new IOException("no usable greeting (" + length + " bytes)");
        }
        byte[] pcm = new byte[(int) length & ~1];
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
