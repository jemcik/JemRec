package com.jemcik.jemrec.capture

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * Two things about a ringing call the answering machine's decision needs from
 * the phone: whether Do Not Disturb would silence it, and whether it is on a
 * SIM the machine answers on.
 */
internal object AnswerConditions {

    private const val KEY_DEFAULT_SIM = "answer_default_sim"
    private const val KEY_SIM_COUNT = "answer_sim_count"

    /**
     * Would Do Not Disturb silence a call from [handle]? Off: no. On: Android
     * says, per caller (NotificationManager.matchesCallFilter, Android 13+,
     * with contacts access or this app's notification listener) - so a
     * starred contact a "starred only" DND lets through rings for the full
     * delay. Where it cannot say, DND on counts as silencing: the cost is an
     * earlier answer for a caller DND would have let ring.
     *
     * Measured on an Honor, 2026-10-22: DND off, a call rings; DND set to
     * starred contacts, a non-starred contact and an unknown number were both
     * silenced.
     */
    fun dndSilences(context: Context, handle: Uri?): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return false
        if (notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return false
        if (handle == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching { !notifications.matchesCallFilter(handle) }.getOrDefault(true)
    }

    /**
     * Whether a call on phone account [account] is one to answer, by the
     * user's "Answer on" choice. With one SIM - or an account Telecom did not
     * name - always: the choice only exists with two.
     */
    fun simAllowed(context: Context, account: String?): Boolean {
        if (account == null || simCount(context) < 2) return true
        return when (val sim = AnsweringMachine.sim(context)) {
            AnsweringMachine.SIM_BOTH -> true
            AnsweringMachine.SIM_DEFAULT -> defaultSim(context)?.let { it == account } ?: true
            else -> sim == account
        }
    }

    /** What the recorder said about the SIMs last time anyone asked - the
     *  settings screen does, each time it opens, and the call tracker at
     *  each ring it decides on. */
    fun remember(context: Context, sims: CaptureDaemon.Sims) {
        Prefs.of(context).edit {
            putString(KEY_DEFAULT_SIM, sims.defaultId)
            putInt(KEY_SIM_COUNT, sims.all.size)
        }
    }

    fun defaultSim(context: Context): String? = Prefs.of(context).getString(KEY_DEFAULT_SIM, null)

    fun simCount(context: Context): Int = Prefs.of(context).getInt(KEY_SIM_COUNT, 1)
}
