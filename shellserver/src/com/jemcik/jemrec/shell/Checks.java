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
    static boolean uidAllowed(@Nullable String said) {
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
    static int inCallMusic(@Nullable String policy) {
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
    static int trackOutput(@Nullable String flinger, int pid) {
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
     * The test call's check tone: [hz] at [peak], [frames] long, with 10 ms of
     * fade at each end - a tone that starts at full amplitude clicks. PCM16
     * little-endian mono, as the chime. Here rather than in GreetingPlayer so
     * a test can play it to toneShare, which listens for it on the call.
     */
    static byte[] tone(int rate, int hz, int frames, double peak) {
        int fadeFrames = rate / 100;
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            double fade = Math.min(1.0, Math.min(i, frames - 1 - i) / (double) fadeFrames);
            short s = (short) (Math.sin(2 * Math.PI * hz * i / rate) * peak * fade);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
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

    /**
     * Whether a phone call goes on through the mixer (Voip.Capture) or the
     * call capture (VOICE_CALL), asked when its audio mode changed during the
     * call, and again each second while a change put it on the mixer.
     * [onMixer]: what it is on now; [startedOnMixer]: the mode said so as the
     * call went off-hook.
     *
     * The mode is read once as the call goes off-hook (PhoneCapture), and
     * that was all until 0.24. A call whose mode turns "communication" only
     * after that - a Wi-Fi call put through after the off-hook, or a call
     * handed over from mobile to Wi-Fi - stayed on the call capture, which
     * there hears nothing (the S23) or only the microphone: Samsung's Exynos
     * audio code makes a VOICE_CALL capture outside MODE_IN_CALL an ordinary
     * microphone capture (the open SLSI audio_hw.c, read 2026-10-27), and a
     * Galaxy M34's Wi-Fi call came out with its owner's voice alone (issue
     * #37). The way back, MODE_IN_CALL, is a call handed to the modem, which
     * the call capture records on every phone measured.
     *
     * ONLY ON TOP OF WHAT 0.23 DID. A call that began on the mixer is left
     * there whatever else plays, as 0.23 left it, and leaves it only for the
     * modem's voice path. A call that began on the call capture goes to the
     * mixer only while no app's own call is up [appInCall] - in the middle of
     * a call, "communication" can also be another app's call brought to the
     * front over a phone call on hold, as Telecom gives Google Meet's that
     * mode, and the mixer would record it - and only while Telecom holds a
     * phone call [phoneCallInTelecom] (not one ending into an app's call,
     * CallStates.audioModeChanged). Null: Telecom could not be asked, and
     * the mode is taken at its word. Kept off the mixer by these, the call is
     * recorded as 0.23 recorded it. Any other mode - normal at the end,
     * ringtone, call screening - changes nothing.
     */
    static boolean mixerAfterModeChange(boolean onMixer, boolean startedOnMixer, int mode, int sdk,
                                        @Nullable Boolean phoneCallInTelecom, boolean appInCall) {
        if (mode == MODE_IN_CALL) {
            return false;
        }
        if (!phoneCallInMixer(mode, sdk)) {
            return onMixer;
        }
        return startedOnMixer || (!appInCall && !Boolean.FALSE.equals(phoneCallInTelecom));
    }

    /**
     * Whether anyone is in a call, from what plays and captures: the owners'
     * uids of the voice-call players [voicePlayerUids], and of every capture
     * with its source - -1 where Android does not say whose it is. A call is
     * one app playing voice-call audio and capturing at once.
     *
     * ANDROID 12 DOES NOT SAY. The shell lacks MODIFY_AUDIO_ROUTING there
     * (granted from 13), and Android hands it every player as uid -1 and no
     * capture's owner at all (the Android 12 AVD, 2026-10-27). Asked for one
     * app doing both, the answering machine found none, and went by the audio
     * mode alone - which the ringtone of a phone call ringing over a messenger
     * call takes: on that AVD it answered such a call whenever the ringtone
     * got the mode first (answer_during_voip, four runs of five). So where
     * an owner is hidden, a voice-call player and a call
     * app's kind of capture - voice communication or the microphone, not a
     * hotword's - count as a call together: left ringing is the safe side.
     */
    static boolean someoneInCall(int[] voicePlayerUids, int[] captureUids, int[] captureSources) {
        if (voicePlayerUids.length == 0) {
            return false;
        }
        boolean hidden = false;
        for (int uid : voicePlayerUids) {
            hidden |= uid < 0;
        }
        for (int i = 0; i < captureUids.length; i++) {
            int uid = captureUids[i];
            boolean callCapture = captureSources[i] == SOURCE_VOICE_COMMUNICATION || captureSources[i] == SOURCE_MIC;
            if ((hidden || uid < 0) && callCapture) {
                return true;
            }
            for (int player : voicePlayerUids) {
                if (uid >= 0 && uid == player) {
                    return true;
                }
            }
        }
        return false;
    }

    /** MediaRecorder.AudioSource's. */
    static final int SOURCE_MIC = 1;
    static final int SOURCE_VOICE_COMMUNICATION = 7;

    /**
     * What follows the start in a rec-file name: "_in" or "_out" for a phone
     * call; for an app's call "_voip-" and the app, after "_in" or "_out" where
     * Telecom said which way it went - Google Meet's, whose ring the call watch
     * hears: jemrec_rec_1793017768333_in_voip-com.google.android.apps.tachyon.dat.
     * The app reads the app back from after "_voip-", and the way from before
     * it (VoipApps); a messenger outside Telecom leaves the way to its
     * notification, as before.
     */
    static String recfileTag(boolean incoming, @Nullable String app, @Nullable Boolean appIncoming) {
        if (app == null) {
            return incoming ? "_in" : "_out";
        }
        return (appIncoming == null ? "" : appIncoming ? "_in" : "_out") + "_voip-" + app;
    }

    /** What kind of call a report is about, the way recfileTag tells it:
     *  "a phone call, incoming", "a call in com.whatsapp". [answered]: by
     *  this process's answering machine. */
    static String callInWords(boolean incoming, @Nullable String app, @Nullable Boolean appIncoming,
                              boolean answered) {
        if (app == null) {
            return "a phone call, " + (incoming ? "incoming" : "outgoing")
                    + (answered ? ", answered by the answering machine" : "");
        }
        return "a call in " + app + (appIncoming == null ? "" : appIncoming ? ", incoming" : ", outgoing");
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
     * The two sides of a call as stereo PCM (Voip.Capture): [mine], the
     * microphone, on the left, and [theirs], the stereo copy of the call made
     * mono, on the right - for every call, so the sides never swap with its
     * kind: where the call capture has them on a phone that splits the sides
     * (the Honor: its left all zeros while muted, 2026-10-22), and where the
     * answering machine reads its own side (Main.onChunk). Up to 0.24 a
     * messenger call had them the other way round. In the
     * platform's byte order, as AudioRecord writes it; a ByteBuffer starts out
     * big-endian, which would be noise.
     */
    static void stereo(short[] mine, short[] theirs, int frames, java.nio.ByteBuffer out) {
        out.order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            out.putShort(mine[i]);
            out.putShort((short) ((theirs[2 * i] + theirs[2 * i + 1]) / 2));
        }
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
    static @Nullable String cableChange(@Nullable Boolean before, boolean now) {
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
    static boolean usbAdb(@Nullable String config) {
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

    /**
     * The timestamp of the newest background stop worth telling the user
     * about, among the process-exit records in `dumpsys activity exit-info`
     * output: a system kill - reason 13 (OTHER KILLS BY SYSTEM, which is how
     * a vendor power manager's kill reads: the Honor's was
     * iAwareR[SmartClean]) or 14 (FREEZER) - that cost something. Null when
     * there is none.
     *
     * COST, BECAUSE REASON 13 IS ALSO ROUTINE. It is how Android and the
     * vendors reclaim an idle app too, and JemRec is idle between calls by
     * design. Measured on the Honor (2026-10-25): 2,015 of its 2,062 reason-13
     * records were of apps idle in the background (importance 400: "iAwareK",
     * "too many empty"), and none of the 136 apps last killed that way was
     * left stopped. Such a kill costs JemRec nothing - the recorder runs on as
     * shell and wakes the app for the next call - and warning of it would be a
     * false alarm after nearly every call. So a kill counts when JemRec was
     * WORKING (importance below {@link #IMPORTANCE_CACHED}: the Honor's harmful
     * kill was 125, a foreground service), or when the phone LEFT IT STOPPED,
     * which [stopped] answers: a stopped app loses its scheduled jobs and the
     * boot broadcast, so nothing would bring a recorder back once it goes. On
     * the Honor only iAwareR kills left apps stopped: the four such apps not
     * running again were all stopped=true. [stopped] is asked only when the
     * newest record is an idle kill, and lends to that record alone: the
     * user's own Force stop leaves the app stopped too, and must not make an
     * older idle kill look like one.
     *
     * The user's own Force stop (10), a crash (4), an app update (16) and the
     * rest are passed over, not stopped at: a kill followed within the
     * daemon's minute by a swipe-away is still a kill to tell about, as the
     * app's own check (BackgroundKill.systemKill) sees it.
     *
     * Records come newest first (measured on the Honor: #0 an update at
     * 14:58, #2 the kill at 13:52), so the first match is the newest. The
     * timestamp string is the daemon's handle for "have I already reported
     * this one": it changes only when a newer stop lands.
     *
     * Tolerant of Main.slurp joining lines with " | ": the work is regex within
     * each record's text, and the record markers and key=value pairs survive
     * the join.
     */
    static @Nullable String backgroundKillStamp(@Nullable String dumpsys, java.util.function.BooleanSupplier stopped) {
        if (dumpsys == null) {
            return null;
        }
        boolean newest = true;
        int start = dumpsys.indexOf(EXIT_RECORD);
        while (start >= 0) {
            int next = dumpsys.indexOf(EXIT_RECORD, start + 1);
            String record = next < 0 ? dumpsys.substring(start) : dumpsys.substring(start, next);
            java.util.regex.Matcher reason = EXIT_REASON.matcher(record);
            if (reason.find()) {
                int code = Integer.parseInt(reason.group(1));
                if (code == 13 || code == 14) {
                    // Every Android since 11 prints it; one that did not
                    // would leave the kill counted, as before this rule.
                    java.util.regex.Matcher importance = EXIT_IMPORTANCE.matcher(record);
                    boolean working = !importance.find() || Integer.parseInt(importance.group(1)) < IMPORTANCE_CACHED;
                    if (working || (newest && stopped.getAsBoolean())) {
                        java.util.regex.Matcher stamp = EXIT_STAMP.matcher(record);
                        return stamp.find() ? stamp.group(1) : null;
                    }
                }
            }
            newest = false;
            start = next;
        }
        return null;
    }

    /**
     * Whether `dumpsys package <app>` says the app is stopped for user 0 -
     * the state a Force stop leaves, and the Honor's iAwareR kills too (see
     * backgroundKillStamp). Read from the "User 0:" line, where the Honor
     * also prints an iAwareStopped= of its own, which is not this. False when
     * the dump could not be read.
     */
    static boolean packageStopped(@Nullable String dumpsys) {
        if (dumpsys == null) {
            return false;
        }
        java.util.regex.Matcher m = USER0_STOPPED.matcher(dumpsys);
        return m.find() && m.group(1).equals("true");
    }

    /**
     * Whether [stamp] (backgroundKillStamp) is a stop not yet told, noting it
     * in [told] if so. Every stamp told is kept, not only the last: the newest
     * stop that counts can go BACK to an older one. An idle kill counts only
     * while the app is stopped, the wake that tells of it starts the app and
     * clears the flag, and the next look then finds the stop before it -
     * which, compared with the last stamp alone, read as new and woke the app
     * a second time. A handful of stamps a year; nothing to bound.
     */
    static boolean newStop(java.util.Set<String> told, @Nullable String stamp) {
        return stamp != null && told.add(stamp);
    }

    /** ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED: an exit at or
     *  above it found the process idle in the background. */
    static final int IMPORTANCE_CACHED = 400;

    private static final String EXIT_RECORD = "ApplicationExitInfo #";
    /** "reason=", not the "reason=" inside "subreason=": printed after it on
     *  the Honor, but nothing promises that order everywhere. */
    private static final java.util.regex.Pattern EXIT_REASON =
            java.util.regex.Pattern.compile("(?<![A-Za-z])reason=(\\d+)");
    private static final java.util.regex.Pattern EXIT_IMPORTANCE =
            java.util.regex.Pattern.compile("(?<![A-Za-z])importance=(\\d+)");
    private static final java.util.regex.Pattern EXIT_STAMP =
            java.util.regex.Pattern.compile("timestamp=(\\d{4}-\\d{2}-\\d{2} [\\d:.]+)");
    /** Within the line: Main.slurp joins lines with " | ". */
    private static final java.util.regex.Pattern USER0_STOPPED =
            java.util.regex.Pattern.compile("User 0:[^|\\n]*?(?<![A-Za-z])stopped=(true|false)");

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
