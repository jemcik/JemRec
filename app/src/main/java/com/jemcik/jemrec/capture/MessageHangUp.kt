package com.jemcik.jemrec.capture

/**
 * The answering machine's call, hung up when its recorder is lost in the
 * middle of a message.
 *
 * The machine lives in the recorder: the greeting, the microphone muted so
 * the room stays out, the message's longest length, and the hang-up when its
 * recording stops (the user's rule, review round 10). A recorder ended
 * mid-message - its process killed - takes all of that with it, and the call
 * stays answered and muted: the caller heard silence until they hung up,
 * with no limit, since the machine came in 0.17. A recorder started again
 * cannot end it: all it sees is a call up and its microphone muted, which
 * may as well be a call of the user's (Answering.giveBackStaleMute).
 *
 * The call's own service can, with exact call tracking on: Telecom binds
 * CallTracker into every call, and each call it is given is that call's own
 * android.telecom.Call - ending one ends that call and no other (AOSP
 * Telecom's InCallAdapter.disconnectCall takes the call by its id, for any
 * in-call service bound; read 2026-11-18, not produced). So the message's
 * call is the one call up, active, as the message starts; and it is ended
 * only while it is still that call, still active, and the microphone still
 * muted - given back, someone has taken the call. With tracking off there
 * is no such call to hold, and nothing is ended (review round 13; the user's
 * choice, 2026-11-18). Plain, so the JVM tests hold it.
 */
internal object MessageHangUp {

    /** A call Telecom has up: its id (its creation time) and whether it is
     *  active - not held, not ringing. */
    data class Up(val id: Long, val active: Boolean)

    /** The message's call: the one call up, and active, as the message
     *  starts - the machine answers none over another; null else. */
    fun messageCall(calls: List<Up>): Long? = calls.singleOrNull()?.takeIf { it.active }?.id

    /** What to do with the message's call now its recorder is lost. */
    sealed interface Decision {
        data class End(val id: Long) : Decision

        data class Keep(val why: String) : Decision
    }

    fun decide(messageCall: Long?, calls: List<Up>, micMuted: Boolean): Decision {
        if (messageCall == null) return Decision.Keep("no call of its own known - exact call tracking off?")
        val call = calls.firstOrNull { it.id == messageCall } ?: return Decision.Keep("its call is over")
        if (!call.active) return Decision.Keep("its call is not the active one")
        if (!micMuted) return Decision.Keep("the microphone is given back - someone took the call")
        return Decision.End(call.id)
    }
}
