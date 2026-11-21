package com.jemcik.jemrec.recordings

import android.content.Context
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.jemcik.jemrec.audio.AudioStream
import com.jemcik.jemrec.audio.OggSink
import com.jemcik.jemrec.audio.OpusOggWriter
import com.jemcik.jemrec.audio.PacketClock
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.calls.CallMatch
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.CoverageHistory
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.CaptureDaemon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.net.Socket
import java.util.Date

/**
 * Turns a finished recording the daemon wrote during a call into a saved .ogg.
 *
 * This is the collect half of the split: the daemon captured the call to a file
 * of its own; this fetches that file back over loopback and muxes it into the
 * user's storage. It is deliberately OFFLINE - the whole call is already on the
 * daemon's disk - so nothing here is on a clock and a throttled app only makes
 * the copy slower, never lossy.
 *
 * Shared by CallMonitorService (right after a call) and the startup reconcile
 * (anything a crash or reboot left uncollected), so the delete-or-keep policy
 * lives in exactly one place.
 */
object RecordingSaver {

    private const val TAG = "JemRec"

    /** What the daemon answers, where the codec id goes, for a file it no
     *  longer has. */
    private const val GONE = "gone"

    /** What the saver asks of the recorder about its files. */
    internal interface DaemonFiles {
        /** The file's stream (AudioStream), and whether it starts with its
         *  length; throws if the recorder cannot be reached. */
        suspend fun fetch(context: Context, name: String): AudioStream.Fetched

        /** Whether the recorder deleted it. */
        suspend fun delete(context: Context, name: String): Boolean

        /** The files it holds; null when it could not be asked. */
        suspend fun pending(context: Context): List<String>?

        /** The same, and whether the list is known whole - an older
         *  recorder's may be cut short unseen (com.jemcik.jemrec.recorder.DaemonClient.Listing). */
        suspend fun listing(context: Context): com.jemcik.jemrec.recorder.DaemonClient.Listing? =
            pending(context)?.let { com.jemcik.jemrec.recorder.DaemonClient.Listing(it, whole = true) }
    }

    /** The recorder itself. */
    private object Recorder : DaemonFiles {
        override suspend fun fetch(context: Context, name: String): AudioStream.Fetched = CaptureDaemon.fetch(context, name)
        override suspend fun delete(context: Context, name: String): Boolean = CaptureDaemon.deleteRecording(context, name)
        override suspend fun pending(context: Context): List<String>? = CaptureDaemon.listPendingOrNull(context)
        override suspend fun listing(context: Context): com.jemcik.jemrec.recorder.DaemonClient.Listing? = CaptureDaemon.listingOrNull(context)
    }

    /**
     * What a save works with: the recorder's files, the writer a part goes
     * through, where a part goes, the clock, and the thread. Today's own;
     * the tests put in theirs (RecordingSaverTest), to reach what only a
     * phone could before.
     */
    @VisibleForTesting
    internal var daemon: DaemonFiles = Recorder

    @VisibleForTesting
    internal var sinks: (FileDescriptor, ByteArray) -> OggSink = ::OpusOggWriter

    @VisibleForTesting
    internal var targets: (Context, String) -> RecordingStore.Target? = RecordingStore::open

    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    @VisibleForTesting
    internal var io: CoroutineDispatcher = Dispatchers.IO

    /** A recording that is not to be saved, deleted from the recorder: one
     *  made after the switch went off (CallMonitorService). Whether it went. */
    suspend fun deleteFromRecorder(context: Context, recfile: String): Boolean = daemon.delete(context, recfile)

    sealed interface Outcome {
        /** Muxed and saved; the daemon copy deleted - unless a part of it
         *  would not publish, or the delete did not get through: the copy
         *  then stays, for the next collection to save or only delete. */
        data class Saved(
            val name: String,
            val seconds: Double,
            /** Where to read it back from, and for app storage the file. */
            val uri: android.net.Uri,
            val filePath: String?,
            /** Its call's direction and start, as Telecom or the call log had
             *  them: for call waiting, not the recording's own call's. */
            val incoming: Boolean,
            val startMillis: Long,
            /** A call the answering machine took: a message. */
            val message: Boolean = false,
            /** One of a call's recordings (save's part): no short call. */
            val part: Boolean = false,
            /** A part joined mid-call: its call's start; -1 for none. */
            val callAt: Long = -1,
            /** It stopped early, this many ms before its call ended, and that
             *  was just told (StoppedEarly.tell): the call's after-call notice
             *  says it in that notice's place, so the call has one (AfterCall,
             *  1.4). Null if it did not, or another recording carries the call
             *  on. */
            val earlyMissedMs: Long? = null,
        ) : Outcome

        /** The file was read in full but held no audio; deleted rather than kept. */
        data object Empty : Outcome

        /** The daemon no longer has it: the other collector got there first.
         *  Not news - the recording is saved, just not by this call. */
        data object Gone : Outcome

        /** This process is already saving this very file. Leave it to that. */
        data object Busy : Outcome

        /** A call the user's rules leave out (see CallRules), or one recorded
         *  with recording switched off when it ended: deleted from the
         *  daemon, never saved, and announced for a Calls tab on screen. Not
         *  a failure. */
        data object Skipped : Outcome

        /** An outgoing call nobody answered: the recording was only ringing,
         *  by Telecom's own account (CallTracking). Deleted, not saved. */
        data object NotAnswered : Outcome

        /** The answering machine took the call and the caller hung up during
         *  the greeting: nothing was said, nothing is kept. */
        data object GreetingHangUp : Outcome

        /** The answering machine took the call, could not play the greeting,
         *  and hung up: the user must hear of it. */
        data object GreetingFailed : Outcome

        /** Could not fetch or the read broke off; the daemon copy is LEFT for a
         *  later retry. */
        data class Failed(val reason: String) : Outcome {
            /** The file could not be written - the storage full, likeliest -
             *  as opposed to a recorder out of reach or a transfer cut short. */
            val writing: Boolean get() = reason == NOT_WRITTEN || reason == UNPUBLISHED
        }
    }

    /**
     * Files being saved right now. The call-end fetch and the reconcile can
     * ask for the same file within moments of each other - a keep-alive tick
     * landing as a call ends - and two saves of one file are two .ogg entries
     * of the same call, the second of them empty.
     */
    private val inFlight = mutableSetOf<String>()

    private fun claim(recfile: String): Boolean = synchronized(inFlight) { inFlight.add(recfile) }
    private fun release(recfile: String) = synchronized(inFlight) { inFlight.remove(recfile) }

    /**
     * Each saved recording's name, the moment it is saved, for whoever has
     * the list on screen.
     *
     * Both collectors run out of the screen's sight - the per-call service and
     * the keep-alive worker - and the list re-read the disk only on resume. So
     * a recording saved while the app was open was not shown until the next
     * open. Measured: a call's hand-off failed, the user opened the app, the
     * list was read, and seconds later the reconcile collected and saved the
     * call - into a folder the file manager showed and the list did not, until
     * the app was closed and reopened.
     */
    val saved: SharedFlow<String> get() = RecordingLibrary.changed

    /** A recording appeared or went away somewhere the list cannot see -
     *  deleted from the after-call notification, say. Same flow: the list
     *  only needs to know to read the disk again. */
    fun announce(name: String) = RecordingLibrary.announce(name)

    /** Whether a daemon file name is for an incoming call, from its "_in." / "_out." tag. */
    fun isIncoming(recfile: String): Boolean = RecordingStore.isIncoming(recfile)

    /**
     * [justEnded]: the call hung up moments ago, so its call-log entry may
     * still be on its way and is worth waiting a few seconds for. A reconcile
     * collects calls whose entry is either in the log by now or never will be.
     * [part]: one of a call's recordings, a recorder lost mid-call having
     * left more (CallMonitorService.saveCall) - so is a file joined mid-call:
     * marked so, it is no short call to the short-call rule (Retention).
     */
    suspend fun save(context: Context, recfile: String, incoming: Boolean, justEnded: Boolean = true, part: Boolean = false): Outcome =
        withContext(io) {
            if (!claim(recfile)) return@withContext Outcome.Busy
            try {
                saveHeld(context, recfile, incoming, justEnded, part)
            } finally {
                release(recfile)
            }
        }

