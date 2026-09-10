package com.jemcik.jemrec.diag

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Recordings
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
 * favourite, and times spread across today, this week and before it, so the
 * filter chips have something to filter.
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
    )

    private val CALLS = listOf(
        Call(14, true, "Olivia Bennett", "+442079460958", 252, 1_992_294,
            favorite = true, photo = "demo_face_1"),
        Call(3 * 60 + 20, false, "Marcus Chen", "+14155550136", 725, 5_663_129,
            photo = "demo_face_2"),
        Call(6 * 60 + 5, true, "+44 7700 900461", "+447700900461", 47, 360_448),
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
                    (if (call.incoming) "_in" else "_out") + ".ogg",
                uri = Uri.parse("jemrec-demo://call/${call.minutesAgo}"),
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
