package com.jemcik.jemrec.capture

import android.content.Context
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Telecom's own account of each phone call, as it happens - see CallTracking.
 *
 * Bound by Telecom for the length of each call, and only while exact call
 * tracking is on: the daemon allows MANAGE_ONGOING_CALLS for this app's uid
 * then, and takes it back when it is turned off. Being bound also keeps this
 * process at the front while a call is up, which is what lets the end of a
 * call be acted on here rather than only through the daemon's wake.
 *
 * It reads; it never answers, holds or ends a call. Messenger calls are not
 * here: they are self-managed, and Telecom keeps them from a service like
 * this one unless it asks, which it does not.
 */
class CallTracker : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed(call)
    }

    /** The calls the rules left out, so their end is noted as such. */
    private val leftOut = mutableSetOf<Long>()

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        // Switched off, and bound all the same - the daemon could not be told
        // to withdraw the permission yet: off means off, nothing is kept.
        if (!CallTracking.enabled(this)) return
        changed(call)
        decideEarly(call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        val id = call.details.creationTimeMillis
        wasActive -= id
        val wasLeftOut = leftOut.remove(id)
        if (wasLeftOut) {
            // A left-out call that rang and was never answered never went
            // off-hook, so the daemon's skip is still waiting for it - and
            // would take the next call, one the rules keep, instead. Taken
            // back whether or not tracking is still on.
            skips.trySend(applicationContext to false)
        }
        if (!CallTracking.enabled(this)) return
        val ended = System.currentTimeMillis()
        CallTracking.update(this, id) { old ->
            val base = old ?: track(call)
            base.copy(endedAt = base.endedAt.takeIf { it > 0 } ?: ended)
        }
        Log.i(TAG, "calltrack: call ended")
        if (wasLeftOut) {
            val tracked = CallTracking.calls(this).firstOrNull { it.id == id }
            CallLedger.noteSkipped(this, id, tracked?.incoming ?: true, tracked?.seconds ?: 0)
        }
        if (calls.isEmpty()) collectSoon()
    }

    /** A state change: note an answer or a hang-up. */
    private fun changed(call: Call) {
        if (!CallTracking.enabled(this)) return
        val id = call.details.creationTimeMillis
        val now = System.currentTimeMillis()
        CallTracking.update(this, id) { old ->
            val base = old ?: track(call)
            when (call.state) {
                Call.STATE_ACTIVE ->
                    // The first answer from Telecom's own connect time, which
                    // is to the millisecond; a return from hold from now.
                    if (base.activations.isEmpty()) {
                        base.copy(activations = listOf(call.details.connectTimeMillis.takeIf { it > 0 } ?: now))
                    } else if (old == null || wasActive[id] == true) {
                        // New here, and track() already took Telecom's
                        // connect time; or ACTIVE again with no hold between.
                        base
                    } else {
                        base.copy(activations = base.activations + now)
                    }
                Call.STATE_DISCONNECTED -> base.copy(endedAt = base.endedAt.takeIf { it > 0 } ?: now)
                else -> base
            }
        }
        wasActive[id] = call.state == Call.STATE_ACTIVE
    }

    /** Whether each call was active at its last change, so a repeat of
     *  ACTIVE is not counted as a second answer. */
    private val wasActive = mutableMapOf<Long, Boolean>()

    private fun track(call: Call): CallTracking.Tracked = CallTracking.Tracked(
        id = call.details.creationTimeMillis,
        incoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING,
        // The number only with the call-log permission: the same consent the
        // call log needs, for the same information.
        number = if (CallLogLookup.granted(this)) call.details.handle?.schemeSpecificPart else null,
        // Already answered, if Telecom says so: a call first seen here late -
        // tracking switched on mid-call, a service bound mid-call - is not
        // then taken for one nobody answered, and deleted as such.
        activations = listOfNotNull(call.details.connectTimeMillis.takeIf { it > 0 }),
        endedAt = 0L,
    )

    /**
     * Whose calls to keep, before recording: a call the rules leave out is
     * known while it still rings (or as it is dialled), so the daemon is told
     * not to record it at all. Only for a call on its own - with another one
     * already up, the recording is shared and the saver splits it instead -
     * and only in automatic mode, where the rules apply. And only with the
     * call log and the contacts, as everywhere else that decides who a call
     * was with: without them a call is kept.
     *
     * Not either straight after another call: the daemon records on for the
     * two seconds it takes to be sure a call is over, and a call that goes
     * off-hook in them joins that recording. A skip would then stop it - the
     * kept call's audio with it - where the saver splits the two instead.
     */
    private fun decideEarly(call: Call) {
        val context = applicationContext
        if (calls.size > 1) return
        val id = call.details.creationTimeMillis
        val now = System.currentTimeMillis()
        if (CallTracking.calls(context).any { it.id != id && it.endedAt > now - RECENT_END_MS }) return
        if (!CallRules.active(context)) return
        if (!CallLogLookup.granted(context) || !CallLogLookup.photosGranted(context)) return
        if (!RecorderSwitch.isOn(context) || RecordingMode.of(context) != RecordingMode.AUTOMATIC) return
        val number = call.details.handle?.schemeSpecificPart
        val contact = CallLogLookup.contactFor(context, number, mutableMapOf())
        if (contact.failed || CallRules.keeps(context, contact.ids)) return
        leftOut += id
        Log.i(TAG, "calltrack: the rules leave this call out; telling the recorder")
        skips.trySend(context to true)
    }

    /**
     * The last call is over: collect what the daemon recorded, a few seconds
     * on, once it has finished the file. The daemon's own wake does the same
     * and usually first; this is for the times its wake does not land.
     */
    private fun collectSoon() {
        val context = applicationContext
        scope.launch {
            delay(COLLECT_AFTER_MS)
            runCatching { RecordingSaver.reconcile(context) }
                .onFailure { Log.w(TAG, "calltrack: collecting after the call failed", it) }
        }
    }

    private companion object {
        const val TAG = "JemRec"
        const val COLLECT_AFTER_MS = 5_000L

        /** Another call ended this recently: no skip (see decideEarly).
         *  Past the daemon's 2 s end check, with room to spare. */
        const val RECENT_END_MS = 10_000L

        /** Not tied to the service: Telecom unbinds it the moment the last
         *  call goes, and the collection after it must still run. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Skips (true) and their take-backs (false), to the daemon one at a
         * time and in the order they were decided. Launched separately, the
         * take-back of a call hung up at once could land first - and leave the
         * skip armed for the next call, one the rules keep.
         */
        val skips = Channel<Pair<Context, Boolean>>(Channel.UNLIMITED).also { channel ->
            scope.launch {
                for ((context, skip) in channel) {
                    if (!CaptureDaemon.skipCall(context, skip) && skip) {
                        Log.w(TAG, "calltrack: the recorder did not take the skip; the saver will delete it instead")
                    }
                }
            }
        }
    }
}
