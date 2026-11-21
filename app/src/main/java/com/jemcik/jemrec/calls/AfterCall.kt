package com.jemcik.jemrec.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.AfterCallReceiver
import com.jemcik.jemrec.capture.AudioCheck
import com.jemcik.jemrec.minutesSeconds
import com.jemcik.jemrec.recordings.DeleteResult
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.RecordingLibrary
import com.jemcik.jemrec.recordings.RecordingMarks
import com.jemcik.jemrec.recordings.RecordingSaver
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Retention
import com.jemcik.jemrec.recordings.StoppedEarly
import com.jemcik.jemrec.recordings.Trash
import com.jemcik.jemrec.recordings.WaveformCache
import com.jemcik.jemrec.recordings.Waveforms

/**
 * What the user sees the moment a call has been saved.
 *
 * THE RECORDING, WHERE THEY ALREADY ARE
 *
 * The question people ask of a call recorder right after hanging up is "did
 * it get that?", and before this the only way to answer it was to open the
 * app and look. Google Phone keeps its own recordings in a database of its
 * own and has no way in for another app's - checked in its APK: the screen
 * that plays them and the call history behind it are not exported - so this
 * is the next best place: one silent notification with the call's name and
 * length, and Play, Share and Delete on it. It copies what Google's own
 * recorder does with the player it shows for the last call. (Honor's Phone
 * app is the exception, and has its own file names - see FileNames.)
 *
 * Visible but silent: DEFAULT importance, so it puts an icon in the status bar
 * and sits with the other notifications, with its sound and vibration taken
 * away, no banner and no badge. It was LOW first, and on MagicOS LOW means
 * folded into "Muted" at the foot of the shade with no status-bar icon - the
 * first real call on the developer's phone produced it and nobody saw it.
 * One at a time - the newest call replaces the last, so a day of calls is one
 * notification, not a stack of them. It goes away on its own after
 * [TIMEOUT_MS]. It can be turned off in Settings, and like any channel in
 * Android's own settings too. A message the answering machine took is the
 * exception: one each, on a channel of its own, kept until dismissed - so
 * each one's buttons name their own recording (see [open]).
 *
 * AND THE BAD NEWS, LOUDER
 *
 * When the recording turns out to hold nothing - see [AudioCheck] - this is
 * where the user hears about it, on the problems channel, with a sound and
 * whether or not they wanted the everyday notification. A silent recording is
 * the failure people otherwise discover months later, on the day they need it.
 *
 * Every button goes to this app only: Play and Share open JemRec at that
 * recording, through a guarded link (see DeepLink), and Delete goes to a
 * receiver that is not exported - and asks first, in the notification itself,
 * because a stray tap in the shade must not cost anyone a recording.
 */
internal object AfterCall {

    private const val TAG = "JemRec"
    /** v2: v1 was LOW, and a channel's importance cannot be changed once the
     *  user has it - only replaced. See ensureChannels. */
    private const val CHANNEL_ID = "jemrec.aftercall.v2"
    private const val LEGACY_CHANNEL_ID = "jemrec.aftercall.v1"
    const val PROBLEM_CHANNEL_ID = "jemrec.problem.v1"
    /** Messages the answering machine took: news, so a sound, and one
     *  notification each - two left while away must not replace each other. */
    private const val MESSAGE_CHANNEL_ID = "jemrec.messages.v1"
    private const val NOTIFICATION_ID = 33
    private const val MESSAGE_ID_BASE = 3400
    private const val KEY_ENABLED = "after_call_notice"

    /** Long enough to come back to after the call; short enough not to sit in
     *  the shade from one day into the next. */
    private const val TIMEOUT_MS = 12L * 60 * 60 * 1000

    /** A recording the reconcile saved long after its call is history by
     *  then, not news - the list is where it belongs. */
    private const val FRESH_MS = 30L * 60 * 1000

