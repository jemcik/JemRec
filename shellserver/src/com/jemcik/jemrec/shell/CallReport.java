package com.jemcik.jemrec.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * What the app's Copy a report (Settings, Diagnostics) says about the last
 * call recorded: which way it was recorded and when that changed, how loud
 * each channel came out each way, the audio mode as it went, and what was
 * playing and capturing on the phone a moment in. Nothing about the call
 * itself: no number, no name, no sound.
 *
 * WHY. Phones carry calls in ways that only show on the phone. On a Galaxy
 * S23 a Wi-Fi call played through the mixer and the call capture heard
 * nothing (2026-10-24); on a Galaxy M34 a Wi-Fi call came out with its
 * owner's voice alone (issue #37, 2026-10-26), and nothing the reporter could
 * see said which way it had been recorded - the recorder's log, which does,
 * is out of reach without a computer, and replaced at its next start. This is
 * that log's part about one call, kept until the next, in words a user can
 * paste.
 *
 * Plain Java, like the daemon's other decisions, so the wording and the
 * arithmetic are tested off the phone; AudioScene reads the phone into it.
 * Every method is called from more than one thread - the encoder's (levels),
 * the call's mode watch (PhoneCapture), the main looper (scenes, the end) -
 * hence synchronized.
 */
final class CallReport {

    /** The ways a call is recorded, and whose voice each channel holds. */
    enum Route {
        CALL_CAPTURE("the call capture (VOICE_CALL)", "left", "right"),
        PHONE_MIXER("the mixer: a copy of the call's audio, and the microphone",
                "left (you, the microphone)", "right (the other side, the copy)"),
        MESSENGER("the messenger capture: a copy of the call's audio, and the microphone",
                "left (the other side, the copy)", "right (you, the microphone)");

        final String words;
        final String left;
        final String right;

        Route(String words, String left, String right) {
            this.words = words;
            this.left = left;
            this.right = right;
        }
    }

    /** A few looks around are plenty; a long call must not grow this. */
    static final int MAX_SCENES = 4;
    static final int MAX_NOTES = 20;
    /** At least this share of a channel's samples exactly zero is worth
     *  saying: digital silence is "nothing reached it", not a quiet room. */
    private static final double ZEROS_WORTH_SAYING = 0.5;

    /** One stretch recorded one way, with its levels. */
    private static final class Segment {
        final long atMs;
        final Route route;
        final int mode;
        final double[] sumSquares = new double[2];
        final int[] peak = new int[2];
        final long[] zeros = new long[2];
        long frames;

        Segment(long atMs, Route route, int mode) {
            this.atMs = atMs;
            this.route = route;
            this.mode = mode;
        }
    }

    private final String call;
    private final LongSupplier clock;
    private final long startMs;
    private final List<Segment> segments = new ArrayList<>();
    private final List<String> scenes = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int notesLeftOut;
    private long endMs = -1;

    /** [call]: what kind of call, in words - "a phone call, incoming".
     *  [clock]: milliseconds, SystemClock.elapsedRealtime on the phone. */
    CallReport(String call, LongSupplier clock) {
        this.call = call;
        this.clock = clock;
        this.startMs = clock.getAsLong();
    }

    /** From now on the recording comes [route]'s way; [mode] is the audio
     *  mode that decided it, -1 if none was read. */
    synchronized void route(Route route, int mode) {
        segments.add(new Segment(at(), route, mode));
    }

    /** Something worth knowing that the routes do not say: the mode
     *  changing, a switch Android refused, a microphone given back. */
    synchronized void note(String what) {
        if (notes.size() < MAX_NOTES) {
            notes.add(time(at()) + "  " + what);
        } else {
            notesLeftOut++;
        }
    }

    /** What was playing and capturing, read now: lines from player() and
     *  capture(). Whether the copy is armed: [copyArmed]. */
    synchronized void scene(int mode, boolean copyArmed, List<String> playing, List<String> capturing) {
        if (scenes.size() >= MAX_SCENES) {
            return;
        }
        StringBuilder s = new StringBuilder();
        s.append(time(at())).append("  audio mode ").append(mode)
                .append(copyArmed ? ", the copy armed" : ", the copy not armed").append('\n');
        appendList(s, "playing", playing);
        appendList(s, "capturing", capturing);
        scenes.add(s.toString());
    }

