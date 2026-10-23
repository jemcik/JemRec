package com.jemcik.jemrec.ui

import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.capture.PHONE_SOURCE
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.VoipApps
import com.jemcik.jemrec.capture.sourceOf

/*
 * The chips over the two lists, and the one rule that says which are drawn
 * (the user, 2026-10-23): a chip only when it would narrow its list to some
 * of it - not to nothing, and not to all of it. A chip that would show
 * nothing, or everything, is not drawn; one that is on is drawn whatever,
 * so it can be turned off. The Recordings tab had this for Kept and Messages
 * only; now it is every chip's, in both tabs. Kept out of the screens so it
 * is tested off them (ListChipsTest).
 */

/** What a chip narrows by. In this order on screen: search, then who, how
 *  the call came, kept, messages, what became of it. Search first, so it is
 *  always in view: with real calls - the phone, two messengers, messages -
 *  the row outgrew the Honor's screen and pushed it off the end (the user,
 *  2026-10-23). No Today or This week: the list is newest first under Today,
 *  Yesterday and Earlier, which says what they did without hiding the rest
 *  (the user's call, the same day). */
internal enum class ChipKind { SEARCH, FAVORITES, SOURCE, KEPT, MESSAGES, NOT_RECORDED }

/** A chip; a SOURCE chip says which: [PHONE_SOURCE] or a messenger's tag. */
internal data class ListChip(val kind: ChipKind, val source: String? = null)

/** An item of a list as the chips see it: a recording, or a call. [source]
 *  is null where the list has none to tell apart (the Calls tab: the phone's
 *  call log holds phone calls only). */
internal data class ChipFacts(
    val favorite: Boolean,
    val kept: Boolean,
    val message: Boolean,
    val source: String?,
    val notRecorded: Boolean,
)

/**
 * The chips to draw over [items], in order; [on] are the chips picked now.
 * Search is drawn, first, over any list with something in it.
 */
internal fun chipsFor(items: List<ChipFacts>, on: Set<ListChip>): List<ListChip> {
    if (items.isEmpty()) return on.toList()
    val total = items.size
    fun narrows(count: Int) = count in 1 until total
    val chips = mutableListOf(ListChip(ChipKind.SEARCH))
    fun offer(chip: ListChip, drawn: Boolean) {
        if (drawn || chip in on) chips += chip
    }
    offer(ListChip(ChipKind.FAVORITES), narrows(items.count { it.favorite }))
    // The phone first, then the messengers in the order Settings lists them.
    val sources = items.mapNotNull { it.source }.distinct()
        .sortedBy { source -> if (source == PHONE_SOURCE) -1 else VoipApps.KNOWN.indexOfFirst { it.tag == source } }
    for (source in sources) offer(ListChip(ChipKind.SOURCE, source), narrows(items.count { it.source == source }))
    // A source picked whose calls have all gone since: still drawn, to be
    // turned off.
    on.filter { it.kind == ChipKind.SOURCE && it.source !in sources }.forEach { chips += it }
    offer(ListChip(ChipKind.KEPT), narrows(items.count { it.kept }))
    offer(ListChip(ChipKind.MESSAGES), narrows(items.count { it.message }))
    offer(ListChip(ChipKind.NOT_RECORDED), narrows(items.count { it.notRecorded }))
    return chips
}

internal fun Recording.chipFacts(): ChipFacts =
    ChipFacts(callerFavorite, kept, message, sourceOf(this), notRecorded = false)

internal fun CallHistory.Call.chipFacts(): ChipFacts =
    ChipFacts(favorite, recording?.kept == true, recording?.message == true, source = null, notRecorded = status in NOT_RECORDED)

/**
 * The calls that connected and were not recorded - each says why: the
 * Calls tab's own chip. Not the ones nobody answered (missed, no answer:
 * there was nothing to record), nor the ones recorded since deleted, still
 * to be collected, or a test of the answering machine.
 */
internal val NOT_RECORDED: Set<Status> = setOf(
    Status.SKIPPED, Status.TOO_SHORT, Status.RECORDING_OFF, Status.ASK_FIRST, Status.NOT_RECORDED,
    Status.NO_RECORDING, Status.GREETING_HANGUP, Status.GREETING_FAILED,
)

/** The Recordings tab's chips that are on. */
internal fun UiState.recordingChipsOn(): Set<ListChip> = buildSet {
    if (favoritesOnly) add(ListChip(ChipKind.FAVORITES))
    sources.forEach { add(ListChip(ChipKind.SOURCE, it)) }
    if (keptOnly) add(ListChip(ChipKind.KEPT))
    if (messagesOnly) add(ListChip(ChipKind.MESSAGES))
    if (searchOpen) add(ListChip(ChipKind.SEARCH))
}

/**
 * What the Calls tab is narrowed to: its chips and its search, apart from the
 * Recordings tab's, so a chip picked there does not quietly narrow this.
 */
data class CallsNarrowing(
    val favorites: Boolean = false,
    val kept: Boolean = false,
    val messages: Boolean = false,
    val notRecorded: Boolean = false,
    val query: String = "",
    val searchOpen: Boolean = false,
)

internal fun CallsNarrowing.chipsOn(): Set<ListChip> = buildSet {
    if (favorites) add(ListChip(ChipKind.FAVORITES))
    if (kept) add(ListChip(ChipKind.KEPT))
    if (messages) add(ListChip(ChipKind.MESSAGES))
    if (notRecorded) add(ListChip(ChipKind.NOT_RECORDED))
    if (searchOpen) add(ListChip(ChipKind.SEARCH))
}

/** [chip] tapped on the Calls tab: on, or off again; search folding away
 *  takes its query with it, as on the Recordings tab. */
internal fun CallsNarrowing.tapped(chip: ListChip): CallsNarrowing = when (chip.kind) {
    ChipKind.FAVORITES -> copy(favorites = !favorites)
    ChipKind.KEPT -> copy(kept = !kept)
    ChipKind.MESSAGES -> copy(messages = !messages)
    ChipKind.NOT_RECORDED -> copy(notRecorded = !notRecorded)
    ChipKind.SEARCH -> if (searchOpen) copy(searchOpen = false, query = "") else copy(searchOpen = true)
    ChipKind.SOURCE -> this
}

/** The calls [narrowing] leaves: the Calls tab's filterRecordings. */
internal fun filterCalls(calls: List<CallHistory.Call>, narrowing: CallsNarrowing): List<CallHistory.Call> {
    val q = narrowing.query.trim()
    return calls.filter { call ->
        (!narrowing.favorites || call.favorite) &&
            (!narrowing.kept || call.recording?.kept == true) &&
            (!narrowing.messages || call.recording?.message == true) &&
            (!narrowing.notRecorded || call.status in NOT_RECORDED) &&
            (q.isEmpty() || call.label.contains(q, ignoreCase = true) || call.number?.contains(q) == true)
    }
}
