package com.jemcik.jemrec.playback

/**
 * Where a recording is while it plays, by the clock: where its sound started
 * from, plus the time since it got under way.
 *
 * WHY. ExoPlayer runs its playback loop as rarely as it can ("dynamic
 * scheduling", on by default), and the position it gives the app moves only
 * when that loop has run: measured on the Honor (MagicOS 11), 2026-11-16, it
 * stood still and then jumped about 256 ms, four times a second. The media
 * session hands that position on with the time it was asked, and every three
 * seconds the screen's reading was put right by 110 to 210 ms - back about
 * one time in three, where the playhead, which never goes back, stood still:
 * the user saw it slow down. MediaPlayer worked its position out when asked.
 * Turning the scheduling off (an experimental switch Media3 means to remove)
 * smoothed it, at half again the app's CPU while playing (7.1% of a core to
 * 10.8%, the same day, a probe running in both). This keeps it on: the
 * position comes from the clock once the sound is under way (Media3's
 * onAudioPositionAdvancing, the time playout started), and the player's own
 * word stands while it is not - sent elsewhere, not yet started - or where it
 * starts from, if that is further on; and a pause keeps where the clock had
 * the sound (below).
 *
 * The player's position trails the sound by up to a loop's wait. A clock that
 * has run further ahead of it than [MOST_AHEAD_MS] means the sound stopped
 * under it - a stall - and one behind it means the clock started from a word
 * that trailed the sound (as a play read just after a pause did, before a
 * pause kept its place - below): either way the player's word goes, and the
 * clock starts again from it. Starting from a word that trails, it may fall
 * behind again as the player steps on; each time it is brought up, only ever
 * forward, and is soon in step.
 *
 * A PAUSE HOLDS THE CLOCK'S PLACE. Paused, ExoPlayer's word is its last step,
 * up to a quarter of a second short of where the sound stopped, and it stood
 * so until the next play: the session handed it on as the paused position,
 * the screen's playhead went back to it (PlayheadGlide.place, read from its
 * rules), and on Play the player went on from where the sound really was.
 * Measured on the Honor, 2026-11-17: paused 104 ms after a step at 227,045
 * ms, the session said 227,047; played again, the player went on from
 * 227,159, and the clock had 227,149. Three pauses earlier that day, for two
 * notification sounds and a call, went on 229, 231 and 206 ms past where
 * they had been shown.
 * So [pausedAt] keeps where the clock had the sound until it plays again or
 * is sent elsewhere, and played again ([resumes]) it goes on from there - or
 * from the player's word, once that has caught up and is further on. A stall
 * or the end ([stopped]) keeps the player's word: there the sound stopped
 * before anything here was told.
 */
internal class PlayClock {

    /** Where the sound starts from: the position it was started or sent to. */
    private var fromMs = 0L

    /** When the sound got under way from [fromMs], on SystemClock's
     *  elapsedRealtime; -1 while it has not. */
    private var atMs = -1L

    /** Under way: the place is read from the clock (RecordingPlayer). */
    val underWay: Boolean get() = atMs >= 0

    /** Where the sound was paused, by the clock; -1 when the player's word
     *  goes. */
    private var heldMs = -1L

    /** The player's word when the audio track said it ran dry, the clock
     *  waiting for it to move on; -1 when none is waited for ([underrun]). */
    private var dryAtMs = -1L

    /** Played again from a paused place ahead of the player's word, and not
     *  yet under way: an underrun now puts it back to the word ([underrun]). */
    private var resumedAhead = false

    /** Where the sound was sent ([sentTo]), held until it is under way from
     *  there; -1 when none. The player may be sent ahead of it (OpusPreRoll)
     *  and say so until then. */
    private var soughtMs = -1L

    /** Started, or sent to [positionMs]: the sound goes from there, and until
     *  it does, it is there - or where the player says, if that is further
     *  on. */
    fun startsFrom(positionMs: Long) {
        fromMs = positionMs
        atMs = -1
        heldMs = -1
        dryAtMs = -1
        resumedAhead = false
        soughtMs = -1
    }

    /** Sent to [positionMs] - a seek: as [startsFrom], and that is where it
     *  is, playing or paused, until the sound is under way from there. The
     *  player itself may be sent up to OpusPreRoll.MS before it, and says so
     *  until then: paused after a seek, the player's word read 80 ms short
     *  of where it was sent (1.4). A stop meanwhile ([stopped]) keeps it -
     *  Media3 says it is not playing while a seek is under way. */
    fun sentTo(positionMs: Long) {
        startsFrom(positionMs)
        soughtMs = positionMs
    }

    /** The sound got under way at [atMs], elapsedRealtime. */
    fun underWay(atMs: Long) {
        this.atMs = atMs
        heldMs = -1
        dryAtMs = -1
        resumedAhead = false
        soughtMs = -1
    }

    /** Paused at [nowMs], the player saying [reportedMs]: where it was the
     *  moment before - as [position] says it while playing - stands while it
     *  is paused. Paused again, as a focus change while paused does, the
     *  first pause's place stands. */
    fun pausedAt(reportedMs: Long, nowMs: Long, durationMs: Long) {
        if (heldMs < 0) heldMs = position(reportedMs, playing = true, nowMs, durationMs)
        atMs = -1
    }

