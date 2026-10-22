package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.audio.AudioConfig;

import android.media.MediaCodec;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A phone call's capture, unchanged, with a look at each chunk on its way to
 * the encoder: the answering machine needs to know when the caller first made
 * a sound (a message starts just before it), and the diagnostics whether the
 * check tone is in the phone's own recording. The PCM is read, never altered.
 */
final class LevelTap implements AudioCapture {

    interface Listener {
        /** A chunk of interleaved stereo [pcm], [frames] long, read at
         *  [atMs] - SystemClock.elapsedRealtime(), the answering machine's
         *  clock, which a wall-clock step mid-call does not move. */
        void chunk(short[] pcm, int frames, long atMs);
    }

    private final AudioCapture inner;
    private final Listener listener;
    private final short[] samples = new short[AudioConfig.MAX_READ_SIZE / AudioConfig.BYTES_PER_SAMPLE];

    LevelTap(AudioCapture inner, Listener listener) {
        this.inner = inner;
        this.listener = listener;
    }

    @Override
    public void checkCompatibility() throws AudioCaptureException {
        inner.checkCompatibility();
    }

    @Override
    public void start() throws AudioCaptureException {
        inner.start();
    }

    @Override
    public void stop() {
        inner.stop();
    }

    @Override
    public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
        int r = inner.read(outDirectBuffer, outBufferInfo);
        if (r > 0) {
            try {
                int count = Math.min(r / AudioConfig.BYTES_PER_SAMPLE, samples.length);
                ByteBuffer view = outDirectBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                view.position(outBufferInfo.offset);
                view.limit(outBufferInfo.offset + count * AudioConfig.BYTES_PER_SAMPLE);
                view.asShortBuffer().get(samples, 0, count);
                listener.chunk(samples, count / AudioConfig.CHANNELS, SystemClock.elapsedRealtime());
            } catch (Throwable ignored) {
                // A look that fails must not cost the recording.
            }
        }
        return r;
    }
}
