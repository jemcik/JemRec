package com.jemcik.jemrec.recordings

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.AfterCall

/**
 * The folder chosen for recordings lost - [WHY_LOST] - and said: on home
 * and in Settings while it lasts (UiState.folderLost, from [lost]), and once
 * in a notification at the first save it sent elsewhere, for whoever is not
 * opening JemRec. Until 1.2 a save fell back
 * without a word, and the folder's recordings left the list: for Honor's
 * file names, the Phone app's play buttons went with them.
 */
internal object FolderAccess {

    private const val TAG = "JemRec"
    private const val KEY_TOLD = "folder_lost_told"

    /** What may have happened to it, said the same in the notification, on
     *  home and in Settings: two of them had lost the drive (reviews,
     *  2026-11-18). */
    const val WHY_LOST = "moved, deleted, its drive taken out, or its access taken back"

    /** Among the problems' ids (AfterCall's 31 and 33, MissedCalls' 32,
     *  RecorderHealth's 34, BackgroundKill's 35). */
    private const val NOTIFICATION_ID = 36

    /** Whether the folder chosen is lost now; the folder there again, what
     *  was said of it goes. */
    fun lost(context: Context): Boolean = when (RecordingStore.folderReachable(context)) {
        null -> false
        true -> { reached(context); false }
        false -> true
    }

    /** A save gone to [where] instead of the folder chosen: said once, if
     *  the folder is lost - a passing failure with the folder there is the
     *  log's alone. */
    fun savedElsewhere(context: Context, where: String) {
        // The folder looked at, read first: chosen anew or let go of since,
        // nothing is said (tellOnce).
        val tree = RecordingStore.treeUri(context)
        if (RecordingStore.folderReachable(context) != false) {
            Log.w(TAG, "store: saved to $where though the folder chosen is there")
            return
        }
        if (Prefs.of(context).getBoolean(KEY_TOLD, false)) return
        runCatching {
            // The words before the lock, as the PendingIntents below (review
            // round 2): reached(), on the main thread, must not wait on what
            // another thread asks Android under it.
            val text = "JemRec can no longer reach ${RecordingStore.chosenWords(RecordingStore.describe(context))} " +
                "- $WHY_LOST - so this call was saved in $where. " +
                "Choose the folder again in JemRec."
            // The tap: home, whose card says it too; the button: Android's
            // folder picker, at once. Built before the lock too: their
            // PendingIntents ask Android's activity manager (review round 3).
            val tap = PendingIntent.getActivity(context, 0, DeepLink.home(context), PendingIntent.FLAG_IMMUTABLE)
            val choose = PendingIntent.getActivity(
                context, 3601, DeepLink.intent(context, DeepLink.Kind.CHOOSE_FOLDER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            tellOnce(context, AfterCall.problem(context, "Recording saved elsewhere", text, tap, "Choose again" to choose).build(), tree)
        }.onFailure { Log.w(TAG, "store: could not say the folder is lost", it) }
    }

    /** Said once, under the lock reached() takes it back under: two saves
     *  gone elsewhere at once found the flag clear and both posted. Only the
     *  check, the notify and the flag are under it - and the folder: another
     *  chosen, or none, since [tree] was looked at, and nothing is said
     *  (review round 4). The same folder chosen again in that moment is not
     *  seen here: home's next reading takes the notification back (lost). */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun tellOnce(context: Context, notification: android.app.Notification, tree: android.net.Uri?) {
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(KEY_TOLD, false) || RecordingStore.treeUri(context) != tree) return
        val seen = AfterCall.notifyProblem(context, NOTIFICATION_ID, notification, "store")
        // Told once - if it could be seen; else at the next save.
        if (seen) prefs.edit { putBoolean(KEY_TOLD, true) }
    }

    /** The folder there again, or another chosen: what was said goes. */
    @Synchronized
    fun reached(context: Context) {
        val prefs = Prefs.of(context)
        if (!prefs.getBoolean(KEY_TOLD, false)) return
        prefs.edit { remove(KEY_TOLD) }
        AfterCall.cancelProblem(context, NOTIFICATION_ID)
    }
}
