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

    /** The app's id for the arming the results below are for, 0 before any:
     *  sent with 'X' 'A' and answered with them, so the app tells a result
     *  that is its test's from one that is not - an arming it got no answer
     *  to, or an older test's. */
    static volatile long testArming;

    /** The last diagnostics test call, for 'X' 'R'. */
    static volatile String testState = "none";
    static volatile int testRoute = -1;
    static volatile long testRealMs;
    static volatile long testAudioMs;
    static volatile int testToneRun;
    static volatile int testToneBest;
    static volatile int testMuteHeld = -1;
    static volatile int testVoice = -1;
    /** Telecom's word on the test call mid-greeting, the latest that could
     *  tell: 1 on Wi-Fi, 0 not, -1 not read (Checks.callOnWifi). */
    static volatile int testWifi = -1;
    /** The checks' readings still being taken, for 'R'
     *  (CallDecisions.testStateToSay). */
    static final java.util.concurrent.atomic.AtomicInteger readingsInFlight =
            new java.util.concurrent.atomic.AtomicInteger();
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

    /** How long the emergency check is waited for: a binder call with no
     *  time limit of its own, and the arming is answered after it. */
    static final long EMERGENCY_CHECK_MS = 2_000L;

    /** Whether [number] is an emergency number here - or might be: one that
     *  cannot be checked, or not in time, is never dialled for a test either. */
    static boolean mayBeEmergencyNumber(String number) {
        TelephonyManager telephony = Platform.phone;
        if (telephony == null) {
            Ln.w("answer: could not tell whether the test's number is an emergency number: no telephony");
            return true;
        }
        try {
            return Hop.inTime("jemrec-emergency-check", () -> telephony.isEmergencyNumber(number),
                    EMERGENCY_CHECK_MS);
        } catch (java.util.concurrent.TimeoutException t) {
            Ln.w("answer: could not tell whether the test's number is an emergency number: no answer in "
                    + EMERGENCY_CHECK_MS / 1000 + " s");
            return true;
        } catch (Throwable t) {
            Ln.w("answer: could not tell whether the test's number is an emergency number: " + t);
            return true;
        }
    }

    static final long GREETING_ROUTE_CHECK_MS = 3_000L;

    /**
     * The test's greeting, mid-way: is it on the output the caller hears, and
     * is the call on Wi-Fi? Mid-way, not at the answer: a Galaxy S23's first
     * test call over Wi-Fi Calling began on LTE and was handed over to Wi-Fi
     * two seconds after the machine answered it (2026-11-12). Only for the
     * test armed as [arming] as its greeting began, and while it is under way
     * (CallDecisions.testCheckApplies). The route, the Wi-Fi mark and the
     * voice network type are read together, so the call is named by the path
     * its route was checked on: a move later in a greeting the track
     * outlives - in-call music, through the S23's handover at 19:32 - would
     * otherwise put one path's verdict under the other's name. They are read
     * off the main looper, the route's first, as it decides the verdict;
     * each is kept as soon as it is read, the latest greeting's - one played
     * again after a move - over an earlier one's.
     */
    static void checkTestRoute(long arming) {
        if (!applies(arming)) {
            return;
        }
        boolean music = AnswerSettings.ROUTE_MUSIC.equals(Answering.answerSettings.route);
        readingsInFlight.incrementAndGet();
        new Thread(() -> {
            try {
                int on = Checks.trackOutput(Shell.dump("media.audio_flinger"), android.os.Process.myPid());
                Ln.i("answer: test greeting on the in-call music output: " + words(on));
                keep(arming, () -> testRoute = music ? on : -1);
                int wifi = Checks.callOnWifi(Shell.dump("telecom"));
                Ln.i("answer: test call on Wi-Fi: " + words(wifi));
                int voice = voiceNow();
                keep(arming, () -> {
                    testWifi = CallDecisions.wifiAfter(testWifi, wifi);
                    testVoice = CallDecisions.voiceAfter(testVoice, voice);
                });
            } finally {
                landed();
            }
        }, "jemrec-route-check").start();
    }

    private static int voiceNow() {
        TelephonyManager phone = Platform.phone;
        try {
            return phone == null ? -1 : phone.getVoiceNetworkType();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** On the main looper: the test armed as [arming], under way. */
    private static boolean applies(long arming) {
        AnswerMachine machine = Answering.answerMachine;
        return CallDecisions.testCheckApplies(testArming, arming, machine != null && machine.testing());
    }

    /** A check's readings taken: counted down on the main looper after
     *  what they found is kept there, which was posted first. */
    private static void landed() {
        android.os.Handler main = Platform.mainHandler;
        if (main == null || !main.post(readingsInFlight::decrementAndGet)) {
            readingsInFlight.decrementAndGet();
        }
    }

    /** [write], on the main looper where the arming is set, if the test armed
     *  as [arming] is still the one armed: a reading taken in time is kept
     *  however its call ended meanwhile, and never as a later test's. */
    private static void keep(long arming, Runnable write) {
        android.os.Handler main = Platform.mainHandler;
        if (main != null) {
            main.post(() -> {
                if (CallDecisions.testCheckApplies(testArming, arming, true)) {
                    write.run();
                }
            });
        }
    }

    private static String words(int said) {
        return said == 1 ? "yes" : said == 0 ? "no" : "cannot tell";
    }
}
