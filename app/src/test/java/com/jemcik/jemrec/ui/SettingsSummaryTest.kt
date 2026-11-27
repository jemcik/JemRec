package com.jemcik.jemrec.ui

import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallRules.Contacts
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.asksFirst
import com.jemcik.jemrec.recordings.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Settings says a setting is set to: the summaries are the overview now,
 *  so a wrong one is a wrong answer at a glance. */
class SettingsSummaryTest {

    private val allowed = UiState(canReadCallLog = true, canReadContacts = true)
    private fun chosen(vararg names: String) = names.map { CallRules.Chosen(it, it, null) }

    @Test fun withoutPermissionEveryCallIsKept() {
        // The rules cannot apply without knowing who called - so whatever is
        // stored, the row must not claim a choice is in force.
        val state = UiState(ruleContacts = Contacts.NONE, ruleOtherNumbers = false)
        assertEquals("Every call", rulesSummary(state).text)
        assertFalse(rulesSummary(state).nothing)
    }

    @Test fun allButNobodyIsAll() {
        // It read "Every call is kept, except calls with 0 contacts".
        val state = allowed.copy(ruleContacts = Contacts.ALL_BUT)
        assertEquals("Every call", rulesSummary(state).text)
        assertEquals("Every call is kept.", rulesSentence(state).text)
        val noStrangers = state.copy(ruleOtherNumbers = false)
        assertEquals("Your contacts, not other numbers", rulesSummary(noStrangers).text)
    }

    @Test fun allButSomeNamesHowMany() {
        val one = allowed.copy(ruleContacts = Contacts.ALL_BUT, ruleChosen = chosen("A"))
        assertEquals("Everyone but 1 contact", rulesSummary(one).text)
        val two = one.copy(ruleChosen = chosen("A", "B"))
        assertEquals("Every call is kept, except calls with 2 contacts.", rulesSentence(two).text)
    }

    @Test fun settingsThatKeepNothingAreFlagged() {
        val none = allowed.copy(ruleContacts = Contacts.NONE, ruleOtherNumbers = false)
        assertTrue(rulesSummary(none).nothing)
        assertTrue(rulesSentence(none).nothing)
        val onlyNobody = allowed.copy(ruleContacts = Contacts.ONLY, ruleOtherNumbers = false)
        assertTrue(rulesSummary(onlyNobody).nothing)
        // Strangers still kept: something is.
        assertFalse(rulesSummary(onlyNobody.copy(ruleOtherNumbers = true)).nothing)
        assertEquals("Only numbers not in your contacts", rulesSummary(onlyNobody.copy(ruleOtherNumbers = true)).text)
    }

    @Test fun messengerRowSaysWhyBeforeWhat() {
        assertEquals("Needs Android 14 or later", messengerSummary(UiState(voipSupported = false)))
        assertEquals("None of the supported apps is installed", messengerSummary(UiState(voipSupported = true)))
        val installed = UiState(voipSupported = true, voipInstalled = VoipApps.KNOWN.filter { it.tag != "signal" })
        assertEquals("Off", messengerSummary(installed))
        val on = installed.copy(voipEnabled = setOf("com.viber.voip", "com.whatsapp"))
        // In the order the apps are listed, not the order they were switched on.
        assertEquals("WhatsApp, Viber", messengerSummary(on))
        assertEquals(
            "WhatsApp, Google Meet",
            messengerSummary(installed.copy(voipEnabled = setOf("com.google.android.apps.tachyon", "com.whatsapp"))),
        )
        // Two Telegrams on, told apart as on their screen (issue #93).
        assertEquals(
            "Telegram, Telegram (telegram.org)",
            messengerSummary(installed.copy(voipEnabled = setOf("org.telegram.messenger.web", "org.telegram.messenger"))),
        )
    }

    @Test fun startWithoutWifiAlwaysSaysThePortIsOpenToTheNetwork() {
        // The cost is the summary, whichever way it is set.
        val off = UiState(startWithoutWifi = false)
        val waiting = UiState(startWithoutWifi = true)
        val open = waiting.copy(portSet = true)
        listOf(off, waiting, open).forEach { assertTrue("5555" in startWithoutWifiSummary(it)) }
        assertTrue("any network" in startWithoutWifiSummary(off))
        assertTrue("any network" in startWithoutWifiSummary(open))
        // Open until a reboot, which only Wi-Fi undoes once.
        assertTrue("After a restart, JemRec needs Wi-Fi once" in startWithoutWifiSummary(open))
        assertTrue("next time JemRec starts on Wi-Fi" in startWithoutWifiSummary(waiting))
    }

