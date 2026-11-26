package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.jemcik.jemrec.calls.RecordPrompt
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The call's notification posted once more, alone, after two posts close
 * together, of which Android may show the first last (CallMonitorService's
 * again). What Android does with them is the emulators' to show; here, what
 * it is given and when, by the looper's own clock.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], shadows = [QuickPostsTest.Posts::class])
class QuickPostsTest {

    /** Every post: how long after the test began, and its title. */
    @Implements(Service::class)
    class Posts : ShadowService() {
        @Implementation(minSdk = Build.VERSION_CODES.Q)
        override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            if (refused) throw IllegalStateException("Service.startForeground() not allowed")
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            posted += SystemClock.uptimeMillis() - began to title
            super.startForeground(id, notification, foregroundServiceType)
            if (posted.size == slow) SystemClock.sleep(600)
        }
    }

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<CallMonitorService>
    private var startId = 0
    private val realWatch = CallMonitorService.watchFor
    private val realListen = CallMonitorService.listenToMode
    private val realDispatcher = CallMonitorService.dispatcher
    private val file = "jemrec_rec_${System.currentTimeMillis() - 60_000}_in.dat"

    @Before fun create() {
        ShadowLog.clear()
        CallMonitorService.watchFor = { _, _ -> null }
        CallMonitorService.listenToMode = { _, _ -> }
        // The service's jobs never get a turn - a save, a cable's watch: the
        // posts here are the starts' own and the one more.
        CallMonitorService.dispatcher = Executor { }.asCoroutineDispatcher()
        controller = Robolectric.buildService(CallMonitorService::class.java).create()
        posted.clear()
        refused = false
        slow = 0
        began = SystemClock.uptimeMillis()
    }

    @After fun destroy() {
        controller.destroy()
        CallMonitorService.watchFor = realWatch
        CallMonitorService.listenToMode = realListen
        CallMonitorService.dispatcher = realDispatcher
    }

    private fun wake(action: String, recfile: String = file) {
        val intent = Intent("com.jemcik.jemrec.$action")
            .setClassName(context, "com.jemcik.jemrec.capture.CallMonitorService")
            .putExtra("recfile", recfile)
            .putExtra("incoming", true)
            .putExtra("token", CaptureDaemon.token(context))
        controller.get().onStartCommand(intent, 0, ++startId)
    }

    private fun pass(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    @Test fun aWakesTwoPostsAreFollowedByOneMoreAlone() {
        wake("CALL_STARTED")
        assertEquals(listOf(0L to "Call in progress", 0L to "Recording call"), posted)
        pass(499)
        assertEquals("not yet", 2, posted.size)
        pass(1)
        assertEquals(500L to "Recording call", posted.last())
        pass(10_000)
        assertEquals("and no more: it is alone", 3, posted.size)
    }

    /** What it says is what is so by then, its buttons with it: here Ask
     *  first's ticket, gone since the pair with no post of its own. */
    @Test fun theOneMoreSaysWhatIsSoByThen() {
        wake("CALL_STARTED", recfile = "")
        assertEquals("Not recording this call", posted.last().second)
        assertEquals(listOf("Record"), buttons())
        RecordPrompt.dismiss(context)
        pass(499)
        assertEquals("not yet", listOf("Record"), buttons())
        pass(1)
        assertEquals(3, posted.size)
        assertEquals("Call in progress", posted.last().second)
        assertEquals(emptyList<String>(), buttons())
    }

    private fun buttons() =
        shadowOf(controller.get()).lastForegroundNotification.actions.orEmpty().map { it.title.toString() }

    /** A second post that Android keeps waiting - its lock held, which is
     *  when the first is lost - is as close to the one before. */
    @Test fun aPostThatWaitsIsStillThePairsSecond() {
        slow = 2
        wake("CALL_STARTED")
        pass(10_000)
        assertEquals(listOf(0L, 0L, 1_100L), posted.map { it.first })
    }

    /** ...and the next start is measured from the end of that wait. */
    @Test fun aPostAfterOneThatWaitedIsMeasuredFromItsEnd() {
        slow = 1
        controller.get().onStartCommand(Intent(), 0, ++startId)
        pass(499)
        controller.get().onStartCommand(Intent(), 0, ++startId)
        pass(10_000)
        assertEquals(listOf(0L, 1_099L, 1_599L), posted.map { it.first })
    }

    /** Not for a call that is over, while its save keeps the service: "Call
     *  in progress" would come back for it. */
    @Test fun aCallsEndDropsTheOneMore() {
        wake("CALL_STARTED")
        wake("CALL_ENDED")
        assertEquals(listOf("Call in progress", "Recording call", "Recording call"), posted.map { it.second })
        pass(10_000)
        assertFalse("kept for the save", shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(3, posted.size)
    }

    @Test fun manyInARowAreFollowedByOneAfterTheLast() {
        wake("CALL_STARTED")
        for (i in 1..5) {
            pass(200)
            wake(if (i % 2 == 1) "RECORDING_STOPPED" else "RECORDING_BACK")
        }
        assertEquals("two each, no more while they come", 12, posted.size)
        pass(499)
        assertEquals(12, posted.size)
        pass(1)
        assertEquals(1_500L to "Recording stopped", posted.last())
        pass(10_000)
        assertEquals(13, posted.size)
    }

    /** A cable's wake, one post and nothing near it: nothing to put right. */
    @Test fun aPostAloneIsNotPostedAgain() {
        wake("CABLE")
        pass(10_000)
        assertEquals(listOf("Checking the recorder"), posted.map { it.second })
    }

    /** ...a start this service ignores, too: one post, as before 1.8.1. */
    @Test fun aStrayStartIsNotPostedAgain() {
        controller.get().onStartCommand(Intent(), 0, ++startId)
        pass(10_000)
        assertEquals(listOf(0L to "Call in progress"), posted)
    }

    /** Half a second apart is apart. */
    @Test fun postsFarApartAreNotAPair() {
        controller.get().onStartCommand(Intent(), 0, ++startId)
        pass(500)
        controller.get().onStartCommand(Intent(), 0, ++startId)
        pass(10_000)
        assertEquals(listOf(0L, 500L), posted.map { it.first })
    }

    /** ...and closer is a pair, though each was a start's only post. */
    @Test fun twoStartsCloseTogetherAreAPair() {
        controller.get().onStartCommand(Intent(), 0, ++startId)
        pass(499)
        controller.get().onStartCommand(Intent(), 0, ++startId)
        pass(10_000)
        assertEquals(listOf(0L, 499L, 999L), posted.map { it.first })
    }

    @Test fun nothingIsPostedOnceTheServiceIsGone() {
        wake("CALL_STARTED")
        val service = controller
        service.destroy()
        controller = Robolectric.buildService(CallMonitorService::class.java).create()
        pass(10_000)
        assertEquals(2, posted.size)
    }

    /** Refused in between - the service out of the foreground, and the app not
     *  allowed back into it - it is a line in the log, not the end of the
     *  process: it runs on the main thread. */
    @Test fun refusedItIsOnlyLogged() {
        wake("CALL_STARTED")
        refused = true
        pass(500)
        assertEquals(2, posted.size)
        assertTrue(ShadowLog.getLogsForTag("JemRec").any { "monitor: not posted again: IllegalStateException" in it.msg })
        refused = false
        pass(10_000)
        assertEquals("and not tried for ever", 2, posted.size)
    }

    private companion object {
        val posted: MutableList<Pair<Long, String>> = java.util.concurrent.CopyOnWriteArrayList()

        /** Whether Android refuses the foreground. */
        @Volatile var refused = false

        /** Which post, counted from one, Android keeps waiting 600 ms. */
        @Volatile var slow = 0

        @Volatile var began = 0L
    }
}
