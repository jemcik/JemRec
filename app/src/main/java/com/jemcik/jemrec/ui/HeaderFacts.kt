package com.jemcik.jemrec.ui

/**
 * What the home screen's header says, worked out once for all three looks so
 * they cannot disagree: Deck's LCD, Memo's words and pills and Multitrack's
 * LEDs are three drawings of these facts.
 */
internal enum class RecorderStatus {
    /** Switched off: no call is recorded. */
    OFF,
    /** Switched on and not yet finished starting. */
    STARTING,
    READY,
    /** Switched on, and the recorder is down: Instruction says what brings
     *  it back. */
    CANNOT,
}

internal fun recorderStatus(state: UiState): RecorderStatus = when {
    !state.enabled -> RecorderStatus.OFF
    state.starting -> RecorderStatus.STARTING
    state.daemonRunning -> RecorderStatus.READY
    else -> RecorderStatus.CANNOT
}

/**
 * The settings a header marks, one mark each, shown only while they are on.
 *
 * The four that change what happens to a call: whether it is recorded without
 * asking ([automatic] - one setting, so AUTO or ASK, never both), answered
 * ([answering]), recorded from a messenger app ([messengers]), and deleted
 * later ([autoDeleteDays], 0 for never). The rest stay in Settings.
 */
internal data class Marks(
    val automatic: Boolean,
    val answering: Boolean,
    val messengers: Boolean,
    val autoDeleteDays: Int,
)

internal fun marks(state: UiState, answering: Boolean) = Marks(
    automatic = state.automatic,
    // The machine is the recorder's, and off with all debugging there is no
    // recorder: set, but not answering, so not lit.
    answering = answering && !state.debuggingOff,
    messengers = state.voipSupported && state.voipInstalled.any { it.pkg in state.voipEnabled },
    autoDeleteDays = state.autoDeleteAfterDays,
)

/** Auto-delete's age, short: "90D", "6M", "1Y" - as Settings offers them. */
internal fun ageMark(days: Int): String = when (days) {
    180 -> "6M"
    365 -> "1Y"
    else -> "${days}D"
}

/** The same age, short, in words: "90 d", "6 mo", "1 yr" - Memo's line. */
internal fun ageShort(days: Int): String = when (days) {
    180 -> "6 mo"
    365 -> "1 yr"
    else -> "$days d"
}

/** The same age in words: what a screen reader hears in the header, and
 *  what Settings offers. */
internal fun ageWords(days: Int): String = when (days) {
    180 -> "6 months"
    365 -> "1 year"
    else -> "$days days"
}

/** The settings that have a mark, in the order the headers show them. */
internal enum class Marked { MODE, ANSWERING, MESSENGERS, AUTO_DELETE }

/** One setting's mark: its label, and whether it is lit - shown, on Deck's
 *  LCD; a lit LED, on Multitrack's. [days] is auto-delete's age, which Memo
 *  writes out ("90 d") where the others print the label. */
internal data class HeaderMark(val setting: Marked, val label: String, val lit: Boolean, val days: Int = 0)

/** A mark as it is drawn, header or Settings, for the tests to measure: the
 *  two are one composable each look, and must come out one size. */
internal fun markFaceTag(mark: HeaderMark) = "markface-${mark.setting}-${if (mark.lit) "lit" else "dark"}"

/**
 * [setting]'s mark, as the header shows it - and as its row in Settings shows
 * it, design C of the canvas's "Settings, three ways" (the user's choice,
 * 2026-10-23: "it will definitely help users to understand what is in the
 * header"). One function for both, so they cannot disagree. With recording
 * off the mode does not apply, so its mark goes out; the others stay - they
 * are still set.
 */
internal fun headerMark(setting: Marked, marks: Marks, status: RecorderStatus): HeaderMark = when (setting) {
    Marked.MODE -> HeaderMark(setting, if (marks.automatic) "AUTO" else "ASK", status != RecorderStatus.OFF)
    Marked.ANSWERING -> HeaderMark(setting, "ANS", marks.answering)
    Marked.MESSENGERS -> HeaderMark(setting, "MSG", marks.messengers)
    Marked.AUTO_DELETE -> HeaderMark(
        setting,
        if (marks.autoDeleteDays > 0) "DEL ${ageMark(marks.autoDeleteDays)}" else "DEL",
        marks.autoDeleteDays > 0,
        marks.autoDeleteDays,
    )
}

/** Every setting's mark, lit or not, in order: Multitrack's LEDs. */
internal fun headerMarks(marks: Marks, status: RecorderStatus): List<HeaderMark> =
    Marked.entries.map { headerMark(it, marks, status) }

/** The lit marks, in order: what Deck's LCD prints - it leaves out what is
 *  off, where Multitrack's LEDs go dark. */
internal fun litMarks(marks: Marks, status: RecorderStatus): List<HeaderMark> =
    headerMarks(marks, status).filter { it.lit }

