package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.diag.TurnOnLater

// JemRec off or on, as the record control and the tile turn
// it, with When JemRec is off as the prefs set it - and on
// again by itself after a delay, for the emulator check of
// Turn all debugging off, whose off takes adb away with it
// (tools/emulator_checks.py, debugging_off):
//   --es op switch --ez on false [--ei back_after_s 30]
internal object SwitchOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        val backAfter = intent.getIntExtra("back_after_s", 0)
        if (!on && backAfter > 0) TurnOnLater.schedule(appContext, backAfter)
        report(
            appContext, "switch",
            "turning ${if (on) "on" else "off"}" +
                (if (!on && backAfter > 0) ", on again in $backAfter s" else "") +
                ", with ${com.jemcik.jemrec.recorder.DebugSwitchesOff.mode(appContext)}",
        )
        com.jemcik.jemrec.recorder.RecorderSwitch.set(appContext, on)
        com.jemcik.jemrec.recorder.RecorderSwitch.turn(appContext, on)
    }
}
