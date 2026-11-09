package com.jemcik.jemrec.ui

import android.util.Log
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.recordings.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What a tap does while the screen is busy. Each opening's setup check
 *  holds busy for seconds, longer with the recorder down, so every action
 *  has to say which. */
internal enum class WhenBusy {
    /** Not run, and its control greyed meanwhile: the screen says it cannot
     *  be done now. A tap while busy is let go, not kept for later; a tap
     *  once the screen is free runs, holding busy. */
    DROPPED,

    /** Waits its turn, then runs holding busy: an answer already given, in a
     *  dialog that has closed, or a control that is never greyed. Dropped,
     *  each of these did nothing without a sign. */
    QUEUED,

    /** Neither waits nor holds busy. */
    BYPASSES,

    /** Waits for the screen to be free, then runs without holding busy: a
     *  start, which the header shows as Starting (StartsUnderWay). */
    WAITS,
}

/**
 * The busy policy, one row an action: what it is called when it fails
 * ("<label> failed: ..." in the log), what it does while busy, and why.
 * Three more wait for the screen to be free and then run an action of the
 * table themselves (Actions.free): a deep link's self-test and its Allow
 * this Wi-Fi, and pairing again's setup check.
 */
internal enum class Action(val label: String, val whenBusy: WhenBusy) {
    /** Diagnostics' Self-test, greyed while busy. */
    SELF_TEST("self test", WhenBusy.DROPPED),

    /** The setup check at every return to the app: one at a time is
     *  enough, and the next return asks again. */
    CHECK_SETUP("check setup", WhenBusy.DROPPED),

    /** "They are on already", on the Developer options card; greyed. */
    DEVELOPER_OPTIONS_ON("check setup", WhenBusy.DROPPED),

    /** The background card's Not now; greyed. */
    BACKGROUND_NOT_NOW("check setup", WhenBusy.DROPPED),

    /** Show the code field again, on the pairing card; greyed. */
    PAIR_FROM_SHADE("pair from the shade", WhenBusy.DROPPED),

    /** Try again, when setup's last step failed; greyed. */
    FINISH_SETUP("finish setup", WhenBusy.DROPPED),

    /** Diagnostics' Start recorder, greyed. */
    START_RECORDER("start recorder", WhenBusy.DROPPED),

    /** Recently deleted's Restore and Restore all, greyed. */
    RESTORE("restore", WhenBusy.DROPPED),
    RESTORE_ALL("restore all", WhenBusy.DROPPED),

    /** Auto-delete's choices, greyed in their dialogs. */
    AUTO_DELETE("auto-delete", WhenBusy.DROPPED),

    /** Messenger calls' caller names switch, greyed. */
    MESSENGER_NAMES("messenger caller names", WhenBusy.DROPPED),

    /** The folder dialog's Use default, greyed. */
    DEFAULT_FOLDER("use default folder", WhenBusy.DROPPED),

    /** Start fresh, said yes to in its dialog. */
    START_FRESH("start fresh", WhenBusy.QUEUED),

    /** Setup's Open settings and Try again on the connection card: Open
     *  settings is never greyed, and goes to Settings at once, so a connect
     *  dropped there never ran while the user was in Settings. */
    CONNECT("connect", WhenBusy.QUEUED),

    /** The record control, which takes a tap while busy (setEnabled): the
     *  switch is saved at once, the screen's part waits. */
    TURN_ON("turn on", WhenBusy.QUEUED),
    TURN_OFF("turn off", WhenBusy.QUEUED),

    /** Hiding Android's debugging notification: setup's answer, and
     *  Settings' switch, which is not greyed - a flip dropped while busy did
     *  nothing (0.15's review). */
    DEBUG_NOTIFICATION("debugging notification", WhenBusy.QUEUED),

    /** Delete all for good, said yes to in its dialog. */
    DELETE_FOR_GOOD("delete for good", WhenBusy.QUEUED),

    /** The selection's Delete, said yes to in its dialog. */
    DELETE_SELECTED("delete recordings", WhenBusy.QUEUED),

    /** A picker's answer - a contact for the rules, a folder - arrives as
     *  the screen resumes, while the resume's check holds busy: dropped,
     *  the contact was picked and nothing was added (measured on an
     *  emulator while 0.12 was made, 2026-10-20). */
    RULE_CONTACT_ADDED("add a contact", WhenBusy.BYPASSES),
    FOLDER_CHOSEN("choose a folder", WhenBusy.BYPASSES),

    /** A chosen contact's x, in Whose calls: one preference written. */
    RULE_CONTACT_REMOVED("remove a contact", WhenBusy.BYPASSES),

    /** Diagnostics' Copy a report: the recorder asked, the clipboard
     *  written, nothing else touched. */
    COPY_REPORT("copy a report", WhenBusy.BYPASSES),

    /** What the recorder is told when a setting changes - the recording
     *  mode, exact call tracking, the messenger apps: at once, as the next
     *  call can come seconds from now. */
    TELL_RECORDER("tell the recorder", WhenBusy.BYPASSES),

    /** The header's Allow this Wi-Fi, and the alert's: taken while busy, as
     *  the record control is, and Starting while it works (allowThisWifi). */
    ALLOW_THIS_WIFI("allow this Wi-Fi", WhenBusy.WAITS),
}

/**
 * Runs each action as its row in [Action] says. Every action that holds
 * busy goes through here, so that the busy flag, the connected flag and the
 * failure's report cannot drift apart.
 */
internal class Actions(
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
    private val log: ActionLog,
) {
    fun run(action: Action, block: suspend () -> Unit) {
        when (action.whenBusy) {
            WhenBusy.DROPPED -> {
                if (state.value.busy) return
                scope.launch { holdingBusy(action.label, block) }
            }
            // A resume's setup check holds the busy flag for seconds (longer
            // with the recorder down), and a delete confirmed meanwhile closed
            // its dialog and did nothing, without a word.
            WhenBusy.QUEUED -> scope.launch {
                free()
                holdingBusy(action.label, block)
            }
            WhenBusy.BYPASSES -> scope.launch { block() }
            WhenBusy.WAITS -> scope.launch {
                free()
                block()
            }
        }
    }

    /** Until the screen is free: for a tap that then runs an action of its
     *  own, which a busy screen would drop. */
    suspend fun free() {
        state.first { !it.busy }
    }

    private suspend fun holdingBusy(label: String, block: suspend () -> Unit) {
        state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e // the screen gone: not a failure to report
        } catch (t: Throwable) {
            Log.w("JemRec", "$label failed", t)
            log.say("$label failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            state.update { it.copy(busy = false, connected = AdbTransport.lastKnownConnected) }
        }
    }
}

/**
 * The recordings a delete said yes to waits its turn or runs for (a
 * QUEUED action). Said yes to twice while the screen is busy - nothing seen
 * to happen, so the user asks again - each counted the same rows, and the
 * second moved them again: "could not move 1" after a delete that worked,
 * or a second "deleted for good". A later yes takes only what no earlier
 * one has; one done lets its rows go.
 */
internal class Claims {
    private val names = mutableSetOf<String>()

    /** Of [rows], those no earlier yes has: now this one's. */
    @Synchronized
    fun take(rows: List<Recording>): List<Recording> = rows.filter { names.add(it.name) }

    /** [rows] done with: a later question may count them again. */
    @Synchronized
    fun letGo(rows: List<Recording>) {
        rows.forEach { names.remove(it.name) }
    }
}
