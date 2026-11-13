package com.jemcik.jemrec.shell;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The loopback protocol as the recorder speaks it: the command bytes, every
 * line it answers with, and the name frames it reads. Plain Java, so
 * daemon-tests holds each line to shellserver/tests/fixtures/protocol.txt -
 * the replies the app's own tests read with the app's parsers. The two sides
 * are written apart, and an app talks to whatever recorder is running, an
 * older one for days off Wi-Fi too: a line changes only on both at once.
 * What each command does is Commands'; the connection and the handshake,
 * LoopbackServer's and Handshake's.
 */
final class Wire {

    private Wire() {
    }

    /** The protocol's version, answered with every ping: see Handshake for
     *  what version 2 brought. */
    static final int PROTOCOL_VERSION = 2;
    /** The first byte of a connection that proves itself (Handshake). */
    static final int COMMAND_HELLO = 'A';

    static final int COMMAND_PING = 'P';
    static final int COMMAND_RECORD = 'R';

    /**
     * THE DAEMON RECORDS TO A FILE; THE APP COLLECTS IT AFTERWARDS.
     *
     * The app used to read the live audio over this socket and mux it as the
     * call ran. That put a backgrounded, throttleable app process on the
     * real-time path: on this phone Honor's iAware freezes JemRec while another
     * app (the dialer) is in front, its socket reader stalls, and the tail of
     * the call is lost when the socket closes. Measured - a 57s call arrived as
     * 35s, ~620 packets discarded.
     *
     * So the daemon - shell uid, outside app management, never frozen - now
     * writes the whole call to /data/local/tmp itself, driven by its own call
     * watch, with no app in the loop. Afterwards the app FETCHes the finished
     * file over this socket at whatever pace the OS allows: a freeze there only
     * delays the copy, it cannot lose audio, because the audio is already on
     * disk complete.
     */
    static final int COMMAND_FETCH = 'F';
    static final int COMMAND_DELETE = 'D';
    static final int COMMAND_SET_ENABLED = 'E';
    static final int COMMAND_LIST = 'L';
    /** On-demand: the app tells the daemon to start recording the call in
     *  progress, once the user has said yes to the start-of-call prompt. */
    static final int COMMAND_START = 'S';

    /**
     * Report on the call watch, for the app's self-test.
     *
     * The self-test proved that this daemon answers and that the HAL hands over
     * voice-call audio, and on that alone it said "a call would be recorded" -
     * while the daemon was deaf to every call, its watch garbage-collected (see
     * CallWatch.callWatch). Measured: PASS straight after a call it had missed. Neither
     * check touches the watch, so the app now asks after it directly: when this
     * process started, whether the watch registered, whether the main looper
     * that delivers its events still runs, the mode, whether a call is up, how
     * many events and off-hooks there have been and when the last of each came,
     * and since when the current recording has run. The app holds that against
     * the phone's call log. One line, "WATCH 1 key=value ..."; a daemon older
     * than this hangs up on the command, which the app reads as too old to say.
     */
    static final int COMMAND_WATCH = 'W';
    static final int WATCH_FORMAT = 1;

    /** Four bytes where the codec id goes: the file the app asked for is not here. */
    static final String GONE = "gone";

    /**
     * Exit.
     *
     * The app used to stop this process with `pkill` over ADB, which works
     * right up until the moment it is most needed. Starting fresh closes the
     * ADB session as part of what it does, so by the time it wanted the daemon
     * gone it had already thrown away the only way it had of asking - and the
     * daemon carried on running as an orphan until the phone was rebooted.
     *
     * Over loopback there is no such dependency. The socket is the one channel
     * that is always there, which is the entire premise of this daemon.
     */
    static final int COMMAND_QUIT = 'Q';

    /**
     * Switch Wireless and USB debugging off, and go: "Turn all debugging off"
     * in the app's When JemRec is off, for apps that will not open while
     * debugging is on (issue #37). One byte follows: 1 to go, 0 to call off a
     * go still waiting. It waits, as QUIT does, while a call or a fetch is in
     * progress - so this process, never frozen, is the one that waits, rather
     * than an app that may be. USB debugging going off stops adbd, and adbd
     * takes this process with it (see Shield.keepShieldUp): starting it again is the
     * app's, on its On, over Wi-Fi. Replies OFF NOW, OFF AFTER CALL, OFF
     * CANCELLED, or OFF TOO LATE for a 0 that came once the switches were
     * being written: the app's On then waits for this process to be gone
     * before it puts them back, or these writes would land on its.
     */
    static final int COMMAND_DEBUGGING_OFF = 'O';

