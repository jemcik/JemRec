package com.jemcik.jemrec.ui

import android.net.Uri
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.capture.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** The Calls tab's rows: which calls are marked as nobody answering, and
 *  whose a recorded call's row is. */
class CallRowsTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)

    private fun call(kind: Kind, label: String = "Hana Sato", at: Long = 1_761_000_000_000L, recording: Recording? = null) =
        CallHistory.Call(
            at = at, seconds = 60, kind = kind, label = label, number = null, favorite = false,
            recording = recording, status = if (recording != null) Status.RECORDED else Status.NOT_CONNECTED,
        )

    @Test fun onlyCallsNobodyAnsweredAreMarkedSo() {
        for (kind in listOf(Kind.MISSED, Kind.DECLINED, Kind.BLOCKED, Kind.VOICEMAIL)) assertTrue("$kind", unanswered(call(kind)))
        // A call answered on another device was answered.
        for (kind in listOf(Kind.INCOMING, Kind.OUTGOING, Kind.OTHER)) assertFalse("$kind", unanswered(call(kind)))
    }

    @Test fun aRecordedCallsRowIsTheCallsOwn() {
        // Call waiting: B came in during A's recording, and shares it.
        val a = Recording(
            name = "20261021_160200_in.ogg", uri = uri, whenLabel = "", incoming = true,
            durationLabel = "4:12", sizeLabel = "1.2 MB", caller = "Olivia Bennett", callerFavorite = true,
        )
        val b = call(Kind.OUTGOING, label = "Marcus Chen", at = a.startedAtMillis!! + 60_000, recording = a)
        val shown = a.forCall(b)
        assertEquals("Marcus Chen", shown.caller)
        assertFalse(shown.incoming)
        assertFalse(shown.callerFavorite)
        assertEquals(b.at, shown.shownAt)
        // The same file, to play, keep and share.
        assertEquals(a.name, shown.name)
        assertEquals(a.uri, shown.uri)
    }

    @Test fun timesAreInLatinDigitsInAnyLanguage() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("1:05", clock(65_000))
            assertEquals("3:12 · Recording was off", outcomeWords(call(Kind.INCOMING).copy(seconds = 192, status = Status.RECORDING_OFF)))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
