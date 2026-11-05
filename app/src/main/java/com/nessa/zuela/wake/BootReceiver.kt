package com.nessa.zuela.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nessa.zuela.data.ZuelaStore

/**
 * Restarts the "Hey Zuela" background listener after the phone reboots, but only
 * if the user had it switched on. Keeps the always-listening experience working
 * without the user having to reopen the app after every restart.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ZuelaStore(context).wakeEnabled()) {
            try {
                WakeWordService.start(context)
            } catch (e: Exception) {
                // Some OEMs restrict starting a mic service straight from boot; ignore.
            }
        }
    }
}
