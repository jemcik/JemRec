package com.jemcik.jemrec.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.MissedCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Delete button's two steps, and Keep. Not exported: it is reached only
 * through this app's own immutable PendingIntents, so the recording it is told
 * to delete is one this app put in the notification.
 */
class AfterCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MissedCalls.ACTION_SEEN) {
            MissedCalls.markSeen(context)
            return
        }
        val call = AfterCall.Call.from(intent) ?: return
        when (intent.action) {
            AfterCall.ACTION_DELETE_ASK -> AfterCall.askDelete(context, call)
            // Back to how it was - or, if the everyday notification has been
            // switched off since, away.
            AfterCall.ACTION_DELETE_CANCEL -> if (!AfterCall.show(context, call)) AfterCall.cancel(context, call)
            AfterCall.ACTION_DELETE_CONFIRM -> {
                // Deleting is a ContentResolver call: off the main thread, and
                // kept alive past onReceive's return until it is done.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AfterCall.delete(context.applicationContext, call)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
