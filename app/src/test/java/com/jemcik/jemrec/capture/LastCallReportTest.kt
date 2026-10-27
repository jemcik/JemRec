package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastCallReportTest {

    private val recorder = "Recorder: build ce05cfc6\n" +
        "Last call: a phone call, incoming. Recorded for 21.5 s."

    @Test
    fun thePhoneTheSettingsAndTheRecordersAccount() {
        val text = LastCallReport.text(
            appVersion = "0.24",
            phone = "samsung SM-M346B",
            android = "Android 16 (API 36), build BP2A.250605.031",
            messengerApps = listOf("com.google.android.apps.tachyon", "com.whatsapp"),
            tracking = true,
            answering = false,
            recorder = recorder,
        )
        assertEquals(
            "JemRec 0.24 - call report\n" +
                "Phone: samsung SM-M346B, Android 16 (API 36), build BP2A.250605.031\n" +
                "Settings: messenger calls com.google.android.apps.tachyon, com.whatsapp; " +
                "exact call tracking on; answering machine off\n" +
                recorder,
            text,
        )
    }

    @Test
    fun noRecorderToAskSaysSo() {
        val text = LastCallReport.text("0.24", "Google Pixel 9", "Android 17 (API 37), build X", emptyList(),
            tracking = false, answering = true, recorder = null)
        assertTrue(text, text.contains("Settings: messenger calls off; exact call tracking off; answering machine on\n"))
        assertTrue(text, text.endsWith("Recorder: could not be asked - it is not running, or it is older than " +
            "this app."))
    }
}