    /**
     * A call's files ([files], each with its way) saved as one, in order:
     * every one claimed before the first is saved, so no reconcile takes one
     * of them meanwhile - the keep-alive's, kicked at the call's end, saved
     * the end's own file as a call of its own while the part before was
     * still saving, its notice the call's, a short part judged a short call
     * (review round 14). One another save holds already is Busy. More than
     * one, each is a part ([save]'s part).
     */
    internal suspend fun saveAll(
        context: Context,
        files: List<Pair<String, Boolean>>,
        justEnded: Boolean = true,
    ): List<Pair<String, Outcome>> =
        withContext(io) {
            val held = files.map { it.first }.filter { claim(it) }.toSet()
            try {
                files.map { (recfile, incoming) ->
                    recfile to if (recfile in held) saveHeld(context, recfile, incoming, justEnded, files.size > 1) else Outcome.Busy
                }
            } finally {
                held.forEach { release(it) }
            }
        }

    /** [save], the file claimed already. */
    private suspend fun saveHeld(context: Context, recfile: String, incoming: Boolean, justEnded: Boolean, part: Boolean): Outcome =
        // Come to an end, whichever: each place published or abandoned.
        // Thrown, it stays for the next save (SaveJournal); and so it does
        // when a save cut short before is left for later (pickUp's LATER,
        // said as Busy - inside the claim, no other save says it).
        saveClaimed(context, recfile, incoming, justEnded, part).also {
            if (it != Outcome.Busy) {
                SaveHold.reached(SaveHold.At.DONE, recfile)
                SaveJournal.done(context, recfile)
            }
        }

    private suspend fun saveClaimed(context: Context, recfile: String, incoming: Boolean, justEnded: Boolean, part: Boolean): Outcome {
        // A save of this file cut short before - the app's process killed in
        // the middle (SaveJournal): finished, and nothing left to save; or
        // undone, and saved from the recorder's copy as the first time.
        SaveJournal.of(context, recfile)?.let {
            when (val picked = pickUp(context, it, recorderHasIt = true)) {
                is PickedUp.Done -> return picked.saved ?: Outcome.Gone
                // Not a failure to tell of: tried again at the next collection.
                PickedUp.Later -> return Outcome.Busy
                PickedUp.SaveAgain -> Unit
            }
        }
        // Named for when the CALL happened, taken from the daemon file, so a
        // recording collected late by the reconcile still carries the right
        // time rather than the time it was picked up.
        val file = DaemonFile.parse(recfile)
        val startedAt = Date(file.start ?: clock())

        // A messenger call: not in the phone's call log, so none of what
        // follows applies - not the caller lookup, not the rules on whose
        // calls to keep (the app's own switch is the rule), not the phone
        // calls the missed-call check reads. Saved before, though, as a
        // phone call can be: its own entries in the ledger say so.
        val messenger = file.messenger?.let(VoipApps::forPackage)
        val ledger = CallLedger.read(context)
        val before = if (messenger != null) ledger.messengerRecordings else ledger.accounted
        when (SavePlan.drop(file, startedAt.time, before)) {
            SavePlan.Drop.DUPLICATE -> {
                Log.i(TAG, "save: $recfile was saved before; deleting the daemon's copy")
                dropFromDaemon(context, recfile, announce = false)
                return Outcome.Gone
            }
            SavePlan.Drop.GREETING_FAILED -> {
                Log.i(TAG, "save: $recfile - the greeting could not be played")
                // Accounted for: no missed-call report on top. A failure is
                // told by whoever collected it - the hang-up's save or the
                // reconcile - and the Calls tab says which it was.
                dropFromDaemon(context, recfile, announce = true) {
                    CallLedger.noteGreetingFailed(context, startedAt.time, incoming)
                }
                return Outcome.GreetingFailed
            }
            SavePlan.Drop.GREETING_HANG_UP -> {
                Log.i(TAG, "save: $recfile - the caller hung up during the greeting; nothing to keep")
                dropFromDaemon(context, recfile, announce = true) {
                    CallLedger.noteGreetingHangUp(context, startedAt.time, incoming)
                }
                return Outcome.GreetingHangUp
            }
            null -> Unit
        }
        if (messenger != null) {
            val history = CoverageHistory.read(context)
            val missed = SavePlan.markedEarlyMs(file.missedMs)
            // A part of a longer call, a stop the recorder marked too: no
            // short call (review round 14: messenger parts were never marked).
            return saveMessenger(
                context, recfile, startedAt, messenger, file.messengerIncoming, missed,
                SavePlan.isPart(part, file, CallParts.contains(context, recfile)),
            ) { span ->
                SavePlan.offAt(history, startedAt.time + span)
            }
        }
        // A message whose recorder was lost mid-message: the recorder names
        // a message's file only as its call ends, which it never did - saved
        // as one, whole, the greeting at its start; a test call's not kept
        // (LostMessages; review round 14).
        // The recorder asked for its other files only where a lost message
        // could be this one: a save names nothing else from it otherwise.
        val lost = if (file.message == null) {
            val noted = LostMessages.all(context)
            LostMessages.of(noted, startedAt.time)?.let { LostMessages.of(noted, startedAt.time, otherStarts(context, recfile)) }
        } else {
            null
        }
        if (lost?.test == true) {
            Log.i(TAG, "save: $recfile - the answering machine's test call, its recorder lost; not kept")
            dropFromDaemon(context, recfile, announce = true) {
                // Its other file - the replacing recorder's - counted once
                // already (LostMessages.Lost.counted; review round 33).
                if (lost.counted) CallLedger.noteTestCall(context, startedAt.time, incoming, 0)
            }
            LostMessages.used(context, lost)
            return Outcome.Skipped
        }
        if (lost != null) Log.i(TAG, "save: $recfile - a message whose recorder was lost; saved whole as one")

        // Who it was with - asked only when something needs to know: the
        // rules on whose calls to keep, or a file name with the caller in it.
        // With neither, which is the default, this is skipped entirely and a
        // call is saved exactly as it always was.
        val format = FileNames.format(context)
        val ruled = CallRules.applies(context, startedAt.time)

        // Exact call tracking: Telecom's own account of the calls this
        // recording holds. Without it - switched off, or Telecom told us
        // nothing about this one - the call log is the one way to know.
        val tracked = if (CallTracking.enabled(context)) {
            CallTracking.callsIn(CallTracking.calls(context), startedAt.time, incoming, joined = file.joined)
        } else {
            emptyList()
        }
        val history = CoverageHistory.read(context)
        if (SavePlan.switchedOff(tracked, history)) {
            Log.i(TAG, "save: recording was switched off during $recfile; discarding it")
            dropFromDaemon(context, recfile, announce = true)
            lost?.let { LostMessages.used(context, it) }
            return Outcome.Skipped
        }
        // The daemon's other files are calls too: one whose save failed is
        // not in the ledger yet, and its call-log entry must not be taken for
        // this one's.
        val pending = if (ruled || format != FileNames.Format.DATE) {
            // Not one joined mid-call: its way a guess, it claims no entry.
            CallMatch.pendingRecorded(daemon.pending(context).orEmpty().filter { it != recfile }).filterNot { it.joined }
        } else {
            emptyList()
        }
        val decision = SavePlan.decide(
            lookups = PhoneLookups(context, pending),
            settings = SavePlan.Settings(format, ruled) { contactIds -> CallRules.keeps(context, contactIds) },
            startedAt = startedAt.time,
            incoming = incoming,
            tracked = tracked,
            tries = if (justEnded) IDENTIFY_TRIES else 1,
            joined = file.joined,
        )
        return when (decision) {
            is SavePlan.Decision.NotAnswered -> {
                lost?.let { LostMessages.used(context, it) }
                Log.i(TAG, "save: $recfile was never answered - only ringing, not saved")
                dropFromDaemon(context, recfile, announce = true) {
                    CallLedger.noteRecording(context, decision.start, decision.incoming, 0)
                }
                Outcome.NotAnswered
            }
            is SavePlan.Decision.LeaveOut -> {
                lost?.let { LostMessages.used(context, it) }
                Log.i(TAG, "save: $recfile is a call the rules leave out - deleting it unsaved")
                // Announced: a Calls tab on screen says so now, not at the next open.
                dropFromDaemon(context, recfile, announce = true) {
                    decision.leftOut.forEach { CallLedger.noteSkipped(context, it.start, it.incoming, it.seconds, it.callAt) }
                }
                Outcome.Skipped
            }
            is SavePlan.Decision.Save -> {
                if (decision.leftOut.isNotEmpty()) {
                    Log.i(TAG, "save: ${decision.leftOut.size} call(s) in $recfile are ones the rules leave out")
                }
                // Noted with the kept parts once they are published (write):
                // the own call's is at the file's start, which tells the next
                // collection the file was saved - noted before the write, a
                // save that failed had its retry delete the recorder's only
                // copy (the 1.2 capped review).
                val skips = decision.leftOut.map { SaveJournal.Skip(it.start, it.incoming, it.seconds, it.callAt) }
                val cut = SavePlan.cut(decision, file, startedAt.time, tracked)
                val message = cut.message || lost != null
                if (cut.notKept > 0) {
                    Log.i(TAG, "save: $recfile - ${cut.notKept} call(s) answered during the message, not kept")
                }
                val clock = PacketClock(startedAt.time, cut.clockOffset, cut.fromFirst)
                // Without Telecom's end, the recording's own, once it is read.
                val offBy = if (SavePlan.telecomEnded(tracked) != null) {
                    null
                } else {
                    { span: Long -> SavePlan.offAt(history, startedAt.time + span) }
                }
                // One call Telecom saw end: a recording that stopped well
                // before it is said (SavePlan.stoppedEarlyMs). Not a
                // message: in Ask first, taking the call over ends the
                // message on purpose while the call goes on (Calls.
                // splitOffMessage), and a message's file is named the same
                // either way (review, 2026-11-18).
                val endedAt = SavePlan.telecomEnded(tracked)?.takeIf { tracked.size == 1 && !message }
                // Without it - call tracking off, or more than one call in
                // the recording - the recorder's own mark, where it saw the
                // recording end before the call (StoppedEarly.marked).
                val missed = if (endedAt == null) SavePlan.markedEarlyMs(file.missedMs) else null
                // The other recordings known, read before this one is noted:
                // one may carry the call on (StoppedEarly).
                // Its call's other parts too: a group carries its own files
                // on, the later one still being written and in no list
                // (review round 15).
                val others = if (endedAt == null) {
                    emptyList()
                } else {
                    otherStarts(context, recfile) + CallParts.groups(context).filter { recfile in it }
                        .flatMap { g -> g.mapNotNull { DaemonFile.parse(it).start } }
                }
                // A part of a longer call: the short-call rule leaves it -
                // so too a recording the recorder marked as stopped early,
                // known before the write, so its tags carry it; one Telecom's
                // end says so is marked as it is published (review round 14:
                // a lost recorder's file collected alone was trashed as short).
                val isPart = SavePlan.isPart(part, file, CallParts.contains(context, recfile))
                val written = write(
                    context, recfile, cut.parts, phoneCall = true, message = message, clock = clock,
                    orWhole = cut.orWhole, offBy = offBy, part = isPart, skips = skips,
                    lastPacket = when {
                        endedAt != null -> { lastSlot -> StoppedEarly.check(startedAt.time, lastSlot, endedAt, others) }
                        missed != null -> { lastSlot -> StoppedEarly.marked(startedAt.time, lastSlot, missed) }
                        else -> null
                    },
                ) { p ->
                    RecordingTags.Facts(
                        message = message, incoming = p.incoming, startedAt = p.startMillis, part = isPart,
                        callAt = p.callAt.takeIf { it >= 0 },
                    )
                }
                // Used up at every end of its file but one left to try again
                // (review round 16).
                if (lost != null && written !is Outcome.Failed && written != Outcome.Busy) LostMessages.used(context, lost)
                if (message && written == Outcome.Empty) {
                    // Nothing from the message's start on: the sound the
                    // recorder took for the caller came as the line went down
                    // (measured on the Android 12 emulator, 2026-10-22). The
                    // caller left before the beep, not a silent recording.
                    // The recorder's copy went with the empty read.
                    Log.i(TAG, "save: $recfile - nothing after the message's start; the caller hung up during the greeting")
                    CallLedger.noteGreetingHangUp(context, startedAt.time, incoming)
                    RecordingLibrary.announce(recfile)
                    Outcome.GreetingHangUp
                } else {
                    written
                }
            }
        }
    }

