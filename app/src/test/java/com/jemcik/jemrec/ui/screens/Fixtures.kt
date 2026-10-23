package com.jemcik.jemrec.ui.screens

import android.net.Uri
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.capture.CallRules
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.VoipApps
import com.jemcik.jemrec.capture.Waveforms
import com.jemcik.jemrec.ui.SetupStep
import com.jemcik.jemrec.ui.UiState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Invented calls for the screen tests - the people do not exist, as in the
 * debug build's demo list - at fixed times around [NOW], 21 October 2026,
 * 18:00 UTC: three today, one yesterday, the rest earlier.
 *
 * Built on demand, not at class load: a recording's time is read from its
 * name in the default time zone, which ScreenTest sets to UTC first.
 */
object Fixtures {

    val NOW: Long = utc("20261021_180000")

    private fun utc(stamp: String): Long =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(stamp)!!.time

    private fun recording(
        stamp: String,
        incoming: Boolean,
        caller: String?,
        duration: String,
        app: String? = null,
        favorite: Boolean = false,
        kept: Boolean = false,
        whenLabel: String = "",
    ) = Recording(
        name = "${stamp}_${if (incoming) "in" else "out"}${app?.let { "_$it" } ?: ""}.ogg",
        uri = Uri.parse("jemrec-test://$stamp"),
        whenLabel = whenLabel,
        incoming = incoming,
        durationLabel = duration,
        sizeLabel = "1.2 MB",
        caller = caller,
        callerFavorite = favorite,
        kept = kept,
    )

    fun recordings(): List<Recording> = listOf(
        recording("20261021_160200", true, "Olivia Bennett", "4:12", favorite = true, kept = true),
        recording("20261021_152400", false, "Marcus Chen", "12:05", app = "whatsapp"),
        recording("20261021_101100", true, "+44 7700 900461", "0:47"),
        recording("20261020_141600", false, "Priya Raman", "2:38"),
        recording("20261019_131600", true, "Daniel Okafor", "41:02", kept = true),
        recording("20261017_144600", false, "477", "1:19"),
        recording("20261012_111600", true, "Sofia Marchetti", "8:54"),
        recording("20260928_091600", false, null, "0:15", whenLabel = "28 Sept, 09:16"),
    )

    fun calls(): List<CallHistory.Call> {
        val byName = recordings().associateBy { it.name.take(15) }
        fun call(stamp: String, seconds: Int, kind: Kind, label: String, status: Status, favorite: Boolean = false) =
            CallHistory.Call(
                at = utc(stamp), seconds = seconds, kind = kind, label = label, number = null,
                favorite = favorite, recording = byName[stamp].takeIf { status == Status.RECORDED }, status = status,
            )
        return listOf(
            call("20261021_160200", 252, Kind.INCOMING, "Olivia Bennett", Status.RECORDED, favorite = true),
            call("20261021_152300", 0, Kind.MISSED, "+44 20 7946 0321", Status.NOT_CONNECTED),
            call("20261021_140500", 0, Kind.OUTGOING, "Priya Raman", Status.NOT_CONNECTED),
            call("20261021_101100", 47, Kind.INCOMING, "+44 7700 900461", Status.RECORDED),
            call("20261020_151500", 192, Kind.INCOMING, "Hana Sato", Status.RECORDING_OFF),
            call("20261020_141600", 158, Kind.OUTGOING, "Priya Raman", Status.RECORDED),
            call("20261019_160000", 312, Kind.INCOMING, "Dr. Lena Vogel", Status.SKIPPED),
            call("20261018_153500", 58, Kind.INCOMING, "+1 202 555 0147", Status.NOT_RECORDED),
        )
    }

    /** JemRec set up and recording, everything allowed, a full list. */
    fun ready(): UiState = UiState(
        step = SetupStep.READY,
        enabled = true,
        daemonRunning = true,
        automatic = true,
        canReadCallLog = true,
        canReadContacts = true,
        canReadAudio = true,
        recordings = recordings(),
        calls = calls(),
        folder = "Recordings/JemRec",
        usingDefaultFolder = true,
        fileNames = FileNames.Format.DATE_AND_CALLER,
        autoDeleteAfterDays = 90,
        autoDeleteShorterThan = 10,
        voipSupported = true,
        voipInstalled = VoipApps.KNOWN.filter { it.pkg in setOf("com.whatsapp", "org.telegram.messenger", "com.viber.voip") },
        voipEnabled = setOf("com.whatsapp", "org.telegram.messenger"),
        voipNames = true,
    )

    fun chosen(vararg names: String) = names.map { CallRules.Chosen(it, it, null) }

    /** A call's loudness as the waveform draws it: phrases of words and the
     *  pauses between them, two people at their own levels, and a quiet
     *  stretch in the middle - the same every run, so the same pixels. */
    fun waveform(from: Long = 20_261_022L): List<Float> = Waveforms.invented(from)
}
