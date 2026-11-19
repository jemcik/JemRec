package com.jemcik.jemrec.recordings

/**
 * What the name of one of the recorder's files says, read in one place.
 *
 *   jemrec_rec_<ms>[_early<ms>][_msg<ms>|_msgnone|_msgfail][_from<ms>][_joined]_<in|out>.dat
 *   jemrec_rec_<ms>[_early<ms>][_in|_out]_voip-<package>.dat
 *
 * The first a phone call's (the recorder's Checks.messageRecfile and
 * takenRecfile put a message's or a takeover's start just before the
 * direction), the second a messenger's (Checks.recfileTag: the way only where
 * Telecom told it, Google Meet's); either "_early" where the recording ended
 * before the call (Checks.earlyRecfile); "_joined" where the recorder
 * began during the call, its way then only a guess (Checks.recfileTag). The
 * tags are read only ahead of a
 * messenger's package, whose name may hold an underscore and "_msg1_" with
 * it. Pure.
 */
internal data class DaemonFile(
    /** When the call began, wall clock; null when the name does not say. */
    val start: Long?,
    /** Which way it went, by the "_in." tag; a messenger's file has none. */
    val incoming: Boolean,
    /** A message the answering machine took, or null for any other call. */
    val message: Message?,
    /** Where a call the user took over from the answering machine starts,
     *  in ms from the capture's first sample; null for any other. */
    val takenFromMs: Long?,
    /** A messenger's package, for a messenger's call. */
    val messenger: String?,
    /** Which way a messenger's call went, where the recorder could tell. */
    val messengerIncoming: Boolean?,
    /** How much of the call the recording missed at its end, in ms, where
     *  the recorder saw its encoder end before the call (StoppedEarly). */
    val missedMs: Long? = null,
    /** The recorder began during the call - started again mid-call - so
     *  [incoming] is its guess, and the call is the one up at [start]
     *  (CallTracking.callsIn, SavePlan.decide). */
    val joined: Boolean = false,
) {
    sealed interface Message {
        /** Where the message starts, in ms from the capture's first sample. */
        data class Starts(val ms: Long) : Message

        /** The caller hung up during the greeting. */
        data object None : Message

        /** The greeting could not be played. */
        data object Failed : Message
    }

    companion object {
        private val MESSAGE_TAG = Regex("_msg(\\d+|none|fail)_")
        private val TAKEN_TAG = Regex("_from(\\d+)_")
        private val JOINED_TAG = Regex("_joined_(?:in|out)\\.dat$")
        /** Followed by the rest of the name, or by nothing in a messenger's,
         *  whose tags end where its package begins. */
        private val EARLY_TAG = Regex("^jemrec_rec_\\d+_early(\\d+)(?:_|$)")

        fun parse(name: String) = parse(name, name.substringBefore("_voip-"))

        /** [tags]: the name ahead of a messenger's package. */
        private fun parse(name: String, tags: String) = DaemonFile(
            start = name.substringAfter("jemrec_rec_", "").substringBefore('_', "").toLongOrNull(),
            incoming = isIncoming(tags),
            message = when (val tag = MESSAGE_TAG.find(tags)?.groupValues?.get(1)) {
                null -> null
                "none" -> Message.None
                "fail" -> Message.Failed
                else -> tag.toLongOrNull()?.let { Message.Starts(it) }
            },
            takenFromMs = TAKEN_TAG.find(tags)?.groupValues?.get(1)?.toLongOrNull(),
            messenger = name.substringAfter("_voip-", "").removeSuffix(".dat").ifEmpty { null },
            messengerIncoming = when {
                name.contains("_in_voip-") -> true
                name.contains("_out_voip-") -> false
                else -> null
            },
            missedMs = EARLY_TAG.find(tags)?.groupValues?.get(1)?.toLongOrNull(),
            joined = JOINED_TAG.containsMatchIn(tags),
        )

        /**
         * Which way a call went, from the "_in." / "_out." tag every name
         * carries - the recorder's jemrec_rec_<millis>_in.dat and the saved
         * yyyyMMdd_HHmmss_in.ogg alike. One reader for both, because two had
         * drifted: one matched "_in" and would have called an "_input" incoming.
         */
        fun isIncoming(name: String): Boolean = name.contains("_in.")
    }
}
