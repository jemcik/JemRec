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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /**
     * Two deletes Android must be asked about, said yes to while busy - the
     * second for a row picked between: each is asked in turn, a yes taking
     * its own recording's notification only. One question was held between
     * them: the first was never asked, or its yes took the other's
     * notification and left its own.
     */
    @Test fun twoQuestionsAreAskedInTurn() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FakeMediaStore.install(File(app.cacheDir, "media"))
        val names = listOf("20261020_101010_in.ogg", "20261020_111111_in.ogg")
        val rows = names.mapIndexed { i, name ->
            FakeMediaStore.rows += FakeMediaStore.Row(5L + i, name, notOurs = true)
            val call = AfterCall.Call(
                name, FakeMediaStore.uriOf(5L + i), null, 12.0, true, 0L, "Priya Shah", AudioCheck.Verdict.FINE, true,
            )
            assertEquals(true, AfterCall.show(app, call))
            Recordings.describe(name, FakeMediaStore.uriOf(5L + i), 1000, 1000)
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val vm = viewModel(UiState(recordings = rows, selected = setOf(names[0]), selecting = true))
        val busy = HeldBusy(rows)
        try {
            busy.hold(vm)
            vm.confirmDeleteSelected()
            vm.toggleSelected(rows[1])
            vm.confirmDeleteSelected()
            busy.release()
            settle(vm)

            assertNotNull("the first asked", vm.state.value.deleteConsent)
            vm.onDeleteConsentShown()
            vm.onDeleteConsentResult(true)
            settle(vm)
            // The second's left: tied to its recording as AfterCall ties it.
            assertEquals(
                listOf(com.jemcik.jemrec.recordings.WaveformCache.hashOf(names[1])),
                manager.activeNotifications.map { it.notification.extras.getString("com.jemcik.jemrec.aftercall.RECORDING") },
            )
            assertNotNull("the second asked once the first is answered", vm.state.value.deleteConsent)
            vm.onDeleteConsentShown()
            vm.onDeleteConsentResult(true)
            settle(vm)
            assertEquals(0, manager.activeNotifications.size)
        } finally {
            Recordings.override = null
        }
    }

    /** Android's no to a restore is said as a restore's: it was "deletion
     *  cancelled". */
    @Test fun aRestoreRefusedIsSaidAsOne() {
        val app = RuntimeEnvironment.getApplication()
        FakeMediaStore.install(File(app.cacheDir, "media"))
        val name = "20261020_101010_in.ogg"
        FakeMediaStore.rows += FakeMediaStore.Row(5, name, notOurs = true, trashed = true)
        val row = Recordings.describe(name, FakeMediaStore.uriOf(5), 1000, 1000)
        val vm = viewModel(UiState(screen = com.jemcik.jemrec.ui.Screen.TRASH, trashed = listOf(row)))
        vm.restore(row)
        settle(vm)
        assertNotNull("Android asked", vm.state.value.deleteConsent)
        vm.onDeleteConsentShown()
        vm.onDeleteConsentResult(false)
        settle(vm)
        val log = vm.state.value.log
        assertTrue(log, log.contains("restore cancelled"))
        assertFalse(log, log.contains("deletion cancelled"))
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
