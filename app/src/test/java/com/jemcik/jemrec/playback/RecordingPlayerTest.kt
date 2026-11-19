package com.jemcik.jemrec.playback

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import com.jemcik.jemrec.recordings.OggSamples
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RecordingPlayer off the phone, on Media3's own ExoPlayer: the Ogg file read
 * by its extractor, decoded by Media3's stand-in codec for Robolectric
 * (ShadowMediaCodecConfig) and played into Robolectric's AudioTrack, run as
 * Media3's own tests run a player (TestPlayerRunHelper). What it says and when
 * - ready once, its end once and paused there, played again after it, an
 * error - where its place is after a pause and a seek, and that a player let
 * go says nothing more. No test reached it before 1.2's reviews (P6-4).
 *
 * What these cannot reach is the clock under way (PlayClock): Media3 says the
 * sound is under way (onAudioPositionAdvancing) from the audio track's own
 * position, and Robolectric's track never moves it - measured here
 * (2026-11-18), the event never came in five seconds of play. The clock's
 * rules are PlayClockTest's; the clock under way on a real audio track is the
 * emulator checks' (both_ears, raise_to_listen: each move's aim against the
 * clock, and a stall of the sound).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordingPlayerTest {

    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val context = RuntimeEnvironment.getApplication()
    // On Media3's FakeClock, which moves as the player waits, as Media3's own
    // playback tests run: time does not pass by itself here.
    private val fakeClock = androidx.media3.test.utils.FakeClock(/* isAutoAdvancing = */ true)
    private val player = RecordingPlayer(context).apply {
        forTests = { it.setClock(fakeClock) }
        time = fakeClock
    }
    private var changed = 0
    private var finished = 0
    private var failed = 0

    @After fun tearDown() = player.close()

    /** [seconds] of 20 ms Opus packets, each page at where its packet ends
     *  and the last marked the stream's end (RFC 7845), after the headers of
     *  a recording the Honor saved (pre-skip 312). The bytes after each TOC
     *  are made up: the stand-in codec passes them through. */
    private fun recording(seconds: Int): Uri {
        val headers = OggSamples.honorHeaders
        val serial = ByteBuffer.wrap(headers, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val packets = seconds * 50
        val audio = (0 until packets).map { i ->
            val body = ByteArray(80) { if (it == 0) 0xFC.toByte() else (it + i).toByte() }
            OggSamples.page(serial, seq = i + 2, granule = 960L * (i + 1), type = if (i == packets - 1) 4 else 0, body = body)
        }
        val file = File(context.cacheDir, "recording-$seconds.ogg")
        file.writeBytes(headers + audio.reduce { a, b -> a + b })
        return Uri.fromFile(file)
    }

    private fun open(uri: Uri, bothEars: Boolean = true) = player.open(
        uri, Output.SPEAKER, bothEars,
        onChanged = { changed++ }, onFinished = { finished++ }, onFailed = { failed++ },
    )

    private fun exo() = checkNotNull(player.exoPlayer)

    @Test fun itOpensSaysReadyOnceAndKnowsItsLength() {
        assertTrue(open(recording(3)))
        TestPlayerRunHelper.run(exo()).untilState(Player.STATE_READY)
        assertEquals(1, changed)
        assertTrue("about 3 s: ${player.durationMs}", player.durationMs in 2_900..3_100)
        assertEquals(0, player.positionMs)
        assertTrue(ShadowLog.getLogsForTag("JemRec").any { it.msg == "player: open, both sides in both ears" })
    }

    @Test fun eachSideInItsOwnEarIsSaid() {
        assertTrue(open(recording(1), bothEars = false))
        assertTrue(ShadowLog.getLogsForTag("JemRec").any { it.msg == "player: open, each side in its own ear" })
    }

    /** At its end it stops - paused, so a seek back does not play it by
     *  itself - and says so once; a start plays it again. */
    @Test fun atItsEndItStopsAndSaysSoOnce() {
        assertTrue(open(recording(2)))
        assertTrue(player.start())
        TestPlayerRunHelper.run(exo()).untilState(Player.STATE_ENDED)
        assertEquals(1, finished)
        assertFalse("paused at its end", exo().playWhenReady)
        player.seekTo(0)
        TestPlayerRunHelper.run(exo()).untilState(Player.STATE_READY)
        assertFalse("not playing by itself", exo().isPlaying)
        assertEquals(1, finished)
    }

    /** Paused, its place stays where the pause left it; a seek lands where
     *  it is asked (SeekParameters.EXACT). */
    @Test fun aPauseHoldsItsPlaceAndASeekLandsWhereAsked() {
        assertTrue(open(recording(5)))
        assertTrue(player.start())
        TestPlayerRunHelper.play(exo()).untilPosition(0, 1_000)
        player.pause()
        val held = player.positionMs
        assertTrue("about a second in: $held", held in 900..1_300)
        TestPlayerRunHelper.run(exo()).untilPendingCommandsAreFullyHandled()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("paused, the place stays", held, player.positionMs)
        player.seekTo(3_000)
        assertEquals(3_000, player.positionMs)
        TestPlayerRunHelper.run(exo()).untilState(Player.STATE_READY)
        assertEquals(3_000L, exo().currentPosition)
    }

    /** A file with nothing in it is refused from open() itself, before
     *  anything takes the audio focus for it. */
    /** Ended, a start plays it again from its start (ExoPlayer would stay
     *  set to play at its end). */
    @Test fun aStartAfterItsEndPlaysItAgain() {
        assertTrue(open(recording(1)))
        assertTrue(player.start())
        TestPlayerRunHelper.run(exo()).untilState(Player.STATE_ENDED)
        assertEquals(1, finished)
        player.seekTo(0)
        assertTrue(player.start())
        TestPlayerRunHelper.run(exo()).untilState(Player.STATE_ENDED)
        assertEquals("played again to its end", 2, finished)
    }

    @Test fun aFileWithNothingInItIsRefused() {
        val empty = File(context.cacheDir, "empty.ogg").apply { writeBytes(ByteArray(0)) }
        assertFalse(open(Uri.fromFile(empty)))
        assertNull(player.exoPlayer)
        assertFalse(player.start())
        assertEquals(0, player.positionMs)
    }

    /** One that opens and then will not decode says so, and stays down. */
    @Test fun aFileThatWillNotPlaySaysSo() {
        val junk = File(context.cacheDir, "junk.ogg").apply { writeBytes(ByteArray(4096) { 7 }) }
        assertTrue(open(Uri.fromFile(junk)))
        TestPlayerRunHelper.run(exo()).untilPlayerError()
        assertEquals(1, failed)
        assertFalse(player.start())
    }

    /** Let go, it says nothing more and has no place; a second opened after
     *  it is the only one heard. */
    @Test fun aPlayerLetGoSaysNothingMore() {
        assertTrue(open(recording(2)))
        val first = exo()
        player.close()
        assertEquals(0, player.positionMs)
        assertEquals(0, player.durationMs)
        assertTrue(open(recording(1)))
        TestPlayerRunHelper.run(exo()).untilState(Player.STATE_READY)
        assertEquals("only the second's ready", 1, changed)
        assertTrue(first !== exo())
    }
}
