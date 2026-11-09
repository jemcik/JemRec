package com.jemcik.jemrec.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.util.concurrent.SettableFuture
import com.jemcik.jemrec.answering.Greeting
import com.jemcik.jemrec.capture.FakeRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The greeting, being played or recorded as the screen goes for good (closed
 * from Recents): each stops with the view model.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class GreetingPreviewClosedTest {

    private val app = RuntimeEnvironment.getApplication()

    /** A view model kept in [store], as an activity keeps it, so that
     *  clearing the store ends it as closing the screen does. */
    private fun model(store: ViewModelStore): MainViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(app, connectSession = { SettableFuture.create() }) as T
        },
    )[MainViewModel::class.java]

    /** The main looper turned until [done]: the view model's work runs only
     *  as it turns. */
    private fun turnUntil(what: String, done: () -> Boolean) {
        val main = shadowOf(Looper.getMainLooper())
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            main.idle()
            if (System.currentTimeMillis() > until) fail("never happened: $what")
            Thread.sleep(10)
        }
    }

    /** Its preview played on to its end, up to thirty seconds, and its
     *  AudioTrack was never released. */
    @Test fun thePreviewStopsWithTheScreen() {
        // Ten seconds of a greeting, so the preview is still playing when the
        // screen goes.
        Greeting.save(app, ByteArray(Greeting.RATE * 2 * 10))
        val store = ViewModelStore()
        val vm = model(store)
        vm.answer.togglePreview()
        turnUntil("the preview to start") { vm.answer.state.value.playing }
        store.clear()
        assertFalse("the preview stopped with the screen", vm.answer.state.value.playing)
    }

    /**
     * A take, recording: the recorder is told to stop. It went on recording
     * the microphone for up to thirty seconds, and refused a new take until
     * then - Record, on coming back, said the recorder could not start.
     */
    @Test fun aTakeStopsWithTheScreen() {
        val recorder = FakeRecorder(app)
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        try {
            val store = ViewModelStore()
            val vm = model(store)
            vm.answer.startTake()
            turnUntil("the take to start") { vm.answer.state.value.recordingTake }
            store.clear()
            turnUntil("the recorder told to stop") { recorder.heard.any { it.key == "GS" } }
        } finally {
            recorder.close()
        }
    }
}
