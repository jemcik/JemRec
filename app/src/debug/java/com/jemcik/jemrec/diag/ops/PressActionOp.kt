package com.jemcik.jemrec.diag.ops

import android.app.NotificationManager
import android.content.Context
import android.content.Intent

// One of JemRec's own notifications' buttons pressed, as posted: the action
// titled --es action on the one titled --es title, its own PendingIntent sent.
// For a button in the shade's silent part, where uiautomator does not reach.
internal object PressActionOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty()
        val label = intent.getStringExtra("action").orEmpty()
        val posted = appContext.getSystemService(NotificationManager::class.java).activeNotifications.map { it.notification }
        val shown = posted.firstOrNull { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == title }
        val button = shown?.actions?.firstOrNull { it.title?.toString() == label }
        button?.actionIntent?.send()
        report(
            appContext, "pressaction",
            when {
                shown == null -> "no notification \"$title\""
                button == null -> "\"$title\" has no \"$label\": ${shown.actions?.map { it.title }}"
                else -> "pressed $label on $title"
            },
        )
    }
}
