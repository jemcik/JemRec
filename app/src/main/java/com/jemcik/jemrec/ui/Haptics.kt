package com.jemcik.jemrec.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The app's haptics, named by what they mean rather than which constant they
 * are, so call sites read "confirm" or "toggle" and the choice of vibration
 * lives in one place.
 *
 * Matched to Android's own vocabulary: a firm LongPress for entering selection
 * (the gesture people already expect to feel), the ToggleOn/ToggleOff pair for
 * switches and checkboxes, a light tap for ordinary buttons, and the heavier
 * Confirm for an action that cannot be taken back. Kept deliberately sparse -
 * a phone that buzzes at everything teaches the hand to ignore it.
 */
fun HapticFeedback.tap() =
    performHapticFeedback(HapticFeedbackType.ContextClick)

fun HapticFeedback.toggle(on: Boolean) =
    performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

fun HapticFeedback.longPress() =
    performHapticFeedback(HapticFeedbackType.LongPress)

/** For an action there is no undoing - a delete, a reset. */
fun HapticFeedback.confirm() =
    performHapticFeedback(HapticFeedbackType.Confirm)
