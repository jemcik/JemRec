package com.jemcik.jemrec.calls

/**
 * Telecom's own account of a call (CallTracking.Tracked), as each change of
 * its state adds to it, and whether a call is left out before it is
 * recorded: the decisions of the call tracker (CallTracker), which reads
 * them off Telecom's calls and keeps what comes back. No phone in it.
 */
internal object TelecomAccount {

    /** What a call's state change says, as far as the account goes. */
    enum class Change { ACTIVE, DISCONNECTED, OTHER }

    /**
     * The account [base] after a [change]: the first answer at Telecom's own
     * connect time [connectedAt] - to the millisecond - or [now] without one;
     * a return from hold at [now]. Not a second answer when the call is
     * [isNew] here - its account already took Telecom's connect time - or
     * [wasActive] at its last change, ACTIVE again with no hold between. The
     * first end counts.
     */
    fun changed(
        base: CallTracking.Tracked,
        isNew: Boolean,
        change: Change,
        connectedAt: Long,
        wasActive: Boolean,
        now: Long,
    ): CallTracking.Tracked = when (change) {
        Change.ACTIVE -> when {
            base.activations.isEmpty() -> base.copy(activations = listOf(connectedAt.takeIf { it > 0 } ?: now))
            isNew || wasActive -> base
            else -> base.copy(activations = base.activations + now)
        }
        Change.DISCONNECTED -> ended(base, now)
        Change.OTHER -> base
    }

    /** The call is over at [at], unless it ended already. */
    fun ended(base: CallTracking.Tracked, at: Long): CallTracking.Tracked =
        base.copy(endedAt = base.endedAt.takeIf { it > 0 } ?: at)

    /** Another call ended this recently: no early skip. Past the recorder's
     *  2 s end check, with room to spare. */
    const val RECENT_END_MS = 10_000L

    /**
     * Whether the call [id], ringing or dialled, is left out before it is
     * recorded: only one on its own ([callsUp]), with no other call ended in
     * the last [RECENT_END_MS] ([tracked]), in Record every call
     * ([automatic]), and with the rules saying no ([keeps]: null is "cannot
     * tell", which keeps it). Each asked only when the one before let it
     * through: the last is a contacts lookup.
     *
     * Not straight after another call: the recorder records on for the two
     * seconds it takes to be sure a call is over, and a call that goes
     * off-hook in them joins that recording. A skip would then stop it - the
     * kept call's audio with it - where the saver splits the two instead.
     */
    fun skipEarly(
        id: Long,
        callsUp: Int,
        now: Long,
        tracked: () -> List<CallTracking.Tracked>,
        automatic: () -> Boolean,
        keeps: () -> Boolean?,
    ): Boolean {
        if (callsUp > 1) return false
        if (tracked().any { it.id != id && it.endedAt > now - RECENT_END_MS }) return false
        if (!automatic()) return false
        // Kept, no rules, or no telling: recorded.
        return keeps() == false
    }
}
