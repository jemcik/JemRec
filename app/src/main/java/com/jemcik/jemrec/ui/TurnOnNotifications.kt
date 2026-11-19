package com.jemcik.jemrec.ui

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.jemcik.jemrec.calls.NotificationHealth

/**
 * Turning JemRec's notifications back on, one way wherever it is offered -
 * the header's "Tap to turn on notifications" and home's notice's Turn on:
 * Android's own question first, where the permission is what is missing
 * (Android 13 and later) and Android still asks it (rememberPermissionRequest
 * opens the settings once it stops); else Android's settings for what is
 * off. Until the review of 2026-11-18 the header went straight to the
 * settings while the notice asked first: one thing, two rules.
 */
internal val LocalTurnOnNotifications = staticCompositionLocalOf<() -> Unit> {
    // No default: a header drawn without AppScaffold's would offer a tap
    // that does nothing. Compose's guide asks of a CompositionLocal "a good
    // default value" - there is none here, so a missing one fails at once
    // (review, 2026-11-18).
    error("LocalTurnOnNotifications is provided by AppScaffold")
}

@Composable
internal fun rememberTurnOnNotifications(state: UiState, vm: MainViewModel): () -> Unit {
    val context = LocalContext.current
    // Once Android no longer asks, the app's notification settings - where
    // the switch is - not App info, a level above them (review, 2026-11-18).
    val ask = rememberPermissionRequest(
        settings = { NotificationHealth.openSettings(it, NotificationHealth.Off.ALL) },
    ) { vm.refresh() }
    val off by rememberUpdatedState(state.notificationsOff ?: NotificationHealth.Off.ALL)
    return remember(ask) {
        {
            if (off == NotificationHealth.Off.ALL && NotificationHealth.permissionMissing(context)) {
                ask.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                vm.openNotificationSettings(off)
            }
        }
    }
}
