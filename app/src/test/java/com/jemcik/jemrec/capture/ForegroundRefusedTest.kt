package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.Service
import android.os.Build
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/**
 * The call service told to stop by a plain start, as the app tells it
 * (CallMonitorService.stop), with Android refusing it the foreground: it does
 * so for about a minute after an app leaves the screen, from Android 12. The
 * refusal is an exception on the main thread; the emulator check off_just_left
 * shows it on the real thing.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], shadows = [ForegroundRefusedTest.RefusingTheForeground::class])
class ForegroundRefusedTest {

    @Implements(Service::class)
    class RefusingTheForeground : ShadowService() {
        @Implementation(minSdk = Build.VERSION_CODES.Q)
        override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            throw IllegalStateException("Service.startForeground() not allowed")
        }
    }

    private val context = RuntimeEnvironment.getApplication()

    @Test fun aStopRefusedTheForegroundStopsWithoutTakingTheProcess() {
        val controller = Robolectric.buildService(CallMonitorService::class.java).create()
        CallMonitorService.stop(context)
        controller.withIntent(shadowOf(context).nextStartedService).startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
}
