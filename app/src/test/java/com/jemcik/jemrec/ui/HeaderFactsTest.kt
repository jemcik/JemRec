package com.jemcik.jemrec.ui

import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.setup.SetupStep
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
        assertEquals(listOf("AUTO", "ANS", "MSG", "DEL 90D"), litMarks(all, RecorderStatus.READY).map { it.label })
        // The mode is one setting, so one mark: ASK instead of AUTO, never both.
        assertEquals(listOf("ASK"), litMarks(Marks(false, false, false, 0), RecorderStatus.READY).map { it.label })
    }

    @Test fun withRecordingOffTheModeMarkGoesOut() {
        val all = Marks(automatic = true, answering = true, messengers = true, autoDeleteDays = 365)
        assertEquals(listOf("ANS", "MSG", "DEL 1Y"), litMarks(all, RecorderStatus.OFF).map { it.label })
    }

    /** Settings draws each setting's mark from the same function the
     *  headers do: every setting has one, lit or not, and the lit ones are
     *  exactly Deck's LCD. */
    @Test fun settingsMarksAreTheHeadersMarks() {
        val some = Marks(automatic = false, answering = true, messengers = false, autoDeleteDays = 0)
        val each = headerMarks(some, RecorderStatus.READY)
        assertEquals(Marked.entries.toList(), each.map { it.setting })
        assertEquals(listOf("ASK", "ANS", "MSG", "DEL"), each.map { it.label })
        assertEquals(listOf(true, true, false, false), each.map { it.lit })
        assertEquals(litMarks(some, RecorderStatus.READY), each.filter { it.lit })
        // A setting that is off still has its mark in Settings, dark.
        assertEquals(HeaderMark(Marked.MESSENGERS, "MSG", false), headerMark(Marked.MESSENGERS, some, RecorderStatus.READY))
        assertFalse(headerMark(Marked.MODE, some, RecorderStatus.OFF).lit)
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

    /** One setting on: its own verb. "Messenger calls is on" was what
     *  TalkBack said on the Honor (2026-11-20). */
    @Test fun oneSettingOnTakesItsOwnVerb() {
        assertEquals(
            "Ready to record. Records every call. Messenger calls are on.",
            headerSpeech(ready, Marks(true, answering = false, messengers = true, autoDeleteDays = 0)),
        )
        assertEquals(
            "Ready to record. Records every call. Auto-delete after 30 days is on.",
            headerSpeech(ready, Marks(true, answering = false, messengers = false, autoDeleteDays = 30)),
        )
    }

    @Test fun theRecorderDownSaysWhatToDo() {
        val none = Marks(true, false, false, 0)
        val down = ready.copy(daemonRunning = false)
        assertTrue(headerSpeech(down.copy(onWifi = false), none).startsWith("Cannot record. Join any Wi-Fi"))
        assertTrue(
            headerSpeech(down.copy(networkNotAllowed = true), none)
                .startsWith("Cannot record. Android has not allowed this Wi-Fi."),
        )
        assertTrue(headerSpeech(down.copy(pairingRefused = true), none).startsWith("Cannot record. Wireless debugging turns JemRec away"))
        assertTrue(headerSpeech(down, none).startsWith("Cannot record. The recorder did not start"))
    }

    /** Nothing read yet is said as that: not "Cannot record" from the
     *  defaults, not "Starting" with nothing starting, and no mark lit or
     *  spoken, as no setting has been read. */
    @Test fun nothingReadYetIsCheckingNotCannotNorStarting() {
        val unread = ready.copy(daemonRunning = false, listUnread = true, autoDeleteAfterDays = 90)
        assertEquals(RecorderStatus.CHECKING, recorderStatus(unread))
        assertEquals(null, instruction(unread))
        assertEquals(RecorderStatus.OFF, recorderStatus(unread.copy(enabled = false)))
        assertEquals(RecorderStatus.STARTING, recorderStatus(unread.copy(starting = true)))
        val marks = marks(unread, answering = true)
        assertEquals(emptyList<HeaderMark>(), litMarks(marks, RecorderStatus.CHECKING))
        assertEquals("Checking.", headerSpeech(unread, marks))
    }

    /** Nothing read yet, in any state: the answering machine's and the
     *  messengers' marks are not spoken from defaults, and Off does not say
     *  turning on needs Wi-Fi before the port is read (reviews, 2026-11-18). */
    @Test fun nothingReadYetClaimsNothingInAnyState() {
        val unread = ready.copy(listUnread = true, enabled = false, debuggingOff = true, portSet = false)
        val marks = marks(unread, answering = true)
        assertFalse(marks.answering)
        assertFalse(marks.messengers)
        assertFalse(headerSpeech(unread, marks).contains("answering machine"))
        assertEquals(Instruction.SWITCH_ON, instruction(unread))
        assertEquals(Instruction.SWITCH_ON_OVER_WIFI, instruction(unread.copy(listUnread = false)))
    }

    @Test fun askFirstUnableToAskCannotRecord() {
        // Its question is a notification; off, no call is recorded, the
        // recorder up or not - never "ready" over the notice that says so.
        val off = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST
        val all = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL
        val asking = ready.copy(automatic = false)
        for (what in listOf(off, all)) {
            val state = asking.copy(notificationsOff = what)
            assertEquals(RecorderStatus.CANNOT, recorderStatus(state))
            assertEquals(Instruction.TURN_ON_NOTIFICATIONS, instruction(state))
            assertTrue(headerSpeech(state, Marks(false, false, false, 0)).startsWith("Cannot record. Ask first asks in a notification"))
        }
        // Recorded without asking, notifications off are not "cannot record";
        // nor are problem alerts off, in Ask first.
        assertEquals(RecorderStatus.READY, recorderStatus(ready.copy(notificationsOff = all)))
        assertEquals(
            RecorderStatus.READY,
            recorderStatus(asking.copy(notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.PROBLEMS)),
        )
        // The recorder down as well: that comes first, as it says what brings it back.
        val downToo = asking.copy(notificationsOff = off, daemonRunning = false, onWifi = false)
        assertEquals(Instruction.JOIN_WIFI, instruction(downToo))
        // And Multitrack's word with it: the link is lost, not the question.
        assertEquals("NO LINK", multitrackWord(recorderStatus(downToo), instruction(downToo)))
        assertEquals("CAN'T ASK", multitrackWord(recorderStatus(asking.copy(notificationsOff = off)), instruction(asking.copy(notificationsOff = off))))
    }

    /** Ask first with the Battery setting Restricted and exact call tracking
     *  off: Android refuses the wake that would put the question up, so no
     *  call is recorded - said as that, with the way to App info; with
     *  tracking on, Telecom's bind lets a phone call's question through
     *  (BatteryHealth; reviews, 2026-11-18). */
    @Test fun askFirstHeldBackByTheBatteryCannotRecord() {
        val asking = ready.copy(automatic = false, batteryRestricted = true, callTracking = false)
        assertEquals(RecorderStatus.CANNOT, recorderStatus(asking))
        assertEquals(Instruction.ALLOW_BACKGROUND, instruction(asking))
        assertEquals("CAN'T ASK", multitrackWord(RecorderStatus.CANNOT, instruction(asking)))
        assertTrue(headerSpeech(asking, marks(asking, answering = false)).startsWith("Cannot record. With the battery use Restricted"))
        // Tracking on, or Automatic, or not restricted: ready.
        assertEquals(RecorderStatus.READY, recorderStatus(asking.copy(callTracking = true)))
        assertEquals(RecorderStatus.READY, recorderStatus(asking.copy(automatic = true)))
        assertEquals(RecorderStatus.READY, recorderStatus(asking.copy(batteryRestricted = false)))
        // Its notifications off too: those first, as a tap there helps alone.
        val both = asking.copy(notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL)
        assertEquals(Instruction.TURN_ON_NOTIFICATIONS, instruction(both))
    }

    /** Ask first with its button over calls working asks with its
     *  notifications off too: ready, and the battery's card its own. */
    @Test fun askFirstsButtonOverCallsAsksWithNotificationsOff() {
        val off = ready.copy(automatic = false, notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL)
        assertEquals(RecorderStatus.CANNOT, recorderStatus(off))
        val button = off.copy(callButton = true, overlayAllowed = true)
        assertEquals(RecorderStatus.READY, recorderStatus(button))
        // Switched on and not let show, or allowed and not switched on: no.
        assertEquals(RecorderStatus.CANNOT, recorderStatus(button.copy(overlayAllowed = false)))
        assertEquals(RecorderStatus.CANNOT, recorderStatus(button.copy(callButton = false)))
        // The battery's reason stands, and is what is said.
        val held = button.copy(batteryRestricted = true, callTracking = false)
        assertEquals(RecorderStatus.CANNOT, recorderStatus(held))
        assertEquals(Instruction.ALLOW_BACKGROUND, instruction(held))
    }

    @Test fun onWifiTheRecorderDownIsNeverToldToJoinOne() {
        // The Honor, 2026-10-24: on Wi-Fi, its pairing deleted, the header
        // said "Join any Wi-Fi to resume".
        val down = ready.copy(daemonRunning = false)
        assertEquals(Instruction.PAIR_AGAIN, instruction(down.copy(pairingRefused = true)))
        assertEquals(Instruction.START_AGAIN, instruction(down))
        assertEquals(Instruction.ALLOW_WIFI, instruction(down.copy(networkNotAllowed = true)))
        // Off Wi-Fi, Wi-Fi is what brings it back, whatever else is wrong.
        assertEquals(Instruction.JOIN_WIFI, instruction(down.copy(onWifi = false, pairingRefused = true)))
        assertEquals(Instruction.JOIN_WIFI, instruction(down.copy(onWifi = false, networkNotAllowed = true)))
    }

    @Test fun offWithDebuggingOffNeedsWifiOnlyWithoutThePort() {
        val none = Marks(true, false, false, 0)
        val off = ready.copy(enabled = false, debuggingOff = true)
        assertEquals(Instruction.SWITCH_ON_OVER_WIFI, instruction(off))
        assertTrue(headerSpeech(off, none).startsWith("Off, with debugging off. Calls are not recorded; turning on needs Wi-Fi."))
        // adbd on Start without Wi-Fi's port comes back anywhere (the Honor,
        // 2026-10-27): no Wi-Fi to send anyone looking for.
        val port = off.copy(portSet = true)
        assertEquals(Instruction.SWITCH_ON, instruction(port))
        assertTrue(headerSpeech(port, none).startsWith("Off, with debugging off. Calls are not recorded."))
        assertFalse("Wi-Fi" in headerSpeech(port, none))
        // A plain off is a plain off, port or not.
        assertEquals(Instruction.SWITCH_ON, instruction(ready.copy(enabled = false, portSet = true)))
    }

    @Test fun everyInstructionThatIsATapSaysWhatItDoes() {
        assertEquals(
            setOf(
                Instruction.ALLOW_WIFI, Instruction.PAIR_AGAIN, Instruction.START_AGAIN,
                Instruction.TURN_ON_NOTIFICATIONS, Instruction.ALLOW_BACKGROUND,
            ),
            Instruction.entries.filter { it.tap != null }.toSet(),
        )
    }

    @Test fun homeGivesWayToPairingOnlyWhenThePairingIsRefusedOnWifi() {
        val lost = ready.copy(step = SetupStep.READY, screen = Screen.HOME, daemonRunning = false, pairingRefused = true)
        assertTrue(pairingLostOnHome(lost))
        assertFalse(pairingLostOnHome(lost.copy(pairingRefused = false)))
        assertFalse(pairingLostOnHome(lost.copy(onWifi = false)))
        assertFalse(pairingLostOnHome(lost.copy(daemonRunning = true)))
        assertFalse(pairingLostOnHome(lost.copy(starting = true)))
        assertFalse(pairingLostOnHome(lost.copy(enabled = false)))
        assertFalse(pairingLostOnHome(lost.copy(screen = Screen.SETTINGS)))
        assertFalse(pairingLostOnHome(lost.copy(step = SetupStep.NEEDS_PAIRING)))
    }
}
