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
    /** Switched on, and the recorder is down - until Wi-Fi brings it back. */
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
    answering = answering,
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

/** The same age in words, for a screen reader. */
internal fun ageWords(days: Int): String = when (days) {
    180 -> "6 months"
    365 -> "1 year"
    else -> "$days days"
}

/**
 * The marks as LCD labels, in a fixed order. With recording off the mode does
 * not apply, so its mark goes out; the others stay - they are still set.
 */
internal fun markLabels(marks: Marks, status: RecorderStatus): List<String> = buildList {
    if (status != RecorderStatus.OFF) add(if (marks.automatic) "AUTO" else "ASK")
    if (marks.answering) add("ANS")
    if (marks.messengers) add("MSG")
    if (marks.autoDeleteDays > 0) add("DEL ${ageMark(marks.autoDeleteDays)}")
}

/** What the header asks of the user while nothing can be recorded; each
 *  look words it its own way. */
internal enum class Instruction {
    /** Recording is off: the record control turns it on. */
    SWITCH_ON,
    /** Android has not allowed this Wi-Fi: a tap asks. */
    ALLOW_WIFI,
    /** No Wi-Fi: the recorder comes back by itself once there is one. */
    JOIN_WIFI,
}

internal fun instruction(state: UiState): Instruction? = when (recorderStatus(state)) {
    RecorderStatus.OFF -> Instruction.SWITCH_ON
    RecorderStatus.CANNOT -> if (state.networkNotAllowed) Instruction.ALLOW_WIFI else Instruction.JOIN_WIFI
    else -> null
}

/**
 * All of it as one sentence, for TalkBack: a screen reader hears the state,
 * then what is on - the order a sighted glance takes. Not the last call: the
 * list under the header says it, first in line; nor how many there are: the
 * Recordings tab says that, in every look.
 */
internal fun headerSpeech(state: UiState, marks: Marks): String {
    val status = recorderStatus(state)
    val first = when (status) {
        RecorderStatus.OFF -> "Off. Calls are not recorded."
        RecorderStatus.STARTING -> "Starting."
        RecorderStatus.READY -> if (marks.automatic) "Ready to record. Records every call." else "Ready to record. Asks before each call."
        RecorderStatus.CANNOT -> if (state.networkNotAllowed) {
            "Cannot record. Android has not allowed this Wi-Fi."
        } else {
            "Cannot record. Join any Wi-Fi; it resumes on its own."
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