    private fun noteSkips(context: Context, skips: List<SaveJournal.Skip>) =
        skips.forEach { CallLedger.noteSkipped(context, it.start, it.incoming, it.seconds, it.callAt) }

    /** failingSince's key for a save cut short whose place will not say. */
    private const val SILENT = "silent:"

    /** What [pickUp] made of a save cut short. */
    private sealed interface PickedUp {
        /** Nothing of it is left, or what was is deleted: save the
         *  recorder's copy as the first time. */
        data object SaveAgain : PickedUp

        /** Finished: every part published and noted, the recorder's copy
         *  deleted - nothing left to save. [saved]: the last part noted, for
         *  what follows any save (AfterSave: a message's notice, the silence
         *  check, the short-call rule) - a save finished here went without
         *  them, and a message left unsaid (review round 10). */
        data class Done(val saved: Outcome.Saved? = null) : PickedUp

        /** Its places would not say what they hold: left as it is, journal
         *  and all, for a later collection - never taken for gone. */
        data object Later : PickedUp
    }

    /**
     * What a save cut short left of [cut]'s recorder file (SaveJournal), put
     * right - under its claim, so no save of it is under way. [recorderHasIt]:
     * the recorder still holds the file, so a part lost can be saved again.
     *
     * Still writing: no place it opened is a whole recording; each is
     * deleted. Publishing: every part was written whole. Each is read first,
     * changing nothing (SaveJournal.Left): one whose provider will not say
     * leaves the whole for LATER - an unanswered query taken for "published"
     * deleted the recorder's only copy (review, 2026-11-18). Then, with none
     * lost, each still pending is published, each part noted as publish()
     * notes it, and the recorder's copy deleted. A part lost - abandoned when
     * it would not publish, or a pending row expired - is saved again from
     * the recorder's copy, the parts not yet seen by anyone deleted first;
     * a part the save published itself (SaveJournal.Part.published) is the
     * user's, and never deleted. With the recorder's copy gone, what is
     * there is published and noted, and nothing is undone.
     */
    private suspend fun pickUp(context: Context, cut: SaveJournal.Entry, recorderHasIt: Boolean): PickedUp {
        val recfile = cut.recfile
        if (cut is SaveJournal.Writing) {
            val gone = cut.places.count { deleteLeft(context, it) }
            Log.i(TAG, "save: $recfile was cut short while writing; ${cut.places.size} unfinished file(s), $gone deleted")
            SaveJournal.done(context, recfile)
            return if (recorderHasIt) PickedUp.SaveAgain else PickedUp.Done()
        }
        cut as SaveJournal.Publishing
        val left = cut.parts.map { if (it.published) SaveJournal.Left.THERE else leftAt(context, it.place) }
        if (left.any { it == null }) {
            // Not for ever: a place that has not said for a week - its folder
            // gone, its grant let go with a folder chosen since - is given up
            // on, the recorder's copy saved again wherever saving goes now,
            // nothing deleted: waiting on, it was never saved, and never said
            // (review round 10).
            // Its own clock (SILENT): a save's failures share failingSince's
            // key, and either shortened the other's week - a re-save failing
            // once after 6.5 days silent gave up within hours, deleting the
            // recorder's only copy (review round 11).
            if (clock() - failingSince(context, SILENT + recfile) > GIVE_UP_MS) {
                Log.w(TAG, "save: $recfile was cut short while publishing, and where it was written has not said for a week; " +
                    if (recorderHasIt) "saving it again" else "given up")
                failingSince(context, SILENT + recfile, clear = true)
                SaveJournal.done(context, recfile)
                return if (recorderHasIt) PickedUp.SaveAgain else PickedUp.Done()
            }
            Log.w(TAG, "save: $recfile was cut short while publishing, and where it was written would not say; left for later")
            return PickedUp.Later
        }
        failingSince(context, SILENT + recfile, clear = true)
        val parts = cut.parts.zip(left)
        val lost = parts.any { (part, l) -> l == SaveJournal.Left.MISSING && !part.published }
        if (lost && recorderHasIt) {
            // An unpublished part not there - nothing in it, or gone - is
            // deleted with the pending ones: an empty file kept under the
            // recording's name made the save again "(1)".
            val unfinished = parts.filter { (part, l) -> !part.published && l != SaveJournal.Left.THERE }.map { it.first.place }
            unfinished.forEach { deleteLeft(context, it) }
            Log.i(TAG, "save: $recfile was cut short while publishing, a part of it lost; ${unfinished.size} unpublished file(s) deleted, saving it again")
            SaveJournal.done(context, recfile)
            return PickedUp.SaveAgain
        }
        // A part the save published, there or since deleted by the user, or
        // one seen there now, is noted; one pending is published first; one
        // lost with the recorder's copy gone is beyond help, and said.
        val noted = mutableListOf<SaveJournal.Part>()
        for ((part, l) in parts) {
            when {
                part.published || l == SaveJournal.Left.THERE -> noted += part
                l == SaveJournal.Left.PENDING -> if (publishLeft(context, part.place)) {
                    noted += part
                } else {
                    Log.w(TAG, "save: $recfile was cut short while publishing, and a part would not publish; left for later")
                    return PickedUp.Later
                }
                else -> Log.w(TAG, "save: a part of $recfile was lost, and the recorder no longer has it")
            }
        }
        for (part in noted) {
            val name = RecordingStore.nameOf(context, part.place.uri) ?: part.name
            if (part.phoneCall) RecordingMarks.Directions.note(context, name, part.incoming)
            RecordingMarks.Starts.note(context, mapOf(name to RecordingMarks.Starts.Start(part.startMillis, part.callAt.takeIf { it >= 0 })))
            if (part.message) RecordingMarks.Messages.mark(context, name)
            if (part.part) RecordingMarks.Parts.mark(context, listOf(name))
            if (part.phoneCall) {
                CallLedger.noteRecording(context, part.startMillis, part.incoming, part.seconds, part.callAt)
            } else {
                CallLedger.noteMessenger(context, part.startMillis, part.incoming, part.seconds)
            }
            RecordingLibrary.announce(name)
        }
        // The calls the rules left out, noted with the parts as the save
        // would have (SaveJournal.Skip).
        noteSkips(context, cut.skips)
        Log.i(TAG, "save: $recfile was cut short while publishing; ${noted.size} file(s) finished")
        val told = if (noted.isNotEmpty()) {
            cut.early?.also {
                StoppedEarly.tell(context, it, clock())
                SaveJournal.told(context, recfile)
            }
        } else {
            null
        }
        // Noted in the ledger first: a delete that does not get through
        // leaves the copy to the next collection, which only deletes it.
        if (recorderHasIt) daemon.delete(context, recfile)
        SaveJournal.done(context, recfile)
        // The last part noted that is still there: one the user deleted
        // since would get an after-call notice to play a file that is gone
        // (review round 11).
        val shown = noted.lastOrNull { leftAt(context, it.place) == SaveJournal.Left.THERE }
        return PickedUp.Done(shown?.let { part ->
            Outcome.Saved(
                name = RecordingStore.nameOf(context, part.place.uri) ?: part.name,
                // Whole seconds in the journal: a part under a second is not
                // 0 here, which the short-call rule reads as "no length".
                seconds = part.seconds.toDouble().coerceAtLeast(0.5),
                uri = part.place.uri,
                filePath = part.place.filePath,
                incoming = part.incoming,
                startMillis = part.startMillis,
                message = part.message,
                part = part.part,
                callAt = part.callAt,
                earlyMissedMs = told?.takeUnless { it.carried }?.missed,
            )
        })
    }

