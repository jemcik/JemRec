package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.jemcik.jemrec.diag.HIDE_SETTINGS_KEY
import com.jemcik.jemrec.diag.SpikeReceiver.Companion.PREFS

// Act like a Pixel on Android 17 that hides USB debugging
// and Developer options from apps: --ez on true|false.
// Kept across restarts; see DemoProvider.
internal object HideSettingsOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(HIDE_SETTINGS_KEY, on) }
        com.jemcik.jemrec.system.GlobalSettings.actAsHidden = on
        report(appContext, "hidesettings", "acting as if hidden: $on")
    }
}
