package com.jemcik.jemrec.recordings

import android.content.Context
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.calls.CallMatch
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.CoverageHistory
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.AudioStream
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.MessageMarks
import com.jemcik.jemrec.capture.OggSink
import com.jemcik.jemrec.capture.OpusOggWriter
import com.jemcik.jemrec.capture.PacketClock
import com.jemcik.jemrec.capture.RecordingStore
import com.jemcik.jemrec.capture.RecordingTags
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
        /** The file's stream (AudioStream); throws if the recorder cannot be reached. */
        suspend fun fetch(context: Context, name: String): Socket

        /** Whether the recorder deleted it. */
        suspend fun delete(context: Context, name: String): Boolean

        /** The files it holds; null when it could not be asked. */
        suspend fun pending(context: Context): List<String>?
    }

    /** The recorder itself. */
    private object Recorder : DaemonFiles {
        override suspend fun fetch(context: Context, name: String): Socket = CaptureDaemon.fetch(context, name)
        override suspend fun delete(context: Context, name: String): Boolean = CaptureDaemon.deleteRecording(context, name)
        override suspend fun pending(context: Context): List<String>? = CaptureDaemon.listPendingOrNull(context)
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
            /** A call the answering machine took: a message. */
            val message: Boolean = false,
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

        /** The answering machine took the call and the caller hung up during
         *  the greeting: nothing was said, nothing is kept. */
        data object GreetingHangUp : Outcome

        /** The answering machine took the call, could not play the greeting,
         *  and hung up: the user must hear of it. */
        data object GreetingFailed : Outcome

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
        withContext(io) {
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
        val file = DaemonFile.parse(recfile)
        val startedAt = Date(file.start ?: clock())

        // A messenger call: not in the phone's call log, so none of what
        // follows applies - not the caller lookup, not the rules on whose
        // calls to keep (the app's own switch is the rule), not the ledger
        // the missed-call check reads.
        val messenger = VoipApps.forRecfile(recfile)
        if (messenger != null) return saveMessenger(context, recfile, startedAt, messenger, file.messengerIncoming)

        when (SavePlan.drop(file, startedAt.time, CallLedger.read(context).accounted)) {
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
            CallTracking.callsIn(CallTracking.calls(context), startedAt.time, incoming)
        } else {
            emptyList()
        }
        if (SavePlan.switchedOff(tracked, CoverageHistory.read(context))) {
            Log.i(TAG, "save: recording was switched off during $recfile; discarding it")
            dropFromDaemon(context, recfile, announce = true)
            return Outcome.Skipped
        }
        // The daemon's other files are calls too: one whose save failed is
        // not in the ledger yet, and its call-log entry must not be taken for
        // this one's.
        val pending = if (ruled || format != FileNames.Format.DATE) {
            CallMatch.pendingRecorded(daemon.pending(context).orEmpty().filter { it != recfile })
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
                dropFromDaemon(context, recfile, announce = true) {
                    CallLedger.noteRecording(context, decision.start, decision.incoming, 0)
                }
                Outcome.NotAnswered
            }
            is SavePlan.Decision.LeaveOut -> {
                Log.i(TAG, "save: $recfile is a call the rules leave out - deleting it unsaved")
                // Announced: a Calls tab on screen says so now, not at the next open.
                dropFromDaemon(context, recfile, announce = true) {
                    decision.leftOut.forEach { CallLedger.noteSkipped(context, it.start, it.incoming, it.seconds) }
                }
                Outcome.Skipped
            }
            is SavePlan.Decision.Save -> {
                if (decision.leftOut.isNotEmpty()) {
                    Log.i(TAG, "save: ${decision.leftOut.size} call(s) in $recfile are ones the rules leave out")
                }
                decision.leftOut.forEach { CallLedger.noteSkipped(context, it.start, it.incoming, it.seconds) }
                val cut = SavePlan.cut(decision, file, startedAt.time, tracked)
                if (cut.notKept > 0) {
                    Log.i(TAG, "save: $recfile - ${cut.notKept} call(s) answered during the message, not kept")
                }
                val clock = PacketClock(startedAt.time, cut.clockOffset)
                val written = write(context, recfile, cut.parts, ledger = true, message = cut.message, clock = clock, orWhole = cut.orWhole) { part ->
                    RecordingTags.Facts(message = cut.message, incoming = part.incoming)
                }
                if (cut.message && written == Outcome.Empty) {
                    // Nothing from the message's start on: the sound the
                    // recorder took for the caller came as the line went down
                    // (measured on the Android 12 emulator, 2026-10-22). The
                    // caller left before the beep, not a silent recording.
                    // The recorder's copy went with the empty read.
                    Log.i(TAG, "save: $recfile - nothing after the message's start; the caller hung up during the greeting")
                    CallLedger.noteGreetingHangUp(context, startedAt.time, incoming)
                    _saved.tryEmit(recfile)
                    Outcome.GreetingHangUp
                } else {
                    written
                }
            }
        }
    }

    /**
     * A recording dropped unsaved: deleted from the recorder, [note]d in the
     * ledger, and [announce]d - the Calls tab on screen then says what
     * became of the call.
     */
    private suspend fun dropFromDaemon(context: Context, recfile: String, announce: Boolean, note: () -> Unit = {}) {
        daemon.delete(context, recfile)
        note()
        if (announce) _saved.tryEmit(recfile)
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
    ): Outcome {
        val note = VoipCallNotes.find(context, app.pkg, startedAt.time, incoming)
        val format = FileNames.format(context)
        val name = FileNames.buildVoip(format, startedAt.time, note?.incoming, app.tag, note?.name)
        val outcome = write(context, recfile, listOf(Part.whole(name, note?.incoming ?: false, startedAt.time)), ledger = false) {
            RecordingTags.Facts(incoming = note?.incoming, caller = note?.name)
        }
        // Where the name does not say who: the date and number formats, or a
        // caller with nothing a file name can take.
        if (outcome is Outcome.Saved && note?.name != null && FileNames.parse(name)?.caller == null) {
            FileNames.noteCaller(context, name, note.name)
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
        )

        companion object {
            fun whole(name: String, incoming: Boolean, startMillis: Long) =
                Part(name, incoming, startMillis, listOf(0L until Long.MAX_VALUE))
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

        /** Copy the finished file into place, with [facts] in it. */
        fun copyIn(facts: RecordingTags.Facts) {
            val from = work ?: return
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
        var read = WriteOutcome.Read.BROKE_OFF
            private set

        fun from(socket: Socket) {
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
                if (stream == null) read = WriteOutcome.Read.COMPLETE
                stream?.use { stream ->
                    if (stream.codecId == GONE) {
                        read = WriteOutcome.Read.GONE
                    } else {
                        val config = stream.readPacket()
                        if (config == null || !config.isConfig) {
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
                                val part = parts.firstOrNull { it.holds(slot) }
                                if (part == null) continue
                                val into = open[part] ?: run {
                                    val target = targets(context, part.name)
                                    if (target == null) {
                                        nowhere = true
                                        null
                                    } else {
                                        open(part, target, config.data).also { open[part] = it }
                                    }
                                } ?: break
                                if (into.firstSlot < 0) into.firstSlot = slot
                                into.lastSlot = slot
                                into.writer.write(packet.data, packet.pts)
                            }
                            read = if (nowhere) WriteOutcome.Read.NOWHERE else WriteOutcome.Read.COMPLETE
                        }
                    }
                }
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
     * [ledger]: note each saved part for the missed-call check - phone calls
     * only. [facts]: what each part's file says of itself (RecordingTags).
     * [orWhole]: saved into instead when no part held any of the file's
     * packets (SavePlan.wholeIfNothingFits).
     */
    private suspend fun write(
        context: Context,
        recfile: String,
        parts: List<Part>,
        ledger: Boolean,
        message: Boolean = false,
        clock: PacketClock? = null,
        orWhole: Part? = null,
        facts: (Part) -> RecordingTags.Facts,
    ): Outcome {
        val socket = try {
            daemon.fetch(context, recfile)
        } catch (t: Throwable) {
            // Could not even reach the daemon: keep the file, try again later.
            Log.w(TAG, "save: cannot reach the daemon for $recfile, will retry", t)
            return Outcome.Failed("The recorder could not be reached.")
        }

        // Tagged, unless saving this file has failed before: a work copy and
        // then the copy into place need its size free twice, where writing
        // into place needs it once, and a file that cannot be saved is given
        // up on after a week. Untagged, it is saved as it always was; its
        // marks are in the preferences still, and only a reinstall loses them.
        val transfer = Transfer(context, recfile, parts, clock, tagged = !failedBefore(context, recfile))
        transfer.from(socket)
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
        // Into place, tags and all - only what will be published: a copy that
        // fails is a file not written, and the daemon's copy is kept for it.
        val copied = finished && transfer.read == WriteOutcome.Read.COMPLETE && anything && open.values.all { o ->
            o.writer.packets == 0L || runCatching { o.copyIn(facts(o.part)) }
                .onFailure { Log.e(TAG, "save: a file from $recfile could not be copied into place", it) }
                .isSuccess
        }
        open.values.forEach { it.discardWork() }

        val whole = orWhole?.takeIf { transfer.seen > 0 }
        return when (WriteOutcome.of(transfer.read, anything, copied, whole != null)) {
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
                Outcome.Failed("The recording could not be written. Is the storage full?")
            }
            WriteOutcome.Ending.PUBLISH -> publish(context, recfile, counts, ledger, message)
            WriteOutcome.Ending.WHOLE -> {
                Log.w(TAG, "save: none of the ${transfer.seen} packets of $recfile fell inside the call Telecom reported; saving it whole")
                open.values.forEach { it.target.abandon() }
                write(context, recfile, listOf(checkNotNull(whole)), ledger, message, facts = facts)
            }
            WriteOutcome.Ending.EMPTY -> {
                // Read the whole thing; there was simply no call audio in it.
                // Noted in the ledger only where the user is told
                // (CallMonitorService): collected later, it is the missed-call
                // check that says so.
                Log.w(TAG, "save: no audio in $recfile, discarding")
                open.values.forEach { it.target.abandon() }
                dropFromDaemon(context, recfile, announce = false)
                Outcome.Empty
            }
            WriteOutcome.Ending.BROKE_OFF -> {
                open.values.forEach { it.target.abandon() }
                Outcome.Failed("Something went wrong while saving it.")
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
        ledger: Boolean,
        message: Boolean,
    ): Outcome = withContext(NonCancellable) {
        // From the first publish to the daemon's delete, one piece: a
        // cancellation between them left a saved recording on the
        // daemon too, and the next reconcile saved it again as "(1)".
        var last: Outcome.Saved? = null
        var unpublished = false
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
            if (ledger) {
                FileNames.noteDirection(context, o.part.name, o.part.incoming)
                // Before anyone can delete it: see CallLedger for why the
                // missed-call check asks this and not the disk.
                CallLedger.noteRecording(context, o.part.startMillis, o.part.incoming, seconds.toInt())
            }
            if (message) MessageMarks.mark(context, o.part.name)
            _saved.tryEmit(o.part.name)
            last = Outcome.Saved(
                o.part.name, seconds, o.target.uri, o.target.filePath, o.part.incoming, o.part.startMillis,
                message = message,
            )
        }
        when {
            last == null -> return@withContext Outcome.Failed("The recording could not be written.")
            // A part that would not publish: the daemon's copy stays,
            // so its audio is not lost - a duplicate later is better.
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
        val pending = daemon.pending(context).orEmpty()
        if (pending.isEmpty()) return
        Log.i(TAG, "reconcile: ${pending.size} pending")
        for (recfile in pending) {
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
    internal suspend fun failedAgain(context: Context, recfile: String, reason: String) {
        if (clock() - failingSince(context, recfile) > GIVE_UP_MS) {
            Log.w(TAG, "reconcile: giving up on $recfile ($reason)")
            daemon.delete(context, recfile)
            failingSince(context, recfile, clear = true)
        } else {
            Log.w(TAG, "reconcile: left $recfile for later ($reason)")
        }
    }

    private const val KEY_FAILING = "save_failing"

    /** Whether saving [recfile] has failed before, noting nothing. */
    private fun failedBefore(context: Context, recfile: String): Boolean =
        com.jemcik.jemrec.Prefs.of(context).getStringSet(KEY_FAILING, emptySet()).orEmpty()
            .any { it.substringBefore('|') == recfile }

    /** When saving [recfile] first failed (noted now, if this is the first);
     *  [clear] forgets it. Kept in the preferences as "recfile|millis". */
    @Synchronized
    private fun failingSince(context: Context, recfile: String, clear: Boolean = false): Long {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        val all = prefs.getStringSet(KEY_FAILING, emptySet()).orEmpty()
        val mine = all.firstOrNull { it.substringBefore('|') == recfile }
        if (clear) {
            if (mine != null) prefs.edit { putStringSet(KEY_FAILING, all - mine) }
            return 0L
        }
        mine?.substringAfter('|')?.toLongOrNull()?.let { return it }
        val now = clock()
        prefs.edit { putStringSet(KEY_FAILING, all + "$recfile|$now") }
        return now
    }

    /** The call's start time, from the recorder's name for its file. */
    internal fun startMillis(recfile: String): Long? = DaemonFile.parse(recfile).start

}