    @Test fun startWithoutWifiSaysWhenThePhoneWouldNotOpenIt() {
        val failed = UiState(startWithoutWifi = true, portFailed = true)
        assertEquals("This phone would not open port 5555. JemRec asks again after a restart", startWithoutWifiSummary(failed))
        assertTrue(startWithoutWifiFailed(failed))
        // Switched off, a failure from earlier this boot is not news.
        assertFalse("would not" in startWithoutWifiSummary(failed.copy(startWithoutWifi = false)))
        assertFalse(startWithoutWifiFailed(failed.copy(startWithoutWifi = false)))
    }

    @Test fun anOpenPortOutranksAnEarlierFailure() {
        // An adbd slow to come back was taken for a failure, then the port
        // was there after all: the switch must not deny it, in red.
        val open = UiState(startWithoutWifi = true, portFailed = true, portSet = true)
        assertTrue(startWithoutWifiSummary(open).startsWith("Debugging port 5555 is open"))
        assertFalse(startWithoutWifiFailed(open))
    }

    @Test fun switchedOffThePortItOpenedIsStillSaid() {
        // The close waits for a Wi-Fi that allows Wireless debugging - not one
        // Android refuses it on, where the revive comes over the port itself -
        // and for JemRec back on if its off took debugging; until then the
        // port is open.
        val closing = UiState(startWithoutWifi = false, portSet = true, portOurs = true)
        assertEquals(
            "Debugging port 5555 is still open. It closes the next time JemRec is on a Wi-Fi that allows Wireless debugging, or the phone restarts",
            startWithoutWifiSummary(closing),
        )
    }

    @Test fun switchedOffAPortSomeoneElseOpenedIsSaidToo() {
        // A computer's `adb tcpip 5555`, or Tasker's ADB Wifi: not JemRec's to
        // close, but open to every network, and JemRec starts over it. Until
        // 1.10 this said the plain off text - "Opens debugging port 5555" as if
        // shut - while When JemRec is off said "Back on: anywhere" (the Honor,
        // 2026-11-26, discussion #144).
        val theirs = UiState(startWithoutWifi = false, portSet = true, portOurs = false)
        assertEquals(
            "Debugging port 5555 is open to any network the phone joins. A computer or another app opened it: it stays open until a restart, and JemRec starts over it with no Wi-Fi",
            startWithoutWifiSummary(theirs),
        )
        // With all debugging off adbd is stopped: shut until On, then open.
        assertEquals(
            "Debugging port 5555 is closed while debugging is off. A computer or another app opened it: it opens with debugging until a restart, so JemRec turns back on with or without Wi-Fi",
            startWithoutWifiSummary(theirs.copy(debuggingOff = true)),
        )
        // Nothing JemRec failed at: never in red, a close failure from earlier
        // in the boot included.
        listOf(theirs, theirs.copy(debuggingOff = true), theirs.copy(portCloseFailed = true)).forEach {
            assertFalse(startWithoutWifiFailed(it))
        }
        // Gone again (`adb usb`, a restart): the off text.
        assertTrue(startWithoutWifiSummary(theirs.copy(portSet = false)).startsWith("Turn JemRec back on"))
    }

