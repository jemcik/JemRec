package com.jemcik.jemrec.recordings

/**
 * What becomes of a recording once the saver has read it from the recorder
 * (RecordingSaver.write): one table, where nine flags used to decide it. Pure.
 */
internal object WriteOutcome {

    /** How reading it ended. */
    enum class Read {
        /** The recorder no longer has it: the other collector got there first. */
        GONE,

        /** A part had nowhere to go, and reading stopped there. */
        NOWHERE,

        /** It broke off mid-transfer, or a part's writer could not be made. */
        BROKE_OFF,

        /** Read to its end - in full, or a file with nothing in it at all. */
        COMPLETE,
    }

    enum class Ending {
        /** Collected already: not news. */
        GONE,

        /** Failed, the recorder's copy kept: nowhere to save it. */
        NOWHERE,

        /** Failed, the recorder's copy kept: a part would not finish or copy into place. */
        NOT_WRITTEN,

        /** Published, part by part. */
        PUBLISH,

        /** Nothing fell inside the call's cut: read again and saved whole. */
        WHOLE,

        /** No audio in it: dropped. */
        EMPTY,

        /** Failed, the recorder's copy kept: the transfer broke off. */
        BROKE_OFF,
    }

    /**
     * The ending of a recording read as [read], where [anything] - some part
     * got a packet - and [written] - every part finished, and copied into
     * place - and whether it may be tried [whole]: a cut to one call
     * (SavePlan.wholeIfNothingFits) of a file whose packets it missed.
     */
    fun of(read: Read, anything: Boolean, written: Boolean, whole: Boolean): Ending = when (read) {
        Read.GONE -> Ending.GONE
        Read.NOWHERE -> Ending.NOWHERE
        Read.BROKE_OFF -> Ending.BROKE_OFF
        Read.COMPLETE -> when {
            anything && !written -> Ending.NOT_WRITTEN
            anything -> Ending.PUBLISH
            whole -> Ending.WHOLE
            else -> Ending.EMPTY
        }
    }
}
