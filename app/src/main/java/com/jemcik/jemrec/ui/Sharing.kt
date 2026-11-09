package com.jemcik.jemrec.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.jemcik.jemrec.audio.Transcoder
import com.jemcik.jemrec.recordings.AppRecordings
import com.jemcik.jemrec.recordings.Recording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share: the format asked first, then the original at once or an .m4a made
 * for it, handed to Android's share sheet - for a recording on either tab,
 * or from a notification's Share.
 */
internal class Sharing(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val log: ActionLog,
) {
    /** The in-flight .m4a conversion, so its spinner's Cancel can stop it. */
    private var conversionJob: Job? = null

    /**
     * Tapping share picks a format first, converting nothing yet.
     *
     * A recording is Opus in an Ogg on disk - fine for Android and desktop, but
     * Apple's stock apps will not open it. Only the .m4a route needs the
     * transcode, so the choice comes BEFORE any work: sharing the original is
     * instant, and the (slower, blocking) convert happens only when it is what
     * the user picked.
     */
    fun share(recording: Recording) = state.update { it.copy(shareChoice = recording) }

    fun dismissShareChoice() = state.update { it.copy(shareChoice = null) }

    /** Share the Opus original as-is: no conversion, so it is instant. */
    fun shareOriginal(recording: Recording) {
        state.update { it.copy(shareChoice = null) }
        openShareSheet(recording.uri, "audio/ogg")
    }

    /** Convert to AAC in an .m4a first - so it plays on any device - showing a
     *  spinner while it works, then hand the result to the share sheet. */
    fun shareUniversal(recording: Recording) {
        state.update { it.copy(shareChoice = null, converting = true) }
        conversionJob = scope.launch {
            val m4a = withContext(Dispatchers.Default) {
                val dir = File(app.getExternalFilesDir(null), "shares").apply { mkdirs() }
                sweepStaleShares(dir)
                val out = File(dir, recording.name.substringBeforeLast('.') + ".m4a")
                // isActive goes false the instant the job is cancelled, which is
                // how Cancel reaches the transcode loop.
                if (Transcoder.toM4a(app, recording.uri, out) { !isActive }) out else null
            }
            state.update { it.copy(converting = false) }
            if (m4a == null) {
                log.tell("could not prepare that recording to share")
                return@launch
            }
            val uri = AppRecordings.uriOf(app, m4a)
            openShareSheet(uri, "audio/mp4")
        }
    }

    /**
     * Abort a running .m4a conversion - the spinner's Cancel, or a back/scrim
     * tap on it.
     *
     * Cancelling the job flips isActive false, so the transcode breaks out and
     * sweeps its half-written file; the coroutine then unwinds without reaching
     * the share sheet or the failure message. The spinner is taken down here
     * because that unwinding will not run the line that normally does it.
     */
    fun cancelConversion() {
        conversionJob?.cancel()
        conversionJob = null
        state.update { it.copy(converting = false) }
    }

    private fun openShareSheet(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "Share recording")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(chooser) }
            .onFailure { log.tell("could not share that recording") }
    }

    /** Delete share copies older than a few hours. A share in flight is minutes
     *  old at most, so the cutoff never sweeps one still being read. */
    private fun sweepStaleShares(dir: File) {
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
