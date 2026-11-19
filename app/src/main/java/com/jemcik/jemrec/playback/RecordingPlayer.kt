package com.jemcik.jemrec.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ogg.OggExtractor

/** What SessionPlayer plays a recording with: RecordingPlayer on the phone,
 *  a stand-in in the tests. */
internal interface PlaybackEngine {
    /** Opens [uri] for [output], with both sides in both ears or not
     *  ([bothEars]), ready to start from the beginning; false if it will not
     *  open. [onChanged] when what it says changes by itself - its length
     *  known, its sound under way after a start or a seek, its audio track
     *  run dry (PlayClock.underrun), playing or paused - [onFinished] at
     *  its end, [onFailed] on an error, opening included, which may come
     *  after this returns. */
    fun open(
        uri: Uri,
        output: Output,
        bothEars: Boolean,
        onChanged: () -> Unit,
        onFinished: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean

    /** False if it would not start: after an error the player stays down. */
    fun start(): Boolean
    fun pause()
    fun seekTo(ms: Int)
    fun close()


    /** Where the sound is for the screen and the session: smoothed while it
     *  plays (PlayClock). */
    val positionMs: Int

    val durationMs: Int
}

/**
 * Plays recordings inside the app.
 *
 * WHY NOT JUST HAND THE FILE TO ANOTHER APP
 *
 * The first version fired an ACTION_VIEW and let Android choose. On this phone
 * that meant YouTube Music's audio preview - a bar with no close button, which
 * looked like the app had frozen, and could only be escaped with Back.
 *
 * That is bad enough as an experience. The privacy side is worse: these are
 * recordings of phone calls, and delegating them means granting a third-party
 * app - Google's, in this case - read access to the audio of a private
 * conversation, every time the user taps one. A call recorder should not hand
 * its recordings to anything without being asked to.
 *
 * So playback stays here, and sharing stays a separate, deliberate action.
 * Since 0.19 it plays in PlaybackService, under the system's media controls,
 * so it goes on with the screen off; SessionPlayer drives this.
 *
 * EXOPLAYER, SINCE 1.2. Until then this was a MediaPlayer, and MediaPlayer
 * cannot put one channel into the other: setVolume(left, right) only scales
 * each, and nothing in android.media.audiofx mixes. ExoPlayer can, with
 * Media3's ChannelMixingAudioProcessor (BothEars) - Android's own MediaPlayer
 * page recommends Media3 for new playback, and JemRec had Media3's session
 * already. It is cut down to what a recording needs, the Ogg extractor and
 * one audio renderer: measured on the release APK, 2026-11-16, 511,036
 * bytes more (10,240,823 to 10,751,859); the default factories had come to
 * about a megabyte on 2026-10-23.
 *
 * What the MediaPlayer did, and how each still holds:
 * - Its audio attributes set before it played: speech, the media stream, or
 *   voice-call audio at the earpiece (SessionPlayer.attributes - the same
 *   ones). The mode is never set here: the answering machine and the
 *   recorder read MODE_IN_COMMUNICATION as another app's call (CallTracker,
 *   Voip). A move between the two opens the file again with them, as
 *   MediaPlayer did (SessionPlayer.handleSetAudioAttributes says why not
 *   in place).
 * - The CPU kept while it plays with the screen off: MediaPlayer decoded in
 *   the media server; ExoPlayer decodes here and holds a wake lock while it
 *   plays (WAKE_MODE_LOCAL, Media3's default, said; WAKE_LOCK is in the
 *   manifest).
 * - No audio focus and no becoming-noisy of its own: SessionPlayer does both
 *   (PlaybackRules), so ExoPlayer's are off.
 * - [seekTo] lands where it is asked, not on the sync point before it:
 *   SeekParameters.EXACT, as SEEK_CLOSEST was (MediaPlayer's plain seekTo
 *   was SEEK_PREVIOUS_SYNC). Measured on the Honor, 2026-11-16: ExoPlayer and
 *   MediaPlayer landed on the same millisecond at five seeks in a 542.7 s call.
 * - At its end it stops and says so once ([onFinished]); a later start plays
 *   it again. ExoPlayer would stay set to play at its end - a seek back to
 *   the start would then play it again by itself - so it is paused first.
 * - An error while playing says so ([onFailed]) and the player stays down:
 *   [start] is false after it. A file that will not open says so from open()
 *   itself, as MediaPlayer's prepare did: ExoPlayer prepares as it goes, and
 *   would say it a moment later, after SessionPlayer had taken the audio
 *   focus for it - another app's music paused for nothing - so open() reads
 *   the file's first byte. One that opens and then will not decode still
 *   says so a moment later, on the same thread, and SessionPlayer shows it
 *   the way it did.
 * - [close] lets it go, and an earlier player's word never reaches a later
 *   one: each listens only while it is the one open.
 * - [positionMs] and [durationMs] 0 when there is nothing to say; and the
 *   position read whenever it is asked, as MediaPlayer's was: ExoPlayer's
 *   own moves in steps, so it is the clock's while the sound plays and
 *   where the clock had it at a pause (PlayClock), and SessionPlayer is told
 *   as the sound gets under way.
 */
@OptIn(UnstableApi::class)
internal class RecordingPlayer(private val context: Context) : PlaybackEngine {