    /** What a place of a save cut short holds; null where it will not say. */
    private fun leftAt(context: Context, place: SaveJournal.Place): SaveJournal.Left? = when {
        place.filePath != null -> java.io.File(place.filePath)
            .let { if (it.isFile && it.length() > 0L) SaveJournal.Left.THERE else SaveJournal.Left.MISSING }
        place.uri.authority == android.provider.MediaStore.AUTHORITY -> MediaStoreRecordings.leftAt(context, place.uri)
        else -> FolderRecordings.leftAt(context, place.uri)
    }

    /** A MediaStore row a save cut short wrote whole, published. */
    private fun publishLeft(context: Context, place: SaveJournal.Place): Boolean =
        place.filePath == null && place.uri.authority == android.provider.MediaStore.AUTHORITY &&
            MediaStoreRecordings.publishLeft(context, place.uri)

    /** A place a save cut short left, deleted. */
    private fun deleteLeft(context: Context, place: SaveJournal.Place): Boolean =
        if (place.filePath == null && place.uri.authority == android.provider.MediaStore.AUTHORITY) {
            MediaStoreRecordings.deleteLeft(context, place.uri)
        } else {
            runCatching { RecordingLocation.of(place.uri, place.filePath).deleteForGood(context) == true }.getOrDefault(false)
        }

    /**
     * A recording dropped unsaved: deleted from the recorder, [note]d in the
     * ledger, and [announce]d - the Calls tab on screen then says what
     * became of the call.
     */
    private suspend fun dropFromDaemon(context: Context, recfile: String, announce: Boolean, note: () -> Unit = {}) {
        daemon.delete(context, recfile)
        note()
        if (announce) RecordingLibrary.announce(recfile)
    }

    /** The phone behind SavePlan: the contacts and the call log. */
    private class PhoneLookups(
        private val context: Context,
        private val pending: List<CallLedger.Recorded>,
    ) : SavePlan.Lookups {
        private val contacts = mutableMapOf<String, CallLogLookup.Contact>()

        /** The calls already accounted for, or still on the daemon, which
         *  this one is not - a test call or a greeting hang-up included: an
         *  entry of theirs taken for this call names it after them. */
        private val others by lazy { CallLedger.read(context).accounted + pending }

        override val callLogAllowed: Boolean get() = CallLogLookup.granted(context)
        override fun contact(number: String?) = CallLogLookup.contactFor(context, number, contacts)
        override fun logged(start: Long, incoming: Boolean) = CallLogLookup.callFor(context, start, incoming, others)
        override fun loggedBetween(from: Long, to: Long) = CallLogLookup.entriesBetween(context, from, to)

        // The mobile network's country, else the SIM's: neither needs a
        // permission. Both ua on the Honor (gsm.operator.iso-country and
        // gsm.sim.operator.iso-country, 2026-11-01).
        override val country: String? by lazy {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            telephony?.networkCountryIso?.takeIf { it.isNotBlank() } ?: telephony?.simCountryIso?.takeIf { it.isNotBlank() }
        }

        override suspend fun pause() = delay(1_000)
    }

