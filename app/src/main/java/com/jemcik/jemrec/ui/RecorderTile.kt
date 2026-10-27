package com.jemcik.jemrec.ui

import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import android.content.Context
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.TurnOffQuestionActivity
import com.jemcik.jemrec.capture.DebugSwitchesOff
import com.jemcik.jemrec.capture.switchesOff
import com.jemcik.jemrec.capture.takesTheRecorder
import com.jemcik.jemrec.capture.RecorderSwitch

/**
 * The Quick Settings tile: JemRec's on/off switch, without opening JemRec.
 *
 * The same switch as the one in the app's header, through the same path
 * (RecorderSwitch.turn), so the two can never disagree about what "off"
 * means. Each refreshes the other: the tile reads the switch whenever the
 * panel opens, and RecorderSwitch.set asks the tile to look again.
 *
 * Behind the lock screen. Turning a call recorder off, or on, is not
 * something whoever picks up a locked phone should be able to do, so a tap
 * on a locked phone asks for the unlock first.
 *
 * Before setup has finished there is nothing to switch, so the tile opens the
 * app, where setup is.
 */
class RecorderTile : TileService() {

    companion object {
        private const val KEY_ADDED = "qs_tile_added"

        /**
         * Whether the tile is in the Quick Settings panel, as far as Android
         * has said - it has no way to ask. Told by the tile's own callbacks
         * (added, removed, and listening, which only a tile in the panel
         * does, so one added before this was kept is found the first time the
         * panel opens), and by the answer to a request to add it.
         */
        fun isAdded(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ADDED, false)

        fun setAdded(context: Context, added: Boolean) {
            Prefs.of(context).edit { putBoolean(KEY_ADDED, added) }
        }
    }

    override fun onTileAdded() = setAdded(this, true)

    override fun onTileRemoved() = setAdded(this, false)

    override fun onStartListening() {
        setAdded(this, true)
        show(RecorderSwitch.isOn(this))
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        if (!Setup.isComplete(this)) {
            openApp()
            return
        }
        val on = !RecorderSwitch.isOn(this)
        // Turning off with all debugging, away from Wi-Fi: the same question
        // the header's button asks, before anything happens.
        if (!on && DebugSwitchesOff.asksBeforeOff(this)) {
            openQuestion()
            return
        }
        // Shown at once; the recorder catches up in the background.
        show(on, debuggingOff = !on && DebugSwitchesOff.mode(this).switchesOff().takesTheRecorder())
        RecorderSwitch.turnInBackground(this, on)
    }

    private fun show(on: Boolean, debuggingOff: Boolean = DebugSwitchesOff.recorderGone(this)) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "JemRec"
        tile.subtitle = when {
            on -> "Recording calls"
            debuggingOff -> "Off, debugging off"
            else -> "Off"
        }
        tile.contentDescription = when {
            on -> "JemRec, recording calls"
            debuggingOff -> "JemRec, off, with debugging off"
            else -> "JemRec, off"
        }
        tile.updateTile()
    }

    // As openApp: the PendingIntent form from Android 14, the Intent form below.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openQuestion() {
        val intent = Intent(this, TurnOffQuestionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    // The Intent form is deprecated, and throws for apps targeting Android 14+
    // - but it runs here only below 14, where the PendingIntent form does
    // not exist yet. Lint sees the call, not the version check around it.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
