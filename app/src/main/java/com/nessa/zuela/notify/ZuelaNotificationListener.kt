package com.nessa.zuela.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * Reads incoming message notifications (WhatsApp, Instagram, SMS, Messenger,
 * Telegram, Signal…) so Zuela can read them aloud and reply to them.
 *
 * This is the ONLY way an ordinary Android app can see or reply to messages in
 * other apps — the OS sandbox forbids reading another app's screen or database.
 * Replies are sent through the notification's own "reply" action (the same
 * mechanism smartwatches use), so no message is ever sent without a real reply
 * channel that the messaging app itself provided.
 *
 * Requires the user to grant "Notification access" in system settings.
 */
class ZuelaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            sbn.packageName in MESSAGING_PACKAGES
        if (!isMessage) return

        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (sender.isBlank() || text.isBlank()) return

        MessageInbox.add(
            MessageInbox.Message(
                app = appLabel(sbn.packageName),
                sender = sender,
                text = text,
                time = System.currentTimeMillis(),
            ),
            replyAction = findReplyAction(n),
        )
    }

    private fun findReplyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }

    private fun appLabel(pkg: String): String = when {
        pkg.contains("whatsapp") -> "WhatsApp"
        pkg.contains("instagram") -> "Instagram"
        pkg.contains("orca") || pkg.contains("messenger") -> "Messenger"
        pkg.contains("telegram") -> "Telegram"
        pkg.contains("signal") -> "Signal"
        pkg.contains("mms") || pkg.contains("messaging") || pkg.contains("sms") -> "Messages"
        else -> "a message"
    }

    private companion object {
        val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "com.facebook.orca",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging",
        )
    }
}

/**
 * In-memory store of the most recent messages the listener has captured, plus
 * the reply action for each sender. Static so both the UI and the background
 * wake service can reach it without binding to the service.
 */
object MessageInbox {

    data class Message(val app: String, val sender: String, val text: String, val time: Long)

    private const val MAX = 40
    private val messages = ArrayDeque<Message>()
    private val replyActions = HashMap<String, Notification.Action>()

    @Synchronized
    fun add(message: Message, replyAction: Notification.Action?) {
        messages.addLast(message)
        while (messages.size > MAX) messages.removeFirst()
        if (replyAction != null) {
            replyActions[message.sender.lowercase(Locale.ROOT)] = replyAction
        }
    }

    @Synchronized
    fun recent(limit: Int = 5): List<Message> = messages.toList().takeLast(limit)

    @Synchronized
    fun replyActionFor(name: String): Notification.Action? {
        val key = name.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        replyActions[key]?.let { return it }
        return replyActions.entries
            .firstOrNull { it.key.contains(key) || key.contains(it.key) }
            ?.value
    }

    @Synchronized
    fun clear() {
        messages.clear()
        replyActions.clear()
    }

    /** Sends [message] through a captured notification reply action. */
    fun sendReply(context: Context, action: Notification.Action, message: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val remoteInput = inputs.firstOrNull() ?: return false
        val actionIntent = action.actionIntent ?: return false
        val fillIn = Intent()
        val results = Bundle().apply { putCharSequence(remoteInput.resultKey, message) }
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        return try {
            actionIntent.send(context, 0, fillIn)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }
}