    private var player: ExoPlayer? = null
    private val clock = PlayClock()

    /** The ExoPlayer open, for the JVM tests to run Media3's own way
     *  (TestPlayerRunHelper). */
    @androidx.annotation.VisibleForTesting
    internal val exoPlayer: ExoPlayer? get() = player

    /** What the JVM tests add to the player's making: Media3's FakeClock,
     *  which moves as the player waits, where time does not pass by itself
     *  (ExoPlayer.Builder.setClock, for tests alone). */
    @androidx.annotation.VisibleForTesting
    internal var forTests: (ExoPlayer.Builder) -> Unit = {}

    /** The time the clock (PlayClock) reads: the player's own, Media3's
     *  Clock - on the phone elapsedRealtime and the wall clock, as it was; in
     *  the tests the FakeClock the player runs on. Read apart, the under-way
     *  start Media3 gives on the player's clock was turned into nonsense on
     *  the wall clock, and no test reached the clock at all (review round 9). */
    @androidx.annotation.VisibleForTesting
    internal var time: androidx.media3.common.util.Clock = androidx.media3.common.util.Clock.DEFAULT

    /** The clock under way: the sound going, the place read from it. */
    @androidx.annotation.VisibleForTesting
    internal val clockUnderWay: Boolean get() = clock.underWay

    override val positionMs: Int
        get() {
            val made = player ?: return 0
            return clock.position(made.currentPosition, made.isPlaying, time.elapsedRealtime(), lengthOf(made)).toInt()
        }

    override val durationMs: Int
        get() = player?.let { lengthOf(it).toInt() } ?: 0


