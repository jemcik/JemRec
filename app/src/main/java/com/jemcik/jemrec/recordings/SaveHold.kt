package com.jemcik.jemrec.recordings

import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Where the debug build holds a save, so that an emulator check can end the
 * app's process there as Android ends one it needs the memory of
 * (tools/emulator_checks.py, killed_mid_save). CLAUDE.md lists "a process
 * killed mid-transfer" among what Android does to a feature, and a save takes
 * well under a second, too short to hit from outside (review, 2026-11-18).
 * Set only by the debug build's holdsave op, in its own process: the killed
 * process forgets it, and the release build never sets it.
 */
internal object SaveHold {

    enum class At {
        /** A second of packets written: the recording's place made and
         *  still pending in MediaStore, its work copy part written. */
        TRANSFER,

        /** Written and copied into place, not yet published. */
        PUBLISH,

        /** What publishing will do written down (SaveJournal), nothing
         *  published yet: a MediaStore row still pending. */
        PUBLISHING,

        /** Published, not yet in the ledger, the recorder's copy not yet
         *  deleted. */
        PUBLISHED,

        /** Saved, noted, the recorder's copy deleted - the journal not yet
         *  told: the reconcile's own pick-up, the recorder's copy gone. */
        DONE,
    }

    @Volatile var at: At? = null

    /** Ended at once at [at], not held: the process killed straight after
     *  what came before - the journal's write - so a write that is not on
     *  disk yet is lost, as a kill loses it (review, 2026-11-18). */
    @Volatile var die = false

    /** Held at [here], in the save of [recfile], if it is [at] - once, for
     *  a minute at most, or the process ended there ([die]). */
    fun reached(here: At, recfile: String) {
        if (at != here) return
        at = null
        Log.i("JemRec", "save: held at ${here.name.lowercase()} for $recfile (the debug build's holdsave)")
        if (die) {
            die = false
            dying(here)
        } else {
            hold(here)
        }
    }

    /** What holding is: a minute's sleep, for the check's kill. The JVM
     *  tests throw instead - nothing after it runs, as after a kill. */
    @VisibleForTesting
    internal var hold: (At) -> Unit = { Thread.sleep(HOLD_MS) }

    /** What dying is: the process ended, as the low-memory killer ends it. */
    @VisibleForTesting
    internal var dying: (At) -> Unit = { android.os.Process.killProcess(android.os.Process.myPid()) }

    /** [At.TRANSFER]'s place: a second of 20 ms packets. */
    const val TRANSFER_PACKETS = 50L

    private const val HOLD_MS = 60_000L
}
