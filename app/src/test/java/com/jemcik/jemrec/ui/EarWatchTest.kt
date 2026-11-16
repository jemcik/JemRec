package com.jemcik.jemrec.ui

import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.os.PowerManager
import com.jemcik.jemrec.playback.Output
import com.jemcik.jemrec.playback.PlayThrough
import com.jemcik.jemrec.playback.PlayThroughSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor
import java.time.Duration

/** Listening at the ear on a phone: the sensor, the lock that darkens the
 *  screen, headphones, and what reaches the session's player. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class EarWatchTest {

    private val app = RuntimeEnvironment.getApplication()
    private val sensors = shadowOf(app.getSystemService(SensorManager::class.java))
    private val power = shadowOf(app.getSystemService(PowerManager::class.java))
    private val audio = shadowOf(app.getSystemService(AudioManager::class.java))
    /** The Honor's: 0 near, 5 far. */
    private val proximity: Sensor = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY).also { shadowOf(it).setMaximumRange(5f) }

    private var now = 0L
    /** What the watch did on the player, in order. */
    private val done = mutableListOf<String>()
    private var offered: Boolean? = null
    private var covered = false
    private val controls = object : EarWatch.Controls {
        override fun moveTo(output: Output) {
            done += "move $output"
        }

        override fun pause() {
            done += "pause"
        }

        override fun offered(offered: Boolean) {
            this@EarWatchTest.offered = offered
        }

        override fun cover(covered: Boolean) {
            this@EarWatchTest.covered = covered
        }
    }

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @Before fun aPhoneWithAnEarpieceAndASensor() {
        audio.setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        sensors.addSensor(proximity)
        power.setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
    }

    private fun watch() = EarWatch(app, controls, clock = { now })

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun read(near: Boolean) {
        sensors.sendSensorEventToListeners(SensorEventBuilder.newBuilder(proximity, floatArrayOf(if (near) 0f else 5f)).build())
        idle()
    }

    private fun watched() = sensors.getListeners().isNotEmpty()

    /** Android's audio mode, as a call sets it: its listeners told. */
    private fun mode(mode: Int) {
        app.getSystemService(AudioManager::class.java).mode = mode
    }

    private fun darkening(): Boolean = ShadowPowerManager.getLatestWakeLock()?.let { it.isHeld && shadowOf(it).tag == "jemrec:ear" } == true

    /** On screen, playing on the speaker. */
    private fun playing(): EarWatch = watch().apply {
        screen(true)
        player("a.ogg", wanted = true, output = Output.SPEAKER, heldBack = false)
        idle()
    }

    /** ...and raised, the session now at the earpiece. */
    private fun raised(): EarWatch = playing().apply {
        now = 600
        read(true)
        player("a.ogg", wanted = true, output = Output.EARPIECE, heldBack = false)
        idle()
    }

    /** Lowered to answer, raised for the call: under the call's screen the
     *  sensor follows the phone, so the recording plays on at the earpiece -
     *  the Honor played it 1.8 s from the loudspeaker at the ear first
     *  (2026-11-16). Answered is Android's audio mode, in call. */
    @Test fun heldBackByACallItFollowsThePhoneToTheEarpiece() {
        val watch = playing()
        // The call rings and holds it back; its screen comes up.
        mode(AudioManager.MODE_RINGTONE)
        watch.player("a.ogg", wanted = true, output = Output.SPEAKER, heldBack = true)
        watch.screen(false)
        idle()
        assertFalse("not while it only rings", watched())
        // Answered.
        now = 1000
        mode(AudioManager.MODE_IN_CALL)
        idle()
        assertTrue(watched())
        // Raised for the call: moved while it is held back.
        now = 1600
        read(true)
        assertEquals(listOf("move EARPIECE"), done)
        assertTrue(darkening())
        // The call over: Android's mode normal, then the audio given back.
        now = 2000
        mode(AudioManager.MODE_NORMAL)
        watch.player("a.ogg", wanted = true, output = Output.EARPIECE, heldBack = false)
        idle()
        assertTrue("watched until it is lowered", watched())
        assertTrue(darkening())
        // Lowered: the loudspeaker, and nothing watched off the screen.
        now = 2600
        read(false)
        assertEquals(listOf("move EARPIECE", "move SPEAKER"), done)
        assertFalse(watched())
    }

    /** Ringing in a pocket, the recording playing there off the screen:
     *  nothing watched, nothing moved - only a call answered is followed. */
    @Test fun aCallOnlyRingingMovesNothing() {
        val watch = watch()
        watch.player("a.ogg", wanted = true, output = Output.SPEAKER, heldBack = false)
        mode(AudioManager.MODE_RINGTONE)
        watch.player("a.ogg", wanted = true, output = Output.SPEAKER, heldBack = true)
        idle()
        assertFalse(watched())
        // Missed: the audio given back, still in the pocket.
        mode(AudioManager.MODE_NORMAL)
        watch.player("a.ogg", wanted = true, output = Output.SPEAKER, heldBack = false)
        idle()
        assertFalse(watched())
        assertEquals(emptyList<String>(), done)
    }

    /** A call JemRec's answering machine takes (call screening), or one
     *  whose audio goes to another device: the user is not on it at the
     *  phone, which may be in a pocket - nothing watched. A messenger call
     *  is followed as a phone call is. */
    @Test fun onlyACallTheUserIsOnIsFollowed() {
        val watch = watch()
        watch.player("a.ogg", wanted = true, output = Output.SPEAKER, heldBack = true)
        for (m in listOf(AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_CALL_REDIRECT, AudioManager.MODE_COMMUNICATION_REDIRECT)) {
            mode(m)
            idle()
            assertFalse("mode $m", watched())
        }
        mode(AudioManager.MODE_IN_COMMUNICATION)
        idle()
        assertTrue("a messenger call", watched())
    }

    /** A mode change already posted when the screen closes for good runs
     *  after close(): it must not start the sensor or take the lock again -
     *  nothing would let them go, and the lock renews itself. */
    @Test fun aChangeQueuedBeforeCloseStartsNothingAfterIt() {
        val watch = raised()
        // Told now, run on the main thread later - after close().
        mode(AudioManager.MODE_IN_CALL)
        watch.close()
        idle()
        assertFalse(watched())
        assertFalse(darkening())
    }

    @Test fun playingOnScreenItWatchesTheSensorAndTheScreenCanGoDark() {
        playing()
        assertTrue(watched())
        assertTrue(darkening())
        assertEquals(true, offered)
    }

    @Test fun raisedItMovesToTheEarpiece() {
        raised()
        assertEquals(listOf("move EARPIECE"), done)
    }

    /** At the ear, JemRec's screen is covered; lowered, uncovered. */
    @Test fun atTheEarTheScreenIsCovered() {
        raised()
        assertTrue(covered)
        now = 1200
        read(false)
        assertFalse(covered)
    }

    @Test fun aChosenEarpieceIsCoveredAtTheEar() {
        val w = playing()
        w.choose(Output.EARPIECE)
        w.player("a.ogg", wanted = true, output = Output.EARPIECE, heldBack = false)
        idle()
        assertTrue("watched, to cover", watched())
        assertFalse(covered)
        read(true)
        assertTrue(covered)
        read(false)
        assertFalse(covered)
    }

    /** Stopped, or ended, at the ear: still covered - where the phone does
     *  not darken the screen it would light at the cheek - until lowered. */
    @Test fun stoppedAtTheEarTheCoverStaysUntilLowered() {
        val w = raised()
        w.player(null, wanted = false, output = Output.SPEAKER, heldBack = false)
        idle()
        assertTrue(covered)
        now = 1500
        read(false)
        assertFalse(covered)
    }

    /** Lowered: back to the loudspeaker, playing on, still watched for the
     *  next raise. */
    @Test fun loweredItGoesBackToTheLoudspeaker() {
        val w = raised()
        now = 1200
        read(false)
        assertEquals(listOf("move EARPIECE", "move SPEAKER"), done)
        w.player("a.ogg", wanted = true, output = Output.SPEAKER, heldBack = false)
        idle()
        assertTrue(watched())
        assertTrue(darkening())
    }

    @Test fun aReadingRightAfterPlayCountsOnceTheQuietStartIsOver() {
        playing()
        now = 100
        read(true)
        assertEquals(emptyList<String>(), done)
        now = 500
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(listOf("move EARPIECE"), done)
    }

    @Test fun headphonesInNothingIsWatchedAndTheEarpieceIsNotOffered() {
        playing()
        audio.addOutputDevice(device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), true)
        idle()
        assertEquals(false, offered)
        assertFalse(watched())
        assertFalse(darkening())
        read(true)
        assertEquals(emptyList<String>(), done)
    }

    @Test fun bluetoothInTheEarpieceIsNotOffered() {
        audio.addOutputDevice(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), false)
        playing()
        assertEquals(false, offered)
    }

    @Test fun offScreenNothingIsWatchedAndTheScreenIsLetBe() {
        val w = playing()
        w.screen(false)
        idle()
        assertFalse(watched())
        assertFalse(darkening())
    }

    /** Left at the ear - a cheek's swipe home: at the earpiece, the screen
     *  darkened and uncovered, until the phone comes down. */
    @Test fun leftAtTheEarItStaysAtTheEarpieceUntilLowered() {
        val w = raised()
        w.screen(false)
        idle()
        assertEquals(listOf("move EARPIECE"), done)
        assertTrue(watched())
        assertTrue(darkening())
        assertFalse(covered)
        now = 1500
        read(false)
        assertEquals(listOf("move EARPIECE", "move SPEAKER"), done)
        assertFalse(watched())
        assertFalse(darkening())
    }

    /** Headphones plugged in while it is left at the ear still take it. */
    @Test fun headphonesTakeItOffTheScreenToo() {
        val w = raised()
        w.screen(false)
        audio.addOutputDevice(device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), true)
        idle()
        assertEquals(listOf("move EARPIECE", "move SPEAKER"), done)
    }

    /** Reopened while it plays at an earpiece the button chose: there it
     *  stays, never to the loudspeaker because a screen came back. */
    @Test fun reopenedWhileTheEarpiecePlaysItStaysThere() {
        val w = watch()
        w.screen(true)
        w.player("a.ogg", wanted = true, output = Output.EARPIECE, heldBack = false)
        idle()
        assertEquals(emptyList<String>(), done)
        assertTrue(darkening())
    }

    @Test fun theButtonMovesItAndBack() {
        val w = playing()
        w.choose(Output.EARPIECE)
        w.player("a.ogg", wanted = true, output = Output.EARPIECE, heldBack = false)
        w.choose(Output.SPEAKER)
        assertEquals(listOf("move EARPIECE", "move SPEAKER"), done)
    }

    @Test fun onTheLoudspeakerTheSensorMovesNothing() {
        PlayThroughSetting.set(app, PlayThrough.LOUDSPEAKER)
        val w = playing()
        assertFalse(watched())
        assertFalse(darkening())
        w.choose(Output.EARPIECE)
        idle()
        assertTrue("the chosen earpiece still darkens the screen", darkening())
        // Watched now only to cover the screen at the ear: a lowering moves
        // nothing.
        assertTrue(watched())
        read(true)
        read(false)
        assertEquals(listOf("move EARPIECE"), done)
    }

    @Test fun turnedToTheLoudspeakerInSettingsTheSensorIsLetGo() {
        val w = playing()
        w.playThrough(PlayThrough.LOUDSPEAKER)
        idle()
        assertFalse(watched())
    }

    /** "Earpiece" in Settings: a recording opens there, the screen goes dark
     *  at the ear, and the sensor is not watched. */
    @Test fun theEarpieceSettingOpensAtTheEarpiece() {
        PlayThroughSetting.set(app, PlayThrough.EARPIECE)
        val w = watch()
        w.screen(true)
        idle()
        assertTrue(w.startsOnEarpiece())
        w.player("a.ogg", wanted = true, output = Output.EARPIECE, heldBack = false)
        idle()
        assertEquals(emptyList<String>(), done)
        assertTrue("watched, only to cover", watched())
        assertTrue(darkening())
        read(true)
        read(false)
        assertEquals(emptyList<String>(), done)
    }

    @Test fun withHeadphonesInNothingOpensAtTheEarpiece() {
        PlayThroughSetting.set(app, PlayThrough.EARPIECE)
        audio.addOutputDevice(device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), false)
        val w = watch()
        w.screen(true)
        idle()
        assertFalse(w.startsOnEarpiece())
    }

    @Test fun withoutASensorTheButtonStillMovesIt() {
        sensors.removeSensor(proximity)
        val w = playing()
        assertFalse(watched())
        w.choose(Output.EARPIECE)
        assertEquals(listOf("move EARPIECE"), done)
        assertFalse(darkening())
        assertFalse(EarWatch.canRaise(app))
    }

    @Test fun aPhoneWithoutAnEarpieceOffersNothingAndMovesNothing() {
        audio.setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        val w = playing()
        assertEquals(false, offered)
        assertFalse(watched())
        w.choose(Output.EARPIECE)
        assertEquals(emptyList<String>(), done)
        assertFalse(EarWatch.canRaise(app))
    }

    @Test fun thisPhoneCanRaise() {
        assertTrue(EarWatch.canRaise(app))
    }

    /** Stopped: the lock is let go (its release waits for the phone to come
     *  down), and the sensor is watched only until then, for the cover. */
    @Test fun stoppedEverythingIsLetGoOnceLowered() {
        val w = raised()
        w.player(null, wanted = false, output = Output.SPEAKER, heldBack = false)
        idle()
        assertFalse(darkening())
        assertTrue(watched())
        now = 1500
        read(false)
        assertFalse(watched())
    }

    /** Closed with the phone at the ear: nothing left to see it lowered, so
     *  paused and put back on the loudspeaker - not left at the earpiece, nor
     *  out loud at an ear. */
    @Test fun closedAtTheEarItIsPausedOnTheLoudspeaker() {
        val w = raised()
        w.close()
        assertEquals(listOf("move EARPIECE", "pause", "move SPEAKER"), done)
        assertFalse(watched())
        assertFalse(darkening())
    }

    @Test fun closedAtAChosenEarpieceItIsLeftThere() {
        val w = playing()
        w.choose(Output.EARPIECE)
        w.player("a.ogg", wanted = true, output = Output.EARPIECE, heldBack = false)
        w.close()
        assertEquals(listOf("move EARPIECE"), done)
    }

    /** A headset or car kit for calls only (Bluetooth SCO without A2DP)
     *  takes no media: the earpiece is still offered. */
    @Test fun aCallOnlyHeadsetIsNotHeadphones() {
        audio.addOutputDevice(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), false)
        playing()
        assertEquals(true, offered)
        assertTrue(watched())
    }

    /** Headphones are read from the start: a Play before the screen comes
     *  up - a notification's, as JemRec opens - knows them. */
    @Test fun headphonesAreKnownBeforeTheScreenComesUp() {
        PlayThroughSetting.set(app, PlayThrough.EARPIECE)
        audio.addOutputDevice(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), false)
        assertFalse(watch().startsOnEarpiece())
    }
}
