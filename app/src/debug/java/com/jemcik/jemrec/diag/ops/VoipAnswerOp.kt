package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.diag.VoipAnswerSpike

// The messenger-call answering spike (docs/VOIP-ANSWER-SPIKE.md):
//   --es op voipanswer --ei after 5 --ei minutes 15
internal object VoipAnswerOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val after = intent.getIntExtra("after", 5)
        val minutes = intent.getIntExtra("minutes", 5)
        VoipAnswerSpike.arm(appContext, after, minutes)
        report(appContext, "voipanswer", "armed: the next messenger call is answered after $after s, for $minutes min; see files/voip-answer.log")
    }
}
