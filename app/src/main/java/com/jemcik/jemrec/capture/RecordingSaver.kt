package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
        data class Saved(val name: String, val seconds: Double) : Outcome

        /** The file was read in full but held no audio; deleted rather than kept. */
        data object Empty : Outcome

        /** The daemon no longer has it: the other collector got there first.
         *  Not news - the recording is saved, just not by this call. */
        data object Gone : Outcome

        /** This process is already saving this very file. Leave it to that. */
        data object Busy : Outcome

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

    /** Whether a daemon file name is for an incoming call, from its "_in." / "_out." tag. */
    fun isIncoming(recfile: String): Boolean = RecordingStore.isIncoming(recfile)

    suspend fun save(context: Context, recfile: String, incoming: Boolean): Outcome =
        withContext(Dispatchers.IO) {
            if (!claim(recfile)) return@withContext Outcome.Busy
            try {
                saveClaimed(context, recfile, incoming)
            } finally {
                release(recfile)
            }
        }

    private suspend fun saveClaimed(context: Context, recfile: String, incoming: Boolean): Outcome {
        // Named for when the CALL happened, taken from the daemon file, so a
        // recording collected late by the reconcile still carries the right
        // time rather than the time it was picked up.
        val startedAt = Date(startMillis(recfile) ?: System.currentTimeMillis())
        val name = RecordingStore.nameFor(startedAt, incoming)
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
                _saved.tryEmit(name)
                Outcome.Saved(name, seconds)
            }
            completed -> {
                // Read the whole thing; there was simply no call audio in it.
                Log.w(TAG, "save: no audio in $recfile, discarding")
                target.abandon()
                CaptureDaemon.deleteRecording(context, recfile)
                Outcome.Empty
            }
            else -> {
                target.abandon()
                Outcome.Failed("Something went wrong while saving it.")
            }
        }
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
            when (val outcome = save(context, recfile, isIncoming(recfile))) {
                is Outcome.Saved -> Log.i(TAG, "reconcile: saved $recfile as ${outcome.name}")
                Outcome.Empty -> Log.i(TAG, "reconcile: dropped empty $recfile")
                Outcome.Gone -> Log.i(TAG, "reconcile: $recfile was collected meanwhile")
                Outcome.Busy -> Log.i(TAG, "reconcile: $recfile is being saved already, leaving it")
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
