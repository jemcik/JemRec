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
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;

/**
 * A stand-in for a messenger call, for tools/emulator_checks.py: the audio
 * mode at MODE_IN_COMMUNICATION, call audio playing and the microphone open,
 * as WhatsApp's or a meeting's would be - so that the answering machine can be
 * checked to leave a phone call ringing over it. NOT shipped.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar app_process / \
 *       com.jemcik.jemrec.probe.FakeCallProbe <seconds>
 *
 * Prints the audio mode once a second, so a check can see whether a phone call
 * ringing over it takes the mode away.
 */
public final class FakeCallProbe {

    private static final int RATE = 16000;

    private FakeCallProbe() {
    }

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        // Before ANY mention of Workarounds - see VoipProbe.
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        Context context = FakeContext.get();
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        for (String name : new String[] {"mOriginalContext", "mApplicationContext"}) {
            Field field = AudioManager.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(audio, context);
        }

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
        AudioRecord mic = new AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .build();
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        track.play();
        mic.startRecording();
        System.out.println("fakecall: up, mode=" + audio.getMode());

        byte[] quiet = new byte[RATE / 10 * 2];
        byte[] in = new byte[RATE / 10 * 2];
        long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        long nextSay = 0;
        while (SystemClock.elapsedRealtime() < until) {
            track.write(quiet, 0, quiet.length);
            mic.read(in, 0, in.length);
            if (SystemClock.elapsedRealtime() >= nextSay) {
                System.out.println("fakecall: mode=" + audio.getMode());
                nextSay = SystemClock.elapsedRealtime() + 1000;
            }
        }
        mic.stop();
        mic.release();
        track.stop();
        track.release();
        audio.setMode(AudioManager.MODE_NORMAL);
        System.out.println("fakecall: down");
        System.exit(0);
    }
}
