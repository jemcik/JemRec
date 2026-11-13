package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import android.telephony.TelephonyManager;

/**
 * The answering machine's test call, for the app's diagnostics: what the last
 * one showed ('X' 'R'), its windows, and the checks on its number and its
 * greeting's route.
 */
final class AnswerTest {

    private AnswerTest() {
    }

    /** The last diagnostics test call, for 'X' 'R'. */
    static volatile String testState = "none";
    static volatile int testRoute = -1;
    static volatile long testRealMs;
    static volatile long testAudioMs;
    static volatile int testToneRun;
    static volatile int testToneBest;
    static volatile int testMuteHeld = -1;
    static volatile int testVoice = -1;
    /** 0 when the test's greeting could not be played at all. */
    static volatile int testPlayed = -1;
    /** Chunks of about 21 ms in a row with the check tone: 8 is ~170 ms. */
    static final int TONE_CHUNKS = 8;
    /** The check tone counts above -50 dBFS, and as most of the chunk. */
    static final double TONE_MIN_RMS = 32768 * Math.pow(10, -50 / 20.0);
    static final double TONE_MIN_SHARE = 0.5;

    /** Three minutes for someone to call. A dialled call goes out within a
     *  second or two; the window is short, because until it closes the next
     *  call this phone places is taken for the test. */
    static final long TEST_INCOMING_WINDOW_MS = 180_000L;
    static final long TEST_OUTGOING_WINDOW_MS = 15_000L;

    /** Whether [number] is an emergency number here - or might be: one that
     *  cannot be checked is never dialled for a test either. */
    static boolean mayBeEmergencyNumber(String number) {
        TelephonyManager telephony = Platform.phone;
        if (telephony == null) {
            Ln.w("answer: could not tell whether the test's number is an emergency number: no telephony");
            return true;
        }
        try {
            return telephony.isEmergencyNumber(number);
        } catch (Throwable t) {
            Ln.w("answer: could not tell whether the test's number is an emergency number: " + t);
            return true;
        }
    }

    static final long GREETING_ROUTE_CHECK_MS = 3_000L;

    /** The test's greeting, mid-way: is it on the output the caller hears? */
    static void checkTestRoute() {
        new Thread(() -> {
            String flinger = Shell.dump("media.audio_flinger");
            int on = Checks.trackOutput(flinger, android.os.Process.myPid());
            testRoute = AnswerSettings.ROUTE_MUSIC.equals(Answering.answerSettings.route) ? on : -1;
            Ln.i("answer: test greeting on the in-call music output: "
                    + (on == 1 ? "yes" : on == 0 ? "no" : "cannot tell"));
        }, "jemrec-route-check").start();
    }
}
