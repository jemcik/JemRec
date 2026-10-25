package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The answering machine's greeting on this side: where it is kept, and the
 * user recording a new one.
 *
 * RECORDED HERE, NOT IN THE APP. The app holds no microphone permission and
 * says so in its permissions list; a call recorder that asks for the
 * microphone and does not use it is asking for trust it has not earned. This
 * process runs as the shell, which may record, so the user's "Record" opens the
 * microphone here, for as long as they hold it, and nowhere else.
 *
 * Raw 16-bit little-endian mono at 48 kHz throughout: the app trims and levels
 * a take (it can be tested there) and sends the result back as the greeting.
 * Both files are the shell's, 0600 under this process's umask.
 */
final class Greeting {

    static final File FILE = new File("/data/local/tmp/jemrec-greeting.pcm");
    static final File TAKE = new File("/data/local/tmp/jemrec-greeting-take.pcm");
    static final int RATE = GreetingPlayer.RATE;
    /** Thirty seconds, and a little room. */
    static final long MAX_MS = 30_000L;
    static final int MAX_BYTES = (int) (MAX_MS * RATE / 1000 * 2) + RATE;

    private Greeting() {
    }

    private static volatile @Nullable Thread recording;
    private static volatile boolean stopRequested;

    /** Start recording a take from the microphone, for up to MAX_MS. */
    static synchronized boolean startTake() {
        if (recording != null) {
            return false;
        }
        stopRequested = false;
        Thread t = new Thread(Greeting::recordTake, "jemrec-greeting-take");
        t.setDaemon(true);
        recording = t;
        t.start();
        return true;
    }

    /** Stop the take; returns its length in bytes, 0 if there is none. */
    static long stopTake() {
        Thread t;
        synchronized (Greeting.class) {
            t = recording;
            stopRequested = true;
        }
        if (t != null) {
            try {
                t.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return TAKE.isFile() ? TAKE.length() : 0;
    }

    private static void recordTake() {
        AudioRecord record = null;
        try (OutputStream out = new FileOutputStream(TAKE)) {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(8 * AudioRecord.getMinBufferSize(
                            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setContext(FakeContext.get());
            }
            record = builder.build();
            record.startRecording();
            Ln.i("greeting: recording a take");
            byte[] buf = new byte[RATE / 10 * 2];
            long written = 0;
            long limit = MAX_MS * RATE / 1000 * 2;
            while (!stopRequested && written < limit) {
                int n = record.read(buf, 0, buf.length);
                if (n < 0) {
                    throw new IOException("AudioRecord.read returned " + n);
                }
                out.write(buf, 0, n);
                written += n;
            }
            Ln.i("greeting: take of " + written * 1000 / (RATE * 2) + " ms");
        } catch (Throwable t) {
            Ln.e("greeting: could not record a take", t);
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                    // not started
                }
                record.release();
            }
            synchronized (Greeting.class) {
                recording = null;
            }
        }
    }

    /** Replace the greeting with [length] bytes from [in]. */
    static void put(InputStream in, int length) throws IOException {
        if (length <= 0 || length > MAX_BYTES) {
            throw new IOException("implausible greeting length " + length);
        }
        File next = new File(FILE.getPath() + ".new");
        try (OutputStream out = new FileOutputStream(next)) {
            byte[] buf = new byte[64 * 1024];
            int left = length;
            while (left > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, left));
                if (n < 0) {
                    throw new IOException("greeting cut short, " + left + " bytes missing");
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
        if (!next.renameTo(FILE)) {
            throw new IOException("could not store the greeting");
        }
        Ln.i("greeting: stored " + length + " bytes");
    }

    static boolean present() {
        return FILE.isFile() && FILE.length() > 0;
    }

    static void delete() {
        FILE.delete();
        TAKE.delete();
    }
}
