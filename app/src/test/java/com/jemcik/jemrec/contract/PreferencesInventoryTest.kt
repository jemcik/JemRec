package com.jemcik.jemrec.contract

import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.AfterCall
import com.jemcik.jemrec.capture.AnswerDiagnostics
import com.jemcik.jemrec.capture.AnsweringMachine
import com.jemcik.jemrec.capture.CallRules
import com.jemcik.jemrec.capture.CallTracking
import com.jemcik.jemrec.capture.CoverageHistory
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.DebugSwitch
import com.jemcik.jemrec.capture.DebugSwitchesOff
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.OffMode
import com.jemcik.jemrec.capture.RecorderHealth
import com.jemcik.jemrec.capture.RecorderSwitch
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.capture.Retention
import com.jemcik.jemrec.capture.StartWithoutWifi
import com.jemcik.jemrec.capture.VoipCallNotes
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import com.jemcik.jemrec.ui.theme.Looks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * What the app keeps in its preferences, under the same names, read the same
 * way on a fresh install.
 *
 * Every setting lives with the object that owns it, and the 1.0 restructuring
 * moves those objects. A key renamed in a move compiles and loses every
 * user's setting on update; an enum constant renamed loses the setting stored
 * by its name; a default changed changes every fresh install. Nothing else
 * would notice: each owner's own tests write and read through the new name.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class PreferencesInventoryTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun everyKeyKeepsItsName() {
        val declared = Regex("""const val \w*KEY\w* = "([^"]+)"""").findAll(Repo.app).map { it.groupValues[1] }.toList()
        assertEquals(KEYS.toSortedSet(), declared.toSortedSet())
        // And none declared twice: two owners of one key write over each other.
        assertEquals(emptySet<String>(), declared.groupingBy { it }.eachCount().filterValues { it > 1 }.keys)
    }

    @Test
    fun theFilesAndTheOneLiteralKeyKeepTheirNames() {
        assertEquals("jemrec", Prefs.FILE)
        assertTrue("private const val PREFS = \"jemrec_colours\"" in Repo.app)
        assertTrue("private const val READ_FILE = \"jemrec_tags_read\"" in Repo.app)
        // The one key written as a literal, twice, with no owner of its own.
        assertEquals(2, Regex("\"select_hint_seen\"").findAll(Repo.app).count())
    }

    @Test
    fun everyEnumStoredByNameKeepsItsConstants() {
        assertEquals(listOf("MEMO", "DECK", "MULTITRACK"), Look.entries.map { it.name })
        assertEquals(listOf("PHONE", "LIGHT", "DARK"), LightDark.entries.map { it.name })
        assertEquals(listOf("AUTOMATIC", "ON_DEMAND"), RecordingMode.entries.map { it.name })
        assertEquals(listOf("DATE", "DATE_AND_CALLER", "DATE_AND_NUMBER", "HONOR"), FileNames.Format.entries.map { it.name })
        assertEquals(listOf("ALL", "ALL_BUT", "ONLY", "NONE"), CallRules.Contacts.entries.map { it.name })
        assertEquals(listOf("KEEP_DEBUGGING", "HIDE_DEVELOPER_OPTIONS", "ALL_DEBUGGING_OFF"), OffMode.entries.map { it.name })
        assertEquals(listOf("DEVELOPER_OPTIONS", "WIRELESS_DEBUGGING", "USB_DEBUGGING"), DebugSwitch.entries.map { it.name })
        assertEquals(listOf("OFF", "ASK_FIRST", "AUTOMATIC"), CoverageHistory.State.entries.map { it.name })
        assertEquals(
            listOf("NEEDS_WIFI", "NETWORK_NOT_ALLOWED", "CANNOT_CONNECT", "CANNOT_START"),
            RecorderHealth.Problem.entries.map { it.name },
        )
        assertEquals(listOf("WORKS", "LIKELY", "FAILS", "INCONCLUSIVE"), AnswerDiagnostics.Verdict.entries.map { it.name })
    }

    @Test
    fun aFreshInstallReadsTheDefaults() {
        assertTrue("recording on", RecorderSwitch.isOn(context))
        assertEquals(RecordingMode.AUTOMATIC, RecordingMode.of(context))
        assertTrue("exact call tracking on", CallTracking.enabled(context))
        assertEquals(CallRules.Contacts.ALL, CallRules.contacts(context))
        assertTrue("other numbers kept", CallRules.otherNumbers(context))
        assertEquals(false, AnsweringMachine.enabled(context))
        assertEquals(20, AnsweringMachine.delay(context))
        assertEquals(true, AnsweringMachine.dndShort(context))
        assertEquals(60, AnsweringMachine.longest(context))
        assertEquals("default", AnsweringMachine.sim(context))
        assertEquals("music", AnsweringMachine.route(context))
        assertEquals(FileNames.Format.DATE, FileNames.format(context))
        assertEquals(OffMode.KEEP_DEBUGGING, DebugSwitchesOff.mode(context))
        assertEquals(emptySet<DebugSwitch>(), DebugSwitchesOff.switchedOff(context))
        assertTrue("after-call notice on", AfterCall.enabled(context))
        assertEquals(false, VoipCallNotes.enabled(context))
        assertEquals(false, StartWithoutWifi.enabled(context))
        assertEquals(0, Retention.maxAgeDays(context))
        assertEquals(0, Retention.minSeconds(context))
        // Looks reads the preferences once a process and keeps what it read:
        // forgotten first, so this is a fresh install's reading and not what
        // an earlier test in this JVM left, and again after, for the next.
        fun forgetTheLook() = Looks::class.java.getDeclaredField("flow").apply { isAccessible = true }.set(Looks, null)
        forgetTheLook()
        try {
            assertEquals(LookChoice(Look.MEMO, LightDark.PHONE), Looks.choice(context))
        } finally {
            forgetTheLook()
        }
        assertEquals(false, DebugNotificationCleaner.asked(context))
    }

    private companion object {
        /** Every `const val ...KEY... = "..."` in app/src/main on 2026-11-01
         *  (0.26): preference keys, plus the few key-named constants that are
         *  file and extra names, which a rename breaks just the same. */
        val KEYS = listOf(
            "adb_private_key.der", "adb_refused_in_a_row", "after_call_notice", "answer_default_sim",
            "answer_delay_s", "answer_dnd_short", "answer_longest_s", "answer_on", "answer_result_",
            "answer_route", "answer_sim", "answer_sim_count", "answer_stage_one", "auto_delete_after_days",
            "auto_delete_last_run", "auto_delete_shorter_than", "background_kill_warned_ms", "cable_stopped_adbd",
            "call_ledger", "call_tracking", "caller_banner_dismissed", "calls_covered_since", "coverage_history",
            "daemon_token", "debug_notification_snoozed", "debug_off_pending", "debug_switched_off",
            "emergency_call_at", "emergency_resumed_at", "file_names", "hide_debug_notification",
            "kept_recordings", "light_dark", "link_token", "look", "message_recordings", "missed_seen_until",
            "name_callers", "name_directions", "names", "off_mode", "pairing_code", "port_close_failed_boot",
            "port_failed_boot", "port_opened_boot", "prompt_ticket", "qs_tile_added", "recorder_down_alerted",
            "recorder_down_cause", "recorder_down_since", "recorder_enabled", "recording_mode",
            "recordings_tree_uri", "rules_chosen", "rules_contacts", "rules_other_numbers", "save_failing",
            "setup_background_asked", "setup_complete", "setup_dev_options_confirmed", "setup_paired",
            "start_without_wifi", "tracked_calls", "voip_apps", "voip_call_notes", "voip_names",
            "wireless_debugging_refused_on", "wireless_debugging_switched_on",
        )
    }
}
