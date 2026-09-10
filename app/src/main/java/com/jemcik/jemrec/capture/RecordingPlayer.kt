package com.jemcik.jemrec.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

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
 *
 * Speech, not music: USAGE_MEDIA with CONTENT_TYPE_SPEECH routes to the media
 * stream rather than the call stream, which is what a person expects when they
 * play a recording back with the phone to their ear or on speaker.
 */
class RecordingPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    /** Which recording is loaded, playing or paused. Null when nothing is. */
    var current: Uri? = null
        private set

    var isPlaying: Boolean = false
        private set

    val positionMs: Int
        get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    val durationMs: Int
        get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    /**
     * Play this recording, or pause it if it is the one already playing.
     *
     * Tapping the row that is currently playing should stop it - anything else
     * means there is no way to stop a recording except letting it finish.
     */
    fun toggle(uri: Uri, onFinished: () -> Unit) {
        if (current == uri && player != null) {
            if (isPlaying) pause() else resume()
            return
        }
        release()
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, uri)
                setOnCompletionListener {
                    this@RecordingPlayer.isPlaying = false
                    onFinished()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "player: error what=$what extra=$extra")
                    this@RecordingPlayer.isPlaying = false
                    onFinished()
                    true
                }
                prepare()
                start()
            }
            current = uri
            isPlaying = true
        }.onFailure {
            Log.e(TAG, "player: could not play $uri", it)
            release()
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        isPlaying = false
    }

    private fun resume() {
        runCatching { player?.start() }
        isPlaying = true
    }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms) }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        current = null
        isPlaying = false
    }

    private companion object {
        const val TAG = "JemRec"
    }
}
