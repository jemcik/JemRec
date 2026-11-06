package com.jemcik.jemrec.ui.screens

import android.Manifest
import android.app.NotificationManager
import androidx.core.net.toUri
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.capture.AudioCheck
import com.jemcik.jemrec.recordings.FakeMediaStore
import com.jemcik.jemrec.recordings.FakeStorage
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

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

    /** A message Android will not let this app move, its notification up,
     *  selected and deleted, and Android's question out. */
    private fun askedAbout(): Pair<MainViewModel, NotificationManager> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val name = "20261020_101010_in.ogg"
        FakeMediaStore.install(File(app.cacheDir, "media"))
        FakeMediaStore.rows += FakeMediaStore.Row(5, name, notOurs = true)
        val call = AfterCall.Call(
            name, FakeMediaStore.uriOf(5), null, 12.0, true, 0L, "Priya Shah", AudioCheck.Verdict.FINE, true,
        )
        assertEquals(true, AfterCall.show(app, call))
        val row = Recordings.describe(name, FakeMediaStore.uriOf(5), 1000, 1000)
        val vm = viewModel(UiState(recordings = listOf(row), selected = setOf(name), selecting = true))
        vm.confirmDeleteSelected()
        settle(vm)
        assertNotNull(vm.state.value.deleteConsent)
        return vm to app.getSystemService(NotificationManager::class.java)
    }

    @Test fun androidsYesTakesTheNotificationOfWhatItMoved() {
        val (vm, manager) = askedAbout()
        assertEquals(1, manager.activeNotifications.size)
        vm.onDeleteConsentResult(true)
        settle(vm)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun androidsNoLeavesIt() {
        val (vm, manager) = askedAbout()
        vm.onDeleteConsentResult(false)
        settle(vm)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun aListingThatFailsTakesNoNotificationWithIt() {
        val (vm, manager) = askedAbout()
        // Nothing is listed: that says nothing of a recording not asked about.
        FakeMediaStore.listing = IllegalStateException("no")
        val app = RuntimeEnvironment.getApplication()
        val other = AfterCall.Call(
            "20261021_101010_in.ogg", FakeMediaStore.uriOf(6), null, 12.0, true, 0L, "Sam", AudioCheck.Verdict.FINE, true,
        )
        assertEquals(true, AfterCall.show(app, other))
        vm.onDeleteConsentResult(true)
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
