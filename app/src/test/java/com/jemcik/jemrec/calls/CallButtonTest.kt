package com.jemcik.jemrec.calls

import android.provider.Settings
import com.jemcik.jemrec.calls.CallButton.Event
import com.jemcik.jemrec.calls.CallButton.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSettings

/** Ask first's button over calls: what it shows, where it stands, and when
 *  it may show at all (CallButton). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallButtonTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test fun aRecordIsStartingThenRecordingThenGone() {
        var face = CallButton.next(null, Event.ASKED)
        assertEquals(Face.ASK, face)
        face = CallButton.next(face, Event.RECORD)
        assertEquals("not recording until the recorder says so", Face.STARTING, face)
        face = CallButton.next(face, Event.TAKEN)
        assertEquals(Face.RECORDING, face)
        assertNull(CallButton.next(face, Event.SEEN))
    }

    @Test fun aRecordThatDidNotTakeIsNotRecording() {
        assertNull(CallButton.next(Face.STARTING, Event.FAILED))
    }

    @Test fun overTakesItDownWhateverItShows() {
        (Face.entries + null).forEach { assertNull(CallButton.next(it, Event.OVER)) }
    }

    @Test fun anAnswerIsForTheFaceThatWaitsForIt() {
        // A second call asked about while the first's Record is on its way:
        // its answer is not the second's.
        val asking = CallButton.next(Face.STARTING, Event.ASKED)
        assertEquals(Face.ASK, asking)
        listOf(Event.TAKEN, Event.FAILED, Event.SEEN).forEach { assertEquals("$it", Face.ASK, CallButton.next(asking, it)) }
        // A Record from elsewhere with none up, and its answer: none put up.
        listOf(Event.RECORD, Event.TAKEN, Event.FAILED, Event.SEEN).forEach { assertNull("$it", CallButton.next(null, it)) }
        // Shown long enough, for what is still being started.
        assertEquals(Face.STARTING, CallButton.next(Face.STARTING, Event.SEEN))
        assertEquals(Face.RECORDING, CallButton.next(Face.RECORDING, Event.RECORD))
        assertEquals(Face.RECORDING, CallButton.next(Face.RECORDING, Event.TAKEN))
        assertEquals(Face.RECORDING, CallButton.next(Face.RECORDING, Event.FAILED))
    }

    @Test fun itsPlaceIsAShareOfTheRoomItHas() {
        assertEquals(0, CallButton.top(0f, room = 2000, height = 150))
        assertEquals(1850, CallButton.top(1f, room = 2000, height = 150))
        assertEquals(925, CallButton.top(0.5f, room = 2000, height = 150))
        // Turned: the same share of a lower screen, and inside it.
        assertEquals(375, CallButton.top(0.5f, room = 900, height = 150))
        // No room to move in, or less than it is tall: at the top, not above it.
        assertEquals(0, CallButton.top(1f, room = 150, height = 150))
        assertEquals(0, CallButton.top(1f, room = 100, height = 150))
    }

    @Test fun whereItWasDraggedToIsKeptInsideTheScreen() {
        assertEquals(0.5f, CallButton.placeOf(925, room = 2000, height = 150), 0.0001f)
        assertEquals(0f, CallButton.placeOf(-40, room = 2000, height = 150), 0f)
        assertEquals(1f, CallButton.placeOf(3000, room = 2000, height = 150), 0f)
        assertEquals(0f, CallButton.placeOf(40, room = 150, height = 150), 0f)
        assertEquals("a third of the way down until dragged", 1f / 3, CallButton.place(context), 0.0001f)
        CallButton.setPlace(context, 0.7f)
        assertEquals(0.7f, CallButton.place(context), 0f)
        CallButton.setPlace(context, 9f)
        assertEquals(1f, CallButton.place(context), 0f)
    }

    @Test fun itWorksOnlySwitchedOnAndAllowed() {
        assertFalse("off until switched on", CallButton.enabled(context))
        ShadowSettings.setCanDrawOverlays(true)
        assertFalse("allowed alone shows nothing", CallButton.works(context))
        CallButton.setEnabled(context, true)
        assertTrue(CallButton.works(context))
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse("taken back on Android's page", CallButton.works(context))
    }

    @Test fun startFreshForgetsItAndWhereItWas() {
        CallButton.setEnabled(context, true)
        CallButton.setPlace(context, 0.9f)
        CallButton.forget(context)
        assertFalse(CallButton.enabled(context))
        assertEquals(1f / 3, CallButton.place(context), 0.0001f)
    }

    @Test fun androidsPageIsDisplayOverOtherApps() {
        assertTrue(CallButton.openSettings(context))
        val page = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, page.action)
        assertEquals("package:" + context.packageName, page.dataString)
    }
}
