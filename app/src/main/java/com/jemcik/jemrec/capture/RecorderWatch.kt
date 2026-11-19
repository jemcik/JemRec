package com.jemcik.jemrec.capture

import kotlinx.coroutines.delay

/**
 * The recorder, watched by the call's own service while a call it announced
 * is up (CallMonitorService). The service runs for every call the recorder
 * starts, tracking on or off, and is the one part of the app there for the
 * whole call.
 *
 * A recorder ended mid-call - its process killed - could not say so, and
 * nothing brought it back until the call was over: the rest of the call went
 * unrecorded, unsaid until after it. Started again while the call is up, it
 * records the rest of it, a recording of its own: measured on the Android 17
 * emulator (2026-11-18), the recorder killed 4 s into a call and started
 * again 8.8 s later, 4.2 s and 10.8 s were saved, the 8.8 s between lost. So
 * every [LOOK_MS] the recorder is asked whether it is there; gone, the
 * call's notification says so ([stopped]) and it is revived, again every
 * [RETRY_MS] while it will not start. The revived recorder's own start of
 * the call puts the notification back to recording.
 *
 * And the call's end, when the recorder never said it: its end of a call is
 * what took the service down, so after a recorder ended mid-call and not
 * brought back the call's notification stayed up after the call, until the
 * next call's end. The audio mode left in a call ([callUp]) for [GONE_LOOKS]
 * looks with no recorder - or [GONE_LOOKS_RECORDER_UP] with one, which says
 * the end itself within seconds - and the call is over ([over]).
 *
 * Only while JemRec is on ([watching]): switched off, nothing is recorded
 * that a recorder down would cost, and nothing is said. Plain, so the JVM
 * tests run its time (RecorderWatchTest).
 */
