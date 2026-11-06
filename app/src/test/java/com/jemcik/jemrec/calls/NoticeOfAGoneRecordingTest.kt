package com.jemcik.jemrec.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.jemcik.jemrec.capture.AudioCheck
import com.jemcik.jemrec.recordings.FakeStorage
import com.jemcik.jemrec.recordings.RecordingStore
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Retention
import com.jemcik.jemrec.recordings.Trash
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Date

/**
 * The after-call notification of a recording that has gone. A message's has no
 * timeout, so one deleted from the list stayed, with Play, Share and Delete on
 * nothing (the Honor, 2026-11-06).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class NoticeOfAGoneRecordingTest {

    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val day = 24L * 60 * 60 * 1000

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun shown() = manager.activeNotifications.size

    /** A recording in app storage, listed, its notification up. */
    private fun recorded(daysAgo: Long = 1, message: Boolean = false): AfterCall.Call {
        val name = RecordingStore.nameFor(Date(System.currentTimeMillis() - daysAgo * day), incoming = true)
        val file = FakeStorage.appFile(app, name)
        val call = AfterCall.Call(
            name, FakeStorage.appUri(app, file), file.absolutePath, 12.0, true, 0L, "Priya Shah", AudioCheck.Verdict.FINE, message,
        )
        assertEquals(true, AfterCall.show(app, call))
        return call
    }

    private fun row(call: AfterCall.Call) = Recordings.list(app).single { it.name == call.name }

    @Test fun aMessageDeletedFromTheListTakesItsNotificationWithIt() {
        val message = recorded(message = true)
        assertEquals(1, shown())
        Trash.trashAll(app, listOf(row(message)))
        assertEquals(0, shown())
    }

    @Test fun soDoesTheLastCall() {
        val call = recorded()
        Trash.trashAll(app, listOf(row(call)))
        assertEquals(0, shown())
    }

    @Test fun soDoesOneAskingWhetherToDelete() {
        // The last call's: a message's goes by its id in any case.
        val call = recorded()
        AfterCall.askDelete(app, call)
        Trash.trashAll(app, listOf(row(call)))
        assertEquals(0, shown())
    }

    @Test fun anotherRecordingsNotificationStays() {
        val first = recorded(daysAgo = 1, message = true)
        val other = recorded(daysAgo = 2, message = true)
        Trash.trashAll(app, listOf(row(first)))
        assertEquals(1, shown())
        Trash.trashAll(app, listOf(row(other)))
        assertEquals(0, shown())
    }

    @Test fun theLastCallsNotificationIsOnlyThatCallsToTake() {
        val earlier = recorded(daysAgo = 2)
        recorded(daysAgo = 1)
        // One notification for each last call: this one is the newer's.
        Trash.trashAll(app, listOf(row(earlier)))
        assertEquals(1, shown())
    }

    @Test fun aMessageWhoseIdIsAnothersIsNotTakenForIt() {
        // "Aa" and "BB" hash alike, so their notifications share an id.
        val message = AfterCall.Call(
            "Aa.ogg", "content://media/external/audio/media/5".toUri(), null, 12.0, true, 0L, null, AudioCheck.Verdict.FINE, true,
        )
        assertEquals(true, AfterCall.show(app, message))
        AfterCall.gone(app, "BB.ogg")
        assertEquals(1, shown())
    }

    @Test fun aMessagesNotificationFromBeforeTheySaidWhoseGoesByItsId() {
        val name = "20261020_101010_in.ogg"
        val id = 3400 + (name.hashCode() and 0xffff)
        val old = Notification.Builder(app, "x").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        manager.createNotificationChannel(NotificationChannel("x", "x", NotificationManager.IMPORTANCE_LOW))
        manager.notify(id, old)
        assertEquals(1, shown())
        AfterCall.gone(app, name)
        assertEquals(0, shown())
    }

    @Test fun aNoteThatItCouldNotBeDeletedGoesWithTheRecordingToo() {
        val call = recorded()
        val gone = java.io.File(call.filePath!!)
        // The folder will not take the rename: said in the notification's place.
        val folder = FakeStorage.appFolder(app).apply { setWritable(false) }
        val row = row(call)
        try {
            assumeFalse("the tests run as root", folder.canWrite())
            AfterCall.delete(app, call)
            assertEquals(1, shown())
        } finally {
            folder.setWritable(true)
        }
        assertEquals(true, gone.exists())
        Trash.trashAll(app, listOf(row))
        assertEquals(0, shown())
    }

    @Test fun oneMovedByAutoDeleteGoesToo() {
        val old = recorded(daysAgo = 40)
        assertEquals(1, shown())
        Retention.setMaxAgeDays(app, 30)
        Retention.run(app)
        assertEquals(0, shown())
        assertEquals(listOf(old.name), Trash.list(app).map { it.name })
    }

    @Test fun oneDeletedForGoodGoesToo() {
        val message = recorded(message = true)
        // In Recently deleted with its notification up, as after a move
        // Android did on the user's yes.
        val trashed = row(message)
        Trash.trash(app, trashed.uri, trashed.filePath, trashed.name)
        assertEquals(true, AfterCall.show(app, message))
        Trash.deleteForGood(app, Trash.list(app))
        assertEquals(0, shown())
    }

    @Test fun aRecordingThatCouldNotBeMovedKeepsIt() {
        val call = recorded()
        val row = row(call)
        val folder = FakeStorage.appFolder(app).apply { setWritable(false) }
        try {
            assumeFalse("the tests run as root", folder.canWrite())
            Trash.trashAll(app, listOf(row))
            assertEquals(1, shown())
        } finally {
            folder.setWritable(true)
        }
    }
}
