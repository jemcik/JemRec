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
        public void playGreeting(boolean checkTone) {
            did.add(checkTone ? "greeting with check tone" : "greeting");
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
        // Ask first: the recording up to the takeover is the message (Main).
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
        machine.greetingFailed();
        assertTrue(world.did.contains("hang up"));
        AnswerMachine.Outcome outcome = machine.callEnded();
        assertFalse(world.muted);
        // Said so - not taken for a caller who hung up.
        assertTrue(outcome.greetingFailed);
        assertFalse(outcome.nothingLeft());
        // Only while the greeting plays.
        machine.greetingFailed();
        assertEquals(1, world.did.stream().filter("hang up"::equals).count());
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
