package com.jemcik.jemrec.calls

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jemcik.jemrec.calls.CallButton.Event
import com.jemcik.jemrec.calls.CallButton.Face
import com.jemcik.jemrec.ui.CallButtonPill
import com.jemcik.jemrec.ui.theme.JemRecTheme
import com.jemcik.jemrec.ui.theme.Looks
import kotlin.math.abs

/** Ask first's button over the call, as the call's service drives it. */
internal interface AskButton {
    /** Up for a question whose answers carry [ticket]: whether it is - not
     *  with it switched off, or Android not letting it show. On the main
     *  thread, where the service takes its wakes. */
    fun show(ticket: String): Boolean

    /** From any thread. */
    fun on(event: Event)

    /** Gone at once: the service is. On the main thread. */
    fun close()
}

/**
 * The button as a window over whatever app is in front, a call's screen first
 * of all, but not the lock screen (CallButton).
 *
 * As small as what it draws: FLAG_NOT_FOCUSABLE, so the call's screen keeps
 * the keys and Back, and every touch outside it reaches what is under it.
 * FLAG_SHOW_WHEN_LOCKED lets it over a call's screen on a locked phone, which
 * is where most calls are answered: "Show SHOW_WHEN_LOCKED windows if keyguard
 * is occluded" (DisplayPolicy.shouldBeHiddenByKeyguard). The flag is
 * deprecated for an activity, which has a property of its own; a window of a
 * service's has only the flag.
 *
 * A touch that passes through another app's window over it is not taken
 * (filterTouchesWhenObscured): no tap meant for that window records a call.
 *
 * Drawn in Compose, which wants a lifecycle and a saved state from the tree
 * it hangs in; a window of a service's has neither, so it brings its own.
 */
internal class CallButtonWindow(
    private val context: Context,
    /** Record (true) or Not now, with the ticket it was shown with. */
    private val answered: (ticket: String, record: Boolean) -> Unit,
) : AskButton {

    private val main = Handler(Looper.getMainLooper())
    private val face = mutableStateOf<Face?>(null)
    private var ticket = ""
    private var window: Shown? = null

    /** The questions shown so far: what an event is for, if a later one is
     *  up by the time it is taken. */
    @Volatile
    private var asked = 0

    /** Switched off in Settings with it up: down at once. Android does not:
     *  taken back by the recorder the permission is at its default, and a
     *  window already shown is hidden only where it is refused (AOSP
     *  WindowState.updateAppOpsState, 12 and 16) - it floated on over JemRec
     *  itself (review round 1; read, not produced). Watched while it is up. */
    private var stopWatching: () -> Unit = {}

    override fun show(ticket: String): Boolean {
        if (!CallButton.works(context)) {
            // One still up for a call before - a call waiting answered, the
            // button switched off or refused since - holds a ticket that the
            // new question has replaced.
            turn(Event.OVER)
            return false
        }
        if (window == null) {
            window = runCatching { Shown(windowContext()).apply { add() } }
                .onFailure { Log.w(TAG, "button: Android would not show it", it) }
                .getOrNull()
                ?: return false
            stopWatching = CallButton.watch(context) { if (!CallButton.enabled(context)) turn(Event.OVER) }
        }
        asked++
        this.ticket = ticket
        turn(Event.ASKED)
        Log.i(TAG, "button: over the call, asking whether to record it")
        return true
    }

    override fun on(event: Event) {
        val question = asked
        main.post { if (question == asked) turn(event) }
    }

    override fun close() {
        asked++
        turn(Event.OVER)
    }

    private fun turn(event: Event) {
        val next = CallButton.next(face.value, event)
        if (next == face.value) return
        face.value = next
        if (next == null) {
            window?.remove()
            window = null
            stopWatching()
            stopWatching = {}
        }
        if (next == Face.RECORDING) {
            val question = asked
            main.postDelayed({ if (question == asked) turn(Event.SEEN) }, CallButton.RECORDING_MS)
        }
    }

    private fun windowContext(): Context {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    /** The window while it is up. */
    private inner class Shown(private val on: Context) {

        private val manager = on.getSystemService(WindowManager::class.java)
        private val owner = Owner()

        @Suppress("DEPRECATION") // FLAG_SHOW_WHEN_LOCKED: the class's KDoc.
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = TITLE
            gravity = Gravity.TOP or Gravity.END
            x = (EDGE_DP * on.resources.displayMetrics.density).toInt()
        }

        private val view: FrameLayout = object : FrameLayout(on) {
            private val slop = ViewConfiguration.get(on).scaledTouchSlop
            private var fingerAt = 0f
            private var topAt = 0
            private var dragging = false

            // A drag up or down moves it; anything less is the button's own.
            // By the finger's place on the screen: the window moves under it.
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        fingerAt = event.rawY
                        topAt = params.y
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging && abs(event.rawY - fingerAt) > slop) {
                            dragging = true
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        }
                        if (dragging) {
                            moveTo(topAt + (event.rawY - fingerAt).toInt())
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                        dragging = false
                        CallButton.setPlace(context, CallButton.placeOf(params.y, room(), height))
                        return true
                    }
                }
                super.dispatchTouchEvent(event)
                return true
            }

            // Turned, folded, its text made larger: kept where it was, in the
            // room there is now.
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                post { place() }
            }
        }

        fun add() {
            view.filterTouchesWhenObscured = true
            view.accessibilityPaneTitle = "Record this call?"
            view.setViewTreeLifecycleOwner(owner)
            view.setViewTreeSavedStateRegistryOwner(owner)
            view.addView(
                ComposeView(on).apply {
                    setContent {
                        val look by Looks.changes(context).collectAsState()
                        JemRecTheme(look) {
                            CallButtonPill(
                                face = face.value ?: Face.ASK,
                                onRecord = { answered(ticket, true) },
                                onNotNow = { answered(ticket, false) },
                            )
                        }
                    }
                },
            )
            // As tall as it comes out: not known before it is laid out, and
            // another once "Record" is "Recording".
            view.addOnLayoutChangeListener { _: View, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) view.post { place() }
            }
            params.y = CallButton.top(CallButton.place(context), room(), (TALL_DP * on.resources.displayMetrics.density).toInt())
            owner.resume()
            manager.addView(view, params)
        }

        fun remove() {
            // At once: the service may be going, and its windows with it.
            runCatching { manager.removeViewImmediate(view) }.onFailure { Log.w(TAG, "button: could not be taken down", it) }
            owner.destroy()
        }

        /** Where it was left, in the room there is. */
        fun place() = moveTo(CallButton.top(CallButton.place(context), room(), view.height))

        private fun moveTo(top: Int) {
            val y = top.coerceIn(0, (room() - view.height).coerceAtLeast(0))
            if (y == params.y || !view.isAttachedToWindow) return
            params.y = y
            manager.updateViewLayout(view, params)
        }

        /** What it can be anywhere in: the screen, less the bars and cutouts
         *  Android lays it out between. */
        private fun room(): Int {
            val metrics = manager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return metrics.bounds.height() - bars.top - bars.bottom
        }
    }

    /** The lifecycle and saved state Compose asks of the tree it is in. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun resume() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    internal companion object {
        private const val TAG = "JemRec"

        /** The window's name, as `dumpsys window` lists it: the emulator
         *  check finds it by this. */
        const val TITLE = "JemRecButton"

        /** Clear of the screen's edge, where Back's swipe begins. */
        private const val EDGE_DP = 8

        /** As tall as it is at the phone's own text size (CallButtonPill):
         *  where it goes before it has been laid out. */
        private const val TALL_DP = 56
    }
}