    private static void appendList(StringBuilder s, String what, List<String> lines) {
        String head = "         " + what + ": ";
        if (lines.isEmpty()) {
            s.append(head).append("nothing\n");
            return;
        }
        String indent = " ".repeat(head.length());
        for (int i = 0; i < lines.size(); i++) {
            s.append(i == 0 ? head : indent).append(lines.get(i)).append('\n');
        }
    }

    /** A chunk of the recording, interleaved stereo, into the current
     *  route's levels. Before any route: nothing to put it in. */
    synchronized void chunk(short[] pcm, int frames, int channels) {
        if (segments.isEmpty() || channels < 2) {
            return;
        }
        Segment seg = segments.get(segments.size() - 1);
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < 2; c++) {
                int v = pcm[f * channels + c];
                seg.sumSquares[c] += (double) v * v;
                seg.peak[c] = Math.max(seg.peak[c], Math.abs(v));
                if (v == 0) {
                    seg.zeros[c]++;
                }
            }
        }
        seg.frames += frames;
    }

    /** The recording stopped. */
    synchronized void ended() {
        if (endMs < 0) {
            endMs = clock.getAsLong();
        }
    }

    synchronized String text() {
        StringBuilder s = new StringBuilder();
        long end = endMs >= 0 ? endMs : clock.getAsLong();
        s.append("Last call: ").append(call).append(". ")
                .append(endMs >= 0 ? "Recorded for " : "Still being recorded, ")
                .append(String.format(Locale.US, "%.1f s", (end - startMs) / 1000.0))
                .append(endMs >= 0 ? ".\n" : " so far.\n");
        s.append("How it was recorded:\n");
        if (segments.isEmpty()) {
            s.append("  nothing was recorded\n");
        }
        for (Segment seg : segments) {
            s.append(time(seg.atMs)).append("  ").append(seg.route.words);
            if (seg.mode >= 0) {
                s.append(", audio mode ").append(seg.mode);
            }
            s.append('\n').append("         ");
            if (seg.frames == 0) {
                s.append("no sound reached the recording this way\n");
            } else {
                s.append(seg.route.left).append(' ').append(level(seg, 0)).append("; ")
                        .append(seg.route.right).append(' ').append(level(seg, 1)).append('\n');
            }
        }
        if (!scenes.isEmpty()) {
            s.append("What was playing and capturing:\n");
            for (String scene : scenes) {
                s.append(scene);
            }
        }
        if (!notes.isEmpty()) {
            s.append("Along the way:\n");
            for (String note : notes) {
                s.append(note).append('\n');
            }
            if (notesLeftOut > 0) {
                s.append("         and ").append(notesLeftOut).append(" more\n");
            }
        }
        return s.toString().trim();
    }

    /** One channel of a stretch, as a level: digital silence said as such. */
    private static String level(Segment seg, int channel) {
        if (seg.zeros[channel] == seg.frames) {
            return "silent: every sample zero";
        }
        double rms = Math.sqrt(seg.sumSquares[channel] / seg.frames);
        String said = String.format(Locale.US, "%.1f dB (peak %.1f dB)", db(rms), db(seg.peak[channel]));
        double zeroShare = seg.zeros[channel] / (double) seg.frames;
        if (zeroShare >= ZEROS_WORTH_SAYING) {
            said += String.format(Locale.US, ", %d%% of it exact zeros", Math.round(zeroShare * 100));
        }
        return said;
    }

    /** Full scale, 32768, is 0 dB. */
    static double db(double level) {
        return level <= 0 ? -120 : 20 * Math.log10(level / 32768.0);
    }

    private long at() {
        return clock.getAsLong();
    }

    private String time(long atMs) {
        return String.format(Locale.US, "%7.1f s", (atMs - startMs) / 1000.0);
    }

    // --- one player or capture, in words --------------------------------------------

    /** A player, as AudioScene read it: whose [uid] ([who]), what for, how it
     *  may be captured, by what it plays, where to. -1: not known. */
    static String player(int uid, String who, int usage, int contentType, int capturePolicy, int playerType,
                         int flags, int device) {
        return "uid " + uid + " (" + who + "): " + usage(usage) + ", " + contentType(contentType)
                + "; " + capturePolicy(capturePolicy) + "; " + playerType(playerType)
                + (flags >= 0 ? String.format(Locale.US, "; flags 0x%x", flags) : "")
                + "; to " + device(device);
    }

    /** A capture: whose, from what source, whether Android silenced it, from where. */
    static String capture(int uid, String who, int source, boolean silenced, int device) {
        return "uid " + uid + " (" + who + "): " + source(source) + "; "
                + (silenced ? "SILENCED" : "not silenced") + "; from " + device(device);
    }

    /** Whose uid: the system's own by name, an app by its package. */
    static String who(int uid, int ownUid, String[] packages) {
        if (uid == ownUid) {
            return "the shell: this recorder";
        }
        switch (uid) {
            case 0: return "root";
            case 1000: return "system";
            case 1001: return "phone";
            case 1002: return "bluetooth";
            case 1041: return "audio server";
            case 1047: return "camera server";
            case 2000: return "shell";
            default: break;
        }
        if (uid < 0) {
            // Android 12 names no owner to the shell (Checks.someoneInCall).
            return "not named by Android";
        }
        if (packages.length == 0) {
            return uid < 10000 ? "a system uid" : "an app";
        }
        return packages.length == 1 ? packages[0] : packages[0] + " and " + (packages.length - 1) + " more";
    }

    // AudioAttributes.USAGE_*.
    static String usage(int usage) {
        switch (usage) {
            case 0: return "unknown use";
            case 1: return "media";
            case 2: return "voice call";
            case 3: return "call signalling";
            case 4: return "alarm";
            case 5: return "notification";
            case 6: return "ringtone";
            case 11: return "accessibility";
            case 12: return "navigation";
            case 13: return "system sounds";
            case 14: return "game";
            case 16: return "assistant";
            case 17: return "call assistant";
            default: return "usage " + usage;
        }
    }

    // AudioAttributes.CONTENT_TYPE_*.
    static String contentType(int type) {
        switch (type) {
            case 0: return "unknown content";
            case 1: return "speech";
            case 2: return "music";
            case 3: return "movie";
            case 4: return "sonification";
            default: return "content " + type;
        }
    }

    // AudioAttributes.ALLOW_CAPTURE_BY_*: the copy takes ALL and SYSTEM, never NONE.
    static String capturePolicy(int policy) {
        switch (policy) {
            case 1: return "any app may capture it";
            case 2: return "only the system may capture it";
            case 3: return "NOTHING may capture it";
            default: return "capture policy " + policy;
        }
    }

    // AudioPlaybackConfiguration.PLAYER_TYPE_* (hidden).
    static String playerType(int type) {
        switch (type) {
            case -1: return "player type unknown";
            case 1: return "AudioTrack";
            case 2: return "MediaPlayer";
            case 3: return "SoundPool";
            case 11: return "OpenSL ES";
            case 12: return "OpenSL ES (file)";
            case 13: return "AAudio";
            case 14: return "a hardware source";
            case 15: return "an external proxy";
            default: return "player type " + type;
        }
    }

    // MediaRecorder.AudioSource.*.
    static String source(int source) {
        switch (source) {
            case 0: return "default source";
            case 1: return "microphone";
            case 2: return "voice uplink";
            case 3: return "voice downlink";
            case 4: return "voice call";
            case 5: return "camcorder";
            case 6: return "voice recognition";
            case 7: return "voice communication";
            case 8: return "remote submix";
            case 9: return "unprocessed microphone";
            case 10: return "voice performance";
            case 1997: return "echo reference";
            case 1998: return "radio tuner";
            case 1999: return "hotword";
            default: return "source " + source;
        }
    }

    // AudioDeviceInfo.TYPE_*; -1 where Android named none.
    static String device(int type) {
        switch (type) {
            case -1: return "no device Android named";
            case 0: return "an unknown device";
            case 1: return "earpiece";
            case 2: return "speaker";
            case 3: return "wired headset";
            case 4: return "wired headphones";
            case 7: return "Bluetooth (call)";
            case 8: return "Bluetooth (media)";
            case 11: return "USB device";
            case 15: return "built-in microphone";
            case 18: return "telephony";
            case 22: return "USB headset";
            case 23: return "hearing aid";
            case 24: return "speaker (safe)";
            case 25: return "remote submix";
            case 26: return "Bluetooth LE headset";
            case 27: return "Bluetooth LE speaker";
            default: return "device " + type;
        }
    }
}
