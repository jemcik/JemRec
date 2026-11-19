package com.jemcik.jemrec.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.jemcik.jemrec.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The process ended in the background, and the window made again from its
 * saved state, with a view model made new: where the user was comes back -
 * the view model takes it from its SavedStateHandle itself, as Android's
 * factory makes it, with nothing the window must remember to hand over.
 * Until the review of 2026-11-18 the window handed it over, and nothing
 * tested that it did; the emulator check process_death makes it happen on
 * the real thing.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class ProcessDeathTest {

    private val app = RuntimeEnvironment.getApplication()

    /** The window arms the keep-alive as it opens: WorkManager held, as in
     *  the other tests that open it (HeldWork). */
    private lateinit var work: com.jemcik.jemrec.capture.HeldWork

    @Before fun holdWork() {
        work = com.jemcik.jemrec.capture.HeldWork(app)
    }

    @After fun letWorkGo() = work.close()

    /** Android's factory makes the view model through its (app, saved)
     *  constructor: nothing read yet, either list, and what the preferences
     *  say there at once - not UiState()'s defaults, which drew "Cannot
     *  record" until the first reading. Every other test makes its own
     *  state by hand, so passing UiState() here, or leaving the preferences
     *  out, failed none (review, 2026-11-18). */
    @Test fun theViewModelAndroidMakesStartsUnreadWithThePreferences() {
        com.jemcik.jemrec.capture.RecordingMode.set(app, com.jemcik.jemrec.capture.RecordingMode.ON_DEMAND)
        val made = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).create()
        val first = ViewModelProvider(made.get())[MainViewModel::class.java].state.value
        assertTrue("the list unread", first.listUnread)
        assertTrue("the calls unread", first.callsUnread)
        assertFalse("Ask first, as the preferences say", first.automatic)
        made.destroy()
    }

    @Test fun aViewModelMadeNewAfterTheProcessEndedIsWhereTheUserWas() {
        val plain = Intent(app, MainActivity::class.java)
        val first = Robolectric.buildActivity(MainActivity::class.java, plain).setup()
        val before = ViewModelProvider(first.get())[MainViewModel::class.java]
        before.showTab(HomeTab.CALLS)
        before.showScreen(Screen.SETTINGS)
        val saved = Bundle()
        first.pause().stop().saveInstanceState(saved).destroy()

        val again = Robolectric.buildActivity(MainActivity::class.java, plain).setup(saved)
        val after = ViewModelProvider(again.get())[MainViewModel::class.java]
        assertNotSame("a view model made new, as after the process ended", before, after)
        assertEquals(Screen.SETTINGS, after.state.value.screen)
        assertEquals(HomeTab.CALLS, after.state.value.homeTab)
        again.pause().stop().destroy()
    }
}
