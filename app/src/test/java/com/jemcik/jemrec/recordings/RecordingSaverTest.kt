package com.jemcik.jemrec.recordings

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.provider.CallLog
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.CallLedger.Recorded
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.FakeCallLog
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.MessageMarks
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
        listening.cancel()
        fakes.restore()
    }

    private fun recfile(at: Long = t, incoming: Boolean = true, tag: String = "") =
        "jemrec_rec_${at}${tag}_${if (incoming) "in" else "out"}.dat"

    private fun save(name: String, incoming: Boolean = true, justEnded: Boolean = true): Outcome =
        runBlocking { RecordingSaver.save(context, name, incoming, justEnded) }

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

        assertEquals(listOf(Recorded(waiting.id, true, 5)), skippedAtFetch)
        assertEquals(dateName(t, true), saved.name)
        // Only the first call's stretch, up to where the second was answered.
        assertArrayEquals(content(0 until 500), fakes.file(saved.name).readBytes())
        assertTrue(logged("save: 1 call(s) in $file are ones the rules leave out"))
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
        Prefs.of(context).edit { putStringSet("save_failing", setOf("$again|${now - 1}")) }
        fakes.files[again] = stream(10)
        save(again)
        assertTrue("untagged: written in place", fakes.sinks.last().fd === fakes.descriptors[dateName(t + 60_000, true)])
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
        assertEquals(listOf(Recorded(t, true, 10)), ledger().recordings)
        // Pins today's behaviour, which looks like a bug: the first call's
        // entry in the ledger makes the next save take the whole recording
        // for saved before, and delete it - the second call's audio with it
        // (to be fixed in its own commit)
        assertEquals(Outcome.Gone, save(file, justEnded = false))
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(file), fakes.fetched)
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
