package com.jemcik.jemrec.shell;

import android.media.MediaCodec;
import android.os.SystemClock;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.util.Ln;

import java.nio.ByteBuffer;

/**
 * A recording's capture, made anew when a read fails (RestartPolicy says
 * when), so one failure mid-call does not end the recording. Any capture: a
 * phone call's (PhoneCapture, made anew where the call stood - CallPath) and
 * a messenger call's (without the microphone if the guard had given it
 * back - Voip.micKept). Each time is written into the log, and into the
 * call's report (Copy a report) until the recording has stopped - then the
 * stop and the way back, not each slow try. The loop that decides is RestartLoop's, plain Java
 * and tested; this is scrcpy's AudioCapture around it.
 */
final class Restarting implements AudioCapture {

    /** A new capture of the kind, not started, taking up where [failed] -
     *  the one read last, stopped, or a first one that would not start -
     *  left the call; null if none can be made. */
    interface Maker {
        @Nullable AudioCapture make(AudioCapture failed);
    }

    /** What the call is told of the recording stopping and going on: the
     *  app's notification, the answering machine (RecordingSession). */
    interface Told {
        /** Stopped, and tried again slowly; [forGood]: not even that.
         *  Nothing has reached the recording since [sinceMs],
         *  SystemClock.elapsedRealtime; -1 when nothing ever did. */
        void stopped(boolean forGood, long sinceMs);

        void back();

        /** A read delivered at [atMs] while stopped, before it is back. */
        default void delivered(long atMs) {
        }
    }

    private final RestartLoop<AudioCapture> loop;

    Restarting(AudioCapture first, Maker maker, CallReport report, Told told) {
        this.loop = new RestartLoop<>(first, new RestartLoop.Captures<AudioCapture>() {
            @Override
            public @Nullable AudioCapture make(AudioCapture failed) {
                return maker.make(failed);
            }

            @Override
            public void start(AudioCapture capture) throws Exception {
                capture.start();
            }

            @Override
            public void stop(AudioCapture capture) {
                // Tolerated as stopFailed's is: a new capture that would not
                // start may refuse its stop as one that never started does,
                // and thrown out of the encoder's input thread - which takes
                // only IOException and InterruptedException - it ended the
                // recording (review round 10; no capture throws so yet).
                try {
                    capture.stop();
                } catch (RuntimeException e) {
                    Ln.w("recording: a capture would not stop: " + e.getMessage());
                }
            }

            @Override
            public void sleep(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }

            @Override
            public void log(String line, boolean error) {
                if (error) {
                    Ln.e("recording: " + line);
                } else {
                    Ln.w("recording: " + line);
                }
            }

            @Override
            public void note(String what) {
                report.note(what);
            }

            @Override
            public long nowMs() {
                return SystemClock.elapsedRealtime();
            }

            @Override
            public void stopFailed(AudioCapture failed) {
                // Any capture: a phone call's on the mixer is a PhoneCapture
                // reading a Voip one, and with no messenger switched on its
                // stop let the copy's policy go - one made again while the
                // audio server is still down may be refused (review,
                // 2026-11-18). Where none is registered, nothing is held.
                Voip.holdPolicy();
                // One that never started may refuse its stop: said, and the
                // restart goes on (review, 2026-11-18).
                try {
                    failed.stop();
                } catch (RuntimeException e) {
                    Ln.w("recording: the failed capture would not stop: " + e.getMessage());
                }
            }

            @Override
            public void restartOver() {
                Voip.releaseHold();
            }

            @Override
            public void stopped(long sinceMs) {
                told.stopped(false, sinceMs);
            }

            @Override
            public void delivered(long atMs) {
                told.delivered(atMs);
            }

            @Override
            public void back() {
                told.back();
            }
        });
    }

    @Override
    public void checkCompatibility() throws AudioCaptureException {
        loop.current().checkCompatibility();
    }

    /** The first capture's start; one that will not start goes the
     *  restart's way, not the end of the recording (RestartLoop.firstFailed). */
    @Override
    public void start() throws AudioCaptureException {
        try {
            loop.current().start();
        } catch (AudioCaptureException | RuntimeException e) {
            Ln.w("recording: the capture would not start (" + e.getMessage() + "); making it anew");
            loop.firstFailed();
        }
    }

    @Override
    public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
        return loop.read(capture -> capture.read(outDirectBuffer, outBufferInfo));
    }

    @Override
    public void stop() {
        loop.stop();
    }

    /** When a read last went to the encoder; -1 for never (StallWatch). */
    long lastDeliveredAt() {
        return loop.lastDeliveredAt();
    }

    /** The loop has said the recording stopped, and is not back. */
    boolean slow() {
        return loop.slow();
    }
}
