package com.jemcik.jemrec.ui

import android.app.Application
import android.app.StatusBarManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.jemcik.jemrec.answering.AnsweringMachine
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.RecordingStore
import com.jemcik.jemrec.recordings.Retention
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.Looks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * What Settings and the screens under it change, and the permission answers
 * that change what the lists may read. Each setting is written at once; the
 * recorder is told where it needs to be, and the screen reads again where a
 * setting changes what it shows.
 */
internal class SettingsActions(
    private val app: Application,
    private val state: MutableStateFlow<UiState>,
    private val log: ActionLog,
    private val actions: Actions,
    private val reading: Reading,
    private val answer: AnswerController,
    private val trash: TrashActions,
) {
    /**
     * Show who called as saved, wherever it is saved from. Its grant runs to
     * its end once begun (Setup.grantNotificationAccess), and saves the switch
     * on after it: JemRec closed from Recents and opened again within the
     * grant's seconds read it off, and showed it off until the screen was read
     * again.
     */
    private val stopWatchingNames = VoipCallNotes.watchEnabled(app) { on ->
        state.update { it.copy(voipNames = on) }
    }

    /** The screen is gone: the switch is no longer watched. */
    fun close() {
        stopWatchingNames()
    }

    /**
     * The newest release on GitHub, in the browser (discussion #91): what
     * there is to install, beside the version Settings shows. The browser
     * makes the request; JemRec makes none, and needs no permission for it.
     */
    fun openNewestVersion() {
        val opened = runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, NEWEST_RELEASE.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        opened.onFailure {
            Log.w("JemRec", "settings: no app to open the newest release - ${it.javaClass.simpleName}")
            log.tell("No browser on this phone to open it")
        }
    }

    /** Never offered again once waved away. An optional feature that keeps
     *  asking is not optional. */
    fun dismissCallLogOffer() {
        CallLogLookup.dismissBanner(app)
        state.update { it.copy(offerCallLog = false) }
    }

    /**
     * The user has answered the audio-access request, either way.
     *
     * Granted, the recordings from before the reinstall appear on the next
     * refresh. Refused, the app carries on recording and seeing everything it
     * makes from now on - which is why nothing here treats "no" as a problem.
     */
    fun onAudioAccessResult() {
        state.update { it.copy(canReadAudio = AudioAccess.granted(app)) }
        reading.refresh()
    }

    /**
     * The permission dialog has closed, whichever way and whichever of the two
     * were granted.
     *
     * The verdict is read back from the system rather than taken from the
     * callback: the user can grant one and refuse the other, and the system is
     * the only thing that knows which.
     */
    fun onCallLogResult() {
        // Anything refused counts as an answer. Asking again next time the app
        // opens would be nagging, which is what the offer exists to avoid.
        if (CallLogLookup.anythingLeftToOffer(app)) CallLogLookup.dismissBanner(app)
        state.update {
            it.copy(canReadCallLog = CallLogLookup.granted(app), offerCallLog = false)
        }
        reading.refresh()
    }

    /**
     * Switch between recording every call and being asked each time.
     *
     * Written straight through rather than deferred, because the very next call
     * could arrive seconds from now and it must use the setting the user just
     * chose, not the one this screen last read.
     */
    fun setAutomatic(automatic: Boolean) {
        RecordingMode.set(
            app,
            if (automatic) RecordingMode.AUTOMATIC else RecordingMode.ON_DEMAND,
        )
        state.update { it.copy(automatic = automatic) }
        // The daemon decides at off-hook whether to record or to ask first, so
        // it has to be told which the moment this changes.
        actions.run(Action.TELL_RECORDER) { CaptureDaemon.pushMode(app) }
    }

    /** The Settings switch for the notification that follows each call.
     *  Problems are still reported with it off - see AfterCall. */
    fun setAfterCallNotice(on: Boolean) {
        AfterCall.setEnabled(app, on)
        state.update { it.copy(afterCallNotice = on) }
    }

    /** The app repaints at once: both activities draw from Looks, and
     *  Settings reads the choice from the theme (LocalLookChoice). */
    fun setLook(look: Look) {
        Looks.set(app, Looks.choice(app).copy(look = look))
        // Multitrack's caller colours are given only while it is the look.
        if (look == Look.MULTITRACK) reading.refresh()
    }

    fun setLightDark(lightDark: LightDark) {
        Looks.set(app, Looks.choice(app).copy(lightDark = lightDark))
    }

    /** One of auto-delete's two rules changed: saved, and applied at once. */
    fun setAutoDelete(afterDays: Int? = null, shorterThan: Int? = null) = actions.run(Action.AUTO_DELETE) {
        afterDays?.let { Retention.setMaxAgeDays(app, it) }
        shorterThan?.let { Retention.setMinSeconds(app, it) }
        val result = withContext(Dispatchers.IO) { Retention.run(app) }
        if (result.deleted > 0) log.say("auto-delete moved ${result.deleted} recording(s) to Recently deleted")
        // Recordings Android wants confirmed are asked about now, while the
        // user is here; the daily pass cannot ask.
        result.needsConsent?.let { trash.askAndroid(it, result.disputed) }
        reading.now()
    }

    /** Whose calls to keep. Applied to calls from now on; see CallRules. */
    fun setRuleContacts(contacts: CallRules.Contacts) {
        CallRules.setContacts(app, contacts)
        state.update { it.copy(ruleContacts = contacts) }
        // The chosen contacts are read only for the choices that list them.
        reading.refresh()
    }

    fun setRuleOtherNumbers(on: Boolean) {
        CallRules.setOtherNumbers(app, on)
        state.update { it.copy(ruleOtherNumbers = on) }
    }

    /**
     * The contact the picker returned, added to the rules' list - while
     * busy too (Action.RULE_CONTACT_ADDED): coming back from the picker
     * resumes the screen, and the resume's own setup check holds busy.
     */
    fun addRuleContact(contact: Uri) {
        actions.run(Action.RULE_CONTACT_ADDED) {
            val added = withContext(Dispatchers.IO) { CallRules.add(app, contact) }
            if (!added) log.tell("could not add that contact")
            reading.now()
        }
    }

    fun removeRuleContact(key: String) {
        actions.run(Action.RULE_CONTACT_REMOVED) {
            withContext(Dispatchers.IO) { CallRules.remove(app, key) }
            reading.now()
        }
    }

    /**
     * Whether exact call tracking off takes the answering machine with it:
     * on as the screen has it, or as the preferences have it - before the
     * first reading the screen's copy says off (AnswerUi's default). Settings'
     * question (SettingsDialog.TRACKING_OFF) and the switching off below ask
     * this one rule: the question asked only the screen's copy, and before
     * the first reading the machine went off unasked.
     */
    fun trackingOffTakesTheMachine(): Boolean = answer.state.value.enabled || AnsweringMachine.enabled(app)

    /** Exact call tracking on or off; the daemon allows or withdraws it at once. */
    fun setCallTracking(on: Boolean) {
        CallTracking.setEnabled(app, on)
        state.update { it.copy(callTracking = on) }
        // The answering machine answers on tracking's word alone: left on
        // without it, it would say it answers and never would. Settings asks
        // before this (SettingsDialog.TRACKING_OFF).
        if (!on && trackingOffTakesTheMachine()) {
            answer.setEnabled(false)
            log.say("the answering machine is off too: it needs exact call tracking")
        }
        answer.reload()
        actions.run(Action.TELL_RECORDER) {
            if (!CaptureDaemon.setTracking(app)) {
                log.say("the recorder will pick up exact call tracking when it next restarts")
            }
        }
    }

    /** Record [pkg]'s calls, or stop; the daemon is told at once. */
    fun setVoipApp(pkg: String, on: Boolean) {
        VoipApps.setEnabled(app, pkg, on)
        state.update { it.copy(voipEnabled = VoipApps.enabled(app)) }
        // The last one off stops the caller-name listener; the first one on
        // starts it again, if names were asked for.
        DebugNotificationCleaner.apply(app)
        actions.run(Action.TELL_RECORDER) {
            if (!CaptureDaemon.setVoipApps(app)) {
                log.say("the recorder will pick up the messenger apps when it next restarts")
            }
        }
    }

    /** Caller names for messenger calls, from their call notifications. Needs
     *  notification access, granted as the debugging one's is
     *  (Setup.grantNotificationAccess). */
    fun setVoipNames(on: Boolean) = actions.run(Action.MESSENGER_NAMES) {
        if (on && !DebugNotificationCleaner.accessGranted(app)) {
            val problem = Setup.grantNotificationAccess(app)
            if (problem != null) {
                state.update { it.copy(voipNamesProblem = problem) }
                return@run
            }
        }
        VoipCallNotes.setEnabled(app, on)
        DebugNotificationCleaner.apply(app)
        state.update { it.copy(voipNames = on, voipNamesProblem = null) }
    }

    /** How new recordings are named. Ones already saved keep their names. */
    fun setFileNames(format: FileNames.Format) {
        FileNames.setFormat(app, format)
        state.update { it.copy(fileNames = format) }
    }

    fun useDefaultFolder() = actions.run(Action.DEFAULT_FOLDER) {
        RecordingStore.clearTree(app)
        log.say("recordings will go to the standard Recordings folder")
        reading.refresh()
    }

    /** While busy too, as addRuleContact: the picker's answer arrives as the
     *  screen resumes, while its check runs (Action.FOLDER_CHOSEN). */
    fun onFolderChosen(uri: Uri) {
        actions.run(Action.FOLDER_CHOSEN) {
            withContext(Dispatchers.IO) { RecordingStore.setTree(app, uri) }
            log.say("recordings will be saved to the folder you chose")
            reading.now()
        }
    }

    /**
     * Android's answer to "add the tile": added, already there, or declined.
     * The answer is what Android knows, so it settles what the Settings row
     * says - and "already there" is said back, so a tap is never met with
     * nothing, which is what an added tile's row did before.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun onTileAddAnswer(result: Int) {
        val added = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> true
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> false
            else -> return
        }
        RecorderTile.setAdded(app, added)
        state.update { it.copy(tileAdded = added) }
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
            log.tell("it is in your Quick Settings panel already")
        }
    }
}

/** GitHub's newest release of JemRec, not a pre-release: its page has the
 *  APK. */
internal const val NEWEST_RELEASE = "https://github.com/jemcik/JemRec/releases/latest"
