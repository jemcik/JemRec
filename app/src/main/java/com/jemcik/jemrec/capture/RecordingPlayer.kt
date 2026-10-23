package com.jemcik.jemrec.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/** What SessionPlayer plays a recording with: RecordingPlayer on the phone,
 *  a stand-in in the tests. */
internal interface PlaybackEngine {
    /** Opens [uri], ready to start from the beginning; false if it will not
     *  open. [onFinished] at its end, [onFailed] on an error while playing. */
    fun open(uri: Uri, onFinished: () -> Unit, onFailed: () -> Unit): Boolean

    /** False if it would not start: after an error the player stays down. */
    fun start(): Boolean
    fun pause()
    fun seekTo(ms: Int)
    fun close()
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
 * Speech, not music: USAGE_MEDIA with CONTENT_TYPE_SPEECH routes to the media
 * stream rather than the call stream, which is what a person expects when they
 * play a recording back with the phone to their ear or on speaker.
 */
internal class RecordingPlayer(private val context: Context) : PlaybackEngine {

    private var player: MediaPlayer? = null

    override val positionMs: Int
        get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    override val durationMs: Int
        get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    override fun open(uri: Uri, onFinished: () -> Unit, onFailed: () -> Unit): Boolean {
        close()
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, uri)
                setOnCompletionListener { onFinished() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "player: error what=$what extra=$extra")
                    onFailed()
                    true
                }
                prepare()
            }
            true
        }.getOrElse {
            Log.e(TAG, "player: could not play $uri", it)
            close()
            false
        }
    }

    /** After an error the player will not start again: playing only if it
     *  did, rather than a pause icon and a running clock over silence. */
    override fun start(): Boolean = player != null && runCatching { player?.start() }.isSuccess

    override fun pause() {
        runCatching { player?.pause() }
    }

    /** To [ms] itself. The plain seekTo(int) is SEEK_PREVIOUS_SYNC (Android's
     *  MediaPlayer docs): the sync frame at or before it, which can leave
     *  the player short of where the scrubber was tapped. */
    override fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) }
    }

    override fun close() {
        runCatching { player?.release() }
        player = null
    }

    private companion object {
        const val TAG = "JemRec"
    }
}