    /** A messenger call, named for its app and - when its notification said
     *  - its caller and direction; the direction the recorder's, where it
     *  knew ([incoming], from its file's name). See VoipCallNotes. */
    private suspend fun saveMessenger(
        context: Context,
        recfile: String,
        startedAt: Date,
        app: VoipApps.App,
        incoming: Boolean?,
        missed: Long?,
        part: Boolean,
        offBy: (Long) -> Boolean,
    ): Outcome {
        val note = VoipCallNotes.find(context, app.pkg, startedAt.time, incoming)
        val format = FileNames.format(context)
        val name = FileNames.buildVoip(format, startedAt.time, note?.incoming, app.tag, note?.name)
        val outcome = write(
            context, recfile, listOf(Part.whole(name, note?.incoming ?: false, startedAt.time)), phoneCall = false, offBy = offBy,
            lastPacket = missed?.let { m -> { lastSlot -> StoppedEarly.marked(startedAt.time, lastSlot, m) } },
            part = part,
        ) {
            RecordingTags.Facts(incoming = note?.incoming, caller = note?.name, startedAt = startedAt.time, part = part)
        }
        // Where the name does not say who: the date and number formats, or a
        // caller with nothing a file name can take.
        if (outcome is Outcome.Saved && note?.name != null && FileNames.parse(outcome.name)?.caller == null) {
            RecordingMarks.Callers.note(context, outcome.name, note.name)
        }
        return outcome
    }

    /**
     * One file a daemon recording is saved into, and which of its packets go
     * there, by 20 ms slot from the recording's start: counted from the first
     * packet, unless the capture lost time (PacketClock). Usually one part
     * with all of them; see SavePlan for the others.
     */
    internal class Part(
        val name: String,
        val incoming: Boolean,
        /** For the ledger: when this part's call began. */
        val startMillis: Long,
        val packets: List<LongRange>,
        /** For the ledger: a part joined mid-call's own call start, which
         *  matches it to its call (CallLedger.Recorded.callAt); -1 for none. */
        val callAt: Long = -1,
    ) {
        fun holds(index: Long): Boolean = packets.any { index in it }

        /**
         * The slots of this part, from its start to the last written
         * ([last]), that no packet filled: what the capture never delivered
         * inside it, the stretch before its [first] packet included. A call
         * waiting's other stretches are not this part's, and do not count.
         * 0 with nothing written.
         */
        fun undeliveredSlots(first: Long, last: Long, written: Long): Long {
            if (first < 0) return 0
            val from = minOf(first, packets.firstOrNull()?.first ?: first)
            val held = packets.sumOf { r -> (minOf(r.last, last) - maxOf(r.first, from) + 1).coerceAtLeast(0) }
            return (held - written).coerceAtLeast(0)
        }

        /** The same part from packet [first] on. */
        fun from(first: Long): Part = Part(
            name, incoming, startMillis,
            packets.mapNotNull { r -> maxOf(r.first, first).let { if (it > r.last) null else it..r.last } },
            callAt,
        )

        companion object {
            fun whole(name: String, incoming: Boolean, startMillis: Long, callAt: Long = -1) =
                Part(name, incoming, startMillis, listOf(0L until Long.MAX_VALUE), callAt)
        }
    }

    /**
     * A part being written: where to, and the muxer - which writes into a
     * work file of its own, copied into place with the recording's tags when
     * it is finished (RecordingTags: the muxer cannot write them). With no
     * work file, the muxer writes into place itself, untagged, as it always
     * used to (see write).
     */
    private class Open(
        val part: Part,
        val target: RecordingStore.Target,
        val writer: OggSink,
        private val work: File?,
        private val workFd: ParcelFileDescriptor?,
    ) {
        /** The first and last slots written here (PacketClock): the time
         *  they span less the packets written is what the capture never
         *  delivered inside this part. */
        var firstSlot = -1L
        var lastSlot = -1L

        /** Seconds inside this part the capture did not deliver. */
        val undelivered: Double
            get() = part.undeliveredSlots(firstSlot, lastSlot, writer.packets) * CallTracking.PACKET_MS / 1000.0

        /** Copy the finished file into place, with [facts] in it - or, where
         *  the muxer wrote into place itself, give its pages their positions
         *  where they lie. Either way written through to the storage. */
        fun copyIn(facts: RecordingTags.Facts) {
            val from = work ?: return RecordingTags.mendInPlace(target.descriptor.fileDescriptor)
            workFd?.close()
            RecordingTags.copyTagged(from, target.descriptor.fileDescriptor, facts)
        }

        fun discardWork() {
            runCatching { workFd?.close() }
            work?.delete()
        }
    }