    /**
     * The port as all five places that go by it say it: Settings' line, When
     * JemRec is off's Back on and footer, the header's "turn on over Wi-Fi",
     * and the question before turning off away from Wi-Fi. Whether adbd will
     * be on 5555 ([UiState.portSet]) is one fact, and each must agree with it
     * for every state - JemRec's port or not, the switch on or off, debugging
     * off or on, a failed open or close. Settings alone said "shut" for a port
     * someone else opened from 0.25 to 1.9, beside a screen that said
     * "anywhere" for it.
     */
    @Test fun everyPlaceThatSaysThePortAgrees() {
        // Every line Settings can say, each with what it says of the port:
        // open now, shut while debugging is off but opening with it, or none
        // there. A new line fails here until it is put in one of the three.
        val open = setOf(
            "This phone would not close debugging port 5555. It stays open until the phone restarts",
            "Debugging port 5555 is still open. It closes the next time JemRec is on a Wi-Fi that allows Wireless debugging, or the phone restarts",
            "Debugging port 5555 is open to any network the phone joins. A computer or another app opened it: it stays open until a restart, and JemRec starts over it with no Wi-Fi",
            "Debugging port 5555 is open to any network the phone joins. After a restart, JemRec needs Wi-Fi once",
        )
        val withDebugging = setOf(
            "This phone would not close debugging port 5555. Shut while debugging is off, it opens with it until the phone restarts",
            "Debugging port 5555 is closed while debugging is off. It opens with debugging, then closes once JemRec is on a Wi-Fi that allows Wireless debugging",
            "Debugging port 5555 is closed while debugging is off. A computer or another app opened it: it opens with debugging until a restart, so JemRec turns back on with or without Wi-Fi",
            "Debugging port 5555 is closed while debugging is off. Turning JemRec on opens it again, with or without Wi-Fi",
        )
        val none = setOf(
            "Turn JemRec back on with no Wi-Fi. Opens debugging port 5555 to any network the phone joins",
            "This phone would not open port 5555. JemRec asks again after a restart",
            "Opens debugging port 5555 the next time JemRec starts on Wi-Fi",
        )
        val bools = listOf(false, true)
        val seen = mutableSetOf<String>()
        for (switch in bools) for (set in bools) for (ours in bools) for (off in bools) for (failed in bools) for (closeFailed in bools) {
            val state = UiState(
                startWithoutWifi = switch, portSet = set, portOurs = ours, debuggingOff = off,
                portFailed = failed, portCloseFailed = closeFailed,
            )
            val said = "switch=$switch set=$set ours=$ours debuggingOff=$off failed=$failed closeFailed=$closeFailed"
            val settings = startWithoutWifiSummary(state)
            seen += settings
            val expected = when {
                !set -> none
                off -> withDebugging
                else -> open
            }
            assertTrue("Settings: \"$settings\" | $said", settings in expected)
            assertEquals(
                "Back on | $said", set,
                offModeLines(OffMode.ALL_DEBUGGING_OFF, set).toMap().getValue("Back on:").startsWith("anywhere"),
            )
            // The footer names the open port with the switch off; switched
            // on, it says what the switch costs whatever the port is now.
            if (!switch) assertEquals("footer | $said", set, whenOffFooter(state).any { "5555 stays open until the phone restarts" in it })
            assertEquals("header | $said", off && !set, onNeedsWifi(state))
            assertEquals("Off question | $said", !set, OffMode.ALL_DEBUGGING_OFF.asksFirst(onWifi = false) { set })
        }
        // Every line reached by some state: none listed above for nothing.
        assertEquals(open + withDebugging + none, seen)
    }

    @Test fun aCloseThatDidNotTakeIsSaidInRed() {
        // Switched off, JemRec's port still set after it asked to close it:
        // open until a restart, and nothing in the off text would say so.
        val stuck = UiState(startWithoutWifi = false, portSet = true, portOurs = true, portCloseFailed = true)
        assertEquals(
            "This phone would not close debugging port 5555. It stays open until the phone restarts",
            startWithoutWifiSummary(stuck),
        )
        assertTrue(startWithoutWifiFailed(stuck))
        assertTrue(startWithoutWifiSummary(stuck.copy(debuggingOff = true)).contains("Shut while debugging is off"))
        // Closed after all, or a computer's port: nothing to say in red.
        assertFalse(startWithoutWifiFailed(stuck.copy(portSet = false)))
        assertFalse(startWithoutWifiFailed(stuck.copy(portOurs = false)))
        // Switched on again, the port it kept is simply open...
        assertTrue(startWithoutWifiSummary(stuck.copy(startWithoutWifi = true)).startsWith("Debugging port 5555 is open"))
        assertFalse(startWithoutWifiFailed(stuck.copy(startWithoutWifi = true)))
        // ...and, gone meanwhile (a computer's `adb usb`), not blamed on an
        // open that was never tried.
        val reopening = stuck.copy(startWithoutWifi = true, portSet = false)
        assertFalse("would not" in startWithoutWifiSummary(reopening))
        assertFalse(startWithoutWifiFailed(reopening))
    }

