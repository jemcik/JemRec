package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Build;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.io.FileOutputStream;

/**
 * What the answering machine does to the phone. Every method runs on the
 * main looper, where the call watch delivers its events; the greeting's
 * callbacks come from its own thread and are posted back here.
 */
final class AnswerEffects implements AnswerMachine.Effects {



    // The same names as the fields of Platform they are taken from, on
    // purpose: inside this class every use is of the copy, which cannot
    // be null - CallWatch.startCallWatcher makes this only once it has all four,
    // and Platform's own stay null if anything before them fails.
    private final Handler mainHandler;
    private final AudioManager callAudio;
    private final TelecomManager telecom;
    private final TelephonyManager phone;

    AnswerEffects(Handler mainHandler, AudioManager callAudio, TelecomManager telecom, TelephonyManager phone) {
        this.mainHandler = mainHandler;
        this.callAudio = callAudio;
        this.telecom = telecom;
        this.phone = phone;
    }

    @Override
    public long now() {
        // Not the wall clock: a time correction mid-call would move a
        // message's start, or stretch a timer, by the size of the step.
        return SystemClock.elapsedRealtime();
    }

    @Override
    public Object schedule(Runnable task, long delayMs) {
        mainHandler.postDelayed(task, delayMs);
        return task;
    }

    @Override
    public void cancel(Object handle) {
        mainHandler.removeCallbacks((Runnable) handle);
    }

    @Override
    public boolean callUp() {
        CallWatch watch = CallWatch.callWatch;
        return watch != null && watch.phoneCallUp();
    }

    @Override
    public boolean otherAppCallUp() {
        if (Calls.callVoipApp != null) {
            // A messenger call this process is recording.
            return true;
        }
        try {
            // The mode is the VoIP app's while its call runs - but a phone
            // call ringing over it may hand the mode to the ringtone, so
            // the app playing and capturing call audio is asked too.
            // The shell's own uid counts too: this process never plays call
            // audio, and the emulator check's stand-in call runs as the shell.
            return callAudio.getMode() == AudioManager.MODE_IN_COMMUNICATION
                    || Voip.someoneInCall(callAudio);
        } catch (Throwable t) {
            Ln.w("answer: could not tell whether another app is in a call: " + t);
            return true;
        }
    }

    @Override
    public boolean emergencyCallback() {
        return Platform.emergencyCallbackMode(phone);
    }

