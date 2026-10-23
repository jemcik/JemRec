package com.jemcik.jemrec.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Multitrack's caller colours: one for each caller, kept, and as few shared
 * as can be.
 *
 * A colour came from the name's hash alone, so two callers shared one one
 * time in six - on the Honor (2026-10-23) the user's two most frequent
 * callers were both blue, and so were most of the list. Now a caller seen for
 * the first time gets the colour fewest of the callers in the lists have, and
 * keeps it; the first six callers are six colours. Most frequent first, then
 * the latest, when many are new at once - the user's existing calls, the
 * first time: the people seen most are the ones sure to differ.
 *
 * What is kept is not the name: a key, the first 12 hex digits of the
 * SHA-256 of the name in lower case - one-way - and the colour's number, in
 * the app's private storage, which leaves the phone in no backup (see
 * data_extraction_rules.xml). A name not given one yet - a frame before the
 * first refresh, the Theme screen's invented calls, the screen tests - keeps
 * the old rule, the name's hash (legacyIndex).
 */
internal object CallerColours {

    /** Colours in Multitrack's palettes, light and dark alike. */
    const val HUES = 6

    private const val PREFS = "jemrec_colours"

    private val keys = ConcurrentHashMap<String, String>()

    /** [name]'s key: what is kept in its place. */
    fun keyOf(name: String): String = keys.getOrPut(name) {
        MessageDigest.getInstance("SHA-256").digest(name.lowercase().toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
    }

    /** The colour by the name's hash alone: before 0.19 every colour, now
     *  the fallback, and the first choice of those least used. */
    fun legacyIndex(name: String, hues: Int = HUES): Int = abs(name.lowercase().hashCode() % hues)

    /** Whether [name] gets a caller's colour: a name, not a bare number,
     *  which gets the neutral one. */
    fun named(name: String?): Boolean = name != null && name.any { it.isLetter() }

    /**
     * Each name in [ranked] - the callers in the lists now, the first
     * first - with its colour: as [known] has it, or, for a new one, the
     * colour fewest of the [ranked] callers have; of those, its legacyIndex
     * if that is one, else the lowest. Pure: the store is round it.
     */
    fun assign(known: Map<String, Int>, ranked: List<String>, hues: Int = HUES): Map<String, Int> {
        val out = known.toMutableMap()
        val names = ranked.distinct()
        val counts = IntArray(hues)
        names.forEach { name -> known[keyOf(name)]?.let { if (it in 0 until hues) counts[it]++ } }
        for (name in names) {
            val key = keyOf(name)
            if (key in out) continue
            val fewest = counts.min()
            val choices = counts.indices.filter { counts[it] == fewest }
            val pick = legacyIndex(name, hues).takeIf { it in choices } ?: choices.first()
            out[key] = pick
            counts[pick]++
        }
        return out
    }

    /** [calls] - a caller's name and when, one pair a call or recording -
     *  as assign takes them: the callers named, most calls first, then the
     *  latest call. */
    fun ranked(calls: List<Pair<String?, Long>>): List<String> =
        calls.filter { named(it.first) }
            .groupBy { it.first!! }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Pair<String?, Long>>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.second } })
            .map { it.key }

    /** Every caller in [calls] with its colour, new ones given theirs and
     *  kept. Off the main thread: it reads and writes the app's storage. */
    fun update(context: Context, calls: List<Pair<String?, Long>>): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.all.mapNotNull { (key, value) -> (value as? Int)?.let { key to it } }.toMap()
        val all = assign(known, ranked(calls))
        val added = all.filterKeys { it !in known }
        if (added.isNotEmpty()) prefs.edit { added.forEach { (key, index) -> putInt(key, index) } }
        return all
    }
}

/** Each caller's colour, by key (CallerColours), from the screen's state. */
internal val LocalCallerColours = staticCompositionLocalOf { emptyMap<String, Int>() }
