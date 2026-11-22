package com.jemcik.jemrec.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.recorder.RecorderLifecycle

/**
 * Android's USB_STATE: a USB mode changed with a cable in, a cable went in or
 * out, a gadget came up. Hands over to UsbStateWatch, the cable's watch run
 * as a job.
 *
 * The broadcast is a system one (`@SystemApi`, so the constant is hidden and
 * the action is written out; protected, so only the system sends it), and
 * reaches an app that is not running: UsbDeviceManager sends it with
 * FLAG_RECEIVER_INCLUDE_BACKGROUND (AOSP, Android 12 to main), which lets an
 * implicit broadcast past the background check to manifest receivers.
 * Measured with JemRec's process killed: delivered on the Android 12, 13, 16
 * and 17 emulators, in the restricted standby bucket and under Battery
 * Restricted too, and on the Honor, Android starting JemRec "for broadcast"
 * (2026-11-22; its App launch setting not read). Not to an app the user
 * force-stopped, as for every broadcast. USB_STATE is not on Android's list
 * of implicit broadcasts a manifest may still receive; the flag is what lets
 * it through (AOSP's own MtpReceiver receives it from its manifest too).
 *
 * Only setup is asked here, so a phone JemRec never set up queues nothing;
 * whether JemRec stands down is the run's to ask, under the recorder's lock,
 * as the USB debugging watch's run does. The queueing reads Android (the
 * network, Start without Wi-Fi's port) off the main thread, so the broadcast
 * is held open for it (goAsync) - four seconds at most from here, all of it
 * (UsbStateWatch.BUDGET_MS), well inside the ten Android gives it.
 */
class UsbStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_STATE) return
        if (!RecorderLifecycle.isSetUp(context)) return
        val pending = goAsync()
        UsbStateWatch.queue(context) { pending.finish() }
    }

    companion object {
        /** UsbManager.ACTION_USB_STATE, hidden (`@SystemApi`). */
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    }
}