    @Override
    public boolean greetingPresent() {
        return Greeting.present();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean answer() {
        if (!Answering.muteReady) {
            Ln.w("answer: the microphone cannot be muted from here; not answering");
            return false;
        }
        try {
            telecom.acceptRingingCall();
            return true;
        } catch (Throwable t) {
            Ln.e("answer: acceptRingingCall failed", t);
            return false;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void hangUp() {
        try {
            Ln.i("answer: endCall -> " + telecom.endCall());
        } catch (Throwable t) {
            Ln.e("answer: endCall failed", t);
        }
    }

    @Override
    public boolean audioInCall() {
        return callAudio.getMode() == AudioManager.MODE_IN_CALL;
    }

    @Override
    public boolean callAudioUp() {
        int mode = callAudio.getMode();
        return mode == AudioManager.MODE_IN_CALL || Checks.phoneCallInMixer(mode, Build.VERSION.SDK_INT);
    }

    @Override
    public void setMuted(boolean on) {
        if (on) {
            // The marker first: a death between the two leaves a marker
            // for a mute that never happened, which costs an unneeded
            // unmute at the next start. The other order could leave a
            // muted phone and nothing to say so. A marker that cannot be
            // left does not stop the mute: the caller hearing the room is
            // worse, and the phone clears the mute as the call ends.
            try {
                new FileOutputStream(Paths.MUTE_MARKER).close();
            } catch (Throwable t) {
                Ln.e("answer: could not leave the mute marker", t);
            }
        }
        try {
            callAudio.setMicrophoneMute(on);
            if (!on) {
                Paths.MUTE_MARKER.delete();
            }
            AnswerMachine machine = Answering.answerMachine;
            if (on && machine != null && machine.testing()) {
                // A mute that did not take ends the test there (the
                // machine hangs up): this is how the test says why.
                AnswerTest.testMuteHeld = callAudio.isMicrophoneMute() ? 1 : 0;
            }
            Ln.i("answer: microphone " + (on ? "muted" : "unmuted") + " -> " + callAudio.isMicrophoneMute());
        } catch (Throwable t) {
            Ln.e("answer: could not " + (on ? "mute" : "unmute") + " the microphone", t);
        }
    }

    @Override
    public boolean muted() {
        return callAudio.isMicrophoneMute();
    }

    @Override
    public void playGreeting(boolean checkTone) {
        AnswerMachine machine = Answering.answerMachine;
        if (machine == null) {
            return; // cannot be: the machine is what asks
        }
        String route = Answering.answerSettings.route;
        Answering.greetingPlayer.play(callAudio, route, checkTone, Greeting.FILE, new GreetingPlayer.Listener() {
            @Override
            public void checkToneDone() {
                mainHandler.post(machine::checkToneDone);
            }

            @Override
            public void done(long realMs, long audioMs) {
                mainHandler.post(() -> {
                    if (machine.testing()) {
                        // Not the mute again: the one that matters was
                        // read back as it was asked for (setMuted), and
                        // one lost since would have been taken for the
                        // user taking the call, which stops the greeting
                        // before this. Unmuted now, at the beep, is the
                        // user's own tap - not a mute that failed.
                        AnswerTest.testRealMs = realMs;
                        AnswerTest.testAudioMs = audioMs;
                        AnswerTest.testPlayed = 1;
                    }
                    machine.greetingDone();
                });
            }

            @Override
            public void failed(String why) {
                mainHandler.post(() -> {
                    if (machine.testing()) {
                        AnswerTest.testPlayed = 0;
                    }
                    machine.greetingFailed();
                });
            }
        });
        if (checkTone) {
            // Two seconds in, the greeting's track is up: where did it go?
            mainHandler.postDelayed(AnswerTest::checkTestRoute, AnswerTest.GREETING_ROUTE_CHECK_MS);
        }
    }

    @Override
    public void stopGreeting() {
        Answering.greetingPlayer.stop();
    }

    @Override
    public boolean greetingLoud() {
        return Answering.ownSideSounds && Answering.greetingPlayer.loudNow();
    }

    @Override
    public void messageStarted() {
        AnswerMachine machine = Answering.answerMachine;
        boolean test = machine != null && machine.testing();
        if (test) {
            AnswerTest.testState = "running";
            try {
                AnswerTest.testVoice = phone.getVoiceNetworkType();
            } catch (Throwable t) {
                AnswerTest.testVoice = -1;
            }
        }
        // Only that a message is under way: where it starts, the
        // recording's name says at the end.
        Wakes.wake(Wakes.ACTION_MESSAGE_STARTED, true, "", null, "--ez", "test", String.valueOf(test));
    }

    @Override
    public void callTaken() {
        AnswerMachine machine = Answering.answerMachine;
        if (machine != null && CallDecisions.splitsOffMessage(Calls.recordingMode, machine.testing())) {
            // Ask first: the message ends where the user took the call,
            // and the call is theirs to record or not - asked, as at the
            // start of any call. Answering machines have always stopped
            // recording when picked up. Off the looper: stopping the
            // encoder can take a moment.
            AnswerMachine.Outcome part = machine.messageSoFar();
            new Thread(() -> Calls.splitOffMessage(part), "jemrec-split").start();
            return;
        }
        Wakes.wake(Wakes.ACTION_MESSAGE_TAKEN, true, "", null);
    }

    @Override
    public void log(String message) {
        Ln.i(message);
    }
}
