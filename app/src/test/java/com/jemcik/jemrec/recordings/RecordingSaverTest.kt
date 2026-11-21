package com.jemcik.jemrec.recordings

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.provider.CallLog
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.CallLedger.Recorded
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.FakeCallLog
import com.jemcik.jemrec.recordings.RecordingSaver.Outcome
import com.jemcik.jemrec.recordings.SaverFakes.Companion.configPacket
import com.jemcik.jemrec.recordings.SaverFakes.Companion.content
import com.jemcik.jemrec.recordings.SaverFakes.Companion.outOfStep
import com.jemcik.jemrec.recordings.SaverFakes.Companion.stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.concurrent.thread

/**
 * What the saver does with each recording the recorder hands over, branch by
 * branch, as it does it today: what is saved, under what name, what is kept
 * on the recorder for later, what the ledger is told, what is announced, and
 * the log lines the emulator checks read. The recorder, the writer and the
 * storage are fakes (SaverFakes) put in through the saver's seams; the call
 * log, the contacts, the preferences and the files are Robolectric's.
 *
 * A fake writer writes each packet's own bytes, so a saved file's content
 * says which packets went into it.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordingSaverTest {

    private val context = RuntimeEnvironment.getApplication()
    private val fakes = SaverFakes(context)

    /** A call's start, on the second, and when it is saved. */
    private val t = 1_795_000_000_000L
    private val now = t + 10 * 60_000L

    private val announced = mutableListOf<String>()
    private val listening = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Before fun setUp() {
        ShadowLog.clear()
        fakes.install(now)
        listening.launch { RecordingSaver.saved.collect { announced += it } }
    }

    @After fun tearDown() {
        RecordingSaver.reconcileOwed = false
        listening.cancel()
        fakes.restore()
    }

    private fun recfile(at: Long = t, incoming: Boolean = true, tag: String = "") =
        "jemrec_rec_${at}${tag}_${if (incoming) "in" else "out"}.dat"

    private fun save(name: String, incoming: Boolean = true, justEnded: Boolean = true, part: Boolean = false): Outcome =
        runBlocking { RecordingSaver.save(context, name, incoming, justEnded, part) }

    private fun reconcile() = runBlocking { RecordingSaver.reconcile(context) }

    private fun dateName(at: Long, incoming: Boolean) = FileNames.build(FileNames.Format.DATE, at, incoming, null)

    private fun ledger() = CallLedger.read(context)
    private fun logs() = ShadowLog.getLogsForTag("JemRec").map { it.msg }
    private fun logged(line: String) = line in logs()

    private fun track(call: CallTracking.Tracked) = CallTracking.update(context, call.id) { call }

    /** The work files the tagged path leaves, which it must not. */
    private fun workFiles() = File(context.noBackupFilesDir, "saving").listFiles().orEmpty().toList()

    // ---- a call, saved ---------------------------------------------------------

    @Test fun aCallIsSavedWholeAndTheRecordersCopyDeleted() {
        val file = recfile()
        fakes.files[file] = stream(50)
        var ledgerAtDelete: CallLedger.Ledger? = null
        fakes.onDelete = { ledgerAtDelete = ledger() }
        val name = dateName(t, true)

        val saved = save(file) as Outcome.Saved

        assertEquals(name, saved.name)
        assertEquals(1.0, saved.seconds, 1e-9)
        assertEquals(t, saved.startMillis)
        assertTrue(saved.incoming)
        assertFalse(saved.message)
        assertEquals(fakes.file(name).path, saved.filePath)
        assertEquals(listOf(name), fakes.published)
        assertArrayEquals(content(0 until 50), fakes.file(name).readBytes())
        assertEquals(listOf(file), fakes.deleted)
        // Noted before the recorder's copy goes, for the missed-call check.
        assertEquals(listOf(Recorded(t, true, 1)), ledgerAtDelete!!.recordings)
        assertEquals(listOf(name), announced)
        assertTrue(logged("save: wrote 50 packets, 1.0s from $file"))
        assertTrue("the work file is gone", workFiles().isEmpty())
        // Its name says which way the call went: nothing noted for it.
        assertTrue(FileNames.directions(context).isEmpty())
    }

    /** A recording the recorder marked as stopped well before its call is
     *  shorter than the call: a part, no short call - collected alone, a
     *  lost recorder's 20 s of a ten-minute call was trashed as short
     *  (review round 14). */
    @Test fun aRecordingStoppedEarlyIsAPartNotAShortCall() {
        val file = recfile(tag = "_early20000")
        fakes.files[file] = stream(50)
        val saved = save(file) as Outcome.Saved
        assertTrue(saved.part)
        assertTrue(saved.name in RecordingMarks.Parts.all(context))
    }

    /** A message whose recorder was lost mid-message - never named one -
     *  is saved as a message, whole; a lost test call's file is not kept
     *  (review round 14). */
    @Test fun aLostMessageIsSavedAsAMessageAndALostTestIsNot() {
        LostMessages.note(context, LostMessages.Lost(greetingAt = t + 10_000, callAt = t - 5_000, test = false))
        val file = recfile()
        fakes.files[file] = stream(50)
        val saved = save(file) as Outcome.Saved
        assertTrue(saved.message)
        assertTrue(saved.name in RecordingMarks.Messages.all(context))
        val later = t + 60 * 60_000L
        LostMessages.note(context, LostMessages.Lost(greetingAt = later + 8_000, callAt = null, test = true))
        val test = recfile(later)
        fakes.files[test] = stream(50)
        assertEquals(Outcome.Skipped, save(test))
        assertTrue(test in fakes.deleted)
        assertTrue("each used up", LostMessages.all(context).isEmpty())
    }

    /** A test call's other file - the one the recorder that replaced the lost
     *  one began in its call - dropped as the test's, the test call counted
     *  once, by the message's own file (review round 33: an in-memory drop
     *  missed a reconcile, a failed delete, a restart). */
    @Test fun aTestCallsOtherFileIsDroppedNotCountedAgain() {
        val other = recfile()
        LostMessages.note(context, LostMessages.Lost(greetingAt = t, callAt = t, test = true, counted = false))
        assertEquals(listOf(LostMessages.Lost(t, t, test = true, counted = false)), LostMessages.all(context))
        fakes.files[other] = stream(50)
        assertEquals(Outcome.Skipped, save(other))
        assertTrue(other in fakes.deleted)
        assertTrue(CallLedger.read(context).testCalls.isEmpty())
    }

    /** A lost message's every end uses it up, not only a save or a test
     *  call's drop: left, a file begun in its window and retried later
     *  was taken for the message (review round 17). */
    @Test fun aLostMessageIsUsedUpByEveryEnd() {
        // Switched off before Telecom's end of the call.
        LostMessages.note(context, LostMessages.Lost(greetingAt = t + 10_000, callAt = t - 5_000, test = false))
        val ended = t + 30_000
        track(CallTracking.Tracked(t - 10_000, true, null, listOf(t), ended))
        Prefs.of(context).edit { putStringSet("coverage_history", setOf("0:AUTOMATIC", "${ended - 5_000}:OFF")) }
        val off = recfile()
        fakes.files[off] = stream(10)
        assertEquals(Outcome.Skipped, save(off))
        assertTrue(logged("save: recording was switched off during $off; discarding it"))
        assertTrue("switched off", LostMessages.all(context).isEmpty())
        // Never answered.
        Prefs.of(context).edit { remove("coverage_history") }
        val later = t + 60 * 60_000L
        LostMessages.note(context, LostMessages.Lost(greetingAt = later + 10_000, callAt = later - 5_000, test = false))
        track(CallTracking.Tracked(later, false, null, emptyList(), later + 20_000))
        val unanswered = recfile(later, incoming = false)
        fakes.files[unanswered] = stream(10)
        assertEquals(Outcome.NotAnswered, save(unanswered, incoming = false))
        assertTrue("never answered", LostMessages.all(context).isEmpty())
    }

    /** One call's parts, left to a reconcile, are saved together as its
     *  parts - no short call - and told once (review round 14). */
    @Test fun aReconcileSavesOneCallsPartsTogether() {
        val first = recfile()
        val joined = "jemrec_rec_${t + 60_000}_joined_in.dat"
        CallParts.note(context, listOf(first, joined))
        fakes.listing = listOf(first, joined)
        fakes.files[first] = stream(50)
        fakes.files[joined] = stream(20)
        reconcile()
        assertEquals(2, fakes.published.size)
        assertTrue(fakes.published.all { it in RecordingMarks.Parts.all(context) })
        assertTrue(logged("part: saved jemrec_rec_${t + 60_000}_joined_in.dat, a part of its call"))
    }

    /** A reconcile does not say a call's part failed: told at the hang-up,
     *  counted toward its week - said at every run, for a week, with a
     *  sound (review round 15). */
    @Test fun aReconcileDoesNotSayAPartFailed() {
        val first = recfile()
        val joined = "jemrec_rec_${t + 60_000}_joined_in.dat"
        CallParts.note(context, listOf(first, joined))
        fakes.listing = listOf(first, joined)
        fakes.files[first] = stream(50)
        fakes.files[joined] = stream(20)
        fakes.unwritable = setOf(dateName(t + 60_000, true))
        reconcile()
        assertNull(shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(31))
    }

    /** A messenger call's file noted as a call's part is one, collected
     *  alone (review round 15). */
    @Test fun aMessengerFileNotedAsAPartIsOne() {
        val file = "jemrec_rec_${t}_voip-com.viber.voip.dat"
        CallParts.note(context, listOf(file, "jemrec_rec_${t + 60_000}_voip-com.viber.voip.dat"))
        fakes.files[file] = stream(10)
        assertTrue((save(file, incoming = false) as Outcome.Saved).part)
    }

    /** A messenger call's part is marked as a phone call's is (round 14). */
    @Test fun aMessengerCallsPartIsMarked() {
        val file = "jemrec_rec_${t}_voip-com.viber.voip.dat"
        fakes.files[file] = stream(10)
        val saved = save(file, incoming = false, part = true) as Outcome.Saved
        assertTrue(saved.part)
        assertTrue(saved.name in RecordingMarks.Parts.all(context))
    }

    @Test fun anOutgoingCallIsSavedAsOutgoing() {
        val file = recfile(incoming = false)
        fakes.files[file] = stream(10)
        val saved = save(file, incoming = false) as Outcome.Saved
        assertEquals(dateName(t, false), saved.name)
        assertFalse(saved.incoming)
        assertEquals(listOf(Recorded(t, false, 0)), ledger().recordings)
    }

    @Test fun aNameWithNoStartIsSavedAsNow() {
        val file = "jemrec_rec_x_in.dat"
        fakes.files[file] = stream(10)
        val saved = save(file) as Outcome.Saved
        assertEquals(now, saved.startMillis)
        assertEquals(dateName(now, true), saved.name)
    }

    @Test fun aFileBeingSavedIsNotSavedTwiceAtOnce() {
        val file = recfile()
        fakes.files[file] = stream(10)
        fakes.hold()
        var first: Outcome? = null
        val saving = thread { first = save(file) }
        try {
            fakes.awaitHeld()
            assertEquals(Outcome.Busy, save(file))
        } finally {
            fakes.release()
            saving.join(10_000)
        }
        assertTrue(first is Outcome.Saved)
        assertEquals(listOf(file), fakes.fetched)
    }

    // ---- before anything is fetched ------------------------------------------

    @Test fun aCallSavedBeforeIsOnlyDeletedFromTheRecorder() {
        CallLedger.noteRecording(context, t, true, 30)
        val file = recfile()
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Gone, save(file))
        assertTrue(fakes.fetched.isEmpty())
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(announced.isEmpty())
        assertTrue(logged("save: $file was saved before; deleting the daemon's copy"))
    }

    @Test fun anyCallTheLedgerAccountsForCountsAsSavedBefore() {
        listOf<(Long) -> Unit>(
            { CallLedger.noteSkipped(context, it, true, 30) },
            { CallLedger.noteGreetingHangUp(context, it, true) },
            { CallLedger.noteGreetingFailed(context, it, true) },
            { CallLedger.noteTestCall(context, it, true, 30) },
        ).forEachIndexed { i, note ->
            val at = t + i * 60_000L
            note(at)
            assertEquals(Outcome.Gone, save(recfile(at)))
        }
        assertTrue(fakes.fetched.isEmpty())
    }

    @Test fun aCallerWhoLeftDuringTheGreetingLeavesNothing() {
        val file = recfile(tag = "_msgnone")
        fakes.files[file] = stream(10)
        assertEquals(Outcome.GreetingHangUp, save(file))
        assertTrue(fakes.fetched.isEmpty())
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(Recorded(t, true, 0)), ledger().greetingHangUps)
        assertEquals(listOf(file), announced)
        assertTrue(logged("save: $file - the caller hung up during the greeting; nothing to keep"))
    }

    @Test fun aGreetingThatCouldNotPlayLeavesNothingAndSaysSo() {
        val file = recfile(tag = "_msgfail")
        fakes.files[file] = stream(10)
        assertEquals(Outcome.GreetingFailed, save(file))
        assertTrue(fakes.fetched.isEmpty())
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(Recorded(t, true, 0)), ledger().greetingFailures)
        assertEquals(listOf(file), announced)
        assertTrue(logged("save: $file - the greeting could not be played"))
    }

    @Test fun aCallSwitchedOffBeforeItEndedIsDropped() {
        val ended = t + 30_000
        track(CallTracking.Tracked(t - 10_000, true, null, listOf(t), ended))
        Prefs.of(context).edit { putStringSet("coverage_history", setOf("0:AUTOMATIC", "${ended - 5_000}:OFF")) }
        val file = recfile()
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Skipped, save(file))
        assertTrue(fakes.fetched.isEmpty())
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(file), announced)
        assertTrue(ledger().accounted.isEmpty())
        assertTrue(logged("save: recording was switched off during $file; discarding it"))
    }

    @Test fun switchedOffIsJudgedOnlyByTheEndTelecomGave() {
        // Off now, and at no end anyone gave: saved.
        Prefs.of(context).edit { putStringSet("coverage_history", setOf("0:AUTOMATIC", "${t + 5_000}:OFF")) }
        val file = recfile()
        fakes.files[file] = stream(10)
        assertTrue(save(file) is Outcome.Saved)
    }

    /** Recording switched off [offAfter] ms into a call that began at t. */
    private fun switchedOff(offAfter: Long) =
        Prefs.of(context).edit { putStringSet("coverage_history", setOf("0:AUTOMATIC", "${t + offAfter}:OFF")) }

    @Test fun withoutTelecomsEndARecordingSwitchedOffBeforeItEndedIsDropped() {
        // Ten seconds of call, recording switched off five seconds in, no
        // account from Telecom: the hang-up, reading the switch as the call
        // ends, drops it; so does a collection that reads the recording.
        CallTracking.setEnabled(context, false)
        switchedOff(5_000)
        val file = recfile()
        fakes.files[file] = stream(500)
        assertEquals(Outcome.Skipped, save(file, justEnded = false))
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(fakes.published.isEmpty())
        assertEquals(listOf(file), announced)
        assertTrue(ledger().accounted.isEmpty())
        assertTrue(logged("save: recording was switched off during $file; discarding it"))
    }

    @Test fun aRecordingSwitchedOffOnlyAfterItEndedIsSaved() {
        CallTracking.setEnabled(context, false)
        switchedOff(15_000)
        val file = recfile()
        fakes.files[file] = stream(500)
        assertTrue(save(file) is Outcome.Saved)
    }

    @Test fun telecomsEndDecidesWhereThereIsOne() {
        // Over at 4 s by Telecom's account, switched off at 5 s; the recorder
        // ran on to 10 s. Telecom's end is the call's.
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 4_000))
        switchedOff(5_000)
        val file = recfile(incoming = false)
        fakes.files[file] = stream(500)
        assertTrue(save(file, incoming = false) is Outcome.Saved)
    }

    @Test fun aMessengerCallSwitchedOffBeforeItEndedIsDropped() {
        switchedOff(5_000)
        val file = "jemrec_rec_${t}_voip-com.whatsapp.dat"
        fakes.files[file] = stream(500)
        assertEquals(Outcome.Skipped, save(file))
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(ledger().messengerRecordings.isEmpty())
    }

    @Test fun anOutgoingCallNobodyAnsweredIsDeleted() {
        track(CallTracking.Tracked(t, false, null, emptyList(), t + 20_000))
        val file = recfile(incoming = false)
        fakes.files[file] = stream(10)
        assertEquals(Outcome.NotAnswered, save(file, incoming = false))
        assertTrue(fakes.fetched.isEmpty())
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(Recorded(t, false, 0)), ledger().recordings)
        assertEquals(listOf(file), announced)
        assertTrue(logged("save: $file was never answered - only ringing, not saved"))
    }

    @Test fun telecomsAccountCountsOnlyWithTrackingOn() {
        track(CallTracking.Tracked(t, false, null, emptyList(), t + 20_000))
        CallTracking.setEnabled(context, false)
        val file = recfile(incoming = false)
        fakes.files[file] = stream(10)
        assertTrue(save(file, incoming = false) is Outcome.Saved)
    }

    // ---- the rules -------------------------------------------------------------

    /** Rules that keep contacts' calls and no other number's; the call log
     *  and the contacts allowed; [number]'s call in the log at [at]. */
    private fun ruled(number: String = "+380501234567", at: Long = t, seconds: Int = 30): FakeContacts {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
        CallRules.setOtherNumbers(context, false)
        FakeCallLog.install().add(at, CallLog.Calls.INCOMING_TYPE, seconds = seconds, number = number)
        return FakeContacts.install()
    }

    @Test fun aCallTheRulesLeaveOutIsDeletedAndNoted() {
        ruled()
        val file = recfile()
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Skipped, save(file))
        assertTrue(fakes.fetched.isEmpty())
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(Recorded(t, true, 30)), ledger().skipped)
        assertEquals(listOf(file), announced)
        assertTrue(logged("save: $file is a call the rules leave out - deleting it unsaved"))
    }

    @Test fun aContactsCallTheRulesKeepIsSaved() {
        ruled().known["+380501234567"] = 7L to "Mum"
        val file = recfile()
        fakes.files[file] = stream(10)
        assertTrue(save(file) is Outcome.Saved)
    }

    @Test fun theRulesStandDownWithoutTheContacts() {
        ruled()
        shadowOf(context).denyPermissions(Manifest.permission.READ_CONTACTS)
        val file = recfile()
        fakes.files[file] = stream(10)
        assertTrue(save(file) is Outcome.Saved)
    }

    @Test fun theRulesStandDownForACallRecordedInAskFirst() {
        ruled()
        Prefs.of(context).edit { putStringSet("coverage_history", setOf("0:ASK_FIRST")) }
        val file = recfile()
        fakes.files[file] = stream(10)
        assertTrue(save(file) is Outcome.Saved)
    }

    @Test fun aCallWaitingTheRulesLeaveOutIsNotedAndTheRestSaved() {
        ruled(number = "+380501111111").known["+380501111111"] = 7L to "Mum"
        val waiting = CallTracking.Tracked(t + 8_000, true, "+380502222222", listOf(t + 10_000), t + 15_000)
        track(CallTracking.Tracked(t - 10_000, true, "+380501111111", listOf(t), t + 20_000))
        track(waiting)
        val file = recfile()
        fakes.files[file] = stream(1_000)
        var skippedAtFetch: List<Recorded>? = null
        fakes.onFetch = { skippedAtFetch = ledger().skipped }

        val saved = save(file) as Outcome.Saved

        // Noted with the kept part, once it is saved - not before the fetch:
        // a save that failed then had its retry take the file for saved
        // (aFailedSaveOfAKeptPartIsSavedOnTheRetry).
        assertEquals(emptyList<Recorded>(), skippedAtFetch)
        assertEquals(listOf(Recorded(waiting.id, true, 5)), ledger().skipped)
        assertEquals(dateName(t, true), saved.name)
        // Only the first call's stretch, up to where the second was answered.
        assertArrayEquals(content(0 until 500), fakes.file(saved.name).readBytes())
        assertTrue(logged("save: 1 call(s) in $file are ones the rules leave out"))
    }

    /** The rules leave out the recording's own call - a stranger's - and
     *  keep a contact's waiting call in it; the first save fails (the
     *  recorder out of reach): the next one saves the contact's part. The
     *  own call's skip, keyed at the file's start, was noted before the
     *  write, and the retry took the file for saved before and deleted the
     *  recorder's only copy (the 1.2 capped review: a medium). Noted only
     *  once every kept part is published, as their own entries are. */
    @Test fun aFailedSaveOfAKeptPartIsSavedOnTheRetry() {
        ruled(number = "+380502222222", at = t + 8_000).known["+380502222222"] = 7L to "Mum"
        track(CallTracking.Tracked(t - 10_000, true, "+380501111111", listOf(t), t + 20_000))
        track(CallTracking.Tracked(t + 8_000, true, "+380502222222", listOf(t + 10_000), t + 15_000))
        val file = recfile()
        fakes.files[file] = stream(1_000)
        fakes.unreachable = true
        assertTrue(save(file) is Outcome.Failed)
        assertTrue("no skip before the kept part is saved", ledger().skipped.none { it.startMillis == t })
        fakes.unreachable = false

        val saved = save(file) as Outcome.Saved

        assertFalse(logged("save: $file was saved before"))
        assertTrue(fakes.file(saved.name).exists())
        assertEquals(listOf(file), fakes.deleted)
        assertTrue("the stranger's call noted as left out, once", ledger().skipped.count { it.startMillis == t } == 1)
    }

    /** ...and a save killed right after publishing the kept part: the next
     *  save finishes it from the journal and notes the left-out call then -
     *  once, as the save would have (SaveJournal.Skip). */
    @Test fun aSaveKilledAfterPublishingNotesTheLeftOutCallWhenFinished() {
        ruled(number = "+380502222222", at = t + 8_000).known["+380502222222"] = 7L to "Mum"
        track(CallTracking.Tracked(t - 10_000, true, "+380501111111", listOf(t), t + 20_000))
        track(CallTracking.Tracked(t + 8_000, true, "+380502222222", listOf(t + 10_000), t + 15_000))
        val file = recfile()
        fakes.files[file] = stream(1_000)
        val holding = SaveHold.hold
        SaveHold.at = SaveHold.At.PUBLISHED
        SaveHold.hold = { throw kotlinx.coroutines.CancellationException("the app's process killed") }
        try {
            save(file)
            org.junit.Assert.fail("not killed")
        } catch (_: kotlinx.coroutines.CancellationException) {
        } finally {
            SaveHold.at = null
            SaveHold.hold = holding
        }
        assertTrue("killed before the notes", ledger().skipped.isEmpty())

        save(file)

        assertTrue(logged("save: $file was cut short while publishing; 1 file(s) finished"))
        assertEquals(1, ledger().skipped.count { it.startMillis == t })
        assertEquals(listOf(file), fakes.deleted)
    }

    /** ...and a kept part with no audio in it: the file goes, and the
     *  left-out call is noted as it goes. */
    @Test fun aKeptPartWithNoAudioNotesTheLeftOutCallAsTheFileGoes() {
        ruled(number = "+380502222222", at = t + 8_000).known["+380502222222"] = 7L to "Mum"
        track(CallTracking.Tracked(t - 10_000, true, "+380501111111", listOf(t), t + 20_000))
        track(CallTracking.Tracked(t + 8_000, true, "+380502222222", listOf(t + 10_000), t + 15_000))
        val file = recfile()
        // Two seconds: nothing reaches the kept call, answered ten in.
        fakes.files[file] = stream(100)
        assertEquals(Outcome.Empty, save(file))
        assertEquals(1, ledger().skipped.count { it.startMillis == t })
    }

    @Test fun otherFilesOnTheRecorderAreAskedForOnlyWhenTheCallsNameMatters() {
        val file = recfile()
        fakes.files[file] = stream(10)
        save(file)
        assertEquals("not for a date alone, with no rules", 0, fakes.listed)

        FileNames.setFormat(context, FileNames.Format.DATE_AND_NUMBER)
        fakes.listing = null
        val second = recfile(t + 60_000)
        fakes.files[second] = stream(10)
        assertTrue("a recorder that cannot be asked holds nothing", save(second) is Outcome.Saved)
        assertEquals(1, fakes.listed)
    }

    // ---- cut to the call ---------------------------------------------------------

    @Test fun aCallIsCutFromItsAnswerToItsEnd() {
        // Dialled at t, answered 5 s later, over 4 s after that.
        track(CallTracking.Tracked(t, false, null, listOf(t + 5_000), t + 9_000))
        val file = recfile(incoming = false)
        fakes.files[file] = stream(600)
        val saved = save(file, incoming = false) as Outcome.Saved
        assertArrayEquals(content(250 until 450), fakes.file(saved.name).readBytes())
        assertEquals(4.0, saved.seconds, 1e-9)
        assertEquals(listOf(Recorded(t, false, 4)), ledger().recordings)
    }

    @Test fun callWaitingIsSavedOneFilePerCall() {
        val second = CallTracking.Tracked(t + 8_000, true, null, listOf(t + 10_000), t + 15_000)
        track(CallTracking.Tracked(t - 10_000, true, null, listOf(t), t + 20_000))
        track(second)
        val file = recfile()
        fakes.files[file] = stream(1_000)
        var ledgerAtDelete: CallLedger.Ledger? = null
        fakes.onDelete = { ledgerAtDelete = ledger() }

        val saved = save(file) as Outcome.Saved

        val first = dateName(t, true)
        val other = dateName(t + 10_000, true)
        assertEquals(listOf(first, other), fakes.published)
        assertArrayEquals(content(0 until 500), fakes.file(first).readBytes())
        assertArrayEquals(content(500 until 750), fakes.file(other).readBytes())
        // The last part is what the save says it saved.
        assertEquals(other, saved.name)
        assertEquals(t + 10_000, saved.startMillis)
        assertEquals(setOf(Recorded(t, true, 10), Recorded(t + 10_000, true, 5)), ledgerAtDelete!!.recordings.toSet())
        assertEquals(listOf(first, other), announced)
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(logged("save: wrote 500 packets, 10.0s from $file"))
        assertTrue(logged("save: wrote 250 packets, 5.0s from $file"))
    }

    @Test fun packetsOutsideEveryCallAreLeftOut() {
        // Answered 2 s in and over 1 s later: the ringing and the tail are no call's.
        track(CallTracking.Tracked(t, false, null, listOf(t + 2_000), t + 3_000))
        val file = recfile(incoming = false)
        fakes.files[file] = stream(300)
        val saved = save(file, incoming = false) as Outcome.Saved
        assertArrayEquals(content(100 until 150), fakes.file(saved.name).readBytes())
    }

    @Test fun thePacketsOwnStampsPlaceThemWhenTheCaptureWentQuiet() {
        // The clocks' offset, measured as the call was up: stamps from 0 at the start.
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 10_000, clockOffset = t))
        val file = recfile(incoming = false)
        // 100 packets, a second with none, then 100 more.
        fakes.files[file] = stream(200, ptsUs = { i -> (if (i < 100) i else i + 50) * 20_000L })
        val saved = save(file, incoming = false) as Outcome.Saved
        assertEquals(200L, fakes.sinks.single().packets)
        assertEquals(4.0, saved.seconds, 1e-9)
        assertTrue(logged("save: $file - 200 packets, the first 0.00 s after the start, then 1 gap(s), 1.00 s"))
    }

    @Test fun withoutTheClocksOffsetPacketsAreCountedAndTheClockSaysNothing() {
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 10_000))
        val file = recfile(incoming = false)
        fakes.files[file] = stream(200, ptsUs = { i -> (if (i < 100) i else i + 50) * 20_000L })
        save(file, incoming = false)
        assertFalse(logs().any { it.startsWith("save: $file - 200 packets") })
    }

    @Test fun aCallWhosePacketsMissItsCutIsSavedWhole() {
        // Telecom says answered a minute in; the file holds two seconds.
        track(CallTracking.Tracked(t, false, null, listOf(t + 60_000), t + 70_000))
        val file = recfile(incoming = false)
        fakes.files[file] = stream(100)
        val saved = save(file, incoming = false) as Outcome.Saved
        assertEquals(listOf(file, file), fakes.fetched)
        assertArrayEquals(content(0 until 100), fakes.file(saved.name).readBytes())
        assertTrue(
            logged("save: none of the 100 packets of $file fell inside the call Telecom reported; saving it whole"),
        )
    }

    @Test fun aRecordingOfTwoCallsIsNeverSavedWhole() {
        val second = CallTracking.Tracked(t + 61_000, true, null, listOf(t + 65_000), t + 68_000)
        track(CallTracking.Tracked(t - 10_000, true, null, listOf(t + 60_000), t + 70_000))
        track(second)
        val file = recfile()
        fakes.files[file] = stream(100)
        assertEquals(Outcome.Empty, save(file))
        assertEquals(listOf(file), fakes.fetched)
    }

    // ---- messages and calls taken over ---------------------------------------------

    @Test fun aMessageIsSavedFromItsStart() {
        val file = recfile(tag = "_msg2000")
        fakes.files[file] = stream(500)
        val saved = save(file) as Outcome.Saved
        assertTrue(saved.message)
        assertArrayEquals(content(100 until 500), fakes.file(saved.name).readBytes())
        assertEquals(setOf(saved.name), MessageMarks.all(context))
        assertEquals(listOf(Recorded(t, true, 8)), ledger().recordings)
    }

    @Test fun aCallTakenOverIsSavedFromThereAsAnOrdinaryCall() {
        val file = recfile(tag = "_from2000")
        fakes.files[file] = stream(500)
        val saved = save(file) as Outcome.Saved
        assertFalse(saved.message)
        assertArrayEquals(content(100 until 500), fakes.file(saved.name).readBytes())
        assertTrue(MessageMarks.all(context).isEmpty())
    }

    @Test fun aMessageKeepsOnlyItsOwnCall() {
        track(CallTracking.Tracked(t - 10_000, true, null, listOf(t), t + 20_000))
        track(CallTracking.Tracked(t + 8_000, true, null, listOf(t + 10_000), t + 15_000))
        val file = recfile(tag = "_msg2000")
        fakes.files[file] = stream(1_000)
        val saved = save(file) as Outcome.Saved
        assertEquals(listOf(dateName(t, true)), fakes.published)
        assertArrayEquals(content(100 until 500), fakes.file(saved.name).readBytes())
        assertTrue(logged("save: $file - 1 call(s) answered during the message, not kept"))
    }

    @Test fun aMessageWithNothingAfterItsStartIsAHangUpDuringTheGreeting() {
        val file = recfile(tag = "_msg20000")
        fakes.files[file] = stream(100)
        assertEquals(Outcome.GreetingHangUp, save(file))
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(Recorded(t, true, 0)), ledger().greetingHangUps)
        assertTrue(ledger().recordings.isEmpty())
        assertEquals(listOf(file), announced)
        assertTrue(logged("save: $file - nothing after the message's start; the caller hung up during the greeting"))
    }

    private val inBrackets: (String) -> String = { "${it.removeSuffix(".ogg")} (1).ogg" }

    @Test fun whatTheStorageNamedItIsTheNameEverythingRemembers() {
        val file = recfile(tag = "_msg2000")
        fakes.files[file] = stream(500)
        fakes.madeAs = inBrackets
        val saved = save(file) as Outcome.Saved
        assertEquals(inBrackets(dateName(t, true)), saved.name)
        assertEquals(setOf(saved.name), MessageMarks.all(context))
        assertEquals(listOf(saved.name), announced)
    }

    @Test fun whatTheStorageNamedAMessengerCallIsWhatItsCallerIsNotedUnder() {
        val file = "jemrec_rec_${t}_voip-com.viber.voip.dat"
        Prefs.of(context).edit {
            putStringSet("voip_call_notes", setOf(VoipCallNotes.encode(VoipCallNotes.Note("com.viber.voip", t - 5_000, "Alice", true))))
        }
        fakes.files[file] = stream(10)
        fakes.madeAs = inBrackets
        val saved = save(file, incoming = false) as Outcome.Saved
        assertEquals(inBrackets(FileNames.buildVoip(FileNames.Format.DATE, t, true, "viber", "Alice")), saved.name)
        assertEquals(mapOf(saved.name to "Alice"), FileNames.callers(context))
    }

    // ---- a messenger's call ----------------------------------------------------------

    @Test fun aMessengerCallIsNamedForItsAppAndItsCaller() {
        val file = "jemrec_rec_${t}_voip-com.viber.voip.dat"
        Prefs.of(context).edit {
            putStringSet("voip_call_notes", setOf(VoipCallNotes.encode(VoipCallNotes.Note("com.viber.voip", t - 5_000, "Alice", true))))
        }
        fakes.files[file] = stream(10)
        val saved = save(file, incoming = false) as Outcome.Saved
        val name = FileNames.buildVoip(FileNames.Format.DATE, t, true, "viber", "Alice")
        assertEquals(name, saved.name)
        assertTrue(saved.incoming)
        assertEquals(mapOf(name to "Alice"), FileNames.callers(context))
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(name), announced)
        // Not a phone call: the ledger is the call log's.
        assertTrue(ledger().accounted.isEmpty())
    }

    @Test fun aMessengerCallWithNoNotesIsOutgoingAndNamedForItsApp() {
        val file = "jemrec_rec_${t}_voip-org.example.chat.dat"
        fakes.files[file] = stream(10)
        val saved = save(file) as Outcome.Saved
        assertEquals(FileNames.buildVoip(FileNames.Format.DATE, t, null, "voip", null), saved.name)
        assertFalse(saved.incoming)
        assertTrue(FileNames.callers(context).isEmpty())
    }

    @Test fun aMessengerCallIsNotAskedWhetherItWasSavedBefore() {
        CallLedger.noteRecording(context, t, true, 30)
        val file = "jemrec_rec_${t}_voip-com.whatsapp.dat"
        fakes.files[file] = stream(10)
        assertTrue(save(file) is Outcome.Saved)
    }

    @Test fun aMessengerCallWhoseCopyCouldNotBeDeletedIsOnlyDeletedNextTime() {
        val file = "jemrec_rec_${t}_voip-com.whatsapp.dat"
        fakes.files[file] = stream(10)
        fakes.deletes = false
        assertTrue(save(file) is Outcome.Saved)
        assertTrue(logged("save: could not delete $file from the daemon; it goes next time"))
        // Noted as a phone call's is, where only the saver asks.
        assertEquals(listOf(Recorded(t, false, 0)), ledger().messengerRecordings)
        assertTrue(ledger().accounted.isEmpty())
        fakes.deletes = true
        assertEquals(Outcome.Gone, save(file, justEnded = false))
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(1, fakes.published.size)
        assertTrue(logged("save: $file was saved before; deleting the daemon's copy"))
    }

    // ---- what the recorder hands over ------------------------------------------------

    @Test fun aRecorderThatCannotBeReachedKeepsItsCopy() {
        fakes.unreachable = true
        val file = recfile()
        assertEquals(Outcome.Failed("The recorder could not be reached."), save(file))
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(logged("save: cannot reach the daemon for $file, will retry"))
    }

    @Test fun aSaveCancelledWhileFetchingIsNoFailureAndKeepsTheRecordersCopy() {
        val file = recfile()
        fakes.files[file] = stream(10)
        fakes.hold()
        var outcome: Outcome? = null
        val saving = CoroutineScope(Dispatchers.Default).launch { outcome = RecordingSaver.save(context, file, true) }
        try {
            fakes.awaitHeld()
            saving.cancel()
        } finally {
            fakes.release()
        }
        runBlocking { saving.join() }
        assertNull("no outcome: the cancellation goes on up", outcome)
        assertFalse(logs().any { it.startsWith("save: cannot reach the daemon") })
        assertTrue(fakes.deleted.isEmpty())
    }

    @Test fun aFileTheRecorderNeverWroteToIsEmpty() {
        val file = recfile()
        fakes.files[file] = ByteArray(0)
        assertEquals(Outcome.Empty, save(file))
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(fakes.opened.isEmpty())
    }

    @Test fun aFileTheRecorderNoLongerHasIsGone() {
        val file = recfile()
        assertEquals(Outcome.Gone, save(file))
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(logged("save: $file is no longer on the daemon - collected already"))
    }

    @Test fun aFileWithNoConfigPacketIsEmpty() {
        val file = recfile()
        fakes.files[file] = stream(10, config = false)
        assertEquals(Outcome.Empty, save(file))
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(logged("save: $file had no config packet"))
    }

    @Test fun aFileWithOnlyItsConfigIsEmpty() {
        val file = recfile()
        fakes.files[file] = stream(0)
        assertEquals(Outcome.Empty, save(file))
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(fakes.opened.isEmpty())
        assertTrue(logged("save: no audio in $file, discarding"))
    }

    @Test fun aConfigPacketLaterOnIsSkipped() {
        val file = recfile()
        fakes.files[file] = stream(10, tail = { configPacket() })
        assertEquals(1.0 / 5, (save(file) as Outcome.Saved).seconds, 1e-9)
    }

    @Test fun aTransferThatBreaksOffKeepsTheRecordersCopy() {
        val file = recfile()
        fakes.files[file] = stream(10, tail = { outOfStep() })
        assertEquals(Outcome.Failed("Something went wrong while saving it."), save(file))
        assertTrue(fakes.deleted.isEmpty())
        assertEquals(fakes.opened, fakes.abandoned)
        assertTrue(fakes.published.isEmpty())
        assertTrue(logged("save: transfer of $file broke off"))
        assertTrue(workFiles().isEmpty())
    }

    /** The recorder ended in the middle of a fetch - an Honor's unplug, the
     *  memory reaper: fewer bytes than it said. Taken for the whole
     *  recording, the shorter one was saved and the recorder's whole copy
     *  later dropped as saved already. */
    @Test fun aTransferCutShortKeepsTheRecordersCopy() {
        val file = recfile()
        val whole = stream(50)
        fakes.files[file] = whole
        // 8 bytes of length, then all but the last packet and a half.
        fakes.cutAfter = 8 + whole.size - 20
        assertEquals(Outcome.Failed("Something went wrong while saving it."), save(file))
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(fakes.published.isEmpty())
        assertEquals(fakes.opened, fakes.abandoned)
        assertTrue(logged("save: the transfer of $file was cut short; the recorder's copy is kept"))
        // Fetched again whole, it is saved whole.
        fakes.cutAfter = null
        assertEquals(1.0, (save(file) as Outcome.Saved).seconds, 1e-9)
        assertEquals(listOf(file), fakes.deleted)
    }

    @Test fun aTransferCutInItsLengthKeepsTheRecordersCopy() {
        val file = recfile()
        fakes.files[file] = stream(10)
        fakes.cutAfter = 5
        assertEquals(Outcome.Failed("Something went wrong while saving it."), save(file))
        assertTrue(fakes.deleted.isEmpty())
    }

    @Test fun aTransferCutBeforeItsFirstPacketKeepsTheRecordersCopy() {
        val file = recfile()
        fakes.files[file] = stream(10)
        fakes.cutAfter = 8 + 4
        assertEquals(Outcome.Failed("Something went wrong while saving it."), save(file))
        assertTrue(fakes.deleted.isEmpty())
    }

    /** The recorder ended before it said anything, or before the whole of
     *  its length's first word. Read as an empty file, it was noted as an
     *  empty call, and the next collection dropped the whole recording as
     *  that call's copy (review, 2026-11-18). */
    @Test fun aReplyCutOffBeforeItsFirstWordKeepsTheRecordersCopy() {
        // 4: a length's first word whole, its second not come - read as an
        // older recorder's mark of a disabled stream, it was empty, and the
        // recording lost (review, 2026-11-18).
        for (cut in listOf(0, 3, 4)) {
            val file = recfile(at = t + cut * 60_000L)
            fakes.files[file] = stream(10)
            fakes.cutAfter = cut
            assertEquals("cut after $cut", Outcome.Failed("Something went wrong while saving it."), save(file))
            assertTrue(fakes.deleted.isEmpty())
            fakes.files.remove(file)
        }
    }

    /** Nothing at all, and nobody knows what sends it - the question before
     *  the fetch went unanswered: taken as cut off, to be fetched again,
     *  not as empty. */
    @Test fun nothingFromARecorderNotKnownToBeOldIsCutOff() {
        fakes.asked = { null }
        val file = recfile()
        fakes.files[file] = stream(10)
        fakes.cutAfter = 0
        assertEquals(Outcome.Failed("Something went wrong while saving it."), save(file))
        assertTrue(fakes.deleted.isEmpty())
    }

    /** The reply says what it is: a length-first one is read right where the
     *  question went unanswered, or found an older recorder replaced by the
     *  time of the fetch. Read the old way, its length was a codec id and a
     *  packet 0x40000000 bytes long. */
    @Test fun aLengthFirstReplyIsReadRightWhateverTheQuestionFound() {
        for ((i, found) in listOf<Boolean?>(null, false).withIndex()) {
            fakes.asked = { found }
            val file = recfile(at = t + i * 60_000L)
            fakes.files[file] = stream(50)
            assertEquals("asked: $found", 1.0, (save(file) as Outcome.Saved).seconds, 1e-9)
            assertTrue(file in fakes.deleted)
        }
    }

    /** An older recorder's empty file is nothing at all: an empty recording,
     *  as ever. */
    @Test fun anOlderRecordersEmptyFileIsEmpty() {
        fakes.lengthFirst = false
        val file = recfile()
        fakes.files[file] = ByteArray(0)
        assertEquals(Outcome.Empty, save(file))
        assertEquals(listOf(file), fakes.deleted)
    }

    /** An older recorder's file for a messenger call whose capture would
     *  not start: scrcpy's mark of a disabled stream, four zero bytes, and
     *  nothing more. Empty, as ever - read as a length, it was a transfer
     *  cut short, tried again at every keep-alive run (review, 2026-11-18). */
    @Test fun anOlderRecordersDisabledStreamIsEmpty() {
        fakes.lengthFirst = false
        val file = recfile()
        fakes.files[file] = ByteArray(4)
        assertEquals(Outcome.Empty, save(file))
        assertEquals(listOf(file), fakes.deleted)
    }

    /** A recorder from before 1.2 says no length: what comes is the whole, as
     *  ever - for days after an update, until a revive replaces it. */
    @Test fun aRecorderThatSaysNoLengthIsReadAsBefore() {
        fakes.lengthFirst = false
        val file = recfile()
        fakes.files[file] = stream(50)
        assertEquals(1.0, (save(file) as Outcome.Saved).seconds, 1e-9)
        assertEquals(listOf(file), fakes.deleted)
    }

    @Test fun anEmptyFileWithItsLengthIsAnEmptyRecording() {
        val file = recfile()
        fakes.files[file] = ByteArray(0)
        assertFalse(save(file) is Outcome.Saved)
        assertTrue(fakes.published.isEmpty())
    }

    private fun stoppedEarlyNotices() = stoppedEarlyTexts().size

    private fun stoppedEarlyTexts() = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        .allNotifications.filter { it.extras.getString("android.title") == "Recording stopped early" }
        .map { it.extras.getCharSequence("android.bigText")?.toString() ?: it.extras.getCharSequence("android.text").toString() }

    /** Saved minutes after the call: "your last call", and what was lost
     *  and why - no way to Copy a report, which for a recorder ended
     *  mid-call gave the call before's (review, 2026-11-18). */
    @Test fun aStoppedEarlyNoticeSaysWhichCallAndNotCopyAReport() {
        val file = recfile(incoming = false)
        fakes.files[file] = stream(50)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        save(file, incoming = false)
        val text = stoppedEarlyTexts().single()
        assertTrue(text, text.startsWith("The recording of your last call stopped about 19 s before the call ended"))
        assertFalse(text, "Copy a report" in text)
    }

    /** Told at the save - kept through a process killed before the call's
     *  notice - and carried on to that notice, which takes its place
     *  (AfterCall, 1.4). */
    @Test fun aRecordingThatStoppedEarlyCarriesWhatItMissedToItsNotice() {
        val file = recfile(incoming = false)
        fakes.files[file] = stream(50)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        val saved = save(file, incoming = false) as Outcome.Saved
        assertEquals(1, stoppedEarlyNotices())
        assertTrue("${saved.earlyMissedMs}", saved.earlyMissedMs in 18_500L..19_500L)
    }

    /** A save killed between publishing and telling: the next one, finishing
     *  it from the journal, tells the early stop and carries it on to the
     *  call's notice all the same. */
    @Test fun aSaveFinishedFromItsJournalCarriesItsEarlyStop() {
        val file = recfile(incoming = false)
        fakes.files[file] = stream(50)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        val holding = SaveHold.hold
        SaveHold.at = SaveHold.At.PUBLISHED
        SaveHold.hold = { throw kotlinx.coroutines.CancellationException("the app's process killed") }
        try {
            save(file, incoming = false)
            org.junit.Assert.fail("not killed")
        } catch (_: kotlinx.coroutines.CancellationException) {
        } finally {
            SaveHold.at = null
            SaveHold.hold = holding
        }
        assertEquals("not told before the kill", 0, stoppedEarlyNotices())
        val saved = save(file, incoming = false) as Outcome.Saved
        assertEquals(1, stoppedEarlyNotices())
        assertTrue("${saved.earlyMissedMs}", saved.earlyMissedMs in 18_500L..19_500L)
    }

    /** A recording that did not stop early carries nothing. */
    @Test fun aRecordingThatRanToTheEndCarriesNoEarlyStop() {
        val file = recfile(incoming = false)
        fakes.files[file] = stream(50)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 1_500))
        assertNull((save(file, incoming = false) as Outcome.Saved).earlyMissedMs)
    }

    /** Collected long after its call - days on, once the recorder was back:
     *  named by its time, not called the last call. */
    @Test fun aStoppedEarlyNoticeLongAfterNamesTheCallByItsTime() {
        RecordingSaver.clock = { t + 2 * 60 * 60_000L }
        val file = recfile(incoming = false)
        fakes.files[file] = stream(50)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        save(file, incoming = false)
        val text = stoppedEarlyTexts().single()
        assertTrue(text, text.startsWith("The recording of your call of "))
        assertFalse(text, "your last call" in text)
    }

    /** A message's start is placed by its packets' own stamps, from the
     *  first: with a second the capture missed before it, counting packets
     *  put the start a second late, and a second of the message was lost
     *  (review, 2026-11-18). */
    @Test fun aMessagesStartIsPlacedByTheStampsAcrossAGapBeforeIt() {
        val file = recfile(tag = "_msg2000")
        // 50 packets, a second with none, then 450 more: the 51st is stamped 2.0 s.
        fakes.files[file] = stream(500, ptsUs = { i -> (if (i < 50) i else i + 50) * 20_000L })
        val saved = save(file) as Outcome.Saved
        assertArrayEquals(content(50 until 500), fakes.file(saved.name).readBytes())
    }

    /** A call Telecom saw go on 19 s past the recording's last packet: the
     *  recording stopped early, and that is said, not found by listening. */
    @Test fun aRecordingThatStoppedWellBeforeTheCallIsSaid() {
        val file = recfile(incoming = false)
        fakes.files[file] = stream(50)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        assertTrue(save(file, incoming = false) is Outcome.Saved)
        assertEquals(1, stoppedEarlyNotices())
        assertTrue(logs().any { it.startsWith("save: the recording stopped ") })
    }

    /** The same on the stamped clock real calls use - the clocks' offset
     *  known: a gap mid-call is not where it stopped; its last packet's
     *  stamp says where. Counted, 100 packets for a call of ten seconds
     *  said it stopped eight seconds early (review, 2026-11-18). */
    @Test fun onTheStampedClockAGapIsNotAStopButTheEndIs() {
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 10_000, clockOffset = t))
        val file = recfile(incoming = false)
        // 50 packets, eight seconds with none, 50 more to the call's end.
        fakes.files[file] = stream(100, ptsUs = { i -> (if (i < 50) i else i + 400) * 20_000L })
        save(file, incoming = false)
        assertEquals(0, stoppedEarlyNotices())

        track(CallTracking.Tracked(t + 60_000, false, null, listOf(t + 60_000), t + 80_000, clockOffset = t + 60_000))
        val short = recfile(at = t + 60_000, incoming = false)
        fakes.files[short] = stream(50, ptsUs = { i -> i * 20_000L })
        save(short, incoming = false)
        assertEquals(1, stoppedEarlyNotices())
    }

    /** A message the user took the call over from, in Ask first: the
     *  message ends there on purpose and the call goes on, so Telecom's end
     *  is long after it - not a recording that stopped early (review,
     *  2026-11-18). */
    @Test fun aMessageEndedByATakeoverSaysNothing() {
        val file = recfile(tag = "_msg2000")
        fakes.files[file] = stream(500)
        track(CallTracking.Tracked(t, true, null, listOf(t), t + 60_000))
        assertTrue((save(file) as Outcome.Saved).message)
        assertEquals(0, stoppedEarlyNotices())
    }

    /** The recorder ended mid-call and brought back while the call was up:
     *  its second recording of the call, still on the recorder, carries the
     *  call on - the first did not lose its last part (review, 2026-11-18). */
    @Test fun aRecordingCarriedOnByASecondOneSaysNothing() {
        val file = recfile(incoming = false)
        fakes.files[file] = stream(50)
        fakes.listing = listOf(file, recfile(at = t + 10_000, incoming = false))
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        assertTrue(save(file, incoming = false) is Outcome.Saved)
        assertEquals(0, stoppedEarlyNotices())
        assertTrue(logs().any { it.endsWith("another recording carries it on") })
    }

    /** Its call's later part still being written - in no list, noted as the
     *  call's part - carries it on: a part, nothing said of it stopping
     *  (review round 16: only the listing counted). */
    @Test fun aCallsPartStillBeingWrittenCarriesItOn() {
        val file = recfile(incoming = false)
        val later = recfile(at = t + 10_000, incoming = false)
        fakes.files[file] = stream(50)
        fakes.listing = listOf(file)
        CallParts.note(context, listOf(file, later))
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        val saved = save(file, incoming = false) as Outcome.Saved
        assertEquals(0, stoppedEarlyNotices())
        assertTrue(saved.part)
    }

    /** A reconcile leaves a part of a call to its end while the call's
     *  service holds the call's other file - only then (review round 16). */
    @Test fun aReconcileLeavesAPartWhoseCallsServiceHoldsTheOther() {
        val first = recfile()
        val joined = "jemrec_rec_${t + 60_000}_joined_in.dat"
        CallParts.note(context, listOf(first, joined))
        fakes.listing = listOf(first)
        fakes.files[first] = stream(50)
        try {
            com.jemcik.jemrec.capture.CallMonitorService.heldNow = setOf(joined)
            reconcile()
            assertTrue(fakes.published.isEmpty())
            assertTrue(logged("reconcile: $first is a part of a call still being saved; leaving it to the call's end"))
            com.jemcik.jemrec.capture.CallMonitorService.heldNow = emptySet()
            reconcile()
            assertEquals(1, fakes.published.size)
        } finally {
            com.jemcik.jemrec.capture.CallMonitorService.heldNow = emptySet()
        }
    }

    /** The end's file renamed at the end (_early): still the part the
     *  group was noted with, by its start (review round 17: saved alone,
     *  told twice). */
    @Test fun aReconcileFindsAPartRenamedAtItsEnd() {
        val first = recfile()
        val joined = "jemrec_rec_${t + 60_000}_joined_in.dat"
        val renamed = "jemrec_rec_${t + 60_000}_early4000_joined_in.dat"
        CallParts.note(context, listOf(first, joined))
        fakes.listing = listOf(first, renamed)
        fakes.files[first] = stream(50)
        fakes.files[renamed] = stream(20)
        reconcile()
        assertEquals(2, fakes.published.size)
        assertTrue(fakes.published.all { it in RecordingMarks.Parts.all(context) })
        // Together: the call told once, by its longer part.
        assertTrue(logged("part: saved $renamed, a part of its call"))
    }

    /** ...and a part the call's service holds under its new name keeps the
     *  other back. */
    @Test fun aReconcileLeavesAPartWhoseHeldPartWasRenamed() {
        val first = recfile()
        val joined = "jemrec_rec_${t + 60_000}_joined_in.dat"
        CallParts.note(context, listOf(first, joined))
        fakes.listing = listOf(first)
        fakes.files[first] = stream(50)
        try {
            com.jemcik.jemrec.capture.CallMonitorService.heldNow = setOf("jemrec_rec_${t + 60_000}_early4000_joined_in.dat")
            reconcile()
            assertTrue(fakes.published.isEmpty())
        } finally {
            com.jemcik.jemrec.capture.CallMonitorService.heldNow = emptySet()
        }
    }

    /** "Call not recorded" goes once its file is saved after all - and a
     *  part's failure said nowhere (a reconcile's) does not take it over
     *  (review rounds 15 and 16). */
    @Test fun aFailureToldGoesOnceItsFileIsSavedAfterAll() {
        val file = recfile()
        val manager = context.getSystemService(NotificationManager::class.java)
        runBlocking {
            AfterSave.react(context, Outcome.Failed("Is the storage full?"), file, true, AfterSave.Collector.HANG_UP) {
                AfterCall.postNotRecorded(context, it)
            }
            AfterSave.react(context, Outcome.Failed("x"), recfile(at = t - 600_000), true, AfterSave.Collector.PART_LATER) {}
        }
        assertNotNull(shadowOf(manager).getNotification(31))
        fakes.listing = listOf(file)
        fakes.files[file] = stream(50)
        reconcile()
        assertNull(shadowOf(manager).getNotification(31))
    }

    /** A messenger call, which Telecom tells nothing of: the recorder's
     *  mark in the name says its recording ended 41 s before it was
     *  stopped, and that is said - where before it was found by listening
     *  (review, 2026-11-18). */
    @Test fun aMessengerCallTheRecorderMarkedEarlyIsSaid() {
        val file = "jemrec_rec_${t}_early41250_voip-com.whatsapp.dat"
        fakes.files[file] = stream(50)
        assertTrue(save(file) is Outcome.Saved)
        val text = stoppedEarlyTexts().single()
        assertTrue(text, text.startsWith("The recording of your last call stopped about 41 s before the call ended"))
    }

    /** A phone call with call tracking off: no end from Telecom, the mark
     *  says it. */
    @Test fun aPhoneCallMarkedEarlyWithTrackingOffIsSaid() {
        CallTracking.setEnabled(context, false)
        val file = recfile(incoming = false, tag = "_early30000")
        fakes.files[file] = stream(50)
        assertTrue(save(file, incoming = false) is Outcome.Saved)
        assertTrue(stoppedEarlyTexts().single().contains("about 30 s"))
    }

    /** Telecom's end, where it has one, is what is said: the mark runs a
     *  second or two over (the recorder stops after the call), and the two
     *  are one recording's loss, said once. */
    @Test fun withTelecomsEndTheMarkIsNotSaidAgain() {
        val file = recfile(incoming = false, tag = "_early30000")
        fakes.files[file] = stream(50)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 20_000))
        save(file, incoming = false)
        assertTrue(stoppedEarlyTexts().single().contains("about 19 s"))
    }

    /** A mark within what the recorder lags at a call's end: nothing said. */
    @Test fun aSmallMarkSaysNothing() {
        val small = recfile(tag = "_early3000")
        fakes.files[small] = stream(50)
        assertTrue(save(small) is Outcome.Saved)
        assertEquals(0, stoppedEarlyNotices())
    }

    /** A message the recorder marked: its capture lost under the caller - a
     *  takeover stops the recording before its encoder can end early, so it
     *  never marks one - and the message cut short is said (review,
     *  2026-11-18). */
    @Test fun aMarkedMessageIsSaid() {
        val message = recfile(tag = "_early30000_msg200")
        fakes.files[message] = stream(50)
        // Tracking on, as by default, Telecom's end known: for a message
        // that end is not used, the mark is (review, 2026-11-18).
        track(CallTracking.Tracked(t, true, null, listOf(t), t + 60_000))
        assertTrue((save(message) as Outcome.Saved).message)
        assertEquals(1, stoppedEarlyNotices())
    }

    @Test fun aRecordingThatRanToTheCallsEndSaysNothing() {
        val file = recfile(incoming = false)
        // 3 s of packets for a call that ended 2 s in: the recorder runs on
        // past a call's end, as it does.
        fakes.files[file] = stream(150)
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 2_000))
        assertTrue(save(file, incoming = false) is Outcome.Saved)
        assertEquals(0, stoppedEarlyNotices())
    }

    // ---- where it goes ---------------------------------------------------------------

    @Test fun nowhereToSaveItKeepsTheRecordersCopy() {
        fakes.nowhere = true
        val file = recfile()
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Failed("There was nowhere to save it."), save(file))
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(announced.isEmpty())
    }

    @Test fun aPartsFileIsOpenedOnlyWhenItHasAPacket() {
        // Call waiting, the second call answered after the file's last packet.
        track(CallTracking.Tracked(t - 10_000, true, null, listOf(t), t + 20_000))
        track(CallTracking.Tracked(t + 8_000, true, null, listOf(t + 10_000), t + 15_000))
        val file = recfile()
        fakes.files[file] = stream(400)
        save(file)
        assertEquals(listOf(dateName(t, true)), fakes.opened)
    }

    @Test fun aWriterThatCannotBeMadeLeavesNothingBehind() {
        fakes.writerFails = true
        val file = recfile()
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Failed("Something went wrong while saving it."), save(file))
        assertEquals(fakes.opened, fakes.abandoned)
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(workFiles().isEmpty())
    }

    @Test fun itIsTaggedThroughAWorkFileUnlessSavingItFailedBefore() {
        val file = recfile()
        fakes.files[file] = stream(10)
        save(file)
        val name = dateName(t, true)
        assertFalse("tagged: written elsewhere first", fakes.sinks.single().fd === fakes.descriptors[name])

        val again = recfile(t + 60_000)
        Prefs.of(context).edit { putStringSet("save_write_failed", setOf(again)) }
        fakes.files[again] = stream(10)
        save(again)
        assertTrue("untagged: written in place", fakes.sinks.last().fd === fakes.descriptors[dateName(t + 60_000, true)])
    }

    /** A transfer cut short is no failure to write: fetched again, the file
     *  is saved tagged, as any other. Counted as one, it was saved untagged
     *  and unjoined (review, 2026-11-18). */
    @Test fun aTransferCutShortIsSavedTaggedWhenItComesWhole() {
        val file = recfile()
        fakes.listing = listOf(file)
        fakes.files[file] = stream(50)
        fakes.cutAfter = 8 + 100
        reconcile()
        assertTrue(fakes.published.isEmpty())
        fakes.cutAfter = null
        reconcile()
        val name = dateName(t, true)
        assertEquals(listOf(name), fakes.published)
        assertFalse("tagged: written elsewhere first", fakes.sinks.last().fd === fakes.descriptors[name])
    }

    /** A write that failed - the storage full - saves untagged after it:
     *  a work copy needs the room twice. */
    @Test fun aWriteThatFailedSavesUntaggedAfterIt() {
        val file = recfile()
        fakes.listing = listOf(file)
        fakes.files[file] = stream(10)
        fakes.finishes = false
        reconcile()
        assertEquals(setOf(file), Prefs.of(context).getStringSet("save_write_failed", emptySet()))
        fakes.finishes = true
        reconcile()
        val name = dateName(t, true)
        assertTrue("untagged: written in place", fakes.sinks.last().fd === fakes.descriptors[name])
        // Saved at last: forgotten.
        assertTrue(Prefs.of(context).getStringSet("save_write_failed", emptySet())!!.isEmpty())
    }

    @Test fun aFileThatWouldNotFinishKeepsTheRecordersCopy() {
        fakes.finishes = false
        val file = recfile()
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Failed("The recording could not be written. Is the storage full?"), save(file))
        assertEquals(fakes.opened, fakes.abandoned)
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(logged("save: a file from $file could not be finished; keeping the daemon's copy"))
        assertTrue(workFiles().isEmpty())
    }

    @Test fun aFileThatCouldNotBeCopiedIntoPlaceKeepsTheRecordersCopy() {
        val file = recfile()
        fakes.unwritable = setOf(dateName(t, true))
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Failed("The recording could not be written. Is the storage full?"), save(file))
        assertTrue(logged("save: a file from $file could not be copied into place"))
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(workFiles().isEmpty())
    }

    @Test fun aFileThatWouldNotPublishIsNotSaved() {
        val file = recfile()
        fakes.unpublishable = setOf(dateName(t, true))
        fakes.files[file] = stream(10)
        assertEquals(Outcome.Failed("The recording could not be written."), save(file))
        assertEquals(listOf(dateName(t, true)), fakes.abandoned)
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(logged("save: a file from $file could not be published"))
        assertTrue(ledger().recordings.isEmpty())
    }

    @Test fun aPartWithNoPacketsWrittenIsNotPublished() {
        fakes.silent = true
        val file = recfile()
        fakes.files[file] = stream(10)
        // Nothing written anywhere: read in full, no audio.
        assertEquals(Outcome.Empty, save(file))
        assertEquals(listOf(dateName(t, true)), fakes.abandoned)
    }

    @Test fun aCallWaitingPartThatWouldNotPublishKeepsTheRecordersCopy() {
        track(CallTracking.Tracked(t - 10_000, true, null, listOf(t), t + 20_000))
        track(CallTracking.Tracked(t + 8_000, true, null, listOf(t + 10_000), t + 15_000))
        val file = recfile()
        val other = dateName(t + 10_000, true)
        fakes.unpublishable = setOf(other)
        fakes.files[file] = stream(1_000)

        val saved = save(file) as Outcome.Saved

        assertEquals(dateName(t, true), saved.name)
        assertEquals(listOf(other), fakes.abandoned)
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(logged("save: keeping $file on the daemon; a part of it was not saved"))
        // Not noted yet: the first call's entry would make the next save take
        // the whole recording for saved before, and delete it - the second
        // call's audio with it.
        assertTrue(ledger().recordings.isEmpty())
        // The next save saves it all again, the second call with it.
        fakes.unpublishable = emptySet()
        assertTrue(save(file, justEnded = false) is Outcome.Saved)
        assertEquals(listOf(file, file), fakes.fetched)
        assertTrue(other in fakes.published)
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(setOf(Recorded(t, true, 10), Recorded(t + 10_000, true, 5)), ledger().recordings.toSet())
    }

    @Test fun aCopyTheRecorderWouldNotDeleteIsSavedAndLeftForNextTime() {
        fakes.deletes = false
        val file = recfile()
        fakes.files[file] = stream(10)
        assertTrue(save(file) is Outcome.Saved)
        assertTrue(logged("save: could not delete $file from the daemon; it goes next time"))
        // And the next collection only deletes it.
        assertEquals(Outcome.Gone, save(file, justEnded = false))
    }

    @Test fun aSavedFileHasItsUndeliveredTimeInTheLog() {
        // Answered at the start: packets missing inside the call's stretch.
        track(CallTracking.Tracked(t, false, null, listOf(t), t + 10_000, clockOffset = t))
        val file = recfile(incoming = false)
        fakes.files[file] = stream(200, ptsUs = { i -> (if (i < 100) i else i + 50) * 20_000L })
        save(file, incoming = false)
        assertTrue(logged("save: wrote 200 packets, 4.0s from $file, 1.00s not delivered by the capture"))
    }

    // ---- the reconcile ----------------------------------------------------------------

    @Test fun aReconcileSavesWhatTheRecorderHolds() {
        val a = recfile()
        val b = recfile(t + 60_000, incoming = false)
        fakes.listing = listOf(a, b)
        fakes.files[a] = stream(10)
        fakes.files[b] = stream(10)
        reconcile()
        assertEquals(listOf(a, b), fakes.deleted)
        assertEquals(listOf(dateName(t, true), dateName(t + 60_000, false)), fakes.published)
        assertTrue(logged("reconcile: 2 pending"))
        assertTrue(logged("reconcile: saved $a"))
        assertTrue(logged("reconcile: saved $b"))
    }

    /** A call up: the reconcile leaves it all for after it - a phone
     *  call's file, a messenger's, a save cut short - as its call is not in
     *  the log yet and any notice it posts is a tone mid-call (review rounds
     *  10 and 11). */
    @Test fun aReconcileLeavesItAllWhileACallIsUp() {
        val a = recfile()
        val messenger = "jemrec_rec_${t + 60_000}_voip-com.whatsapp.dat"
        fakes.listing = listOf(a, messenger)
        fakes.files[a] = stream(10)
        fakes.files[messenger] = stream(10)
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        audio.mode = android.media.AudioManager.MODE_IN_CALL
        reconcile()
        assertTrue(fakes.fetched.isEmpty())
        assertTrue(logged("reconcile: a call is up; leaving it all for after it"))
        audio.mode = android.media.AudioManager.MODE_NORMAL
        reconcile()
        assertEquals(setOf(a, messenger), fakes.fetched.toSet())
    }

    @Test fun aReconcileWithNothingPendingOrNoRecorderDoesNothing() {
        reconcile()
        fakes.listing = null
        reconcile()
        assertTrue(fakes.fetched.isEmpty())
        assertFalse(logs().any { it.startsWith("reconcile:") })
    }

    @Test fun aReconcileTellsOfAGreetingThatCouldNotPlay() {
        val file = recfile(tag = "_msgfail")
        fakes.listing = listOf(file)
        reconcile()
        assertTrue(logged("reconcile: $file - the greeting could not be played"))
        val told = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(31)
        assertNotNull(told)
        assertEquals("Call not recorded", told.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test fun aReconcileLogsEachOutcome() {
        val gone = recfile(t)
        val empty = recfile(t + 60_000)
        val hungUp = recfile(t + 120_000, tag = "_msgnone")
        fakes.listing = listOf(gone, empty, hungUp)
        fakes.files[empty] = stream(0)
        reconcile()
        assertTrue(logged("reconcile: $gone was collected meanwhile"))
        assertTrue(logged("reconcile: dropped empty $empty"))
        assertTrue(logged("reconcile: $hungUp - hung up during the greeting"))
    }

    @Test fun aFileThatKeepsFailingIsGivenUpOnAfterAWeekOfFailing() {
        val file = recfile()
        fakes.listing = listOf(file)
        fakes.unreachable = true
        reconcile()
        assertEquals(setOf("$file|$now"), Prefs.of(context).getStringSet("save_failing", emptySet()))
        assertTrue(logged("reconcile: left $file for later (The recorder could not be reached.)"))

        // Six days on: still trying.
        RecordingSaver.clock = { now + 6 * DAY }
        reconcile()
        assertTrue(fakes.deleted.isEmpty())

        // A week and a moment after the first failure.
        RecordingSaver.clock = { now + 7 * DAY + 1 }
        reconcile()
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(logged("reconcile: giving up on $file (The recorder could not be reached.)"))
        assertTrue(Prefs.of(context).getStringSet("save_failing", emptySet())!!.isEmpty())
    }

    /** A file given up on leaves the files "Call not recorded" is about:
     *  no save would ever take it out, and the alert stayed up after the
     *  one it showed was saved (review round 18). */
    @Test fun aFileGivenUpOnLeavesTheAlertsFiles() {
        val file = recfile()
        val other = recfile(at = t + 600_000)
        Prefs.of(context).edit { putStringSet("not_recorded_for", setOf(file, other)) }
        fakes.listing = listOf(file)
        fakes.unreachable = true
        reconcile()
        RecordingSaver.clock = { now + 7 * DAY + 1 }
        reconcile()
        assertTrue(logged("reconcile: giving up on $file (The recorder could not be reached.)"))
        assertEquals(setOf(other), Prefs.of(context).getStringSet("not_recorded_for", null))
    }

    /** ...and so does one dropped, never to be saved - its retry found it
     *  empty (review round 19). */
    @Test fun aFileDroppedLeavesTheAlertsFiles() {
        val file = recfile()
        val other = recfile(at = t + 600_000)
        Prefs.of(context).edit { putStringSet("not_recorded_for", setOf(file, other)) }
        fakes.listing = listOf(file)
        fakes.files[file] = ByteArray(0)
        reconcile()
        assertTrue(logged("reconcile: dropped empty $file"))
        assertEquals(setOf(other), Prefs.of(context).getStringSet("not_recorded_for", null))
    }

    /** A reconcile that leaves everything for a call up is owed: the call's
     *  end kicks for it - the kick a lost recorder's files were left to,
     *  its run landing in the next call, left them to the heartbeat (review
     *  round 25). One that runs pays it. */
    @Test fun aReconcileLeftForACallIsOwed() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        audio.mode = android.media.AudioManager.MODE_IN_CALL
        reconcile()
        assertTrue(logged("reconcile: a call is up; leaving it all for after it"))
        assertTrue(RecordingSaver.reconcileOwed)
        // A recorder that will not say what it holds: still owed (review
        // round 25).
        audio.mode = android.media.AudioManager.MODE_NORMAL
        fakes.listing = null
        reconcile()
        assertTrue(RecordingSaver.reconcileOwed)
        fakes.listing = emptyList()
        reconcile()
        assertFalse(RecordingSaver.reconcileOwed)
    }

    /** A call's own file, still held by its service - its end's wake on its
     *  way - is left to that end, which waits for the call log (review
     *  round 28: a reconcile took it, one read before the log was written). */
    @Test fun aReconcileLeavesACallsOwnFileItsServiceHolds() {
        val file = recfile()
        fakes.listing = listOf(file)
        fakes.files[file] = stream(50)
        try {
            com.jemcik.jemrec.capture.CallMonitorService.heldNow = setOf(file)
            reconcile()
            assertTrue(fakes.published.isEmpty())
            assertTrue(logged("reconcile: $file is its call's, still being saved by it; leaving it to the call's end"))
        } finally {
            com.jemcik.jemrec.capture.CallMonitorService.heldNow = emptySet()
        }
        reconcile()
        assertEquals(1, fakes.published.size)
    }

    /** A message's file, not named till its end, under way: left to its
     *  end (review round 29). */
    @Test fun aReconcileLeavesAMessagesFileWhileItIsUnderWay() {
        val file = recfile(at = t + 1_000)
        fakes.listing = listOf(file)
        fakes.files[file] = stream(50)
        try {
            com.jemcik.jemrec.capture.CallMonitorService.messageSince = t
            reconcile()
            assertTrue(fakes.published.isEmpty())
        } finally {
            com.jemcik.jemrec.capture.CallMonitorService.messageSince = 0L
        }
        reconcile()
        assertEquals(1, fakes.published.size)
    }

    /** A call's tells, part and own, are about one alert: the part's post
     *  counts it up, listed by Android or not yet - else the call's own
     *  started the set afresh, and its save took the alert down with the
     *  part still failing (review round 18). */
    @Test fun aCallsTellsJoinOneAlert() {
        val part = recfile()
        val own = recfile(at = t + 60_000)
        runBlocking {
            // Tells that post nothing: Android not listing the alert yet.
            AfterSave.reactToCall(
                context,
                listOf(Triple(part, true, Outcome.Failed("x")), Triple(own, true, Outcome.Failed("y"))),
                own, AfterSave.Collector.HANG_UP, {}, partTell = {},
            )
        }
        assertEquals(setOf(part, own), Prefs.of(context).getStringSet("not_recorded_for", null))
    }

    @Test fun aFileSavedAtLastIsNoLongerFailing() {
        val file = recfile()
        fakes.listing = listOf(file)
        fakes.unreachable = true
        reconcile()
        fakes.unreachable = false
        fakes.files[file] = stream(10)
        reconcile()
        assertTrue(Prefs.of(context).getStringSet("save_failing", emptySet())!!.isEmpty())
    }

    @Test fun onlyAReconcileNotesAFailure() {
        fakes.unreachable = true
        save(recfile())
        assertNull(Prefs.of(context).getStringSet("save_failing", null))
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
