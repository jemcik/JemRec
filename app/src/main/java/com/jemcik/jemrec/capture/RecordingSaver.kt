package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

        // Who it was with - asked only when something needs to know: the
        // rules on whose calls to keep, or a file name with the caller in it.
        // With neither, which is the default, this is skipped entirely and a
        // call is saved exactly as it always was.
        val format = FileNames.format(context)
        val ruled = CallRules.active(context) && rulesApply(context, startedAt.time)
        val call = if (ruled || format != FileNames.Format.DATE) {
            identify(context, startedAt.time, incoming, tries = if (justEnded) IDENTIFY_TRIES else 1)
        } else {
            null
        }
        if (ruled && call != null && !CallRules.keeps(context, call.contactId)) {
            Log.i(TAG, "save: $recfile is a call the rules leave out - deleting it unsaved")
            CaptureDaemon.deleteRecording(context, recfile)
            CallLedger.noteSkipped(context, startedAt.time, incoming, call.seconds)
            // A Calls tab on screen says so now, not at the next open.
            _saved.tryEmit(recfile)
            return Outcome.Skipped
        }

        val name = FileNames.build(format, startedAt.time, incoming, call?.party)
        val target = RecordingStore.open(context, name)
            ?: return Outcome.Failed("There was nowhere to save it.")

        val socket = try {
            CaptureDaemon.fetch(context, recfile)
        } catch (t: Throwable) {
            // Could not even reach the daemon: keep the file, try again later.
            Log.w(TAG, "save: cannot reach the daemon for $recfile, will retry", t)
            target.abandon()
            return Outcome.Failed("The recorder could not be reached.")
        }

        var ogg: OpusOggWriter? = null
        var completed = false
        var gone = false
        try {
            AudioStream(socket).use { stream ->
                if (stream.codecId == GONE) {
                    gone = true
                } else {
                    val config = stream.readPacket()
                    if (config == null || !config.isConfig) {
                        // Empty or malformed - a completed read with nothing in it.
                        Log.w(TAG, "save: $recfile had no config packet")
                        completed = true
                    } else {
                        val writer = OpusOggWriter(target.descriptor.fileDescriptor, config.data)
                        ogg = writer
                        while (true) {
                            val packet = stream.readPacket() ?: break
                            if (packet.isConfig) continue
                            writer.write(packet.data, packet.pts)
                        }
                        completed = true
                    }
                }
            }
        } catch (t: Throwable) {
            // Broke off mid-transfer: ambiguous, so keep the file for a retry.
            Log.e(TAG, "save: transfer of $recfile broke off", t)
        }

        val written = ogg?.packets ?: 0L
        val seconds = ogg?.durationSeconds ?: 0.0
        runCatching { ogg?.close() }

        return when {
            gone -> {
                Log.i(TAG, "save: $recfile is no longer on the daemon - collected already")
                target.abandon()
                Outcome.Gone
            }
            completed && written > 0L -> {
                Log.i(TAG, "save: wrote %d packets, %.1fs -> %s".format(written, seconds, name))
                target.close()
                CaptureDaemon.deleteRecording(context, recfile)
                FileNames.noteDirection(context, name, incoming)
                // Before anyone can delete it: see CallLedger for why the
                // missed-call check asks this and not the disk.
                CallLedger.noteRecording(context, startedAt.time, incoming, seconds.toInt())
                _saved.tryEmit(name)
                Outcome.Saved(name, seconds, target.uri, target.filePath)
            }
            completed -> {
                // Read the whole thing; there was simply no call audio in it.
                Log.w(TAG, "save: no audio in $recfile, discarding")
                target.abandon()
                CaptureDaemon.deleteRecording(context, recfile)
                // Reported to the user as "Call not recorded" already; the
                // missed-call check must not say it a second time.
                CallLedger.noteRecording(context, startedAt.time, incoming, 0)
                Outcome.Empty
            }
            else -> {
                target.abandon()
                Outcome.Failed("Something went wrong while saving it.")
            }
        }
    }

    /** Who a call was with, as the saver needs it. */
    private class Identified(val party: FileNames.Party, val contactId: Long?, val seconds: Int)

    /** The call log is written as a call ends, and the save can get there
     *  first: asked this many times, a second apart. */
    private const val IDENTIFY_TRIES = 8

    /**
     * Who the call was with, from the call log and the contacts. Null without
     * the log, or if it never shows the call - and then the rules keep the
     * recording and the name is the date's: see CallRules, KEPT WHEN UNSURE.
     */
    private suspend fun identify(context: Context, startMillis: Long, incoming: Boolean, tries: Int): Identified? {
        if (!CallLogLookup.granted(context)) return null
        // The calls already recorded, which this one is not.
        val others = CallLedger.read(context).let { it.recordings + it.skipped }
        repeat(tries) { attempt ->
            CallLogLookup.callFor(context, startMillis, incoming, others)?.let { entry ->
                val contact = CallLogLookup.contactFor(context, entry.number, mutableMapOf())
                // Who it was is not known if the contacts could not be asked.
                if (contact.failed) return null
                return Identified(
                    FileNames.Party(entry.number, contact.name ?: entry.name),
                    contact.id,
                    entry.seconds,
                )
            }
            if (attempt < tries - 1) delay(1_000)
        }
        Log.w(TAG, "save: the call log has no entry for the call at $startMillis")
        return null
    }

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
            when (val outcome = save(context, recfile, isIncoming(recfile), justEnded = false)) {
                is Outcome.Saved -> {
                    Log.i(TAG, "reconcile: saved $recfile as ${outcome.name}")
                    // A hang-up whose wake never arrived is still a call that
                    // just ended, and still worth the after-call notification.
                    // AfterCall leaves out anything long past.
                    runCatching { AfterCall.saved(context, outcome, isIncoming(recfile), startMillis(recfile) ?: 0L) }
                        .onFailure { Log.w(TAG, "reconcile: after-call notice failed", it) }
                }
                Outcome.Empty -> Log.i(TAG, "reconcile: dropped empty $recfile")
                Outcome.Gone -> Log.i(TAG, "reconcile: $recfile was collected meanwhile")
                Outcome.Busy -> Log.i(TAG, "reconcile: $recfile is being saved already, leaving it")
                Outcome.Skipped -> Log.i(TAG, "reconcile: $recfile was a call the rules leave out")
                is Outcome.Failed -> if (ageMs(recfile) > GIVE_UP_MS) {
                    Log.w(TAG, "reconcile: giving up on $recfile (${outcome.reason})")
                    CaptureDaemon.deleteRecording(context, recfile)
                } else {
                    Log.w(TAG, "reconcile: left $recfile for later (${outcome.reason})")
                }
            }
        }
    }

    /** The call's start time, from the millis in jemrec_rec_<millis>_<in|out>.dat. */
    internal fun startMillis(recfile: String): Long? =
        recfile.substringAfter("jemrec_rec_", "").substringBefore('_', "").toLongOrNull()

    /** Age of the file, or 0 (treated as fresh) if the name cannot be parsed. */
    private fun ageMs(recfile: String): Long {
        val millis = startMillis(recfile) ?: return 0
        return System.currentTimeMillis() - millis
    }
}
