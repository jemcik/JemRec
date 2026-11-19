package com.jemcik.jemrec.playback

/** Where a recording sounds. SPEAKER is the media stream, as before 1.1: the
 *  phone's loudspeakers, or headphones when they are in. */
enum class Output { SPEAKER, EARPIECE }

/**
 * Settings' "Play recordings" (PlayThroughSetting), stored by name: the
 * loudspeaker; the loudspeaker with the earpiece while the phone is at the
 * ear (the default); or the earpiece, never switched to the loudspeaker by
 * itself. Headphones, when in, come first in each.
 */
enum class PlayThrough { LOUDSPEAKER, LOUDSPEAKER_AND_EAR, EARPIECE }

/**
 * Listening at the ear (discussion #83), as plain Kotlin so it can be tested
 * off the phone: when a recording moves to the earpiece and back, and when
 * the proximity sensor and the lock that darkens the screen at the ear are
 * wanted. EarWatch carries it out.
 *
 * Raised to the ear while it plays, a recording moves to the earpiece;
 * lowered, it goes back to the loudspeaker and plays on, where it was
 * started - the user's choice (2026-11-15), over pausing as Signal does
 * (VoiceNoteProximityWakeLockManager, read 2026-11-14). Pausing kept a
 * private call from going out loud to whoever is near once the phone came
 * down; but it was started on the loudspeaker, and a hand passing over the
 * sensor paused it (on the Honor, 0.64 s of a hand, on purpose). Nothing
 * here pauses: a hand over the sensor only dips the sound.
 *
 * Settings' "Play recordings" picks where each recording starts, and whether
 * the sensor moves it (PlayThrough). The player's button moves the one
 * playing, for that recording; the sensor never takes an earpiece the
 * button or the setting chose back to the loudspeaker, and the screen still
 * goes dark at the ear. Changed in Settings, the choice moves what plays.
 *
 * Measured with a probe on the Honor (MagicOS 11), 2026-11-15, with
 * MediaPlayer, which played recordings until 1.2: raised, the recording
 * moved to the earpiece in 69-83 ms, heard as smooth; the screen went dark
 * and JemRec stayed in front (no pause or stop of the screen). With
 * ExoPlayer the move opens the file again, as MediaPlayer's did
 * (SessionPlayer.reopen): 117-160 ms to sound on the Android 17 AVD.
 */
internal class AtTheEar(private val quietMs: Long = QUIET_MS) {

    /** What EarWatch reads from the phone and the player. */
    data class Facts(
        /** The recording loaded in the player, or null. */
        val recording: String?,
        /** The user's Play: playing, or held back by a call to play on after. */
        val wanted: Boolean,
        /** JemRec's screen is in front. */
        val onScreen: Boolean,
        /** Headphones, a headset, Bluetooth: the recording sounds there. */
        val headphones: Boolean,
        /** Settings' "Play recordings". */
        val playThrough: PlayThrough,
        /** A proximity sensor, and the lock that darkens the screen by it. */
        val sensor: Boolean,
        /** Held back by a call the user is on, by Android's audio mode - a
         *  phone call made or answered, not one only ringing nor one the
         *  answering machine takes, or a messenger call - to play on after it. */
        val heldByCall: Boolean = false,
    )

    /** What EarWatch should make so. [cover]: JemRec's own screen blacked
     *  out and deaf to touch, while it plays at the earpiece held to the ear
     *  - where the phone does not darken the screen itself (the Honor in
     *  landscape, 2026-11-15). */
    data class Wants(
        val output: Output,
        val sensor: Boolean,
        val screenOffLock: Boolean,
        val cover: Boolean = false,
    )

    /** The button's choice, for the recording loaded - the setting's until
     *  the button is used. */
    private var chosen = Output.SPEAKER

    /** On the earpiece because the phone is at the ear, not by the button. */
    private var raised = false

    /** JemRec's screen covered, until the phone leaves the ear. */
    private var covering = false

    /** The sensor's last reading since it was started; null before one. */
    private var near: Boolean? = null

    /** Readings before this time wait (see QUIET_MS); null while the sensor
     *  is not wanted. */
    private var quietUntil: Long? = null

    private var recording: String? = null

    /** The setting as last read, so a change is seen. */
    private var playThrough: PlayThrough? = null

    /** The time of the last update. */
    private var lastMs = 0L

    /** The button: [output] for this recording. */
    fun choose(output: Output, facts: Facts, nowMs: Long): Wants {
        chosen = output
        raised = false
        return update(facts, nowMs)
    }

    /** A proximity reading: [isNear] the sensor's (isNear). */
    fun sensed(isNear: Boolean, facts: Facts, nowMs: Long): Wants {
        near = isNear
        return update(facts, nowMs)
    }

