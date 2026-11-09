package com.jemcik.jemrec.ui

import android.app.Application
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import com.jemcik.jemrec.capture.PlaybackService
import com.jemcik.jemrec.playback.PlaybackRules
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Waveforms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screen's side of playback: the recording loaded, playing or not and
 * where, its waveform, and the player in PlaybackService that plays it,
 * reached as the system's controls reach it (Media3's controller). Main
 * thread, all of it.
 */
internal class Playback(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val log: ActionLog,
    /** How it reaches PlaybackService's session (MainViewModel's). */
    private val connectSession: (Application) -> ListenableFuture<MediaController>,
) {
    /**
     * The media session's player, in PlaybackService, reached as the
     * system's controls reach it. Null until something is played, or until
     * the screen comes forward while the service is up. Main thread.
     */
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val whenConnected = mutableListOf<(MediaController) -> Unit>()
    private var screenUp = true
    private var ticker: Job? = null
    private var waveformJob: Job? = null

    /** What the session does, whoever asked for it: JemRec's own taps, or
     *  the system's controls - a pause from the lock screen, a call holding
     *  it back, the headphones coming out. */
    private val sessionEvents = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = showPlayback()

        override fun onPlayerError(error: PlaybackException) {
            log.tell("could not play that recording")
            stopPlayback()
        }
    }

    /**
     * Play or pause a recording - inside this app, in PlaybackService, so it
     * goes on with the screen off under the system's media controls.
     *
     * It used to fire an ACTION_VIEW and let Android choose, which on this
     * phone meant YouTube Music's preview bar - no close button, and it looked
     * like the app had frozen. The privacy problem was worse: handing a
     * recording of a private call to whichever app claimed the intent.
     */
    fun play(recording: Recording) {
        // Android refuses a player the audio during a call; say why, rather
        // than a tap that does nothing. A pause is never refused: a voice
        // app can hold the call's audio mode with a recording still playing.
        val pausing = state.value.playingName == recording.name && state.value.playing
        if (!pausing && inCall()) {
            log.tell("a recording does not play during a call")
            return
        }
        if (state.value.playingName != recording.name) {
            state.update { it.copy(playingName = recording.name, playing = false, positionMs = 0, durationMs = 0) }
            loadWaveform(recording)
        }
        withController { c ->
            when {
                c.currentMediaItem?.mediaId != recording.name -> {
                    c.setMediaItem(mediaItem(recording))
                    c.prepare()
                    c.play()
                }
                c.isPlaying -> c.pause()
                else -> {
                    // Closed, its item still set: stopped by the system's
                    // controls, or failed with the screen away. Play alone
                    // opens nothing.
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    c.play()
                }
            }
        }
    }

    /** A recording as the session plays it: named for the system's controls
     *  (PlaybackRules.titles), its uri sent as the request's, which reaches
     *  the session where a MediaItem's own does not. */
    private fun mediaItem(recording: Recording): MediaItem {
        val titles = PlaybackRules.titles(recording.caller, recording.app, recording.message, recording.whenLabel)
        return MediaItem.Builder()
            .setMediaId(recording.name)
            .setUri(recording.uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(recording.uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.title).setArtist(titles.subtitle).build())
            .build()
    }

    private fun inCall(): Boolean =
        app.getSystemService(AudioManager::class.java)
            ?.let { it.mode != AudioManager.MODE_NORMAL } == true

    /** [action] on the session's player, once connected - at once if it is. */
    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        whenConnected += action
        if (connecting != null) return
        val future = connectSession(app)
        connecting = future
        future.addListener({
            connecting = null
            val waiting = whenConnected.toList()
            whenConnected.clear()
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                Log.e("JemRec", "playback: no session")
                if (waiting.isNotEmpty()) {
                    log.tell("could not play that recording")
                    stopPlayback()
                }
                return@addListener
            }
            controller = c
            c.addListener(sessionEvents)
            waiting.forEach { it(c) }
            showPlayback()
        }, ContextCompat.getMainExecutor(app))
    }

    /**
     * The screen made to match the session: which recording, playing or
     * not, where. A recording that started while the screen was away - the
     * app reopened while it plays - gets its waveform here.
     */
    private fun showPlayback() {
        val c = controller ?: return
        val name = c.currentMediaItem?.mediaId
        val arrived = name != null && name != state.value.playingName
        state.update {
            if (name == null) {
                it.copy(playingName = null, playing = false, positionMs = 0, durationMs = 0, waveform = null)
            } else {
                it.copy(
                    playingName = name,
                    playing = c.isPlaying,
                    positionMs = c.currentPosition.toInt(),
                    durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET }?.toInt() ?: 0,
                )
            }
        }
        if (arrived) state.value.recordings.firstOrNull { it.name == name }?.let(::loadWaveform)
        if (c.isPlaying && screenUp) tick() else ticker?.cancel()
    }

    /** The waveform of the recording just loaded: the plain scrubber until
     *  it is there, which for a long call can be a few seconds the first time. */
    private fun loadWaveform(recording: Recording) {
        waveformJob?.cancel()
        state.update { it.copy(waveform = null) }
        waveformJob = scope.launch {
            val levels = withContext(Dispatchers.IO) {
                Waveforms.levels(app, recording.name, recording.uri, keepGoing = { isActive })
            } ?: return@launch
            state.update { if (it.playingName == recording.name) it.copy(waveform = levels) else it }
        }
    }

    fun seekTo(ms: Int) {
        controller?.seekTo(ms.toLong())
        state.update { it.copy(positionMs = ms) }
    }

    /** A recording playing, paused: before a greeting take or its preview
     *  (AnswerController's quiet), so the call does not go into the
     *  greeting. */
    fun pause() {
        controller?.pause()
    }

    /**
     * Drives the progress bar: the session's position every 300 ms, while
     * it plays and the screen is up. The end of the recording rewinds it to
     * the start, paused (SessionPlayer); it does not close the row. It used to
     * vanish the moment playback ended - a player that disappears under your
     * thumb as you go to seek back is a small ambush. The chevron is the way
     * out.
     */
    private fun tick() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val c = controller ?: break
                if (!c.isPlaying) break
                state.update {
                    it.copy(
                        playing = true,
                        positionMs = c.currentPosition.toInt(),
                        durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET }?.toInt() ?: it.durationMs,
                    )
                }
                delay(300)
            }
        }
    }

    /** The screen came forward: show what the session plays - it may have
     *  played on, or been paused from the lock screen, while it was away. */
    fun shown() {
        screenUp = true
        if (controller != null) showPlayback() else if (PlaybackService.running) withController { }
    }

    /**
     * Out of sight, the scrubber is no longer driven; the recording plays
     * on, under the system's media controls.
     *
     * Until 0.19 it paused here, and on another tab or Settings too: the
     * scrubber under the row was the only control there was, so a recording
     * that went on playing without it went on with nothing able to stop it -
     * from a pocket, a private call out loud. PlaybackService gave it the
     * system's controls, so it no longer pauses for any of them.
     */
    fun hidden() {
        screenUp = false
        ticker?.cancel()
    }

    /**
     * The list just read: a recording gone from it stops - deleted from its
     * notification, auto-deleted - as a deleted call does not play on. The
     * list holds every recording (only drawing it is paged), so one playing
     * on from before the screen opened is in it.
     */
    fun listRead(recordings: List<Recording>) {
        state.value.playingName?.let { name ->
            if (recordings.none { it.name == name }) stopPlayback()
        }
        // A recording that played on while the screen was away gets its
        // waveform once the list it is in has arrived: the session can name
        // it before that (measured on the Android 17 emulator, 2026-10-23 -
        // the reopened row showed the plain scrubber).
        state.value.let { now ->
            if (now.playingName != null && now.waveform == null && waveformJob?.isActive != true) {
                now.recordings.firstOrNull { it.name == now.playingName }?.let(::loadWaveform)
            }
        }
    }

    /** Stopped, and the row folded back. */
    fun stopPlayback() {
        ticker?.cancel()
        waveformJob?.cancel()
        whenConnected.clear()
        controller?.run {
            stop()
            clearMediaItems()
        }
        state.update {
            it.copy(playing = false, playingName = null, positionMs = 0, durationMs = 0, waveform = null)
        }
    }

    /** The screen is gone for good; a recording playing goes on playing,
     *  under the system's controls, until they stop it. */
    fun close() {
        ticker?.cancel()
        controller?.run {
            removeListener(sessionEvents)
            release()
        }
        connecting?.let(MediaController::releaseFuture)
    }
}
