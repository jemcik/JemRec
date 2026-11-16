package com.jemcik.jemrec.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.playback.Output
import com.jemcik.jemrec.playback.PlayThrough
import com.jemcik.jemrec.playback.PlayThroughSetting
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.ui.Day
import com.jemcik.jemrec.ui.RecordingRow
import com.jemcik.jemrec.ui.RowEvents
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The player's earpiece button in each look, and Settings' "Raise to
 *  listen". */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class EarpieceTest : ScreenTest() {

    private val asked = mutableListOf<String>()
    private val events = object : RowEvents {
        override fun play(recording: Recording) {
            asked += "play"
        }

        override fun seekTo(ms: Int) = Unit

        override fun stopPlaying() {
            asked += "stop"
        }

        override fun chooseOutput(output: Output) {
            asked += "output $output"
        }

        override fun toggleKept(recording: Recording) {
            asked += "keep"
        }

        override fun share(recording: Recording) = Unit
        override fun startSelecting(recording: Recording) = Unit
        override fun toggleSelected(recording: Recording) = Unit
    }

    private fun player(state: UiState, look: Look) = show(look = LookChoice(look)) {
        RecordingRow(Fixtures.recordings().first(), Day.TODAY, state, events)
    }

    /** A tap asks for the earpiece - and only that: the button sits in the
     *  row's head, whose own tap would pause the recording. */
    private fun aTapAsksForTheEarpiece(look: Look) {
        player(Fixtures.playing(), look)
        compose.onNodeWithContentDescription("Playing through the loudspeaker - tap for the earpiece").performClick()
        assertEquals(listOf("output EARPIECE"), asked)
    }

    @Test fun memosPlayerOffersTheEarpiece() = aTapAsksForTheEarpiece(Look.MEMO)

    @Test fun decksPlayerOffersTheEarpiece() = aTapAsksForTheEarpiece(Look.DECK)

    @Test fun multitracksPlayerOffersTheEarpiece() = aTapAsksForTheEarpiece(Look.MULTITRACK)

    @Test fun atTheEarpieceATapAsksForTheSpeaker() {
        player(Fixtures.playing(earpiece = true), Look.MEMO)
        compose.onNodeWithContentDescription("Playing through the earpiece - tap for the loudspeaker").performClick()
        assertEquals(listOf("output SPEAKER"), asked)
    }

    /** Headphones in, or a tablet: no earpiece to offer. */
    @Test fun notOfferedThereIsNoButton() {
        player(Fixtures.playing().copy(earpieceOffered = false), Look.MEMO)
        compose.onAllNodesWithContentDescription("Playing through the loudspeaker - tap for the earpiece").assertCountEquals(0)
    }

    /** Only the player has it: the other rows play on the speaker until
     *  one of them is played. */
    @Test fun onlyThePlayingRowHasIt() {
        show { com.jemcik.jemrec.ui.AppScaffold(Fixtures.playing(), viewModel(Fixtures.playing())) }
        compose.onAllNodesWithContentDescription("Playing through the loudspeaker - tap for the earpiece").assertCountEquals(1)
    }

    /** At the ear, the cover takes every touch: a cheek where the player's
     *  fold is does not stop the recording - and TalkBack finds nothing
     *  under it to read or reach. */
    @Test fun theCoverTakesTheTouches() {
        val playing = Fixtures.playing(earpiece = true)
        val vm = viewModel(playing)
        var shown by androidx.compose.runtime.mutableStateOf(playing)
        show { com.jemcik.jemrec.ui.AppScaffold(shown, vm) }
        val fold = compose.onNodeWithContentDescription("Collapse player").fetchSemanticsNode().boundsInRoot.center
        shown = playing.copy(earCovered = true)
        compose.waitForIdle()
        compose.onNodeWithTag(com.jemcik.jemrec.ui.EAR_COVER_TAG).assertExists()
        compose.onAllNodesWithContentDescription("Collapse player").assertCountEquals(0)
        compose.onRoot().performTouchInput { click(fold) }
        compose.waitForIdle()
        assertEquals(playing.playingName, vm.state.value.playingName)
        // The window as dark as it goes, while covered.
        assertEquals(
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF,
            compose.activity.window.attributes.screenBrightness,
        )
    }

    /** The same tap with nothing over it stops the recording - so the one
     *  above failed for the cover alone. */
    @Test fun uncoveredTheSameTapStopsIt() {
        val vm = viewModel(Fixtures.playing(earpiece = true))
        show { com.jemcik.jemrec.ui.AppScaffold(vm.state.collectAsState().value, vm) }
        compose.onAllNodesWithTag(com.jemcik.jemrec.ui.EAR_COVER_TAG).assertCountEquals(0)
        compose.onNodeWithContentDescription("Collapse player").performClick()
        compose.waitForIdle()
        assertEquals(null, vm.state.value.playingName)
        assertEquals(
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            compose.activity.window.attributes.screenBrightness,
        )
    }

    /** On a phone that can (EarWatch, read as ReadingTest shows): the three
     *  choices, the default picked, and one picked saved. */
    @Test fun settingsPicksWhereRecordingsPlay() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS))
        showApp(vm)
        val app = RuntimeEnvironment.getApplication()
        assertEquals(PlayThrough.LOUDSPEAKER_AND_EAR, PlayThroughSetting.of(app))
        compose.onNodeWithText("Play recordings").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Loudspeaker").assertExists()
        compose.onAllNodesWithText("Loudspeaker, earpiece at your ear").assertCountEquals(2)
        compose.onNodeWithText("Earpiece").performClick()
        compose.waitForIdle()
        assertEquals(PlayThrough.EARPIECE, vm.state.value.playThrough)
        assertEquals(PlayThrough.EARPIECE, PlayThroughSetting.of(app))
        compose.onNodeWithText("Earpiece").assertExists()
    }

    /** No proximity sensor: no earpiece at the ear to choose, and the
     *  default reads as the loudspeaker it is there. */
    @Test fun withoutASensorTheEarpieceAtTheEarIsNotOffered() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS, raiseAvailable = false))
        showApp(vm)
        compose.onNodeWithText("Play recordings").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Loudspeaker, earpiece at your ear").assertCountEquals(0)
        compose.onAllNodesWithText("Loudspeaker").assertCountEquals(2)
    }

    /** A tablet - no earpiece - has nothing to choose. */
    @Test fun withoutAnEarpieceThereIsNoSetting() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS, earpieceAvailable = false))
        showApp(vm)
        compose.waitForIdle()
        compose.onAllNodesWithText("Play recordings").assertCountEquals(0)
    }
}
