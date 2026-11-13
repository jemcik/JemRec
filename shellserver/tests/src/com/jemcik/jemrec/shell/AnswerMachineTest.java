package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

/**
 * The answering machine on a fake clock: when it answers, when it must not,
 * and that it always gives the microphone back.
 */
public class AnswerMachineTest {

    /** A clock, a timer queue, and a record of what the machine did. */
    private static final class World implements AnswerMachine.Effects {
        long now = 1_000_000L;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> did = new ArrayList<>();
        boolean callUp;
        boolean otherAppCallUp;
        int otherAppCallAsked;
        boolean emergencyCallback;
        /** The call's audio mode: not in call while it rings; answering puts
         *  it in call, unless [audioComes] is false. */
        boolean audioInCall;
        boolean audioComes = true;
        /** A phone call that plays through the mixer: its audio up in
         *  communication mode, never in call. */
        boolean mixerCall;
        boolean muted;
        /** Whether a mute asked for takes. */
        boolean muteTakes = true;
        boolean answerWorks = true;
        boolean greetingPresent = true;
        boolean greetingLoud;
        /** A greeting was started and not stopped: stopping an idle player
         *  does nothing, as GreetingPlayer.stop. */
        boolean playing;
        /** The way the call's audio took as each greeting was started. */
        final List<Integer> paths = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        /** Communication mode is the phone call's, not another app's call. */
        boolean phoneCallOnMixer = true;
        int mixerAsks;

        @Override
        public long now() {
            return now;
        }

        @Override
        public Object schedule(Runnable task, long delayMs) {
            long[] handle = {now + delayMs};
            due.add(handle);
            tasks.add(task);
            return handle;
        }

        @Override
        public void cancel(Object handle) {
            int i = due.indexOf(handle);
            if (i >= 0) {
                due.remove(i);
                tasks.remove(i);
            }
        }

        @Override
        public boolean callUp() {
            return callUp;
        }

        @Override
        public boolean otherAppCallUp() {
            otherAppCallAsked++;
            return otherAppCallUp;
        }

        @Override
        public boolean emergencyCallback() {
            return emergencyCallback;
        }

        @Override
        public boolean greetingPresent() {
            return greetingPresent;
        }

        @Override
        public boolean answer() {
            did.add("answer");
            if (answerWorks) {
                audioInCall = audioComes;
            }
            return answerWorks;
        }

        @Override
        public void hangUp() {
            did.add("hang up");
        }

        @Override
        public boolean audioInCall() {
            return audioInCall;
        }

        @Override
        public boolean callAudioUp() {
            return audioInCall || mixerCall;
        }

        /** In call is the voice path - the mode is one at a time, so in call
         *  wins over a mixer call left set. */
        @Override
        public int callPath() {
            return audioInCall ? Checks.PATH_VOICE : mixerCall ? Checks.PATH_MIXER : Checks.PATH_NONE;
        }

        @Override
        public boolean phoneCallOnMixer() {
            mixerAsks++;
            return phoneCallOnMixer;
        }

        @Override
        public void setMuted(boolean on) {
            if (!on || muteTakes) {
                muted = on;
            }
            did.add(on ? "mute" : "unmute");
        }

        @Override
        public boolean muted() {
            return muted;
        }

        @Override
        public void playGreeting(boolean checkTone, int path) {
            did.add(checkTone ? "greeting with check tone" : "greeting");
            paths.add(path);
            playing = true;
        }

        @Override
        public void stopGreeting() {
            if (playing) {
                did.add("stop greeting");
            }
            playing = false;
        }

        @Override
        public boolean greetingLoud() {
            return greetingLoud;
        }

        @Override
        public void callTaken() {
            did.add("call taken");
        }

        @Override
        public void messageStarted() {
            did.add("message started");
        }

        @Override
        public void log(String message) {
            logs.add(message);
        }

        /** Run every timer due within [ms], in order, as the clock moves. */
        void advance(long ms) {
            long until = now + ms;
            while (true) {
                int next = -1;
                for (int i = 0; i < due.size(); i++) {
                    if (due.get(i)[0] <= until && (next < 0 || due.get(i)[0] < due.get(next)[0])) {
                        next = i;
                    }
                }
                if (next < 0) {
                    break;
                }
                now = due.remove(next)[0];
                tasks.remove(next).run();
            }
            now = until;
        }

        boolean timersPending() {
            return !due.isEmpty();
        }
    }

    private final World world = new World();
    private final AnswerMachine machine = new AnswerMachine(world);

    private void assertDid(String... what) {
        assertEquals(Arrays.asList(what), world.did);
    }

    private void on() {
        machine.configure(true, 60_000L);
    }