    /** Played again, the player saying [reportedMs]: the sound goes on from
     *  where a pause left it, or from the player's word if that is further
     *  on - read just after the pause, it may still be its last step. */
    fun resumes(reportedMs: Long) {
        // Sent somewhere while paused, not yet under way from there: there
        // still - the player's word may be short of it (sentTo).
        if (soughtMs >= 0) {
            sentTo(maxOf(soughtMs, reportedMs))
            return
        }
        val from = if (heldMs >= 0) maxOf(heldMs, reportedMs) else reportedMs
        startsFrom(from)
        resumedAhead = from > reportedMs
    }

    /** Stopped some other way - stalled, ended: the player's word, until it
     *  is under way again. A pause's place stands. A wait on the dry word
     *  goes: its start brings its own under-way, and the wait's re-anchor
     *  before it was counted twice once that came (review, 2026-11-18). */
    fun stopped() {
        atMs = -1
        dryAtMs = -1
    }

    /**
     * The sound ran dry under the clock - the audio track starved (Media3's
     * onAudioUnderrun, said at the next write, as the sound is about to go
     * again), the player saying [reportedMs]. Under way is said once a
     * start, not after a stall, and a stall shorter than [MOST_AHEAD_MS]
     * less a step left the clock that far ahead of the sound for good - the
     * playhead, a pause's place and a move to the earpiece with it, which
     * then skipped as much of the call (review, 2026-11-18). So the
     * player's word stands until it moves on from here - the new sound is
     * heard only after the output's own latency, which Media3 takes off its
     * word and the clock would not - and the clock goes on from where it
     * moved to ([position]). Paused meanwhile - the stall said only after
     * it - the paused place goes back to the word, if that is short of it;
     * and played again from that place before the stall was said, the start
     * goes back with it - else the clock led by the stall for good (review,
     * 2026-11-18). True if this changed where the sound is said to be.
     */
    fun underrun(reportedMs: Long): Boolean {
        // Sent somewhere and not yet under way from there: no sound since
        // to have run ahead of, and the player's word is short of the place
        // on purpose (sentTo).
        if (soughtMs >= 0) return false
        if (atMs >= 0) {
            fromMs = reportedMs
            atMs = -1
            dryAtMs = reportedMs
            return true
        }
        if (heldMs > reportedMs) {
            heldMs = reportedMs
            return true
        }
        if (resumedAhead && fromMs > reportedMs) {
            // Back to the word, and the word followed until the sound is
            // under way - which this start's under-way says - not the dry
            // wait, whose re-anchor before that was counted twice once it
            // came (review, 2026-11-18).
            fromMs = reportedMs
            resumedAhead = false
            return true
        }
        return false
    }

    /**
     * Where it is at [nowMs] (elapsedRealtime): by the clock while [playing]
     * and under way, and in step with the player's [reportedMs] - else that,
     * and the clock goes on from there. Paused, where [pausedAt] had it;
     * playing but not yet under way, where it starts from, or the player's
     * word if that is further on; sent somewhere ([sentTo]) and not yet under
     * way from there, playing or not, there - or the player's word if that is
     * further on. Never past [durationMs], when that is known (above 0).
     */
    fun position(reportedMs: Long, playing: Boolean, nowMs: Long, durationMs: Long): Long {
        if (!playing) {
            return when {
                heldMs >= 0 -> heldMs
                soughtMs >= 0 -> maxOf(soughtMs, reportedMs).let { if (durationMs > 0) minOf(it, durationMs) else it }
                else -> reportedMs
            }
        }
        if (atMs < 0 && dryAtMs >= 0 && reportedMs > dryAtMs) {
            // On again after running dry: the clock from the word, as it moves.
            fromMs = reportedMs
            atMs = nowMs
            dryAtMs = -1
            return reportedMs
        }
        if (atMs < 0) return maxOf(fromMs, reportedMs)
        val clocked = fromMs + (nowMs - atMs)
        if (clocked < reportedMs || clocked > reportedMs + MOST_AHEAD_MS) {
            fromMs = reportedMs
            atMs = nowMs
            return reportedMs
        }
        return if (durationMs > 0) minOf(clocked, durationMs) else clocked
    }

    companion object {
        /** How far the clock may run ahead of the player's word before it
         *  is taken for a stall. ExoPlayer's word stands still and then steps
         *  on, about 257 ms at a time, on the loudspeaker (the Honor,
         *  2026-11-16) and on a Bluetooth earbud alike; on the earbud the
         *  clock ran up to 359 ms ahead of it in one run (2026-11-17), and past
         *  the 400 this was, in another: taken for a stall, the playhead went
         *  back 389 ms and on again. A second clears those steps by far, and a
         *  sound truly stopped under a player that says it plays - rare, for a
         *  file on the phone - is found within it once it stops for longer
         *  than that; a shorter stop the audio track says itself
         *  ([underrun]). */
        const val MOST_AHEAD_MS = 1_000L
    }
}
