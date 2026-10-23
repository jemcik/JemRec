package com.jemcik.jemrec.diag

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.OpusOggWriter
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.capture.Waveforms
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Invented calls, for the README's screenshots.
 *
 * WHY THIS EXISTS. A screenshot of a call recorder is a screenshot of somebody's
 * calls: contact names, phone numbers, who they spoke to and for how long. There
 * is no crop that makes that publishable, and blurring it would show an app
 * nobody can read. So the list gets a stand-in - eight calls that never happened,
 * with the shapes a real list has: contacts with a picture, contacts without one,
 * bare numbers, a call the log could not name at all, both directions, a
 * favourite, a message the answering machine took, and times spread across
 * today, this week and before it, so the filter chips have something to filter.
 *
 * DEBUG ONLY, structurally. This whole source set is absent from a release APK,
 * so a shipped build contains no code that could fill Recordings.override - which
 * is why that seam is a plain nullable rather than something guarded by a flag at
 * runtime.
 *
 * Turn it on from a USB shell, the same way as every other diagnostic:
 *
 *   adb shell am broadcast -n com.jemcik.jemrec/.diag.SpikeReceiver \
 *       --es token <t> --es op demo --ez on true
 *
 * That delivery is unreliable on this phone (see SpikeReceiver), so the
 * screenshot script writes the preference file directly with `run-as` and
 * restarts the app. Either way the flag is what matters: the provider below
 * reinstalls the override at process start, which is what lets the script
 * force-stop the app between themes.
 */
/** The diag preference behind GlobalSettings.actAsHidden. */
internal const val HIDE_SETTINGS_KEY = "act_as_hidden_settings"

internal object DemoRecordings {

    private const val TAG = "JemRec"
    private const val PREFS = "jemrec_diag"
    private const val KEY = "demo_recordings"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Installs or removes the override, and remembers the choice. */
    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
        install(on)
        Log.i(TAG, "demo: invented recordings ${if (on) "on" else "off"}")
    }

    fun install(on: Boolean) {
        Recordings.override = if (on) { context -> recordings(context) } else null
        CallHistory.override = if (on) { context -> calls(context) } else null
        Waveforms.override = if (on) { name -> Waveforms.invented(name.hashCode().toLong()) } else null
    }

    /**
     * A silent call [seconds] long, for the player to play in the
     * screenshots - the invented calls have no sound, and a player that will
     * not start shows no waveform. Written as the app writes a recording,
     * with Android's own Ogg muxer (OpusOggWriter), from packets of one empty
     * frame each, which a decoder fills with silence. Made on the phone, once,
     * in the app's cache: there is no audio file in the repository.
     */
    private fun silentCall(context: Context, seconds: Int): Uri? = runCatching {
        // A tenth of a second over: the decoder trims the start, and the
        // player's end time read 4:11 for a 4:12 call with less (the Honor,
        // 2026-10-23). In the name, so a file made shorter is not reused.
        val packets = seconds * 50 + 5
        val file = File(context.cacheDir, "demo-call-$packets.ogg")
        if (file.length() == 0L) {
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray()).put(1).put(2).putShort(312).putInt(48_000).putShort(0).put(0)
                .array()
            val part = File(context.cacheDir, "demo-call.part")
            FileOutputStream(part).use { out ->
                val writer = OpusOggWriter(out.fd, head)
                // CELT, fullband, 20 ms, stereo, one frame - of no bytes.
                val nothing = byteArrayOf(0xfc.toByte())
                repeat(packets) { writer.write(nothing, 0) }
                check(writer.finish())
            }
            check(part.renameTo(file))
        }
        Uri.fromFile(file)
    }.onFailure { Log.w(TAG, "demo: no silent call to play: $it") }.getOrNull()

    /**
     * The Calls tab's invented log: every invented recording as the call it
     * came from, and a few calls that were not recorded, one of each reason
     * worth showing. Nobody here is real, the same as the recordings.
     */
    fun calls(context: Context): List<CallHistory.Call> {
        val now = System.currentTimeMillis()
        // A messenger call is not in the phone's call log, so not here either.
        val recorded = recordings(context).zip(CALLS).filter { (_, call) -> call.app == null }.map { (recording, call) ->
            CallHistory.Call(
                // The log dates a call from when it started ringing; the
                // recording, from when it was answered.
                at = now - call.minutesAgo * 60_000 - 8_000,
                seconds = call.seconds,
                kind = if (call.incoming) CallHistory.Kind.INCOMING else CallHistory.Kind.OUTGOING,
                label = call.caller ?: call.number ?: "Unknown number",
                number = call.number,
                photo = recording.callerPhoto,
                favorite = call.favorite,
                recording = recording,
                status = CallHistory.Status.RECORDED,
            )
        }
        fun unrecorded(minutesAgo: Long, kind: CallHistory.Kind, label: String, seconds: Int, status: CallHistory.Status) =
            CallHistory.Call(now - minutesAgo * 60_000, seconds, kind, label, label, status = status)
        val others = listOf(
            unrecorded(52, CallHistory.Kind.MISSED, "+44 20 7946 0321", 0, CallHistory.Status.NOT_CONNECTED),
            unrecorded(2 * 60 + 10, CallHistory.Kind.OUTGOING, "Priya Raman", 0, CallHistory.Status.NOT_CONNECTED),
            // A caller who hung up during the greeting: answered, nothing kept.
            // Ofcom's drama range, like the other London numbers here.
            unrecorded(4 * 60 + 35, CallHistory.Kind.INCOMING, "+44 20 7946 0123", 9, CallHistory.Status.GREETING_HANGUP),
            unrecorded(24 * 60 + 2 * 60, CallHistory.Kind.INCOMING, "Hana Sato", 192, CallHistory.Status.RECORDING_OFF),
            unrecorded(2 * 24 * 60 + 15, CallHistory.Kind.INCOMING, "Dr. Lena Vogel", 312, CallHistory.Status.SKIPPED),
            unrecorded(3 * 24 * 60 + 40, CallHistory.Kind.INCOMING, "+1 202 555 0147", 58, CallHistory.Status.IN_TRASH),
            unrecorded(5 * 24 * 60 + 9 * 60, CallHistory.Kind.DECLINED, "+1 202 555 0147", 0, CallHistory.Status.NOT_CONNECTED),
        )
        return (recorded + others).sortedByDescending { it.at }
    }

    /**
     * One row of the demo list, before it is turned into a [Recording].
     *
     * `photo` is a drawable in this source set rather than a real contact
     * picture: an invented person with a real face would be someone else's
     * photograph on a public README, beside a record of a phone call.
     */
    private class Call(
        val minutesAgo: Long,
        val incoming: Boolean,
        val caller: String?,
        val number: String?,
        val seconds: Int,
        val bytes: Long,
        val favorite: Boolean = false,
        val photo: String? = null,
        /** A messenger call's app tag (VoipApps), or null for a phone call. */
        val app: String? = null,
        /** A message the answering machine took. */
        val message: Boolean = false,
    )

    private val CALLS = listOf(
        Call(14, true, "Olivia Bennett", "+442079460958", 252, 1_992_294,
            favorite = true, photo = "demo_face_1"),
        Call(3 * 60 + 20, false, "Marcus Chen", "+14155550136", 725, 5_663_129,
            photo = "demo_face_2", app = "whatsapp"),
        Call(6 * 60 + 5, true, "+44 7700 900461", "+447700900461", 47, 360_448, message = true),
        Call(26 * 60, false, "Priya Raman", "+919820098200", 158, 1_258_291),
        Call(2 * 24 * 60 + 3 * 60, true, "Daniel Okafor", "+2348031234567", 2462, 18_664_653),
        Call(4 * 24 * 60 + 90, false, "477", "477", 79, 618_496),
        Call(9 * 24 * 60 + 5 * 60, true, "Sofia Marchetti", "+390612345678", 534, 4_089_446),
        Call(23 * 24 * 60 + 7 * 60, false, null, null, 15, 120_832),
    )

    fun recordings(context: Context): List<Recording> {
        val now = System.currentTimeMillis()
        return CALLS.map { call ->
            val at = Date(now - call.minutesAgo * 60_000)
            Recording(
                // The real names carry the time and the direction, and the list
                // parses both back out of them - so the demo has to use the same
                // format or the Today and This week chips filter it all away.
                name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(at) +
                    (if (call.incoming) "_in" else "_out") + (call.app?.let { "_$it" } ?: "") + ".ogg",
                // The first plays - silence - so the screenshots can show its
                // waveform; the rest are names alone.
                uri = (if (call === CALLS.first()) silentCall(context, call.seconds) else null)
                    ?: Uri.parse("jemrec-demo://call/${call.minutesAgo}"),
                whenLabel = whenLabel(at),
                incoming = call.incoming,
                durationLabel = "%d:%02d".format(call.seconds / 60, call.seconds % 60),
                sizeLabel = size(call.bytes),
                caller = call.caller,
                callerPhoto = call.photo?.let {
                    Uri.parse("android.resource://${context.packageName}/drawable/$it")
                },
                number = call.number,
                callerFavorite = call.favorite,
                message = call.message,
            )
        }
    }

    // Both duplicated from Recordings, which keeps them private and is right to:
    // this is throwaway code in a source set that never ships, and a demo list
    // that reached into the real one would be a reason not to change the real
    // one later.

    private fun whenLabel(at: Date): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(at)
        return if (day.format(at) == day.format(Date())) "Today $time"
        else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(at)
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}