    /**
     * Reading a recording from the recorder into [parts], each packet into the
     * part that holds it; a packet none holds is dropped. A part's file is
     * opened at its first packet, so a part with none leaves nothing behind.
     * [clock]: where each packet falls, for parts cut to a call; without it
     * they are counted. [tagged]: through a work file, for the tags.
     */
    private class Transfer(
        private val context: Context,
        private val recfile: String,
        private val parts: List<Part>,
        private val clock: PacketClock?,
        private val tagged: Boolean,
    ) {
        val open = LinkedHashMap<Part, Open>()
        var seen = 0L
            private set

        /** The last slot a packet fell in: how long the recording ran. */
        var lastSlot = -1L
            private set
        var read = WriteOutcome.Read.BROKE_OFF
            private set

        fun from(fetched: AudioStream.Fetched) {
            val socket = fetched.socket
            try {
                val stream = try {
                    AudioStream(socket, fetched.lengthFirst)
                } catch (e: java.io.EOFException) {
                    // Nothing at all, not even a codec id: a file the daemon
                    // created and never wrote to (it could not start capturing).
                    // An empty recording, not a transfer to retry for a week.
                    runCatching { socket.close() }
                    null
                } catch (t: Throwable) {
                    runCatching { socket.close() }
                    throw t
                }
                if (stream == null) read = WriteOutcome.Read.COMPLETE
                stream?.use { stream ->
                    if (stream.codecId == GONE) {
                        read = WriteOutcome.Read.GONE
                    } else {
                        val config = stream.readPacket()
                        if (config == null && stream.cutShort) {
                            Log.w(TAG, "save: the transfer of $recfile was cut short; the recorder's copy is kept")
                        } else if (config == null || !config.isConfig) {
                            // Empty or malformed - a completed read with nothing in it.
                            Log.w(TAG, "save: $recfile had no config packet")
                            read = WriteOutcome.Read.COMPLETE
                        } else {
                            var nowhere = false
                            while (true) {
                                val packet = stream.readPacket() ?: break
                                if (packet.isConfig) continue
                                val slot = clock?.next(packet.pts) ?: seen
                                seen++
                                lastSlot = maxOf(lastSlot, slot)
                                val part = parts.firstOrNull { it.holds(slot) }
                                if (part == null) continue
                                val into = open[part] ?: run {
                                    val target = targets(context, part.name)
                                    if (target == null) {
                                        nowhere = true
                                        null
                                    } else {
                                        SaveJournal.opened(context, recfile, SaveJournal.Place(target.uri, target.filePath))
                                        open(part, target, config.data).also { open[part] = it }
                                    }
                                } ?: break
                                if (into.firstSlot < 0) into.firstSlot = slot
                                into.lastSlot = slot
                                into.writer.write(packet.data, packet.pts)
                                // Written, not read: a call's ringing is read and cut.
                                if (into.writer.packets == SaveHold.TRANSFER_PACKETS) SaveHold.reached(SaveHold.At.TRANSFER, recfile)
                            }
                            read = when {
                                nowhere -> WriteOutcome.Read.NOWHERE
                                // Fewer bytes than the recorder said: not the
                                // whole recording. Taken for it, the shorter
                                // one was saved and, at the next collection,
                                // the recorder's whole copy dropped as saved
                                // already (SavePlan's DUPLICATE).
                                stream.cutShort -> {
                                    Log.w(TAG, "save: the transfer of $recfile was cut short; the recorder's copy is kept")
                                    WriteOutcome.Read.BROKE_OFF
                                }
                                else -> WriteOutcome.Read.COMPLETE
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Broke off mid-transfer: ambiguous, so keep the file for a retry.
                Log.e(TAG, "save: transfer of $recfile broke off", t)
            }
        }

        /** A writer for [part] into [target]; one that cannot be made must
         *  not leave its target open behind it. */
        private fun open(part: Part, target: RecordingStore.Target, config: ByteArray): Open {
            var work: File? = null
            var workFd: ParcelFileDescriptor? = null
            val writer = try {
                if (tagged) {
                    work = RecordingTags.workFile(context)
                    workFd = ParcelFileDescriptor.open(
                        work,
                        ParcelFileDescriptor.MODE_READ_WRITE or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )
                }
                sinks((workFd ?: target.descriptor).fileDescriptor, config)
            } catch (t: Throwable) {
                runCatching { workFd?.close() }
                work?.delete()
                target.abandon()
                throw t
            }
            return Open(part, target, writer, work, workFd)
        }
    }

    /**
     * Fetch the daemon's file and mux it into [parts] (Transfer), then finish
     * every part, copy each into place, and publish them - or say why not.
     * [phoneCall]: each saved part noted for the missed-call check, and its
     * direction; a messenger's call is noted only as saved. [facts]: what
     * each part's file says of itself (RecordingTags). [orWhole]: saved into
     * instead when no part held any of the file's packets
     * (SavePlan.wholeIfNothingFits). [offBy]: whether recording was switched
     * off by the end of a recording that ran this long, in ms (SavePlan.offAt).
     */
    private suspend fun write(
        context: Context,
        recfile: String,
        parts: List<Part>,
        phoneCall: Boolean,
        message: Boolean = false,
        clock: PacketClock? = null,
        orWhole: Part? = null,
        offBy: ((Long) -> Boolean)? = null,
        lastPacket: ((lastSlot: Long) -> StoppedEarly.Early?)? = null,
        part: Boolean = false,
        /** Calls the rules leave out, noted only as the file is done with:
         *  with the parts once all are published, or as an empty one goes. */
        skips: List<SaveJournal.Skip> = emptyList(),
        facts: (Part) -> RecordingTags.Facts,
    ): Outcome {
        val fetched = try {
            daemon.fetch(context, recfile)
        } catch (e: CancellationException) {
            // Stopped, not failed: the recorder's copy stays for the next
            // collection, and nobody is told of a failure.
            throw e
        } catch (t: Throwable) {
            // Could not even reach the daemon: keep the file, try again later.
            Log.w(TAG, "save: cannot reach the daemon for $recfile, will retry", t)
            return Outcome.Failed("The recorder could not be reached.")
        }

        // Tagged, unless writing this file has failed before: a work copy and
        // then the copy into place need its size free twice, where writing
        // into place needs it once, and a file that cannot be saved is given
        // up on after a week. Untagged, it is saved as it always was; its
        // marks are in the preferences still, and only a reinstall loses them.
        // Only a write's failure: a recorder out of reach, or a transfer cut
        // short, says nothing of the room, and its file was saved untagged
        // and unjoined - 50 pages a second - for nothing (review, 2026-11-18).
        val transfer = Transfer(context, recfile, parts, clock, tagged = !writeFailedBefore(context, recfile))
        transfer.from(fetched)
        // Where the capture put its first packet, and what it lost after: the
        // first thing to ask of a cut that looks wrong (issue #40).
        val first = clock?.firstMs
        if (clock != null && first != null) {
            Log.i(
                TAG,
                "save: %s - %d packets, the first %.2f s after the start, then %d gap(s), %.2f s"
                    .format(recfile, transfer.seen, first / 1000.0, clock.gaps, clock.lostMs / 1000.0),
            )
        }

        val open = transfer.open
        val counts = open.values.map { it to (it.writer.packets to it.writer.durationSeconds) }
        // Every file finished before any is published: one that would not
        // finish is unplayable, and the daemon's copy is then the only good
        // one - kept for a retry, not deleted as saved.
        val finished = open.values.map { runCatching { it.writer.finish() }.getOrDefault(false) }.all { it }
        val anything = counts.any { (_, count) -> count.first > 0L }
        val switchedOff = transfer.read == WriteOutcome.Read.COMPLETE &&
            offBy?.invoke((transfer.lastSlot + 1) * CallTracking.PACKET_MS) == true
        // Into place, tags and all - only what will be published: a copy that
        // fails is a file not written, and the daemon's copy is kept for it.
        val copied = !switchedOff && finished && transfer.read == WriteOutcome.Read.COMPLETE && anything && open.values.all { o ->
            o.writer.packets == 0L || runCatching { o.copyIn(facts(o.part)) }
                .onFailure { Log.e(TAG, "save: a file from $recfile could not be copied into place", it) }
                .isSuccess
        }
        open.values.forEach { it.discardWork() }

        val whole = orWhole?.takeIf { transfer.seen > 0 }
        return when (WriteOutcome.of(transfer.read, anything, copied, whole != null, switchedOff)) {
            WriteOutcome.Ending.GONE -> {
                Log.i(TAG, "save: $recfile is no longer on the daemon - collected already")
                open.values.forEach { it.target.abandon() }
                Outcome.Gone
            }
            WriteOutcome.Ending.NOWHERE -> {
                open.values.forEach { it.target.abandon() }
                Outcome.Failed("There was nowhere to save it.")
            }
            WriteOutcome.Ending.NOT_WRITTEN -> {
                Log.e(TAG, "save: a file from $recfile could not be finished; keeping the daemon's copy")
                open.values.forEach { it.target.abandon() }
                Outcome.Failed(NOT_WRITTEN)
            }
            WriteOutcome.Ending.PUBLISH -> {
                SaveHold.reached(SaveHold.At.PUBLISH, recfile)
                // Decided now, said once published - and written down with
                // the rest, for a save cut short there (SaveJournal).
                val early = lastPacket?.invoke(transfer.lastSlot)
                publish(context, recfile, counts, phoneCall, message, early, part, skips).let {
                    if (it is Outcome.Saved && early != null) {
                        StoppedEarly.tell(context, early, clock())
                        SaveJournal.told(context, recfile)
                        // Said now, kept through a process killed before the
                        // call's notice; that notice then takes its place.
                        it.copy(earlyMissedMs = early.takeUnless { e -> e.carried }?.missed)
                    } else {
                        it
                    }
                }
            }
            WriteOutcome.Ending.WHOLE -> {
                Log.w(TAG, "save: none of the ${transfer.seen} packets of $recfile fell inside the call Telecom reported; saving it whole")
                open.values.forEach { it.target.abandon() }
                write(
                    context, recfile, listOf(checkNotNull(whole)), phoneCall, message, offBy = offBy, part = part,
                    skips = skips, facts = facts,
                )
            }
            WriteOutcome.Ending.EMPTY -> {
                // Read the whole thing; there was simply no call audio in it.
                // Noted in the ledger only where the user is told
                // (CallMonitorService): collected later, it is the missed-call
                // check that says so.
                Log.w(TAG, "save: no audio in $recfile, discarding")
                open.values.forEach { it.target.abandon() }
                dropFromDaemon(context, recfile, announce = false) { noteSkips(context, skips) }
                Outcome.Empty
            }
            WriteOutcome.Ending.BROKE_OFF -> {
                open.values.forEach { it.target.abandon() }
                Outcome.Failed("Something went wrong while saving it.")
            }
            WriteOutcome.Ending.SWITCHED_OFF -> {
                Log.i(TAG, "save: recording was switched off during $recfile; discarding it")
                open.values.forEach { it.target.abandon() }
                dropFromDaemon(context, recfile, announce = true)
                Outcome.Skipped
            }
        }
    }

    /**
     * Publish each finished part with a packet in it, note it, announce it,
     * and then delete the recorder's copy - unless a part would not publish.
     * The last part published is what the save says it saved.
     */
    private suspend fun publish(
        context: Context,
        recfile: String,
        counts: List<Pair<Open, Pair<Long, Double>>>,
        phoneCall: Boolean,
        message: Boolean,
        early: StoppedEarly.Early? = null,
        partOf: Boolean = false,
        skips: List<SaveJournal.Skip> = emptyList(),
    ): Outcome = withContext(NonCancellable) {
        // Stopped well before its call: shorter than the call, no short call
        // (review round 14) - marked by name, and in the journal.
        val part = partOf || early != null
        // From the first publish to the daemon's delete, one piece: a
        // cancellation between them left a saved recording on the
        // daemon too, and the next reconcile saved it again as "(1)". So
        // did the app's process killed there, which NonCancellable cannot
        // stop - written down first, and finished by the next save
        // (SaveJournal, pickUp).
        SaveJournal.publishing(
            context, recfile,
            counts.filter { (_, count) -> count.first > 0L }.map { (o, count) ->
                SaveJournal.Part(
                    SaveJournal.Place(o.target.uri, o.target.filePath), o.target.name, o.part.startMillis,
                    o.part.incoming, count.second.toInt(), phoneCall, message, callAt = o.part.callAt, part = part,
                )
            },
            early,
            skips,
        )
        SaveHold.reached(SaveHold.At.PUBLISHING, recfile)
        var last: Outcome.Saved? = null
        var unpublished = false
        val published = mutableListOf<Pair<Part, Int>>()
        for ((o, count) in counts) {
            val (packets, seconds) = count
            if (packets == 0L) {
                o.target.abandon()
                continue
            }
            if (!o.target.publish()) {
                Log.e(TAG, "save: a file from $recfile could not be published")
                o.target.abandon()
                unpublished = true
                continue
            }
            // Published: written down at once, so a part gone later is told
            // from one lost before it was (SaveJournal, pickUp).
            SaveJournal.published(context, recfile, o.target.uri)
            // The recording's daemon name, not its own: with the
            // caller in it, a file name is a name and a number, and
            // logcat is no place for those.
            // And what the capture left out inside it: a recording
            // shorter than its call by that, not cut wrong - what the
            // emulator checks allow for (tools/emulator_checks.py).
            val undelivered = o.undelivered
            Log.i(
                TAG,
                "save: wrote %d packets, %.1fs from %s".format(packets, seconds, recfile) +
                    if (undelivered > 0) ", %.2fs not delivered by the capture".format(undelivered) else "",
            )
            // As the file is called: the part's name is the one asked for,
            // and a place that found it taken gave another.
            val name = o.target.name
            if (phoneCall) RecordingMarks.Directions.note(context, name, o.part.incoming)
            RecordingMarks.Starts.note(context, mapOf(name to RecordingMarks.Starts.Start(o.part.startMillis, o.part.callAt.takeIf { it >= 0 })))
            published += o.part to seconds.toInt()
            if (message) RecordingMarks.Messages.mark(context, name)
            if (part) RecordingMarks.Parts.mark(context, listOf(name))
            RecordingLibrary.announce(name)
            last = Outcome.Saved(
                name, seconds, o.target.uri, o.target.filePath, o.part.incoming, o.part.startMillis,
                message = message, part = part, callAt = o.part.callAt,
            )
        }
        SaveHold.reached(SaveHold.At.PUBLISHED, recfile)
        // In the ledger before anyone can delete them - see CallLedger for
        // why the missed-call check asks it and not the disk - and before the
        // recorder's copy goes: one that outlives this, its delete not getting
        // through, is only deleted next time. Only when every part was
        // published: with one left on the recorder, another part's entry
        // made the next collection take the whole recording for saved
        // before and delete it, the part that did not publish with it.
        if (!unpublished) {
            published.forEach { (part, seconds) ->
                if (phoneCall) {
                    CallLedger.noteRecording(context, part.startMillis, part.incoming, seconds, part.callAt)
                } else {
                    CallLedger.noteMessenger(context, part.startMillis, part.incoming, seconds)
                }
            }
            // And the calls the rules left out, by the same rule: the own
            // call's entry is the file's start (SaveJournal.Skip).
            noteSkips(context, skips)
        }
        when {
            last == null -> return@withContext Outcome.Failed(UNPUBLISHED)
            // A part that would not publish: the daemon's copy stays,
            // so its audio is not lost - a duplicate of the others later,
            // from the next collection, is better.
            unpublished -> Log.w(TAG, "save: keeping $recfile on the daemon; a part of it was not saved")
            !daemon.delete(context, recfile) ->
                // Saved, and noted in the ledger; the next collection
                // finds it there and only deletes it (saveClaimed).
                Log.w(TAG, "save: could not delete $recfile from the daemon; it goes next time")
        }
        last
    }

    /** The call log is written as a call ends, and the save can get there
     *  first: asked this many times, a second apart. */
    private const val IDENTIFY_TRIES = 8

    /** A file that still cannot be saved after this long is given up on. A
     *  week is far past any transient cause; what is left is a file that
     *  cannot be read, retried at every start forever. */
    private const val GIVE_UP_MS = 7L * 24 * 60 * 60 * 1000

    /** A reconcile left everything because a call was up: the next call's
     *  end kicks the keep-alive for it, under a work name of its own
     *  (RecorderKeepAlive.kickAfterCall). Process-wide - the call's service
     *  stands down between calls, and a flag of its own went with it
     *  (review rounds 25-27). */
    @Volatile
    var reconcileOwed = false

    /**
     * Collect anything the daemon still holds - normally nothing, but a crash or
     * a reboot between hang-up and the fetch can leave a file behind. Safe to
     * call often; it no-ops when there is nothing pending.
     *
     * EVERYTHING PENDING IS SAVED, WHATEVER THE MODE. This used to save only in
     * automatic mode and let on-demand files age out unsaved, on the grounds
     * that saving was the user's call, "made per recording by the Keep/Discard
     * prompt" - a prompt from a design that no longer exists. In on-demand mode
     * the daemon records nothing until the user taps Record, so a file that
     * exists in that mode exists because they asked for it; dropping it threw
     * away exactly the recordings the user had explicitly wanted, whenever the
     * app happened to miss the hang-up.
     */
    suspend fun reconcile(context: Context) {
        // Nothing while a call is up: a phone call's file is not in the log
        // yet - a reconcile's save reads it once - and whatever it finishes or
        // saves posts its notice, muted while the call is up (AOSP
        // NotificationAttentionHelper.disableNotificationEffects), a tone in
        // the earpiece in an app's call outside Telecom (playInCallNotification)
        // - a messenger's file and a save cut short as much as a phone call's
        // (review rounds 10 and 11). The call's end collects them
        // (CallMonitorService). Not at a ring's mode, which another app can
        // leave set: it held the collecting for ever (round 12).
        if (CaptureDaemon.callUp(context)) {
            Log.i(TAG, "reconcile: a call is up; leaving it all for after it")
            // Owed: the call's end kicks for it (CallMonitorService) - a kick
            // made for files a lost recorder left, its run landing in the
            // next call, left them to the heartbeat (review round 25).
            reconcileOwed = true
            return
        }
        val listing = daemon.listing(context)
        // Paid once the recorder has said what it holds - not when it would
        // not answer, nothing then collected (review round 25).
        if (listing != null) reconcileOwed = false
        val listed = listing?.names
        // A save cut short whose recorder copy is gone - killed after the
        // delete, before the journal was told - is put right here, as no
        // save of that file will come (SaveJournal). Only when the recorder
        // answered with a list known whole: an older recorder's, with no end
        // to it, may be cut short unseen, and a file missing from it is no
        // proof it is gone (review round 11).
        if (listed != null && listing.whole) {
            withContext(io) {
                for (stale in SaveJournal.all(context)) {
                    if (stale.recfile in listed || !claim(stale.recfile)) continue
                    try {
                        // Read again under the claim: a save of it may have
                        // ended between the read above and the claim, and
                        // its entry gone with it (review, 2026-11-18).
                        val picked = SaveJournal.of(context, stale.recfile)?.let { pickUp(context, it, recorderHasIt = false) }
                        // Finished here, it is a save like any other: what
                        // follows one follows it - a message's notice too.
                        (picked as? PickedUp.Done)?.saved?.let { saved ->
                            AfterSave.react(context, saved, stale.recfile, saved.incoming, AfterSave.Collector.RECONCILE) {
                                AfterCall.postNotRecorded(context, it)
                            }
                        }
                    } finally {
                        release(stale.recfile)
                    }
                }
            }
        }
        val pending = listed.orEmpty()
        if (listed != null && listing.whole) CallParts.prune(context, listed)
        if (pending.isEmpty()) return
        Log.i(TAG, "reconcile: ${pending.size} pending")
        val groups = CallParts.groups(context)
        // Files by their start, not their name: the recorder renames the
        // end's file at the end (_early), and the group and the service hold
        // the name it began with (review round 17).
        fun startOf(name: String) = DaemonFile.parse(name).start
        val pendingStarts = pending.mapNotNullTo(HashSet()) { startOf(it) }
        val done = HashSet<String>()
        for (recfile in pending) {
            if (recfile in done) continue
            // One call's parts, saved together and told once, as the call's
            // end would have (CallParts; review round 14).
            // A part whose call's other part is still being written - not
            // listed, not saved - while the call's service is up: its end
            // saves them together (review round 15: saved alone, told twice).
            val whole = groups.firstOrNull { g -> g.any { startOf(it) == startOf(recfile) } }
            // Read before what the service holds, which its end writes first
            // (onCallEnded): a message's file is one or the other at every
            // moment, never neither (review round 30).
            val messageSince = com.jemcik.jemrec.capture.CallMonitorService.messageSince
            val heldStarts = com.jemcik.jemrec.capture.CallMonitorService.heldNow.mapNotNullTo(HashSet()) { startOf(it) }
            if (whole != null && whole.any { startOf(it) !in pendingStarts && startOf(it) in heldStarts }) {
                // Only a file the call's service itself holds: any service
                // running, a messenger part saved, one dropped empty, read as
                // still being written (review round 16).
                Log.i(TAG, "reconcile: $recfile is a part of a call still being saved; leaving it to the call's end")
                done += whole
                continue
            }
            // A file the call's service holds itself - its call just over,
            // the end's wake (an am, up to 10 s) on its way: its end saves it,
            // the call log waited for. Taken here, it was read once, before
            // Telecom wrote the entry - the rules on whose calls to keep and
            // the caller's name went unapplied (review round 28; read, not
            // produced).
            // And a message's file, not named till its end: one begun about
            // as the message started (the recorder begins it at the answer,
            // before the message's own wake) - round 29.
            val aMessages = messageSince > 0 && (startOf(recfile) ?: Long.MIN_VALUE) >= messageSince - 60_000
            if (startOf(recfile) in heldStarts || aMessages) {
                Log.i(TAG, "reconcile: $recfile is its call's, still being saved by it; leaving it to the call's end")
                done += recfile
                continue
            }
            val group = whole?.let { g -> g.mapNotNullTo(HashSet()) { startOf(it) } }?.let { s -> pending.filter { startOf(it) in s } }
            if (group != null && group.size > 1) {
                done += group
                val outcomes = saveAll(context, group.map { it to isIncoming(it) }, justEnded = false)
                    .map { (file, outcome) -> Triple(file, isIncoming(file), outcome) }
                // A part's failure is not said here: told at the hang-up, it
                // is counted toward its week as any reconcile's, or every run
                // said it again, with a sound, for a week (review round 15).
                AfterSave.reactToCall(
                    context, outcomes, own = null, AfterSave.Collector.RECONCILE, { AfterCall.postNotRecorded(context, it) },
                    partBy = AfterSave.Collector.PART_LATER,
                ) { AfterCall.postNotRecorded(context, it) }
                continue
            }
            val incoming = isIncoming(recfile)
            val outcome = save(context, recfile, incoming, justEnded = false)
            AfterSave.react(context, outcome, recfile, incoming, AfterSave.Collector.RECONCILE) {
                AfterCall.postNotRecorded(context, it)
            }
        }
    }

    /** Saving [recfile] came right, or to an end: it is no longer failing. */
    internal fun noLongerFailing(context: Context, recfile: String) {
        failingSince(context, recfile, clear = true)
    }

    /** Saving [recfile] failed again, for [reason]: noted, and given up on -
     *  the recorder's copy deleted - a week after the first time. */
    internal suspend fun failedAgain(context: Context, recfile: String, failed: Outcome.Failed) {
        val reason = failed.reason
        if (failed.writing) noteWriteFailed(context, recfile)
        if (clock() - failingSince(context, recfile) > GIVE_UP_MS) {
            Log.w(TAG, "reconcile: giving up on $recfile ($reason)")
            daemon.delete(context, recfile)
            failingSince(context, recfile, clear = true)
            AfterCall.neverSaved(context, recfile)
        } else {
            Log.w(TAG, "reconcile: left $recfile for later ($reason)")
        }
    }

    private const val KEY_FAILING = "save_failing"

    /** The files whose write has failed: saved untagged from then on. */
    private const val KEY_WRITE_FAILED = "save_write_failed"

    private const val NOT_WRITTEN = "The recording could not be written. Is the storage full?"
    private const val UNPUBLISHED = "The recording could not be written."

    /** Whether writing [recfile] has failed before, noting nothing. */
    private fun writeFailedBefore(context: Context, recfile: String): Boolean =
        recfile in com.jemcik.jemrec.Prefs.of(context).getStringSet(KEY_WRITE_FAILED, emptySet()).orEmpty()

    @Synchronized
    private fun noteWriteFailed(context: Context, recfile: String) {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        val all = prefs.getStringSet(KEY_WRITE_FAILED, emptySet()).orEmpty()
        if (recfile !in all) prefs.edit { putStringSet(KEY_WRITE_FAILED, all + recfile) }
    }

    /** When saving [recfile] first failed (noted now, if this is the first);
     *  [clear] forgets it. Kept in the preferences as "recfile|millis". */
    @Synchronized
    private fun failingSince(context: Context, recfile: String, clear: Boolean = false): Long {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        val all = prefs.getStringSet(KEY_FAILING, emptySet()).orEmpty()
        val mine = all.firstOrNull { it.substringBefore('|') == recfile }
        if (clear) {
            if (mine != null) prefs.edit { putStringSet(KEY_FAILING, all - mine) }
            val writes = prefs.getStringSet(KEY_WRITE_FAILED, emptySet()).orEmpty()
            if (recfile in writes) prefs.edit { putStringSet(KEY_WRITE_FAILED, writes - recfile) }
            return 0L
        }
        mine?.substringAfter('|')?.toLongOrNull()?.let { return it }
        val now = clock()
        prefs.edit { putStringSet(KEY_FAILING, all + "$recfile|$now") }
        return now
    }

    /** The starts of the recordings known besides [recfile]: those saved
     *  (CallLedger) and those still waiting on the recorder. */
    private suspend fun otherStarts(context: Context, recfile: String): List<Long> {
        val ledger = com.jemcik.jemrec.calls.CallLedger.read(context)
        // Every recording the ledger knows: one left out, a message's, a
        // messenger's too - a skipped file shielded nothing (review round 16).
        return (daemon.pending(context).orEmpty() - recfile).mapNotNull { startMillis(it) } +
            (ledger.accounted + ledger.messengerRecordings).map { it.startMillis }
    }

    /** The call's start time, from the recorder's name for its file. */
    internal fun startMillis(recfile: String): Long? = DaemonFile.parse(recfile).start

}
