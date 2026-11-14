package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioEncoder;
import com.genymobile.scrcpy.audio.AudioSource;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.Ln;

import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;

/**
 * One call's recording, written straight to a file by the daemon.
 *
 * This is the same capture and encoder the app used to drive over the
 * socket, pointed at a FileDescriptor instead. The framed format the
 * Streamer writes is exactly what the app's reader already understands, so
 * the file IS the stream, just complete and at rest - the app muxes it to
 * .ogg later, offline, with no chance of losing the tail to a stall.
 */
final class RecordingSession {

    /**
     * Both directions of the call. This is the whole point of the project, and
     * whether it actually carries the far end is a property of the device's
     * audio HAL rather than of this code. The app's self-test checks it.
     */
    static final AudioSource AUDIO_SOURCE = AudioSource.VOICE_CALL;

    /** Opus at 48 kHz stereo. scrcpy's default, and a sane one for speech. */
    static final AudioCodec AUDIO_CODEC = AudioCodec.OPUS;
    static final int BIT_RATE = 128_000;

    /** Send the codec id, and per-packet pts and length. The app needs the pts
     *  to feed MediaMuxer. */
    static final boolean SEND_CODEC_META = true;
    static final boolean SEND_FRAME_META = true;

    /** A phone call's capture: VOICE_CALL at once, switched to the messenger
     *  capture while the call plays through the mixer (PhoneCapture). */
    private static AudioCapture phoneCapture(CallReport report) {
        return new PhoneCapture(new PhoneCapture.Call() {
            @Override
            public AudioCapture voice() {
                return new AudioDirectCapture(AUDIO_SOURCE);
            }

            @Override
            public @Nullable AudioCapture mixer() {
                return Voip.newPhoneCapture();
            }

            @Override
            public @Nullable Boolean phoneCallInTelecom() {
                return Platform.phoneCallInTelecom();
            }

            @Override
            public boolean appInCall() {
                return Platform.appInCall();
            }

            @Override
            public void lookAround() {
                Reports.lookAround(report);
            }
        }, Platform.callAudio, report);
    }
    final String name;
    final boolean incoming;
    /** When it started on the wall clock: the file's name, and the app's. */
    final long startedAt;
    /** When it started on SystemClock.elapsedRealtime, for its age: a wall
     *  clock set back or forward in a call (NITZ, NTP, by hand) would make
     *  a minutes-old recording look new, or a new one old. Read with
     *  startedAt. */
    final long startedElapsed;
    /** The messenger whose call this is, or null for a phone call. */
    final @Nullable String app;
    private final File file;
    private final FileOutputStream out;
    private final AsyncProcessor recorder;
    /** What Copy a report says about this call (CallReport). */
    final CallReport report;

    private RecordingSession(String name, boolean incoming, long startedAt, long startedElapsed, @Nullable String app,
                             File file, FileOutputStream out, AsyncProcessor recorder, CallReport report) {
        this.name = name;
        this.incoming = incoming;
        this.app = app;
        this.startedAt = startedAt;
        this.startedElapsed = startedElapsed;
        this.file = file;
        this.out = out;
        this.recorder = recorder;
        this.report = report;
    }

    static RecordingSession start(boolean incoming, @Nullable String app, @Nullable Boolean appIncoming)
            throws Exception {
        // in/out is baked into the name so an orphan collected after a
        // reboot - when the daemon no longer remembers the call - still
        // knows which it was. A messenger call carries its app, and which
        // way it went only where Telecom said (Checks.recfileTag).
        long startedAt = System.currentTimeMillis();
        long startedElapsed = SystemClock.elapsedRealtime();
        String name = Paths.REC_PREFIX + startedAt + Checks.recfileTag(incoming, app, appIncoming) + Paths.REC_SUFFIX;
        // Every call through the level tap, for its report's levels; a
        // phone call this process answered for the answering machine and
        // its diagnostics too. They look at what is recorded, never change
        // it. The machine answered before the off-hook that starts this,
        // so that is known now.
        AnswerMachine machine = Answering.answerMachine;
        boolean answered = app == null && machine != null && machine.answeredThisCall();
        CallReport report = new CallReport(Checks.callInWords(incoming, app, appIncoming, answered),
                SystemClock::elapsedRealtime);
        AudioCapture inner = app != null ? Voip.newCapture() : phoneCapture(report);
        if (inner == null) {
            throw new IllegalStateException("messenger calls are not armed");
        }
        if (app != null) {
            report.route(CallReport.Route.MESSENGER, -1);
        }
        AudioCapture capture = new LevelTap(inner, (pcm, frames, atMs) -> {
            report.chunk(pcm, frames, AudioConfig.CHANNELS);
            if (answered) {
                Answering.onChunk(pcm, frames, atMs);
            }
        });
        File file = new File(Paths.REC_DIR, name);
        FileOutputStream out = new FileOutputStream(file);
        // Until the encoder has it, the file is this method's to close: a
        // start that failed here - a thread the phone cannot make - left its
        // descriptor to the garbage collector, if that ever came (the file
        // stays, empty, as before).
        AsyncProcessor recorder = Closing.orClose(out, () -> {
            // Fails fast and loudly on a device whose HAL will not give us this
            // source, rather than writing a silent or one-sided file.
            capture.checkCompatibility();
            Streamer streamer = new Streamer(out.getFD(), AUDIO_CODEC, SEND_CODEC_META, SEND_FRAME_META);
            AsyncProcessor encoder = new AudioEncoder(capture, streamer, BIT_RATE, null, null);
            encoder.start(fatalError -> {
                if (fatalError) {
                    Ln.e("recording: encoder terminated with an error");
                }
            });
            return encoder;
        });
        Ln.i("recording: started -> " + name);
        Reports.lastReport = report;
        // The first look at what plays: by then a call's players are up.
        Reports.lookSoon(report);
        return new RecordingSession(name, incoming, startedAt, startedElapsed, app, file, out, recorder, report);
    }

    void stop() {
        try {
            recorder.stop();
            recorder.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Ln.w("recording: error while stopping the encoder: " + t.getMessage());
        }
        Shell.closeQuietly(out);
        Ln.i("recording: stopped -> " + name + " (" + file.length() + " bytes)");
        report.ended();
        Reports.keepReport(report);
    }
}
