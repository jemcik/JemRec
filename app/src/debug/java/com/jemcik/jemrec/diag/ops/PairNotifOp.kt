package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

internal object PairNotifOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        com.jemcik.jemrec.setup.PairingNotification.show(appContext)
        kotlinx.coroutines.delay(1_500)
        report(appContext, "pairnotif", "pairing notification posted")
    }
}