    /** Ring, go-ahead at once, wait the delay: answered and greeting. A
     *  call before it in the same test has ended: its audio has gone. */
    private void answeredWithGreeting() {
        world.audioInCall = false;
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(20_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertEquals("the greeting should have begun", AnswerMachine.State.GREETING, machine.state());
    }

    @Test
    public void answersAfterTheDelayWithMuteThenGreeting() {
        answeredWithGreeting();
        assertDid("answer", "mute", "greeting", "message started");
        assertEquals(AnswerMachine.State.GREETING, machine.state());
        assertTrue(world.muted);
    }

    @Test
    public void doesNotAnswerBeforeTheDelay() {
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(19_999L);
        assertDid();
        world.advance(1L);
        assertDid("answer");
    }

    @Test
    public void noGoAheadNoAnswer() {
        on();
        machine.ringing();
        world.advance(120_000L);
        assertDid();
        assertEquals(AnswerMachine.State.RINGING, machine.state());
    }

    @Test
    public void switchedOffNoAnswer() {
        machine.configure(false, 60_000L);
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void aGoAheadBeforeTheRingCounts() {
        on();
        machine.goAhead(5_000L, world.now);
        world.advance(1_000L);
        machine.ringing();
        world.advance(5_000L);
        assertDid("answer");
    }

    @Test
    public void aStaleGoAheadIsForgotten() {
        on();
        machine.goAhead(5_000L, world.now);
        world.advance(AnswerMachine.GO_AHEAD_EARLY_MS + 1);
        machine.ringing();
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void aGoAheadIsForOneRingOnly() {
        on();
        machine.goAhead(5_000L, world.now);
        machine.ringing();
        machine.idleSignal();
        machine.ringing();
        world.advance(60_000L);
        // The first ring took it and stopped; the second has none.
        assertDid();
    }

    @Test
    public void theUserAnsweringFirstCancels() {
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(4_000L);
        machine.offHook(true);
        world.advance(60_000L);
        assertDid();
        assertEquals(AnswerMachine.State.IDLE, machine.state());
    }

    @Test
    public void theCallerHangingUpWhileItRingsCancels() {
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(4_000L);
        machine.idleSignal();
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void callWaitingIsNeverAnswered() {
        on();
        world.callUp = true;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void aCallUpByTheTimeTheTimerFiresIsNotAnswered() {
        on();
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.callUp = true;
        world.advance(5_000L);
        assertDid();
    }

    @Test
    public void emergencyCallbackModeIsNeverAnswered() {
        on();
        world.emergencyCallback = true;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(60_000L);
        assertDid();
        // Not even a test: whoever rings in its window would be answered.
        machine.idleSignal();
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS + 1_000L);
        assertDid();
    }

    @Test
    public void switchingOffDisarms() {
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        machine.configure(false, 60_000L);
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void withdrawingTheGoAheadDisarms() {
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        machine.cancelGoAhead();
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void waitsForTheCallsAudio() {
        on();
        world.audioComes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        machine.offHook(true);
        world.advance(1_000L);
        assertDid("answer");
        world.audioInCall = true;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute", "greeting", "message started");
    }

    @Test
    public void noGreetingBeforeTheOffHook() {
        // The Honor has the call's audio up 14 ms after the answer, and the
        // call connected at 235 ms (2026-10-22): the room is kept out from
        // the first, the greeting - and the app's "Taking a message" - wait
        // for the call to be there.
        on();
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L + 4 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute");
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute", "greeting", "message started");
    }

    @Test
    public void anAnswerThatTookNoCallIsUndone() {
        // Accepting a ring that has just ended does nothing, and says nothing:
        // no off-hook, so no end - and nothing else would ever bring the
        // machine back from "answered".
        on();
        world.audioComes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L + AnswerMachine.AUDIO_WAIT_MS + 1_000L);
        assertDid("answer");
        assertEquals(AnswerMachine.State.IDLE, machine.state());
        assertFalse(machine.answeredThisCall());
        // So the next caller is answered, and the user's next call is theirs.
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        assertDid("answer", "answer");
    }

    @Test
    public void aRingEndingAsItIsAnsweredLeavesTheMachineReady() {
        on();
        world.audioComes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        assertDid("answer");
        // The caller's idle arrives a moment after the answer: no call is up.
        machine.idleSignal();
        assertEquals(AnswerMachine.State.IDLE, machine.state());
        assertFalse(machine.answeredThisCall());
        assertFalse(world.timersPending());
        assertNull(machine.callEnded());
    }

    @Test
    public void aCallThroughTheMixerIsGreetedNotHungUp() {
        // A Wi-Fi Calling call on a Galaxy S23: never MODE_IN_CALL, its audio
        // up in communication mode. It was hung up as a call whose audio
        // never came (2026-10-24); the caller heard nothing and was cut off.
        on();
        world.audioComes = false;
        world.mixerCall = true;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute", "greeting", "message started");
        assertEquals(AnswerMachine.State.GREETING, machine.state());
    }

    @Test
    public void audioThatNeverComesOnACallThatIsUpHangsUp() {
        // Answered, the call up, its audio never there: no greeting can go
        // into it, and the caller would hear the room - unmuted for.
        on();
        world.audioComes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_WAIT_MS + 1_000L);
        assertDid("answer", "hang up");
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertTrue(outcome.greetingFailed);
        assertFalse(outcome.nothingLeft());
    }

    @Test
    public void aMuteOnARingThatEndedIsUndone() {
        // On the Honor the call's audio comes up as the answer is sent,
        // before the network connects the call: a caller gone in that moment
        // leaves the microphone muted, and no call - whose end would undo it
        // - ever off-hook. No greeting has started, so the app was told of
        // no message.
        on();
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L + AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute");
        machine.idleSignal();
        assertDid("answer", "mute", "unmute");
        assertFalse(world.muted);
        assertEquals(AnswerMachine.State.IDLE, machine.state());
        assertFalse(machine.answeredThisCall());
        assertFalse(world.timersPending());
        // And the next caller is answered as ever.
        world.audioInCall = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        assertEquals("answer", world.did.get(world.did.size() - 1));
    }

    @Test
    public void aCallTheUserAnsweredAMomentBeforeIsLeftToThem() {
        // Their tap brought the call's audio up; telephony's off-hook has not
        // reached the machine yet. Accepting it again would do nothing - and
        // then the greeting would go into their call.
        on();
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(4_900L);
        world.audioInCall = true;
        world.advance(100L);
        assertDid();
        assertEquals(AnswerMachine.State.IDLE, machine.state());
    }

    @Test
    public void noGreetingHereNoAnswer() {
        // The app has its copy; this process's is missing - an upload that
        // failed. Answered, the caller would be hung up on.
        on();
        world.greetingPresent = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(30_000L);
        assertDid();
        // Nor a test: it plays the same file, and would fail - and the
        // route be blamed for it.
        machine.idleSignal();
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        assertDid();
    }

    @Test
    public void aMuteThatDoesNotTakeHangsUp() {
        // The caller would hear the room, and the mute watch would take the
        // unmuted microphone for the user taking the call.
        on();
        world.muteTakes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute", "hang up");
        world.advance(10_000L);
        assertFalse(world.did.contains("call taken"));
        assertFalse(world.did.contains("greeting"));
        assertTrue(machine.callEnded().greetingFailed);
    }

    @Test
    public void theLongestMessageHangsUp() {
        answeredWithGreeting();
        machine.greetingDone();
        world.advance(59_999L);
        assertFalse(world.did.contains("hang up"));
        world.advance(1L);
        assertTrue(world.did.contains("hang up"));
    }

    @Test
    public void talkToThemOpensTheMicAndLiftsTheLimit() {
        answeredWithGreeting();
        machine.talk();
        assertDid("answer", "mute", "greeting", "message started", "stop greeting", "unmute", "call taken");
        world.advance(120_000L);
        assertFalse(world.did.contains("hang up"));
        assertEquals(AnswerMachine.State.TALKING, machine.state());
    }

    @Test
    public void theMessageSoFarIsWhatATakeoverKeeps() {
        // Ask first: the recording up to the takeover is the message (Calls).
        answeredWithGreeting();
        world.advance(2_000L);
        long spoke = world.now;
        speak(1_500L);
        world.advance(1_000L);
        machine.talk();
        AnswerMachine.Outcome part = machine.messageSoFar();
        assertTrue(part.isMessage());
        assertEquals(spoke - AnswerMachine.PRE_ROLL_MS, part.messageStart());
        // Taken before a word: nothing of it to keep.
        machine.callEnded();
        answeredWithGreeting();
        machine.talk();
        assertEquals(0, machine.messageSoFar().speechAt);
        // At the call's end it is still the call the user took.
        assertTrue(machine.callEnded().talked);
    }

    @Test
    public void anUnmuteFromTheCallScreenIsTalkToThem() {
        answeredWithGreeting();
        machine.greetingDone();
        world.muted = false;
        world.advance(AnswerMachine.MUTE_WATCH_MS);
        // Seen once: not yet - it could be the call ending.
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
        world.advance(AnswerMachine.MUTE_WATCH_MS);
        assertEquals(AnswerMachine.State.TALKING, machine.state());
        world.advance(120_000L);
        assertFalse(world.did.contains("hang up"));
        // Never muted again over the user.
        assertEquals(1, world.did.stream().filter("mute"::equals).count());
        // And the app's "Taking a message" is told it is over.
        assertTrue(world.did.contains("call taken"));
    }

    @Test
    public void theMuteClearedByItsOwnHangUpIsNotATakeover() {
        // Measured on the Honor, 2026-10-22: the phone clears the mute as the
        // call ends.
        answeredWithGreeting();
        machine.greetingDone();
        world.advance(60_000L);
        assertTrue(world.did.contains("hang up"));
        world.muted = false;
        world.advance(5_000L);
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
        assertFalse(machine.callEnded().talked);
    }

    @Test
    public void theMuteClearedAsTheCallerHangsUpIsNotATakeover() {
        answeredWithGreeting();
        machine.greetingDone();
        world.muted = false;
        world.advance(AnswerMachine.MUTE_WATCH_MS);
        world.audioInCall = false;
        world.advance(5_000L);
        assertFalse(machine.callEnded().talked);
    }

    @Test
    public void aMomentaryUnmuteIsNotATakeover() {
        answeredWithGreeting();
        machine.greetingDone();
        world.muted = false;
        world.advance(AnswerMachine.MUTE_WATCH_MS);
        world.muted = true;
        world.advance(AnswerMachine.MUTE_WATCH_MS);
        world.muted = false;
        world.advance(AnswerMachine.MUTE_WATCH_MS);
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
    }

    @Test
    public void theEndUnmutesAndReportsTheMessage() {
        answeredWithGreeting();
        world.advance(6_000L);
        machine.greetingDone();
        long beep = world.now;
        world.advance(2_000L);
        machine.level(AnswerMachine.SOUND_RMS * 2, 20L);
        long spoke = world.now;
        world.advance(10_000L);
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertNotNull(outcome);
        assertTrue(world.did.contains("unmute"));
        assertFalse(world.muted);
        assertEquals(beep, outcome.beepEndedAt);
        assertEquals(spoke, outcome.firstSoundAt);
        // Spoke after the beep: the message starts at the beep.
        assertEquals(beep, outcome.messageStart());
        assertFalse(world.timersPending());
        assertEquals(AnswerMachine.State.IDLE, machine.state());
    }

    /** [ms] of the caller talking, in the recorder's 20 ms chunks. */
    private void speak(long ms) {
        for (long t = 0; t < ms; t += 20) {
            machine.level(AnswerMachine.SOUND_RMS * 2, 20L);
            world.advance(20L);
        }
    }

    @Test
    public void wordsOverTheGreetingAreKept() {
        answeredWithGreeting();
        world.advance(2_000L);
        long spoke = world.now;
        speak(1_200L);
        world.advance(3_000L);
        machine.greetingDone();
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertEquals(spoke - AnswerMachine.PRE_ROLL_MS, outcome.messageStart());
    }

    @Test
    public void aBlipAsTheCallConnectsDoesNotStartTheMessage() {
        // Measured on the Honor, 2026-10-22: a sound as the call connected,
        // then the caller listened in silence; the message kept those ten
        // seconds.
        answeredWithGreeting();
        world.advance(400L);
        speak(100L);
        world.advance(9_000L);
        machine.greetingDone();
        long beep = world.now;
        world.advance(2_000L);
        speak(3_000L);
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertEquals(beep, outcome.messageStart());
        // Past the beep: a message, words or not.
        assertFalse(outcome.nothingLeft());
    }

    @Test
    public void aBlipAndAHangUpBeforeTheBeepLeaveNothing() {
        // The same click, and a caller who then listened and hung up: no
        // word was said, so no message - not seconds of silence saved as one.
        answeredWithGreeting();
        world.advance(400L);
        speak(100L);
        world.advance(5_000L);
        assertTrue(machine.callEnded().nothingLeft());
    }

    @Test
    public void aFalseIdleDoesNotDeafenTheMachine() {
        // Google Phone reports idle mid-call, and stays idle (CallStates);
        // the call's audio mode says it goes on.
        answeredWithGreeting();
        world.advance(1_000L);
        machine.idleSignal();
        world.advance(2_000L);
        machine.callGoesOn();
        // A real end's audio lingers a moment: not yet taken for a false idle.
        machine.level(AnswerMachine.SOUND_RMS * 10, 20L);
        assertEquals(0, machine.callEnded().firstSoundAt);
        answeredWithGreeting();
        world.advance(1_000L);
        machine.idleSignal();
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        machine.callGoesOn();
        long spoke = world.now;
        speak(1_000L);
        assertEquals(spoke - AnswerMachine.PRE_ROLL_MS, machine.callEnded().messageStart());
    }

    @Test
    public void noLimitStillHearsTheCallerAfterAFalseIdle() {
        long beep = noLimitToTheBeep();
        machine.idleSignal();
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        machine.callGoesOn();
        // Talking on, in bursts, for longer than the silence allowed.
        for (int i = 0; i < 4; i++) {
            speak(1_000L);
            world.advance(10_000L);
        }
        assertTrue(world.now - beep > AnswerMachine.SILENCE_HANGUP_MS);
        assertFalse(world.did.contains("hang up"));
    }

    @Test
    public void anUnmuteAfterTheCallIsOverIsNotATakeover() {
        // The caller hung up mid-message; the Honor clears the mute as the
        // call ends, and the audio mode may stay a moment longer.
        answeredWithGreeting();
        machine.greetingDone();
        world.advance(5_000L);
        machine.idleSignal();
        world.muted = false;
        world.advance(3 * AnswerMachine.MUTE_WATCH_MS);
        assertFalse(world.did.contains("call taken"));
        assertFalse(machine.callEnded().talked);
    }

    @Test
    public void aMachineSwitchedOnMidRingLeavesThatRing() {
        // Off, it does not ask about other calls at all - three calls into
        // the audio service on every ring - so it cannot answer that ring.
        machine.configure(false, 60_000L);
        machine.ringing();
        assertEquals(0, world.otherAppCallAsked);
        on();
        machine.goAhead(5_000L, world.now);
        world.advance(30_000L);
        assertDid();
    }

    @Test
    public void wordsBrokenByShortPausesAreOneRun() {
        answeredWithGreeting();
        world.advance(1_000L);
        long spoke = world.now;
        // "Hi... it's me... call back": 300 ms bursts, 1 s pauses.
        for (int i = 0; i < 3; i++) {
            speak(300L);
            world.advance(1_000L);
        }
        machine.greetingDone();
        assertEquals(spoke - AnswerMachine.PRE_ROLL_MS, machine.callEnded().messageStart());
    }

    @Test
    public void aCallTheUserTookIsNotAMessage() {
        answeredWithGreeting();
        world.advance(1_000L);
        machine.talk();
        assertFalse(machine.callEnded().isMessage());
        answeredWithGreeting();
        machine.greetingDone();
        assertTrue(machine.callEnded().isMessage());
    }

    @Test
    public void silenceDoesNotCountAsSound() {
        answeredWithGreeting();
        world.advance(1_000L);
        machine.level(AnswerMachine.SOUND_RMS / 2, 20L);
        machine.greetingDone();
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertEquals(0, outcome.firstSoundAt);
    }

    @Test
    public void theRoomBeforeTheMuteSettlesIsNotTheCaller() {
        answeredWithGreeting();
        machine.level(AnswerMachine.SOUND_RMS * 10, 20L);
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertEquals(0, outcome.firstSoundAt);
    }

    @Test
    public void hangingUpDuringTheGreetingLeavesNoMessage() {
        answeredWithGreeting();
        world.advance(3_000L);
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertNotNull(outcome);
        assertEquals(0, outcome.messageStart());
        assertTrue(outcome.nothingLeft());
        assertTrue(world.did.contains("stop greeting"));
        assertTrue(world.did.contains("unmute"));
    }

    @Test
    public void aCallItDidNotAnswerReportsNothing() {
        on();
        machine.ringing();
        machine.offHook(true);
        assertNull(machine.callEnded());
        assertDid();
    }

    @Test
    public void afterTalkingTheEndDoesNotUnmuteAgain() {
        answeredWithGreeting();
        machine.talk();
        world.did.clear();
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertTrue(outcome.talked);
        assertFalse(world.did.contains("unmute"));
    }

    @Test
    public void hangUpNowHangsUp() {
        answeredWithGreeting();
        machine.hangUpNow();
        assertTrue(world.did.contains("hang up"));
    }

    @Test
    public void aTestCallAnswersWhileSwitchedOffAndMutesAfterTheCheckTone() {
        machine.configure(false, 60_000L);
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "greeting with check tone", "message started");
        assertFalse(world.muted);
        machine.checkToneDone();
        assertTrue(world.muted);
        machine.greetingDone();
        world.advance(AnswerMachine.TEST_MESSAGE_MS);
        assertTrue(world.did.contains("hang up"));
        assertTrue(machine.callEnded().test);
    }

    @Test
    public void aTestIsForOneCall() {
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.offHook(true);
        machine.callEnded();
        world.did.clear();
        machine.ringing();
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void anExpiredTestAnswersNothing() {
        machine.armTest(true, 1_000L);
        world.advance(2_000L);
        machine.ringing();
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void anOutgoingTestTakesTheNextPlacedCall() {
        machine.armTest(false, 60_000L);
        // An outgoing call is in call from the moment it is dialled.
        world.audioInCall = true;
        machine.offHook(false);
        world.advance(AnswerMachine.TEST_OUTGOING_WAIT_MS);
        assertDid("greeting with check tone", "message started");
        assertTrue(machine.testing());
    }

    @Test
    public void anIncomingTestIgnoresPlacedCalls() {
        machine.armTest(true, 60_000L);
        machine.offHook(false);
        world.advance(60_000L);
        assertDid();
    }

    @Test
    public void theAnswerItselfFailingLeavesTheCall() {
        on();
        world.answerWorks = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(60_000L);
        assertDid("answer");
        assertEquals(AnswerMachine.State.IDLE, machine.state());
    }

    @Test
    public void everyTimerIsGoneAfterTheEnd() {
        answeredWithGreeting();
        machine.greetingDone();
        machine.callEnded();
        assertFalse(world.timersPending());
        Iterator<String> nothing = world.did.iterator();
        assertNotNull(nothing);
    }

    @Test
    public void aGoAheadForARingThatIsOverIsNotTheNextCallersOne() {
        // The app looked a caller up while they gave up; its go-ahead came
        // after - and a stranger rang next.
        on();
        long firstRang = world.now;
        machine.ringing();
        world.advance(1_000L);
        machine.idleSignal();
        world.advance(500L);
        machine.goAhead(5_000L, firstRang);
        world.advance(1_000L);
        machine.ringing();
        world.advance(30_000L);
        assertDid();
        assertEquals(AnswerMachine.State.RINGING, machine.state());
        // Nor when it lands while the next one rings.
        machine.goAhead(5_000L, firstRang);
        world.advance(30_000L);
        assertDid();
    }

    @Test
    public void aGoAheadBeforeTheRingStillCountsAfterAnEarlierCall() {
        on();
        machine.ringing();
        machine.idleSignal();
        world.advance(60_000L);
        // The app saw the new ring before telephony told this process.
        machine.goAhead(5_000L, world.now);
        machine.ringing();
        world.advance(5_000L);
        assertDid("answer");
    }

    @Test
    public void aGreetingThatCannotPlayHangsUp() {
        answeredWithGreeting();
        assertTrue(machine.greetingFailed());
        assertTrue(world.did.contains("hang up"));
        // Hung up: a move before the end comes is not looked at.
        world.audioInCall = false;
        world.mixerCall = true;
        machine.pathApplied(Checks.PATH_MIXER);
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertEquals(world.logs.toString(), 0, world.logs.stream().filter(l -> l.contains("moved")).count());
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertFalse(world.muted);
        // Said so - not taken for a caller who hung up.
        assertTrue(outcome.greetingFailed);
        assertFalse(outcome.nothingLeft());
        // Only while the greeting plays.
        assertFalse(machine.greetingFailed());
        assertEquals(1, world.did.stream().filter("hang up"::equals).count());
    }

    /** The call moving under the greeting, as a Galaxy S23's did from LTE
     *  to Wi-Fi (2026-11-12): its audio away, back after [awayMs]. */
    private void callMoves(long awayMs) {
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.advance(awayMs);
        world.mixerCall = true;
    }

    /** The call moving to the mixer, as Telecom asks and Android applies,
     *  with nothing released under its greeting - in-call music's track,
     *  which plays on through a move. */
    private void movesToTheMixer() {
        world.audioInCall = false;
        world.mixerCall = true;
        machine.pathApplied(Checks.PATH_MIXER);
    }

    private long movedLogs() {
        return world.logs.stream().filter(l -> l.contains("moved")).count();
    }

    @Test
    public void aGreetingOnTheVoicePathWhoseCallMovesToTheMixerPlaysAgainForIt() {
        // A Galaxy S23's call answered on LTE, then handed over to Wi-Fi:
        // its greeting stopped as Android applied communication, and played
        // again from the start for the mixer's route.
        answeredWithGreeting();
        assertEquals(Arrays.asList(Checks.PATH_VOICE), world.paths);
        world.did.clear();
        movesToTheMixer();
        assertDid("stop greeting");
        // The word the S23's log showed as the greeting was stopped.
        assertTrue(world.logs.contains("answer: Android applied the call's mode: the mixer"));
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER), world.paths);
        assertTrue(world.muted);
        // Told again that it is there: nothing more.
        machine.pathApplied(Checks.PATH_MIXER);
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
        machine.greetingDone();
        assertFalse(machine.callEnded().greetingFailed);
    }

    @Test
    public void aGreetingThroughTheMixerWhoseCallMovesToTheVoicePathPlaysAgainForIt() {
        world.mixerCall = true;
        world.audioComes = false;
        answeredWithGreeting();
        assertEquals(Arrays.asList(Checks.PATH_MIXER), world.paths);
        world.did.clear();
        world.mixerCall = false;
        world.audioInCall = true;
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
        assertEquals(Arrays.asList(Checks.PATH_MIXER, Checks.PATH_VOICE), world.paths);
    }

    @Test
    public void aReplayWaitsForTheModeToBeApplied() {
        // The S23's move from LTE to Wi-Fi (2026-11-13): normal then
        // communication asked 24 ms apart; normal applied 0.13 s after,
        // releasing the call-screening track; communication applied 0.6 s
        // after that. A track made before then would go with the mode
        // applied between.
        answeredWithGreeting();
        machine.pathApplied(Checks.PATH_VOICE);
        world.did.clear();
        world.audioInCall = false;
        world.mixerCall = true;
        world.advance(130L);
        machine.pathApplied(Checks.PATH_NONE);
        machine.greetingInterrupted();
        world.playing = false;
        world.advance(600L);
        assertDid();
        machine.pathApplied(Checks.PATH_MIXER);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER), world.paths);
        // Back: in-call music has its output only with in call applied.
        world.did.clear();
        world.mixerCall = false;
        world.audioInCall = true;
        machine.pathApplied(Checks.PATH_NONE);
        machine.greetingInterrupted();
        world.playing = false;
        world.advance(600L);
        assertDid();
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER, Checks.PATH_VOICE), world.paths);
    }

    /** Answered with the ringtone's mode applied and in call asked for. */
    private void answeredBeforeTheModeIsApplied() {
        world.audioInCall = false;
        machine.pathApplied(Checks.PATH_NONE);
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(20_000L);
        machine.offHook(true);
    }

    @Test
    public void theFirstGreetingWaitsForTheModeToBeApplied() {
        // Muted at once - the room is out as soon as the audio is asked up -
        // the greeting once Android has applied it.
        answeredBeforeTheModeIsApplied();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute");
        world.logs.clear();
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute", "greeting", "message started");
        // The log says it waited: Android's word before the greeting.
        assertEquals("answer: Android applied the call's mode: its voice path", world.logs.get(0));
        // The next call waits on its own clock, not the last one's.
        world.advance(AnswerMachine.APPLIED_WAIT_MS);
        machine.callEnded();
        world.did.clear();
        answeredBeforeTheModeIsApplied();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute");
    }

    @Test
    public void aModeNeverSaidAppliedIsTakenAsAskedAfterAWhile() {
        // Android's word lost: as before 0.31, by the asking alone - the
        // greeting late, not the caller hung up on.
        answeredBeforeTheModeIsApplied();
        world.advance(AnswerMachine.APPLIED_WAIT_MS);
        assertFalse(world.did.contains("greeting"));
        // The poll a whole wait after the one that first saw it asked.
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("greeting"));
        assertFalse(world.did.contains("hang up"));
        assertTrue(world.logs.toString(), world.logs.stream().anyMatch(l -> l.contains("has not said it applied")));
        // And a replay the same: the mode it waits for never said.
        world.did.clear();
        world.audioInCall = false;
        world.mixerCall = true;
        machine.greetingInterrupted();
        world.playing = false;
        world.advance(AnswerMachine.APPLIED_WAIT_MS);
        assertDid();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
    }

    @Test
    public void aMoveThatBouncesBackWaitsForItsModeAgain() {
        // Communication asked and applied, then in call asked again before
        // Android applies it: the replay waits for in call applied, however
        // long ago in call was first seen - a music track made before has no
        // output a call has.
        answeredWithGreeting();
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(3_000L);
        world.did.clear();
        movesToTheMixer();
        world.mixerCall = false;
        world.audioInCall = true;
        world.advance(5 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting");
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_VOICE), world.paths);
    }

    @Test
    public void aGreetingBegunBeforeItsModeWasAppliedPlaysAgainOnceItIs() {
        // Begun on the asking alone, Android's word late: once it comes, the
        // greeting is made again for it - once.
        answeredBeforeTheModeIsApplied();
        world.advance(AnswerMachine.APPLIED_WAIT_MS + 2 * AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("greeting"));
        world.did.clear();
        machine.pathApplied(Checks.PATH_VOICE);
        assertDid("stop greeting");
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
    }

    @Test
    public void aGreetingThroughTheMixerBegunWithoutTheWordIsNotMadeAgainOnIt() {
        // The call-screening route's track there is injected, not given an
        // output by the mode applied: its word late changes nothing, and a
        // greeting played again for it would only count towards the hang-up.
        machine.pathApplied(Checks.PATH_NONE);
        world.audioComes = false;
        world.audioInCall = false;
        world.mixerCall = true;
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(20_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.APPLIED_WAIT_MS + 2 * AnswerMachine.AUDIO_POLL_MS);
        assertEquals(Arrays.asList(Checks.PATH_MIXER), world.paths);
        // Begun without the word, and the log says so.
        assertTrue(world.logs.toString(), world.logs.stream().anyMatch(l -> l.contains("has not said it applied")));
        world.did.clear();
        // The word comes, and the mute goes as Android applies the mode, a
        // moment after: put back, held - not taken for the user taking the
        // call, nor a greeting played again.
        machine.pathApplied(Checks.PATH_MIXER);
        world.advance(300L);
        world.muted = false;
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertDid("mute");
        assertTrue(world.muted);
        assertEquals(AnswerMachine.State.GREETING, machine.state());
    }

    @Test
    public void theFirstGreetingPutsBackAMuteDroppedAsTheModeIsApplied() {
        // Muted as the audio is asked up; Android applying the mode drops it:
        // put back before the greeting, not left for the mute watch to take
        // for the user taking the call.
        answeredBeforeTheModeIsApplied();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.muted = false;
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("answer", "mute", "mute", "greeting", "message started");
        world.advance(2 * AnswerMachine.MUTE_WATCH_MS);
        assertFalse(world.did.contains("call taken"));
        // One that does not take: hung up, as for any mute that will not take.
        machine.callEnded();
        world.did.clear();
        answeredBeforeTheModeIsApplied();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.muted = false;
        world.muteTakes = false;
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("hang up"));
        assertFalse(world.did.contains("greeting"));
    }

    @Test
    public void aMoveWhileTheCallWasSaidOverIsLookedAtWhenItGoesOn() {
        answeredWithGreeting();
        machine.idleSignal();
        world.did.clear();
        movesToTheMixer();
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        assertDid();
        long applied = world.logs.stream().filter(l -> l.contains("Android applied")).count();
        machine.callGoesOn();
        assertDid("stop greeting");
        // Looked at again, not said again: no mode was applied then.
        assertEquals(applied, world.logs.stream().filter(l -> l.contains("Android applied")).count());
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER), world.paths);
    }

    @Test
    public void aMoveWhileTheCallWasSaidOverIsLookedAtWhenItIsOffHookAgain() {
        // The other way out of a false idle: off-hook again, which calls off
        // the end check, so callGoesOn never comes.
        answeredWithGreeting();
        machine.idleSignal();
        world.did.clear();
        movesToTheMixer();
        assertDid();
        machine.offHook(true);
        assertDid("stop greeting");
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER), world.paths);
    }

    @Test
    public void theWordForAFallbackGreetingWhileSaidOverIsLookedAtWhenItGoesOn() {
        // A greeting begun on the asking alone, Android's word come while
        // telephony said the call was over: once the call goes on, the
        // greeting is made again for it.
        world.audioInCall = false;
        machine.pathApplied(Checks.PATH_NONE);
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(20_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.APPLIED_WAIT_MS + 2 * AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("greeting"));
        machine.idleSignal();
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        world.did.clear();
        machine.callGoesOn();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting");
    }

    @Test
    public void aMoveWhileSaidOverIsTheCallsOwn() {
        // A call that ended while said over, a move noted in it: the next
        // call's false idle is no move of its own.
        answeredWithGreeting();
        machine.idleSignal();
        movesToTheMixer();
        machine.callEnded();
        world.mixerCall = false;
        machine.pathApplied(Checks.PATH_VOICE);
        answeredWithGreeting();
        machine.idleSignal();
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        world.did.clear();
        machine.callGoesOn();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
    }

    @Test
    public void aCallGoingOnWithNoWordFromAndroidIsNoMove() {
        // No mode ever said applied - no listener here: nothing to look at
        // again, and nothing taken for a move.
        answeredWithGreeting();
        machine.idleSignal();
        world.did.clear();
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        machine.callGoesOn();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
        assertEquals(0, movedLogs());
    }

    @Test
    public void telecomIsAskedAboutAnAppsCallOnlySoOften() {
        // Another app's call holding communication after a cut: asked once
        // as Android applied it, then at most each MIXER_ASK_MS.
        answeredWithGreeting();
        world.phoneCallOnMixer = false;
        movesToTheMixer();
        machine.greetingInterrupted();
        world.mixerAsks = 0;
        world.advance(2 * AnswerMachine.MIXER_ASK_MS);
        assertTrue("asked " + world.mixerAsks, world.mixerAsks <= 2);
        // A mode applied anew: asked afresh.
        world.mixerAsks = 0;
        world.phoneCallOnMixer = true;
        machine.pathApplied(Checks.PATH_MIXER);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertEquals(1, world.mixerAsks);
        assertTrue(world.did.contains("greeting"));
    }

    @Test
    public void aModeAskedLateIsStillWaitedForNotHungUp() {
        // In call asked 6.5 s after the answer, Android's word never coming:
        // greeted on the asking alone 2 s later, as 0.30 greeted it at once -
        // not hung up at the answer's 8 s as audio that never came.
        machine.pathApplied(Checks.PATH_NONE);
        world.audioComes = false;
        world.audioInCall = false;
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(20_000L);
        machine.offHook(true);
        world.advance(6_500L);
        world.audioInCall = true;
        world.advance(AnswerMachine.AUDIO_WAIT_MS - 6_500L + AnswerMachine.AUDIO_POLL_MS);
        assertFalse(world.did.contains("hang up"));
        world.advance(AnswerMachine.APPLIED_WAIT_MS);
        assertTrue(world.did.contains("greeting"));
        assertFalse(world.did.contains("hang up"));
        // A cut's wait the same: the audio back late, its word never said.
        world.did.clear();
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.playing = false;
        world.advance(7_500L);
        world.audioInCall = true;
        world.advance(AnswerMachine.AUDIO_WAIT_MS - 7_500L + AnswerMachine.AUDIO_POLL_MS);
        assertFalse(world.did.contains("hang up"));
        world.advance(AnswerMachine.APPLIED_WAIT_MS);
        assertDid("greeting");
    }

    @Test
    public void aModeBetweenTheWaysIsNoMove() {
        // Normal for a moment as one mode gives way to the next: no way, so
        // the machine stops nothing; back on the same way, nothing was a
        // move. What a track does through it is the platform's: a
        // call-screening one it releases comes as a cut.
        answeredWithGreeting();
        world.did.clear();
        machine.pathApplied(Checks.PATH_NONE);
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
        assertEquals(1, world.paths.size());
    }

    @Test
    public void anotherAppsCallIsNoMove() {
        // Communication taken by an app's call - a messenger call answered
        // over the greeting: the phone call has not moved, and nothing plays
        // into the app's call.
        answeredWithGreeting();
        world.did.clear();
        world.phoneCallOnMixer = false;
        movesToTheMixer();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
        // Nor after a cut: waited for the phone call's way instead.
        machine.greetingInterrupted();
        world.playing = false;
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
        world.phoneCallOnMixer = true;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
    }

    @Test
    public void aMoveAlreadyCutIsNotStoppedAgain() {
        // The call-screening track released as its call moved: the cut is
        // under way, and the mode applied after it is the same move.
        answeredWithGreeting();
        world.did.clear();
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.playing = false;
        movesToTheMixer();
        world.advance(3 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER), world.paths);
        assertEquals(0, movedLogs());
    }

    @Test
    public void aModeChangeWhileTheCallIsSaidOverIsTheEnds() {
        // Telephony's idle, then the mode: a phone call ending into another
        // app's, say. The greeting is not stopped for it, nor played again.
        answeredWithGreeting();
        machine.idleSignal();
        world.did.clear();
        movesToTheMixer();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
        assertEquals(0, movedLogs());
    }

    @Test
    public void movesBetweenTheWaysCountAsCuts() {
        // Each played again counts: one more is hung up on, as for cuts.
        answeredWithGreeting();
        for (int i = 0; i < AnswerMachine.GREETING_RESTARTS; i++) {
            boolean toMixer = i % 2 == 0;
            world.audioInCall = !toMixer;
            world.mixerCall = toMixer;
            machine.pathApplied(toMixer ? Checks.PATH_MIXER : Checks.PATH_VOICE);
            world.advance(AnswerMachine.AUDIO_POLL_MS);
        }
        assertEquals(1 + AnswerMachine.GREETING_RESTARTS, world.paths.size());
        assertFalse(world.did.contains("hang up"));
        boolean toMixer = AnswerMachine.GREETING_RESTARTS % 2 == 0;
        world.audioInCall = !toMixer;
        world.mixerCall = toMixer;
        machine.pathApplied(toMixer ? Checks.PATH_MIXER : Checks.PATH_VOICE);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("hang up"));
        assertEquals(1 + AnswerMachine.GREETING_RESTARTS, world.paths.size());
        // The log says why: the last cut's own words - and that cut said no
        // more playing again.
        assertTrue(world.logs.toString(), world.logs.stream().anyMatch(l -> l.contains("played again as often as a call may be")));
        assertTrue(world.logs.toString(), world.logs.stream().anyMatch(l -> l.contains("hanging up")
                && l.contains("(the last cut: the call moved to " + (toMixer ? "the mixer" : "its voice path"))));
        assertTrue(machine.callEnded().greetingFailed);
    }

    @Test
    public void aMoveAfterTheBeepIsTheMessages() {
        // The greeting is over: the message is taken on whatever way the
        // call takes, nothing stopped or played.
        answeredWithGreeting();
        machine.greetingDone();
        world.did.clear();
        movesToTheMixer();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
        // A mute Android drops applying the mode is put back, as in the
        // greeting - not taken for the user taking the call.
        world.mixerCall = false;
        world.audioInCall = true;
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(300L);
        world.muted = false;
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertDid("mute");
        assertTrue(world.muted);
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
    }

    @Test
    public void aMuteThatWillNotGoBackAfterTheBeepKeepsTheMessage() {
        // The call moved during the message, the mute dropped and would not
        // take again: hung up, and what the caller said kept as the message.
        answeredWithGreeting();
        machine.greetingDone();
        world.muteTakes = false;
        world.did.clear();
        movesToTheMixer();
        world.advance(300L);
        world.muted = false;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("mute", "hang up");
        assertTrue(world.logs.contains("answer: the microphone did not mute again after the call moved; hanging up"));
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertFalse(outcome.greetingFailed);
        assertTrue(outcome.isMessage());
    }

    @Test
    public void aMoveAfterTheBeepWhileSaidOverIsHeldIfTheCallGoesOn() {
        // A false idle after the beep, a move inside it dropping the mute:
        // once the call is seen to go on, the mute is put back - not taken for
        // the user taking the call.
        answeredWithGreeting();
        machine.greetingDone();
        machine.idleSignal();
        movesToTheMixer();
        world.muted = false;
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        world.did.clear();
        machine.callGoesOn();
        assertTrue(world.logs.contains("answer: the idle was not the call's end; listening on"));
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertDid("mute");
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
    }

    @Test
    public void aMoveAfterTheBeepWhileSaidOverIsNotMutedForIt() {
        // The phone call ended into an app's call: the mode is that call's,
        // and its microphone not this machine's to mute.
        answeredWithGreeting();
        machine.greetingDone();
        machine.idleSignal();
        world.muted = false;
        world.did.clear();
        movesToTheMixer();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
    }

    @Test
    public void anAppsCallTakenDuringACutIsTheUsersCall() {
        // A messenger call through Telecom taken over the greeting, whose
        // call-screening track goes with the mode: nothing muted again in the
        // app's call, nothing hung up after the wait - an unmute as it begins
        // (Telecom's, for a call answered through it, or the user's) is the
        // user taking the call.
        answeredWithGreeting();
        world.phoneCallOnMixer = false;
        movesToTheMixer();
        machine.greetingInterrupted();
        world.playing = false;
        world.did.clear();
        world.muted = false;
        world.advance(AnswerMachine.AUDIO_WAIT_MS + 1_000L);
        assertFalse(world.did.toString(), world.did.contains("mute"));
        assertFalse(world.did.toString(), world.did.contains("hang up"));
        assertEquals(AnswerMachine.State.TALKING, machine.state());
        // Nothing unmuted as it began: still no hang-up on the user's call
        // after the wait for the phone call's audio.
        machine.callEnded();
        world.mixerCall = false;
        world.phoneCallOnMixer = true;
        machine.pathApplied(Checks.PATH_VOICE);
        answeredWithGreeting();
        world.phoneCallOnMixer = false;
        movesToTheMixer();
        machine.greetingInterrupted();
        world.playing = false;
        world.did.clear();
        world.advance(2 * AnswerMachine.AUDIO_WAIT_MS);
        assertFalse(world.did.toString(), world.did.contains("hang up"));
        // And one taken while a greeting played again holds the mute: its
        // unmute is not undone either.
        machine.callEnded();
        world.mixerCall = false;
        world.phoneCallOnMixer = true;
        machine.pathApplied(Checks.PATH_VOICE);
        answeredWithGreeting();
        movesToTheMixer();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.paths.toString(), world.paths.get(world.paths.size() - 1) == Checks.PATH_MIXER);
        world.phoneCallOnMixer = false;
        world.advance(AnswerMachine.MIXER_ASK_MS);
        world.did.clear();
        world.muted = false;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertFalse(world.did.toString(), world.did.contains("mute"));
    }

    @Test
    public void aSameWayReportWhileSaidOverIsNoMove() {
        // In call told again while said over - normal and back, say: no leg
        // on the other way, nothing played again once the call goes on.
        answeredWithGreeting();
        machine.idleSignal();
        machine.pathApplied(Checks.PATH_NONE);
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        world.did.clear();
        machine.callGoesOn();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
    }

    @Test
    public void aRoundTripWhileSaidOverInTheGreetingPlaysAgain() {
        // In call, the mixer, in call again while telephony said the call was
        // over, the mute dropped on the way: once it is seen to go on, the
        // greeting - through the mixer leg, in-call music to no output -
        // plays again, the mute put back, not taken for the call taken.
        answeredWithGreeting();
        machine.idleSignal();
        movesToTheMixer();
        world.mixerCall = false;
        world.audioInCall = true;
        machine.pathApplied(Checks.PATH_VOICE);
        world.muted = false;
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        world.did.clear();
        machine.callGoesOn();
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertDid("stop greeting", "mute", "greeting");
        assertEquals(AnswerMachine.State.GREETING, machine.state());
    }

    @Test
    public void anAppsCallTakenAfterTheBeepIsNotMutedForIt() {
        // A messenger call through Telecom taken during the message: its mode
        // is the app's, and an unmute as it begins (Telecom's, for a call
        // answered through it, or the user's) is the user's - the mute watch
        // takes it for the call taken, as before.
        answeredWithGreeting();
        machine.greetingDone();
        world.phoneCallOnMixer = false;
        world.did.clear();
        movesToTheMixer();
        world.muted = false;
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertFalse(world.did.contains("mute"));
        assertEquals(AnswerMachine.State.TALKING, machine.state());
        // The same once a false idle ends: the app's call is still not this
        // machine's to mute.
        machine.callEnded();
        world.mixerCall = false;
        world.phoneCallOnMixer = true;
        // The next call's in call, applied as it is answered.
        machine.pathApplied(Checks.PATH_VOICE);
        answeredWithGreeting();
        machine.greetingDone();
        machine.idleSignal();
        world.phoneCallOnMixer = false;
        movesToTheMixer();
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        machine.callGoesOn();
        world.did.clear();
        world.muted = false;
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertFalse(world.did.contains("mute"));
        assertEquals(AnswerMachine.State.TALKING, machine.state());
    }

    @Test
    public void aMoveToTheVoicePathAfterTheBeepIsThePhoneCallsWhateverAppIsInACall() {
        // In call is the phone call's mode alone - no app's call takes it -
        // so a mute dropped as Android applies it is put back even while an
        // app is in a call of its own (one ringing, say): only communication
        // mode may be that app's.
        world.mixerCall = true;
        world.audioComes = false;
        answeredWithGreeting();
        machine.greetingDone();
        world.phoneCallOnMixer = false;
        world.did.clear();
        world.mixerCall = false;
        world.audioInCall = true;
        machine.pathApplied(Checks.PATH_VOICE);
        world.advance(300L);
        world.muted = false;
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertDid("mute");
        assertTrue(world.muted);
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
    }

    @Test
    public void aMoveInAFalseIdleOfTheGreetingIsHeldIfTheBeepCameMeanwhile() {
        // The move while said over, its greeting played on to the beep before
        // the call is seen to go on: the mute it dropped is put back then.
        answeredWithGreeting();
        machine.idleSignal();
        movesToTheMixer();
        world.muted = false;
        machine.greetingDone();
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        world.did.clear();
        machine.callGoesOn();
        world.advance(4 * AnswerMachine.MUTE_WATCH_MS);
        assertDid("mute");
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
    }

    @Test
    public void aStoppedGreetingsLateEndIsNoBeep() {
        // Its end come between a move's cut and the greeting played again:
        // no message begun on it, the greeting plays again.
        answeredWithGreeting();
        movesToTheMixer();
        machine.greetingDone();
        assertEquals(AnswerMachine.State.GREETING, machine.state());
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER), world.paths);
    }

    @Test
    public void aCutIsUnderWayFromTheCutToTheGreetingPlayedAgain() {
        answeredWithGreeting();
        assertFalse(machine.cutUnderWay());
        movesToTheMixer();
        assertTrue(machine.cutUnderWay());
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertFalse(machine.cutUnderWay());
        machine.greetingInterrupted();
        assertTrue(machine.cutUnderWay());
        machine.callEnded();
        assertFalse(machine.cutUnderWay());
    }

    @Test
    public void aStoppedGreetingsLateReportIsTheSameCut() {
        // A release the stopped greeting reports between a move's cut and
        // the greeting played again: no second cut, the move its cause.
        answeredWithGreeting();
        world.logs.clear();
        movesToTheMixer();
        machine.greetingInterrupted();
        assertFalse(machine.greetingFailed());
        assertEquals(1, world.logs.stream().filter(l -> l.contains("the greeting stopped")).count());
        assertTrue(world.logs.toString(), world.logs.stream().anyMatch(l -> l.contains("(the call moved to the mixer)")));
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertEquals(Arrays.asList(Checks.PATH_VOICE, Checks.PATH_MIXER), world.paths);
    }

    @Test
    public void aMoveIsNotWatchedOnceTheCallIsTaken() {
        answeredWithGreeting();
        machine.talk();
        world.did.clear();
        movesToTheMixer();
        world.advance(10 * AnswerMachine.AUDIO_POLL_MS);
        assertDid();
        assertEquals(0, movedLogs());
    }

    @Test
    public void aTestMovedBeforeItsToneHasTheToneAgain() {
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.did.clear();
        movesToTheMixer();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "greeting with check tone");
    }

    @Test
    public void aGreetingCutOffAsTheCallMovesPlaysAgainOnceItsAudioIsBack() {
        answeredWithGreeting();
        world.did.clear();
        callMoves(470L);
        // Not while the audio is away: not hung up, nothing played.
        assertDid();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        // The greeting from the start, the mute held; the message the same.
        assertDid("greeting");
        assertTrue(world.muted);
        assertTrue(world.logs.contains("answer: the call's audio is up; the greeting plays again"));
        // A move that drops the mute: muted again before it plays.
        world.did.clear();
        world.mixerCall = false;
        callMoves(470L);
        world.muted = false;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("mute", "greeting");
        assertTrue(world.muted);
        assertEquals(AnswerMachine.State.GREETING, machine.state());
        machine.greetingDone();
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
        assertFalse(machine.callEnded().greetingFailed);
    }

    @Test
    public void aMoveStraightToAnotherCallModePlaysAgainAtOnce() {
        // In call to communication, the audio up throughout: the track made
        // for the one is released as the mode leaves it all the same.
        answeredWithGreeting();
        world.did.clear();
        world.mixerCall = true;
        machine.greetingInterrupted();
        world.advance(AnswerMachine.AUDIO_POLL_MS - 1);
        assertDid();
        world.advance(1L);
        assertDid("greeting");
    }

    @Test
    public void aMuteDroppedAsTheCallMovesIsNotTakenForTheUser() {
        // The audio up again at once, the mute gone with the move: muted
        // again before the mute watch has seen it twice, wherever in the
        // watch's period the cut falls.
        for (long at = 0; at < AnswerMachine.MUTE_WATCH_MS; at += 10) {
            answeredWithGreeting();
            world.advance(at);
            world.muted = false;
            machine.greetingInterrupted();
            world.advance(2 * AnswerMachine.MUTE_WATCH_MS);
            assertFalse("cut at " + at, world.did.contains("call taken"));
            assertTrue("cut at " + at, world.muted);
            machine.callEnded();
            world.did.clear();
        }
    }

    @Test
    public void aMuteDroppedAsTheModeIsAppliedAfterTheReplayIsPutBack() {
        // The S23's move: the mode asked for back before the track died, the
        // greeting played again at the first poll, and the mode applied 0.37
        // s after the cut - which may drop the mute. Put back before the
        // mute watch sees it twice, wherever in its period that falls.
        for (long at = 0; at < AnswerMachine.MUTE_WATCH_MS; at += 10) {
            answeredWithGreeting();
            world.advance(at);
            machine.greetingInterrupted();
            world.advance(370L);
            world.muted = false;
            world.advance(2 * AnswerMachine.MUTE_WATCH_MS);
            assertFalse("cut at " + at, world.did.contains("call taken"));
            assertTrue("cut at " + at, world.muted);
            machine.callEnded();
            world.did.clear();
        }
    }

    @Test
    public void dropsPutBackBetweenAreNotTakenForTheUser() {
        // The move drops the mute, and Android applying the mode drops it
        // again - and again a watch period later: each put back, so the mute
        // watch never sees two in a row, wherever the cut falls in its period
        // and the later drops in the hold.
        for (long at = 0; at < AnswerMachine.MUTE_WATCH_MS; at += 20) {
            for (long second = 100; second < AnswerMachine.REPLAY_MUTE_HOLD_MS; second += 70) {
                answeredWithGreeting();
                world.advance(at);
                world.muted = false;
                machine.greetingInterrupted();
                world.advance(AnswerMachine.AUDIO_POLL_MS + second);
                world.muted = false;
                world.advance(AnswerMachine.MUTE_WATCH_MS);
                if (second + AnswerMachine.MUTE_WATCH_MS < AnswerMachine.REPLAY_MUTE_HOLD_MS) {
                    world.muted = false;
                }
                world.advance(2 * AnswerMachine.MUTE_WATCH_MS);
                assertFalse("cut at " + at + ", again " + second, world.did.contains("call taken"));
                assertTrue("cut at " + at + ", again " + second, world.muted);
                machine.callEnded();
                world.did.clear();
            }
        }
    }

    @Test
    public void theMuteIsHeldOnlyWhileTheReplayBegins() {
        // After the hold, an unmute is the user's again: taken on the mute
        // watch's second sighting.
        answeredWithGreeting();
        machine.greetingInterrupted();
        world.advance(AnswerMachine.AUDIO_POLL_MS + AnswerMachine.REPLAY_MUTE_HOLD_MS);
        world.did.clear();
        world.muted = false;
        world.advance(2 * AnswerMachine.MUTE_WATCH_MS);
        assertTrue(world.did.contains("call taken"));
        assertFalse(world.did.contains("mute"));
    }

    @Test
    public void twoCutsBeforeTheAudioIsBackPlayItOnce() {
        answeredWithGreeting();
        world.did.clear();
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.advance(20L);
        machine.greetingInterrupted();
        world.mixerCall = true;
        world.advance(3 * AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
    }

    @Test
    public void audioNotBackAfterTheMoveHangsUp() {
        answeredWithGreeting();
        world.did.clear();
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.advance(AnswerMachine.AUDIO_WAIT_MS - 1);
        assertDid();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("stop greeting", "hang up");
        assertTrue(machine.callEnded().greetingFailed);
    }

    @Test
    public void aCallThatKeepsMovingIsHungUpOn() {
        answeredWithGreeting();
        for (int i = 0; i < AnswerMachine.GREETING_RESTARTS; i++) {
            callMoves(500L);
            world.advance(AnswerMachine.AUDIO_POLL_MS);
            world.mixerCall = false;
        }
        assertEquals(1 + AnswerMachine.GREETING_RESTARTS, world.did.stream().filter("greeting"::equals).count());
        assertFalse(world.did.contains("hang up"));
        assertEquals(AnswerMachine.GREETING_RESTARTS, world.logs.stream().filter(("answer: the greeting stopped"
                + " (the call's audio left); it plays again once the call's audio is up")::equals).count());
        // Cut once more: hung up when its audio is back, not played again -
        // and the log says so as it is cut.
        callMoves(500L);
        assertFalse(world.did.contains("hang up"));
        assertTrue(world.logs.toString(), world.logs.contains("answer: the greeting stopped (the call's audio left);"
                + " played again as often as a call may be, the call is hung up once its audio is up"));
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("hang up"));
        assertEquals(1 + AnswerMachine.GREETING_RESTARTS, world.did.stream().filter("greeting"::equals).count());
        assertTrue(machine.callEnded().greetingFailed);
    }

    @Test
    public void theEndAfterTheCallHasMovedIsStillTheEnds() {
        // Played again as often as a call may be, then the caller hangs up:
        // the end's cut is no greeting that failed - idle first, as on the
        // S23 and the Honor, or the cut first.
        for (boolean idleFirst : new boolean[]{true, false}) {
            world.mixerCall = false;
            answeredWithGreeting();
            for (int i = 0; i < AnswerMachine.GREETING_RESTARTS; i++) {
                callMoves(500L);
                world.advance(AnswerMachine.AUDIO_POLL_MS);
                world.mixerCall = false;
            }
            world.did.clear();
            if (idleFirst) {
                machine.idleSignal();
            }
            world.audioInCall = false;
            machine.greetingInterrupted();
            if (!idleFirst) {
                world.advance(200L);
                machine.idleSignal();
            }
            world.advance(2 * AnswerMachine.AUDIO_WAIT_MS);
            assertDid();
            assertFalse("idle first: " + idleFirst, machine.callEnded().greetingFailed);
        }
    }

    @Test
    public void aGreetingThatCannotPlayAsTheCallEndsIsTheEnds() {
        // Telephony says the call is over: the end is why, not the route.
        answeredWithGreeting();
        machine.idleSignal();
        world.did.clear();
        assertFalse(machine.greetingFailed());
        world.advance(2 * AnswerMachine.AUDIO_WAIT_MS);
        assertDid();
        assertFalse(machine.callEnded().greetingFailed);
    }

    @Test
    public void aReplayWhoseTrackCannotBeMadeIsACutToo() {
        // The mode moved again between the audio coming back and the track's
        // making, which Android refuses outside the call's modes: waited out
        // and played again, as a cut.
        answeredWithGreeting();
        callMoves(500L);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.did.clear();
        world.mixerCall = false;
        assertFalse(machine.greetingFailed());
        assertDid();
        world.mixerCall = true;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
    }

    @Test
    public void aFirstGreetingThatCannotPlayWithTheAudioAwayIsACut() {
        // The mode left between the audio being seen up and the track's
        // making, which Android refuses outside the call's modes: waited
        // out, and played once the audio is back.
        answeredWithGreeting();
        world.did.clear();
        world.audioInCall = false;
        assertFalse(machine.greetingFailed());
        assertDid();
        world.mixerCall = true;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
        assertFalse(machine.callEnded().greetingFailed);
    }

    @Test
    public void aGreetingPlayedAgainThatFailsWithTheAudioUpHangsUp() {
        // Its track made and stalled, the call's audio up throughout: the
        // greeting is why, as for the first - not another cut to wait out.
        answeredWithGreeting();
        callMoves(500L);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.did.clear();
        assertTrue(machine.greetingFailed());
        assertDid("stop greeting", "hang up");
        assertTrue(machine.callEnded().greetingFailed);
    }

    @Test
    public void aFailureAfterTheUsersHangUpIsNoFailedGreeting() {
        // The notification's Hang up, and the track fails as the hang-up
        // goes through, the call's audio still up: the user's end, not a
        // greeting that could not play.
        answeredWithGreeting();
        machine.hangUpNow();
        assertFalse(machine.greetingFailed());
        assertFalse(machine.callEnded().greetingFailed);
    }

    @Test
    public void aMuteThatDoesNotTakeAfterTheMoveHangsUp() {
        answeredWithGreeting();
        world.did.clear();
        world.muteTakes = false;
        world.muted = false;
        callMoves(500L);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("mute", "stop greeting", "hang up");
    }

    @Test
    public void aMuteThatWillNotGoBackAsTheGreetingPlaysAgainHangsUp() {
        // Played again, then the mute dropped as Android applies the mode,
        // and it will not take again: the caller would hear the room - hung
        // up, the greeting failed, as when it will not take before.
        answeredWithGreeting();
        callMoves(500L);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.did.clear();
        world.muteTakes = false;
        world.muted = false;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("mute", "stop greeting", "hang up");
        assertTrue(machine.callEnded().greetingFailed);
    }

    @Test
    public void aTestCutOffBeforeItsToneHasTheToneAgain() {
        // The S23's test call at 20:09 was cut 2.2 s after its greeting's
        // track began, part-way through its check tone; on 2026-11-13 one
        // answered over Wi-Fi Calling, its Wi-Fi turned off, was cut 3.5 s
        // in, before its tone was done, and muted only after the tone of the
        // greeting played again.
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.did.clear();
        callMoves(470L);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        // Not muted yet: a test mutes after its tone - nor by the hold as the
        // greeting begins again, which keeps a mute, not makes one.
        world.advance(AnswerMachine.REPLAY_MUTE_HOLD_MS);
        assertDid("greeting with check tone");
        assertFalse(world.muted);
        machine.checkToneDone();
        assertTrue(world.muted);
        // Cut again after it: the tone not again, the mute held.
        world.did.clear();
        world.mixerCall = false;
        callMoves(470L);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
    }

    @Test
    public void eachCallHasItsOwnRestartsAndTone() {
        // A test whose tone went out, its greeting then cut off as often as
        // a call may be.
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        machine.checkToneDone();
        for (int i = 0; i < AnswerMachine.GREETING_RESTARTS; i++) {
            callMoves(500L);
            world.advance(AnswerMachine.AUDIO_POLL_MS);
            world.mixerCall = false;
        }
        machine.greetingDone();
        machine.callEnded();
        // The next test, cut off before its tone: played again, tone and all.
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.did.clear();
        callMoves(470L);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting with check tone");
    }

    @Test
    public void aCutOffGreetingIsNotPlayedAgainOnceTheCallIsTakenOrHungUp() {
        // Talk to them while the audio is away: nothing plays after it.
        answeredWithGreeting();
        world.audioInCall = false;
        machine.greetingInterrupted();
        machine.talk();
        world.did.clear();
        world.mixerCall = true;
        world.advance(AnswerMachine.AUDIO_WAIT_MS);
        assertDid();
        machine.callEnded();
        // The notification's Hang up meanwhile: nor after that.
        world.mixerCall = false;
        answeredWithGreeting();
        world.audioInCall = false;
        machine.greetingInterrupted();
        machine.hangUpNow();
        world.did.clear();
        world.mixerCall = true;
        world.advance(AnswerMachine.AUDIO_WAIT_MS);
        assertDid();
        machine.callEnded();
        // Only while the greeting plays.
        world.mixerCall = false;
        answeredWithGreeting();
        machine.greetingDone();
        world.did.clear();
        machine.greetingInterrupted();
        world.advance(AnswerMachine.AUDIO_WAIT_MS);
        assertDid();
    }

    @Test
    public void aCutAsTheCallEndsIsTheEndsNotAFailedGreeting() {
        // On the screening route every call's end releases the track too.
        // Where telephony's idle comes first - the Honor's mode lingered a
        // third of a second after it (2026-10-22) - the cut is the end's:
        // nothing plays, nothing is hung up, and the end is no greeting that
        // failed.
        answeredWithGreeting();
        machine.idleSignal();
        world.did.clear();
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.mixerCall = true;
        world.advance(2 * AnswerMachine.AUDIO_WAIT_MS);
        assertDid();
        assertFalse(machine.callEnded().greetingFailed);
        // The idle come while it waits for the audio: the same.
        world.mixerCall = false;
        answeredWithGreeting();
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.advance(200L);
        machine.idleSignal();
        world.did.clear();
        world.mixerCall = true;
        world.advance(2 * AnswerMachine.AUDIO_WAIT_MS);
        assertDid();
        // ...and an idle the call outlives: the greeting plays then.
        machine.callGoesOn();
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("greeting");
        assertFalse(machine.callEnded().greetingFailed);
    }


    @Test
    public void aMuteDroppedWhileTheCallIsSaidOverIsPutBack() {
        // A false idle, then a move that drops the mute: the mute watch is
        // off while telephony says the call is over, so the wait mutes again
        // as soon as the call's audio is up - not the room open until the
        // call is seen to go on.
        answeredWithGreeting();
        machine.idleSignal();
        world.audioInCall = false;
        machine.greetingInterrupted();
        world.did.clear();
        world.muted = false;
        world.mixerCall = true;
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertDid("mute");
        assertTrue(world.muted);
        world.advance(AnswerMachine.FALSE_IDLE_MS);
        assertDid("mute");
    }
    @Test
    public void soundAfterTheCallIsOverIsNotTheCaller() {
        answeredWithGreeting();
        world.advance(1_000L);
        // Telephony says the call is over; the recording runs on until the
        // end is confirmed, hearing the line go down.
        machine.idleSignal();
        world.advance(1_500L);
        machine.level(AnswerMachine.SOUND_RMS * 10, 20L);
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertEquals(0, outcome.firstSoundAt);
        assertTrue(outcome.nothingLeft());
        // And the next call hears again.
        answeredWithGreeting();
        world.advance(1_000L);
        machine.level(AnswerMachine.SOUND_RMS * 10, 20L);
        assertTrue(machine.callEnded().firstSoundAt > 0);
    }

    @Test
    public void theGreetingsOwnSoundIsNotTheCaller() {
        answeredWithGreeting();
        world.advance(1_000L);
        world.greetingLoud = true;
        machine.level(AnswerMachine.SOUND_RMS * 10, 20L);
        world.advance(1_000L);
        // In the greeting's pause, the caller is heard.
        world.greetingLoud = false;
        machine.level(AnswerMachine.SOUND_RMS * 10, 20L);
        long spoke = world.now;
        assertEquals(spoke, machine.callEnded().firstSoundAt);
    }

    @Test
    public void afterTheBeepTheGreetingCannotBeLoud() {
        answeredWithGreeting();
        world.advance(6_000L);
        machine.greetingDone();
        world.greetingLoud = true;
        world.advance(1_000L);
        machine.level(AnswerMachine.SOUND_RMS * 10, 20L);
        assertTrue(machine.callEnded().firstSoundAt > 0);
    }

    @Test
    public void aCallTheUserTookIsNeverNothingLeft() {
        answeredWithGreeting();
        world.advance(1_000L);
        machine.talk();
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertTrue(outcome.talked);
        assertFalse(outcome.nothingLeft());
    }

    @Test
    public void aCallTheGreetingNeverReachedIsNeverNothingLeft() {
        on();
        world.audioComes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        machine.offHook(true);
        world.callUp = true;
        world.advance(AnswerMachine.AUDIO_WAIT_MS + AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("hang up"));
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertFalse(outcome.nothingLeft());
    }

    /** As answeredWithGreeting, with no limit on the message, to the beep. */
    private long noLimitToTheBeep() {
        world.audioInCall = false;
        machine.configure(true, 0);
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(20_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        world.advance(6_000L);
        machine.greetingDone();
        return world.now;
    }

    @Test
    public void noLimitRunsWhileTheCallerSpeaks() {
        noLimitToTheBeep();
        // Ten minutes, a word every twenty seconds: never hung up.
        for (int i = 0; i < 30; i++) {
            world.advance(20_000L);
            machine.level(AnswerMachine.SOUND_RMS * 2, 20L);
        }
        assertFalse(world.did.contains("hang up"));
        assertEquals(AnswerMachine.State.MESSAGE, machine.state());
    }

    @Test
    public void noLimitHangsUpAfterSilence() {
        noLimitToTheBeep();
        world.advance(5_000L);
        machine.level(AnswerMachine.SOUND_RMS * 2, 20L);
        long spoke = world.now;
        world.advance(AnswerMachine.SILENCE_HANGUP_MS - 2_000L);
        assertFalse(world.did.contains("hang up"));
        world.advance(3_000L);
        assertTrue(world.did.contains("hang up"));
        assertTrue(world.now - spoke >= AnswerMachine.SILENCE_HANGUP_MS);
        // Its own hang-up: the mute the phone clears as the call ends is not
        // the user taking it.
        machine.callEnded();
        assertFalse(world.muted);
    }

    @Test
    public void noLimitWithACallerWhoNeverSpeaks() {
        long beep = noLimitToTheBeep();
        world.advance(AnswerMachine.SILENCE_HANGUP_MS + AnswerMachine.SILENCE_CHECK_MS);
        assertTrue(world.did.contains("hang up"));
        assertTrue(world.now - beep <= AnswerMachine.SILENCE_HANGUP_MS + AnswerMachine.SILENCE_CHECK_MS);
    }

    @Test
    public void takingTheCallStopsTheSilenceWatch() {
        noLimitToTheBeep();
        machine.talk();
        world.advance(10 * AnswerMachine.SILENCE_HANGUP_MS);
        assertFalse(world.did.contains("hang up"));
        assertFalse(world.timersPending());
    }

    @Test
    public void aLimitStillHangsUpOnTime() {
        answeredWithGreeting();
        world.advance(6_000L);
        machine.greetingDone();
        world.advance(59_000L);
        assertFalse(world.did.contains("hang up"));
        world.advance(1_000L);
        assertTrue(world.did.contains("hang up"));
    }

    @Test
    public void aPhoneCallDuringAMessengerCallIsNeverAnswered() {
        // On WhatsApp, Viber or in a meeting: answering would take the user
        // out of it, and the mute would silence them there.
        on();
        world.otherAppCallUp = true;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(30_000L);
        assertDid();
    }

    @Test
    public void aMessengerCallStartedWhileItRingsStopsTheAnswer() {
        on();
        machine.ringing();
        machine.goAhead(10_000L, world.now);
        world.advance(4_000L);
        world.otherAppCallUp = true;
        world.advance(10_000L);
        assertDid();
        assertEquals(AnswerMachine.State.IDLE, machine.state());
    }

    @Test
    public void notEvenATestCallDuringAMessengerCall() {
        on();
        machine.armTest(true, 180_000L);
        world.otherAppCallUp = true;
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS + 1_000L);
        assertDid();
    }

    @Test
    public void theAppsYesToARingOverAMessengerCallIsIgnored() {
        // Measured on the emulator, 2026-10-22, by the answer_during_voip
        // check: the ring took the audio mode before the app looked, and the
        // app said yes while this said no.
        on();
        world.otherAppCallUp = true;
        long rang = world.now;
        machine.ringing();
        world.advance(400L);
        machine.goAhead(5_000L, rang + 400L);
        world.advance(30_000L);
        assertDid();
        // And it is not held for the next caller either.
        world.otherAppCallUp = false;
        machine.idleSignal();
        world.advance(1_000L);
        machine.ringing();
        world.advance(30_000L);
        assertDid();
    }

    @Test
    public void aTakenOverCallStartsWhereItWasTaken() {
        // The Honor, 2026-10-22: kept from the answer, a taken call began
        // with eight seconds of the user hearing their own greeting.
        answeredWithGreeting();
        world.advance(8_000L);
        long took = world.now;
        machine.talk();
        speak(3_000L);
        assertEquals(took - AnswerMachine.PRE_ROLL_MS, machine.callEnded().takenStart());
    }

    @Test
    public void anOffHookAfterAnIdleIsTheCallGoingOn() {
        // CallStates takes an off-hook inside its end check for the same call
        // going on, and then never says callGoesOn: the caller must be heard
        // again, and the user's unmute be theirs - or the limit would hang up
        // a call that goes on.
        answeredWithGreeting();
        machine.greetingDone();
        world.advance(1_000L);
        machine.idleSignal();
        world.advance(1_000L);
        machine.offHook(true);
        long spoke = world.now;
        speak(1_000L);
        world.muted = false;
        world.advance(2 * AnswerMachine.MUTE_WATCH_MS);
        assertEquals(AnswerMachine.State.TALKING, machine.state());
        assertEquals(spoke, machine.callEnded().speechAt);
    }

    @Test
    public void aTestRingThatEndedIsNoTestUnderWay() {
        // Or the next test is refused as busy, with no call up.
        machine.armTest(true, 180_000L);
        machine.ringing();
        assertTrue(machine.testing());
        machine.idleSignal();
        assertFalse(machine.testing());
        // Answered by the user first: the same.
        machine.armTest(true, 180_000L);
        machine.ringing();
        machine.offHook(true);
        assertFalse(machine.testing());
    }

    @Test
    public void aCallOfTheUsersWhileATestWaitsEndsTheTest() {
        // The user called an emergency number while a test waited for its
        // caller: whoever calls back is not answered as the test's.
        machine.armTest(true, 180_000L);
        machine.offHook(false);
        machine.callEnded();
        machine.ringing();
        world.advance(60_000L);
        assertDid();
        // A call they answered, while an outgoing test waited: their next
        // call out is theirs too.
        machine.idleSignal();
        machine.armTest(false, 15_000L);
        machine.ringing();
        machine.offHook(true);
        machine.callEnded();
        world.audioInCall = true;
        machine.offHook(false);
        world.advance(AnswerMachine.TEST_OUTGOING_WAIT_MS + 1_000L);
        assertDid();
    }

    @Test
    public void aCallTheUserTookIsTheirsToEnd() {
        // No message any more: a Hang up - from a notification left up, or
        // the recorder's as JemRec is uninstalled - leaves it be.
        answeredWithGreeting();
        assertTrue(machine.takingMessage());
        machine.talk();
        assertFalse(machine.takingMessage());
        assertTrue(machine.answeredThisCall());
        machine.hangUpNow();
        assertFalse(world.did.contains("hang up"));
    }

    @Test
    public void aTakenOverCallKeepsTheCallersWordsBeforeIt() {
        answeredWithGreeting();
        world.advance(2_000L);
        long spoke = world.now;
        speak(1_500L);
        world.advance(3_000L);
        machine.talk();
        assertEquals(spoke - AnswerMachine.PRE_ROLL_MS, machine.callEnded().takenStart());
    }

    // EDGES AND LEFTOVERS THE TESTS ABOVE DID NOT PIN - each a change PIT made
    // to the machine that no test noticed (./gradlew :daemon-tests:pitest).

    /** In the message: the beep just past, the clock exactly SETTLE_MS past
     *  the mute (answeredWithGreeting leaves it at the mute). */
    private void inMessage() {
        answeredWithGreeting();
        machine.greetingDone();
        world.advance(AnswerMachine.SETTLE_MS);
    }

    /** A machine with no message limit, answered and its greeting done. */
    private void inMessageWithNoLimit() {
        machine.configure(true, 0L);
        world.audioInCall = false;
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        world.advance(20_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        machine.greetingDone();
    }

    private void armed() {
        on();
        machine.ringing();
        machine.goAhead(20_000L, world.now);
        assertEquals(AnswerMachine.State.ARMED, machine.state());
    }

    @Test
    public void aWithdrawnGoAheadLeavesNoTimerBehind() {
        armed();
        machine.cancelGoAhead();
        assertFalse(world.timersPending());
    }

    @Test
    public void aRingThatStopsLeavesNoTimerBehind() {
        armed();
        machine.idleSignal();
        assertFalse(world.timersPending());
    }

    @Test
    public void theUserAnsweringFirstLeavesNoTimerBehind() {
        armed();
        machine.offHook(true);
        assertEquals(AnswerMachine.State.IDLE, machine.state());
        assertFalse(world.timersPending());
    }

    @Test
    public void aCallThatEndsWhileArmedLeavesNoTimerBehind() {
        armed();
        assertNull(machine.callEnded());
        assertFalse(world.timersPending());
    }

    @Test
    public void takingTheCallDuringTheGreetingStopsItsWatches() {
        answeredWithGreeting();
        machine.talk();
        assertFalse(world.timersPending());
    }

    @Test
    public void takingTheCallStopsTheSilenceWatchToo() {
        inMessageWithNoLimit();
        machine.talk();
        assertFalse(world.timersPending());
    }

    @Test
    public void soundExactlyAtTheLevelIsSound() {
        inMessage();
        world.advance(1_000L);
        machine.level(AnswerMachine.SOUND_RMS, AnswerMachine.SPEECH_MS);
        assertEquals(world.now - AnswerMachine.PRE_ROLL_MS, machine.callEnded().takenStart());
    }

    @Test
    public void soundRightAtTheEndOfTheSettlingCounts() {
        inMessage();
        machine.level(2 * AnswerMachine.SOUND_RMS, AnswerMachine.SPEECH_MS);
        assertEquals(world.now - AnswerMachine.PRE_ROLL_MS, machine.callEnded().takenStart());
    }

    @Test
    public void exactlyEnoughSoundIsWords() {
        inMessage();
        world.advance(1_000L);
        machine.level(2 * AnswerMachine.SOUND_RMS, AnswerMachine.SPEECH_MS);
        assertEquals(world.now - AnswerMachine.PRE_ROLL_MS, machine.callEnded().takenStart());
    }

    @Test
    public void aPauseOfExactlyTheRunGapKeepsTheWordsTogether() {
        inMessage();
        world.advance(1_000L);
        long first = world.now;
        machine.level(2 * AnswerMachine.SOUND_RMS, 300L);
        world.advance(AnswerMachine.RUN_GAP_MS);
        machine.level(2 * AnswerMachine.SOUND_RMS, 200L);
        assertEquals(first - AnswerMachine.PRE_ROLL_MS, machine.callEnded().takenStart());
    }

    @Test
    public void exactlyThirtySecondsOfSilenceEndsAMessageWithNoLimit() {
        inMessageWithNoLimit();
        world.did.clear();
        world.advance(AnswerMachine.SILENCE_HANGUP_MS);
        assertDid("hang up");
    }

    @Test
    public void theAnswerGivesUpOnAudioAtExactlyItsWait() {
        on();
        world.audioInCall = false;
        world.audioComes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_WAIT_MS);
        assertDid("answer", "hang up");
    }

    @Test
    public void aRingAtTheVeryEndOfTheTestWindowIsNotTheTest() {
        machine.armTest(true, 10_000L);
        world.advance(10_000L);
        machine.ringing();
        assertEquals(AnswerMachine.State.RINGING, machine.state());
        assertFalse(world.timersPending());
    }

    @Test
    public void anOutgoingCallAtTheVeryEndOfTheTestWindowIsNotTheTest() {
        machine.armTest(false, 10_000L);
        world.advance(10_000L);
        machine.offHook(false);
        assertEquals(AnswerMachine.State.IDLE, machine.state());
        assertFalse(world.timersPending());
    }

    @Test
    public void aGoAheadExactlyItsHoldBeforeTheRingStillCounts() {
        on();
        machine.goAhead(20_000L, 0L);
        world.advance(AnswerMachine.GO_AHEAD_EARLY_MS);
        machine.ringing();
        assertEquals(AnswerMachine.State.ARMED, machine.state());
    }

    @Test
    public void takingTheCallBeforeItIsAnsweredDoesNothing() {
        on();
        machine.ringing();
        machine.talk();
        assertDid();
        assertEquals(AnswerMachine.State.RINGING, machine.state());
    }

    @Test
    public void takingACallThatWasNeverMutedUnmutesNothing() {
        on();
        world.audioInCall = false;
        world.audioComes = false;
        machine.ringing();
        machine.goAhead(5_000L, world.now);
        world.advance(5_000L);
        machine.offHook(true);
        machine.talk();
        assertFalse(world.did.contains("unmute"));
    }

    @Test
    public void unmutingFromTheCallScreenDuringTheGreetingTakesTheCall() {
        answeredWithGreeting();
        world.muted = false;
        world.advance(2 * AnswerMachine.MUTE_WATCH_MS);
        assertTrue(world.did.contains("call taken"));
    }

    /** A test call, answered, its greeting and check tone playing UNMUTED -
     *  the phone's own recording can carry the tone only before the mute. */
    private void testCallGreeting() {
        machine.armTest(true, 180_000L);
        world.audioInCall = false;
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.offHook(true);
        world.advance(AnswerMachine.AUDIO_POLL_MS);
        assertTrue(world.did.contains("greeting with check tone"));
    }

    @Test
    public void aTestCallWhoseMuteFailsAfterTheCheckToneStopsTheToneAndHangsUp() {
        // The one way a hang-up meets the greeting still playing: a test
        // call's check tone goes out unmuted, and then the mute does not take.
        testCallGreeting();
        world.muteTakes = false;
        world.did.clear();
        machine.checkToneDone();
        assertDid("mute", "stop greeting", "hang up");
    }

    @Test
    public void aGoAheadDuringAnAnsweredCallIsIgnored() {
        // The app's lookup can finish after the machine has answered: its
        // word comes too late to change anything.
        answeredWithGreeting();
        world.did.clear();
        machine.goAhead(20_000L, world.now);
        assertEquals(AnswerMachine.State.GREETING, machine.state());
        assertDid();
    }

    @Test
    public void aTestCallsUnmutedCheckToneIsNotTheUserTakingTheCall() {
        testCallGreeting();
        world.advance(3 * AnswerMachine.MUTE_WATCH_MS);
        assertFalse(world.did.contains("call taken"));
    }

    @Test
    public void aTestCallEndedDuringItsUnmutedToneLeftSomething() {
        testCallGreeting();
        assertFalse(machine.callEnded().nothingLeft());
    }

    @Test
    public void aCallTakenWithoutWordsStartsHalfASecondBeforeTheTaking() {
        answeredWithGreeting();
        world.advance(3_000L);
        long takenAt = world.now;
        machine.talk();
        assertEquals(takenAt - AnswerMachine.PRE_ROLL_MS, machine.callEnded().takenStart());
    }

    @Test
    public void aTestThatCouldNotAnswerDoesNotMakeTheNextCallATest() {
        // A test ring with no greeting to play: not answered, and the caller
        // gives up - an unanswered ring never reaches callEnded, so only the
        // next ring's reset clears what the test set.
        world.greetingPresent = false;
        machine.armTest(true, 180_000L);
        machine.ringing();
        world.advance(AnswerMachine.TEST_ANSWER_MS);
        machine.idleSignal();
        world.greetingPresent = true;
        world.did.clear();
        answeredWithGreeting();
        assertTrue(world.did.contains("greeting"));
        assertFalse(world.did.contains("greeting with check tone"));
    }
}
