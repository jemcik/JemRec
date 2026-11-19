package com.jemcik.jemrec.recordings

/**
 * What follows a save, for each of the two that collect recordings: the
 * hang-up's (CallMonitorService, as the call ends) and the reconcile's
 * (RecordingSaver.reconcile: the keep-alive, and after the last call) - and
 * for the other parts of one call, a recorder lost mid-call having left more
 * than one, saved together by either ([Collector.PART] at the hang-up,
 * [Collector.PART_LATER] in a reconcile). One table: an outcome added is
 * answered for all at once, and where they differ, the row says so. Pure;
 * RecordingSaver's AfterSave.react does it.
 */
internal object AfterSave {

    /** Who collected the recording, and the tag of its log lines. */
    enum class Collector(val tag: String) {
        HANG_UP("monitor"),
        RECONCILE("reconcile"),

        /** A call's part other than the one the call is told by - the part
         *  with the most audio (CallMonitorService.saveCall): no notice of
         *  its own, nor "Call not recorded" - told one by one, an empty part
         *  said that of a call with minutes saved, and the call's notice was
         *  whichever part finished last (review rounds 12 and 13). A failure
         *  is said, as a part of the call not saved (round 14: never said,
         *  "Recorded 2:00" for a call whose first five minutes failed), and
         *  counts toward its week, as the reconcile's. */
        PART("part"),

        /** As [PART], for a reconcile's group: a failure not said, only
         *  counted - told at the hang-up, said again at every run it was
         *  (review rounds 15 and 16); a greeting that failed told as the
         *  reconcile tells it, never told before - dropped at its first
         *  collection (round 17). */
        PART_LATER("part"),
    }

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

    /** Which of a call's files the call is told by (reactToCall): each
     *  with what its save came to and, saved, its seconds. The one saved
     *  with the most audio; else one kept by another collector (own first);
     *  nothing kept, [own] - unless own came to less than another's
     *  failure, kept to try again: an empty own's "No call audio" replaced
     *  "Is the storage full?" (review round 15) - else the last. */
    fun toldOf(files: List<Triple<String, Kind, Double>>, own: String?): String {
        files.filter { it.second == Kind.SAVED }.maxByOrNull { it.third }?.let { return it.first }
        val ownKind = files.firstOrNull { it.first == own }?.second
        // Saved by another collector, or being saved: kept - told by a log
        // line, not "Call not recorded" of a call saved (review round 16).
        val kept = setOf(Kind.GONE, Kind.BUSY)
        if (ownKind in kept) return own!!
        files.firstOrNull { it.second in kept }?.let { return it.first }
        if (ownKind == Kind.FAILED) return own!!
        return files.firstOrNull { it.second == Kind.FAILED }?.first
            ?: own?.takeIf { ownKind != null } ?: files.last().first
    }

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
            // Left out by the user's rules on whose calls to keep, or dropped
            // as recorded with recording switched off: the saver's own line
            // before this one says which.
            Kind.SKIPPED -> Row({ "monitor: $it was left out (the rules, or recording switched off)" })
            // Only ringing: nobody answered.
            Kind.NOT_ANSWERED -> Row({ "monitor: $it was never answered" })
            // The answering machine answered; the caller left before the beep.
            Kind.GREETING_HANG_UP -> Row({ "monitor: $it - the caller hung up during the greeting" })
            // Every answered call would end this way until it is put right.
            Kind.GREETING_FAILED -> Row(tell = Tell.GREETING_FAILED)
            Kind.FAILED -> Row(tell = Tell.REASON)
        }
        Collector.PART -> when (kind) {
            Kind.SAVED -> Row({ "part: saved $it, a part of its call" }, failing = Failing.CLEAR)
            Kind.EMPTY -> Row({ "part: dropped empty $it, a part of its call" }, failing = Failing.CLEAR)
            Kind.GONE -> Row({ "part: $it was collected already" }, failing = Failing.CLEAR)
            Kind.BUSY -> Row({ "part: $it is being saved by another collector" })
            Kind.SKIPPED -> Row({ "part: $it was left out (the rules, or recording switched off)" }, failing = Failing.CLEAR)
            Kind.NOT_ANSWERED -> Row({ "part: $it was never answered" }, failing = Failing.CLEAR)
            Kind.GREETING_HANG_UP -> Row({ "part: $it - the caller hung up during the greeting" }, failing = Failing.CLEAR)
            // Every answered call would end this way until it is put right.
            Kind.GREETING_FAILED -> Row(tell = Tell.GREETING_FAILED, failing = Failing.CLEAR)
            Kind.FAILED -> Row(tell = Tell.REASON, failing = Failing.COUNT)
        }
        // A greeting that failed is told as the reconcile tells it: never
        // told before, dropped at its first collection (review round 17).
        Collector.PART_LATER -> when (kind) {
            Kind.FAILED -> Row(failing = Failing.COUNT)
            Kind.GREETING_FAILED -> row(kind, Collector.RECONCILE)
            else -> row(kind, Collector.PART)
        }
        Collector.RECONCILE -> when (kind) {
            // A hang-up whose wake never arrived is still a call that just
            // ended, and still worth the after-call notification. AfterCall
            // leaves out anything long past.
            Kind.SAVED -> Row({ "reconcile: saved $it" }, notice = true, failing = Failing.CLEAR)
            Kind.EMPTY -> Row({ "reconcile: dropped empty $it" }, failing = Failing.CLEAR)
            Kind.GONE -> Row({ "reconcile: $it was collected meanwhile" }, failing = Failing.CLEAR)
            Kind.BUSY -> Row({ "reconcile: $it is being saved already, leaving it" })
            Kind.SKIPPED -> Row(
                { "reconcile: $it was left out (the rules, or recording switched off)" }, failing = Failing.CLEAR,
            )
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
