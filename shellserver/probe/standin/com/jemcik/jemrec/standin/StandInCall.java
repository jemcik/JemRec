package com.jemcik.jemrec.standin;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * A stand-in for a messenger's call, for tools/emulator_checks.py
 * (messenger_recorder_lost). NOT shipped. Built by the script into an app
 * named as a messenger JemRec knows - Telegram's telegram.org build, whose
 * name is free on an emulator - because JemRec's recorder knows a messenger
 * call by the app that plays voice-call audio and captures at once
 * (Voip.callingApp), and FakeCallProbe, run as the shell, is no app on its
 * list. Its call is FakeCallProbe's: the audio mode at
 * MODE_IN_COMMUNICATION, a 440 Hz tone at -12 dBFS as the call's audio, the
 * microphone open. It holds the call while in front, for its seconds, or
 * until `am force-stop` ends it - Android then puts the mode back, as it does
 * for a messenger killed mid-call (AudioService's mode death handler).
 *
 *   am start -n org.telegram.messenger.web/com.jemcik.jemrec.standin.StandInCall --ei seconds 90
 */
public final class StandInCall extends Activity {

    private static final String TAG = "JemRecStandIn";
    private static final int RATE = 16000;

    private volatile boolean up = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int seconds = getIntent().getIntExtra("seconds", 60);
        new Thread(() -> call(seconds), "standin-call").start();
    }

    @Override
    protected void onDestroy() {
        up = false;
        super.onDestroy();
    }

    private void call(int seconds) {
        AudioManager audio = getSystemService(AudioManager.class);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        AudioRecord mic;
        try {
            mic = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .build();
        } catch (SecurityException e) {
            // Installed without -g: no microphone, no call as JemRec counts one.
            Log.w(TAG, "standin: no microphone: " + e);
            track.release();
            runOnUiThread(this::finish);
            return;
        }
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        track.play();
        mic.startRecording();
        Log.i(TAG, "standin: call up, mode=" + audio.getMode());

        // A tenth of a second: 44 whole cycles of 440 Hz, so it loops clean.
        byte[] tone = new byte[RATE / 10 * 2];
        for (int i = 0; i < tone.length / 2; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / RATE) * 32768 * 0.25);
            tone[2 * i] = (byte) v;
            tone[2 * i + 1] = (byte) (v >> 8);
        }
        byte[] in = new byte[RATE / 10 * 2];
        long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (up && SystemClock.elapsedRealtime() < until) {
            track.write(tone, 0, tone.length);
            mic.read(in, 0, in.length);
        }
        mic.stop();
        mic.release();
        track.stop();
        track.release();
        audio.setMode(AudioManager.MODE_NORMAL);
        Log.i(TAG, "standin: call down");
        runOnUiThread(this::finish);
    }
}