    /** Whether it is at the earpiece because the phone was raised - what
     *  EarWatch puts back when it can no longer watch the sensor. */
    fun raisedNow(): Boolean = raised

    /** When to [update] again: a reading that came in a quiet time waits
     *  for its end. Null when nothing waits. */
    fun due(): Long? = quietUntil?.takeIf { near != null && it > lastMs }

    /** Anything in [facts] changed, or [due] came. */
    fun update(facts: Facts, nowMs: Long): Wants {
        lastMs = nowMs
        if (facts.recording != recording || facts.playThrough != playThrough) {
            // Each recording starts where the setting says - or in the
            // headphones - and a setting changed moves what plays.
            recording = facts.recording
            playThrough = facts.playThrough
            chosen = startsOn(facts.playThrough)
            raised = false
        }
        // Playing here: on the screen, playing, nothing else to listen with.
        // In a pocket, with headphones in or paused, the sensor moves
        // nothing and covers nothing.
        val raising = facts.playThrough == PlayThrough.LOUDSPEAKER_AND_EAR
        val here = facts.onScreen && facts.recording != null && facts.wanted && !facts.headphones
        // Watched to move a recording that plays on the loudspeaker: raised
        // only on the screen, and once at the ear, until it is lowered,
        // whatever screen is up - left by a cheek's swipe home at the ear,
        // twice on the Honor (2026-11-15), it went on the loudspeaker there.
        // And through a call that holds it back, so that it plays on after
        // the call where the phone is: lowered to answer and raised for the
        // call, it played on 1.8 s from the loudspeaker at the ear, until
        // JemRec's screen came back from under the call's (the Honor,
        // 2026-11-16). Not while a phone call only rings: a phone left
        // ringing in a pocket would move it to the earpiece there.
        val raise = facts.sensor && raising && chosen == Output.SPEAKER && facts.recording != null &&
            facts.wanted && !facts.headphones && (facts.onScreen || raised || facts.heldByCall)
        if (!raise) {
            // Not watched, nothing holds it at the ear: back where it was
            // started - the recording paused or ended there, headphones in.
            raised = false
            quietUntil = null
        } else if (quietUntil == null) {
            quietUntil = nowMs + quietMs
        }
        val quiet = quietUntil
        if (raise && quiet != null && nowMs >= quiet) {
            val atEar = near
            if (atEar != null && atEar != raised) {
                raised = atEar
                // A raise wobbles: a reading the other way in the next
                // moment waits, and counts only if it still holds then.
                quietUntil = nowMs + quietMs
            }
        }
        // Lowered away from JemRec: nothing more to watch there, past the call.
        val stillRaise = raise && (facts.onScreen || raised || facts.heldByCall)
        if (!stillRaise) quietUntil = null
        val output = if (!facts.headphones && (chosen == Output.EARPIECE || raised)) Output.EARPIECE else Output.SPEAKER
        // Covered while a recording plays at the earpiece held to the ear,
        // and then until the phone leaves the ear or the screen: a recording
        // that ends or is paused there would otherwise light the screen at
        // the cheek, where the phone does not darken it (the 1.1 review).
        val atEarNow = near == true
        covering = facts.sensor && facts.onScreen && atEarNow &&
            (covering || (here && output == Output.EARPIECE))
        // Watched too at an earpiece, only to cover the screen while it is
        // at the ear: a reading there counts at once, as covering is safe.
        val watched = stillRaise || (facts.sensor && here && output == Output.EARPIECE) || covering
        if (!watched) near = null
        return Wants(
            output = output,
            sensor = watched,
            // Held on at the ear when the screen is left: the phone's own
            // darkening is for any screen.
            screenOffLock = (here && facts.sensor && (raising || output == Output.EARPIECE)) || (stillRaise && raised),
            cover = covering,
        )
    }

    companion object {
        /** Where a recording starts, by [playThrough]: on the earpiece only
         *  when the setting says so. */
        fun startsOn(playThrough: PlayThrough): Output =
            if (playThrough == PlayThrough.EARPIECE) Output.EARPIECE else Output.SPEAKER

        /**
         * A proximity sensor's reading as near: closer than 5 cm, and not
         * its far value. Android's sensor docs: most report the distance in
         * centimetres, some only near and far, far being their maximum. The
         * test is Signal's; the Honor's ams TCS3720 reads 0 near and its
         * maximum, 5, far (2026-11-15).
         */
        fun isNear(value: Float, maximumRange: Float): Boolean = value < 5f && value != maximumRange

        /**
         * How long a reading waits after the sensor starts and after each
         * move, and then counts only if it still holds. Signal ignores
         * readings for 500 ms after starting; waiting instead, a finger near
         * the sensor from the tap on Play moves nothing, and a raise held to
         * the ear in that time still counts.
         */
        const val QUIET_MS = 500L
    }
}
