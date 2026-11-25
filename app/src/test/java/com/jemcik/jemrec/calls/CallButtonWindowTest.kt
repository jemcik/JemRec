package com.jemcik.jemrec.calls

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.jemcik.jemrec.calls.CallButton.Event
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/** The window Ask first's button is (CallButtonWindow): what Android is asked
 *  to show, when it comes and goes, and whose answer a tap is. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class CallButtonWindowTest {

    private val context = RuntimeEnvironment.getApplication()
    private val answers = mutableListOf<Pair<String, Boolean>>()
    private val window = CallButtonWindow(context) { ticket, record -> answers += ticket to record }

    @Before fun allowed() {
        CallButton.setEnabled(context, true)
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After fun gone() {
        window.close()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun overlays(): List<View> =
        (Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)).let { global ->
            @Suppress("UNCHECKED_CAST")
            val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>
            views.filter { (it.layoutParams as? WindowManager.LayoutParams)?.title == CallButtonWindow.TITLE }
        }

    private val params get() = overlays().single().layoutParams as WindowManager.LayoutParams

    @Test fun itIsASmallWindowOverTheCallsScreenLockedOrNot() {
        assertTrue(window.show("t1"))
        idle()
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
        // The call's screen keeps its keys and Back; the touches outside too.
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        @Suppress("DEPRECATION")
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED != 0)
        // It asks for no screen: one gone dark at the ear stays dark.
        @Suppress("DEPRECATION")
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        assertTrue("taps under another window's are not taken", overlays().single().filterTouchesWhenObscured)
        assertEquals("Record this call?", overlays().single().accessibilityPaneTitle)
    }

    @Test fun switchedOffOrNotAllowedThereIsNone() {
        CallButton.setEnabled(context, false)
        assertFalse(window.show("t1"))
        CallButton.setEnabled(context, true)
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(window.show("t1"))
        idle()
        assertTrue(overlays().isEmpty())
    }

    @Test fun itGoesWhenThereIsNothingLeftToAsk() {
        window.show("t1")
        window.on(Event.OVER)
        idle()
        assertTrue(overlays().isEmpty())
        // And comes back for the next call's question.
        assertTrue(window.show("t2"))
        idle()
        assertEquals(1, overlays().size)
    }

    @Test fun oneWindowHoweverManyQuestions() {
        window.show("t1")
        window.show("t2")
        idle()
        assertEquals(1, overlays().size)
    }

    @Test fun recordingIsShownAMomentAndGoes() {
        window.show("t1")
        window.on(Event.RECORD)
        window.on(Event.TAKEN)
        idle()
        assertEquals(1, overlays().size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallButton.RECORDING_MS + 100))
        assertTrue(overlays().isEmpty())
    }

    @Test fun recordingsMomentDoesNotTakeTheNextQuestionDown() {
        window.show("t1")
        window.on(Event.RECORD)
        window.on(Event.TAKEN)
        idle()
        // A second call, asked about and recorded a second on: the first's
        // three seconds end while the second's "Recording" is up.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        window.show("t2")
        window.on(Event.RECORD)
        window.on(Event.TAKEN)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CallButton.RECORDING_MS - 500))
        assertEquals("the first's moment is not the second's", 1, overlays().size)
        // Its own ends it.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(overlays().isEmpty())
    }

    @Test fun anEventForTheQuestionBeforeIsNotTheNextOnes() {
        window.show("t1")
        // The call before's end, on its way as the next one is asked about.
        window.on(Event.OVER)
        window.show("t2")
        idle()
        assertEquals(1, overlays().size)
    }

    @Test fun closedItIsGoneAtOnceWhateverIsOnItsWay() {
        window.show("t1")
        window.on(Event.RECORD)
        window.close()
        assertTrue(overlays().isEmpty())
        idle()
        assertTrue(overlays().isEmpty())
    }

    @Test fun switchedOffInSettingsMidCallItGoesAtOnce() {
        window.show("t1")
        idle()
        CallButton.setPlace(context, 0.5f)
        idle()
        assertEquals("another setting takes nothing down", 1, overlays().size)
        CallButton.setEnabled(context, false)
        idle()
        assertTrue(overlays().isEmpty())
    }

    @Test fun oneThatMayNotShowForTheNextQuestionIsNotLeftWithTheLastOnesTicket() {
        window.show("t1")
        idle()
        // Its permission taken back while it is up: Android hides it, and its
        // ticket is replaced by the next question's.
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(window.show("t2"))
        idle()
        assertTrue(overlays().isEmpty())
    }

    /** A finger down and up on the button, as Android sends it: within the
     *  view, its place on the screen the view's. */
    private fun tap(view: View, x: Float) {
        val at = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(at, at + 40, action, x, view.height / 2f, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
            idle()
        }
    }

    @Test fun aTapAnswersTheQuestionItWasShownFor() {
        window.show("t1")
        idle()
        window.show("t2")
        idle()
        val view = overlays().single()
        assertTrue("laid out: ${view.width}x${view.height}", view.width > 0 && view.height > 0)
        tap(view, x = view.height.toFloat())
        assertEquals(listOf("t2" to true), answers)
        // And the last of it, as wide as it is tall, is Not now.
        tap(view, x = view.width - view.height / 2f)
        assertEquals(listOf("t2" to true, "t2" to false), answers)
    }

    @Test fun onceRecordIsSaidThereIsNothingToTap() {
        window.show("t1")
        window.on(Event.RECORD)
        idle()
        val view = overlays().single()
        tap(view, x = view.height.toFloat())
        assertEquals(emptyList<Pair<String, Boolean>>(), answers)
    }

    @Test fun aDragMovesItAndItsPlaceIsKept() {
        window.show("t1")
        idle()
        val view = overlays().single()
        val from = params.y
        // From the middle of Record: a tap there would be an answer.
        drag(view, by = 400f)
        idle()
        assertTrue("moved down ($from to ${params.y})", params.y in from + 390..from + 410)
        val kept = CallButton.place(context)
        assertNotEquals(1f / 3, kept, 0.01f)
        assertEquals("a drag is no answer", emptyList<Pair<String, Boolean>>(), answers)
        // Where it was left, at the next call.
        val left = params.y
        window.on(Event.OVER)
        idle()
        window.show("t2")
        idle()
        assertEquals(left.toFloat(), params.y.toFloat(), 2f)
    }

    /** A drag ends the press it began as: without a cancel the button under
     *  the finger still has it down - as ViewGroup keeps it, no child's touch
     *  under way - and stays drawn pressed. */
    @Test fun aDragEndsThePressItBeganAs() {
        window.show("t1")
        idle()
        val view = overlays().single()
        drag(view, by = 200f)
        val touched = android.view.ViewGroup::class.java.getDeclaredField("mFirstTouchTarget").apply { isAccessible = true }
        org.junit.Assert.assertNull(touched.get(view))
        // And a tap after it is one answer.
        tap(view, x = view.height.toFloat())
        assertEquals(listOf("t1" to true), answers)
    }

    @Test fun aDragStopsAtTheScreensEdges() {
        window.show("t1")
        idle()
        drag(overlays().single(), by = -9_000f)
        idle()
        assertEquals(0, params.y)
        drag(overlays().single(), by = 9_000f)
        idle()
        assertEquals(1f, CallButton.place(context), 0.01f)
    }

    @Test fun turnedItKeepsItsPlaceInTheRoomThereIs() {
        CallButton.setPlace(context, 1f)
        window.show("t1")
        idle()
        val upright = params.y
        RuntimeEnvironment.setQualifiers("+land")
        overlays().single().dispatchConfigurationChanged(context.resources.configuration)
        idle()
        assertTrue("at the foot of a lower screen: $upright, then ${params.y}", params.y in 1 until upright)
    }

    /** [by] pixels down, a finger on the middle of Record to start with. */
    private fun drag(view: View, by: Float) {
        val at = SystemClock.uptimeMillis()
        val x = view.height.toFloat()
        val y = view.height / 2f
        fun touch(action: Int, dy: Float) {
            val event = MotionEvent.obtain(at, at, action, x, y + dy, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
            idle()
        }
        touch(MotionEvent.ACTION_DOWN, 0f)
        touch(MotionEvent.ACTION_MOVE, by / 2)
        touch(MotionEvent.ACTION_MOVE, by)
        touch(MotionEvent.ACTION_UP, by)
    }
}
