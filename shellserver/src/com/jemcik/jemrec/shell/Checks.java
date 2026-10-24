package com.jemcik.jemrec.shell;

/**
 * Small decisions of the daemon's, in plain Java so they can be tested
 * (shellserver/tests: ChecksTest) - each has been got wrong once, or is the
 * kind that would be.
 */
final class Checks {

    private Checks() {
    }

    /** The shell's uid: adb, and the tools run over it. */
    static final int SHELL_UID = 2000;

    /**
     * Whether `cmd appops get --uid` says the UID is allowed: its own line,
     * "Uid mode: MANAGE_ONGOING_CALLS: allow". The package's line follows it
     * and says "allow" too when only the package is allowed - which is not
     * enough - so "allow" anywhere in the output proves nothing. Lines may
     * come joined with " | " (Main.slurp) or with newlines.
     */
    static boolean uidAllowed(String said) {
        if (said == null) {
            return false;
        }
        for (String line : said.split("[|\n]")) {
            String l = line.trim();
            if (l.startsWith("Uid mode:") && l.endsWith(": allow")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a caller with [peer] uid may talk to the daemon, the app's uid
     * being [app]: JemRec itself, the shell, or root. Unknown on either side
     * (negative) is let through, to the handshake.
     */
    static boolean callerAllowed(int peer, int app) {
        return peer < 0 || peer == 0 || peer == SHELL_UID || app < 0 || peer == app;
    }

    /**
     * Whether `dumpsys media.audio_policy` shows what the answering machine's
     * greeting needs: 1 for the call's uplink device among the outputs
     * available now and an output with AUDIO_OUTPUT_FLAG_INCALL_MUSIC - the
     * one Qualcomm mixes into the uplink - and 0 for not; -1 for a dump that
     * could not be had, or read, which is no "no": a 0 blocks the answering
     * machine as certain. Android's own isPstnCallAudioInterceptable asks
     * only for the devices, and said yes on an Honor for a route the caller
     * never heard; the emulator has the devices and no such output.
     */
    static int inCallMusic(String policy) {
        if (policy == null) {
            return -1;
        }
        int from = policy.indexOf("Available output devices");
        if (from < 0) {
            return -1;
        }
        int to = policy.indexOf("Available input devices", from);
        String outputs = policy.substring(from, to > from ? to : policy.length());
        return outputs.contains("AUDIO_DEVICE_OUT_TELEPHONY_TX")
                && policy.contains("AUDIO_OUTPUT_FLAG_INCALL_MUSIC") ? 1 : 0;
    }

    /**
     * Where `dumpsys media.audio_flinger` shows process [pid]'s track active:
     * 1 on an in-call music output - the greeting going where the caller
     * hears it, rather than somewhere that looks the same from the app's
     * side - 0 on another output, and -1 nowhere to be read: a dump that
     * failed, or track tables unlike the Honor's (Android 17,
     * "Client(pid/uid)"), which prove nothing either way. The track tables
     * are read; the "Local log" under each thread is history, not state, and
     * the input threads are another table, both skipped.
     */
    static int trackOutput(String flinger, int pid) {
        if (flinger == null) {
            return -1;
        }
        java.util.regex.Pattern active = java.util.regex.Pattern.compile(
                "(?m)^\\s+(?:[A-Z]\\s+)?\\d+\\s+yes\\s+" + pid + "/");
        int found = -1;
        for (String thread : flinger.split("\nOutput thread ")) {
            int log = thread.indexOf("Local log:");
            String state = log >= 0 ? thread.substring(0, log) : thread;
            int input = state.indexOf("\nInput thread ");
            if (input >= 0) {
                state = state.substring(0, input);
            }
            if (active.matcher(state).find()) {
                if (state.contains("AUDIO_OUTPUT_FLAG_INCALL_MUSIC")) {
                    return 1;
                }
                found = 0;
            }
        }
        return found;
    }

    /**
     * How much of [channel]'s power in interleaved 16-bit [pcm] (the first
     * [frames] frames of [channels] each) is at [hz] - Goertzel against the
     * total, 0 to about 1. The check tone shows as most of it; speech and
     * silence as a sliver.
     */
    static double toneShare(short[] pcm, int channels, int channel, int frames, double hz, int rate) {
        double k = 2 * Math.cos(2 * Math.PI * hz / rate);
        double s1 = 0;
        double s2 = 0;
        double total = 0;
        for (int i = 0; i < frames; i++) {
            double v = pcm[i * channels + channel];
            double s0 = v + k * s1 - s2;
            s2 = s1;
            s1 = s0;
            total += v * v;
        }
        if (total <= 0 || frames == 0) {
            return 0;
        }
        double tone = (s1 * s1 + s2 * s2 - k * s1 * s2) * 2 / frames;
        return tone / total;
    }

    /**
     * A message's recording file, named so that any collector - the hang-up's
     * or a reconcile days later - knows it is one, and where the message
     * starts: "_msg" and the start in ms from the capture's first sample (0
     * when no start is known: all of it is kept), or "_msgnone" when
     * [nothingLeft] - the caller hung up during the greeting without a word -
     * or "_msgfail" when the greeting could not be played at all, just before
     * the direction tag that the app reads by "_in." and "_out.".
     * jemrec_rec_1792661198533_in.dat -> jemrec_rec_1792661198533_msg8120_in.dat
     */
    static String messageRecfile(String name, long messageStart, long captureStart, boolean nothingLeft,
                                 boolean greetingFailed) {
        return withTag(name, greetingFailed ? "_msgfail"
                : nothingLeft ? "_msgnone"
                : "_msg" + offset(messageStart, captureStart));
    }

    /**
     * Which [blockFrames]-long blocks of 16-bit little-endian mono [pcm] are
     * at or above [rms]: the answering machine's own sound, as it is played.
     * A last, partial block counts as a block.
     */
    static boolean[] loudBlocks(byte[] pcm, int blockFrames, double rms) {
        int frames = pcm.length / 2;
        boolean[] loud = new boolean[(frames + blockFrames - 1) / blockFrames];
        for (int b = 0; b < loud.length; b++) {
            int from = b * blockFrames;
            int to = Math.min(frames, from + blockFrames);
            double sum = 0;
            for (int i = from; i < to; i++) {
                double s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
                sum += s * s;
            }
            loud[b] = Math.sqrt(sum / (to - from)) >= rms;
        }
        return loud;
    }

    /** Whether any block within [margin] of [block] is loud. Past the end of
     *  what was written, nothing is. */
    static boolean loudAround(boolean[] loud, long block, int margin) {
        long from = Math.max(0, block - margin);
        long to = Math.min(loud.length - 1L, block + margin);
        for (long b = from; b <= to; b++) {
            if (loud[(int) b]) {
                return true;
            }
        }
        return false;
    }

    /**
     * A call the user took over from the answering machine, named with where
     * it starts: "_from" and the start in ms from the capture's first sample,
     * before the direction tag. The app trims to it, as for a message, and
     * keeps the call as an ordinary recording, not a message.
     * jemrec_rec_1792661198533_in.dat -> jemrec_rec_1792661198533_from8120_in.dat
     */
    static String takenRecfile(String name, long start, long captureStart) {
        return withTag(name, "_from" + offset(start, captureStart));
    }

    /** Where [at] falls in a capture that began at [captureStart], in ms: 0
     *  when either is unknown, or [at] came first. */
    private static long offset(long at, long captureStart) {
        return Math.max(0, at > 0 && captureStart > 0 ? at - captureStart : 0);
    }

    /** [tag] in a recording's name, just before its direction. */
    private static String withTag(String name, String tag) {
        return name.replaceFirst("(_in|_out)\\.dat$", tag + "$1.dat");
    }

    /**
     * The sound that ends the greeting: a ding-dong - E5 then C5, a falling
     * third, each note with a soft second partial and its own decay - chosen
     * by the user over a plain 1000 Hz beep, which was "acute and unpleasant"
     * (2026-10-22). [peak] is its loudest sample. PCM16 little-endian mono,
     * 0.85 s; both notes sit well inside what a phone line carries.
     */
    static byte[] chime(int rate, double peak) {
        double[] mix = new double[(int) (0.85 * rate)];
        addNote(mix, rate, 0.0, 0.45, 659.25, 0.18);
        addNote(mix, rate, 0.22, 0.60, 523.25, 0.22);
        double max = 0;
        for (double v : mix) {
            max = Math.max(max, Math.abs(v));
        }
        byte[] pcm = new byte[mix.length * 2];
        for (int i = 0; i < mix.length; i++) {
            short s = (short) Math.round(mix[i] / max * peak);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    /** One bell-like note into [mix]: a 5 ms attack, an exponential decay of
     *  [tau] s, a 20 ms fade at its end so it never clicks. */
    private static void addNote(double[] mix, int rate, double start, double length, double hz, double tau) {
        int from = (int) (start * rate);
        int frames = (int) (length * rate);
        for (int i = 0; i < frames && from + i < mix.length; i++) {
            double t = (double) i / rate;
            double envelope = Math.min(1.0, t / 0.005) * Math.exp(-t / tau) * Math.min(1.0, (length - t) / 0.02);
            mix[from + i] += envelope * (Math.sin(2 * Math.PI * hz * t) + 0.25 * Math.sin(2 * Math.PI * 2 * hz * t));
        }
    }

    /** AudioManager's modes, as numbers, for the decisions below. */
    static final int MODE_IN_CALL = 2;
    static final int MODE_IN_COMMUNICATION = 3;

    /**
     * Whether a messenger call being recorded goes on, given the audio mode
     * and whether its app still plays and captures call audio. The mode
     * alone is not enough: a phone call ringing over the messenger call
     * takes it to the ringtone's (measured on an Honor, 2026-10-22, with
     * Telegram: the recording was cut in two at the ring). Only a phone call
     * actually up - MODE_IN_CALL - ends it while the app still talks: then
     * the phone call is the call.
     */
    static boolean messengerCallGoesOn(int mode, boolean appStillCalling) {
        return mode == MODE_IN_COMMUNICATION || (mode != MODE_IN_CALL && appStillCalling);
    }

    /** Android 14: from here the shell holds CAPTURE_VOICE_COMMUNICATION_OUTPUT,
     *  which copying a call's playback needs (Voip.Policy). */
    static final int SDK_VOICE_COPY = 34;

    /**
     * Whether a phone call plays through Android's mixer, as a messenger call
     * does, instead of the modem's voice path - and so is recorded as one
     * (Voip.Capture), because VOICE_CALL hears nothing of it. Told by the
     * audio mode while the phone call is up.
     *
     * Measured on a Galaxy S23 (One UI, Android 16, 2026-10-24), over Wi-Fi
     * Calling: MODE_IN_COMMUNICATION from the dialling, the far side played
     * by voice-communication players of the call stack (uid 1000); VOICE_CALL
     * at 48k stereo, 16k and 8k mono, VOICE_DOWNLINK and VOICE_UPLINK all
     * delivered zeros at the full rate, a whole call long; the messenger
     * capture had the far side from the copy and her side from the
     * microphone, with the call's own microphone never silenced and both
     * people hearing each other. Every phone call measured before - the
     * Honor's and the emulators' - is MODE_IN_CALL, and goes through the
     * modem as always.
     */
    static boolean phoneCallInMixer(int mode, int sdk) {
        return mode == MODE_IN_COMMUNICATION && sdk >= SDK_VOICE_COPY;
    }

    /** Root mean square of one [channel] of the first [frames] frames of
     *  interleaved [pcm]. */
    static double channelRms(short[] pcm, int channels, int channel, int frames) {
        if (frames <= 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < frames; i++) {
            double v = pcm[i * channels + channel];
            sum += v * v;
        }
        return Math.sqrt(sum / frames);
    }

    /**
     * Whether a USB port's current mode (UsbPortStatus.getCurrentMode) means
     * a cable is in. 0 is MODE_NONE, nothing attached; measured on the Honor,
     * 2026-10-24: 1 plugged into a computer, 8 into a wall charger, 0 with
     * neither. A negative mode is a port that could not be read, not a cable.
     */
    static boolean cableIn(int mode) {
        return mode > 0;
    }

    /**
     * One reading of every port: the highest mode, so any port with a cable
     * in is a cable in; one that could not be read (-1) counts for nothing,
     * and if none could, the reading is -1 - nothing is known, so nothing
     * has changed.
     */
    static int portsReading(int... modes) {
        int reading = -1;
        for (int mode : modes) {
            reading = Math.max(reading, mode);
        }
        return reading;
    }

    /**
     * What a reading of the cable means for the recorder: "unplugged",
     * "plugged in", or null for nothing new. The first reading ([before] null)
     * is how things are, not a change.
     */
    static String cableChange(Boolean before, boolean now) {
        if (before == null || before == now) {
            return null;
        }
        return now ? "plugged in" : "unplugged";
    }

    /**
     * Whether USB debugging is among the functions in a sys.usb.config value,
     * "hisuite,mtp,mass_storage,adb" on the Honor plugged into a computer:
     * "adb" as one of its comma-separated names. Honor's own "hdb" is not it.
     */
    static boolean usbAdb(String config) {
        if (config == null) {
            return false;
        }
        for (String function : config.split(",")) {
            if (function.trim().equals("adb")) {
                return true;
            }
        }
        return false;
    }

    /** Root mean square of the first [frames] frames of interleaved [pcm],
     *  every channel together. */
    static double rms(short[] pcm, int channels, int frames) {
        int n = frames * channels;
        if (n == 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (double) pcm[i] * pcm[i];
        }
        return Math.sqrt(sum / n);
    }
}
