package com.jemcik.jemrec.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How much diagnostics log to keep. Roughly a screen or two of history,
 *  which is as far back as anyone reads. */
private const val LOG_LIMIT = 8_000

/**
 * What the screen says about what it did: the log in Diagnostics, and a
 * snackbar for a failure of something the user did just now. Every part of
 * the view model says things through this one.
 */
internal class ActionLog(private val state: MutableStateFlow<UiState>) {

    /** Failures of something the user just did, for a snackbar where they are:
     *  the log in Diagnostics is no place to learn that a share or a delete
     *  did not work. */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** As say, and in a snackbar too: for a failure of something the user
     *  did just now. */
    fun tell(text: String) {
        say(text)
        _notices.tryEmit(text.replaceFirstChar { it.uppercase() })
    }

    /** Named `say` rather than `append` on purpose: inside a buildString block
     *  an `append` member would be shadowed by StringBuilder.append. */
    fun say(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        // NEWEST FIRST. Appending put the answer at the bottom of a log that
        // grows, so pressing a button and reading the result meant scrolling
        // past everything that had happened before it - and pressing "Start
        // recorder" looked like it did nothing at all, because the one line it
        // wrote was below the fold. A log nobody scrolls to is not feedback.
        state.update { current ->
            // Trimmed rather than cleared by hand. There was a Clear button and
            // it is gone: with the newest entry at the top there is nothing to
            // clear PAST, so the button only ever deleted history someone might
            // have wanted. A cap does the one useful thing it did - stop the
            // log growing without bound - without asking anyone to press it.
            current.copy(log = ("[$stamp] $text\n\n" + current.log).take(LOG_LIMIT))
        }
    }
}
