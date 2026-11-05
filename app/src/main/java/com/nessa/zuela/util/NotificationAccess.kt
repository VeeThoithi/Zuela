package com.nessa.zuela.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.nessa.zuela.notify.ZuelaNotificationListener

/** Helpers for the "Notification access" permission that message + media features need. */
object NotificationAccess {

    fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val target = ComponentName(context, ZuelaNotificationListener::class.java)
        return enabled.split(":").any {
            ComponentName.unflattenFromString(it) == target
        }
    }

    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Fall back to the app's own settings if the listener screen is unavailable.
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
