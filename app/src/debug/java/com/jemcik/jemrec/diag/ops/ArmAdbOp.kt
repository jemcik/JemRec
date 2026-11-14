package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import android.provider.Settings

// Can the app re-arm Wireless debugging by itself, given
// WRITE_SECURE_SETTINGS? If so, recovering after a reboot
// needs no user action at all, and the one manual step left
// in the whole design disappears.
internal object ArmAdbOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val text = runCatching {
            Settings.Global.putInt(
                appContext.contentResolver, "adb_wifi_enabled", 1,
            )
        }.fold(
            { accepted ->
                val now = Settings.Global.getInt(
                    appContext.contentResolver, "adb_wifi_enabled", -1,
                )
                "putInt accepted=$accepted; adb_wifi_enabled is now $now"
            },
            { "FAILED: ${it.javaClass.simpleName}: ${it.message}" },
        )
        report(appContext, "armadb", text)
    }
}
