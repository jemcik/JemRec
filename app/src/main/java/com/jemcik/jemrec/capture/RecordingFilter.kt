package com.jemcik.jemrec.capture

/** The source a list can be narrowed to besides a messenger's tag: a phone
 *  call - one with no messenger in its name. */
const val PHONE_SOURCE = "phone"

/**
 * Apply the search text, and the favourites, kept, messages and source
 * chips, to a list. Each is its own question - who the call was with, what
 * was kept, how the call came - so they combine: each ANDs on top of the
 * others. There is no time preset: Today and This week went (the user,
 * 2026-10-23) - the list is newest first under Today, Yesterday and Earlier,
 * which says what they did without hiding the rest.
 *
 * [sources], when any are set, are the ways a call may have come:
 * [PHONE_SOURCE], or messengers' tags ("whatsapp") - any of them. Chips of
 * one kind widen the list, chips of different kinds narrow it: WhatsApp and
 * Telegram and favourites is favourites' calls on either app. They were one
 * at a time, copied from the time presets, where picking one replaced the
 * other; a call comes one way only, so a second source can only widen (the
 * user asked why they excluded each other, 2026-10-23).
 *
 * Shared by the list (what to show) and select-all (what "all" means now), so
 * the two can never disagree about which rows are on screen. Pure and cheap;
 * it runs on every keystroke and chip tap.
 */
fun filterRecordings(
    recordings: List<Recording>,
    query: String,
    favoritesOnly: Boolean,
    keptOnly: Boolean = false,
    messagesOnly: Boolean = false,
    sources: Set<String> = emptySet(),
): List<Recording> {
    val q = query.trim()
    return recordings.filter { r ->
        val passesFavorite = !favoritesOnly || r.callerFavorite
        val passesKept = !keptOnly || r.kept
        val passesMessage = !messagesOnly || r.message
        val passesSource = sources.isEmpty() || sourceOf(r) in sources
        val passesQuery = q.isEmpty() ||
            r.caller?.contains(q, ignoreCase = true) == true ||
            r.number?.contains(q) == true ||
            r.whenLabel.contains(q, ignoreCase = true)
        passesFavorite && passesKept && passesMessage && passesSource && passesQuery
    }
}

/** How [recording]'s call came: a messenger's tag, or [PHONE_SOURCE]. */
fun sourceOf(recording: Recording): String = recording.parsedName?.app ?: PHONE_SOURCE
