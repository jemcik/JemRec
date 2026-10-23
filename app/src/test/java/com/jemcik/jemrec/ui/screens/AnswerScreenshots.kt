package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.capture.AnswerDiagnostics
import com.jemcik.jemrec.ui.AnswerUi
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.Screen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** The answering machine's screen, in the states a user meets it in. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class AnswerScreenshots : ScreenTest() {

    private val passed = AnswerDiagnostics.StageOne(
        permissions = true, inCallMusic = true, screening = true, describe = "HONOR BKQ-N49 (SM8850) Android API 37",
    )

    private fun answering(answer: AnswerUi, fontScale: Float = 1f) {
        val state = Fixtures.ready().copy(screen = Screen.ANSWERING_MACHINE)
        val vm = viewModel(state, answer)
        show(fontScale) { AppScaffold(state, vm) }
    }

    private val ready = AnswerUi(
        enabled = true,
        stageOne = passed,
        greetingSeconds = 6.4,
        results = mapOf("2G" to AnswerDiagnostics.Verdict.WORKS),
        carrier = "off 0",
    )

    // Taller than a phone: the whole screen, test section and footer too.
    @Config(qualifiers = "+h1700dp")
    @Test fun readyAndOn() {
        answering(ready)
        snap("answering")
    }

    @Config(qualifiers = "+h1700dp-night")
    @Test fun dark() {
        answering(ready.copy(enabled = false))
        snap("answering-dark")
    }

    @Test fun noLimit() {
        answering(ready.copy(longest = com.jemcik.jemrec.capture.AnsweringMachine.NO_LIMIT))
        snap("answering-no-limit")
    }

    @Test fun pausedAfterAnEmergencyCall() {
        answering(ready.copy(pausedUntil = Fixtures.NOW + 20 * 60 * 60 * 1000L))
        snap("answering-paused")
    }

    @Test fun beforeTheChecks() {
        answering(AnswerUi())
        snap("answering-not-checked")
    }

    @Test fun notAvailableOnThisPhone() {
        answering(AnswerUi(stageOne = passed.copy(inCallMusic = false, screening = false)))
        snap("answering-not-available")
    }

    @Test fun recordingAGreeting() {
        answering(AnswerUi(stageOne = passed, recordingTake = true, takeStartedAt = Fixtures.NOW))
        snap("answering-recording")
    }

    @Test fun didTheyHearIt() {
        answering(ready.copy(askHeard = "VoLTE"))
        snapWithDialog("answering-heard")
    }

    @Config(qualifiers = "+h1700dp")
    @Test fun largeText() {
        answering(ready, fontScale = 2f)
        snap("answering-large-text")
    }
}