    /**
     * The messenger apps whose calls to record - see Voip. In the environment
     * at spawn (Main.VOIP_ENV) and sent again whenever the user changes them, the
     * same way the recording mode is. Empty, the default, records none and
     * watches nothing.
     */
    static final int COMMAND_SET_VOIP = 'V';

    /**
     * EXACT CALL TRACKING. Telecom binds the app's in-call service
     * (CallTracker) for every phone call once MANAGE_ONGOING_CALLS is allowed
     * for the app's UID - allowing it for the package alone was not enough on
     * an Honor (measured: Telecom checked the op and bound nothing). Only
     * shell can allow it, so this process does: at start, when the app says
     * (COMMAND_SET_TRACKING), and again at each heartbeat in case the system
     * took it back - some ROMs revert it. Off withdraws it, and nothing is
     * bound.
     */
    static final int COMMAND_SET_TRACKING = 'T';

    /**
     * The rules on whose calls to keep said no to a phone call while it was
     * still ringing or being dialled (the app's CallTracker knows the number
     * by then): do not record it. A recording that started a moment ago for
     * it - dialling takes the phone off-hook at once - is stopped and deleted.
     */
    static final int COMMAND_SKIP = 'K';

    /**
     * THE ANSWERING MACHINE - see AnswerMachine. Its settings ("on,seconds,
     * route") arrive in the environment at spawn and over 'N' when the user
     * changes them, the way the mode does, and default to off. The app's
     * go-ahead for a ringing call is 'Y' (four bytes: the delay in ms, or -1
     * to take it back); 'H' is Talk to them ('T') or Hang up ('H') during a
     * message; 'G' handles the greeting and 'X' the diagnostics.
     */
    static final int COMMAND_ANSWER_SETTINGS = 'N';
    static final int COMMAND_GO_AHEAD = 'Y';
    static final int COMMAND_HANDOVER = 'H';
    static final int COMMAND_GREETING = 'G';
    static final int COMMAND_DIAGNOSE = 'X';

    /**
     * The last call's report, for Copy a report in the app's Diagnostics: see
     * CallReport. Kept in a file as well, from the moment its recording
     * stops, so a recorder started again since - an update, a revive -
     * still has it.
     */
    static final int COMMAND_REPORT = 'C';

    /**
     * Notification access for the app's own listener (Listener): the app's
     * Show who called and Hide the debugging notification need it, and only
     * the shell can grant it. The app used to open an ADB session for it,
     * which needs Wireless debugging on - and LineageOS switches that off by
     * itself a second after a revive's session has started this process
     * (the OnePlus, 2026-11-12), so on that phone the switches could not be
     * turned on (Show who called, measured; Hide the debugging notification
     * goes the same way, read from the code). This process is the shell, up
     * whenever recording works: it grants the one component it names,
     * nothing the app could choose, and reads Android's list back for up to
     * two seconds (Listener). Replies LISTENER ok, or LISTENER failed; an
     * older recorder hangs up on it, and the app takes its session then.
     */
    static final int COMMAND_ALLOW_LISTENER = 'B';

    /** The answer to a ping, bare or proved: the protocol and this jar's
     *  build. */
    static String pong(String build) {
        return "PONG " + PROTOCOL_VERSION + " " + build;
    }

    /** QUIT: going, or not while a call or a fetch is in progress. */
    static final String BYE = "BYE";
    static final String BUSY = "BUSY";

    /** DEBUGGING_OFF's answers. */
    static final String OFF_NOW = "OFF NOW";
    static final String OFF_AFTER_CALL = "OFF AFTER CALL";
    static final String OFF_CANCELLED = "OFF CANCELLED";
    static final String OFF_TOO_LATE = "OFF TOO LATE";

    /** SET_ENABLED: the mode in force after it. */
    static String mode(int mode) {
        return String.valueOf(mode);
    }

    static String track(boolean on) {
        return "TRACK " + (on ? "on" : "off");
    }

    /** COMMAND_ALLOW_LISTENER's answer: whether Android lists the app's
     *  listener as allowed, read back after the grant. */
    static String listener(boolean allowed) {
        return "LISTENER " + (allowed ? "ok" : "failed");
    }

    /** SKIP: "current", "next" or "cancelled". */
    static String skip(String what) {
        return "SKIP " + what;
    }

    static String voip(int apps, boolean armed) {
        return "VOIP " + apps + " " + (armed ? "armed" : "off");
    }

