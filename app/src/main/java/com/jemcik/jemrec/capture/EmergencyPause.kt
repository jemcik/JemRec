package com.jemcik.jemrec.capture

import android.content.Context
import android.telecom.Call
import android.telephony.TelephonyManager
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * The answering machine stands down for a day after the user calls an
 * emergency number. An emergency service calling back - from an ordinary
 * number, or a withheld one, so the callback cannot be told apart - must ring
 * until the user can pick up, not be answered with a greeting.
 *
 * WHY A DAY, AND WHY A PAUSE. Phones do the same, time-boxed, rather than try
 * to recognise the callback: iPhone turns its call screening off for 24 hours
 * after an emergency call (support.apple.com/111106), and Android turns call
 * blocking off, for 2 hours by default (CarrierConfigManager,
 * KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT). Android's emergency
 * callback mode - which the recorder asks about too - starts only on CDMA, or
 * on LTE where a carrier opts in (AOSP, read 2026-10-22): on GSM it never
 * does. A day costs little: a caller in it reaches the carrier's voicemail, as
 * before JemRec.
 *
 * Counted from the end of the last emergency call this app saw; Telecom's own
 * record of one, on a ringing call, counts too. The user can end the pause
 * early, knowingly ([resume]).
 */
internal object EmergencyPause {

    const val PAUSE_MS = 24L * 60 * 60 * 1000
    private const val KEY_CALL = "emergency_call_at"
    private const val KEY_RESUMED = "emergency_resumed_at"

    /** Always emergency numbers on a GSM phone (3GPP TS 22.101): what is
     *  checked when Android cannot be asked. */
    private val ALWAYS = setOf("112", "911")

    /** An emergency call is being made, or has just ended: the pause runs
     *  from [at], whatever the user said about an earlier one - by the
     *  call, not the clock, which can be set back between the two. */
    fun noteCall(context: Context, at: Long = System.currentTimeMillis()) {
        Prefs.of(context).edit {
            putLong(KEY_CALL, at)
            remove(KEY_RESUMED)
        }
    }

    /** Telecom's time of the last emergency call, from a ringing call
     *  ([telecomLast]): kept, if later than this app's own, so the screens
     *  show the pause it starts - one this app did not see, made before
     *  it was set up, or with call tracking off. */
    fun noteTelecom(context: Context, telecomLast: Long) {
        val prefs = Prefs.of(context)
        if (telecomLast > prefs.getLong(KEY_CALL, 0L)) prefs.edit { putLong(KEY_CALL, telecomLast) }
    }

    /** The user's word, warned: answer again from now, until the next
     *  emergency call. */
    fun resume(context: Context, at: Long = System.currentTimeMillis()) {
        Prefs.of(context).edit { putLong(KEY_RESUMED, at) }
    }

    /** Until when the machine stands down, or null. [telecomLast] is
     *  Telecom's time of the last emergency call, from a ringing call's
     *  extras (see [telecomLast]); 0 if none. */
    fun pausedUntil(context: Context, telecomLast: Long = 0L, now: Long = System.currentTimeMillis()): Long? {
        val prefs = Prefs.of(context)
        return pausedUntil(prefs.getLong(KEY_CALL, 0L), telecomLast, prefs.getLong(KEY_RESUMED, 0L), now)
    }

    /** Pure, for the tests. */
    internal fun pausedUntil(lastCall: Long, telecomLast: Long, resumedAt: Long, now: Long): Long? {
        val last = maxOf(lastCall, telecomLast)
        if (last <= 0L || last <= resumedAt) return null
        return (last + PAUSE_MS).takeIf { it > now }
    }

    /** Whether [call] is an emergency call: placed to an emergency number,
     *  or one the network says is. */
    fun isEmergency(context: Context, call: Call): Boolean {
        val details = call.details
        if (details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)) return true
        if (details.callDirection != Call.Details.DIRECTION_OUTGOING) return false
        return isEmergencyNumber(context, details.handle?.schemeSpecificPart ?: return false)
    }

    /** Whether [number] is an emergency number on this phone - one a test
     *  call never dials, either. */
    fun isEmergencyNumber(context: Context, number: String): Boolean {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return false
        // Public from Android 10, and no permission for an exact match; where
        // it cannot answer, the numbers every GSM phone treats as emergency.
        return runCatching { context.getSystemService(TelephonyManager::class.java).isEmergencyNumber(digits) }
            .getOrElse { digits in ALWAYS }
    }

    /**
     * Whether a ringing call is marked as an emergency service calling back:
     * by the network, or by the phone's emergency callback mode. Never
     * answered, resumed or not - the mark is about this call.
     */
    fun isCallback(details: Call.Details): Boolean =
        details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE) ||
            details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)

    /** Telecom's time of the last emergency call since the phone started -
     *  its start - as it puts it on a ringing call; 0 if it did not. Not there
     *  with a default dialer other than the phone's own. */
    fun telecomLast(details: Call.Details): Long =
        details.extras?.getLong(Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS, 0L) ?: 0L
}
