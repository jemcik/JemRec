package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs

/**
 * The answering machine, from the app's side: the user's settings, and the
 * decision, while a call rings, whether to answer it and when.
 *
 * THE APP DECIDES WHO; THE RECORDER DECIDES WHEN. The recorder (the daemon's
 * AnswerMachine) answers a ringing call only on a go-ahead from here, and runs
 * the timer itself, because this app can be frozen while the dialer is in
 * front. Every doubt here means no go-ahead: recording keeps a call when
 * unsure, because a recording can be deleted later - an answered call cannot
 * be unanswered, so here the default flips.
 *
 * Proven on an Honor (Snapdragon, Android 17), 2026-10-22: answered from the
 * shell, the greeting heard in full by a caller on another phone, the room
 * kept out by the mute, the message recorded. Other chips are what the
 * diagnostics (AnswerDiagnostics) find out, phone by phone.
 */
object AnsweringMachine {

    private const val KEY_ON = "answer_on"
    private const val KEY_DELAY = "answer_delay_s"
    private const val KEY_DND_SHORT = "answer_dnd_short"
    private const val KEY_LONGEST = "answer_longest_s"
    private const val KEY_SIM = "answer_sim"
    private const val KEY_ROUTE = "answer_route"

    /** Seconds of ringing before it answers. 5 suits taking every call as a
     *  message; below the carrier's own voicemail timer, always. */
    val DELAYS = listOf(5, 10, 15, 20, 25, 30)
    const val DEFAULT_DELAY = 20

    /** About two rings: Do Not Disturb would silence the call anyway. */
    const val DND_DELAY = 5

    /** The longest message, in seconds; [NO_LIMIT] runs until the caller
     *  hangs up, or has been silent half a minute (the recorder's
     *  AnswerMachine.SILENCE_HANGUP_MS). */
    const val NO_LIMIT = 0
    const val SILENCE_HANGUP_SECONDS = 30
    val LONGEST = listOf(30, 60, 120, NO_LIMIT)
    const val DEFAULT_LONGEST = 60

    /** Which SIM's calls it answers, with two: the default SIM for calls
     *  until the user picks; "both"; or one SIM's phone-account id. */
    const val SIM_DEFAULT = "default"
    const val SIM_BOTH = "both"

    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.of(context).edit().putBoolean(KEY_ON, on).apply()
    }

    fun delay(context: Context): Int =
        Prefs.of(context).getInt(KEY_DELAY, DEFAULT_DELAY).takeIf { it in DELAYS } ?: DEFAULT_DELAY

    fun setDelay(context: Context, seconds: Int) {
        require(seconds in DELAYS)
        Prefs.of(context).edit().putInt(KEY_DELAY, seconds).apply()
    }

    /** With Do Not Disturb silencing the call: after about two rings (true,
     *  the default), or the same delay as always. */
    fun dndShort(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_DND_SHORT, true)

    fun setDndShort(context: Context, short: Boolean) {
        Prefs.of(context).edit().putBoolean(KEY_DND_SHORT, short).apply()
    }

    fun longest(context: Context): Int =
        Prefs.of(context).getInt(KEY_LONGEST, DEFAULT_LONGEST).takeIf { it in LONGEST } ?: DEFAULT_LONGEST

    fun setLongest(context: Context, seconds: Int) {
        require(seconds in LONGEST)
        Prefs.of(context).edit().putInt(KEY_LONGEST, seconds).apply()
    }

    fun sim(context: Context): String = Prefs.of(context).getString(KEY_SIM, SIM_DEFAULT) ?: SIM_DEFAULT

    fun setSim(context: Context, sim: String) {
        Prefs.of(context).edit().putString(KEY_SIM, sim).apply()
    }

    /** How the greeting reaches the caller on this phone: "music" (Qualcomm's
     *  in-call music, proven on the Honor) unless a test call found that only
     *  the call-screening route works here. */
    fun route(context: Context): String = Prefs.of(context).getString(KEY_ROUTE, ROUTE_MUSIC) ?: ROUTE_MUSIC

    fun setRoute(context: Context, route: String) {
        require(route == ROUTE_MUSIC || route == ROUTE_SCREENING)
        Prefs.of(context).edit().putString(KEY_ROUTE, route).apply()
    }

    const val ROUTE_MUSIC = "music"
    const val ROUTE_SCREENING = "screening"

    /** "on,seconds,route", as the recorder reads it (its AnswerSettings).
     *  On only with a greeting to play: otherwise there is nothing to say. */
    fun wire(context: Context): String = wire(
        on = enabled(context) && Greeting.present(context),
        longest = longest(context),
        route = route(context),
    )

    internal fun wire(on: Boolean, longest: Int, route: String): String = "${if (on) 1 else 0},$longest,$route"

    /** What is known about a ringing call and the phone, for [decide]. */
    data class Ringing(
        val enabled: Boolean,
        val greeting: Boolean,
        val recorderOn: Boolean,
        val trackingOn: Boolean,
        /** Other calls Telecom has besides this one: call waiting. */
        val otherCalls: Int,
        val roaming: Boolean,
        /** The rules on whose calls to keep would keep this caller: true,
         *  false, or null when the caller could not be identified. */
        val rulesKeep: Boolean?,
        /** Do Not Disturb would silence this call. */
        val dndSilences: Boolean,
        val delay: Int,
        val dndShort: Boolean,
        /** The call is on a SIM this answers on. */
        val simAllowed: Boolean,
        /** Another app is in a call - a messenger, VoIP, a meeting - which
         *  answering would take the user out of, and the mute silence. The
         *  recorder asks again, seeing more than this app can. */
        val otherAppCall: Boolean = false,
        /** The call is marked as an emergency service calling back. */
        val emergencyCallback: Boolean = false,
        /** The user called an emergency number within a day (EmergencyPause). */
        val emergencyPaused: Boolean = false,
    )

    /** The go-ahead to send, or why there is none. */
    sealed interface Decision {
        data class Answer(val delaySeconds: Int) : Decision
        data class No(val why: String) : Decision
    }

    /**
     * Answer a ringing call, and after how long? Pure, for the tests. Every
     * "no" is the safe side: the call rings on, as if JemRec were not there.
     */
    fun decide(r: Ringing): Decision = when {
        !r.enabled -> Decision.No("switched off")
        !r.greeting -> Decision.No("no greeting recorded")
        !r.recorderOn -> Decision.No("recording is switched off")
        !r.trackingOn -> Decision.No("exact call tracking is off")
        r.otherCalls > 0 -> Decision.No("another call is up")
        r.otherAppCall -> Decision.No("a messenger or meeting call is up")
        r.emergencyCallback -> Decision.No("an emergency service may be calling back")
        r.emergencyPaused -> Decision.No("paused after an emergency call")
        r.roaming -> Decision.No("roaming")
        !r.simAllowed -> Decision.No("not a SIM it answers on")
        r.rulesKeep == null -> Decision.No("the caller could not be identified")
        !r.rulesKeep -> Decision.No("whose calls to keep leaves this caller out")
        r.dndSilences && r.dndShort -> Decision.Answer(minOf(DND_DELAY, r.delay))
        else -> Decision.Answer(r.delay)
    }
}