/** What the header asks of the user while nothing can be recorded; each
 *  look words it its own way. [tap], where the words are a tap: what
 *  TalkBack says the tap does (instructionTap). */
internal enum class Instruction(val tap: String? = null) {
    /** Recording is off: the record control turns it on. */
    SWITCH_ON,
    /** Off with all debugging (When JemRec is off): the record control turns
     *  it on, over Wi-Fi only - unless adbd is on Start without Wi-Fi's port,
     *  and then it is a plain SWITCH_ON. */
    SWITCH_ON_OVER_WIFI,
    /** Android has not allowed this Wi-Fi: a tap asks. */
    ALLOW_WIFI("Allow this Wi-Fi"),
    /** No Wi-Fi: the recorder comes back by itself once there is one. */
    JOIN_WIFI,
    /** On Wi-Fi, and Wireless debugging turns JemRec away - a deleted
     *  pairing, likeliest: a tap opens setup's pairing step. */
    PAIR_AGAIN("Pair again"),
    /** On Wi-Fi, and the recorder did not come back for a reason no revive
     *  has named yet: a tap tries again. */
    START_AGAIN("Start the recorder again"),
}

/**
 * The recorder down: off Wi-Fi, Wi-Fi is what brings it back, whatever else
 * is wrong; on Wi-Fi, what the last failed revive found (RecorderHealth) -
 * this Wi-Fi not allowed, the pairing refused, or neither yet. Two refusals
 * in a row make the pairing the cause; after one it is a try again, whose
 * tap makes the second.
 */
internal fun instruction(state: UiState): Instruction? = when (recorderStatus(state)) {
    RecorderStatus.OFF -> if (onNeedsWifi(state)) Instruction.SWITCH_ON_OVER_WIFI else Instruction.SWITCH_ON
    RecorderStatus.CANNOT -> when {
        !state.onWifi -> Instruction.JOIN_WIFI
        state.networkNotAllowed -> Instruction.ALLOW_WIFI
        state.pairingRefused -> Instruction.PAIR_AGAIN
        else -> Instruction.START_AGAIN
    }
    else -> null
}

/** Off with all debugging, and nothing but Wi-Fi to come back on over: adbd
 *  is not on Start without Wi-Fi's port. */
internal fun onNeedsWifi(state: UiState): Boolean = state.debuggingOff && !state.portSet

/**
 * Whether home gives way to setup's pairing step (MainViewModel watches for
 * it): set up, on home, the recorder down on Wi-Fi, and Wireless debugging
 * turning JemRec away twice in a row - its pairing deleted, likeliest. Only
 * pairing again brings the recorder back, and a line in the header was a
 * sentence to read and a tap to find (the user, 2026-10-24). Another screen
 * is left alone: it happens once home shows again.
 */
internal fun pairingLostOnHome(state: UiState): Boolean =
    state.step == SetupStep.READY && state.screen == Screen.HOME &&
        recorderStatus(state) == RecorderStatus.CANNOT && state.onWifi && state.pairingRefused

/**
 * All of it as one sentence, for TalkBack: a screen reader hears the state,
 * then what is on - the order a sighted glance takes. Not the last call: the
 * list under the header says it, first in line; nor how many there are: the
 * Recordings tab says that, in every look.
 */
internal fun headerSpeech(state: UiState, marks: Marks): String {
    val status = recorderStatus(state)
    val first = when (status) {
        RecorderStatus.OFF -> when {
            onNeedsWifi(state) -> "Off, with debugging off. Calls are not recorded; turning on needs Wi-Fi."
            state.debuggingOff -> "Off, with debugging off. Calls are not recorded."
            else -> "Off. Calls are not recorded."
        }
        RecorderStatus.STARTING -> "Starting."
        RecorderStatus.READY -> if (marks.automatic) "Ready to record. Records every call." else "Ready to record. Asks before each call."
        RecorderStatus.CANNOT -> when (instruction(state)) {
            Instruction.ALLOW_WIFI -> "Cannot record. Android has not allowed this Wi-Fi."
            Instruction.PAIR_AGAIN -> "Cannot record. Wireless debugging turns JemRec away; pair again."
            Instruction.START_AGAIN -> "Cannot record. The recorder did not start; start it again."
            else -> "Cannot record. Join any Wi-Fi; it resumes on its own."
        }
    }
    val on = buildList {
        if (marks.answering) add("answering machine")
        if (marks.messengers) add("messenger calls")
        if (marks.autoDeleteDays > 0) add("auto-delete after ${ageWords(marks.autoDeleteDays)}")
    }
    val onPart = when (on.size) {
        0 -> ""
        1 -> " ${on[0].replaceFirstChar { it.uppercase() }} is on."
        else -> " " + (on.dropLast(1).joinToString(", ") + " and " + on.last())
            .replaceFirstChar { it.uppercase() } + " are on."
    }
    return first + onPart
}
