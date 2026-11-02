package com.jemcik.jemrec.recordings

/**
 * What follows a save, for each of the two that collect recordings: the
 * hang-up's (CallMonitorService, as the call ends) and the reconcile's
 * (RecordingSaver.reconcile: the keep-alive, and after the last call). One
 * table: an outcome added is answered for both at once, and where the two
 * differ, the row says so. Pure; RecordingSaver's AfterSave.react does it.
 */
internal object AfterSave {

    /** Who collected the recording, and the tag of its log lines. */
    enum class Collector(val tag: String) { HANG_UP("monitor"), RECONCILE("reconcile") }

    /** What a save came to (RecordingSaver.Outcome), as the rows know it. */
    enum class Kind { SAVED, EMPTY, GONE, BUSY, SKIPPED, NOT_ANSWERED, GREETING_HANG_UP, GREETING_FAILED, FAILED }

    /** What the user is told went unrecorded. */
    enum class Tell {
        /** The failure's own reason. */
        REASON,

        /** That no call audio came through. */
        NO_AUDIO,

        /** That the answering machine's greeting would not play. */
        GREETING_FAILED,
    }

    /** What the reconcile's record of a failing file does (RecordingSaver.failingSince). */
    enum class Failing {
        LEAVE,

        /** Saved, or settled one way or another: no longer failing. */
        CLEAR,

        /** Failed again: noted, and given up on after a week of it. */
        COUNT,
    }

    class Row(
        /** The line logged, for the recorder's name for the file. */
        val log: ((String) -> String)? = null,
        val warn: Boolean = false,
        /** The after-call notice, and its waveform (AfterCall.saved). */
        val notice: Boolean = false,
        /** Then the missed-call check. */
        val missedCheck: Boolean = false,
        val tell: Tell? = null,
        /** An empty recording, told of now: noted in the ledger as a call
         *  of no length, so the missed-call check does not tell it again. */
        val noteEmpty: Boolean = false,
        val failing: Failing = Failing.LEAVE,
    )

    fun row(kind: Kind, by: Collector): Row = when (by) {
        Collector.HANG_UP -> when (kind) {
            // While the service still holds the foreground: the check decodes
            // the file, and a dormant app might not get the time. And while
            // the recorder is plainly reachable, whether any earlier call went
            // by unrecorded.
            Kind.SAVED -> Row({ "monitor: saved $it" }, notice = true, missedCheck = true)
            // Told now, so the missed-call check must not say it again. Here,
            // where the telling is: a recording collected later is left to
            // that check.
            Kind.EMPTY -> Row(tell = Tell.NO_AUDIO, noteEmpty = true)
            // The reconcile got there first, or has it now. Either way the
            // recording is being kept, and this is not a failure to report.
            Kind.GONE -> Row({ "monitor: $it was collected already" })
            Kind.BUSY -> Row({ "monitor: $it is being saved by the reconcile" })
            // The user's rules on whose calls to keep left this one out.
            Kind.SKIPPED -> Row({ "monitor: $it was left out by the rules" })
            // Only ringing: nobody answered.
            Kind.NOT_ANSWERED -> Row({ "monitor: $it was never answered" })
            // The answering machine answered; the caller left before the beep.
            Kind.GREETING_HANG_UP -> Row({ "monitor: $it - the caller hung up during the greeting" })
            // Every answered call would end this way until it is put right.
            Kind.GREETING_FAILED -> Row(tell = Tell.GREETING_FAILED)
            Kind.FAILED -> Row(tell = Tell.REASON)
        }
        Collector.RECONCILE -> when (kind) {
            // A hang-up whose wake never arrived is still a call that just
            // ended, and still worth the after-call notification. AfterCall
            // leaves out anything long past.
            Kind.SAVED -> Row({ "reconcile: saved $it" }, notice = true, failing = Failing.CLEAR)
            Kind.EMPTY -> Row({ "reconcile: dropped empty $it" }, failing = Failing.CLEAR)
            Kind.GONE -> Row({ "reconcile: $it was collected meanwhile" }, failing = Failing.CLEAR)
            Kind.BUSY -> Row({ "reconcile: $it is being saved already, leaving it" })
            Kind.SKIPPED -> Row({ "reconcile: $it was a call the rules leave out" }, failing = Failing.CLEAR)
            Kind.NOT_ANSWERED -> Row({ "reconcile: $it was never answered" }, failing = Failing.CLEAR)
            Kind.GREETING_HANG_UP -> Row({ "reconcile: $it - hung up during the greeting" }, failing = Failing.CLEAR)
            // The hang-up's wake did not land: nobody has said so yet, and
            // every answered call ends this way until it is put right.
            Kind.GREETING_FAILED -> Row(
                { "reconcile: $it - the greeting could not be played" },
                warn = true, tell = Tell.GREETING_FAILED, failing = Failing.CLEAR,
            )
            // A week of failing, from the first failure - not from the call:
            // a file stranded on the recorder for a week (a reboot, no Wi-Fi
            // to bring the recorder back) is not given up on its first try.
            Kind.FAILED -> Row(failing = Failing.COUNT)
        }
    }
}