    /** How long [made] plays, in ms; 0 while that is not known. */
    private fun lengthOf(made: ExoPlayer): Long = made.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    override fun open(
        uri: Uri,
        output: Output,
        bothEars: Boolean,
        onChanged: () -> Unit,
        onFinished: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        close()
        return runCatching {
            context.contentResolver.openInputStream(uri).use { check(it != null && it.read() >= 0) { "nothing to read" } }
            val made = build(output, bothEars)
            player = made
            clock.startsFrom(0)
            made.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                    // Not one sent before a pause or a seek that has landed
                    // since: it would set the clock going over a sound that
                    // stands (review, 2026-11-18).
                    if (player !== made || !made.isPlaying) return
                    // Media3 gives the start on the wall clock; the position
                    // runs on elapsedRealtime, which a clock change leaves be.
                    clock.underWay(time.elapsedRealtime() - (time.currentTimeMillis() - playoutStartSystemTimeMs))
                    onChanged()
                }

                override fun onAudioUnderrun(
                    eventTime: AnalyticsListener.EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long,
                ) {
                    if (player !== made) return
                    // Every one, said - the check's no_underrun reads this,
                    // and an underrun the clock had nothing to put right for
                    // starved the sound all the same (review, 2026-11-18).
                    Log.i(TAG, "player: underrun, $bufferSizeMs ms of track, $elapsedSinceLastFeedMs ms since its last feed")
                    if (clock.underrun(made.currentPosition)) {
                        Log.i(TAG, "player: the audio track ran dry; the playhead goes on from the player's word")
                        onChanged()
                    }
                }
            })
            made.addListener(object : Player.Listener {
                private var ready = false

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== made) return
                    when (playbackState) {
                        Player.STATE_READY -> if (!ready) {
                            ready = true
                            onChanged()
                        }
                        Player.STATE_ENDED -> {
                            clock.stopped()
                            made.pause()
                            onFinished()
                        }
                        // Opening or seeking, and let go: nothing to say.
                        Player.STATE_BUFFERING, Player.STATE_IDLE -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== made || isPlaying) return
                    clock.stopped()
                    // Stopped by itself - buffering - not paused: the session
                    // told, or it said playing on until its next word, three
                    // seconds on (review, 2026-11-18).
                    if (made.playWhenReady) onChanged()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== made) return
                    Log.e(TAG, "player: error ${error.errorCodeName}", error)
                    onFailed()
                }
            })
            made.setMediaItem(MediaItem.fromUri(uri))
            made.prepare()
            Log.i(TAG, if (bothEars) "player: open, both sides in both ears" else "player: open, each side in its own ear")
            true
        }.getOrElse {
            Log.e(TAG, "player: could not play $uri", it)
            close()
            false
        }
    }

    override fun start(): Boolean {
        val made = player ?: return false
        if (made.playerError != null) return false
        // Paused, it goes on from where the pause left the sound (PlayClock);
        // already set to play, the sound is under way and the clock with it.
        if (!made.playWhenReady) clock.resumes(made.currentPosition)
        made.play()
        return true
    }

    override fun pause() {
        val made = player ?: return
        // Read before the pause: pausing, ExoPlayer tells its listener it
        // stopped, and the clock's place would be gone (PlayClock).
        clock.pausedAt(made.currentPosition, time.elapsedRealtime(), lengthOf(made))
        made.pause()
    }

    override fun seekTo(ms: Int) {
        val made = player ?: return
        clock.startsFrom(ms.toLong())
        made.seekTo(ms.toLong())
    }

    override fun close() {
        val made = player ?: return
        player = null
        made.release()
    }

    /** An ExoPlayer for a recording: Ogg in, one audio renderer out, the
     *  two sides mixed into both ears at [BothEars.GAIN] each when
     *  [bothEars]. Any other number of channels plays as it is: the mixer
     *  refuses a count it has no matrix for, and the file would not play. */
    private fun build(output: Output, bothEars: Boolean): ExoPlayer {
        val processors: Array<AudioProcessor> = if (bothEars) {
            arrayOf(
                ChannelMixingAudioProcessor().apply {
                    for (channels in 1..MOST_CHANNELS) {
                        putChannelMixingMatrix(
                            if (channels == 2) {
                                ChannelMixingMatrix(2, 2, BothEars.MATRIX)
                            } else {
                                ChannelMixingMatrix(channels, channels, FloatArray(channels * channels) { if (it % (channels + 1) == 0) 1f else 0f })
                            },
                        )
                    }
                },
            )
        } else {
            emptyArray()
        }
        val sink = DefaultAudioSink.Builder(context).setAudioProcessors(processors).build()
        val renderers = RenderersFactory { handler, _, audio, _, _ ->
            arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audio, sink))
        }
        val sources = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context), ExtractorsFactory { arrayOf(OggExtractor()) })
        return ExoPlayer.Builder(context, renderers, sources)
            .setAudioAttributes(SessionPlayer.attributes(output), /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(false)
            .setSeekParameters(SeekParameters.EXACT)
            // A wake lock held only while it plays, for playing with the
            // screen off in a foreground service (ExoPlayer.Builder.setWakeMode).
            // Media3 1.11.1 holds it so by default already (ExoPlayerImpl,
            // "Enable wake lock handling by default"): said here so a later
            // Media3 or a builder option cannot take it away unseen.
            // MediaPlayer needed none, as its decoding ran in the media
            // server; ExoPlayer's runs on this app's playback thread.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .also(forTests)
            .build()
    }

    private companion object {
        const val TAG = "JemRec"

        /** Ogg Opus's surround mapping, family 1, goes up to 8 channels
         *  (RFC 7845, 5.1.1.2). JemRec records two. */
        const val MOST_CHANNELS = 8
    }
}