    const val ACTION_DELETE_ASK = "com.jemcik.jemrec.aftercall.DELETE_ASK"
    const val ACTION_DELETE_CONFIRM = "com.jemcik.jemrec.aftercall.DELETE_CONFIRM"
    const val ACTION_DELETE_CANCEL = "com.jemcik.jemrec.aftercall.DELETE_CANCEL"

    /** On unless switched off: it is what most people want after a call. */
    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY_ENABLED, on) }
        if (!on) cancel(context)
    }

    /** The everyday notification. Not a silent recording's warning, which
     *  shares its id and does not depend on the switch. */
    private fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications
            .filter { it.id == NOTIFICATION_ID && it.notification.channelId != PROBLEM_CHANNEL_ID }
            .forEach { manager.cancel(it.id) }
    }

    /** One saved call, as much as the notification and its buttons need. */
    data class Call(
        val name: String,
        val uri: Uri,
        val filePath: String?,
        val seconds: Double,
        val incoming: Boolean,
        val startMillis: Long,
        val caller: String?,
        val verdict: AudioCheck.Verdict,
        /** A message the answering machine took. */
        val message: Boolean = false,
        /** The call's recordings, by when each began, when a recorder lost
         *  mid-call left it in parts - the first of them [name], [uri] and
         *  [filePath]; empty for a call in one. Play opens the first, as the
         *  Calls tab does; Delete takes them all; and there is no Share - one
         *  part shared is half the call (1.4: the longest part was told
         *  alone, its length the call's, Play, Share and Delete on it). */
        val parts: List<Part> = emptyList(),
        /** Seconds between the parts, not recorded. */
        val between: Double = 0.0,
        /** How much of the call's end was not recorded, in ms - its recording
         *  stopped early (StoppedEarly) - or null. */
        val missedMs: Long? = null,
    ) {
        data class Part(val name: String, val uri: Uri, val filePath: String?)

        fun into(intent: Intent): Intent = intent.putExtras(
            Bundle().apply {
                putString("name", name)
                putString("uri", uri.toString())
                putString("path", filePath)
                putDouble("seconds", seconds)
                putBoolean("incoming", incoming)
                putLong("start", startMillis)
                putString("caller", caller)
                putString("verdict", verdict.name)
                putBoolean("message", message)
                putStringArrayList("partNames", ArrayList(parts.map { it.name }))
                putStringArrayList("partUris", ArrayList(parts.map { it.uri.toString() }))
                putStringArrayList("partPaths", ArrayList(parts.map { it.filePath.orEmpty() }))
                putDouble("between", between)
                putLong("missed", missedMs ?: -1L)
            },
        )

        companion object {
            fun from(intent: Intent): Call? = runCatching {
                Call(
                    name = intent.getStringExtra("name")!!,
                    uri = intent.getStringExtra("uri")!!.toUri(),
                    filePath = intent.getStringExtra("path"),
                    seconds = intent.getDoubleExtra("seconds", 0.0),
                    incoming = intent.getBooleanExtra("incoming", false),
                    startMillis = intent.getLongExtra("start", 0L),
                    caller = intent.getStringExtra("caller"),
                    verdict = AudioCheck.Verdict.valueOf(intent.getStringExtra("verdict")!!),
                    message = intent.getBooleanExtra("message", false),
                    parts = partsOf(intent),
                    between = intent.getDoubleExtra("between", 0.0),
                    missedMs = intent.getLongExtra("missed", -1L).takeIf { it >= 0 },
                )
            }.getOrNull()

            private fun partsOf(intent: Intent): List<Part> {
                val names = intent.getStringArrayListExtra("partNames").orEmpty()
                val uris = intent.getStringArrayListExtra("partUris").orEmpty()
                val paths = intent.getStringArrayListExtra("partPaths").orEmpty()
                return names.indices.map { i -> Part(names[i], uris[i].toUri(), paths[i].ifEmpty { null }) }
            }
        }
    }

    /**
     * A call has just been saved: check what is in it and say so. Blocking -
     * it decodes the file - so callers are on a background thread. [call]:
     * every recording of the call saved, [saved] - the one it is told by -
     * among them; more than one when a recorder lost mid-call left it in
     * parts, all said in one notice (Call.parts).
     */
    fun saved(
        context: Context,
        saved: RecordingSaver.Outcome.Saved,
        incoming: Boolean,
        startMillis: Long,
        call: List<RecordingSaver.Outcome.Saved> = listOf(saved),
    ) {
        // Both save paths come through here, so this is where a call under
        // the user's short-call rule is caught: to the trash, and not a word.
        if (Retention.dropIfTooShort(context, saved)) return
        val parts = call.sortedBy { it.startMillis }
        val endedAt = parts.maxOf { it.startMillis + (it.seconds * 1000).toLong() }
        // A message is news whenever it turns up: the user did not hear it.
        if (!saved.message && System.currentTimeMillis() - endedAt > FRESH_MS) {
            Log.i(TAG, "aftercall: ${FileNames.forLog(saved.name)} is from a while ago, not announcing it")
            // Not announced, but drawn: its waveform as any saved call's, or
            // it waited for the list's fill-in - the app open, its turn come.
            parts.forEach { Waveforms.levels(context, it.name, it.uri) }
            return
        }
        // Silent only if every part long enough to judge is (AudioCheck).
        val verdict = AudioCheck.ofCall(parts.map { AudioCheck.check(context, it.uri) })
        val caller = runCatching { callerFor(context, saved, incoming) }.getOrNull()
        val first = parts.first()
        val told = Call(
            first.name, first.uri, first.filePath, parts.sumOf { it.seconds }, incoming,
            if (parts.size > 1) first.startMillis else startMillis, caller, verdict, saved.message,
            parts = if (parts.size > 1) parts.map { Call.Part(it.name, it.uri, it.filePath) } else emptyList(),
            between = between(parts),
            // The last part's: an earlier one that stopped is carried on.
            missedMs = parts.lastOrNull { it.earlyMissedMs != null }?.earlyMissedMs,
        )
        if (show(context, told)) tookThePlaceOfOthers(context, told)
        // Its waveform worked out now, the notification up and nobody waiting:
        // the player then draws it at once. A 30-minute call's takes about
        // 2.5 s (the Honor, 2026-10-23, Waveforms).
        parts.forEach { Waveforms.levels(context, it.name, it.uri) }
    }

    /**
     * What this call's notice, just posted for the first time, makes old:
     * a message cut short says so itself, and the early notice its save
     * posted goes; a call that stopped early has its notice at the early
     * id, and the last call's everyday notice goes, as a newer call's always
     * replaces it (not a silent recording's warning, a problem kept). Here,
     * not in [show]: Keep shows a notice again, and a newer call's notice by
     * then would have gone with it (review round 2 of 1.4).
     */
    private fun tookThePlaceOfOthers(context: Context, call: Call) {
        when {
            call.message && call.missedMs != null -> cancelProblem(context, StoppedEarly.NOTIFICATION_ID)
            early(call) -> cancel(context)
        }
    }

    /** Seconds between a call's parts ([parts] by start) not recorded. */
    internal fun between(parts: List<RecordingSaver.Outcome.Saved>): Double =
        parts.zipWithNext { a, b -> (b.startMillis - a.startMillis) / 1000.0 - a.seconds }.sumOf { maxOf(0.0, it) }

    /** Who, the way the list would say it: the call log for a phone call,
     *  the name or the notes for a messenger call ("Viber call" at least). */
    private fun callerFor(context: Context, saved: RecordingSaver.Outcome.Saved, incoming: Boolean): String? {
        // Matched by its start, which no zone moves, and a joined part by
        // its call's (review round 14), as the list matches it.
        val probe = Recording(
            saved.name, saved.uri, "", incoming, "", "",
            startUtc = saved.startMillis, callAt = saved.callAt.takeIf { it >= 0 },
        )
        return RecordingMarks.withCallers(context, listOf(probe)).firstOrNull()?.caller
    }

    /** Posts it, unless the user has the everyday one switched off. Whether
     *  it posted. */
    fun show(context: Context, call: Call): Boolean {
        // Only silence. A one-sided verdict is not a missing voice on every
        // phone - see AudioCheck - and a false alarm after a call teaches
        // people to ignore the true one. Nor in a message: the user's side is
        // muted, and a caller who says nothing after the beep is a silent
        // message, not a recording gone wrong.
        val problem = silent(call) || early(call)
        // A message is something the user did not hear: posted whether or not
        // they want the everyday after-call notice.
        if (!problem && !call.message && !enabled(context)) return false
        ensureChannels(context)

        val who = call.caller ?: if (call.incoming) "Incoming call" else "Outgoing call"
        val length = length(call.seconds)
        val inParts = if (call.parts.size > 1) " in ${call.parts.size} parts" else ""
        val channel = channelFor(call)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(if (problem) android.R.drawable.stat_notify_error else android.R.drawable.ic_btn_speak_now)
            .setWhen(call.startMillis)
            .setShowWhen(true)
            .setContentIntent(open(context, DeepLink.Kind.PLAY, call.name, request = 0))
            .setAutoCancel(true)
            // Also what keeps a call that stopped early to the one sound: its
            // notice replaces the early one posted at its save (idFor).
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(timeoutOf(call))
            .addExtras(recordingOf(call))
            // A caller's name is not for the lock screen. The public version
            // says only that a call was recorded.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, channel)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(
                        when {
                            problem -> "A call recording needs a look"
                            call.message -> "New message"
                            else -> "Call recorded"
                        },
                    )
                    .build(),
            )

        // What it is, for Do Not Disturb and the shade's order, as every other
        // problem says it (problem()): "an error in background operation".
        if (problem) builder.setCategory(Notification.CATEGORY_ERROR)

        // The message first: one the caller left in silence is a message
        // still, not a recording gone wrong (see problem).
        when {
            call.message -> {
                // Kept until dismissed: a message is not stale after half a day.
                val text = call.missedMs?.let {
                    "$length message, cut short: the recording stopped about ${StoppedEarly.seconds(it)} s " +
                        "before the caller hung up"
                } ?: "$length message"
                builder.setContentTitle(call.caller?.let { "Message from $it" } ?: "New message")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .addAction(action("Play", open(context, DeepLink.Kind.PLAY, call.name, request = 1)))
                    .addAction(action("Share", open(context, DeepLink.Kind.SHARE, call.name, request = 2)))
                    .addAction(action("Delete", receiver(context, ACTION_DELETE_ASK, call), unlocked = true))
            }
            // The early end first: what went wrong, and the one to act on -
            // a short remnant's silence is no sign of a muted phone.
            early(call) -> {
                val text = "$who, $length recorded$inParts. It stopped about " +
                    "${StoppedEarly.seconds(call.missedMs!!)} s before the call ended: the recorder was ended, or the " +
                    "call's sound could not be taken." + if (silent(call)) " No sound came through in what was recorded." else ""
                builder.setContentTitle("Recording stopped early")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .addAction(action("Play", open(context, DeepLink.Kind.PLAY, call.name, request = 1)))
                if (call.parts.isEmpty()) builder.addAction(action("Share", open(context, DeepLink.Kind.SHARE, call.name, request = 2)))
                builder.addAction(action("Delete", receiver(context, ACTION_DELETE_ASK, call), unlocked = true))
            }
            problem -> {
                val text = "$who, $length$inParts. No sound came through. This happens on calls over " +
                    "Wi-Fi calling, and on some phones always. Run the self-test to check this phone."
                builder.setContentTitle("Last call recorded, but silent")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .addAction(action("Play", open(context, DeepLink.Kind.PLAY, call.name, request = 1)))
                    .addAction(action("Self-test", open(context, DeepLink.Kind.SELF_TEST, null, request = 3)))
                    .addAction(action("Delete", receiver(context, ACTION_DELETE_ASK, call), unlocked = true))
            }
            else -> {
                val gap = Math.round(call.between)
                val text = when {
                    call.parts.isEmpty() -> "Recorded, $length"
                    gap > 0 -> "Recorded$inParts, $length; about $gap s between them not recorded"
                    else -> "Recorded$inParts, $length"
                }
                builder.setContentTitle(who).setContentText(text)
                // Whole when expanded: Android cuts the text to one line, and
                // the parts' clause was past it (review round 2 of 1.4).
                if (call.parts.isNotEmpty()) builder.setStyle(Notification.BigTextStyle().bigText(text))
                builder.addAction(action("Play", open(context, DeepLink.Kind.PLAY, call.name, request = 1)))
                if (call.parts.isEmpty()) builder.addAction(action("Share", open(context, DeepLink.Kind.SHARE, call.name, request = 2)))
                builder.addAction(action("Delete", receiver(context, ACTION_DELETE_ASK, call), unlocked = true))
            }
        }
        post(context, builder.build(), idFor(call))
        Log.i(
            TAG,
            "aftercall: posted for ${FileNames.forLog(call.name)} (${call.verdict}${if (call.message) ", message" else ""}" +
                (if (call.parts.size > 1) ", ${call.parts.size} parts" else "") +
                (if (call.missedMs != null) ", stopped early" else "") + ")",
        )
        return true
    }

    private fun silent(call: Call) = call.verdict == AudioCheck.Verdict.SILENT && !call.message

    /** A call - not a message - whose recording stopped early: told in the
     *  early notice's place (StoppedEarly.NOTIFICATION_ID). */
    private fun early(call: Call) = call.missedMs != null && !call.message

    /** Kept until dismissed: a message, and a call that stopped early, as
     *  StoppedEarly's own notice was; the everyday one, half a day. */
    private fun timeoutOf(call: Call) = if (call.message || early(call)) 0L else TIMEOUT_MS

    /** A call's channel, for the notification and for all that takes its
     *  place: with one channel switched off in Android, a question or a note
     *  on it would not be shown, and its button would seem to do nothing. */
    private fun channelFor(call: Call) = when {
        silent(call) || early(call) -> PROBLEM_CHANNEL_ID
        call.message -> MESSAGE_CHANNEL_ID
        else -> CHANNEL_ID
    }

    /** One notification for the last call; one for each message; and for a
     *  call that stopped early, the early notice's own, replaced in place -
     *  posted at its save, it stays if the process dies before this one. */
    private fun idFor(call: Call): Int = when {
        call.message -> messageId(call.name)
        early(call) -> StoppedEarly.NOTIFICATION_ID
        else -> NOTIFICATION_ID
    }

    private fun messageId(name: String) = MESSAGE_ID_BASE + (name.hashCode() and 0xffff)

    /** What ties a notification to its recording: see [gone]. */
    private const val EXTRA_RECORDING = "com.jemcik.jemrec.aftercall.RECORDING"

    private fun recordingOf(call: Call) = Bundle().apply { putString(EXTRA_RECORDING, tag(call.name)) }

    /** The name's hash: extras are open to any notification listener the user
     *  allowed, and a file name can hold a caller's number. */
    private fun tag(name: String) = WaveformCache.hashOf(name)

    private fun tagOf(notification: Notification) = notification.extras.getString(EXTRA_RECORDING)

    /**
     * The recording [name] has left the list: its notification goes with it,
     * if one is up. A message's has no timeout, and one deleted from the list
     * stayed, with Play, Share and Delete on nothing (the Honor, 2026-11-06).
     * The everyday one is shared by each last call, so it goes only if it is
     * this recording's. A notification failing is no reason to fail a delete.
     */
    fun gone(context: Context, name: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications
                .filter {
                    // One posted before they said whose they are is known by
                    // its id alone. Only that one: ids are 16 bits of a hash,
                    // and two messages can share one.
                    val whose = tagOf(it.notification)
                    whose == tag(name) || (whose == null && it.id == messageId(name))
                }
                .forEach { manager.cancel(it.id) }
        }
    }

    /** The second tap: the notification turns into the question. */
    fun askDelete(context: Context, call: Call) {
        ensureChannels(context)
        val text = when (call.parts.size) {
            0, 1 -> "Delete the recording of this call? It goes to Recently deleted, where it " +
                "can be restored for ${Trash.DAYS} days."
            2 -> "Delete both parts of this call's recording? They go to Recently deleted, where they " +
                "can be restored for ${Trash.DAYS} days."
            else -> "Delete all ${call.parts.size} parts of this call's recording? They go to Recently deleted, " +
                "where they can be restored for ${Trash.DAYS} days."
        }
        val notification = Notification.Builder(context, channelFor(call))
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle(call.caller ?: if (call.incoming) "Incoming call" else "Outgoing call")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(call.startMillis)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .addExtras(recordingOf(call))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // A message's question stays as long as the message would have.
            .setTimeoutAfter(timeoutOf(call))
            // Delete only on a phone unlocked (Notification.Action's
            // setAuthenticationRequired, Android 12): a broadcast runs from the
            // lock screen otherwise, and two taps there - by whoever holds the
            // locked phone - put the call in the trash.
            .addAction(action("Delete", receiver(context, ACTION_DELETE_CONFIRM, call), unlocked = true))
            .addAction(action("Keep", receiver(context, ACTION_DELETE_CANCEL, call)))
            .build()
        post(context, notification, idFor(call))
    }

    fun delete(context: Context, call: Call) {
        // Every part of a call in parts: its notice spoke for them all - but
        // one already trashed from the list is done, not a failure to say.
        val each = if (call.parts.isEmpty()) {
            listOf(Call.Part(call.name, call.uri, call.filePath))
        } else {
            val listed = Recordings.list(context, withCallers = false).mapTo(HashSet()) { it.name }
            call.parts.filter { it.name in listed }
        }
        val results = each.map { part ->
            Trash.trash(context, part.uri, part.filePath, part.name).also { result ->
                Log.i(TAG, "aftercall: delete ${FileNames.forLog(part.name)} -> ${result.javaClass.simpleName}")
                // Its notification has gone with it (Trash.trash): by id here
                // would take a newer call's.
                if (result is DeleteResult.Deleted) RecordingLibrary.announce(part.name)
            }
        }
        when {
            // MediaStore wants the user to confirm in a system dialog, which
            // only an activity can show. The list can.
            results.any { it is DeleteResult.NeedsConsent } -> note(context, "Open JemRec to delete this recording.", call)
            results.any { it is DeleteResult.Failed } -> note(context, "Could not delete the recording.", call)
        }
    }

    fun cancel(context: Context, call: Call) {
        context.getSystemService(NotificationManager::class.java).cancel(idFor(call))
    }

    /** [text] in place of [call]'s notification - the question it answers. */
    private fun note(context: Context, text: String, call: Call) {
        ensureChannels(context)
        post(
            context,
            Notification.Builder(context, channelFor(call))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JemRec")
                .setContentText(text)
                .addExtras(recordingOf(call))
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(timeoutOf(call))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, DeepLink.home(context),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .build(),
            idFor(call),
        )
    }

    private fun post(context: Context, notification: Notification, id: Int = NOTIFICATION_ID) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }.onFailure { Log.w(TAG, "aftercall: could not post", it) }
    }

    /** "3:21", or "45 s" for a call under a minute. */
    internal fun length(seconds: Double): String {
        val total = seconds.toLong()
        return if (total < 60) "$total s" else minutesSeconds(total)
    }

    private fun action(label: String, intent: PendingIntent, unlocked: Boolean = false): Notification.Action =
        Notification.Action.Builder(null as Icon?, label, intent).setAuthenticationRequired(unlocked).build()

    /**
     * A notification on the "Recording problems" channel, in the shape every
     * one of them shares - the recorder down (RecorderHealth), a call not
     * recorded (postNotRecorded), calls missed (MissedCalls), the phone
     * stopping JemRec (BackgroundKill), a recording that stopped early
     * (StoppedEarly), one saved elsewhere (FolderAccess), Battery Restricted
     * (BatteryHealth) - ProblemButtonsTest reads them all: the error icon, [title], [text] shown
     * whole when expanded, a tap that opens [open] - JemRec, where the
     * problem is shown - and clears it, and at most one button, [button]: its
     * label and the fix itself, somewhere the tap does not go. Android's
     * guide: "Don't include text actions that duplicate the behavior of
     * tapping on the notification body", and a tap "must display UI that
     * relates directly to that notification" (developer.android.com,
     * Notifications); until 1.2's reviews every button went where the tap
     * did. [collapsed] is the
     * one folded line when that is not simply the start of [text], as for a
     * list of missed calls. Callers add only what is theirs - quiet updates, a
     * delete intent, a lock-screen version - and post with [notifyProblem].
     *
     * One recipe, so a change to how problems look is made once: four copies
     * of it had grown by 0.9, the same in all but the details.
     */
    internal fun problem(
        context: Context,
        title: String,
        text: String,
        open: PendingIntent,
        button: Pair<String, PendingIntent>? = null,
        collapsed: String = text,
    ): Notification.Builder {
        ensureChannels(context)
        val builder = Notification.Builder(context, PROBLEM_CHANNEL_ID)
            // What it is, for Do Not Disturb and the shade's order: "an error
            // in background operation" (Notification.CATEGORY_ERROR).
            .setCategory(Notification.CATEGORY_ERROR)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (button != null) builder.addAction(action(button.first, button.second))
        return builder
    }

    /** Posts a [problem] notification as [id], logging as [what] if it
     *  cannot. True if it can be seen: Android drops a post without a word
     *  while JemRec's notifications, or the problems' channel, are off - and
     *  one said "once" was then never said (review, 2026-11-18). */
    internal fun notifyProblem(context: Context, id: Int, notification: Notification, what: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "$what: could not post", it) }
            .isSuccess
        val shown = manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(PROBLEM_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        return posted && shown
    }

    /** "Call not recorded" - the hang-up's save, or a collection that found
     *  out later. */
    private const val NOT_RECORDED_ID = 31

    /** Why an answered call kept nothing: the greeting would not play. */
    const val GREETING_FAILED = "The answering machine answered a call, but could not play your " +
        "greeting, so it hung up. Run the test call in Settings, Answering machine."

    /** Why a saved call kept nothing: no call audio in it. */
    const val NO_AUDIO = "No call audio came through. Some phones do not allow calls to be recorded."

    /** The title of a part's failure, told at the call's end
     *  (CallMonitorService.saveCall; AfterSave.Collector.PART). */
    const val PART_NOT_SAVED = "Part of a call not saved"

    /** The recorder's files the failure up is about (postNotRecorded). */
    private const val KEY_NOT_RECORDED_FOR = "not_recorded_for"

    /** A failure about to be told is about [recfile] (AfterSave.react; a
     *  row that tells): [retried], one kept to try again, joins the files
     *  the alert up is about - a fresh list once the alert is gone, swiped
     *  or tapped; one never to be saved (no audio, a greeting that failed)
     *  empties it, as the alert now says what no save will mend. The list
     *  kept every file and never shrank but by a save: one empty call
     *  swiped away held every later alert up (review round 17). [upAlready]:
     *  posted a moment ago by the same call's reaction, which Android may
     *  not list yet - NMS enqueues a post on its own handler
     *  (enqueueNotificationInternal; read, not produced: review round 18). */
    @Synchronized
    fun notRecordedAbout(context: Context, recfile: String, retried: Boolean, upAlready: Boolean = false) {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        val up = upAlready || runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOT_RECORDED_ID }
        }.getOrDefault(false)
        val now = if (up) prefs.getStringSet(KEY_NOT_RECORDED_FOR, emptySet()).orEmpty() else emptySet()
        prefs.edit { putStringSet(KEY_NOT_RECORDED_FOR, if (retried) now + recfile else emptySet()) }
    }

    /** [recfile] saved after all: once every file the failure up is about
     *  is, it goes - a later collection saved it, and "Call not recorded"
     *  stayed up (review round 15); one file of several saved left the
     *  others' alert up (round 16). */
    @Synchronized
    fun savedAfterAll(context: Context, recfile: String) {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        val now = prefs.getStringSet(KEY_NOT_RECORDED_FOR, emptySet()).orEmpty()
        if (recfile !in now) return
        val left = now - recfile
        prefs.edit { if (left.isEmpty()) remove(KEY_NOT_RECORDED_FOR) else putStringSet(KEY_NOT_RECORDED_FOR, left) }
        if (left.isEmpty()) cancelProblem(context, NOT_RECORDED_ID)
    }

    /** [recfile] will never be saved: given up after a week of failing
     *  (RecordingSaver.failedAgain, review round 18), or dropped - empty,
     *  left out, never answered, hung up during the greeting (AfterSave.react,
     *  round 19). No save would take it out of the files the alert is about,
     *  which then stayed up after the one it shows was saved. It leaves them;
     *  the alert stays - still true of it. */
    @Synchronized
    fun neverSaved(context: Context, recfile: String) {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        val now = prefs.getStringSet(KEY_NOT_RECORDED_FOR, emptySet()).orEmpty()
        if (recfile in now) prefs.edit { putStringSet(KEY_NOT_RECORDED_FOR, now - recfile) }
    }

    /** "Call not recorded", with [reason]: from the call service at a hang-up,
     *  or from a collection that found out later (RecordingSaver.reconcile);
     *  [title] another, a part's (PART_NOT_SAVED). */
    fun postNotRecorded(context: Context, reason: String, title: String = "Call not recorded") {
        runCatching {
            // A collection can get here before the call service ever ran:
            // problem makes sure the channel exists.
            val tap = PendingIntent.getActivity(
                context, 0, DeepLink.home(context),
                PendingIntent.FLAG_IMMUTABLE,
            )
            notifyProblem(context, NOT_RECORDED_ID, problem(context, title, reason, tap).build(), "monitor")
        }.onFailure { Log.w(TAG, "monitor: could not report the failure", it) }
    }

    /** Takes down the [problem] notification posted as [id]; never throws. */
    internal fun cancelProblem(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id) }
    }

    /**
     * The recording rides in the intent's identifier as well as its extras.
     * Android tells PendingIntents apart by the intent without its extras, so
     * with the name only in those, every notification's Play was one and the
     * same - updated to the newest call's - and with two messages up, the
     * older one's Delete deleted the newer one.
     */
    private fun open(context: Context, kind: DeepLink.Kind, name: String?, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_BASE + request,
            DeepLink.intent(context, kind, name).setIdentifier(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun receiver(context: Context, action: String, call: Call): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + action.hashCode(),
            call.into(Intent(context, AfterCallReceiver::class.java).setAction(action).setIdentifier(call.name)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQUEST_BASE = 3300

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "After a call", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The recording of the call that just ended, with Play, Share and Delete"
                // Seen, not heard: the status-bar icon is the point, a chime
                // after every call is not.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A message the answering machine took, with Play, Share and Delete"
            },
        )
        // The same channel CallMonitorService creates for "Call not recorded":
        // one place in Android's settings for everything that went wrong.
        manager.createNotificationChannel(
            NotificationChannel(PROBLEM_CHANNEL_ID, "Recording problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a call goes unrecorded, or something stops JemRec recording"
            },
        )
    }
}