internal class RecorderWatch(
    private val callUp: () -> Boolean,
    /** Any mode but normal - a ring's too: while a recorder that has said
     *  the call is up, the call is over once that is false, a ring's mode
     *  counted [RING_MS] at most. */
    private val anyCall: () -> Boolean = callUp,
    private val watching: () -> Boolean,
    private val recorderUp: suspend () -> Boolean,
    private val revive: suspend () -> Boolean,
    private val stopped: () -> Unit,
    /** The call over, the recorder never having said so; true if the
     *  recorder answered at every look since the call's mode left - down at
     *  one, it is gone, perhaps only after the call's end (a part errs
     *  safe), and what it left is taken for no whole call (review rounds 29,
     *  30, 32). */
    private val over: (recorderUp: Boolean) -> Unit,
    private val log: (String) -> Unit,
    /** The call starts the service has heard: one since a revive is the
     *  revived recorder's own, which says the end too. */
    private val starts: () -> Int = { 0 },
    private val lookMs: Long = LOOK_MS,
    private val retryMs: Long = RETRY_MS,
    /** A monotonic clock in ms: [retryMs] and [RING_MS] are timed by it,
     *  not counted in looks - a look with the recorder down takes its pings
     *  too, ~2.4 s, and six "30 s" were ~45 (review rounds 10, 22). Plain
     *  JVM. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    /** A ring's mode is this call too, with no recorder to say it: a
     *  messenger call's, a phone call ringing over it - the messenger call
     *  goes on under the ring (the recorder's own rule,
     *  Checks.messengerCallGoesOn) - and a phone call's on hold, another
     *  call ringing over it (LiveCalls.held; review round 26). Counted
     *  [RING_MS] at most. */
    private val ringIsTheCall: () -> Boolean = { false },
    /** Its revive brought the recorder back: in this call, as the call's
     *  service may need to know before the recorder's start arrives (an am,
     *  up to 10 s; CallMonitorService.callMaybeOver) - as far as the watch
     *  knows: lost again before its next look, it is not said. */
    private val back: () -> Unit = {},
) {

    /** Until the call is over ([over]), or the coroutine is cancelled -
     *  the recorder's own end of the call. A revive that failed waits
     *  [retryMs] for the next, but the looks go on: until 1.2's review the
     *  wait was a sleep, and a call that ended in it kept its notification,
     *  "Trying again", up to 40 s after the hang-up (stopped_early, the
     *  Android 12 and 17 emulators, 2026-11-18). */
    suspend fun run() {
        var gone = 0
        var reviveAt = Long.MIN_VALUE  // from when a revive may be tried
        // A recorder this watch brought back that found no call - the call
        // ended as it came up - never says the end: waited for as one with no
        // recorder, not six looks of "Trying again" (review round 11). Not
        // once it has said the call's start: it says the end as any does,
        // and its end, wakes queued, a 10 s am, can come after two looks -
        // the watch's end then cleared the part before (review round 12).
        var revivedAt: Int? = null
        // The loss said under a ring, where no revive follows it (round 27).
        var lossSaid = false
        // When the ring's mode began, by [now]: timed, not counted in looks -
        // with no recorder a look takes its pings too, and 48 "four minute"
        // looks were six (review round 22, as round 10's retry).
        var ringingSince: Long? = null
        // Down at a look since the call's mode left: what it recorded ended
        // during the call, though another path - the heartbeat, the Wi-Fi or
        // cable watch, the app opened - brings it back before the end's look
        // (review round 30: the end's look alone judged the cut file whole).
        var downSinceOut = false
        while (true) {
            delay(lookMs)
            val up = recorderUp()
            val silentRevive = revivedAt != null && starts() == revivedAt
            // A recorder that has said the call records on through a waiting
            // call's ring - the call up hung up, MODE_RINGTONE, no idle -
            // and says the end itself: a ring is no end for it. The watch
            // ended such a call mid-recording (review round 13).
            val saysTheEnd = up && !silentRevive
            // A ring's mode for four minutes at most: a call can ring three
            // minutes or more (3GPP TS 24.008 T301, minimum 180 s; SIP's
            // Timer C, RFC 3261 16.6, over 3 minutes), and one another
            // app left set held the service and its wake lock while the
            // recorder's end went astray (review rounds 14 and 15).
            // And a messenger call's, with no recorder: a phone call ringing
            // over it ended it after two looks, mid-call (review round 21).
            val ringing = (saysTheEnd || ringIsTheCall()) && !callUp() && anyCall()
            ringingSince = if (ringing) ringingSince ?: now() else null
            val ringStill = ringingSince.let { it == null || now() - it <= RING_MS }
            val callStill = if (saysTheEnd) anyCall() && ringStill else callUp() || ringing && ringStill
            if (!callStill) {
                gone++
                if (!up) downSinceOut = true
                if (gone >= if (saysTheEnd) GONE_LOOKS_RECORDER_UP else GONE_LOOKS) {
                    log("monitor: the call is over and the recorder never said so; standing down")
                    over(up && !downSinceOut)
                    return
                }
                continue
            }
            gone = 0
            downSinceOut = false
            if (up) {
                reviveAt = Long.MIN_VALUE
                lossSaid = false
            }
            if (up || !watching()) continue
            // Not revived under a ring counted as the call: a recorder started
            // then sees the ring first with no call's mode, and its start - not
            // joined - split the call; one started once the mode is back joins
            // it (CallStates; review round 26). The loss is said all the same,
            // once: unsaid, the call was not marked lost, and what it left was
            // no part (round 27).
            if (ringing) {
                if (!lossSaid) {
                    log("monitor: the recorder is gone under a ring; starting it again once the ring is over")
                    stopped()
                    lossSaid = true
                }
                continue
            }
            if (now() < reviveAt) continue
            log("monitor: the recorder is gone mid-call; starting it again")
            stopped()
            lossSaid = true
            val startsBefore = starts()
            if (revive()) {
                revivedAt = startsBefore
                back()
                log("monitor: the recorder is back; it records the rest of the call")
            } else {
                log("monitor: the recorder would not start; trying again in ${retryMs / 1000} s")
                reviveAt = now() + retryMs
            }
        }
    }

    companion object {
        /** How often the recorder is asked: a loopback ping. */
        const val LOOK_MS = 5_000L

        /** How long after a revive that failed another is tried. */
        const val RETRY_MS = 30_000L

        /** Looks with the call gone and no recorder before it is over. */
        const val GONE_LOOKS = 2

        /** How long a ring's mode counts as the call - with a recorder that
         *  says the end, or with none for a messenger call or a phone call
         *  on hold (ringIsTheCall):
         *  four minutes - a call can ring three minutes or more (3GPP TS
         *  24.008 T301). Two looks ended a call under a waiting call still
         *  ringing (review round 15). */
        const val RING_MS = 4 * 60_000L

        /** With a recorder, which says the end itself seconds after the
         *  audio mode leaves the call. Not one this watch brought back that
         *  has not said the call's start: it never saw the call, and never
         *  will (GONE_LOOKS). */
        const val GONE_LOOKS_RECORDER_UP = 6
    }
}
