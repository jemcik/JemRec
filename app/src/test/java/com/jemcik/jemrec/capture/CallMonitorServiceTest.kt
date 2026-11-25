package com.jemcik.jemrec.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.CallLog
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.RecordPrompt
import com.jemcik.jemrec.recorder.BackgroundKill
import com.jemcik.jemrec.recorder.RecorderSwitch
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.SaverFakes
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog

/**
 * The service the recorder wakes for each call (CallMonitorService), driven
 * the way the recorder drives it: intents carrying the token the app keeps in
 * its preferences (CaptureDaemon.token). What is pinned is what it does
 * today - which wakes it obeys, what its notification says, when it asks,
 * when it stands down, and what it tells the user after a save.
 *
 * No recorder runs here, so every question to it fails at once (nothing
 * listens on its port): a save ends "The recorder could not be reached.",
 * Talk to them and Record get no answer. The paths that need a recorder to
 * answer (a save that works, an empty recording) and a cable watch that
 * finds the recorder up or brings it back are left out; one that cannot bring
 * it back, and ends, is in.
 *
 * Where a test must stop a save half way, the save is held inside its read of
 * the call log (FakeCallLog.hold): with a file name that has the number in it,
 * the save asks the log who the call was with, and waits there for the test.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallMonitorServiceTest {

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var controller: ServiceController<CallMonitorService>
    private val service get() = controller.get()
    private var startId = 0

    /** A call a minute ago, inside the ledger's week. */
    private val start = System.currentTimeMillis() - 60_000

    private val realWatch = CallMonitorService.watchFor
    private val realButton = CallMonitorService.buttonFor
    private val realListen = CallMonitorService.listenToMode
    private val realCalls = LiveCalls.calls
    private val realHeld = LiveCalls.held
    private val realEnd = LiveCalls.end

    /** The audio-mode listener the service registered, as Android would
     *  hold it. */
    private var modeListener: android.media.AudioManager.OnModeChangedListener? = null

    @Before fun create() {
        ShadowLog.clear()
        // No watch on the recorder unless a test asks for one: the real one
        // pings and revives on its own time (RecorderWatch).
        CallMonitorService.watchFor = { _, _ -> null }
        CallMonitorService.listenToMode = { _, listener -> modeListener = listener }
        controller = Robolectric.buildService(CallMonitorService::class.java).create()
    }

    @After fun destroy() {
        controller.destroy()
        com.jemcik.jemrec.recordings.RecordingSaver.reconcileOwed = false
        CallMonitorService.telecomOutAtMs = 0L
        CallMonitorService.messageSince = 0L
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = 0L
        CallMonitorService.recorderUpNow = realUpNow
        CallMonitorService.watchFor = realWatch
        CallMonitorService.buttonFor = realButton
        CallMonitorService.listenToMode = realListen
        LiveCalls.calls = realCalls
        LiveCalls.end = realEnd
        LiveCalls.held = realHeld
    }

    private val realUpNow = CallMonitorService.recorderUpNow

    /** The fake watch's revives that brought the recorder back. */
    @Volatile private var backs = 0

    /** The fake watches' ends of the call: each said the call over. */
    @Volatile private var overs = 0

    /** A watch that looks every few ms, its world in the test's hands. */
    private fun fakeWatch(
        recorderUp: () -> Boolean,
        callUp: () -> Boolean,
        revive: () -> Boolean = { false },
        anyCall: () -> Boolean = callUp,
    ) {
        CallMonitorService.watchFor = { _, hooks ->
            RecorderWatch(
                callUp = callUp, anyCall = anyCall, watching = { true }, recorderUp = { recorderUp() }, revive = { revive() },
                stopped = hooks.stopped, over = { overs++; hooks.over(it) }, log = {}, starts = hooks.starts,
                lookMs = 5, retryMs = 5,
                ringIsTheCall = hooks.ringIsTheCall, back = { backs++; hooks.back() },
            )
        }
    }

    /** The recorder ended mid-call: the call's notification says so while
     *  it is brought back, and its own start of the call puts it back to
     *  recording (review, 2026-11-18). */
    @Test fun aRecorderGoneMidCallIsSaidAndBroughtBack() {
        var up = true
        var revived = 0
        fakeWatch(recorderUp = { up }, callUp = { true }, revive = { revived++; false })
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        assertEquals("Recording call", ongoing().title())
        up = false
        awaitTrue("the recorder's loss said") { ongoing().title() == "Recording stopped" }
        awaitTrue("a revive tried, and again") { revived >= 2 }
        // The revived recorder's own start of the call.
        up = true
        wake("CALL_STARTED", recfile(start + 9_000, true), incoming = true)
        assertEquals("Recording call", ongoing().title())
    }

    /** The call over and no recorder said so: the service stands down, its
     *  notification with it, rather than staying up until the next call. */
    @Test fun aCallTheRecorderNeverEndedIsEndedWhenTheAudioModeSaysSo() {
        var callUp = true
        fakeWatch(recorderUp = { false }, callUp = { callUp })
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        assertFalse(stopped)
        callUp = false
        awaitTrue("stood down") { stopped }
    }

    /** Over unsaid while the call before still saves: the service stays for
     *  the save, and its notification stops saying the recorder is being
     *  tried again for a call that is over (review, 2026-11-18). */
    @Test fun aCallEndedUnsaidWhileTheLastOneSavesStopsSayingTryingAgain() {
        val log = heldCallLog(start, incoming = true)
        try {
            val first = recfile(start, true)
            wake("CALL_STARTED", first, incoming = true)
            wake("CALL_ENDED", first, incoming = true)
            log.awaitHeld()
            var callUp = true
            fakeWatch(recorderUp = { false }, callUp = { callUp })
            wake("CALL_STARTED", recfile(start + 50_000, false), incoming = false)
            awaitTrue("the recorder's loss said") { ongoing().title() == "Recording stopped" }
            callUp = false
            awaitTrue("the call stood down, its notice redrawn") { ongoing().title() != "Recording stopped" }
            assertFalse("kept for the save", stopped)
            log.release()
            awaitLog("monitor: recording handled")
            awaitTrue("stood down once saved") { stopped }
        } finally {
            log.release()
        }
    }

    /** A recorder started again mid-call joins the call: in Ask first, a
     *  call answered Record goes on being recorded, not asked about again
     *  (review round 10). */
    @Test fun aRecorderJoiningACallAnsweredRecordRecordsTheRestUnasked() {
        val file = recfile(start, true)
        withRecord(file) {
            wake("CALL_STARTED", "", incoming = true)
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $file")
            settle()
            wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
            awaitLog("monitor: the recorder joined the call it was told to record; recording the rest")
            eventually("recorded on") { logs().count { it == "monitor: on-demand recording started -> $file" } == 2 }
            assertNull("not asked again", prompt())
        }
    }

    /** ...and a call answered Not now is not asked about again; a call of
     *  its own, after it, is. */
    @Test fun aRecorderJoiningACallDeclinedDoesNotAskAgain() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Not now"))
        assertNull(prompt())
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        awaitLog("monitor: the recorder joined a call the user said not to record; not asking again")
        assertNull(prompt())
        wake("CALL_ENDED", "", incoming = true)
        wake("CALL_STARTED", "", incoming = false)
        assertNotNull("a call of its own is asked about", prompt())
    }

    /** A fresh service whose first word is the recorder's stop takes it as
     *  its call, and is watched: the call over unsaid, it stands down
     *  (review round 10: no test had a watch begun by anything but a call's
     *  start). */
    @Test fun aFreshServiceTakingAStopAsItsCallStandsDownWhenItIsOver() {
        var callUp = true
        fakeWatch(recorderUp = { true }, callUp = { callUp })
        wake("RECORDING_STOPPED") { putExtra("for_good", false) }
        assertEquals("Recording stopped", ongoing().title())
        callUp = false
        awaitTrue("stood down") { stopped }
    }

    /** The same for a message's start. */
    @Test fun aFreshServiceTakingAMessageAsItsCallStandsDownWhenItIsOver() {
        var callUp = true
        fakeWatch(recorderUp = { false }, callUp = { callUp })
        wake("MESSAGE_STARTED", incoming = true)
        callUp = false
        awaitTrue("stood down") { stopped }
    }

    /** The recorder gone during a message: the greeting and the mute went
     *  with it, so its notice no longer says the greeting plays, nor offers
     *  Talk to them and Hang up (review round 10). */
    @Test fun aRecorderGoneDuringAMessageDropsTheGreetingsButtons() {
        fakeWatch(recorderUp = { false }, callUp = { true })
        wake("MESSAGE_STARTED", incoming = true)
        assertEquals(listOf("Talk to them", "Hang up"), ongoing().buttons())
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        assertTrue(ongoing().buttons().isEmpty())
    }

    /** The recorder lost while the machine takes a message: the message's
     *  own call hung up, as the machine would have - left, the caller heard
     *  silence until they hung up (review round 13). */
    @Test fun aRecorderLostMidMessageHangsUpItsCall() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        fakeWatch(recorderUp = { false }, callUp = { true })
        wake("MESSAGE_STARTED", incoming = true)
        awaitTrue("the message's call hung up") { ended == listOf(42L) }
        assertTrue(logs().contains("monitor: the recorder was lost while taking a message; hanging up its call"))
    }

    /** ...not when someone has taken the call - the microphone given back -
     *  nor with no call of its own known (exact call tracking off). */
    @Test fun aMessagesCallTakenOrUnknownIsLeftUp() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = false
        fakeWatch(recorderUp = { false }, callUp = { true })
        wake("MESSAGE_STARTED", incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        settle()
        assertEquals(emptyList<Long>(), ended)
        assertTrue(logs().any { it.startsWith("monitor: the recorder was lost while taking a message; its call left up") })
    }

    /** No call known as the message started - exact call tracking off, or
     *  bound too late to say which it was: a call up later is not taken for
     *  it. */
    @Test fun withNoCallKnownTheMessageIsLeftUp() {
        val ended = mutableListOf<Long>()
        // None as the message starts (the first ask), one by the loss.
        var asked = 0
        LiveCalls.calls = { if (asked++ == 0) emptyList() else listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        fakeWatch(recorderUp = { false }, callUp = { true })
        wake("MESSAGE_STARTED", incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        settle()
        assertEquals(emptyList<Long>(), ended)
    }

    /** The audio mode back to normal: the call is over, its Ask first
     *  answer with it - a call redialled within the watch's looks is asked
     *  about, not taken for the one declined (review round 11). */
    @Test fun aNormalModeForgetsTheAnswer() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Not now"))
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertNotNull("asked", prompt())
    }

    /** The ring's mode too: no call is left active (AOSP's
     *  CallAudioModeStateMachine) - the call up hung up while a waiting one
     *  rang, never passing normal (review round 12). */
    @Test fun aRingtoneModeForgetsTheAnswer() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Not now"))
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_RINGTONE)
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertNotNull("asked", prompt())
    }

    /** A joined start that came through a ring - the recorder sends it
     *  incoming - may be the waiting call itself, the call up having hung up
     *  while it rang silently, which no mode change says: asked about, not
     *  given the answer for the call up (review round 13). */
    @Test fun aJoinedStartThatRangIsAskedAgain() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Not now"))
        assertNull(prompt())
        wake("CALL_STARTED", "", incoming = true) { putExtra("joined", true) }
        assertNotNull("asked", prompt())
    }

    /** Over unsaid as the watch reads it, but the mode is in a call again -
     *  a call begun since: not stood down (review round 10; round 11: no
     *  test set the mode). */
    @Test fun aCallBegunSinceTheLastLookIsNotStoodDown() {
        var callUp = true
        fakeWatch(recorderUp = { false }, callUp = { callUp })
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        context.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_IN_CALL
        callUp = false
        Thread.sleep(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse("a call is up", stopped)
        context.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_NORMAL
        awaitTrue("stood down once the mode is out of the call") { stopped }
    }

    /** A takeover may be the first a restarted process hears of the call:
     *  watched from it (review round 11: no test began a watch so). */
    @Test fun aFreshServiceTakingATakeoverAsItsCallStandsDownWhenItIsOver() {
        var callUp = true
        fakeWatch(recorderUp = { false }, callUp = { callUp })
        wake("MESSAGE_TAKEN", incoming = true)
        callUp = false
        awaitTrue("stood down") { stopped }
    }

    /** A Record that did not take - the recorder down - with a call up:
     *  the call taken and watched, not stood down unwatched (review round
     *  11). */
    @Test fun aRecordThatDidNotTakeWithACallUpIsWatched() {
        var callUp = true
        fakeWatch(recorderUp = { false }, callUp = { callUp })
        context.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_IN_CALL
        withRecord(null) {
            RecordPrompt.show(context, incoming = true)
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand start did not take (call over?)")
            settle()
            assertFalse("taken and watched", stopped)
            context.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_NORMAL
            callUp = false
            awaitTrue("stood down once over") { stopped }
        }
    }

    /** A recorder lost mid-call and brought back: the call's end names only
     *  the joined part, and the part before is saved with it - by the
     *  hang-up's path, not left to a keep-alive run (review round 11). */
    @Test fun aCallsEndSavesThePartALostRecorderLeft() {
        val first = recfile(start, true)
        val joined = "jemrec_rec_${start + 60_000}_joined_out.dat"
        wake("CALL_STARTED", first, incoming = true)
        wake("CALL_STARTED", joined, incoming = false) { putExtra("joined", true) }
        wake("CALL_ENDED", joined, incoming = false)
        val until = System.currentTimeMillis() + 10_000
        while (!logs().any { it.startsWith("save:") && first in it }) {
            if (System.currentTimeMillis() > until) fail("the part before never handled:\n" + logs().joinToString("\n"))
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        // Reacted to as the reconcile reacts - its failure counted toward the
        // week, not told as this call not recorded; an empty one dropped
        // quietly, where the hang-up's row said "Call not recorded" for a
        // call recorded by the part after (review round 12).
        awaitTrue("the part before's failure counted") {
            com.jemcik.jemrec.Prefs.of(context).getStringSet("save_failing", emptySet()).orEmpty()
                .any { it.substringBefore('|') == first }
        }
    }

    /** A joined start making two files notes them as the call's parts at
     *  once - a reconcile before the call's end saves them as such (review
     *  round 15). */
    @Test fun aSecondFileNotesTheCallsParts() {
        val first = recfile(start, true)
        val joined = "jemrec_rec_${start + 60_000}_joined_in.dat"
        wake("CALL_STARTED", first, incoming = true)
        wake("CALL_STARTED", joined, incoming = true) { putExtra("joined", true) }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, joined))
    }

    /** A Record answer that brings the call to two files notes them as its
     *  parts too (review round 16: only a start did). */
    @Test fun aRecordBringingTwoFilesNotesTheCallsParts() {
        val first = recfile(start, true)
        val second = recfile(start + 60_000, true)
        wake("CALL_STARTED", first, incoming = true)
        withRecord(second) {
            RecordPrompt.show(context, incoming = true)
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $second")
        }
        // Noted just after that line, on the job's own thread.
        eventually("the parts noted") { com.jemcik.jemrec.recordings.CallParts.contains(context, second) }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** A start of a call of its own - not joined, a new file - while a call
     *  is up: the call before ended unsaid; its file is no part of this one
     *  (review round 16: two calls saved as one). */
    @Test fun aNewCallsStartIsNoPartOfTheCallBefore() {
        val before = recfile(start, true)
        val next = recfile(start + 60_000, false)
        wake("CALL_STARTED", before, incoming = true)
        wake("CALL_STARTED", next, incoming = false)
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, before))
    }

    /** A messenger call's recorder lost and brought back: its start is not
     *  joined - the recorder names no messenger file so - and still the
     *  call's own; its first part stays with it (review round 17). */
    @Test fun aMessengerCallsRevivedRecorderKeepsItsFirstPart() {
        val first = recfile(start, false)
        val revived = recfile(start + 60_000, false)
        fakeWatch(recorderUp = { false }, callUp = { true })
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        wake("CALL_STARTED", revived, incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, revived))
    }

    /** ...but in Ask first it asks again: a messenger's own call waiting
     *  cannot be told from it, and Record carried over would record a call
     *  nobody was asked about. The first part still stays (review round
     *  18). */
    @Test fun aMessengerCallsRevivedRecorderAsksAgainAndKeepsItsFirstPart() {
        val first = recfile(start, false)
        fakeWatch(recorderUp = { false }, callUp = { true })
        wake("CALL_STARTED", "", incoming = false) { putExtra("app", "org.telegram.messenger") }
        withRecord(first) {
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $first")
        }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        wake("CALL_STARTED", "", incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertNotNull("asked again", prompt())
        val second = recfile(start + 60_000, false)
        withRecord(second) {
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $second")
        }
        eventually("the parts noted") { com.jemcik.jemrec.recordings.CallParts.contains(context, second) }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** Only the revived recorder's own start: a messenger call taken as a
     *  phone call hung up, the revive landing in it, is a call of its own -
     *  the phone call's file is no part of it (review round 18). */
    @Test fun aMessengerCallAfterALostPhoneCallsRecorderIsACallOfItsOwn() {
        val phone = recfile(start, true)
        val messenger = recfile(start + 60_000, false)
        fakeWatch(recorderUp = { false }, callUp = { true })
        wake("CALL_STARTED", phone, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        wake("CALL_STARTED", messenger, incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.groups(context).none { phone in it && messenger in it })
        // What the lost recorder left of it: a part, alone (review round 20).
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, phone))
        assertEquals(setOf(messenger), CallMonitorService.heldNow)
    }

    /** ...nor any start after the revived one: its end lost, a redial is a
     *  call of its own (review round 18). */
    @Test fun aStartAfterTheRevivedOneIsACallOfItsOwn() {
        val first = recfile(start, false)
        val revived = recfile(start + 60_000, false)
        val redial = recfile(start + 120_000, false)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        // Up, as a recorder that sends a start is: no loss said after it.
        up = true
        settle()
        wake("CALL_STARTED", revived, incoming = false) { putExtra("app", "org.telegram.messenger") }
        wake("CALL_STARTED", redial, incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertEquals(setOf(redial), CallMonitorService.heldNow)
    }

    /** A message is a call of its own: files a call before left - its end
     *  never heard - are not the message's parts (review round 17). */
    @Test fun aMessagesStartIsNoPartOfTheCallBefore() {
        val before = recfile(start, true)
        val other = recfile(start + 30_000, true)
        wake("CALL_STARTED", before, incoming = true)
        wake("CALL_STARTED", other, incoming = true) { putExtra("joined", true) }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, before))
        wake("MESSAGE_STARTED", incoming = true)
        assertTrue(CallMonitorService.heldNow.isEmpty())
    }

    /** A phone call joined over a messenger call whose recorder was lost -
     *  a call rung in during it - is a call of its own: not grouped with
     *  the messenger call, and not recorded on its Record (review round
     *  19). */
    @Test fun aJoinedPhoneStartOverALostMessengerCallIsACallOfItsOwn() {
        val messenger = recfile(start, false)
        val phone = "jemrec_rec_${start + 60_000}_joined_out.dat"
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", messenger, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("CALL_STARTED", phone, incoming = false) { putExtra("joined", true) }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.groups(context).none { phone in it && messenger in it })
        assertEquals(setOf(phone), CallMonitorService.heldNow)
    }

    /** ...and in Ask first, the messenger call's Record does not record it. */
    @Test fun aJoinedPhoneStartOverALostMessengerCallIsAskedAbout() {
        val messenger = recfile(start, false)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", "", incoming = false) { putExtra("app", "org.telegram.messenger") }
        withRecord(messenger) {
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $messenger")
        }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertNotNull("asked", prompt())
        assertFalse(logged("monitor: the recorder joined the call it was told to record"))
    }

    /** The audio mode back to normal with the recorder lost: the call is
     *  over then - the revive, landing in the next call, had that call's
     *  start taken for this one's (review round 19). */
    @Test fun theModeBackToNormalWithTheRecorderLostEndsTheCall() {
        val first = recfile(start, false)
        val next = recfile(start + 60_000, false)
        var up = false
        // The watch sees the call still: it is in a revive, its looks held.
        fakeWatch(recorderUp = { up }, callUp = { true })
        CallMonitorService.recorderUpNow = { up }
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        context.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_NORMAL
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        awaitTrue("the call over") { logs().contains(outAndOver) }
        // What the lost recorder left: a part, alone - no short call (review
        // round 20).
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
        // The next call's start, the revive landing in it: a call of its own.
        context.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        up = true
        wake("CALL_STARTED", next, incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertEquals(setOf(next), CallMonitorService.heldNow)
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, next))
    }

    /** ...not once the revived recorder has said the call: it says the end
     *  too, and the call's parts are saved together then. */
    @Test fun theModeBackToNormalAfterTheReviveLeavesTheEndToTheRecorder() {
        val first = recfile(start, false)
        val revived = recfile(start + 60_000, false)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("CALL_STARTED", revived, incoming = false) { putExtra("app", "org.telegram.messenger") }
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        // Said, so the release check reads the service's decision (review
        // round 25: neither line came).
        assertTrue(logged("monitor: out of a call, the recorder back in it; its own end ends it"))
        Thread.sleep(2_000)
        assertFalse(logged(outAndOver))
        assertEquals(setOf(first, revived), CallMonitorService.heldNow)
    }

    /** A ring over a call on hold, the recorder lost: the held call is
     *  still up - no end, and a later revived start in it is its own
     *  (review round 25; AOSP OtherFocusState -> RingingFocusState). */
    @Test fun aRingOverAHeldCallIsNoEnd() {
        val first = recfile(start, true)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        CallMonitorService.recorderUpNow = { false }
        LiveCalls.held = { true }
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_RINGTONE)
        Thread.sleep(1_000)
        assertFalse(logged(outAndOver))
        up = true
        settle()
        val revived = "jemrec_rec_${System.currentTimeMillis() + 1_000}_joined_out.dat"
        wake("CALL_STARTED", revived, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(first, revived), CallMonitorService.heldNow)
    }

    /** ...and the watch's own looks keep it too, a ring over a call on hold
     *  counted as the call - two looks into the ring it was ended - with no
     *  revive tried under the ring, which would split the call (review
     *  round 26). */
    @Test fun theWatchKeepsAHeldCallThroughARing() {
        val first = recfile(start, true)
        var mode = android.media.AudioManager.MODE_IN_CALL
        var revives = 0
        fakeWatch(
            recorderUp = { false },
            callUp = { mode == android.media.AudioManager.MODE_IN_CALL },
            anyCall = { mode != android.media.AudioManager.MODE_NORMAL },
            revive = { revives++; false },
        )
        CallMonitorService.recorderUpNow = { false }
        LiveCalls.held = { true }
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        mode = android.media.AudioManager.MODE_RINGTONE
        context.getSystemService(android.media.AudioManager::class.java).mode = mode
        modeListener!!.onModeChanged(mode)
        Thread.sleep(100)
        val before = revives
        Thread.sleep(500)
        assertEquals(setOf(first), CallMonitorService.heldNow)
        assertFalse(stopped)
        // Not said over and refused, again and again: the ring is the call.
        assertEquals(0, overs)
        assertTrue("no revive under the ring", revives <= before + 1)
    }

    /** ...and keeps its Ask first answer: Record said, a ring over the held
     *  call, a revive later - recorded on, not asked again (review round
     *  26). */
    @Test fun aRingOverAHeldCallKeepsItsAnswer() {
        val first = recfile(start, true)
        var up = true
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", "", incoming = true)
        withRecord(first) {
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $first")
        }
        LiveCalls.held = { true }
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_RINGTONE)
        up = false
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        val second = recfile(start + 60_000, true)
        withRecord(second) {
            wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
            awaitLog("monitor: the recorder joined the call it was told to record; recording the rest")
            awaitLog("monitor: on-demand recording started -> $second")
        }
    }

    /** ...but not a Not now: the recorder asks at a waiting call's
     *  off-hook with nothing recording, and the call answered over the held
     *  one, revived into, is asked about (review round 27). */
    @Test fun aNotNowIsNotKeptThroughARingOverAHeldCall() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Not now"))
        assertNull(prompt())
        LiveCalls.held = { true }
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_RINGTONE)
        // A recorder brought back in the call answered over the held one.
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertFalse(logged("the recorder joined a call the user said not to record"))
        assertNotNull("asked", prompt())
    }

    /** A recorder lost under a ring the watch takes for the call - a phone
     *  call ringing over a messenger call: no revive under it, but the loss
     *  is said, and what the recorder left is a part once the call ends
     *  (review round 27: unsaid, it was saved as a whole call). */
    @Test fun aLossUnderARingIsSaid() {
        val first = recfile(start, false)
        var mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        var revives = 0
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        audio.mode = mode
        fakeWatch(
            recorderUp = { false },
            callUp = { mode == android.media.AudioManager.MODE_IN_COMMUNICATION },
            anyCall = { mode != android.media.AudioManager.MODE_NORMAL },
            revive = { revives++; false },
        )
        CallMonitorService.recorderUpNow = { false }
        mode = android.media.AudioManager.MODE_RINGTONE
        audio.mode = mode
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        assertEquals(0, revives)
        mode = android.media.AudioManager.MODE_NORMAL
        audio.mode = mode
        modeListener!!.onModeChanged(mode)
        // Ended - by the mode, or the fake watch's quick looks first - and
        // what it left a part either way, the loss marked.
        awaitTrue("the call over, its file a part") { com.jemcik.jemrec.recordings.CallParts.contains(context, first) }
    }

    /** ...nor once the recorder is back, its start not heard yet - an am
     *  can take seconds: it is in this call, and says its end (review
     *  round 20). */
    @Test fun theModeBackToNormalWithTheRecorderBackLeavesTheEndToIt() {
        val first = recfile(start, true)
        val revived = "jemrec_rec_${start + 60_000}_joined_in.dat"
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        CallMonitorService.recorderUpNow = { true }
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        Thread.sleep(2_000)
        assertFalse(logged(outAndOver))
        // Its start, late: the same call.
        wake("CALL_STARTED", revived, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(first, revived), CallMonitorService.heldNow)
    }

    /** ...nor a moment out of the call: an app switching its route flips
     *  the mode (review round 20; the recorder's own watch waits too). */
    @Test fun aMomentOutOfTheCallIsNoEnd() {
        val first = recfile(start, false)
        fakeWatch(recorderUp = { false }, callUp = { true })
        CallMonitorService.recorderUpNow = { false }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        audio.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        audio.mode = android.media.AudioManager.MODE_NORMAL
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        Thread.sleep(300)
        audio.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_IN_COMMUNICATION)
        Thread.sleep(2_000)
        assertFalse(logged(outAndOver))
    }

    /** A phone call's ring with the recorder lost: the call up hung up, a
     *  waiting call ringing - never normal between (AOSP
     *  CallAudioModeStateMachine): the call is over, and the waiting call's
     *  joined start is a call of its own (review round 20). */
    @Test fun aRingAfterAPhoneCallWithTheRecorderLostEndsIt() {
        val first = recfile(start, true)
        val waiting = "jemrec_rec_${start + 60_000}_joined_out.dat"
        fakeWatch(recorderUp = { false }, callUp = { true })
        CallMonitorService.recorderUpNow = { false }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        audio.mode = android.media.AudioManager.MODE_RINGTONE
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_RINGTONE)
        awaitTrue("the call over") { logs().contains(outAndOver) }
        audio.mode = android.media.AudioManager.MODE_IN_CALL
        wake("CALL_STARTED", waiting, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(waiting), CallMonitorService.heldNow)
    }

    /** Not a messenger call's: Telecom knows nothing of it, and a phone
     *  call ringing over it sets the same mode. */
    @Test fun aRingOverAMessengerCallIsNoEnd() {
        val first = recfile(start, false)
        fakeWatch(recorderUp = { false }, callUp = { true })
        CallMonitorService.recorderUpNow = { false }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        audio.mode = android.media.AudioManager.MODE_RINGTONE
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_RINGTONE)
        Thread.sleep(2_000)
        assertFalse(logged(outAndOver))
    }

    /** A phone call's waiting call answered at once - End and answer - as
     *  the recorder is asked: the call is over all the same, the mode it
     *  left; the waiting call's joined start is a call of its own (review
     *  round 21: the mode's re-check took the next call for this one). */
    @Test fun aWaitingCallAnsweredAtOnceIsStillTheNextCall() {
        val first = recfile(start, true)
        val waiting = "jemrec_rec_${start + 60_000}_joined_out.dat"
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        CallMonitorService.recorderUpNow = { kotlinx.coroutines.delay(500); false }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        audio.mode = android.media.AudioManager.MODE_RINGTONE
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_RINGTONE)
        audio.mode = android.media.AudioManager.MODE_IN_CALL
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_IN_CALL)
        awaitTrue("the call over") { logs().contains(outAndOver) }
        up = true
        settle()
        wake("CALL_STARTED", waiting, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(waiting), CallMonitorService.heldNow)
    }

    /** A messenger's mode out and back twice, no stretch out 1.5 s long: a
     *  route switched twice, no end (review round 21: the mode was read
     *  only at the two ends of the wait). */
    @Test fun aMessengersTwoFlipsAreNoEnd() {
        val first = recfile(start, false)
        fakeWatch(recorderUp = { false }, callUp = { true })
        CallMonitorService.recorderUpNow = { false }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        fun mode(m: Int) {
            audio.mode = m
            modeListener!!.onModeChanged(m)
        }
        mode(android.media.AudioManager.MODE_NORMAL)
        Thread.sleep(300)
        mode(android.media.AudioManager.MODE_IN_COMMUNICATION)
        Thread.sleep(900)
        mode(android.media.AudioManager.MODE_NORMAL)
        Thread.sleep(500)
        mode(android.media.AudioManager.MODE_IN_COMMUNICATION)
        Thread.sleep(2_000)
        assertFalse(logged(outAndOver))
    }

    /** The end with no file of its own - Ask first asked again after the
     *  revive, or its start failed - and the part a lost recorder left: a
     *  part, alone, no short call (review round 21). */
    @Test fun aLostRecordersPartAtAnEndWithNoFileIsAPart() {
        val first = recfile(start, true)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("CALL_ENDED", "", incoming = true)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** ...and at a message's start, the call before's end never heard. */
    @Test fun aLostRecordersPartBeforeAMessageIsAPart() {
        val first = recfile(start, true)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("MESSAGE_STARTED", incoming = true)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** The loss is the call's it happened in: the next call's whole file,
     *  its own end lost, is no part (review round 21). */
    @Test fun aLossEndsWithItsCall() {
        val first = recfile(start, true)
        val messenger = recfile(start + 60_000, false)
        val next = recfile(start + 120_000, false)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("CALL_STARTED", messenger, incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
        wake("CALL_STARTED", next, incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, messenger))
    }

    /** The watch's revive brought the recorder back - in this call, its
     *  start on its way - before the mode left: no end made here, though one
     *  ping says no (review round 22: a revive landing during a long ask
     *  was taken for one in this call). */
    @Test fun aRecorderTheWatchBroughtBackKeepsTheCall() {
        val first = recfile(start, true)
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true }, revive = { up = true; true })
        CallMonitorService.recorderUpNow = { false }
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the watch's revive back") { backs > 0 }
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        Thread.sleep(1_000)
        assertFalse(logged(outAndOver))
        assertEquals(setOf(first), CallMonitorService.heldNow)
    }

    /** Back once, then lost again and not back: the second loss is not
     *  the first's revive - the mode leaving ends the call (review round
     *  23: the flag's reset at each loss had no test). */
    @Test fun aSecondLossIsNotTheFirstsRevive() {
        val first = recfile(start, true)
        val revived = "jemrec_rec_${start + 60_000}_joined_in.dat"
        var up = false
        var tries = 0
        fakeWatch(recorderUp = { up }, callUp = { true }, revive = { (++tries == 1).also { if (it) up = true } })
        CallMonitorService.recorderUpNow = { false }
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the watch's revive back") { backs > 0 }
        wake("CALL_STARTED", revived, incoming = false) { putExtra("joined", true) }
        up = false
        awaitTrue("the second loss said") { ongoing().title() == "Recording stopped" && tries >= 2 }
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        awaitTrue("the call over") { logs().contains(outAndOver) }
    }

    /** The recorder lost unnoticed just before the hang-up, found only
     *  after the phone call's mode left: the redial's joined start is the
     *  next call's, and what the lost recorder left is a part, alone
     *  (review round 24). */
    @Test fun aLossFoundAfterThePhoneCallLeftIsNotTheRedials() {
        val first = recfile(start, true)
        var up = true
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = true)
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        // Begun after the hang-up, as a redial's file is.
        val redial = "jemrec_rec_${System.currentTimeMillis() + 1_000}_joined_out.dat"
        up = false
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("CALL_STARTED", redial, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(redial), CallMonitorService.heldNow)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
        assertTrue(com.jemcik.jemrec.recordings.CallParts.groups(context).none { first in it && redial in it })
    }

    /** The recorder lost unseen before the hang-up and brought back by
     *  another path - the Wi-Fi revive at the hang-up - in the redial: no
     *  look found the loss, and its joined start is the next call's - what
     *  the call before left is a part all the same: a joined start is a
     *  recorder started with a call up (review round 30: saved as a whole
     *  call, the 17 AVD's redial pass). */
    @Test fun aJoinedNextStartMakesTheCallBeforesFileAPart() {
        val first = recfile(start, true)
        fakeWatch(recorderUp = { true }, callUp = { true })
        wake("CALL_STARTED", first, incoming = true)
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        val redial = "jemrec_rec_${System.currentTimeMillis() + 1_000}_joined_out.dat"
        wake("CALL_STARTED", redial, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(redial), CallMonitorService.heldNow)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** ...and brought back between the calls, the redial's start a fresh
     *  one, not joined: a recorder started since the call before's start
     *  (DaemonLauncher.launchedAtMs) says its recorder is gone - and with
     *  none started, the call before is whole, its end's wake lost (round
     *  16's rule). */
    @Test fun aRecorderStartedSinceMakesTheCallBeforesFileAPart() {
        val first = recfile(start, true)
        val second = recfile(start + 60_000, false)
        wake("CALL_STARTED", first, incoming = true)
        wake("CALL_STARTED", second, incoming = false)
        assertFalse("none started: whole", com.jemcik.jemrec.recordings.CallParts.contains(context, first))
        Thread.sleep(5)
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = System.currentTimeMillis()
        // Begun after that start, as a revived recorder's file is.
        val third = "jemrec_rec_${System.currentTimeMillis() + 1_000}_in.dat"
        wake("CALL_STARTED", third, incoming = true)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, second))
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, third))
        // ...and the loss is that call's, not the next one's (round 21): no
        // recorder started since the third began.
        wake("CALL_STARTED", recfile(start + 240_000, false), incoming = false)
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, third))
    }

    /** A recorder started before the call's file began says nothing of it:
     *  the watch's end, a next start, a message's start leave it whole -
     *  "since" is since the file began (review round 31: any start in the
     *  process's life passed every test). */
    @Test fun aRecorderStartedBeforeTheFileLeavesTheWatchsEndWhole() {
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = start - 1
        val first = recfile(start, true)
        var callUp = true
        fakeWatch(recorderUp = { true }, callUp = { callUp })
        wake("CALL_STARTED", first, incoming = true)
        callUp = false
        awaitTrue("the call over") { stopped }
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** Timed by the file's own start, not by when its wake came: a recorder
     *  that began the call and was replaced before its start's wake was heard
     *  (an am, up to 10 s) is gone all the same (review round 31). */
    @Test fun aRecorderReplacedBeforeItsStartWasHeardLeavesAPart() {
        val first = recfile(start, true)
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = start + 1
        wake("CALL_STARTED", first, incoming = true)
        wake("CALL_STARTED", recfile(start + 60_000, false), incoming = false)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    @Test fun aRecorderStartedBeforeTheFileLeavesItWholeAtTheNextStart() {
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = start - 1
        val first = recfile(start, true)
        wake("CALL_STARTED", first, incoming = true)
        wake("CALL_STARTED", recfile(start + 60_000, false), incoming = false)
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    @Test fun aRecorderStartedBeforeTheFileLeavesItWholeAtAMessage() {
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = start - 1
        val first = recfile(start, true)
        wake("CALL_STARTED", first, incoming = true)
        wake("MESSAGE_STARTED", incoming = true)
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** ...and with no next call, the watch ends it, the recorder answering
     *  by then - started since the call's start: a part too. */
    @Test fun aRecorderStartedSinceMakesTheWatchsEndAPart() {
        val first = recfile(start, true)
        var callUp = true
        fakeWatch(recorderUp = { true }, callUp = { callUp })
        wake("CALL_STARTED", first, incoming = true)
        Thread.sleep(5)
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = System.currentTimeMillis()
        callUp = false
        awaitTrue("the call over") { stopped }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** ...and a message's start after it, the call before's end never
     *  heard: its file a part too, as with a loss the watch saw (round 21's
     *  rule; review round 30). */
    @Test fun aMessageAfterARecorderStartedSinceMakesTheCallBeforesFileAPart() {
        val first = recfile(start, true)
        wake("CALL_STARTED", first, incoming = true)
        Thread.sleep(5)
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = System.currentTimeMillis()
        wake("MESSAGE_STARTED", incoming = true)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** A recorder brought back unseen that joined the call in Ask first,
     *  its question not answered: the end names no file, and the one the
     *  call's first recorder left is a part, no whole call (review round
     *  30; round 21's rule held only for a loss the watch saw). */
    @Test fun anEndWithNoFileOfItsOwnLeavesTheFileBeforeAPart() {
        val first = recfile(start, true)
        wake("CALL_STARTED", first, incoming = true)
        // The recorder that joined, started since the first file began.
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = System.currentTimeMillis()
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        wake("CALL_ENDED", "", incoming = true)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** ...but the end of a call whose start never came - left out by the
     *  rules, or its recording would not start - names no file either: the
     *  file held is the call before's, whole, its own end's wake lost, no
     *  part (review round 31). */
    @Test fun anEndWithNoFileAndNoRecorderSinceLeavesTheFileBeforeWhole() {
        val first = recfile(start, true)
        wake("CALL_STARTED", first, incoming = true)
        wake("CALL_ENDED", "", incoming = true)
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** A recorder replaced unseen while the machine takes a message - its
     *  successor's start joined, or from a recorder started since the
     *  message began: the message is lost as a loss the watch finds is -
     *  noted for the reconcile, its call hung up - not dropped as Ask
     *  first's start after a takeover (review round 31: the caller talked
     *  into a muted call nothing ended, the message saved as a call). */
    @Test fun aJoinedStartDuringAMessageTakesTheMessageAsLost() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        wake("CALL_STARTED", "jemrec_rec_${System.currentTimeMillis()}_joined_out.dat", incoming = false) {
            putExtra("joined", true)
        }
        awaitTrue("the message's call hung up") { ended == listOf(42L) }
        // The message's own, a test's - and the replacing recorder's file
        // noted as the test's other (review round 33).
        assertTrue(com.jemcik.jemrec.recordings.LostMessages.all(context).single { it.counted }.test)
        // Its file collected after the call: a kick owed (review round 32).
        wake("CALL_ENDED", "", incoming = false)
        awaitLog("monitor: kicking the keep-alive for what a reconcile left")
    }

    /** The message's call over unseen, its recorder with it, and the next
     *  call's start from a recorder started since - a fresh start, another
     *  call up: the message is lost, a kick owed, and the call up - not the
     *  message's - left alone (review round 32: round 31's test had a fresh
     *  start with the message's call still up, which a recorder started then
     *  never sends - it says joined). */
    @Test fun aStartFromARecorderStartedSinceTheMessageTakesItAsLost() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("MESSAGE_STARTED", incoming = true)
        Thread.sleep(5)
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = System.currentTimeMillis()
        LiveCalls.calls = { listOf(MessageHangUp.Up(43, true)) }
        // The next call's own file: not the message's - noted for nothing.
        wake("CALL_STARTED", recfile(System.currentTimeMillis(), true), incoming = true)
        assertEquals(1, com.jemcik.jemrec.recordings.LostMessages.all(context).size)
        settle()
        assertTrue("the next call left up", ended.isEmpty())
        wake("CALL_ENDED", "", incoming = true)
        awaitLog("monitor: kicking the keep-alive for what a reconcile left")
    }

    /** A joined start outside a message - a revive in a call - is no
     *  message's loss: nothing noted, nothing hung up, no kick owed for it
     *  (review round 32: the rule's message guard was pinned by nothing). */
    @Test fun aJoinedStartOutsideAMessageLosesNoMessage() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("CALL_STARTED", "", incoming = true)
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        wake("CALL_ENDED", "", incoming = false)
        eventually("the service stands down") { stopped }
        settle()
        assertTrue(com.jemcik.jemrec.recordings.LostMessages.all(context).isEmpty())
        assertTrue(ended.isEmpty())
        assertFalse(logged("monitor: kicking the keep-alive for what a reconcile left"))
    }

    /** The file the recorder after it began in the message's call before the
     *  hang-up is the message's too: a caller's last words a part, no short
     *  call - a test call's dropped at the end, not kept as the user's
     *  (review round 32). */
    @Test fun aLostMessagesWindowEndsBeforeTheReplacementsFile() {
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("MESSAGE_STARTED", incoming = true)
        // Begun before this message's wake was heard, as a late wake has it.
        val bAt = System.currentTimeMillis() - 2_000
        wake("CALL_STARTED", "jemrec_rec_${bAt}_joined_out.dat", incoming = false) { putExtra("joined", true) }
        val lost = com.jemcik.jemrec.recordings.LostMessages.all(context).single()
        assertTrue("the message's window ends before the replacing recorder's file", lost.greetingAt < bAt)
        assertFalse(lost.test)
    }

    @Test fun aLostTestMessagesReplacementFileIsNotedAsTheTests() {
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        val bAt = System.currentTimeMillis()
        wake("CALL_STARTED", "jemrec_rec_${bAt}_joined_out.dat", incoming = false) { putExtra("joined", true) }
        assertTrue(com.jemcik.jemrec.recordings.LostMessages.all(context).contains(com.jemcik.jemrec.recordings.LostMessages.Lost(bAt, bAt, test = true, counted = false)))
    }

    /** ...with exact call tracking off too - no call of Telecom's to hang up:
     *  joined into the message's call, the microphone still muted, nobody
     *  has it (review round 33: the drop waited on a hang-up, never made
     *  with tracking off). */
    @Test fun aLostTestMessagesReplacementFileIsTheTestsWithTrackingOff() {
        LiveCalls.calls = { emptyList() }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        val bAt = System.currentTimeMillis()
        wake("CALL_STARTED", "jemrec_rec_${bAt}_joined_out.dat", incoming = false) { putExtra("joined", true) }
        assertTrue(com.jemcik.jemrec.recordings.LostMessages.all(context).contains(com.jemcik.jemrec.recordings.LostMessages.Lost(bAt, bAt, test = true, counted = false)))
    }

    /** ...unless someone has the call - the microphone given back: theirs,
     *  left up and as it is. */
    @Test fun aLostMessagesCallSomeoneTookKeepsItsFile() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = false
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        val b = "jemrec_rec_${System.currentTimeMillis()}_joined_out.dat"
        wake("CALL_STARTED", b, incoming = false) { putExtra("joined", true) }
        assertEquals("only the message's own", 1, com.jemcik.jemrec.recordings.LostMessages.all(context).size)
        assertTrue(ended.isEmpty())
    }

    /** The replacing recorder's joined start ahead of the message's own wake
     *  (both an am, up to 10 s): the recorder that answered is gone already,
     *  and nobody takes the message - lost, its call hung up, no message
     *  taken up (review round 32: a ticket was set for a message nobody
     *  took, the call left muted). */
    @Test fun aJoinedStartAheadOfTheMessagesWakeLosesIt() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(start, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        val bAt = System.currentTimeMillis()
        val b = "jemrec_rec_${bAt}_joined_in.dat"
        wake("CALL_STARTED", b, incoming = false) { putExtra("joined", true) }
        wake("MESSAGE_STARTED", incoming = true)
        awaitTrue("the message's call hung up") { ended == listOf(start) }
        // Its window ends before the joined file, which is not the message.
        assertTrue(com.jemcik.jemrec.recordings.LostMessages.all(context).single().greetingAt < bAt)
        assertNotEquals("Taking a message", ongoing().title())
        assertEquals(setOf(b), CallMonitorService.heldNow)
    }

    /** ...but a fresh start - not joined: the next call's, the message's
     *  over - is not the test's, the stale mute notwithstanding (the
     *  recorder leaves it while a call is up): its file is that call's
     *  (review round 33). */
    @Test fun aNextCallsFileIsNotTheTestsWithTrackingOff() {
        LiveCalls.calls = { emptyList() }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        Thread.sleep(5)
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = System.currentTimeMillis()
        val nextAt = System.currentTimeMillis()
        wake("CALL_STARTED", recfile(nextAt, true), incoming = true)
        assertEquals("only the message's own", 1, com.jemcik.jemrec.recordings.LostMessages.all(context).size)
    }

    /** A joined start whose file began before the message's call - the call
     *  before's, its wake late, its end lost - is no sign the recorder that
     *  answered is gone: the message is taken up (review round 33: timed by
     *  the wake's arrival, a healthy machine was hung up mid-greeting). */
    @Test fun aJoinedFileOfTheCallBeforeLosesNoMessage() {
        val ended = mutableListOf<Long>()
        val created = System.currentTimeMillis()
        LiveCalls.calls = { listOf(MessageHangUp.Up(created, true)) }
        LiveCalls.end = { ended += it; true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("CALL_STARTED", "jemrec_rec_${created - 30_000}_joined_out.dat", incoming = false) { putExtra("joined", true) }
        wake("MESSAGE_STARTED", incoming = true)
        assertEquals("Taking a message", ongoing().title())
        assertTrue(com.jemcik.jemrec.recordings.LostMessages.all(context).isEmpty())
        assertTrue(ended.isEmpty())
    }

    /** ...and in Ask first, the prompt the joined start put up goes with the
     *  call being hung up (review round 33). */
    @Test fun aJoinedStartAheadOfTheMessagesWakeTakesItsPromptDown() {
        LiveCalls.calls = { listOf(MessageHangUp.Up(start, true)) }
        LiveCalls.end = { true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertNotNull(prompt())
        wake("MESSAGE_STARTED", incoming = true)
        assertNull(prompt())
    }

    /** A message is a call of its own: Record said to the call before - its
     *  end lost - does not record the message's call when a joined start goes
     *  into it (review round 33). */
    @Test fun aRecordAnswerIsNotTheMessages() {
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Record"))
        wake("MESSAGE_STARTED", incoming = true)
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertFalse(logged("monitor: the recorder joined the call it was told to record"))
    }

    /** In Ask first, the joined start into a message's call being hung up
     *  asks nothing (review round 32: a heads-up for a call already ending). */
    @Test fun aJoinedStartIntoAMessageBeingHungUpAsksNothing() {
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { true }
        context.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute = true
        wake("MESSAGE_STARTED", incoming = true)
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertNull(prompt())
    }

    /** ...and Ask first's start after a takeover, from the recorder that took
     *  the message, is no loss. */
    @Test fun aTakeoversStartIsNoLossOfTheMessage() {
        val ended = mutableListOf<Long>()
        LiveCalls.calls = { listOf(MessageHangUp.Up(42, true)) }
        LiveCalls.end = { ended += it; true }
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = start - 1
        wake("MESSAGE_STARTED", incoming = true)
        wake("CALL_STARTED", "", incoming = true)
        assertTrue(ended.isEmpty())
        assertTrue(com.jemcik.jemrec.recordings.LostMessages.all(context).isEmpty())
    }

    /** A message in a service of its own, a recorder started earlier in the
     *  process's life: nothing held, nothing cut - no kick owed (review
     *  round 31: every message ended with one). */
    @Test fun aMessageInAFreshServiceOwesNoKick() {
        com.jemcik.jemrec.recorder.DaemonLauncher.launchedAtMs = System.currentTimeMillis()
        Thread.sleep(5)
        wake("MESSAGE_STARTED", incoming = true)
        wake("CALL_ENDED", "", incoming = true)
        eventually("the service stands down") { stopped }
        settle()
        assertFalse(logged("monitor: kicking the keep-alive for what a reconcile left"))
    }

    /** Telecom's last call gone (exact call tracking on), no normal mode
     *  heard - a redial within the end-of-call tone - and the loss found in
     *  the redial: its joined start is the next call's (review round 29). */
    @Test fun telecomsEmptyListEndsThePhoneCallForTheNextStart() {
        val first = recfile(start, true)
        var up = true
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = true)
        Thread.sleep(5)
        CallMonitorService.telecomOutAtMs = System.currentTimeMillis()
        up = false
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        val redial = "jemrec_rec_${System.currentTimeMillis() + 1_000}_joined_out.dat"
        wake("CALL_STARTED", redial, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(redial), CallMonitorService.heldNow)
    }

    /** A call taken up by Record's answer, no start counted: the call
     *  before's mode left is not this call's - its revive joins it (review
     *  round 24). */
    @Test fun aCallTakenUpByRecordKeepsItsRevive() {
        val before = recfile(start, true)
        val asked = recfile(start + 120_000, true)
        val revived = "jemrec_rec_${start + 180_000}_joined_out.dat"
        var up = true
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", before, incoming = true)
        modeListener!!.onModeChanged(android.media.AudioManager.MODE_NORMAL)
        wake("CALL_ENDED", before, incoming = true)
        awaitLog("monitor: recording handled")
        withRecord(asked) {
            RecordPrompt.show(context, incoming = true)
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $asked")
        }
        eventually("its file held") { asked in CallMonitorService.heldNow }
        up = false
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        wake("CALL_STARTED", revived, incoming = false) { putExtra("joined", true) }
        assertEquals(setOf(asked, revived), CallMonitorService.heldNow)
    }

    /** A reconcile owed - left for a call up, in a service since gone - is
     *  kicked for at the next call's end, in a service of its own (review
     *  round 25: the owed kick was this instance's, and went with it). */
    @Test fun aReconcileOwedIsKickedForAtTheNextCallsEnd() {
        com.jemcik.jemrec.recordings.RecordingSaver.reconcileOwed = true
        wake("CALL_STARTED", "", incoming = true)
        wake("CALL_ENDED", "", incoming = true)
        awaitLog("monitor: kicking the keep-alive for what a reconcile left")
    }

    /** The recorder ended in the call's last seconds, its loss never seen,
     *  the watch ending the call with it down: what it left is a part, no
     *  whole call - the short-call rule spares it (review round 29; the 17
     *  AVD's redial pass, slower, ended the call this way). */
    @Test fun aCallEndedWithTheRecorderDownLeavesAPart() {
        val first = recfile(start, true)
        var up = true
        var callUp = true
        fakeWatch(recorderUp = { up }, callUp = { callUp })
        wake("CALL_STARTED", first, incoming = true)
        // Down and over at the same look: no loss said first.
        callUp = false
        up = false
        awaitTrue("the call over") { stopped }
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** ...and with it answering throughout, the watch's end leaves the file
     *  whole: the end's wake gone astray, not the recorder (review round
     *  30). */
    @Test fun aCallEndedWithTheRecorderUpLeavesItWhole() {
        val first = recfile(start, true)
        var callUp = true
        fakeWatch(recorderUp = { true }, callUp = { callUp })
        wake("CALL_STARTED", first, incoming = true)
        callUp = false
        awaitTrue("the call over") { stopped }
        assertEquals(1, overs)
        assertFalse(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** A message, the recorder found down only as the watch ends its call
     *  - killed in its last seconds: its file is a message all the same,
     *  noted as a loss found mid-message notes it; unnoted, it was saved as
     *  an ordinary call, a test call's kept (review round 30). */
    @Test fun aMessageEndedWithTheRecorderDownIsNotedLost() {
        var up = true
        var callUp = true
        fakeWatch(recorderUp = { up }, callUp = { callUp })
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        callUp = false
        up = false
        awaitTrue("the call over") { stopped }
        val lost = com.jemcik.jemrec.recordings.LostMessages.all(context)
        assertEquals(1, lost.size)
        assertTrue(lost.single().test)
    }

    /** A messenger's mode out and back, then a phone call ringing over it
     *  within the wait: the messenger call goes on under the ring - its
     *  wait's end read the ring's mode as out (review round 22). */
    @Test fun aRingAfterAMessengersFlipIsNoEnd() {
        val first = recfile(start, false)
        fakeWatch(recorderUp = { false }, callUp = { true })
        CallMonitorService.recorderUpNow = { false }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        wake("CALL_STARTED", first, incoming = false) { putExtra("app", "org.telegram.messenger") }
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        fun mode(m: Int) {
            audio.mode = m
            modeListener!!.onModeChanged(m)
        }
        mode(android.media.AudioManager.MODE_NORMAL)
        Thread.sleep(300)
        mode(android.media.AudioManager.MODE_IN_COMMUNICATION)
        Thread.sleep(300)
        mode(android.media.AudioManager.MODE_RINGTONE)
        Thread.sleep(2_000)
        assertFalse(logged(outAndOver))
    }

    /** Switched off after the loss: the end's own file dropped, the part
     *  the lost recorder left is a part all the same (review round 22). */
    @Test fun aLostRecordersPartIsAPartWhenTheEndsOwnIsDropped() {
        val first = recfile(start, true)
        val joined = "jemrec_rec_${start + 60_000}_joined_in.dat"
        var up = false
        fakeWatch(recorderUp = { up }, callUp = { true })
        wake("CALL_STARTED", first, incoming = true)
        awaitTrue("the loss said") { ongoing().title() == "Recording stopped" }
        up = true
        settle()
        RecorderSwitch.set(context, false)
        wake("CALL_ENDED", joined, incoming = true)
        assertTrue(com.jemcik.jemrec.recordings.CallParts.contains(context, first))
    }

    /** The end's own file, renamed at the end (_early): not saved again
     *  under the name it began with, to find it gone (review round 12). */
    @Test fun theEndsOwnFileRenamedIsNotSavedTwice() {
        val first = recfile(start, true)
        wake("CALL_STARTED", first, incoming = true)
        val renamed = recfile(start, true, tag = "_early5000")
        wake("CALL_ENDED", renamed, incoming = true)
        awaitTrue("the end's file handled") { logs().any { it.startsWith("save:") && renamed in it } }
        settle()
        assertFalse(logs().joinToString("\n"), logs().any { it.startsWith("save:") && first in it })
    }

    /** [condition], waited for on the watch's own thread, the main looper run. */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "never: $what" }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    /** The mode rule's line (CallMonitorService.callMaybeOver). */
    private val outAndOver = "monitor: out of a call, the recorder still lost; the call is over"

    // ---- driving it --------------------------------------------------------

    /**
     * A wake as the recorder sends it (Wakes.wake): the service by name, the
     * action, the token, recfile ("" for none) and incoming, then the
     * action's own extras. The names are written out, not taken from the
     * service's constants: the recorder has its own copy of each.
     */
    private fun wake(
        action: String,
        recfile: String = "",
        incoming: Boolean = false,
        token: String? = CaptureDaemon.token(context),
        extras: Intent.() -> Unit = {},
    ) {
        val intent = Intent("com.jemcik.jemrec.$action")
            .setClassName(context, "com.jemcik.jemrec.capture.CallMonitorService")
            .putExtra("recfile", recfile)
            .putExtra("incoming", incoming)
        if (token != null) intent.putExtra("token", token)
        intent.extras()
        start(intent)
    }

    private fun start(intent: Intent?) {
        service.onStartCommand(intent, 0, ++startId)
    }

    /** A start from anywhere else: any app can send this exported service anything. */
    private fun stray(action: String?, ticket: String? = null) = start(
        Intent(context, CallMonitorService::class.java).setAction(action).apply {
            if (ticket != null) putExtra("ticket", ticket)
        },
    )

    private fun recfile(at: Long, incoming: Boolean, tag: String = "") =
        "jemrec_rec_${at}${tag}_${if (incoming) "in" else "out"}.dat"

    // ---- what it shows -----------------------------------------------------

    private fun ongoing(): Notification = shadowOf(service).lastForegroundNotification
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.buttons() = actions?.map { it.title.toString() }.orEmpty()

    /** IMMEDIATE or DEFERRED: the builder's choice, which Notification keeps
     *  in a field with no public getter. */
    private fun Notification.foregroundBehaviour(): Int =
        Notification::class.java.getDeclaredField("mFgsDeferBehavior").apply { isAccessible = true }.getInt(this)

    private fun prompt(): Notification? = shadowOf(manager).getNotification(3)
    private fun notRecorded(): Notification? = shadowOf(manager).getNotification(31)

    /** The intent a button of [n] sends when tapped. */
    private fun tap(n: Notification, label: String): Intent =
        shadowOf(n.actions.single { it.title.toString() == label }.actionIntent).savedIntent

    private val stopped get() = shadowOf(service).isStoppedBySelf

    private fun logs() = ShadowLog.getLogsForTag("JemRec").map { it.msg }
    private fun logged(part: String) = logs().any { part in it }

    /** Waits, up to ten seconds unless told otherwise, for what the service
     *  does on its IO scope. */
    private fun eventually(what: String, seconds: Long = 10, done: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!done()) {
            if (System.nanoTime() > deadline) fail("never happened: $what")
            Thread.sleep(10)
        }
    }

    private fun awaitLog(part: String, seconds: Long = 10) = eventually("log \"$part\"", seconds) { logged(part) }

    /** For the rest of a job whose last log line has been seen: what it
     *  does after that line (a stop, its own end) takes microseconds. */
    private fun settle() = Thread.sleep(300)

    /** A save of a call at [at] that will wait in its call-log read (see the class comment). */
    private fun heldCallLog(at: Long, incoming: Boolean): FakeCallLog {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG)
        FileNames.setFormat(context, FileNames.Format.DATE_AND_NUMBER)
        return FakeCallLog.install().apply {
            add(at, if (incoming) CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE, seconds = 30, number = "+380441234567")
            hold()
        }
    }

    // ---- the service itself ------------------------------------------------

    @Test fun itsChannelIsQuietAndThePreV2OnesAreGone() {
        listOf("jemrec.monitor", "jemrec.ask", "jemrec.pairing").forEach {
            manager.createNotificationChannel(NotificationChannel(it, "old", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val second = Robolectric.buildService(CallMonitorService::class.java).create()
        try {
            val channel = manager.getNotificationChannel("jemrec.monitor.v2")!!
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertFalse(channel.canShowBadge())
            listOf("jemrec.monitor", "jemrec.ask", "jemrec.pairing").forEach {
                assertNull("$it deleted", manager.getNotificationChannel(it))
            }
            // "Call not recorded" goes out on the problems channel, made here too.
            assertNotNull(manager.getNotificationChannel(AfterCall.PROBLEM_CHANNEL_ID))
        } finally {
            second.destroy()
        }
    }

    @Test fun runningIsTrueForTheServicesLife() {
        // DaemonLauncher.retire reads it, so as not to start the service by stopping it.
        assertTrue(CallMonitorService.running)
        controller.destroy()
        assertFalse(CallMonitorService.running)
        controller = Robolectric.buildService(CallMonitorService::class.java).create()
    }

    @Test fun everyStartPutsTheServiceInTheForegroundFirst() {
        // Every start of it is a startForegroundService, which owes this within
        // seconds - whatever it carries, and before any check.
        val starts = listOf<Pair<String, () -> Unit>>(
            "no intent" to { start(null) },
            "no action" to { stray(null) },
            "a wrong token" to { wake("CALL_STARTED", token = "forged") },
            "no token" to { wake("CALL_STARTED", token = null) },
            "Talk without a message" to { stray("com.jemcik.jemrec.TALK", ticket = "forged") },
            "Record without a prompt" to { stray("com.jemcik.jemrec.RECORD_NOW") },
            "a call" to { wake("CALL_STARTED") },
        )
        starts.forEach { (what, send) ->
            manager.cancel(1)
            send()
            assertNotNull("$what: notification 1 posted", shadowOf(manager).getNotification(1))
            assertEquals(1, shadowOf(service).lastForegroundNotificationId)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        }
    }

    // ---- the token ---------------------------------------------------------

    @Test fun aWakeWithAWrongTokenIsIgnored() {
        wake("CALL_STARTED", incoming = true, token = "forged")
        assertNull("no prompt", prompt())
        assertTrue(logged("monitor: com.jemcik.jemrec.CALL_STARTED with a bad token, ignoring"))
        // In the foreground anyway, as a call it knows nothing of; then gone.
        assertEquals("Call in progress", ongoing().title())
        assertTrue(stopped)
    }

    /** The recording stopped mid-call: said at once in the call's own,
     *  silent notification, tried again or for good; back when it goes on;
     *  and nothing of it once the call is over (review, 2026-11-18). */
    @Test fun aRecordingStoppedMidCallIsSaidUntilItGoesOn() {
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        wake("RECORDING_STOPPED", recfile(start, true), incoming = true)
        assertEquals("Recording stopped", ongoing().title())
        assertEquals("Trying again - this part of the call is not recorded",
            ongoing().extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        wake("RECORDING_BACK", recfile(start, true), incoming = true)
        assertEquals("Recording call", ongoing().title())
        wake("RECORDING_STOPPED", recfile(start, true), incoming = true) { putExtra("for_good", true) }
        assertEquals("The rest of this call is not recorded",
            ongoing().extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        // The next call starts as recorded, not as the last one stopped.
        wake("CALL_STARTED", recfile(start + 60_000, true), incoming = true)
        assertEquals("Recording call", ongoing().title())
    }

    /** One that reaches a service with no call - this process started
     *  again during the call; the recorder drops one once its call is over
     *  - is taken as the call it is for, and said (review round 9). */
    @Test fun aRecordingStoppedReachingANewServiceIsTheCallItIsFor() {
        wake("RECORDING_STOPPED", recfile(start, true), incoming = true)
        assertEquals("Recording stopped", ongoing().title())
        assertFalse(stopped)
        wake("RECORDING_BACK", recfile(start, true), incoming = true)
        assertEquals("Recording call", ongoing().title())
    }

    @Test fun aWakeWithNoTokenIsIgnored() {
        wake("CALL_STARTED", incoming = true, token = null)
        assertNull(prompt())
        assertTrue(logged("with a bad token, ignoring"))
        assertTrue(stopped)
    }

    @Test fun noWakeWithAWrongTokenChangesACallInProgress() {
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        listOf(
            "CALL_STARTED", "CALL_ENDED", "STOP_MONITOR", "MESSAGE_STARTED", "MESSAGE_TAKEN", "CABLE", "BACKGROUND_KILLED",
            "RECORDING_STOPPED", "RECORDING_BACK",
        )
            .forEach { action ->
                wake(action, recfile(start, true), incoming = true, token = "forged") {
                    putExtra("test", true)
                    putExtra("started", start)
                    putExtra("cable", "in")
                }
                assertTrue(action, logged("monitor: com.jemcik.jemrec.$action with a bad token, ignoring"))
                assertEquals(action, "Recording call", ongoing().title())
                assertEquals(action, Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
                assertTrue(action, ongoing().buttons().isEmpty())
                assertNull(action, prompt())
                assertFalse(action, stopped)
            }
        assertTrue("no test call noted", CallLedger.read(context).testCalls.isEmpty())
    }

    @Test fun aCableOrBackgroundKillWakeWithAWrongTokenSaysNothingOfIt() {
        // Only the recorder's own wakes may make it say "Checking".
        wake("CABLE", token = "forged") { putExtra("cable", "out") }
        assertEquals("Call in progress", ongoing().title())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        wake("BACKGROUND_KILLED", token = "forged")
        assertEquals("Call in progress", ongoing().title())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        assertTrue(stopped)
    }

    @Test fun aStartWithNoActionMidCallDoesNotStopIt() {
        wake("CALL_STARTED", recfile(start, false))
        stray(null)
        assertTrue(logged("monitor: started with no action"))
        assertFalse("not a STOP that skips STOP's guard", stopped)
        assertEquals("Recording call", ongoing().title())
    }

    @Test fun aStartWithNoActionWhenIdleStandsDown() {
        start(null)
        assertTrue(logged("monitor: started with no action"))
        assertTrue(stopped)
    }

    @Test fun stopWithTheTokenStopsEvenMidCall() {
        wake("CALL_STARTED", recfile(start, false))
        // As the app sends it (CallMonitorService.stop): a start, queued
        // behind any start the recorder sent, never stopService - which,
        // before a foreground start reached startForeground, ends the app.
        CallMonitorService.stop(context)
        val sent = shadowOf(context).nextStartedService
        assertEquals("com.jemcik.jemrec.STOP_MONITOR", sent.action)
        assertNull(shadowOf(context).nextStoppedService)
        start(sent)
        assertTrue(stopped)
    }

    /** Refused the start - Battery Restricted refuses one from the
     *  background even to a service that runs - the stop is stopService. */
    @Test fun aStopRefusedItsStartStopsTheServiceDirectly() {
        wake("CALL_STARTED", recfile(start, false))
        val refusing = object : android.content.ContextWrapper(context) {
            override fun startService(service: Intent): android.content.ComponentName? =
                throw IllegalStateException("Not allowed to start service")
        }
        CallMonitorService.stop(refusing)
        assertEquals(CallMonitorService::class.java.name, shadowOf(context).nextStoppedService?.component?.className)
    }

    @Test fun stopWithAWrongTokenDoesNotStopACall() {
        wake("CALL_STARTED", recfile(start, false))
        wake("STOP_MONITOR", token = "forged")
        assertFalse(stopped)
    }

    // ---- a call starts -----------------------------------------------------

    @Test fun aCallTheRecorderIsNotRecordingAsksFirst() {
        wake("CALL_STARTED", "", incoming = true)
        val asking = prompt()!!
        assertEquals("Record this call?", asking.title())
        assertEquals("Incoming call in progress.", asking.text())
        assertEquals("jemrec.ask.v2", asking.channelId)
        assertEquals(listOf("Record", "Not now"), asking.buttons())
        val n = ongoing()
        assertEquals("Not recording this call", n.title())
        assertEquals("Tap Record to record it from now on", n.text())
        assertEquals("jemrec.monitor.v2", n.channelId)
        // The question's own ticket: swiped away, it leaves Record here.
        assertEquals(listOf("Record"), n.buttons())
        assertEquals(tap(asking, "Record").getStringExtra("ticket"), tap(n, "Record").getStringExtra("ticket"))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, n.foregroundBehaviour())
        // A tap opens the app.
        assertEquals(MainActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)
        // Both halves are what the emulator checks look for.
        assertTrue(logged("monitor: off-hook (incoming), asking whether to record"))
        assertFalse(stopped)
    }

    @Test fun thePromptNamesAnOutgoingOrMessengerCall() {
        wake("CALL_STARTED", "", incoming = false)
        assertEquals("Outgoing call in progress.", prompt()!!.text())
        wake("CALL_STARTED", "", incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertEquals("Telegram call in progress.", prompt()!!.text())
        wake("CALL_STARTED", "", incoming = false) { putExtra("app", "com.example.unknown") }
        assertEquals("Messenger call in progress.", prompt()!!.text())
    }

    @Test fun aCallTheRecorderIsRecordingSaysSo() {
        wake("CALL_STARTED", recfile(start, false), incoming = false)
        assertNull("nothing to ask", prompt())
        assertEquals("Recording call", ongoing().title())
        assertEquals("Saving this call", ongoing().text())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        assertTrue(logged("monitor: off-hook (outgoing), the daemon is recording"))
        assertFalse(stopped)
    }

    // ---- the answering machine ---------------------------------------------

    @Test fun aMessageShowsTalkToThemAndHangUp() {
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", false) }
        val n = ongoing()
        assertEquals("Taking a message", n.title())
        assertEquals("Your greeting is playing; your microphone is muted", n.text())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, n.foregroundBehaviour())
        assertEquals(listOf("Talk to them", "Hang up"), n.buttons())
        val tickets = n.actions.zip(listOf("com.jemcik.jemrec.TALK", "com.jemcik.jemrec.HANG_UP")).map { (button, action) ->
            val pending = shadowOf(button.actionIntent)
            assertTrue(pending.isServiceIntent)
            assertEquals(action, pending.savedIntent.action)
            assertEquals(CallMonitorService::class.java.name, pending.savedIntent.component?.className)
            assertEquals(action.hashCode(), pending.requestCode)
            pending.savedIntent.getStringExtra("ticket")
        }
        assertFalse(tickets[0].isNullOrEmpty())
        assertEquals("one ticket for both buttons", tickets[0], tickets[1])
        assertFalse(stopped)
    }

    @Test fun aTestCallOfTheMachineSaysTesting() {
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        assertEquals("Testing the answering machine", ongoing().title())
        assertEquals("Your greeting is playing to the caller", ongoing().text())
        assertEquals(listOf("Talk to them", "Hang up"), ongoing().buttons())
    }

    @Test fun eachMessageHasANewTicket() {
        wake("MESSAGE_STARTED", incoming = true)
        // Read now: FLAG_UPDATE_CURRENT puts the next ticket into the same
        // PendingIntent, so the last notification's buttons carry it too.
        val old = tap(ongoing(), "Talk to them").getStringExtra("ticket")
        wake("MESSAGE_STARTED", incoming = true)
        assertNotEquals(old, tap(ongoing(), "Talk to them").getStringExtra("ticket"))
        stray("com.jemcik.jemrec.TALK", ticket = old)
        assertTrue(logged("monitor: com.jemcik.jemrec.TALK without this message's ticket, ignoring"))
        assertEquals("Taking a message", ongoing().title())
    }

    @Test fun theMessagesTitleComesBeforeTheRecordings() {
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        wake("MESSAGE_STARTED", incoming = true)
        assertEquals("Taking a message", ongoing().title())
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        assertEquals("Testing the answering machine", ongoing().title())
    }

    @Test fun aCallStartAfterAMessageEndsItAndAsks() {
        // Ask first, after a takeover: the message is over, the prompt asks
        // about the rest.
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        val talk = tap(ongoing(), "Talk to them")
        wake("CALL_STARTED", "", incoming = true)
        assertEquals("Not recording this call", ongoing().title())
        assertEquals("the message's buttons gone", listOf("Record"), ongoing().buttons())
        assertNotNull(prompt())
        start(talk)
        assertTrue(logged("without this message's ticket, ignoring"))
    }

    @Test fun aTakeoverInRecordEveryCallSaysRecording() {
        wake("MESSAGE_STARTED", incoming = true)
        val talk = tap(ongoing(), "Talk to them")
        wake("MESSAGE_TAKEN", incoming = true)
        assertEquals("Recording call", ongoing().title())
        assertTrue(ongoing().buttons().isEmpty())
        start(talk)
        assertTrue("the message's buttons are spent", logged("without this message's ticket, ignoring"))
    }

    @Test fun aTakeoverInAskFirstWaitsForThePrompt() {
        RecordingMode.set(context, RecordingMode.ON_DEMAND)
        wake("MESSAGE_STARTED", incoming = true)
        wake("MESSAGE_TAKEN", incoming = true)
        // The recorder stopped at the takeover; its CALL_STARTED asks next.
        assertEquals("Call in progress", ongoing().title())
        assertTrue(ongoing().buttons().isEmpty())
        assertNull(prompt())
        assertFalse(stopped)
    }

    @Test fun aTakeoverMayBeTheFirstThisServiceHears() {
        wake("MESSAGE_TAKEN", incoming = true)
        assertEquals("Recording call", ongoing().title())
        wake("CALL_ENDED", "", incoming = true)
        assertTrue(stopped)
    }

    @Test fun talkAndHangUpWithNoMessageUpAreIgnored() {
        listOf("com.jemcik.jemrec.TALK", "com.jemcik.jemrec.HANG_UP").forEach { action ->
            stray(action, ticket = "anything")
            assertTrue(action, logged("monitor: $action without this message's ticket, ignoring"))
        }
        assertFalse(logged("talk to them ->"))
        assertFalse(logged("hang up ->"))
        assertTrue(stopped)
    }

    @Test fun talkAndHangUpWithAnotherTicketAreIgnored() {
        wake("MESSAGE_STARTED", incoming = true)
        listOf("com.jemcik.jemrec.TALK", "com.jemcik.jemrec.HANG_UP").forEach { action ->
            stray(action, ticket = "forged")
            stray(action)
        }
        assertEquals(4, logs().count { it.endsWith("without this message's ticket, ignoring") })
        assertEquals("Taking a message", ongoing().title())
        assertEquals(listOf("Talk to them", "Hang up"), ongoing().buttons())
        assertFalse(stopped)
    }

    @Test fun aTalkTheRecorderDoesNotConfirmLeavesTheMessageUp() {
        wake("MESSAGE_STARTED", incoming = true)
        start(tap(ongoing(), "Talk to them"))
        awaitLog("monitor: talk to them -> false")
        assertEquals("Taking a message", ongoing().title())
        // The ticket was not spent: the buttons still work.
        start(tap(ongoing(), "Hang up"))
        awaitLog("monitor: hang up -> false")
        assertFalse(logged("ignoring"))
        assertFalse(stopped)
    }

    // ---- the prompt's answer -----------------------------------------------

    @Test fun recordAndNotNowWithoutThePromptsTicketAreIgnored() {
        wake("CALL_STARTED", "", incoming = true)
        val record = tap(prompt()!!, "Record")
        listOf("com.jemcik.jemrec.RECORD_NOW", "com.jemcik.jemrec.DECLINE_RECORDING").forEach { action ->
            stray(action)
            stray(action, ticket = "forged")
            stray(action, ticket = "")
        }
        assertEquals(6, logs().count { it.endsWith("without this prompt's ticket, ignoring") })
        assertNotNull("the prompt stays up", prompt())
        assertFalse(stopped)
        // Its own ticket still answers it.
        assertTrue(RecordPrompt.claim(context, record.getStringExtra("ticket")))
    }

    @Test fun notNowTakesThePromptDownAndKeepsTheCall() {
        wake("CALL_STARTED", "", incoming = true)
        val notNow = tap(prompt()!!, "Not now")
        start(notNow)
        assertTrue(logged("monitor: user declined to record this call"))
        assertNull(prompt())
        assertFalse("the call is still up", stopped)
        assertEquals("Not recording this call", ongoing().title())
        // One prompt, one answer.
        start(notNow)
        assertTrue(logged("without this prompt's ticket, ignoring"))
    }

    @Test fun recordStaysOnTheCallsNotificationAfterNotNow() {
        val file = recfile(start, true)
        withRecord(file) {
            wake("CALL_STARTED", "", incoming = true)
            val spent = tap(prompt()!!, "Record")
            start(tap(prompt()!!, "Not now"))
            val kept = tap(ongoing(), "Record")
            assertEquals("com.jemcik.jemrec.RECORD_NOW", kept.action)
            assertNotEquals("a ticket of its own", spent.getStringExtra("ticket"), kept.getStringExtra("ticket"))
            // The question's is spent: what kept a copy of it starts nothing.
            start(spent)
            assertTrue(logged("without this prompt's ticket, ignoring"))
            assertFalse(logged("monitor: on-demand recording started"))
            start(kept)
            awaitLog("monitor: on-demand recording started -> $file")
            eventually("recording said") { ongoing().title() == "Recording call" }
            assertTrue(ongoing().buttons().isEmpty())
            // And its own ticket is spent too.
            start(kept)
            assertEquals(2, logs().count { it.endsWith("without this prompt's ticket, ignoring") })
        }
    }

    // ---- the button over the call ------------------------------------------

    /** The button over the call in the test's hands: shown if [shows], the
     *  tickets it was shown with, and what it was told, in order. */
    private class FakeButton(private val shows: Boolean = true) : com.jemcik.jemrec.calls.AskButton {
        val tickets = mutableListOf<String>()
        val told = java.util.concurrent.CopyOnWriteArrayList<com.jemcik.jemrec.calls.CallButton.Event>()
        lateinit var answered: (String, Boolean) -> Unit
        var closed = false

        override fun show(ticket: String): Boolean {
            if (shows) tickets += ticket
            return shows
        }

        override fun on(event: com.jemcik.jemrec.calls.CallButton.Event) {
            told += event
        }

        override fun close() {
            closed = true
        }
    }

    private fun fakeButton(shows: Boolean = true) = FakeButton(shows).also { button ->
        CallMonitorService.buttonFor = { _, answered -> button.also { it.answered = answered } }
    }

    private val asked = com.jemcik.jemrec.calls.CallButton.Event.ASKED
    private val record = com.jemcik.jemrec.calls.CallButton.Event.RECORD
    private val taken = com.jemcik.jemrec.calls.CallButton.Event.TAKEN
    private val failed = com.jemcik.jemrec.calls.CallButton.Event.FAILED
    private val over = com.jemcik.jemrec.calls.CallButton.Event.OVER

    @Test fun theButtonComesUpWithTheQuestionUnderItsTicket() {
        val button = fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        assertEquals(listOf(tap(prompt()!!, "Record").getStringExtra("ticket")), button.tickets)
        assertFalse("show says it", asked in button.told)
        // The banner only announces it, the button staying.
        assertNull(prompt()!!.fullScreenIntent)
    }

    @Test fun withoutTheButtonTheQuestionStaysUpAsItDid() {
        fakeButton(shows = false)
        wake("CALL_STARTED", "", incoming = true)
        assertNotNull(prompt()!!.fullScreenIntent)
    }

    @Test fun theButtonComesUpForNoCallThatIsNotAskedAbout() {
        val button = fakeButton()
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        wake("MESSAGE_STARTED", incoming = true)
        assertEquals(emptyList<String>(), button.tickets)
    }

    @Test fun theButtonsRecordRecordsTheCall() {
        val file = recfile(start, true)
        val button = fakeButton()
        withRecord(file) {
            wake("CALL_STARTED", "", incoming = true)
            button.answered(button.tickets.single(), true)
            assertNull("the question is answered", prompt())
            awaitLog("monitor: on-demand recording started -> $file")
            eventually("the button told") { button.told == listOf(record, taken) }
            eventually("recording said") { ongoing().title() == "Recording call" }
            // Answered once.
            button.answered(button.tickets.single(), true)
            assertTrue(logged("the button without this prompt's ticket, ignoring"))
        }
    }

    @Test fun theButtonsCrossIsNotNow() {
        val button = fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        button.answered(button.tickets.single(), false)
        assertTrue(logged("monitor: user declined to record this call"))
        assertNull(prompt())
        assertEquals(listOf(over), button.told)
        assertEquals(listOf("Record"), ongoing().buttons())
    }

    @Test fun aRecordFromTheQuestionIsToldToTheButtonToo() {
        val button = fakeButton()
        withRecord(null) {
            wake("CALL_STARTED", "", incoming = true)
            start(tap(prompt()!!, "Record"))
            eventually("the button told") { button.told == listOf(record, failed) }
        }
    }

    @Test fun theCallsEndTakesTheButtonDown() {
        val button = fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        wake("CALL_ENDED", "", incoming = true)
        assertEquals(listOf(over), button.told)
    }

    @Test fun aCallEndedUnsaidTakesTheButtonDown() {
        var callUp = true
        fakeWatch(recorderUp = { true }, callUp = { callUp })
        val button = fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        callUp = false
        awaitTrue("the button down") { over in button.told }
    }

    @Test fun aSecondCallIsAskedAboutWithTheButtonUnderItsOwnTicket() {
        val button = fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        wake("CALL_STARTED", "", incoming = true)
        assertEquals(2, button.tickets.size)
        assertNotEquals(button.tickets[0], button.tickets[1])
        // The first's answers nothing now; the second's does.
        button.answered(button.tickets[0], false)
        assertFalse(logged("monitor: user declined to record this call"))
        button.answered(button.tickets[1], false)
        assertTrue(logged("monitor: user declined to record this call"))
    }

    @Test fun aRevivedPhoneCallAnsweredNotNowIsNotAskedWithTheButtonAgain() {
        val button = fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        button.answered(button.tickets.single(), false)
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertEquals(1, button.tickets.size)
    }

    @Test fun jemRecSwitchedOffMidCallTakesTheQuestionAndItsTicketDown() {
        fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        assertNotNull(RecordPrompt.ticket(context))
        CallMonitorService.stop(context)
        assertNull(prompt())
        assertNull(RecordPrompt.ticket(context))
    }

    @Test fun switchedOffWithTheServiceGoneTheQuestionGoesToo() {
        // Its process ended, the question still in the shade.
        controller.destroy()
        RecordPrompt.show(context, incoming = true)
        CallMonitorService.stop(context)
        assertNull(prompt())
        assertNull(RecordPrompt.ticket(context))
    }

    @Test fun theServiceGoingTakesTheButtonWithIt() {
        val button = fakeButton()
        wake("CALL_STARTED", "", incoming = true)
        service.onDestroy()
        assertTrue(button.closed)
    }

    @Test fun recordStaysThroughARecorderLostAndBackAfterNotNow() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Not now"))
        wake("CALL_STARTED", "", incoming = false) { putExtra("joined", true) }
        assertNull("not asked again", prompt())
        assertEquals(listOf("Record"), ongoing().buttons())
        assertTrue(RecordPrompt.claim(context, tap(ongoing(), "Record").getStringExtra("ticket")))
    }

    @Test fun theCallsEndTakesRecordWithIt() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Not now"))
        val kept = tap(ongoing(), "Record")
        wake("CALL_ENDED", "", incoming = true)
        assertFalse(RecordPrompt.claim(context, kept.getStringExtra("ticket")))
    }

    @Test fun notNowForACallThisServiceNeverSawKeepsNoTicket() {
        // The process killed while the question waited: nothing to put Record
        // on, so no ticket is left live behind it.
        RecordPrompt.show(context, incoming = true)
        start(tap(prompt()!!, "Not now"))
        assertNull(RecordPrompt.ticket(context))
        assertTrue(stopped)
    }

    @Test fun aRecordThatDidNotTakeLeavesRecordToTapAgain() {
        fakeWatch(recorderUp = { true }, callUp = { true })
        context.getSystemService(android.media.AudioManager::class.java).mode = android.media.AudioManager.MODE_IN_CALL
        withRecord(null) {
            wake("CALL_STARTED", "", incoming = true)
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand start did not take (call over?)")
            eventually("Record offered again") { ongoing().buttons() == listOf("Record") }
            assertNull("not asked again", prompt())
        }
        val file = recfile(start, true)
        withRecord(file) {
            start(tap(ongoing(), "Record"))
            awaitLog("monitor: on-demand recording started -> $file")
        }
    }

    @Test fun recordThatTheRecorderCannotStartLeavesTheCallAsItWas() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Record"))
        assertNull(prompt())
        // Its ticket spent, its Record is gone from the call's own too.
        assertTrue(ongoing().buttons().isEmpty())
        awaitLog("monitor: on-demand start did not take (call over?)")
        // To be tapped again, to the call's end: a call this service holds,
        // whatever the audio mode says - a waiting call ringing over it.
        eventually("Record offered again") { ongoing().buttons() == listOf("Record") }
        assertEquals("Not recording this call", ongoing().title())
        assertFalse(stopped)
    }

    @Test fun aRecordForACallThisServiceNeverSawStandsDownWhenItCannotStart() {
        // The prompt outlives the service on purpose: its ticket is in the
        // preferences, so its answer lands in a service started afresh - the
        // process killed while the prompt sat waiting.
        RecordPrompt.show(context, incoming = true)
        start(tap(prompt()!!, "Record"))
        awaitLog("monitor: on-demand start did not take (call over?)")
        // No call this service knows of, and nothing recording: it does not
        // stay up saying "Call in progress".
        eventually("the service stands down") { stopped }
        assertNull("no ticket left live behind it", RecordPrompt.ticket(context))
    }

    /** The recorder's answer to Record, for [block]: [name], once [answer]
     *  completes when given. */
    private fun withRecord(name: String?, answer: kotlinx.coroutines.CompletableDeferred<Unit>? = null, block: () -> Unit) {
        val before = CallMonitorService.startOnDemand
        CallMonitorService.startOnDemand = { answer?.await(); name }
        try {
            block()
        } finally {
            CallMonitorService.startOnDemand = before
        }
    }

    @Test fun aRecordStartedForACallThisServiceNeverSawKeepsItForThatCall() {
        val file = recfile(start, true)
        withRecord(file) {
            RecordPrompt.show(context, incoming = true)
            start(tap(prompt()!!, "Record"))
            awaitLog("monitor: on-demand recording started -> $file")
            settle()
            assertEquals("Recording call", ongoing().title())
            // A start from anywhere else, while it records: the call keeps it.
            stray(null)
            assertFalse(stopped)
            // Its end, as the recorder sends it.
            wake("CALL_ENDED", file, incoming = true)
            awaitLog("monitor: recording handled")
            eventually("the service stands down") { stopped }
        }
    }

    @Test fun aTalkConfirmedOnlyAfterTheCallEndedChangesNothing() {
        val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
        val before = CallMonitorService.handOver
        CallMonitorService.handOver = { _, _ -> answer.await(); true }
        val log = heldCallLog(start, incoming = true)
        try {
            wake("MESSAGE_STARTED", incoming = true)
            start(tap(ongoing(), "Talk to them"))
            // The call ends before the recorder's answer; its save keeps the
            // service up.
            val file = recfile(start, true)
            wake("CALL_ENDED", file, incoming = true)
            log.awaitHeld()
            val shown = ongoing()
            answer.complete(Unit)
            awaitLog("monitor: talk to them -> true")
            settle()
            assertTrue("not posted again for a call that is over", shown === ongoing())
            assertNotEquals("Recording call", ongoing().title())
        } finally {
            answer.complete(Unit)
            log.release()
            CallMonitorService.handOver = before
        }
    }

    @Test fun aRecordedCallLeavesAnEarlierFailureBehind() {
        // An earlier call's save failed, no call up: its notification says so.
        val first = recfile(start, true)
        wake("CALL_ENDED", first, incoming = true)
        awaitLog("monitor: recording handled")
        assertEquals("Not recording this call", ongoing().title())
        // A call whose start this service never heard of, recorded by a
        // Record tap; its save held, the service up for it.
        val second = recfile(start + 60_000, false)
        val log = heldCallLog(start + 60_000, incoming = false)
        try {
            withRecord(second) {
                RecordPrompt.show(context, incoming = false)
                start(tap(prompt()!!, "Record"))
                awaitLog("monitor: on-demand recording started -> $second")
                wake("CALL_ENDED", second, incoming = false)
                log.awaitHeld()
                // Any re-post now is about this call, which nothing failed.
                stray(null)
                assertEquals("Call in progress", ongoing().title())
            }
        } finally {
            log.release()
        }
    }

    @Test fun aRecordThatStartsAsTheCallEndsStandsDownAndSaysNothingOfRecording() {
        val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
        withRecord(recfile(start, true), answer) {
            wake("CALL_STARTED", "", incoming = true)
            start(tap(prompt()!!, "Record"))
            // The call's end comes before the recorder's answer to Record.
            wake("CALL_ENDED", "", incoming = true)
            assertFalse("the Record still runs", stopped)
            answer.complete(Unit)
            awaitLog("monitor: on-demand recording started")
            eventually("the service stands down") { stopped }
            assertEquals("Call in progress", ongoing().title())
        }
    }

    // ---- a call ends -------------------------------------------------------

    @Test fun anEndWithNothingRecordedTakesThePromptDownAndStandsDown() {
        wake("CALL_STARTED", "", incoming = true)
        val notNow = tap(prompt()!!, "Not now")
        wake("CALL_ENDED", "", incoming = true)
        assertTrue(logged("monitor: call ended, recfile="))
        assertNull(prompt())
        assertTrue(stopped)
        // The chance to answer went with the call.
        start(notNow)
        assertTrue(logged("without this prompt's ticket, ignoring"))
    }

    @Test fun theEndOfATestCallIsNotedInTheLedger() {
        val started = System.currentTimeMillis() - 42_000
        // As the recorder sends it: no recfile (the recording is deleted), test and started.
        wake("CALL_ENDED", "", incoming = true) {
            putExtra("test", true)
            putExtra("started", started)
        }
        val noted = CallLedger.read(context).testCalls.single()
        assertEquals(started, noted.startMillis)
        assertTrue(noted.incoming)
        assertTrue("about 42 s: ${noted.seconds}", noted.seconds in 42..45)
        assertTrue(logs().any { it.startsWith("monitor: a test call of the answering machine, ") && it.endsWith("s, noted") })
        assertTrue(stopped)
    }

    @Test fun anEndWithoutTestOrStartNotesNoTestCall() {
        wake("CALL_ENDED", "", incoming = true) { putExtra("test", true) }
        wake("CALL_ENDED", "", incoming = true) { putExtra("started", start) }
        wake("CALL_ENDED", "", incoming = true) {
            putExtra("test", true)
            putExtra("started", 0L)
        }
        assertTrue(CallLedger.read(context).testCalls.isEmpty())
    }

    @Test fun aRecordingMadeAfterTheSwitchWentOffIsDeletedNotSaved() {
        RecorderSwitch.set(context, false)
        val file = recfile(start, true)
        wake("CALL_STARTED", file, incoming = true)
        wake("CALL_ENDED", file, incoming = true)
        assertTrue(logged("monitor: recorder is off, discarding $file"))
        eventually("the service stands down") { stopped }
        assertFalse("not saved", logged("monitor: recording handled"))
        assertNull(notRecorded())
        assertTrue(CallLedger.read(context).accounted.isEmpty())
    }

    @Test fun aRecordingTheRecorderCannotHandOverIsReported() {
        val file = recfile(start, true)
        wake("CALL_STARTED", file, incoming = true)
        wake("CALL_ENDED", file, incoming = true)
        awaitLog("monitor: recording handled")
        val told = notRecorded()!!
        assertEquals("Call not recorded", told.title())
        assertEquals("The recorder could not be reached.", told.text())
        assertEquals(AfterCall.PROBLEM_CHANNEL_ID, told.channelId)
        assertEquals(MainActivity::class.java.name, shadowOf(told.contentIntent).savedIntent.component?.className)
        assertEquals("Not recording this call", ongoing().title())
        assertEquals("Tap to see why", ongoing().text())
        eventually("the service stands down") { stopped }
        // Left on the recorder for the reconcile: nothing in the ledger.
        assertTrue(CallLedger.read(context).accounted.isEmpty())
    }

    @Test fun aGreetingThatCouldNotPlayIsReported() {
        val file = recfile(start, true, tag = "_msgfail")
        wake("CALL_ENDED", file, incoming = true)
        awaitLog("monitor: recording handled")
        assertEquals("Call not recorded", notRecorded()!!.title())
        assertEquals(
            "The answering machine answered a call, but could not play your greeting, so it hung up. " +
                "Run the test call in Settings, Answering machine.",
            notRecorded()!!.text(),
        )
        assertEquals("Not recording this call", ongoing().title())
        assertEquals(listOf(CallLedger.Recorded(start, true, 0)), CallLedger.read(context).greetingFailures)
        eventually("the service stands down") { stopped }
    }

    @Test fun aCallerWhoLeftDuringTheGreetingIsNotReported() {
        val file = recfile(start, true, tag = "_msgnone")
        wake("CALL_ENDED", file, incoming = true)
        awaitLog("monitor: recording handled")
        assertTrue(logged("monitor: $file - the caller hung up during the greeting"))
        assertNull(notRecorded())
        // Not posted again: it says what it said when the end arrived.
        assertEquals("Call in progress", ongoing().title())
        assertEquals(listOf(CallLedger.Recorded(start, true, 0)), CallLedger.read(context).greetingHangUps)
        eventually("the service stands down") { stopped }
    }

    @Test fun aRecordingSavedBeforeIsNotReportedAgain() {
        CallLedger.noteRecording(context, start, false, 30)
        val file = recfile(start, false)
        wake("CALL_ENDED", file, incoming = false)
        awaitLog("monitor: recording handled")
        assertTrue(logged("monitor: $file was collected already"))
        assertNull(notRecorded())
        eventually("the service stands down") { stopped }
    }

    @Test fun aRecordingAlreadyBeingSavedIsLeftToThatSave() {
        val log = heldCallLog(start, incoming = true)
        try {
            val file = recfile(start, true)
            wake("CALL_ENDED", file, incoming = true)
            log.awaitHeld()
            // The same end again - a wake the recorder sent twice, say: this
            // process is saving that file already (RecordingSaver.Outcome.Busy).
            wake("CALL_ENDED", file, incoming = true)
            awaitLog("monitor: $file is being saved by the reconcile")
            awaitLog("monitor: recording handled")
            assertNull("not a failure to report", notRecorded())
            // The first save still runs, and the service stays for it:
            // stopped, its onDestroy would cancel that save half way.
            settle()
            assertFalse(stopped)
            log.release()
            eventually("the first save") { logs().count { it == "monitor: recording handled" } == 2 }
            eventually("the service stands down") { stopped }
        } finally {
            log.release()
        }
    }

    @Test fun aShortSecondCallsSaveDoesNotStopTheServiceUnderTheFirsts() {
        val log = heldCallLog(start, incoming = true)
        try {
            val first = recfile(start, true)
            wake("CALL_STARTED", first, incoming = true)
            wake("CALL_ENDED", first, incoming = true)
            log.awaitHeld()
            // A second call, over before the first is saved; its save ends at
            // once (saved before, by the ledger).
            val second = recfile(start + 30_000, false)
            CallLedger.noteRecording(context, start + 30_000, false, 5)
            wake("CALL_STARTED", second, incoming = false)
            wake("CALL_ENDED", second, incoming = false)
            awaitLog("monitor: $second was collected already")
            settle()
            assertFalse("the first save still runs", stopped)
            log.release()
            eventually("the first save") { logs().count { it == "monitor: recording handled" } == 2 }
            eventually("the service stands down") { stopped }
        } finally {
            log.release()
        }
    }

    @Test fun aSaveDoesNotStopTheServiceUnderTheNextCall() {
        val log = heldCallLog(start, incoming = true)
        try {
            val file = recfile(start, true)
            wake("CALL_STARTED", file, incoming = true)
            wake("CALL_ENDED", file, incoming = true)
            log.awaitHeld()
            wake("CALL_STARTED", "", incoming = false)
            log.release()
            awaitLog("monitor: recording handled")
            settle()
            assertFalse(stopped)
            // That call's end, nothing else running, does.
            wake("CALL_ENDED", "", incoming = false)
            assertTrue(stopped)
        } finally {
            log.release()
        }
    }

    // ---- one call's save, the next call -------------------------------------

    @Test fun aFailedSaveLeavesTheNextCallBeingRecordedAsItIs() {
        val log = heldCallLog(start, incoming = true)
        try {
            val first = recfile(start, true)
            wake("CALL_STARTED", first, incoming = true)
            wake("CALL_ENDED", first, incoming = true)
            log.awaitHeld()
            // The next call, recorded from its start, while the last one saves.
            wake("CALL_STARTED", recfile(start + 50_000, false), incoming = false)
            assertEquals("Recording call", ongoing().title())
            log.release()
            awaitLog("monitor: recording handled")
            assertEquals("The recorder could not be reached.", notRecorded()!!.text())
            // The first call's failure is told by its own notification; the
            // call being recorded now still says so.
            assertEquals("Recording call", ongoing().title())
            assertEquals("Saving this call", ongoing().text())
            settle()
            assertFalse(stopped)
        } finally {
            log.release()
        }
    }

    @Test fun aFailedSaveDoesNotStickToTheCallsAfterIt() {
        val log = heldCallLog(start, incoming = true)
        try {
            // A fails while B is up, and B is saving when C starts.
            val a = recfile(start, true)
            val bAt = start + 40_000
            val b = recfile(bAt, false)
            log.add(bAt, CallLog.Calls.OUTGOING_TYPE, seconds = 10, number = "+380441234568")
            wake("CALL_STARTED", a, incoming = true)
            wake("CALL_ENDED", a, incoming = true)
            log.awaitHeld()
            wake("CALL_STARTED", b, incoming = false)
            log.release()
            awaitLog("monitor: recording handled")
            log.hold()
            wake("CALL_ENDED", b, incoming = false)
            log.awaitHeld()
            // C, in Ask first: never recorded, and nothing of it failed. A's
            // failure, two calls back, is not C's to show.
            wake("CALL_STARTED", "", incoming = true)
            assertNotNull(prompt())
            assertEquals("Tap Record to record it from now on", ongoing().text())
            start(tap(prompt()!!, "Not now"))
            assertEquals("Tap Record to record it from now on", ongoing().text())
            log.release()
            eventually("B's save") { logs().count { it == "monitor: recording handled" } == 2 }
        } finally {
            log.release()
        }
    }

    @Test fun aMessageAndThenARecordingOutrankNotRecording() {
        val log = heldCallLog(start, incoming = true)
        try {
            val first = recfile(start, true)
            wake("CALL_STARTED", first, incoming = true)
            wake("CALL_ENDED", first, incoming = true)
            log.awaitHeld()
            // The answering machine takes the next call while the last one saves.
            wake("MESSAGE_STARTED", incoming = true)
            log.release()
            awaitLog("monitor: recording handled")
            assertEquals("Taking a message", ongoing().title())
            assertEquals(listOf("Talk to them", "Hang up"), ongoing().buttons())
            // Taken over, in Record every call.
            wake("MESSAGE_TAKEN", incoming = true)
            assertEquals("Recording call", ongoing().title())
        } finally {
            log.release()
        }
    }

    @Test fun theBackgroundKillWakeSaysCheckingAndStandsDownAfter() {
        wake("BACKGROUND_KILLED")
        assertEquals("Checking JemRec", ongoing().title())
        assertEquals("Making sure it keeps running", ongoing().text())
        // Over in a moment, so it waits, and is never seen.
        assertEquals(Notification.FOREGROUND_SERVICE_DEFERRED, ongoing().foregroundBehaviour())
        eventually("the service stands down after the warning") { stopped }
    }

    @Test fun aCallsOwnNotificationBeatsTheWarningWake() {
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        wake("BACKGROUND_KILLED")
        assertEquals("Recording call", ongoing().title())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        settle()
        assertFalse("the call keeps it", stopped)
    }

    @Test fun aStrayStartWaitsForTheBackgroundKillWarning() {
        // The warning is held where it starts: BackgroundKill's checks are
        // synchronized on it, and this thread holds it.
        synchronized(BackgroundKill) {
            wake("BACKGROUND_KILLED")
            stray(null)
            wake("CALL_STARTED", token = "forged")
            assertFalse("finishIfIdle counts the warning", stopped)
        }
        eventually("the service stands down after the warning") { stopped }
    }

    // ---- which start a stop is for ------------------------------------------

    /** The start a stop was made for (stopSelfResult): Android stops a
     *  service only for its latest start, so one made for an older start
     *  - decided before a new wake came - is refused. */
    private val stoppedFor get() = shadowOf(service).stopSelfResultId

    @Test fun aStartTurnedAwayStopsForItself() {
        start(null)
        assertTrue(stopped)
        assertEquals(startId, stoppedFor)
    }

    @Test fun aSaveStopsForTheLastStartTheServiceTookInBeforeItLooked() {
        val log = heldCallLog(start, incoming = true)
        try {
            val file = recfile(start, true)
            wake("CALL_ENDED", file, incoming = true)
            log.awaitHeld()
            // Two starts while it saves, each turned away: the save still runs.
            stray(null)
            stray(null)
            assertFalse(stopped)
            log.release()
            eventually("the service stands down") { stopped }
            assertEquals(startId, stoppedFor)
        } finally {
            log.release()
        }
    }

    // ---- with a recorder that answers (the saver's seams) --------------------

    /** The recorder holding [file] as [bytes], through the saver's seams
     *  (SaverFakes); [block] runs with it, and today's recorder is put back. */
    private fun withRecorder(file: String, bytes: ByteArray, block: (SaverFakes) -> Unit) {
        val fakes = SaverFakes(context)
        fakes.install(System.currentTimeMillis())
        try {
            fakes.files[file] = bytes
            block(fakes)
        } finally {
            fakes.restore()
        }
    }

    @Test fun aSavedCallIsCheckedForMissedCallsAndStandsTheServiceDown() {
        val file = recfile(start, true)
        withRecorder(file, SaverFakes.stream(50)) { fakes ->
            wake("CALL_STARTED", file, incoming = true)
            wake("CALL_ENDED", file, incoming = true)
            awaitLog("monitor: recording handled")
            assertTrue(logged("monitor: saved $file"))
            assertEquals(1, fakes.published.size)
            assertEquals(listOf(file), fakes.deleted)
            assertNull("nothing to report", notRecorded())
            assertEquals(listOf(CallLedger.Recorded(start, true, 1)), CallLedger.read(context).recordings)
            eventually("the service stands down") { stopped }
        }
    }

    @Test fun anEmptyRecordingIsReportedAndNotedAsTold() {
        val file = recfile(start, false)
        withRecorder(file, SaverFakes.stream(0)) { fakes ->
            wake("CALL_STARTED", file, incoming = false)
            wake("CALL_ENDED", file, incoming = false)
            awaitLog("monitor: recording handled")
            assertEquals("Call not recorded", notRecorded()!!.title())
            assertEquals("No call audio came through. Some phones do not allow calls to be recorded.", notRecorded()!!.text())
            assertEquals("Not recording this call", ongoing().title())
            assertEquals(listOf(file), fakes.deleted)
            // Told now, so the missed-call check does not tell it again.
            assertEquals(listOf(CallLedger.Recorded(start, false, 0)), CallLedger.read(context).recordings)
            eventually("the service stands down") { stopped }
        }
    }

    @Test fun aSaveCutShortByTheServiceGoingIsNotToldAsAFailure() {
        val file = recfile(start, true)
        withRecorder(file, SaverFakes.stream(10)) { fakes ->
            fakes.hold()
            try {
                wake("CALL_ENDED", file, incoming = true)
                fakes.awaitHeld()
                // Stopped from outside - any app may stop it - mid-save.
                controller.destroy()
                fakes.release()
                settle()
                assertNull("no false alarm", notRecorded())
                assertFalse(logged("monitor: recording handled"))
                assertTrue("left on the recorder", fakes.deleted.isEmpty())
            } finally {
                fakes.release()
                // A service for @After to destroy, whatever happened here.
                controller = Robolectric.buildService(CallMonitorService::class.java).create()
            }
        }
    }

    @Test fun aRecordingMadeAfterTheSwitchWentOffIsDeletedFromTheRecorder() {
        RecorderSwitch.set(context, false)
        val file = recfile(start, true)
        withRecorder(file, SaverFakes.stream(10)) { fakes ->
            wake("CALL_ENDED", file, incoming = true)
            eventually("the service stands down") { stopped }
            assertEquals(listOf(file), fakes.deleted)
            assertTrue(fakes.fetched.isEmpty())
        }
    }

    /**
     * A save that ends before the thread that started it goes on: the service
     * and the saver both on the starting thread (Unconfined), so the save runs
     * to its end inside its own launch. Launched straight into its field, the
     * job was stored after it had ended, the end found the job before it, and
     * the service stayed up (CI, 2026-11-01). Every time, here.
     */
    @Test fun aSaveThatEndsBeforeItsLaunchReturnsStandsTheServiceDown() {
        controller.destroy()
        CallMonitorService.dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        try {
            controller = Robolectric.buildService(CallMonitorService::class.java).create()
            CallLedger.noteRecording(context, start, true, 30)
            val file = recfile(start, true)
            withRecorder(file, SaverFakes.stream(10)) {
                wake("CALL_ENDED", file, incoming = true)
                assertTrue(logged("monitor: $file was collected already"))
                assertTrue(stopped)
            }
        } finally {
            CallMonitorService.dispatcher = kotlinx.coroutines.Dispatchers.IO
        }
    }

    // ---- the cable watch ---------------------------------------------------

    /**
     * A watch with no recorder to find: three tries at bringing it back,
     * each refused here ("setup has not finished"), RETRY_MS apart, then
     * the keep-alive's: 16 to 19 seconds a test here (2026-11-02), so
     * waited for longer than the rest.
     */
    private fun watchCableToItsEnd() {
        wake("CABLE") { putExtra("cable", "unplugged") }
        awaitLog("cable: the recorder is not back yet; leaving it to the keep-alive", seconds = 40)
        // That line comes before the watch's tail - the Wi-Fi watch armed
        // when off Wi-Fi, the keep-alive enqueued in WorkManager's test
        // database - and its end, which a slow machine may take more than
        // settle() over.
        eventually("the watch's end") { cableWatch()?.isCompleted == true }
    }

    /** The service's cable watch, which nothing outside it can see. */
    private fun cableWatch(): Job? =
        CallMonitorService::class.java.getDeclaredField("cableWatch").apply { isAccessible = true }.get(service) as Job?

    @Test fun aCableWatchThatEndsWithNothingElseUpStandsDown() {
        val work = HeldWork(context)
        try {
            watchCableToItsEnd()
            eventually("the watch's end stands the service down") { stopped }
        } finally {
            work.close()
        }
    }

    @Test fun aCableWatchThatEndsDuringACallLeavesTheServiceToIt() {
        val work = HeldWork(context)
        try {
            wake("CALL_STARTED", "", incoming = true)
            watchCableToItsEnd()
            assertFalse("the call keeps it", stopped)
            // The call's end, nothing else running, does.
            wake("CALL_ENDED", "", incoming = true)
            assertTrue(stopped)
        } finally {
            work.close()
        }
    }

    @Test fun aSaveThatEndsLeavesTheServiceToTheBackgroundKillWarning() {
        synchronized(BackgroundKill) {
            wake("BACKGROUND_KILLED")
            val file = recfile(start, true)
            wake("CALL_ENDED", file, incoming = true)
            awaitLog("monitor: recording handled")
            // Re-posted while the warning still runs: the warning's title
            // comes first, over "Not recording this call".
            assertEquals("Checking JemRec", ongoing().title())
            assertEquals(Notification.FOREGROUND_SERVICE_DEFERRED, ongoing().foregroundBehaviour())
            assertEquals("Call not recorded", notRecorded()!!.title())
            settle()
            // Not stopped under the warning (held here): a phone that
            // freezes an app once its service ends would hold the warning
            // back until the next thaw.
            assertFalse(stopped)
        }
        // The warning, let go, stands the service down as it ends.
        eventually("the warning's end stops the service") { stopped }
    }
}
