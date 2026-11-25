package com.jemcik.jemrec.capture

/**
 * What the call service's own notification says (CallMonitorService), from
 * what the service knows at that moment. Pure.
 */
internal object CallNotice {

    data class State(
        /** The recorder's wakes say a call is up. */
        val callUp: Boolean,
        /** The recorder is recording it. */
        val recording: Boolean,
        /** A save failed: the user is told why, by tapping. */
        val notRecording: Boolean,
        /** The recording stopped mid-call, and whether it is tried again. */
        val stopped: Stopped? = null,
        /** The ticket of the message the answering machine is taking, if one is. */
        val messageTicket: String?,
        /** That message is a test call of the answering machine. */
        val testing: Boolean,
        /** Watching the recorder after a USB cable went in or came out. */
        val checkingRecorder: Boolean,
        /** Up only to post the background-kill warning. */
        val warningOnly: Boolean,
        /** Ask first's ticket for the call up, while Record can still be
         *  tapped (RecordPrompt.ticket): not answered, or answered Not now. */
        val recordTicket: String? = null,
    )

    data class Notice(
        val title: String,
        val text: String,
        /** Shown after Android's ten seconds, if still up, rather than at once. */
        val deferred: Boolean,
        /** The ticket Talk to them and Hang up carry; no buttons without one. */
        val buttonTicket: String?,
        /** The ticket Record carries; no Record without one. */
        val recordTicket: String? = null,
    )

    enum class Stopped {
        /** Being tried again: the recorder's capture, failed too often,
         *  every 10 s (RestartPolicy), or stalled - nothing reached the
         *  recording for 5 s, no read failing (StallWatch) - or the recorder
         *  itself, found gone mid-call, every 30 s (RecorderWatch). */
        TRYING,

        /** The encoder or its file ended: nothing more of this call. */
        FOR_GOOD,
    }

    fun of(s: State): Notice {
        val ticket = s.messageTicket
        // Only the cable watch, no call: a check that ends in seconds when the
        // cable changed nothing, and so the one notification here that waits.
        val cableOnly = s.checkingRecorder && !s.callUp && !s.recording && ticket == null
        // The background-kill warning's wake, no call and no cable watch: over
        // as soon as the warning is posted, so it waits, and is never seen.
        val warningWake = s.warningOnly && !s.checkingRecorder && !s.callUp && !s.recording && ticket == null
        // Ask first, the call not recorded: Record here to its end, whatever
        // became of the question - answered Not now, swiped away, or still up.
        // Until 1.8 Not now was the last word on a call, and a user on One UI,
        // where a banner goes by itself, found no way back to it.
        val record = s.recordTicket?.takeIf { s.callUp && !s.recording && ticket == null }
        // This service only exists during a call, so the notification only
        // says what it is doing about THIS call. "Recording call" while it
        // records; "Call in progress" in the gap before recording starts.
        val (title, text) = when {
            warningWake -> "Checking JemRec" to "Making sure it keeps running"
            cableOnly -> "Checking the recorder" to "A USB cable went in or came out"
            ticket != null && s.testing -> "Testing the answering machine" to "Your greeting is playing to the caller"
            ticket != null -> "Taking a message" to "Your greeting is playing; your microphone is muted"
            s.recording && s.stopped == Stopped.TRYING ->
                "Recording stopped" to "Trying again - this part of the call is not recorded"
            s.recording && s.stopped == Stopped.FOR_GOOD ->
                "Recording stopped" to "The rest of this call is not recorded"
            s.recording -> "Recording call" to "Saving this call"
            record != null -> "Not recording this call" to "Tap Record to record it from now on"
            s.notRecording -> "Not recording this call" to "Tap to see why"
            else -> "Call in progress" to "JemRec is handling this call"
        }
        return Notice(title, text, deferred = cableOnly || warningWake, buttonTicket = ticket, recordTicket = record)
    }
}
