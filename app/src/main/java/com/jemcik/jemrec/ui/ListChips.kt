package com.jemcik.jemrec.ui

import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.VoipApps

/*
 * The chips over the two lists: the one rule that says which are drawn (the
 * user, 2026-10-23), and what each leaves when it is on. A chip only when it
 * would narrow its list to some of it - not to nothing, and not to all of
 * it. A chip that would show nothing, or everything, is not drawn; one that
 * is on is drawn whatever, so it can be turned off. The Recordings tab had
 * this for Kept and Messages only; now it is every chip's, in both tabs.
 * Kept out of the screens so it is tested off them (ListChipsTest,
 * RecordingFilterTest).
 */

/** The source a list can be narrowed to besides a messenger's tag: a phone
 *  call - one with no messenger in its name. */
const val PHONE_SOURCE = "phone"

/** How [recording]'s call came: a messenger's tag, or [PHONE_SOURCE]. */
fun sourceOf(recording: Recording): String = recording.parsedName?.app ?: PHONE_SOURCE

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
 * Whether [chip] leaves this item: what the chip counts when chipsFor decides
 * whether to draw it, and what it keeps when it is on. One answer for both,
 * so a chip drawn because it narrows to some rows narrows to those rows -
 * each list's filter asked its own fields, and could have come to differ.
 */
internal fun ChipFacts.leftBy(chip: ListChip): Boolean = when (chip.kind) {
    ChipKind.SEARCH -> true
    ChipKind.FAVORITES -> favorite
    ChipKind.SOURCE -> source == chip.source
    ChipKind.KEPT -> kept
    ChipKind.MESSAGES -> message
    ChipKind.NOT_RECORDED -> notRecorded
}

/**
 * The chips to draw over [items], in order; [on] are the chips picked now.
 * Search is drawn, first, over any list with something in it.
 */
internal fun chipsFor(items: List<ChipFacts>, on: Set<ListChip>): List<ListChip> {
    if (items.isEmpty()) return on.toList()
    val chips = mutableListOf(ListChip(ChipKind.SEARCH))
    fun offer(chip: ListChip) {
        if (chip in on || items.count { it.leftBy(chip) } in 1 until items.size) chips += chip
    }
    offer(ListChip(ChipKind.FAVORITES))
    // The phone first, then the messengers in the order Settings lists them.
    val sources = items.mapNotNull { it.source }.distinct()
        .sortedBy { source -> if (source == PHONE_SOURCE) -1 else VoipApps.KNOWN.indexOfFirst { it.tag == source } }
    for (source in sources) offer(ListChip(ChipKind.SOURCE, source))
    // A source picked whose calls have all gone since: still drawn, to be
    // turned off.
    on.filter { it.kind == ChipKind.SOURCE && it.source !in sources }.forEach { chips += it }
    offer(ListChip(ChipKind.KEPT))
    offer(ListChip(ChipKind.MESSAGES))
    offer(ListChip(ChipKind.NOT_RECORDED))
    return chips
}

/**
 * The chips [on] as the questions they ask, a list for each kind - search
 * left out, being the query's. Each kind is its own question - who the call
 * was with, how it came, what was kept - so kinds combine: each ANDs on top
 * of the others. Chips of one kind widen: WhatsApp and Telegram and
 * favourites is favourites' calls on either app. Sources were one at a time
 * once, copied from the time presets, where picking one replaced the other;
 * a call comes one way only, so a second source can only widen (the user
 * asked why they excluded each other, 2026-10-23).
 */
private fun questions(on: Set<ListChip>): Collection<List<ListChip>> =
    on.filter { it.kind != ChipKind.SEARCH }.groupBy { it.kind }.values

private fun ChipFacts.answers(questions: Collection<List<ListChip>>): Boolean =
    questions.all { kind -> kind.any { leftBy(it) } }

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

/**
 * What the Recordings tab is narrowed to: its chips and its search, as the
 * Calls tab's are. One value: as six loose fields of the screen's state they
 * were handed to the filter in four places - the list, select-all, and a
 * search's and a refresh's pruning of the selection - and a chip added to
 * one and missed in another would have had select-all pick rows that are
 * not on screen, for a delete.
 */