    @Test fun whenOffsFooterAgreesWithItsLinesAboutThePort() {
        val pointer = "switch on Start without Wi-Fi"
        val staysOpen = "JemRec is off"
        // No port: how to come back away from Wi-Fi.
        assertTrue(whenOffFooter(UiState()).any { pointer in it })
        // The switch on: the cost while off.
        val on = whenOffFooter(UiState(startWithoutWifi = true))
        assertTrue(on.any { staysOpen in it } && on.none { pointer in it })
        // A port open with the switch off - a computer's, or a close that did
        // not take: Back on says anywhere above, so no pointer to the switch,
        // and the open port said as for the switch.
        val open = whenOffFooter(UiState(startWithoutWifi = false, portSet = true))
        assertTrue(offModeLines(OffMode.ALL_DEBUGGING_OFF, portSet = true).toMap().getValue("Back on:").startsWith("anywhere"))
        assertTrue(open.any { staysOpen in it } && open.none { pointer in it })
        // Never "is open": with all debugging off nothing listens.
        assertTrue((on + open).none { "5555 is open" in it })
    }

    @Test fun withAllDebuggingOffThePortIsSaidClosed() {
        // adbd is stopped then, so nothing listens on 5555 - what that choice
        // is for. Switched on, and switched off with JemRec's port to close.
        val on = UiState(startWithoutWifi = true, portSet = true, debuggingOff = true)
        val closing = UiState(startWithoutWifi = false, portSet = true, portOurs = true, debuggingOff = true)
        listOf(on, closing).forEach {
            assertTrue(startWithoutWifiSummary(it).startsWith("Debugging port 5555 is closed while debugging is off"))
            assertFalse(startWithoutWifiFailed(it))
        }
        assertTrue("with or without Wi-Fi" in startWithoutWifiSummary(on))
        assertTrue("closes once JemRec is on a Wi-Fi that allows Wireless debugging" in startWithoutWifiSummary(closing))
        // Not set, the off text is the same with debugging off or on.
        assertEquals(
            startWithoutWifiSummary(UiState(startWithoutWifi = false)),
            startWithoutWifiSummary(UiState(startWithoutWifi = false, debuggingOff = true)),
        )
    }

    /** Since 1.5 Wireless debugging is off while JemRec runs, so no choice
     *  names it among what it switches off (the user's pick, 2026-11-21);
     *  the footer says why, and the case left - one switched on by hand. */
    @Test fun noChoiceNamesWirelessDebuggingAndTheFooterSaysWhy() {
        OffMode.entries.forEach { mode ->
            listOf(false, true).forEach { portSet ->
                assertTrue("$mode", offModeLines(mode, portSet).none { "Wireless" in it.second })
            }
        }
        assertEquals("Developer options", offModeLines(OffMode.HIDE_DEVELOPER_OPTIONS, false).toMap().getValue("Switches off:"))
        assertEquals("Developer options, USB debugging", offModeLines(OffMode.ALL_DEBUGGING_OFF, false).toMap().getValue("Switches off:"))
        val footer = whenOffFooter(UiState())
        assertTrue(footer.any { "Wireless debugging is off while JemRec runs" in it && "switch it on yourself" in it })
    }

    @Test fun backOnAfterAllDebuggingOffIsAnywhereWithThePort() {
        val back = { portSet: Boolean -> offModeLines(OffMode.ALL_DEBUGGING_OFF, portSet).toMap().getValue("Back on:") }
        assertTrue(back(false).startsWith("needs Wi-Fi"))
        assertTrue(back(true).startsWith("anywhere"))
        // The other two are instant anywhere already.
        assertEquals(
            offModeLines(OffMode.HIDE_DEVELOPER_OPTIONS, portSet = false),
            offModeLines(OffMode.HIDE_DEVELOPER_OPTIONS, portSet = true),
        )
    }

    @Test fun fileNamesSayWhatIsMissing() {
        assertNull(fileNamesProblem(UiState(fileNames = FileNames.Format.DATE)))
        listOf(FileNames.Format.DATE_AND_CALLER, FileNames.Format.DATE_AND_NUMBER).forEach { format ->
            assertEquals(
                "Needs your call log. Named by date and time until then.",
                fileNamesProblem(UiState(fileNames = format)),
            )
            assertNull(fileNamesProblem(UiState(fileNames = format, canReadCallLog = true)))
        }
        val honor = UiState(fileNames = FileNames.Format.HONOR, canReadCallLog = true)
        assertEquals("Not saving to ${FileNames.HONOR_FOLDER} yet", fileNamesProblem(honor))
        assertNull(fileNamesProblem(honor.copy(inHonorFolder = true)))
    }
}
