package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;
import android.media.MediaCodec;
import android.os.Build;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A phone call's capture: the modem's voice path, VOICE_CALL, from the first
 * moment, as every call was recorded - and the messenger capture instead
 * (Voip.newPhoneCapture) while the audio mode says the call plays through
 * the mixer (Checks.phoneCallInMixer: Wi-Fi Calling on a Galaxy S23), which
 * has both sides where VOICE_CALL has only silence.
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
 *
 * AND FOLLOWED TO THE END OF THE CALL (0.24). The mode was asked that once,
 * and a call whose mode changed afterwards stayed where the first answer put
 * it: a Wi-Fi call that turned "communication" after the off-hook, or a call
 * handed over between mobile and Wi-Fi, lost the other side for the rest of
 * it. A Galaxy M34's Wi-Fi call came out with its owner's voice alone (issue
 * #37, 2026-10-26) - one of the ways that happens, not measured there. Now
 * each change of mode, once it has held for SETTLE_MS (Telecom and apps flip
 * it while they switch routes), is put to Checks.mixerAfterModeChange, and
 * the capture is swapped if it says so; the one the change put on the mixer
 * is asked again each RECHECK_MS, in case it was another app's call. Only on
 * top of what 0.23 did: a call that began on the mixer leaves it only for the
 * modem's voice path, and one held off it is recorded as 0.23 did. Measured
 * on the Android 17 emulator: see tools/emulator_checks.py, mode_switch.
 *
 * THE SWAP. The new capture is started on the mode's thread, then handed to
 * the encoder's thread, which takes it at its next read and stops the old
 * one: a capture is never stopped while it is being read. stop() and the swap
 * hold the same lock, so each capture is stopped exactly once - one started
 * for a swap that never came and left running would hold the microphone.
 */
final class PhoneCapture implements AudioCapture {

    /** What a phone call's capture needs from the daemon (Main). */
    interface Call {
        /** A new VOICE_CALL capture, not started. */
        AudioCapture voice();

        /** A new mixer capture, not started - null where Android refused
         *  the copy (Voip.newPhoneCapture). */
        @Nullable AudioCapture mixer();

        /** Whether Telecom holds a phone call; null if it cannot be asked. */
        @Nullable Boolean phoneCallInTelecom();

        /** Whether an app is in a call of its own through Telecom, as
         *  Google Meet's are; true when that cannot be told, which leaves
         *  the call as 0.23 recorded it. */
        boolean appInCall();

        /** A look at what is playing and capturing, into the call's report. */
        void lookAround();
    }

    /** A mode change counts once it has held this long. */
    static final long SETTLE_MS = 500L;
    /** How often a call put on the mixer mid-call is asked about again. */
    static final long RECHECK_MS = 1000L;
    /** A look at what is playing this long after a swap: long enough for
     *  the call's players to start, short enough for any call. */
    static final long LOOK_AFTER_MS = 3000L;

    private static final class Handover {
        final AudioCapture capture;
        final boolean mixer;
        final int mode;

        Handover(AudioCapture capture, boolean mixer, int mode) {
            this.capture = capture;
            this.mixer = mixer;
            this.mode = mode;
        }
    }

    private final Call call;
    private final @Nullable AudioManager audio;
    private final CallReport report;

    // Guarded by this: the capture being read, the one waiting to take over,
    // and whether this was stopped.
    private @Nullable AudioCapture current;
    private @Nullable Handover next;
    private boolean stopped;

    // Set by start(), read by stop() on another thread.
    private volatile @Nullable ScheduledExecutorService modeThread;
    private volatile @Nullable AudioManager.OnModeChangedListener modeListener;

    // The mode's thread only.
    private @Nullable ScheduledFuture<?> decision;
    private boolean asked;
    /** The last mode told: Android also tells the one there was as it
     *  takes the listener (the Android 17 emulator, 2026-10-27). */
    private int lastMode = -1;
    /** Where the decisions have put the call: the mixer or the call capture. */
    private boolean onMixer;
    /** The mode said "communication" as the call went off-hook: 0.23's
     *  mixer call, which nothing else playing takes off it. */
    private boolean startedOnMixer;
    /** The mixer by a change during the call: asked about again. */
    private boolean mixerMidCall;

    PhoneCapture(Call call, @Nullable AudioManager audio, CallReport report) {
        this.call = call;
        this.audio = audio;
        this.report = report;
    }

    @Override
    public void checkCompatibility() throws AudioCaptureException {
        call.voice().checkCompatibility();
    }

    @Override
    public void start() throws AudioCaptureException {
        AudioCapture voice = call.voice();
        voice.start();
        synchronized (this) {
            current = voice;
        }
        report.route(CallReport.Route.CALL_CAPTURE, -1);
        AudioManager am = audio;
        if (am == null) {
            return;
        }
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jemrec-call-mode");
            t.setDaemon(true);
            return t;
        });
        modeThread = thread;
        // Listening first: a change that comes while the first ask waits is
        // then decided after it, never lost between the two.
        try {
            AudioManager.OnModeChangedListener listener = mode -> modeChanged(mode);
            am.addOnModeChangedListener(thread, listener);
            modeListener = listener;
        } catch (Throwable t) {
            Ln.w("recording: no audio mode callback, so the call stays the way its first mode put it: " + t);
            report.note("no audio mode callback: " + t);
        }
        thread.execute(this::askMode);
    }

    private void askMode() {
        AudioManager am = audio;
        if (am == null) {
            return; // start() does not run this then
        }
        long asked = SystemClock.elapsedRealtime();
        int mode;
        try {
            mode = am.getMode();
        } catch (Throwable t) {
            // The start stays on the voice path; the changes are still
            // followed, each read again when it is decided.
            this.asked = true;
            Ln.w("recording: could not read the audio mode; the call's voice path: " + t);
            report.note("could not read the audio mode: " + t);
            return;
        }
        long took = SystemClock.elapsedRealtime() - asked;
        this.asked = true;
        lastMode = mode;
        report.note("audio mode " + mode + " as the call went off-hook (told in " + took + " ms)");
        if (isStopped() || !Checks.phoneCallInMixer(mode, Build.VERSION.SDK_INT)) {
            Ln.i("recording: the call's voice path (audio mode " + mode + ", told in " + took + " ms)");
            return;
        }
        if (swap(true, mode, false)) {
            startedOnMixer = true;
            Ln.i("recording: the call plays through the mixer (audio mode " + mode + ", told in " + took
                    + " ms); recording it as messenger calls are");
        }
    }

    /** The mode's thread: a change, decided once it has held. */
    private void modeChanged(int mode) {
        // Before the first ask has answered, the ask reads it anyway.
        if (isStopped() || !asked || mode == lastMode) {
            return;
        }
        lastMode = mode;
        Ln.i("recording: audio mode " + mode + " during the call");
        report.note("audio mode " + mode);
        decideIn(SETTLE_MS);
    }

    private void decideIn(long ms) {
        ScheduledExecutorService thread = modeThread;
        if (thread == null) {
            return;
        }
        ScheduledFuture<?> pending = decision;
        if (pending != null) {
            pending.cancel(false);
        }
        try {
            decision = thread.schedule(this::decide, ms, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // Shut down: the call is over.
        }
    }

    /** The mode's thread: which capture now, and the swap if it changed. */
    private void decide() {
        AudioManager am = audio;
        // The first ask decides the start; a change before it waits for it.
        if (am == null || isStopped() || !asked) {
            return;
        }
        int mode;
        try {
            mode = am.getMode();
        } catch (Throwable t) {
            Ln.w("recording: could not read the audio mode: " + t);
            return;
        }
        // Telecom is asked only where its answer can change anything: every
        // phone call's end is a change of mode too.
        boolean ask = Checks.phoneCallInMixer(mode, Build.VERSION.SDK_INT) && !startedOnMixer;
        boolean appCall = ask && call.appInCall();
        Boolean phoneCall = ask ? call.phoneCallInTelecom() : null;
        boolean mixer = Checks.mixerAfterModeChange(onMixer, startedOnMixer, mode, Build.VERSION.SDK_INT,
                phoneCall, appCall);
        if (mixer != onMixer) {
            String why = mixer ? "the call plays through the mixer now"
                    : mode == Checks.MODE_IN_CALL ? "the call is back on the modem's voice path"
                    : appCall ? "another app's call is up" : "no phone call is left in Telecom";
            if (swap(mixer, mode, true)) {
                Ln.i("recording: " + why + " (audio mode " + mode + "); " + (mixer
                        ? "recording it as messenger calls are" : "recording it through the call capture again"));
                report.note(why + ": " + (mixer ? "to the mixer" : "to the call capture"));
            }
        } else if (!mixer && Checks.phoneCallInMixer(mode, Build.VERSION.SDK_INT)) {
            String why = appCall ? "another app's call is up" : "no phone call is left in Telecom";
            Ln.i("recording: audio mode " + mode + ", but " + why + "; the call capture stays");
            report.note(why + ": the call capture stays");
        }
        if (onMixer && mixerMidCall) {
            decideIn(RECHECK_MS);
        }
    }

    /**
     * Start the capture for [mixer] and hand it to the reader. False, with
     * nothing changed, if it cannot be had or this was stopped meanwhile.
     */
    private boolean swap(boolean mixer, int mode, boolean midCall) {
        AudioCapture capture = mixer ? call.mixer() : call.voice();
        if (capture == null) {
            report.note("the copy of the call's audio was refused; the call capture stays");
            return false; // Voip.newPhoneCapture logged why
        }
        try {
            capture.start();
        } catch (Throwable t) {
            capture.stop();
            Ln.w("recording: the " + (mixer ? "mixer" : "call") + " capture would not start; staying as it is: " + t);
            report.note("the " + (mixer ? "mixer" : "call") + " capture would not start: " + t);
            return false;
        }
        Handover replaced = null;
        boolean handed = false;
        synchronized (this) {
            // After stop() it is nobody else's to stop.
            if (!stopped) {
                replaced = next;
                next = new Handover(capture, mixer, mode);
                handed = true;
            }
        }
        if (!handed) {
            capture.stop();
            return false;
        }
        if (replaced != null) {
            // Never read: the reader had not come for it yet.
            replaced.capture.stop();
        }
        onMixer = mixer;
        // A call that began on the mixer is never taken off it but for the
        // modem's voice path: nothing to ask again.
        mixerMidCall = mixer && midCall && !startedOnMixer;
        ScheduledExecutorService thread = modeThread;
        if (thread != null) {
            try {
                // AudioScene says in the report what went wrong, if anything.
                ScheduledFuture<?> unused = thread.schedule(call::lookAround, LOOK_AFTER_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // Shut down: the call is over.
            }
        }
        return true;
    }

    private synchronized boolean isStopped() {
        return stopped;
    }

    @Override
    public int read(ByteBuffer outDirectBuffer, MediaCodec.BufferInfo outBufferInfo) {
        AudioCapture reading;
        AudioCapture old = null;
        Handover took = null;
        synchronized (this) {
            if (!stopped && next != null) {
                took = next;
                old = current;
                current = took.capture;
                next = null;
            }
            reading = current;
        }
        if (took != null) {
            if (old != null) {
                old.stop();
            }
            report.route(took.mixer ? CallReport.Route.PHONE_MIXER : CallReport.Route.CALL_CAPTURE, took.mode);
        }
        if (reading == null) {
            return -1; // start() sets it, or throws
        }
        return reading.read(outDirectBuffer, outBufferInfo);
    }

    @Override
    public void stop() {
        AudioCapture reading;
        Handover waiting;
        synchronized (this) {
            stopped = true;
            reading = current;
            waiting = next;
            next = null;
        }
        AudioManager am = audio;
        AudioManager.OnModeChangedListener listener = modeListener;
        if (am != null && listener != null) {
            try {
                am.removeOnModeChangedListener(listener);
            } catch (Throwable ignored) {
                // Gone with the process soon enough; it only ever posts here.
            }
        }
        ScheduledExecutorService thread = modeThread;
        if (thread != null) {
            thread.shutdownNow();
        }
        if (reading != null) {
            reading.stop();
        }
        if (waiting != null) {
            waiting.capture.stop();
        }
    }
}
