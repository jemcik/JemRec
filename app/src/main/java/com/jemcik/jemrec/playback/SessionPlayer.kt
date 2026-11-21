package com.jemcik.jemrec.playback

import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import com.jemcik.jemrec.answering.Greeting

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
 * It plays with RecordingPlayer - ExoPlayer since 1.2, cut down to a
 * recording's needs, for both sides in both ears (BothEars); until then a
 * MediaPlayer, the user's choice of 2026-10-23 for its size. Its rules stay
 * here, by PlaybackRules, ExoPlayer's own switched off: pause for a call and
 * resume after it, pause for good when another app takes the audio or the
 * headphones come out.
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
    /** Settings' "Both sides in both ears" (BothEarsSetting), read at each
     *  open: a change while one plays comes through [bothEarsChanged]. */
    private val bothEars: () -> Boolean,
    /** False while a greeting's take may have the microphone (Greeting.takeListening).
     *  The session's Play then does nothing - the notification has no word to
     *  give; the app's own Play says why (Playback.play). */
    private val mayPlay: () -> Boolean = { !Greeting.takeListening },
) : SimpleBasePlayer(looper) {

    private var item: MediaItem? = null
    private var opened = false

    /** The open file's length as known before a reopen of it - a move to the
     *  earpiece, both ears switched - said until the new engine has read
     *  its own. ExoPlayer knows a length only once ready, and until then
     *  the session said none, so the screen folded the player away and put
     *  it back at every move, its earpiece button gone from under the
     *  finger (review, 2026-11-18). MediaPlayer, until 1.2, prepared before
     *  it returned. 0 when there is none to carry. */
    private var carriedMs = 0
    private var error: PlaybackException? = null
    private var playing = PlaybackRules.Playing(wanted = false)
    private var why = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    /** Where it sounds: JemRec's screen moves it (AtTheEar, EarWatch) by
     *  the audio attributes it asks for - voice-call audio for the earpiece.
     *  Each recording starts on the speaker. */
    private var output = Output.SPEAKER

    /** The engine's news is to reach the controllers as a jump in position:
     *  see [changed]. */
    private var putRight = false

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
            .setAudioAttributes(attributes(output))
            .setPlayWhenReady(playing.wanted, why)
            .setPlaybackSuppressionReason(
                if (playing.heldBack) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                else Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            )
            .setPlayerError(error)
        val item = item ?: return state.setPlaybackState(Player.STATE_IDLE).build()
        val length = engine.durationMs.takeIf { it > 0 } ?: carriedMs
        val duration = length.takeIf { opened && it > 0 }?.let { it * 1000L } ?: C.TIME_UNSET
        if (putRight && opened) {
            putRight = false
            state.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_INTERNAL, engine.positionMs.toLong())
        }
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
        output = Output.SPEAKER
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        close()
        item = null
        output = Output.SPEAKER
        return done()
    }

    /**
     * To the earpiece or back, where it was: playing on if it played, paused
     * if not. The file opens again with the new attributes (reopen), and its
     * audio focus is kept, as nothing else has played. Measured on the Honor
     * with MediaPlayer, 2026-11-15: 69-83 ms, heard as smooth.
     *
     * Not changed on the player as it plays: ExoPlayer's setAudioAttributes
     * lets the audio sink's AudioTrack go, with up to 500 ms in it not yet
     * played (DefaultAudioSink.flush), and a seek back to where the sound was
     * is done for nothing when it is the player's own place, to the
     * millisecond (ExoPlayerImplInternal.seekToInternal) - on the Android 17
     * emulator a raise so skipped 390 to 460 ms (2026-11-18). Stopping the
     * player and preparing it again first cost what opening it again does
     * (a new decoder, the file read again), so the one way, opening again,
     * is kept for both: a move and Both sides in both ears (review,
     * 2026-11-18).
     */
    override fun handleSetAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): ListenableFuture<*> {
        val wanted = outputOf(audioAttributes)
        if (wanted == output) return done()
        output = wanted
        if (opened) reopen()
        return done()
    }

    /** Settings' "Both sides in both ears" changed: what plays is opened
     *  again, mixed or not, and goes on from where it was - and, not being
     *  one of the player's own handlers, says so itself: a reopen that
     *  failed reached nobody. */
    fun bothEarsChanged() {
        if (!opened) return
        reopen()
        invalidateState()
    }

    /** Opened again - for the other output (handleSetAudioAttributes), or
     *  the other mix of the two sides (the mix is part of how the player is
     *  built) - going on from where the sound was: the playhead's clock
     *  (PlayClock). The clock
     *  follows the sound: paused, it had 227,149 ms where the player went on
     *  from 227,159, and ExoPlayer's own word was 227,047 (the Honor,
     *  2026-11-17). Until the review of 2026-11-18 this took ExoPlayer's
     *  word, which trails the sound, and went back up to a quarter of a
     *  second. */
    private fun reopen() {
        val uri = item?.localConfiguration?.uri ?: return
        val at = engine.positionMs
        carriedMs = engine.durationMs.takeIf { it > 0 } ?: carriedMs
        // Where it aims, and when: the release checks measure it against
        // where the session said the sound was at that moment, apart from
        // the gap the reopen leaves (emulator_checks.skipped_across).
        Log.i("JemRec", "player: opened again at $at ms, elapsed ${SystemClock.elapsedRealtime()}")
        // Opened there, not opened and then sent: a seek meeting a decoder
        // already up dropped 80 ms after it (RecordingPlayer.open).
        opened = engine.open(uri, output, bothEars(), onChanged = ::changed, onFinished = ::finished, onFailed = ::failed, fromMs = at)
        if (!opened) {
            error = PlaybackException("could not open ${item?.mediaId}", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            playing = PlaybackRules.Playing(wanted = false)
            focus.abandon()
            return
        }
        if (playing.sounding) sound()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val item = item ?: return done()
        // Prepared again after a failure, it starts clean: left set, the
        // error kept the state IDLE over a recording playing.
        error = null
        val uri = item.localConfiguration?.uri
        opened = uri != null && engine.open(uri, output, bothEars(), onChanged = ::changed, onFinished = ::finished, onFailed = ::failed)
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

    /** Playing, if Android gives it the audio - refused during a call - and
     *  no greeting's take may be recording: over the loudspeaker it would go
     *  into the greeting. */
    private fun begin() {
        if (mayPlay() && focus.request()) {
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

    /**
     * The engine has news of its own - the recording's length, known as it
     * opens, its sound under way, or its audio track run dry, the place it is
     * said to be at put back. A controller works the position out from
     * when it was told it plays, which is Play's tap: the sound comes about
     * 0.1 s later, and the session's next word was three seconds on, when the
     * screen's reading went back 109 ms (the Honor, 2026-11-16) and the
     * playhead stood still. A position that only moves on is no news to the
     * session, so it goes as a jump, from where the engine is, and the
     * controllers take it at once. JemRec's own screen reads the engine
     * itself since (SoundPosition); the system's controls still go by this.
     */
    private fun changed() {
        putRight = true
        invalidateState()
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
        carriedMs = 0
        error = null
        playing = PlaybackRules.Playing(wanted = false)
        focus.abandon()
    }

    /** Where the sound of recording [mediaId] is, by the engine - for the
     *  screen (SoundPosition); null unless it is the one open. */
    fun positionOf(mediaId: String): Int? =
        if (opened && item?.mediaId == mediaId) engine.positionMs else null

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
            Player.COMMAND_SET_AUDIO_ATTRIBUTES,
            Player.COMMAND_RELEASE,
        ).build()

        /** What the system's controls may do with it: play, pause, seek -
         *  never choose what plays, nor move it to the earpiece or back,
         *  which are JemRec's alone. */
        val TRANSPORT: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        ).build()

        /** Speech, as RecordingPlayer plays it for [output]: the media
         *  stream, or voice-call audio at the earpiece. The session's volume
         *  keys follow these (Media3 hands them to the platform session). */
        fun attributes(output: Output): AudioAttributes = when (output) {
            Output.SPEAKER -> SPEAKER_ATTRIBUTES
            Output.EARPIECE -> EARPIECE_ATTRIBUTES
        }

        /** Which output [attributes] are for. */
        fun outputOf(attributes: AudioAttributes): Output =
            if (attributes.usage == C.USAGE_VOICE_COMMUNICATION) Output.EARPIECE else Output.SPEAKER

        private val SPEAKER_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        private val EARPIECE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_VOICE_COMMUNICATION)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}
