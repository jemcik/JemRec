package com.jemcik.jemrec.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.util.concurrent.SettableFuture
import com.jemcik.jemrec.answering.Greeting
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
 * The greeting's preview, playing as the screen goes for good (closed from
 * Recents): it stops with the view model, its player let go. It played on
 * to its end, up to thirty seconds, and its AudioTrack was never released.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class GreetingPreviewClosedTest {

    private val app = RuntimeEnvironment.getApplication()

    @Test fun thePreviewStopsWithTheScreen() {
        // Ten seconds of a greeting, so the preview is still playing when the
        // screen goes.
        Greeting.save(app, ByteArray(Greeting.RATE * 2 * 10))
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(app, connectSession = { SettableFuture.create() }) as T
            },
        )[MainViewModel::class.java]

        vm.answer.togglePreview()
        val main = shadowOf(Looper.getMainLooper())
        val until = System.currentTimeMillis() + 10_000
        while (!vm.answer.state.value.playing) {
            main.idle()
            if (System.currentTimeMillis() > until) fail("the preview never started")
            Thread.sleep(10)
        }

        store.clear()
        assertFalse("the preview stopped with the screen", vm.answer.state.value.playing)
    }
}
