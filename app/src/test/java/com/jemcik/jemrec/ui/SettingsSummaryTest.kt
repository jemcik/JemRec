package com.jemcik.jemrec.ui

import com.jemcik.jemrec.capture.CallRules
import com.jemcik.jemrec.capture.CallRules.Contacts
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.VoipApps
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
    }

    @Test fun fileNamesSayWhatIsMissing() {
        assertNull(fileNamesProblem(UiState(fileNames = FileNames.Format.DATE)))
        assertEquals(
            "Needs your call log. Named by date and time until then.",
            fileNamesProblem(UiState(fileNames = FileNames.Format.DATE_AND_CALLER)),
        )
        val honor = UiState(fileNames = FileNames.Format.HONOR, canReadCallLog = true)
        assertEquals("Not saving to ${FileNames.HONOR_FOLDER} yet", fileNamesProblem(honor))
        assertNull(fileNamesProblem(honor.copy(inHonorFolder = true)))
    }
}
