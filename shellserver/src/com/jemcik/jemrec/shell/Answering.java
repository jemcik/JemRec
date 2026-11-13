package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.TimeUnit;

/**
 * The answering machine's place in the recorder: the machine itself, its
 * settings, the greeting's player, the level of each chunk it hears, the
 * microphone given back, and the hop to the main looper it lives on.
 */
final class Answering {

    private Answering() {
    }

    static volatile AnswerSettings answerSettings = AnswerSettings.OFF;
    /** The microphone can be muted from here (the audio context rebound).
     *  Without that, nothing is answered: the caller would hear the room. */
    static volatile boolean muteReady;
    static @Nullable AnswerMachine answerMachine;

    static final GreetingPlayer greetingPlayer = new GreetingPlayer();

    /** This phone's own side of the recording - the uplink, left - is not
     *  silent in the chunk being looked at. Muted, it is silent unless the
     *  phone mixes the greeting into the recording: see AnswerEffects.greetingLoud. */
    static volatile boolean ownSideSounds;

    /** Every captured chunk of a phone call this process answered: a level
     *  for the message's start, and in a test, a look for the check tone on
     *  the uplink side (left). */
    // testToneRun++ below is not atomic, and need not be: only this thread
    // counts, and the TEST command zeroes it only while no test is running.
    @SuppressWarnings("NonAtomicVolatileUpdate")
    static void onChunk(short[] pcm, int frames, long atMs) {
        long chunkMs = frames * 1000L / AudioConfig.SAMPLE_RATE;
        if (Calls.captureStartedAt == 0) {
            Calls.captureStartedAt = atMs - chunkMs;
        }
        AnswerMachine machine = answerMachine;
        if (machine == null || !machine.answeredThisCall()) {
            return;
        }
        double rms = Checks.rms(pcm, AudioConfig.CHANNELS, frames);
        // Before the level is looked at: AnswerEffects.greetingLoud asks it.
        ownSideSounds = Checks.channelRms(pcm, AudioConfig.CHANNELS, 0, frames) >= AnswerMachine.SOUND_RMS;
        machine.level(rms, chunkMs);
        if (machine.testing()) {
            double share = Checks.toneShare(pcm, AudioConfig.CHANNELS, 0, frames, GreetingPlayer.CHECK_TONE_HZ,
                    AudioConfig.SAMPLE_RATE);
            if (share >= AnswerTest.TONE_MIN_SHARE && rms >= AnswerTest.TONE_MIN_RMS) {
                AnswerTest.testToneRun++;
                AnswerTest.testToneBest = Math.max(AnswerTest.testToneBest, AnswerTest.testToneRun);
            } else {
                AnswerTest.testToneRun = 0;
            }
        }
    }

    /** A marker left mid-message - by this process's predecessor, dead: the
     *  microphone may still be muted. Give it back. False while a call is up
     *  and the mute is left for its end; true when nothing is left to do. */
    static boolean giveBackStaleMute() {
        if (!Paths.MUTE_MARKER.exists() || Platform.callAudio == null) {
            return true;
        }
        try {
            int mode = Platform.callAudio.getMode();
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                // A call is up: the message this mute was for, its caller
                // still on - muted, the room stays out - or a call of the
                // user's, muted by them. Not this process's to unmute: the
                // marker is looked at again as that call ends (Calls.endCall).
                Ln.i("answer: a mute left by a message, and a call up; the microphone stays until it ends");
                return false;
            }
        } catch (Throwable ignored) {
            // Cannot tell: as before, give it back.
        }
        try {
            Platform.callAudio.setMicrophoneMute(false);
            Ln.w("answer: the microphone was left muted by a message; unmuted it");
        } catch (Throwable t) {
            Ln.e("answer: could not unmute the microphone a previous run muted", t);
        }
        Paths.MUTE_MARKER.delete();
        return true;
    }

    /** Run [task] on the main looper, where the answering machine lives, and
     *  wait for it. */
    static @Nullable <T> T onMain(java.util.concurrent.Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.call();
        }
        Handler handler = Platform.mainHandler;
        if (handler == null) {
            throw new IllegalStateException("the call watch is not up, so no answering machine");
        }
        java.util.concurrent.FutureTask<T> future = new java.util.concurrent.FutureTask<>(task);
        handler.post(future);
        return future.get(2, TimeUnit.SECONDS);
    }
}
