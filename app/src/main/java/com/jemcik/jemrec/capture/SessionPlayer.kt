package com.jemcik.jemrec.capture

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Audio focus as SessionPlayer asks for it: AndroidAudioFocus on the
 *  phone, a stand-in in the tests. */
internal interface FocusHolder {
    /** True if granted. Refused during a phone call - Android's audio-focus
     *  guide: then "your app should not proceed with audio playback". */
    fun request(): Boolean
    fun abandon()
}

/**
 * The recording being played, as a Media3 Player: what the media session in
 * PlaybackService shows the system's controls - and JemRec's own screen,
 * through a MediaController - and takes their play, pause and seek from.
 *
 * It plays with RecordingPlayer's MediaPlayer, not ExoPlayer: the user's
 * choice (2026-10-23), measured on the release APK as 0.56 MB added against
 * 1.54 MB, and it keeps the exact seeking measured for MediaPlayer. So what
 * ExoPlayer would do by itself is done here, by PlaybackRules: pause for a
 * call and resume after it, pause for good when another app takes the audio
 * or the headphones come out.
 *
 * One recording at a time: setting another replaces it. Its end rewinds to
 * the start, paused, ready to play again - as the row's player did before.
 * [locked] swaps the caller's name for PlaybackRules.LOCKED_TITLE.
 *
 * SimpleBasePlayer is Media3's unstable API: it may change between Media3
 * versions, so the version is pinned (app/build.gradle.kts) and an update
 * is one to test, SessionPlayerTest first.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(
    looper: Looper,
    private val engine: PlaybackEngine,
    private val focus: FocusHolder,
) : SimpleBasePlayer(looper) {

    private var item: MediaItem? = null
    private var opened = false
    private var error: PlaybackException? = null
    private var playing = PlaybackRules.Playing(wanted = false)
    private var why = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    /** Whether the phone is locked: PlaybackService tells it. */
    var locked = false
        set(value) {
            if (field == value) return
            field = value
            invalidateState()
        }

    override fun getState(): State {
        val state = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setAudioAttributes(ATTRIBUTES)
            .setPlayWhenReady(playing.wanted, why)
            .setPlaybackSuppressionReason(
                if (playing.heldBack) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                else Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            )
            .setPlayerError(error)
        val item = item ?: return state.setPlaybackState(Player.STATE_IDLE).build()
        val duration = engine.durationMs.takeIf { opened && it > 0 }?.let { it * 1000L } ?: C.TIME_UNSET
        return state
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(item.mediaId)
                        .setMediaItem(item)
                        .setMediaMetadata(shown(item.mediaMetadata))
                        .setDurationUs(duration)
                        .setIsSeekable(true)
                        .build(),
                ),
            )
            .setPlaybackState(if (opened && error == null) Player.STATE_READY else Player.STATE_IDLE)
            .setContentPositionMs { if (opened) engine.positionMs.toLong() else 0L }
            .build()
    }

    private fun shown(metadata: MediaMetadata): MediaMetadata {
        val unlocked = PlaybackRules.Titles(metadata.title?.toString().orEmpty(), metadata.artist?.toString())
        val titles = PlaybackRules.shown(unlocked, locked)
        return MediaMetadata.Builder().setTitle(titles.title).setArtist(titles.subtitle).build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        close()
        item = mediaItems.firstOrNull()
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        close()
        item = null
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val item = item ?: return done()
        // Prepared again after a failure, it starts clean: left set, the
        // error kept the state IDLE over a recording playing.
        error = null
        val uri = item.localConfiguration?.uri
        opened = uri != null && engine.open(uri, onFinished = ::finished, onFailed = ::failed)
        if (!opened) {
            error = PlaybackException("could not open ${item.mediaId}", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            playing = PlaybackRules.Playing(wanted = false)
        } else if (playing.wanted) {
            begin()
        }
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        why = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        when {
            !playWhenReady -> {
                playing = PlaybackRules.Playing(wanted = false)
                engine.pause()
            }
            opened -> begin()
            // Asked before it is open: handlePrepare begins it. The audio is
            // not asked for yet - granted with nothing to play, it took the
            // audio from another app for good, and its music stayed paused.
            else -> playing = PlaybackRules.Playing(wanted = true)
        }
        return done()
    }

    /** Playing, if Android gives it the audio: refused during a call. */
    private fun begin() {
        if (focus.request()) {
            playing = PlaybackRules.Playing(wanted = true)
            sound()
        } else {
            playing = PlaybackRules.Playing(wanted = false)
            why = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (opened) engine.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs.toInt())
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        close()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        close()
        return done()
    }

    /** Called by AndroidAudioFocus, on this player's looper. */
    fun onFocusChange(change: PlaybackRules.Focus) {
        val before = playing
        playing = PlaybackRules.onFocus(change, playing)
        if (change == PlaybackRules.Focus.LOSS) {
            if (before.wanted) why = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            focus.abandon()
        }
        follow()
    }

    /** Headphones out or a headset gone: PlaybackService tells it. */
    fun becameNoisy() {
        if (!playing.wanted) return
        playing = PlaybackRules.onBecomingNoisy(playing)
        why = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
        follow()
    }

    /** The engine made to match [playing], and the change made known. */
    private fun follow() {
        if (opened) {
            if (playing.sounding) sound() else engine.pause()
        }
        invalidateState()
    }

    private fun sound() {
        if (!engine.start()) failed()
    }

    /** The end: back to the start, paused, the recording still loaded. Its
     *  audio focus is given back - nothing is left to play. */
    private fun finished() {
        engine.seekTo(0)
        playing = PlaybackRules.Playing(wanted = false)
        why = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
        focus.abandon()
        invalidateState()
    }

    private fun failed() {
        engine.close()
        opened = false
        playing = PlaybackRules.Playing(wanted = false)
        error = PlaybackException("could not play ${item?.mediaId}", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        focus.abandon()
        invalidateState()
    }

    private fun close() {
        engine.close()
        opened = false
        error = null
        playing = PlaybackRules.Playing(wanted = false)
        focus.abandon()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    companion object {
        /** No skipping to another recording, and no shuffle or repeat: the
         *  system's controls show play or pause, and the seek bar. */
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_RELEASE,
        ).build()

        /** What the system's controls may do with it: play, pause, seek -
         *  never choose what plays, which is JemRec's alone. */
        val TRANSPORT: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        ).build()

        /** Speech, on the media stream: as RecordingPlayer plays it. */
        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}