/**
 * Reinstalls the demo override when the app's process starts.
 *
 * A ContentProvider is the hook for this: the system creates every declared
 * provider before the first activity, and this app has no Application subclass
 * to override. Declared only in the debug manifest, so it does not exist in a
 * release build - and it does nothing at all unless the flag is set.
 */
class DemoProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return true
        if (DemoRecordings.isOn(context)) {
            DemoRecordings.install(true)
            Log.i("JemRec", "demo: invented recordings restored at start-up")
        }
        // Act like a Pixel that hides USB debugging and Developer options
        // from apps, on a phone or emulator that does not. Set with the
        // `hidesettings` diag op, or by writing the preference with run-as.
        if (context.getSharedPreferences("jemrec_diag", Context.MODE_PRIVATE)
                .getBoolean(HIDE_SETTINGS_KEY, false)
        ) {
            com.jemcik.jemrec.capture.GlobalSettings.actAsHidden = true
            Log.i("JemRec", "diag: acting as if Android hid adb_enabled and development_settings_enabled")
        }
        // The after-call audio check's numbers, kept where `run-as` can read
        // them: on MagicOS 11 the app's own log lines are not readable.
        val measurements = java.io.File(context.filesDir, "audiocheck.log")
        com.jemcik.jemrec.capture.AudioCheck.onMeasured = { line ->
            measurements.appendText("${java.util.Date()} $line\n")
        }
        // How long a waveform took to work out, the same way.
        val waveforms = java.io.File(context.filesDir, "waveform.log")
        com.jemcik.jemrec.capture.Waveforms.onMeasured = { line ->
            waveforms.appendText("${java.util.Date()} $line\n")
        }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0
}