data class RecordingsNarrowing(
    /** Only calls with starred contacts. */
    val favorites: Boolean = false,
    /** Only calls that came one of these ways: [PHONE_SOURCE], or
     *  messengers' tags. Empty: every way. */
    val sources: Set<String> = emptySet(),
    /** Only recordings marked to keep. */
    val kept: Boolean = false,
    /** Only messages the answering machine took. */
    val messages: Boolean = false,
    /** Live search text: the caller, the number, or the time. */
    val query: String = "",
    /** Whether the search field is unfolded. Folded away by default: it is
     *  used now and then, not every visit, so it does not sit taking height
     *  until the search chip asks for it. */
    val searchOpen: Boolean = false,
)

internal fun RecordingsNarrowing.chipsOn(): Set<ListChip> = buildSet {
    if (favorites) add(ListChip(ChipKind.FAVORITES))
    sources.forEach { add(ListChip(ChipKind.SOURCE, it)) }
    if (kept) add(ListChip(ChipKind.KEPT))
    if (messages) add(ListChip(ChipKind.MESSAGES))
    if (searchOpen) add(ListChip(ChipKind.SEARCH))
}

/**
 * [chip] tapped on the Recordings tab: on, or off again. Search folding away
 * takes its query with it: a filter still narrowing the list from behind a
 * hidden field is a filter the user cannot see is on, and the chip that
 * reopens search would not explain why half the recordings were missing.
 */
internal fun RecordingsNarrowing.tapped(chip: ListChip): RecordingsNarrowing = when (chip.kind) {
    ChipKind.FAVORITES -> copy(favorites = !favorites)
    ChipKind.SOURCE -> chip.source?.let { copy(sources = if (it in sources) sources - it else sources + it) } ?: this
    ChipKind.KEPT -> copy(kept = !kept)
    ChipKind.MESSAGES -> copy(messages = !messages)
    ChipKind.SEARCH -> if (searchOpen) copy(searchOpen = false, query = "") else copy(searchOpen = true)
    // The Calls tab's alone: a recording was recorded.
    ChipKind.NOT_RECORDED -> this
}

/**
 * The recordings [narrowing] leaves: its chips (questions), then its search
 * - the caller or the time, case aside, or the number as written. Shared by
 * the list (what to show), select-all (what "all" means now) and the
 * selection's pruning, so they can never disagree about which rows are on
 * screen. Pure and cheap; it runs on every keystroke and chip tap.
 */
internal fun filterRecordings(recordings: List<Recording>, narrowing: RecordingsNarrowing): List<Recording> {
    val asked = questions(narrowing.chipsOn())
    val q = narrowing.query.trim()
    return recordings.filter { r ->
        r.chipFacts().answers(asked) && (
            q.isEmpty() ||
                r.caller?.contains(q, ignoreCase = true) == true ||
                r.number?.contains(q) == true ||
                r.whenLabel.contains(q, ignoreCase = true)
            )
    }
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
    // The Recordings tab's alone: the call log holds phone calls only.
    ChipKind.SOURCE -> this
}

/** The calls [narrowing] leaves, by the same questions as the recordings':
 *  its chips, then its search - the name as the row shows it, or the number. */
internal fun filterCalls(calls: List<CallHistory.Call>, narrowing: CallsNarrowing): List<CallHistory.Call> {
    val asked = questions(narrowing.chipsOn())
    val q = narrowing.query.trim()
    return calls.filter { call ->
        call.chipFacts().answers(asked) &&
            (q.isEmpty() || call.label.contains(q, ignoreCase = true) || call.number?.contains(q) == true)
    }
}

/** A call and the Calls list's key for it. */
internal data class KeyedCall(val key: String, val call: CallHistory.Call)

/**
 * Each call with a key no other has: its time and name, and for a row the
 * call log repeats exactly - a backup restored twice - which repeat it is.
 * Keyed by time and name alone, two such rows crashed the tab (LazyColumn
 * takes no key twice); by position, as before, every key moved when a call
 * came in at the top.
 */
internal fun keyedCalls(calls: List<CallHistory.Call>): List<KeyedCall> {
    val seen = HashMap<String, Int>()
    return calls.map { call ->
        val base = "${call.at}-${call.label}"
        val repeat = seen.merge(base, 1, Int::plus)!!
        KeyedCall(if (repeat == 1) base else "$base#$repeat", call)
    }
}
