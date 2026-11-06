package com.jemcik.jemrec.ui.screens

import android.Manifest
import android.app.NotificationManager
import androidx.core.net.toUri
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.capture.AudioCheck
import com.jemcik.jemrec.recordings.FakeStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** A notification's Play for a recording something else deleted: it says so,
 *  and the notification goes, as nothing is left for it to offer. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordingNoLongerThereTest : ScreenTest() {

    @Test fun aTapThatFindsItGoneTakesTheNotificationAway() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val name = "20261020_101010_in.ogg"
        val call = AfterCall.Call(
            name, "content://media/external/audio/media/5".toUri(), null, 12.0, true, 0L, "Priya Shah", AudioCheck.Verdict.FINE, true,
        )
        assertEquals(true, AfterCall.show(app, call))
        assertEquals(1, manager.activeNotifications.size)
        val vm = viewModel()
        vm.open(DeepLink.Link(DeepLink.Kind.PLAY, name))
        settle(vm)
        assertEquals(0, manager.activeNotifications.size)
    }

    private fun aMessageWithNoFile(): NotificationManager {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val call = AfterCall.Call(
            "20261020_101010_in.ogg", "content://media/external/audio/media/5".toUri(), null, 12.0, true, 0L, "Priya Shah",
            AudioCheck.Verdict.FINE, true,
        )
        assertEquals(true, AfterCall.show(app, call))
        return app.getSystemService(NotificationManager::class.java)
    }

    @Test fun androidsYesTakesTheNotificationOfWhatItMoved() {
        val manager = aMessageWithNoFile()
        val vm = viewModel()
        vm.onDeleteConsentResult(true)
        settle(vm)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun androidsNoLeavesIt() {
        val manager = aMessageWithNoFile()
        val vm = viewModel()
        vm.onDeleteConsentResult(false)
        settle(vm)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun aTapThatFindsItThereLeavesTheNotificationAlone() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FakeStorage.forgetFileProviderFolders()
        val manager = app.getSystemService(NotificationManager::class.java)
        val name = "20261020_101010_in.ogg"
        val file = FakeStorage.appFile(app, name)
        val call = AfterCall.Call(
            name, FakeStorage.appUri(app, file), file.absolutePath, 12.0, true, 0L, "Priya Shah", AudioCheck.Verdict.FINE, true,
        )
        assertEquals(true, AfterCall.show(app, call))
        val vm = viewModel()
        vm.open(DeepLink.Link(DeepLink.Kind.SHARE, name))
        settle(vm)
        assertEquals(1, manager.activeNotifications.size)
    }
}
