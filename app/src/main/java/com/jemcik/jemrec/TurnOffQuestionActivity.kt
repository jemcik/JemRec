package com.jemcik.jemrec

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jemcik.jemrec.capture.RecorderSwitch
import com.jemcik.jemrec.ui.TurnOffAwayFromWifiDialog
import com.jemcik.jemrec.ui.theme.JemRecTheme
import com.jemcik.jemrec.ui.theme.Looks

/**
 * The Quick Settings tile's way to ask TurnOffAwayFromWifiDialog: a tile has
 * no screen of its own, so this see-through one holds the same dialog the
 * header's button shows, over whatever was open. A question, not a place, so
 * it keeps no history and stays out of Recents.
 */
class TurnOffQuestionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JemRecTheme(Looks.choice(this)) {
                TurnOffAwayFromWifiDialog(
                    onTurnOff = {
                        RecorderSwitch.turnInBackground(applicationContext, false)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}
