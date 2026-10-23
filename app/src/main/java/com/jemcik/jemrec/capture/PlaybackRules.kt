package com.jemcik.jemrec.capture

/**
 * What playback in the background decides, as plain Kotlin so it can be
 * tested off the phone: what the system's media controls call a recording,
 * what an audio-focus change or a pulled plug does to playing, and what a
 * controller connecting to the session may do. PlaybackService and
 * SessionPlayer carry it out.
 */
internal object PlaybackRules {

    /** What the media controls show while the phone is locked: nothing of
     *  who the call was with. The lock screen is readable by whoever holds
     *  the phone, and on the Honor (MagicOS 11, 2026-10-23) it showed the
     *  caller's name and the controls with Media3's defaults. */
    const val LOCKED_TITLE = "Call recording"

    /** A recording as the media controls name it: who, then what and when;
     *  with nobody known, what and when. */
    fun titles(caller: String?, app: String?, message: Boolean, whenLabel: String): Titles {
        val kind = when {
            message -> "Message"
            app != null -> "$app call"
            else -> "Phone call"
        }
        return if (caller != null) Titles(caller, "$kind · $whenLabel") else Titles(kind, whenLabel)
    }

    /** What the controls show now: the user's choice (2026-10-23) - the name
     *  once the phone is unlocked, nothing of it while it is locked. */
    fun shown(unlocked: Titles, locked: Boolean): Titles = if (locked) Titles(LOCKED_TITLE, null) else unlocked

    data class Titles(val title: String, val subtitle: String?)

    /** An audio-focus change, as Android's AudioManager reports it. */
    enum class Focus { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

    /**
     * Whether a recording should be playing: [wanted] is the user's play or
     * pause, [heldBack] a pause the system asked for that ends by itself.
     * It sounds only when wanted and not held back.
     */
    data class Playing(val wanted: Boolean, val heldBack: Boolean = false) {
        val sounding: Boolean get() = wanted && !heldBack
    }

    /**
     * Android's audio-focus guide: on a permanent loss pause, and "to
     * restart playback, the user must take an explicit action"; on a
     * transient loss pause, and resume on regaining focus if it was playing.
     * Asked to duck, a recording of speech pauses instead - the guide: for
     * spoken content "the app should pause instead", or words are missed.
     */
    fun onFocus(change: Focus, now: Playing): Playing = when (change) {
        Focus.GAIN -> now.copy(heldBack = false)
        Focus.LOSS -> Playing(wanted = false)
        Focus.LOSS_TRANSIENT, Focus.LOSS_TRANSIENT_CAN_DUCK -> if (now.wanted) now.copy(heldBack = true) else now
    }

    /** Headphones pulled out or a headset gone: pause, and stay paused. A
     *  call's audio must not move to the loudspeaker by itself. */
    fun onBecomingNoisy(now: Playing): Playing = if (now.wanted) Playing(wanted = false) else now

    /** What a controller connecting to the session may do. */
    enum class Access {
        /** JemRec itself: choose the recording, and everything else. */
        ALL,
        /** The system's controls - the notification, the lock screen,
         *  Bluetooth, a car: play, pause and seek in what JemRec chose. */
        TRANSPORT,
        /** Anyone else: turned away. */
        NONE,
    }

    /** Android lets any app ask to connect to the session; [trusted] is
     *  Media3's word for the system and the apps the user allowed to read
     *  notifications, which see the media notification anyway. */
    fun access(ownApp: Boolean, trusted: Boolean): Access = when {
        ownApp -> Access.ALL
        trusted -> Access.TRANSPORT
        else -> Access.NONE
    }
}
