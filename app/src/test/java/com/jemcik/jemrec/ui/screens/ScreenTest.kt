package com.jemcik.jemrec.ui.screens

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jemcik.jemrec.ui.LocalNow
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.theme.JemRecTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.Locale
import java.util.TimeZone

/**
 * What the screen tests share: the real composables drawn under Robolectric,
 * at a fixed time, in a fixed language and time zone, so the same state draws
 * the same pixels on any machine on any day.
 *
 * Each test class runs with Robolectric's own Conscrypt off (@ConscryptMode):
 * the app ships Android's Conscrypt, whose classes would shadow Robolectric's
 * desktop copy and fail to find their native library - and nothing drawn here
 * needs TLS.
 *
 * The screens are given a UiState of the test's own and a real MainViewModel
 * that only the taps reach - nothing here starts the recorder, reads the call
 * log or touches the disk. AppScaffold is what MainScreen draws, without
 * MainScreen's reaching out to the device.
 */
abstract class ScreenTest {

    // An empty activity to draw into. Declaring one in the app's manifest
    // would put it in the debug APK - the build that runs on phones - so it
    // is registered with Robolectric's package manager instead.
    private val emptyActivity = object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(emptyActivity).around(compose)

    private val locale = Locale.getDefault()
    private val zone = TimeZone.getDefault()

    @Before fun fixLanguageAndZone() {
        Locale.setDefault(Locale.UK)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLanguageAndZone() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    /** A view model over [state] that only the taps reach. */
    fun viewModel(state: UiState = UiState()) = MainViewModel(RuntimeEnvironment.getApplication(), state)

    /** Draws [content] as the app would: its theme, its background, the fixed
     *  clock, and the text at [fontScale] times the phone's size. */
    fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalNow provides { Fixtures.NOW },
                LocalDensity provides Density(density.density, fontScale),
            ) {
                JemRecTheme {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Compares the screen with app/src/test/screenshots/[name].png. */
    fun snap(name: String) = compose.onRoot().captureRoboImage(path(name), roborazziOptions = OPTIONS)

    /** The same, for a dialog - which is a window of its own, over the screen. */
    @OptIn(ExperimentalRoborazziApi::class)
    fun snapWithDialog(name: String) = captureScreenRoboImage(path(name), roborazziOptions = OPTIONS)

    private fun path(name: String) = "src/test/screenshots/$name.png"

    companion object {
        val OPTIONS = RoborazziOptions()

        /** The phone the screenshots are taken on: 360 dp wide, 1.5x. */
        const val PHONE = "w360dp-h800dp-hdpi"
    }
}
