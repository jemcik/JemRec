package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
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

    sealed interface Outcome {
        /** Muxed and saved; the daemon copy has been deleted. */
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
        ) : Outcome

        /** The file was read in full but held no audio; deleted rather than kept. */
        data object Empty : Outcome

        /** The daemon no longer has it: the other collector got there first.
         *  Not news - the recording is saved, just not by this call. */
        data object Gone : Outcome

        /** This process is already saving this very file. Leave it to that. */
        data object Busy : Outcome

        /** A call the user's rules leave out (see CallRules): deleted from the
         *  daemon, never saved. Not a failure, and nothing to announce. */
        data object Skipped : Outcome

        /** An outgoing call nobody answered: the recording was only ringing,
         *  by Telecom's own account (CallTracking). Deleted, not saved. */
        data object NotAnswered : Outcome

        /** Could not fetch or the read broke off; the daemon copy is LEFT for a
         *  later retry. */
        data class Failed(val reason: String) : Outcome
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
    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    /** A recording appeared or went away somewhere the list cannot see -
     *  deleted from the after-call notification, say. Same flow: the list
     *  only needs to know to read the disk again. */
    fun announce(name: String) {
        _saved.tryEmit(name)
    }

    /** Whether a daemon file name is for an incoming call, from its "_in." / "_out." tag. */
    fun isIncoming(recfile: String): Boolean = RecordingStore.isIncoming(recfile)

    /**
     * [justEnded]: the call hung up moments ago, so its call-log entry may
     * still be on its way and is worth waiting a few seconds for. A reconcile
     * collects calls whose entry is either in the log by now or never will be.
     */
    suspend fun save(context: Context, recfile: String, incoming: Boolean, justEnded: Boolean = true): Outcome =
        withContext(Dispatchers.IO) {
            if (!claim(recfile)) return@withContext Outcome.Busy
            try {
                saveClaimed(context, recfile, incoming, justEnded)
            } finally {
                release(recfile)
            }
        }

    private suspend fun saveClaimed(context: Context, recfile: String, incoming: Boolean, justEnded: Boolean): Outcome {
        // Named for when the CALL happened, taken from the daemon file, so a
        // recording collected late by the reconcile still carries the right
        // time rather than the time it was picked up.
        val startedAt = Date(startMillis(recfile) ?: System.currentTimeMillis())

        // A messenger call: not in the phone's call log, so none of what
        // follows applies - not the caller lookup, not the rules on whose
        // calls to keep (the app's own switch is the rule), not the ledger
        // the missed-call check reads.
        val messenger = VoipApps.forRecfile(recfile)
        if (messenger != null) return saveMessenger(context, recfile, startedAt, messenger)

        // Who it was with - asked only when something needs to know: the
        // rules on whose calls to keep, or a file name with the caller in it.
        // With neither, which is the default, this is skipped entirely and a
        // call is saved exactly as it always was.
        // Saved once already, and the daemon's copy outlived it - a delete
        // that did not get through. Not again: the second would be "(1)".
        if (CallLedger.read(context).recordings.any { it.startMillis == startedAt.time }) {
            Log.i(TAG, "save: $recfile was saved before; deleting the daemon's copy")
            CaptureDaemon.deleteRecording(context, recfile)
            return Outcome.Gone
        }

        val format = FileNames.format(context)
        val ruled = CallRules.active(context) && rulesApply(context, startedAt.time)

        // Exact call tracking: Telecom's own account of the calls this
        // recording holds. Without it - switched off, or Telecom told us
        // nothing about this one - the call log is the one way to know.
        val tracked = if (CallTracking.enabled(context)) {
            CallTracking.callsIn(CallTracking.calls(context), startedAt.time, incoming)
        } else {
            emptyList()
        }
        // Switched off during the call: the hang-up drops such a recording
        // (CallMonitorService), and so does this, when the hang-up's wake did
        // not land - the two must agree. Telecom says when the call ended.
        val ended = tracked.firstOrNull()?.endedAt?.takeIf { it > 0 }
        if (ended != null && CoverageHistory.stateAt(CoverageHistory.read(context), ended) == CoverageHistory.State.OFF) {
            Log.i(TAG, "save: recording was switched off during $recfile; discarding it")
            CaptureDaemon.deleteRecording(context, recfile)
            _saved.tryEmit(recfile)
            return Outcome.Skipped
        }
        // The daemon's other files are calls too: one whose save failed is
        // not in the ledger yet, and its call-log entry must not be taken for
        // this one's.
        val pending = if (ruled || format != FileNames.Format.DATE) {
            CaptureDaemon.listPendingOrNull(context).orEmpty()
                .filter { it != recfile && VoipApps.forRecfile(it) == null }
                .mapNotNull { name -> startMillis(name)?.let { CallLedger.Recorded(it, RecordingStore.isIncoming(name), -1) } }
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
        )
        return when (decision) {
            is SavePlan.Decision.NotAnswered -> {
                Log.i(TAG, "save: $recfile was never answered - only ringing, not saved")
                CaptureDaemon.deleteRecording(context, recfile)
                CallLedger.noteRecording(context, decision.start, decision.incoming, 0)
                _saved.tryEmit(recfile)
                Outcome.NotAnswered
            }
            is SavePlan.Decision.LeaveOut -> {
                Log.i(TAG, "save: $recfile is a call the rules leave out - deleting it unsaved")
                CaptureDaemon.deleteRecording(context, recfile)
                decision.leftOut.forEach { CallLedger.noteSkipped(context, it.start, it.incoming, it.seconds) }
                // A Calls tab on screen says so now, not at the next open.
                _saved.tryEmit(recfile)
                Outcome.Skipped
            }
            is SavePlan.Decision.Save -> {
                if (decision.leftOut.isNotEmpty()) {
                    Log.i(TAG, "save: ${decision.leftOut.size} call(s) in $recfile are ones the rules leave out")
                }
                decision.leftOut.forEach { CallLedger.noteSkipped(context, it.start, it.incoming, it.seconds) }
                write(context, recfile, decision.parts, ledger = true)
            }
        }
    }

    /** The phone behind SavePlan: the contacts and the call log. */
    private class PhoneLookups(
        private val context: Context,
        private val pending: List<CallLedger.Recorded>,
    ) : SavePlan.Lookups {
        private val contacts = mutableMapOf<String, CallLogLookup.Contact>()

        /** The calls already recorded or left out, or still on the daemon,
         *  which this one is not. */
        private val others by lazy { CallLedger.read(context).let { it.recordings + it.skipped } + pending }

        override val callLogAllowed: Boolean get() = CallLogLookup.granted(context)
        override fun contact(number: String?) = CallLogLookup.contactFor(context, number, contacts)
        override fun logged(start: Long, incoming: Boolean) = CallLogLookup.callFor(context, start, incoming, others)
        override fun loggedBetween(from: Long, to: Long) = CallLogLookup.entriesBetween(context, from, to)
        override suspend fun pause() = delay(1_000)
    }

    /** A messenger call, named for its app and - when its notification said
     *  - its caller and direction. See VoipCallNotes. */
    private suspend fun saveMessenger(
        context: Context,
        recfile: String,
        startedAt: Date,
        app: VoipApps.App,
    ): Outcome {
        val note = VoipCallNotes.find(context, app.pkg, startedAt.time)
        val format = FileNames.format(context)
        val name = FileNames.buildVoip(format, startedAt.time, note?.incoming, app.tag, note?.name)
        val outcome = write(context, recfile, listOf(Part.whole(name, note?.incoming ?: false, startedAt.time)), ledger = false)
        if (outcome is Outcome.Saved && note?.name != null && format == FileNames.Format.DATE) {
            FileNames.noteCaller(context, name, note.name)
        }
        return outcome
    }

    /**
     * One file a daemon recording is saved into, and which of its packets -
     * 20 ms each, counted from the first - go there. Usually one part with
     * all of them; see SavePlan for the others.
     */
    internal class Part(
        val name: String,
        val incoming: Boolean,
        /** For the ledger: when this part's call began. */
        val startMillis: Long,
        val packets: List<LongRange>,
    ) {
        fun holds(index: Long): Boolean = packets.any { index in it }

        companion object {
            fun whole(name: String, incoming: Boolean, startMillis: Long) =
                Part(name, incoming, startMillis, listOf(0L until Long.MAX_VALUE))
        }
    }

    /** A part being written: where to, and the muxer. */
    private class Open(val part: Part, val target: RecordingStore.Target, val writer: OpusOggWriter)

    /**
     * Fetch the daemon's file and mux it into [parts], each packet into the
     * part that holds it; a packet none holds is dropped. A part's file is
     * opened at its first packet, so a part with none leaves nothing behind.
     * [ledger]: note each saved part for the missed-call check - phone calls
     * only.
     */
    private suspend fun write(
        context: Context,
        recfile: String,
        parts: List<Part>,
        ledger: Boolean,
    ): Outcome {
        val socket = try {
            CaptureDaemon.fetch(context, recfile)
        } catch (t: Throwable) {
            // Could not even reach the daemon: keep the file, try again later.
            Log.w(TAG, "save: cannot reach the daemon for $recfile, will retry", t)
            return Outcome.Failed("The recorder could not be reached.")
        }

        val open = LinkedHashMap<Part, Open>()
        var completed = false
        var gone = false
        var nowhere = false
        try {
            val stream = try {
                AudioStream(socket)
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
            if (stream == null) completed = true
            stream?.use { stream ->
                if (stream.codecId == GONE) {
                    gone = true
                } else {
                    val config = stream.readPacket()
                    if (config == null || !config.isConfig) {
                        // Empty or malformed - a completed read with nothing in it.
                        Log.w(TAG, "save: $recfile had no config packet")
                        completed = true
                    } else {
                        var index = 0L
                        while (true) {
                            val packet = stream.readPacket() ?: break
                            if (packet.isConfig) continue
                            val part = parts.firstOrNull { it.holds(index) }
                            index++
                            if (part == null) continue
                            val into = open[part] ?: run {
                                val target = RecordingStore.open(context, part.name)
                                if (target == null) {
                                    nowhere = true
                                    null
                                } else {
                                    // A writer that cannot be made must not
                                    // leave its target open behind it.
                                    val writer = try {
                                        OpusOggWriter(target.descriptor.fileDescriptor, config.data)
                                    } catch (t: Throwable) {
                                        target.abandon()
                                        throw t
                                    }
                                    Open(part, target, writer).also { open[part] = it }
                                }
                            } ?: break
                            into.writer.write(packet.data, packet.pts)
                        }
                        completed = !nowhere
                    }
                }
            }
        } catch (t: Throwable) {
            // Broke off mid-transfer: ambiguous, so keep the file for a retry.
            Log.e(TAG, "save: transfer of $recfile broke off", t)
        }

        val written = open.values.map { it to (it.writer.packets to it.writer.durationSeconds) }
        // Every file finished before any is published: one that would not
        // finish is unplayable, and the daemon's copy is then the only good
        // one - kept for a retry, not deleted as saved.
        val finished = open.values.map { runCatching { it.writer.finish() }.getOrDefault(false) }.all { it }
        val anything = written.any { (_, counts) -> counts.first > 0L }

        return when {
            gone -> {
                Log.i(TAG, "save: $recfile is no longer on the daemon - collected already")
                open.values.forEach { it.target.abandon() }
                Outcome.Gone
            }
            nowhere -> {
                open.values.forEach { it.target.abandon() }
                Outcome.Failed("There was nowhere to save it.")
            }
            completed && anything && !finished -> {
                Log.e(TAG, "save: a file from $recfile could not be finished; keeping the daemon's copy")
                open.values.forEach { it.target.abandon() }
                Outcome.Failed("The recording could not be written. Is the storage full?")
            }
            completed && anything -> withContext(NonCancellable) {
                // From the first publish to the daemon's delete, one piece: a
                // cancellation between them left a saved recording on the
                // daemon too, and the next reconcile saved it again as "(1)".
                var last: Outcome.Saved? = null
                var unpublished = false
                for ((o, counts) in written) {
                    val (packets, seconds) = counts
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
                    // The recording's daemon name, not its own: with the
                    // caller in it, a file name is a name and a number, and
                    // logcat is no place for those.
                    Log.i(TAG, "save: wrote %d packets, %.1fs from %s".format(packets, seconds, recfile))
                    if (ledger) {
                        FileNames.noteDirection(context, o.part.name, o.part.incoming)
                        // Before anyone can delete it: see CallLedger for why the
                        // missed-call check asks this and not the disk.
                        CallLedger.noteRecording(context, o.part.startMillis, o.part.incoming, seconds.toInt())
                    }
                    _saved.tryEmit(o.part.name)
                    last = Outcome.Saved(o.part.name, seconds, o.target.uri, o.target.filePath, o.part.incoming, o.part.startMillis)
                }
                when {
                    last == null -> return@withContext Outcome.Failed("The recording could not be written.")
                    // A part that would not publish: the daemon's copy stays,
                    // so its audio is not lost - a duplicate later is better.
                    unpublished -> Log.w(TAG, "save: keeping $recfile on the daemon; a part of it was not saved")
                    !CaptureDaemon.deleteRecording(context, recfile) ->
                        // Saved, and noted in the ledger; the next collection
                        // finds it there and only deletes it (saveClaimed).
                        Log.w(TAG, "save: could not delete $recfile from the daemon; it goes next time")
                }
                last
            }
            completed -> {
                // Read the whole thing; there was simply no call audio in it.
                // Noted in the ledger only where the user is told
                // (CallMonitorService): collected later, it is the missed-call
                // check that says so.
                Log.w(TAG, "save: no audio in $recfile, discarding")
                open.values.forEach { it.target.abandon() }
                CaptureDaemon.deleteRecording(context, recfile)
                Outcome.Empty
            }
            else -> {
                open.values.forEach { it.target.abandon() }
                Outcome.Failed("Something went wrong while saving it.")
            }
        }
    }

    /** The call log is written as a call ends, and the save can get there
     *  first: asked this many times, a second apart. */
    private const val IDENTIFY_TRIES = 8

    /**
     * Whether the rules get a say in this call. Not when it was recorded
     * because the user tapped Record - ask-first mode at the time - since no
     * rule overrides asking for it. And not without the contacts, which say
     * who is who: the rules stand down rather than take every contact for a
     * stranger.
     */
    private fun rulesApply(context: Context, startMillis: Long): Boolean {
        if (!CallLogLookup.photosGranted(context)) return false
        val state = CoverageHistory.stateAt(CoverageHistory.read(context), startMillis)
            ?: CoverageHistory.current(context)
        return state != CoverageHistory.State.ASK_FIRST
    }

    /** A file that still cannot be saved after this long is given up on. A
     *  week is far past any transient cause; what is left is a file that
     *  cannot be read, retried at every start forever. */
    private const val GIVE_UP_MS = 7L * 24 * 60 * 60 * 1000

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
        val pending = CaptureDaemon.listPending(context)
        if (pending.isEmpty()) return
        Log.i(TAG, "reconcile: ${pending.size} pending")
        for (recfile in pending) {
            val outcome = save(context, recfile, isIncoming(recfile), justEnded = false)
            if (outcome !is Outcome.Failed && outcome != Outcome.Busy) failingSince(context, recfile, clear = true)
            when (outcome) {
                is Outcome.Saved -> {
                    Log.i(TAG, "reconcile: saved $recfile")
                    // A hang-up whose wake never arrived is still a call that
                    // just ended, and still worth the after-call notification.
                    // AfterCall leaves out anything long past.
                    runCatching { AfterCall.saved(context, outcome, outcome.incoming, outcome.startMillis) }
                        .onFailure { Log.w(TAG, "reconcile: after-call notice failed", it) }
                }
                Outcome.Empty -> Log.i(TAG, "reconcile: dropped empty $recfile")
                Outcome.Gone -> Log.i(TAG, "reconcile: $recfile was collected meanwhile")
                Outcome.Busy -> Log.i(TAG, "reconcile: $recfile is being saved already, leaving it")
                Outcome.Skipped -> Log.i(TAG, "reconcile: $recfile was a call the rules leave out")
                Outcome.NotAnswered -> Log.i(TAG, "reconcile: $recfile was never answered")
                // A week of failing, from the first failure - not from the
                // call: a file stranded on the daemon for a week (a reboot,
                // no Wi-Fi to bring the recorder back) is not given up on its
                // first try.
                is Outcome.Failed -> if (System.currentTimeMillis() - failingSince(context, recfile) > GIVE_UP_MS) {
                    Log.w(TAG, "reconcile: giving up on $recfile (${outcome.reason})")
                    CaptureDaemon.deleteRecording(context, recfile)
                    failingSince(context, recfile, clear = true)
                } else {
                    Log.w(TAG, "reconcile: left $recfile for later (${outcome.reason})")
                }
            }
        }
    }

    private const val KEY_FAILING = "save_failing"

    /** When saving [recfile] first failed (noted now, if this is the first);
     *  [clear] forgets it. Kept in the preferences as "recfile|millis". */
    @Synchronized
    private fun failingSince(context: Context, recfile: String, clear: Boolean = false): Long {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        val all = prefs.getStringSet(KEY_FAILING, emptySet()).orEmpty()
        val mine = all.firstOrNull { it.substringBefore('|') == recfile }
        if (clear) {
            if (mine != null) prefs.edit().putStringSet(KEY_FAILING, all - mine).apply()
            return 0L
        }
        mine?.substringAfter('|')?.toLongOrNull()?.let { return it }
        val now = System.currentTimeMillis()
        prefs.edit().putStringSet(KEY_FAILING, all + "$recfile|$now").apply()
        return now
    }

    /** The call's start time, from the millis in jemrec_rec_<millis>_<in|out>.dat. */
    internal fun startMillis(recfile: String): Long? =
        recfile.substringAfter("jemrec_rec_", "").substringBefore('_', "").toLongOrNull()

}
