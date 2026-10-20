package com.jemcik.jemrec.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Asks for permissions - and, once Android has stopped asking, opens the
 * app's page in Android's settings instead, where the user can still say yes.
 *
 * Android stops showing the dialog after a permission has been refused twice:
 * the request comes straight back refused, and a button that only asks again
 * does nothing at all, however often it is tapped. Every Allow button in
 * JemRec was that button for anyone who had said no twice - in setup, a dead
 * end.
 *
 * Told apart by time: a refusal back faster than anyone could read a dialog
 * came from Android, not the user, and only then does settings open. Someone
 * who has just tapped "Don't allow" is not sent anywhere - their next tap,
 * answered instantly, is.
 */
class PermissionRequest internal constructor(private val start: (Array<String>) -> Unit) {
    fun launch(permissions: Array<String>) = start(permissions)
}

/** Back faster than this, and no dialog was shown. */
private const val SILENT_MS = 500L

@Composable
fun rememberPermissionRequest(onResult: (Map<String, Boolean>) -> Unit): PermissionRequest {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val askedAt = remember { LongArray(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        onResult(result)
        val refused = result.filterValues { granted -> !granted }.keys
        if (refused.isEmpty() || activity == null) return@rememberLauncherForActivityResult
        val blocked = refused.none { activity.shouldShowRequestPermissionRationale(it) }
        if (blocked && SystemClock.elapsedRealtime() - askedAt[0] < SILENT_MS) {
            Log.i("JemRec", "permissions: Android no longer asks for ${refused.joinToString()}; opening settings")
            openAppSettings(context)
        }
    }
    return remember(launcher) {
        PermissionRequest { permissions ->
            askedAt[0] = SystemClock.elapsedRealtime()
            launcher.launch(permissions)
        }
    }
}

/** JemRec's own page in Android's settings: Permissions is one tap away. */
internal fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Log.w("JemRec", "permissions: could not open the app's settings", it) }
}
