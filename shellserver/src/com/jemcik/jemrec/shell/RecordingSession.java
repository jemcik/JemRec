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

    /** Opus at 48 kHz stereo, at the rate the app's Quality setting gives
     *  (Quality). */
    static final AudioCodec AUDIO_CODEC = AudioCodec.OPUS;

    /** Send the codec id, and per-packet pts and length. The app needs the pts
     *  to feed MediaMuxer. */
    static final boolean SEND_CODEC_META = true;
    static final boolean SEND_FRAME_META = true;

    /** A phone call's capture: VOICE_CALL at once, switched to the messenger
     *  capture while the call plays through the mixer (PhoneCapture); with
     *  [from], where the call stood, one made anew for the same call
     *  (CallPath). */
    private static PhoneCapture phoneCapture(CallReport report, @Nullable CallPath from) {
        return new PhoneCapture(new PhoneCapture.Call() {
            @Override
            public AudioCapture voice() {
                return new AudioDirectCapture(AUDIO_SOURCE);
            }

            @Override
            public @Nullable AudioCapture mixer(boolean withMic) {
                return Voip.newPhoneCapture(withMic);
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
        }, Platform.callAudio, report, from);
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
    /** Where the recording stopped before stop() asked: its encoder's early
     *  end, or a stall it was never back from (Ending, RecordingLoss). */
    private final Ending ending;
    /** How much of the call the recording missed at its end, in ms - from
     *  the encoder's early end, or a stall's last read delivered, to stop() -
     *  or -1: set by stop(), and put in the file's name (Calls,
     *  Checks.earlyRecfile). */
    volatile long missedMs = -1;

    /**
     * The encoder's end, early or asked for. It ends early when the codec or
     * the file fails - a capture that fails more often than it may be made
     * anew is tried again slowly instead (RestartPolicy), a stall the
     * recording may never come back from (RecordingLoss); either way the call
     * goes on unrecorded. The app could
     * tell that only from Telecom's end of a phone call, so a messenger call,
     * or one recorded with call tracking off, was never told its recording
     * stopped early (review, 2026-11-18): the name carries it now.
     */
    static final class Ending {
        private final Runnable early;
        private volatile boolean stopping;
        /** The encoder ended, whichever way: nothing more is read. */
        private volatile boolean ended;
        /** The stall watch and its thread, ended with the recording. */
        volatile @Nullable Thread watching;
        volatile @Nullable StallWatch stalls;
        /** What the recording misses to its end: an early end of the
         *  encoder's, or a stall never back from (RecordingLoss). */
        final RecordingLoss loss = new RecordingLoss();

        /** [early]: told as it ends before it is stopped. */
        Ending(Runnable early) {
            this.early = early;
        }

        void terminated() {
            // Under the watch's lock, as stop() sets stopping: no look is
            // half way past its check as it ends, to say a stop or back after
            // the end's for-good stop (review round 12).
            StallWatch watch = stalls;
            if (watch != null) {
                synchronized (watch) {
                    ended = true;
                }
            } else {
                ended = true;
            }
            if (!stopping) {
                loss.endedEarly(SystemClock.elapsedRealtime());
                early.run();
            }
        }
    }

    private RecordingSession(String name, boolean incoming, long startedAt, long startedElapsed, @Nullable String app,
                             File file, FileOutputStream out, AsyncProcessor recorder, CallReport report,
                             Ending ending) {
        this.name = name;
        this.incoming = incoming;
        this.app = app;
        this.startedAt = startedAt;
        this.startedElapsed = startedElapsed;
        this.file = file;
        this.out = out;
        this.recorder = recorder;
        this.report = report;
        this.ending = ending;
    }

    static RecordingSession start(boolean incoming, @Nullable String app, @Nullable Boolean appIncoming,
            boolean joined) throws Exception {
        // in/out is baked into the name so an orphan collected after a
        // reboot - when the daemon no longer remembers the call - still
        // knows which it was. A messenger call carries its app, and which
        // way it went only where Telecom said (Checks.recfileTag).
        long startedAt = System.currentTimeMillis();
        long startedElapsed = SystemClock.elapsedRealtime();
        String name = Paths.REC_PREFIX + startedAt + Checks.recfileTag(incoming, app, appIncoming, joined) + Paths.REC_SUFFIX;
        // Every call through the level tap, for its report's levels; a
        // phone call this process answered for the answering machine and
        // its diagnostics too. They look at what is recorded, never change
        // it. The machine answered before the off-hook that starts this,
        // so that is known now.
        AnswerMachine machine = Answering.answerMachine;
        boolean answered = app == null && machine != null && machine.answeredThisCall();
        // Read once: a change while this call records is the next one's.
        int bitRate = Quality.bitRate;
        CallReport report = new CallReport(Checks.callInWords(incoming, app, appIncoming, answered),
                Quality.words(bitRate), SystemClock::elapsedRealtime);
        // The recording stopped mid-call, or back: told to the app, whose
        // call notification says it during the call, and to the answering
        // machine, which hangs up on a message it can no longer keep - its
        // silence watch heard a caller still talking as silent (review,
        // 2026-11-18). Ordered with the call's other wakes; dropped once the
        // call is over, as its start is (Wakes).
        Ending[] ends = new Ending[1];
        Restarting.Told told = new Restarting.Told() {
            @Override
            public void stopped(boolean forGood, long sinceMs) {
                if (!forGood && ends[0] != null) {
                    ends[0].loss.stalled(sinceMs >= 0 ? sinceMs : startedElapsed);
                }
                // Whole, as the release check waits for them (LogLinesTest).
                Ln.w(forGood ? "recording: ended mid-call; telling the app"
                        : "recording: stopped mid-call; telling the app");
                if (answered) {
                    Answering.recordingStopped();
                }
                Wakes.wake(Wakes.ACTION_RECORDING_STOPPED, incoming, name, app,
                        "--ez", "for_good", String.valueOf(forGood));
            }

            @Override
            public void delivered(long atMs) {
                if (ends[0] != null) {
                    ends[0].loss.delivered(atMs);
                }
            }

            @Override
            public void back() {
                if (ends[0] != null) {
                    ends[0].loss.back();
                }
                if (answered) {
                    Answering.recordingBack();
                }
                Wakes.wake(Wakes.ACTION_RECORDING_BACK, incoming, name, app);
            }
        };
        AudioCapture first = app != null ? Voip.newCapture(true) : phoneCapture(report, null);
        if (first == null) {
            throw new IllegalStateException("messenger calls are not armed");
        }
        if (app != null) {
            report.route(CallReport.Route.MESSENGER, -1);
        }
        // Made anew if a read fails, a few times a minute (RestartPolicy).
        // Made anew where the failed one left the call: the microphone as
        // the guard left it, a phone call where its mode's decisions put it
        // (CallPath).
        Restarting inner = new Restarting(first, failed -> app != null
                ? Voip.newCapture(Voip.micKept(failed))
                : phoneCapture(report, failed instanceof PhoneCapture ? ((PhoneCapture) failed).path() : null),
                report, told);
        AudioCapture capture = new LevelTap(inner, (pcm, frames, atMs) -> {
            report.chunk(pcm, frames, AudioConfig.CHANNELS);
            if (answered) {
                Answering.onChunk(pcm, frames, atMs);
            }
        });
        File file = new File(Paths.REC_DIR, name);
        FileOutputStream out = new FileOutputStream(file);
        Ending ending = new Ending(() -> told.stopped(true, SystemClock.elapsedRealtime()));
        ends[0] = ending;
        // Until the encoder has it, the file is this method's to close: a
        // start that failed here - a thread the phone cannot make - left its
        // descriptor to the garbage collector, if that ever came (the file
        // stays, empty, as before).
        AsyncProcessor recorder = Closing.orClose(out, () -> {
            // Fails fast and loudly on a device whose HAL will not give us this
            // source, rather than writing a silent or one-sided file.
            capture.checkCompatibility();
            Streamer streamer = new Streamer(out.getFD(), AUDIO_CODEC, SEND_CODEC_META, SEND_FRAME_META);
            AsyncProcessor encoder = new AudioEncoder(capture, streamer, bitRate, null, null);
            encoder.start(fatalError -> {
                if (fatalError) {
                    Ln.e("recording: encoder terminated with an error");
                }
                ending.terminated();
            });
            return encoder;
        });
        // A stall no read fails in - a read that only blocks - said as any
        // stop (StallWatch), looked at each second until the recording ends.
        StallWatch stalls = new StallWatch(inner::lastDeliveredAt, inner::slow,
                () -> ending.stopping || ending.ended,
                SystemClock::elapsedRealtime, new StallWatch.Saying() {
                    @Override
                    public void stopped(long sinceMs) {
                        report.note("nothing reached the recording for " + StallWatch.STALL_MS / 1000
                                + " s, no read failing: stopped there");
                        told.stopped(false, sinceMs);
                    }

                    @Override
                    public void back() {
                        report.note("a read reached the recording again: it went on from there");
                        told.back();
                    }

                    @Override
                    public void lastRead(long atMs) {
                        told.delivered(atMs);
                    }
                });
        Thread watching = new Thread(() -> {
            try {
                while (!ending.stopping && !ending.ended) {
                    Thread.sleep(1_000L);
                    stalls.look();
                }
            } catch (InterruptedException ignored) {
                // The recording stopped.
            }
        }, "jemrec-stall-watch");
        watching.setDaemon(true);
        watching.start();
        ending.watching = watching;
        ending.stalls = stalls;
        Ln.i("recording: started at " + Quality.words(bitRate) + " -> " + name);
        Reports.lastReport = report;
        // The first look at what plays: by then a call's players are up.
        Reports.lookSoon(report);
        return new RecordingSession(name, incoming, startedAt, startedElapsed, app, file, out, recorder, report,
                ending);
    }

    void stop() {
        long stoppedAt = SystemClock.elapsedRealtime();
        StallWatch stalls = ending.stalls;
        if (stalls != null) {
            // Under the watch's own lock: no look is half way past its check
            // of the stop as it is set - a stop said after a stop that leaves
            // the call going (an Ask first takeover) put "Trying again" on a
            // call whose prompt was up (review round 11). And a stall whose
            // reads came back is no loss to the end.
            synchronized (stalls) {
                ending.stopping = true;
            }
            stalls.closeOut();
        } else {
            ending.stopping = true;
        }
        Thread watching = ending.watching;
        if (watching != null) {
            watching.interrupt();
        }
        try {
            recorder.stop();
            recorder.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Ln.w("recording: error while stopping the encoder: " + t.getMessage());
        }
        Shell.closeQuietly(out);
        long early = ending.loss.since();
        if (early >= 0) {
            missedMs = Math.max(0, stoppedAt - early);
            report.note("the recording had stopped " + missedMs + " ms before it was stopped (ended, or nothing reached it)");
        }
        Ln.i("recording: stopped -> " + name + " (" + file.length() + " bytes)"
                + (early >= 0 ? "; it had stopped " + missedMs + " ms before" : ""));
        report.ended();
        Reports.keepReport(report);
    }
}
