package com.jemcik.jemrec.capture

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The session's player, driven as the system's controls and JemRec drive
 *  it, over a stand-in MediaPlayer and audio focus. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class SessionPlayerTest {

    private class Engine : PlaybackEngine {
        var opensFail = false
        var opened: Uri? = null
        var sounding = false
        val seeks = mutableListOf<Int>()
        var finish: () -> Unit = {}
        var fail: () -> Unit = {}
        private var position = 0

        override fun open(uri: Uri, onFinished: () -> Unit, onFailed: () -> Unit): Boolean {
            if (opensFail) return false
            opened = uri
            finish = onFinished
            fail = onFailed
            position = 0
            return true
        }

        override fun start(): Boolean {
            sounding = opened != null
            return sounding
        }

        override fun pause() {
            sounding = false
        }

        override fun seekTo(ms: Int) {
            seeks += ms
            position = ms
        }

        override fun close() {
            opened = null
            sounding = false
        }

        override val positionMs: Int get() = position
        override val durationMs: Int get() = if (opened != null) 30_000 else 0
    }

    private class Focus(var grants: Boolean = true) : FocusHolder {
        var held = false

        override fun request(): Boolean {
            if (grants) held = true
            return grants
        }

        override fun abandon() {
            held = false
        }
    }

    private val engine = Engine()
    private val focus = Focus()
    private val player = SessionPlayer(Looper.getMainLooper(), engine, focus)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun play() {
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId("20261023_140500_in_Olivia.ogg")
                .setUri("file:///recordings/20261023_140500_in_Olivia.ogg")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Olivia Bennett").setArtist("Phone call · Today 14:05").build())
                .build(),
        )
        player.prepare()
        player.play()
        idle()
    }

    @Test fun itPlaysWhatJemRecGivesIt() {
        play()
        assertTrue(player.isPlaying)
        assertTrue(engine.sounding)
        assertTrue(focus.held)
        assertEquals(30_000L, player.duration)
        assertEquals("Olivia Bennett", player.mediaMetadata.title.toString())
    }

    @Test fun aRingingCallHoldsItBackAndItPlaysOnAfter() {
        play()
        player.onFocusChange(PlaybackRules.Focus.LOSS_TRANSIENT)
        idle()
        assertFalse(player.isPlaying)
        assertFalse(engine.sounding)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, player.playbackSuppressionReason)
        player.onFocusChange(PlaybackRules.Focus.GAIN)
        idle()
        assertTrue(player.isPlaying)
        assertTrue(engine.sounding)
    }

    @Test fun anotherAppTakingTheAudioPausesItForGood() {
        play()
        player.onFocusChange(PlaybackRules.Focus.LOSS)
        idle()
        assertFalse(player.playWhenReady)
        assertFalse(focus.held)
        player.onFocusChange(PlaybackRules.Focus.GAIN)
        idle()
        assertFalse(player.isPlaying)
        assertFalse(engine.sounding)
    }

    @Test fun headphonesOutPauseIt() {
        play()
        player.becameNoisy()
        idle()
        assertFalse(player.playWhenReady)
        assertFalse(engine.sounding)
    }

    @Test fun refusedTheAudioDuringACallItDoesNotPlay() {
        focus.grants = false
        play()
        assertFalse(player.playWhenReady)
        assertFalse(engine.sounding)
    }

    @Test fun theEndRewindsToTheStartPausedAndGivesTheAudioBack() {
        play()
        engine.finish()
        idle()
        assertFalse(player.playWhenReady)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertEquals(listOf(0), engine.seeks)
        assertEquals("20261023_140500_in_Olivia.ogg", player.currentMediaItem?.mediaId)
        assertFalse(focus.held)
        player.play()
        idle()
        assertTrue(engine.sounding)
    }

    @Test fun anErrorWhilePlayingIsReportedAndNothingIsLeftOpen() {
        play()
        engine.fail()
        idle()
        assertNotNull(player.playerError)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertNull(engine.opened)
        assertFalse(focus.held)
    }

    @Test fun aRecordingThatWillNotOpenIsReportedAndTakesNoAudio() {
        engine.opensFail = true
        play()
        assertNotNull(player.playerError)
        assertFalse(engine.sounding)
        // Granted with nothing to play, the focus took another app's music
        // away for good.
        assertFalse(focus.held)
    }

    @Test fun preparedAgainAfterAFailureItPlaysWithNoErrorLeft() {
        play()
        engine.fail()
        idle()
        player.prepare()
        player.play()
        idle()
        assertNull(player.playerError)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.isPlaying)
        assertTrue(engine.sounding)
    }

    @Test fun lockedItIsCalledOnlyCallRecording() {
        play()
        player.locked = true
        idle()
        assertEquals("Call recording", player.mediaMetadata.title.toString())
        assertNull(player.mediaMetadata.artist)
        player.locked = false
        idle()
        assertEquals("Olivia Bennett", player.mediaMetadata.title.toString())
        assertEquals("Phone call · Today 14:05", player.mediaMetadata.artist.toString())
    }

    @Test fun aSeekGoesWhereItWasAsked() {
        play()
        player.seekTo(12_345)
        idle()
        assertEquals(12_345, engine.seeks.last())
    }

    @Test fun stoppedAndClearedNothingIsLeftOpenOrHeld() {
        play()
        player.stop()
        player.clearMediaItems()
        idle()
        assertNull(engine.opened)
        assertFalse(focus.held)
        assertNull(player.currentMediaItem)
    }

    @Test fun theSystemsControlsPlayAndSeekButNeverChooseWhatPlays() {
        assertTrue(SessionPlayer.TRANSPORT.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(SessionPlayer.TRANSPORT.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(SessionPlayer.TRANSPORT.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(SessionPlayer.TRANSPORT.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        // One recording, so no skip buttons beside play.
        assertFalse(SessionPlayer.COMMANDS.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(SessionPlayer.COMMANDS.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }
}
