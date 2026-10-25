package com.jemcik.jemrec.capture

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * Whether calls were being recorded, and how, at any moment since this was
 * first kept - so the Calls tab can say why a call has no recording instead
 * of only that it has none.
 *
 * Three states, from the two settings that decide it: the recorder switch
 * (off records nothing) and the mode (ask-first records only what the user
 * taps Record for). Every change is noted with its time, at the one place
 * both settings already report to (MissedCalls.coverageChanged).
 *
 * ONLY FROM NOW ON. A phone's earlier history was never kept, so a call from
 * before the first entry gets null, "not known" - and the Calls tab says
 * nothing about why, rather than guess. The first entry is the state at the
 * moment this was first asked for ([start]).
 *
 * Bounded: [KEEP] changes, the newest. A switch flipped hundreds of times
 * loses its oldest flips, and the calls they covered become "not known"
 * again, which is honest.
 */
internal object CoverageHistory {

    enum class State { OFF, ASK_FIRST, AUTOMATIC }

    private const val KEY = "coverage_history"
    private const val KEEP = 300

    fun current(context: Context): State = when {
        !RecorderSwitch.isOn(context) -> State.OFF
        RecordingMode.of(context) == RecordingMode.ON_DEMAND -> State.ASK_FIRST
        else -> State.AUTOMATIC
    }

    /** The settings just changed: note what they are now. */
    @Synchronized
    fun note(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = Prefs.of(context)
        val entries = parse(prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableList()
        entries += now to current(context)
        val kept = entries.sortedBy { it.first }.takeLast(KEEP)
        prefs.edit { putStringSet(KEY, kept.map { "${it.first}:${it.second.name}" }.toSet()) }
    }

    /** Begins the history if it has not begun: everything before now stays unknown. */
    fun start(context: Context) {
        if (Prefs.of(context).getStringSet(KEY, null).isNullOrEmpty()) note(context)
    }

    fun read(context: Context): List<Pair<Long, State>> =
        parse(Prefs.of(context).getStringSet(KEY, emptySet()) ?: emptySet())

    /** The state in force at [at], or null before the history begins. Pure. */
    internal fun stateAt(history: List<Pair<Long, State>>, at: Long): State? =
        history.filter { it.first <= at }.maxByOrNull { it.first }?.second

    internal fun parse(entries: Set<String>): List<Pair<Long, State>> =
        entries.mapNotNull { entry ->
            val time = entry.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            val state = runCatching { State.valueOf(entry.substringAfter(':')) }.getOrNull() ?: return@mapNotNull null
            time to state
        }.sortedBy { it.first }
}
