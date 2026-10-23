package com.jemcik.jemrec.ui

import com.jemcik.jemrec.capture.VoipApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What every look's header says - one set of facts, so the three cannot
 *  disagree about whether the next call is recorded. */
class HeaderFactsTest {

    private val ready = UiState(enabled = true, daemonRunning = true, automatic = true)

    @Test fun theStateIsTheSwitchFirstThenTheRecorder() {
        assertEquals(RecorderStatus.READY, recorderStatus(ready))
        // Off wins over everything: nothing records, whatever the recorder is doing.
        assertEquals(RecorderStatus.OFF, recorderStatus(ready.copy(enabled = false)))
        // Starting outranks a recorder not yet up - the old header's rule, so a
        // normal start is never announced as a failure.
        assertEquals(RecorderStatus.STARTING, recorderStatus(ready.copy(starting = true, daemonRunning = false)))
        assertEquals(RecorderStatus.CANNOT, recorderStatus(ready.copy(daemonRunning = false)))
    }

    @Test fun oneMarkPerSettingInAFixedOrder() {
        val all = Marks(automatic = true, answering = true, messengers = true, autoDeleteDays = 90)
        assertEquals(listOf("AUTO", "ANS", "MSG", "DEL 90D"), markLabels(all, RecorderStatus.READY))
        // The mode is one setting, so one mark: ASK instead of AUTO, never both.
        assertEquals(listOf("ASK"), markLabels(Marks(false, false, false, 0), RecorderStatus.READY))
    }

    @Test fun withRecordingOffTheModeMarkGoesOut() {
        val all = Marks(automatic = true, answering = true, messengers = true, autoDeleteDays = 365)
        assertEquals(listOf("ANS", "MSG", "DEL 1Y"), markLabels(all, RecorderStatus.OFF))
    }

    @Test fun messengersCountOnlyWhenAnAppIsBothInstalledAndOn() {
        val whatsapp = VoipApps.KNOWN.first { it.pkg == "com.whatsapp" }
        val installedOff = ready.copy(voipSupported = true, voipInstalled = listOf(whatsapp), voipEnabled = emptySet())
        assertFalse(marks(installedOff, answering = false).messengers)
        assertTrue(marks(installedOff.copy(voipEnabled = setOf("com.whatsapp")), answering = false).messengers)
        // On, but a phone that cannot record them (Android 13 and older).
        assertFalse(marks(installedOff.copy(voipEnabled = setOf("com.whatsapp"), voipSupported = false), false).messengers)
        // On for an app that is not installed.
        assertFalse(marks(ready.copy(voipEnabled = setOf("com.whatsapp")), false).messengers)
    }

    @Test fun autoDeleteAgesAsSettingsOffersThem() {
        assertEquals("30D", ageMark(30))
        assertEquals("6M", ageMark(180))
        assertEquals("1Y", ageMark(365))
        assertEquals("6 months", ageWords(180))
        assertEquals("90 days", ageWords(90))
    }

    @Test fun aScreenReaderHearsTheStateThenWhatIsOn() {
        val marks = Marks(automatic = true, answering = true, messengers = true, autoDeleteDays = 90)
        assertEquals(
            "Ready to record. Records every call. Answering machine, messenger calls and " +
                "auto-delete after 90 days are on.",
            headerSpeech(ready, marks),
        )
        assertEquals(
            "Ready to record. Asks before each call. Answering machine is on.",
            headerSpeech(ready.copy(automatic = false), Marks(false, true, false, 0)),
        )
    }

    @Test fun theRecorderDownSaysWhatToDo() {
        val none = Marks(true, false, false, 0)
        assertTrue(headerSpeech(ready.copy(daemonRunning = false), none).startsWith("Cannot record. Join any Wi-Fi"))
        assertTrue(
            headerSpeech(ready.copy(daemonRunning = false, networkNotAllowed = true), none)
                .startsWith("Cannot record. Android has not allowed this Wi-Fi."),
        )
    }
}
