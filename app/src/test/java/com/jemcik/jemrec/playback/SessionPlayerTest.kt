package com.jemcik.jemrec.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.jemcik.jemrec.answering.Greeting
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
 *  it, over a stand-in for the player (RecordingPlayer, an ExoPlayer since
 *  1.2) and audio focus. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class SessionPlayerTest {

    private class Engine : PlaybackEngine {
        var opensFail = false
        var opened: Uri? = null
        var output: Output? = null
        var opens = 0
        var sounding = false
        val seeks = mutableListOf<Int>()
        var finish: () -> Unit = {}
        var fail: () -> Unit = {}
        private var position = 0

        /** Whether the last open asked for both sides in both ears. */
        var bothEars: Boolean? = null

        /** Ready only when the test says (ready()), as ExoPlayer prepares
         *  as it plays; ready at once otherwise. */
        var readyLater = false
        private var isReady = false
        private var onChanged: () -> Unit = {}

        fun ready() {
            isReady = true
            onChanged()
        }

        override fun open(
            uri: Uri,
            output: Output,
            bothEars: Boolean,
            onChanged: () -> Unit,
            onFinished: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            // As RecordingPlayer's: a new open lets the last one go.
            close()
            if (opensFail) return false
            opens++
            opened = uri
            this.output = output
            this.bothEars = bothEars
            this.onChanged = onChanged
            isReady = !readyLater
            // As the real clock: from the seek, with no lead until the sound
            // is under way again.
            ahead = 0
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
            // As the real clock: from the seek, no lead until under way.
            ahead = 0
        }

        override fun close() {
            opened = null
            sounding = false
        }


        /** How far the clock's place - where the sound is - runs ahead of
         *  the player's own word, which trails it by up to a step (PlayClock). */
        var ahead = 0

        override val positionMs: Int get() = position + ahead
        override val durationMs: Int get() = if (opened != null && isReady) 30_000 else 0
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
    private var bothEars = true
    private val player = SessionPlayer(Looper.getMainLooper(), engine, focus, bothEars = { bothEars })

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

    /** A greeting's take may have the microphone: nothing plays - the media
     *  notification's Play included - as over the loudspeaker it would go into
     *  the greeting; and the audio is not even asked for. */
    @Test fun whileAGreetingIsRecordedItDoesNotPlay() {
        val taking = SessionPlayer(Looper.getMainLooper(), engine, focus, bothEars = { bothEars }, mayPlay = { false })
        taking.setMediaItem(
            MediaItem.Builder().setMediaId("a.ogg").setUri("file:///recordings/a.ogg").build(),
        )
        taking.prepare()
        taking.play()
        idle()
        assertFalse(taking.playWhenReady)
        assertFalse(engine.sounding)
        assertFalse("the audio not taken", focus.held)
        taking.release()
    }

    /** ...as the service makes it: by the process's own mark of a take. */
    @Test fun aTakeHeldAnywhereInTheAppStopsIt() {
        val take = Any()
        Greeting.hold(take)
        try {
            play()
            assertFalse(player.playWhenReady)
            assertFalse(focus.held)
        } finally {
            Greeting.letGo(take)
        }
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

    @Test fun theScreenIsToldWhereTheSoundOfTheOneOpenIs() {
        assertNull(player.positionOf("20261023_140500_in_Olivia.ogg"))
        play()
        player.seekTo(12_345)
        idle()
        assertEquals(12_345, player.positionOf("20261023_140500_in_Olivia.ogg"))
        // Another recording's position is not this one's.
        assertNull(player.positionOf("20261023_150000_in_Marcus.ogg"))
        // Failed, nothing is open to say it.
        engine.fail()
        idle()
        assertNull(player.positionOf("20261023_140500_in_Olivia.ogg"))
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

    private fun moveTo(output: Output) {
        player.setAudioAttributes(SessionPlayer.attributes(output), false)
        idle()
    }

    @Test fun itStartsOnTheSpeaker() {
        play()
        assertEquals(Output.SPEAKER, engine.output)
        assertEquals(Output.SPEAKER, SessionPlayer.outputOf(player.audioAttributes))
    }

    /** At the ear: the file opens again as voice-call audio, where the
     *  sound was, playing on, with the audio focus it already had. */
    @Test fun movedToTheEarpieceItPlaysOnFromWhereItWas() {
        play()
        player.seekTo(12_000)
        idle()
        engine.ahead = 120
        moveTo(Output.EARPIECE)
        assertEquals(Output.EARPIECE, engine.output)
        assertEquals(2, engine.opens)
        assertEquals(12_120, engine.seeks.last())
        assertTrue(engine.sounding)
        assertTrue(player.isPlaying)
        assertTrue(focus.held)
        assertEquals(androidx.media3.common.C.USAGE_VOICE_COMMUNICATION, player.audioAttributes.usage)
        assertEquals(Output.EARPIECE, SessionPlayer.outputOf(player.audioAttributes))
    }

    /** Paused, it opens again where the pause left the sound - the clock's
     *  place, not the player's own word - and stays paused there: a reopen
     *  that skipped its seek while paused passed every test (review,
     *  2026-11-18). */
    @Test fun movedWhilePausedItStaysPaused() {
        play()
        player.seekTo(12_000)
        idle()
        player.pause()
        idle()
        engine.ahead = 120
        moveTo(Output.EARPIECE)
        assertEquals(Output.EARPIECE, engine.output)
        assertEquals(2, engine.opens)
        assertEquals(12_120, engine.seeks.last())
        assertFalse(engine.sounding)
        assertFalse(player.playWhenReady)
        player.play()
        idle()
        assertTrue(engine.sounding)
        assertEquals(Output.EARPIECE, engine.output)
    }

    /** A move opens the file again: its length is said throughout, not
     *  unknown until the new player has read it - the screen folded the
     *  player away and back at every move (review, 2026-11-18). Another
     *  recording carries nothing over. */
    @Test fun aMoveKeepsTheLengthKnown() {
        play()
        assertEquals(30_000L, player.duration)
        engine.readyLater = true
        moveTo(Output.EARPIECE)
        assertEquals(2, engine.opens)
        assertEquals(30_000L, player.duration)
        engine.ready()
        idle()
        assertEquals(30_000L, player.duration)
        player.setMediaItem(MediaItem.Builder().setMediaId("b.ogg").setUri("file:///recordings/b.ogg").build())
        player.prepare()
        idle()
        assertEquals(androidx.media3.common.C.TIME_UNSET, player.duration)
    }

    @Test fun movedBackItPlaysOnTheSpeaker() {
        play()
        moveTo(Output.EARPIECE)
        moveTo(Output.SPEAKER)
        assertEquals(Output.SPEAKER, engine.output)
        assertTrue(engine.sounding)
    }

    @Test fun askedForWhereItAlreadyIsNothingOpensAgain() {
        play()
        moveTo(Output.SPEAKER)
        assertEquals(1, engine.opens)
    }

    /** Before it opens, the move waits for the open. */
    @Test fun movedBeforeItOpensItOpensThere() {
        player.setMediaItem(MediaItem.Builder().setMediaId("a.ogg").setUri("file:///recordings/a.ogg").build())
        moveTo(Output.EARPIECE)
        player.prepare()
        player.play()
        idle()
        assertEquals(Output.EARPIECE, engine.output)
        assertTrue(engine.sounding)
    }

    /** The other mix of the two sides is a player built anew: opened again,
     *  it goes on from where the sound was - the clock's place, which a move
     *  to the earpiece goes back to too - not the player's own word, which
     *  trails the sound by up to a step and went back that much (review,
     *  2026-11-18). */
    @Test fun openedAgainForTheOtherMixItGoesOnFromWhereTheSoundWas() {
        play()
        player.seekTo(12_000)
        idle()
        engine.ahead = 120
        bothEars = false
        player.bothEarsChanged()
        idle()
        assertEquals(2, engine.opens)
        assertEquals(false, engine.bothEars)
        assertEquals(12_120, engine.seeks.last())
        assertTrue(engine.sounding)
    }

    /** A move to the earpiece whose reopen fails: said, and the audio
     *  focus given back - the move's own path (setAudioAttributes), apart
     *  from both ears' below. */
    @Test fun aRecordingThatWillNotOpenAgainIsReportedAndGivesTheAudioBack() {
        play()
        engine.opensFail = true
        moveTo(Output.EARPIECE)
        assertNotNull(player.playerError)
        assertFalse(player.playWhenReady)
        // Not the engine's sounding: a failed open lets the last one go
        // first, so that could not tell (review, 2026-11-18).
        assertFalse(player.isPlaying)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(focus.held)
    }

    @Test fun eachRecordingStartsOnTheSpeaker() {
        play()
        moveTo(Output.EARPIECE)
        player.setMediaItem(MediaItem.Builder().setMediaId("b.ogg").setUri("file:///recordings/b.ogg").build())
        player.prepare()
        player.play()
        idle()
        assertEquals(Output.SPEAKER, engine.output)
        assertEquals(Output.SPEAKER, SessionPlayer.outputOf(player.audioAttributes))
    }

    /** The earpiece is the screen's to choose, at the ear: never a car's or
     *  a lock screen's. */
    @Test fun onlyJemRecMovesItToTheEarpiece() {
        assertTrue(SessionPlayer.COMMANDS.contains(Player.COMMAND_SET_AUDIO_ATTRIBUTES))
        assertFalse(SessionPlayer.TRANSPORT.contains(Player.COMMAND_SET_AUDIO_ATTRIBUTES))
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
    @Test fun bothEarsAsSetReachesThePlayer() {
        play()
        assertEquals(true, engine.bothEars)
        player.stop()
        bothEars = false
        play()
        assertEquals(false, engine.bothEars)
    }

    @Test fun aChangeOfBothEarsGoesOnFromWhereItWas() {
        play()
        player.seekTo(12_000)
        idle()
        bothEars = false
        player.bothEarsChanged()
        idle()
        assertEquals(2, engine.opens)
        assertEquals(false, engine.bothEars)
        assertEquals(12_000, engine.seeks.last())
        assertTrue(engine.sounding)
        assertTrue(player.isPlaying)
        assertTrue(focus.held)
    }

    @Test fun aChangeWhilePausedStaysPaused() {
        play()
        player.pause()
        idle()
        bothEars = false
        player.bothEarsChanged()
        idle()
        assertEquals(2, engine.opens)
        assertFalse(engine.sounding)
        assertFalse(player.isPlaying)
    }

    @Test fun aChangeWhoseReopenFailsSaysSo() {
        play()
        engine.opensFail = true
        bothEars = false
        player.bothEarsChanged()
        idle()
        assertNotNull(player.playerError)
        assertFalse(player.isPlaying)
        assertFalse(focus.held)
    }

    @Test fun aChangeWithNothingOpenOpensNothing() {
        bothEars = false
        player.bothEarsChanged()
        idle()
        assertEquals(0, engine.opens)
    }

    @Test fun theSoundUnderWayPutsTheControllersRight() {
        play()
        val jumps = mutableListOf<Int>()
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                jumps += reason
            }
        })
        engine.seekTo(4_200)
        engine.ready()
        idle()
        assertEquals(listOf(Player.DISCONTINUITY_REASON_INTERNAL), jumps)
        assertEquals(4_200L, player.currentPosition)
        // Once: the next state has no jump in it.
        player.pause()
        idle()
        assertEquals(1, jumps.size)
    }

    @Test fun theLengthComesWhenThePlayerIsReady() {
        engine.readyLater = true
        play()
        assertEquals(C.TIME_UNSET, player.duration)
        engine.ready()
        idle()
        assertEquals(30_000L, player.duration)
    }
}
