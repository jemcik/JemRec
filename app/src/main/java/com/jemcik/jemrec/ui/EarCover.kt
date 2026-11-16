package com.jemcik.jemrec.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * JemRec's own screen blacked out and deaf to touch while a recording plays
 * at the earpiece held to the ear (AtTheEar.Wants.cover), for a phone that
 * does not darken the screen by the proximity sensor itself. Measured on the
 * Honor (MagicOS 11), 2026-11-15: in landscape its display controller logs
 * "Not response proximity sensor for rotation" and leaves the screen on,
 * and a cheek pressed the player's buttons. Where the phone does darken it
 * - the Honor upright - this is under a screen already off. The window's
 * brightness goes to its lowest meanwhile, and back as the phone comes down.
 *
 * The system's bars are hidden meanwhile, revealed by a swipe from their
 * edge (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE): a first swipe only shows
 * them. A cheek's swipe up at the ear had gone home, twice, on the Honor in
 * landscape (2026-11-15); a cover takes JemRec's touches, not the system's.
 */
@Composable
internal fun EarCover() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        }
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val behaviour = bars?.systemBarsBehavior
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window?.attributes = window.attributes.apply { screenBrightness = before }
            bars?.show(WindowInsetsCompat.Type.systemBars())
            if (behaviour != null) bars.systemBarsBehavior = behaviour
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(EAR_COVER_TAG)
            // Nothing under it is read out or reached while it is up.
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    )
}

internal const val EAR_COVER_TAG = "ear-cover"