    /** The answer to COMMAND_WATCH. Times are wall-clock millis, 0 for never. */
    static String watch(long since, boolean watching, boolean looperOk, int mode, boolean call, int events,
            long lastEvent, int offHooks, long lastOffHook, long recording, int voipApps, boolean voipArmed,
            boolean tracking, boolean answer, boolean greeting) {
        return "WATCH " + WATCH_FORMAT
                + " since=" + since
                + " watching=" + (watching ? 1 : 0)
                + " looper=" + (looperOk ? "ok" : "stuck")
                + " mode=" + mode
                + " call=" + (call ? 1 : 0)
                + " events=" + events
                + " lastevent=" + lastEvent
                + " offhooks=" + offHooks
                + " lastoffhook=" + lastOffHook
                + " recording=" + recording
                + " voip=" + voipApps
                + " voiparmed=" + (voipArmed ? 1 : 0)
                + " tracking=" + (tracking ? 1 : 0)
                + " answer=" + (answer ? 1 : 0)
                + " greeting=" + (greeting ? 1 : 0);
    }

    /** DELETE: gone, or not. */
    static String deleted(boolean ok) {
        return ok ? "OK" : "NO";
    }

    static String answer(String settings) {
        return "ANSWER " + settings;
    }

    /** GO_AHEAD and HANDOVER: the machine's state after, or none without
     *  one. */
    static String go(@Nullable String state) {
        return state == null ? "GO none" : "GO " + state;
    }

    static String hand(@Nullable String state) {
        return state == null ? "HAND none" : "HAND " + state;
    }

    /** GREETING: how many bytes of greeting there are now. */
    static String greeting(long bytes) {
        return "GREETING " + bytes;
    }

    /** A take started, or not: the microphone is the greeting player's or
     *  another take's. */
    static String take(boolean recording) {
        return recording ? "TAKE recording" : "TAKE busy";
    }

    /** A take stopped: its length in bytes. */
    static String taken(long bytes) {
        return "TAKE " + bytes;
    }

    /** DIAGNOSE 'C', the no-call checks. */
    static String diag(String perms, int inCallMusic, int screening, long greeting, String route, int sdk,
            int voice, String soc, String maker, String model) {
        return "DIAG 1"
                + " perms=" + perms
                + " incallmusic=" + inCallMusic
                + " screening=" + screening
                + " greeting=" + greeting
                + " route=" + route
                + " sdk=" + sdk
                + " voice=" + voice
                + " soc=" + word(soc)
                + " maker=" + word(maker)
                + " model=" + word(model);
    }

    /** DIAGNOSE 'A': "armed", or why not. */
    static String test(String outcome) {
        return "TEST " + outcome;
    }

    /** DIAGNOSE 'R': the last test call's signals, and the arming they are
     *  for (the app's id for it); [wifi] and [mixer] last, as an app before
     *  them reads the line by its names and passes over one it does not
     *  know. */
    static String result(String state, int route, long realMs, long audioMs, int tone, int toneChunks, int mute,
            int voice, int played, long arming, int wifi, int mixer) {
        return "RESULT 1 state=" + state
                + " route=" + route
                + " realms=" + realMs
                + " audioms=" + audioMs
                + " tone=" + tone
                + " tonechunks=" + toneChunks
                + " mute=" + mute
                + " voice=" + voice
                + " played=" + played
                + " arming=" + arming
                + " wifi=" + wifi
                + " mixer=" + mixer;
    }

    /** DIAGNOSE 'F' and 'S': the carrier's no-reply forwarding. */
    static String fwd(String said) {
        return "FWD " + said;
    }

    /** DIAGNOSE 'P': "SIMS", then the default SIM, then each SIM - built a
     *  piece at a time, as Telecom answers. */
    static final String SIMS = "SIMS";

    /** [value]: the default SIM's id as a word, or empty when there is none. */
    static String simsDefault(String value) {
        return " default=" + value;
    }

    static String sim(String id, @Nullable String label) {
        return " " + word(id) + "|" + word(label == null ? "SIM" : label);
    }

    /** A value for a key=value line: no spaces, no '='. */
    static String word(@Nullable String value) {
        return value == null || value.isEmpty() ? "unknown" : value.replaceAll("[\\s=]+", "_");
    }

    /** One reply line, sent at once. */
    static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** A length-prefixed UTF-8 name: two big-endian bytes, then that many bytes. */
    static String readName(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int len = in.readUnsignedShort();
        if (len > 512) {
            throw new IOException("implausible name length " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
