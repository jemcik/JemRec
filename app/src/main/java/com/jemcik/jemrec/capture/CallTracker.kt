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
 * It reads; it never answers, holds or ends a call - the answering machine is
 * the recorder's doing, on a go-ahead from here (decideAnswer). Messenger calls are not
 * here: they are self-managed, and Telecom keeps them from a service like
 * this one unless it asks, which it does not.
 */
class CallTracker : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed(call)
    }

    /** The calls the rules left out, so their end is noted as such. */
    private val leftOut = mutableSetOf<Long>()

    /** Emergency calls under way, so their end restarts the pause. */
    private val emergencyCalls = mutableSetOf<Long>()

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        // Before anything else, tracking on or not: the answering machine
        // stands down for a day after it (EmergencyPause).
        if (EmergencyPause.isEmergency(this, call)) {
            emergencyCalls += call.details.creationTimeMillis
            EmergencyPause.noteCall(this)
        }
        // Switched off, and bound all the same - the daemon could not be told
        // to withdraw the permission yet: off means off, nothing is kept.
        if (!CallTracking.enabled(this)) return
        changed(call)
        // Whose calls to keep, asked once for both: the contacts lookup is
        // the costly part of each, and the two must judge the caller alike.
        val number = call.details.handle?.schemeSpecificPart
        val keeps = lazy { rulesKeep(applicationContext, number) }
        decideEarly(call, keeps)
        decideAnswer(call, keeps)
    }

    /** Whose calls to keep, for [number]: true if kept - or no rules - false
     *  if left out, null when it cannot be told: no call log or contacts,
     *  or the lookup failed. */
    private fun rulesKeep(context: Context, number: String?): Boolean? = when {
        !CallRules.active(context) -> true
        !CallLogLookup.granted(context) || !CallLogLookup.photosGranted(context) -> null
        else -> CallLogLookup.contactFor(context, number, mutableMapOf())
            .let { if (it.failed) null else CallRules.keeps(context, it.ids) }
    }

    /**
     * The answering machine: should the recorder answer this ringing call,
     * and after how long? Decided here, where the caller is known while the
     * phone rings; the recorder runs the timer, and answers nothing without
     * this go-ahead. Off the main thread: the rules and Do Not Disturb both
     * read the contacts.
     */
    private fun decideAnswer(call: Call, keeps: Lazy<Boolean?>) {
        val context = applicationContext
        if (!AnsweringMachine.enabled(context)) return
        if (call.details.callDirection != Call.Details.DIRECTION_INCOMING || call.state != Call.STATE_RINGING) return
        // Sent with the go-ahead, so one that comes too late for its own
        // ring is never taken for the next caller's. The recorder's clock:
        // since boot, the same in both processes, and a wall-clock step
        // before the go-ahead lands does not move it.
        val rangAt = android.os.SystemClock.elapsedRealtime()
        val others = calls.size - 1
        val handle = call.details.handle
        val account = call.details.accountHandle?.id
        val emergencyCallback = EmergencyPause.isCallback(call.details)
        val telecomLast = EmergencyPause.telecomLast(call.details)
        // Kept, so the answering machine's screen and the self-test show the
        // pause that Telecom's record starts too.
        EmergencyPause.noteTelecom(context, telecomLast)
        scope.launch {
            // The SIMs as they are now, not as the settings screen last saw
            // them: the default can change, and a SIM come, in between.
            CaptureDaemon.sims(context)?.let { AnswerConditions.remember(context, it) }
            val decision = runCatching {
                AnsweringMachine.decide(
                    ringing(context, others, handle, account, keeps).copy(
                        emergencyCallback = emergencyCallback,
                        emergencyPaused = EmergencyPause.pausedUntil(context, telecomLast) != null,
                    ),
                )
            }.getOrElse { AnsweringMachine.Decision.No("could not decide: ${it.javaClass.simpleName}") }
            when (decision) {
                is AnsweringMachine.Decision.Answer -> {
                    Log.i(TAG, "calltrack: answering machine - answer after ${decision.delaySeconds} s")
                    goAheads.trySend(GoAhead(context, decision.delaySeconds * 1000, rangAt))
                }
                is AnsweringMachine.Decision.No -> Log.i(TAG, "calltrack: answering machine - not answering: ${decision.why}")
            }
        }
    }

    private fun ringing(
        context: Context,
        others: Int,
        handle: android.net.Uri?,
        account: String?,
        keeps: Lazy<Boolean?>,
    ): AnsweringMachine.Ringing {
        return AnsweringMachine.Ringing(
            enabled = AnsweringMachine.enabled(context),
            greeting = Greeting.present(context),
            recorderOn = RecorderSwitch.isOn(context),
            trackingOn = CallTracking.enabled(context),
            otherCalls = others,
            // The default SIM's, not necessarily the ringing one's: the
            // app cannot read another SIM's without a phone permission.
            // Unknown counts as roaming: a call is never answered on a guess.
            roaming = runCatching {
                context.getSystemService(android.telephony.TelephonyManager::class.java).isNetworkRoaming
            }.getOrDefault(true),
            rulesKeep = keeps.value,
            dndSilences = AnswerConditions.dndSilences(context, handle),
            delay = AnsweringMachine.delay(context),
            dndShort = AnsweringMachine.dndShort(context),
            simAllowed = AnswerConditions.simAllowed(context, account),
            // Messenger calls are invisible to this service (Telecom shows it
            // only calls it manages); the audio mode is theirs while they run.
            otherAppCall = runCatching {
                context.getSystemService(android.media.AudioManager::class.java).mode ==
                    android.media.AudioManager.MODE_IN_COMMUNICATION
            }.getOrDefault(false),
        )
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        val id = call.details.creationTimeMillis
        // The day is counted from the emergency call's end. The network can
        // mark one only once it is up: asked again here.
        if (emergencyCalls.remove(id) || EmergencyPause.isEmergency(this, call)) EmergencyPause.noteCall(this)
        wasActive -= id
        val wasLeftOut = leftOut.remove(id)
        if (wasLeftOut) {
            // A left-out call that rang and was never answered never went
            // off-hook, so the daemon's skip is still waiting for it - and
            // would take the next call, one the rules keep, instead. Taken
            // back whether or not tracking is still on.
            skips.trySend(applicationContext to false)
        }
        if (!CallTracking.enabled(this)) {
            // Switched off mid-call: nothing more is kept - but a call left
            // out on purpose is still noted as such, or the missed-call check
            // reports it, and the Calls tab marks it red.
            if (wasLeftOut) CallLedger.noteSkipped(this, id, call.details.callDirection == Call.Details.DIRECTION_INCOMING, 0)
            return
        }
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
        val becameActive = call.state == Call.STATE_ACTIVE && wasActive[id] != true
        wasActive[id] = call.state == Call.STATE_ACTIVE
        // A second call answered - a waiting one, or one the user placed -
        // while the answering machine may be taking a message on the first:
        // the user is back, and this call is theirs. Left to the machine, the
        // greeting would play into it, their microphone stay muted, and the
        // message's limit hang it up. Talking takes the message over, as
        // Talk to them does; with no message being taken the recorder
        // ignores it.
        if (becameActive && calls.size > 1) {
            val context = applicationContext
            scope.launch { CaptureDaemon.handOver(context, talk = true) }
        }
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
        // Both clocks read together, while the call is up and the phone
        // cannot sleep between them: nanoTime is the monotonic clock the
        // daemon stamps its packets with (AudioTimestamp.TIMEBASE_MONOTONIC
        // and System.nanoTime there). Issue #40: see PacketClock.
        clockOffset = System.currentTimeMillis() - System.nanoTime() / 1_000_000,
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
    private fun decideEarly(call: Call, keeps: Lazy<Boolean?>) {
        val context = applicationContext
        if (calls.size > 1) return
        val id = call.details.creationTimeMillis
        val now = System.currentTimeMillis()
        if (CallTracking.calls(context).any { it.id != id && it.endedAt > now - RECENT_END_MS }) return
        if (!RecorderSwitch.isOn(context) || RecordingMode.of(context) != RecordingMode.AUTOMATIC) return
        // Kept, no rules, or no telling: recorded.
        if (keeps.value != false) return
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
            // Twice: the daemon ends its file only once the audio mode has
            // left the call, which can be seconds after Telecom says so - and
            // a file still being written is not listed. Collecting twice is
            // harmless (RecordingSaver claims and checks).
            for (wait in COLLECT_AFTER_MS) {
                delay(wait)
                runCatching { RecordingSaver.reconcile(context) }
                    .onFailure { Log.w(TAG, "calltrack: collecting after the call failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "JemRec"
        /** Five seconds after the last call, and fifteen after that. */
        val COLLECT_AFTER_MS = longArrayOf(5_000L, 15_000L)

        /** Another call ended this recently: no skip (see decideEarly).
         *  Past the daemon's 2 s end check, with room to spare. */
        const val RECENT_END_MS = 10_000L

        /** Not tied to the service: Telecom unbinds it the moment the last
         *  call goes, and the collection after it must still run. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** A go-ahead for the answering machine: the delay, and when the
         *  ring it is for was seen. */
        class GoAhead(val context: Context, val delayMs: Int, val rangAt: Long)

        /** Go-aheads, in order - like the skips, and on the same scope. */
        val goAheads = Channel<GoAhead>(Channel.UNLIMITED).also { channel ->
            scope.launch {
                for (go in channel) {
                    val state = CaptureDaemon.goAhead(go.context, go.delayMs, go.rangAt)
                    Log.i(TAG, "calltrack: answering machine go-ahead -> ${state ?: "the recorder did not take it"}")
                }
            }
        }

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
