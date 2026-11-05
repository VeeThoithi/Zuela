package com.nessa.zuela.brain

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.nessa.zuela.data.ZuelaStore
import com.nessa.zuela.media.MediaControls
import com.nessa.zuela.notify.MessageInbox
import com.nessa.zuela.util.NotificationAccess
import com.nessa.zuela.weather.Weather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rule-based commands handled instantly on the phone. Returns the spoken reply,
 * or null if the utterance should go to the AI instead.
 *
 * Supports simple follow-up questions: a command that needs more information
 * (e.g. "remind me tomorrow") sets [pending], and the next utterance is fed to
 * that resolver ("what time?" → "6 pm").
 *
 * `suspend` because weather does a network lookup; everything else is instant.
 */
class CommandProcessor(
    private val context: Context,
    private val store: ZuelaStore,
) {

    private val media = MediaControls(context)

    /** When set, the next utterance answers a follow-up question. */
    private var pending: (suspend (String) -> String)? = null

    suspend fun handle(raw: String): String? {
        val text = raw.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!')
        val cleaned = text
            .replace(Regex("^(hey|hi|ok|okay|yo)\\s+\\w+\\b"), "")
            .replace(
                Regex(
                    "^(please|can you|could you|would you|will you|i want to|" +
                        "i want you to|i'd like to|i would like to|kindly|go ahead and)\\s+"
                ),
                ""
            )
            .trim()

        // ----- pending follow-up answer -----
        pending?.let { resolver ->
            pending = null
            if (CANCEL_REGEX.containsMatchIn(cleaned)) return "Okay, never mind."
            return resolver(cleaned)
        }

        // ----- instant info -----
        if (TIME_REGEX.containsMatchIn(cleaned)) return currentTime()
        if (DATE_REGEX.containsMatchIn(cleaned)) return currentDate()

        // ----- memory -----
        if (CLEAR_MEMORY_REGEX.containsMatchIn(cleaned)) return clearMemory()
        if (WHAT_KNOW_REGEX.containsMatchIn(cleaned)) return describeMemory()
        REMEMBER_REGEX.find(cleaned)?.let { return rememberFact(it.groupValues[2]) }
        FORGET_REGEX.find(cleaned)?.let { return forgetFact(it.groupValues[1]) }

        // ----- calls & media -----
        if (END_CALL_REGEX.containsMatchIn(cleaned)) return endCall()
        if (MEDIA_PAUSE.matches(cleaned)) return mediaAction("Paused.") { media.pause() }
        if (MEDIA_STOP.matches(cleaned)) return mediaAction("Stopped.") { media.stop() }
        if (MEDIA_NEXT.matches(cleaned)) return mediaAction("Skipping ahead.") { media.next() }
        if (MEDIA_PREV.matches(cleaned)) return mediaAction("Going back.") { media.previous() }
        if (MEDIA_RESUME.matches(cleaned)) return mediaAction("Playing.") { media.play() }

        // ----- messages -----
        if (READ_MSGS_REGEX.containsMatchIn(cleaned)) return readMessages()
        REPLY_REGEX.find(cleaned)?.let { return replyToMessage(it.groupValues[1], it.groupValues[2]) }
        SEND_MSG_REGEX.find(cleaned)?.let {
            return sendMessage(
                it.groupValues[1], it.groupValues[2],
                wantsWhatsApp = cleaned.contains("whatsapp")
            )
        }

        // ----- productivity: lists & notes -----
        ADD_LIST_REGEX.find(cleaned)?.let {
            return addToList(listKeyFor(it.groupValues[2]), it.groupValues[1])
        }
        REMOVE_LIST_REGEX.find(cleaned)?.let {
            return removeFromList(listKeyFor(it.groupValues[2]), it.groupValues[1])
        }
        READ_LIST_REGEX.find(cleaned)?.let { return readList(listKeyFor(it.groupValues[1])) }
        CLEAR_LIST_REGEX.find(cleaned)?.let { return clearList(listKeyFor(it.groupValues[1])) }
        NOTE_ADD_REGEX.find(cleaned)?.let {
            val note = it.groupValues[1].ifBlank { it.groupValues[2] }
            return addNote(note)
        }
        if (NOTE_READ_REGEX.containsMatchIn(cleaned)) return readList("notes", "notes")
        if (NOTE_CLEAR_REGEX.containsMatchIn(cleaned)) return clearList("notes", "notes")

        // ----- stopwatch & pomodoro -----
        if (POMODORO_REGEX.containsMatchIn(cleaned)) return startPomodoro()
        if (STOPWATCH_START_REGEX.containsMatchIn(cleaned)) return startStopwatch()
        if (STOPWATCH_STOP_REGEX.containsMatchIn(cleaned)) return reportStopwatch(reset = cleaned.contains("stop"))

        // ----- reminders / alarms / timers (with follow-ups) -----
        if (TIMER_REGEX.containsMatchIn(cleaned)) return setTimer(cleaned)
        if (cleaned.contains("remind me")) return setReminder(cleaned)
        if (ALARM_REGEX.containsMatchIn(cleaned)) return setAlarm(cleaned)

        // ----- weather / play / search -----
        if (WEATHER_REGEX.containsMatchIn(cleaned)) {
            val city = WEATHER_CITY_REGEX.find(cleaned)?.groupValues?.get(1)?.trim()
            return Weather.spoken(context, city)
        }
        SPOTIFY_REGEX.find(cleaned)?.let { return playOnSpotify(it.groupValues[1]) }
        WEB_SEARCH_REGEX.find(cleaned)?.let { return webSearch(it.groupValues[1]) }

        // ----- open app / call contact -----
        OPEN_REGEX.find(cleaned)?.let { return openApp(it.groupValues[2]) }
        CALL_REGEX.find(cleaned)?.let { return callContact(it.groupValues[2]) }

        return null
    }

    // ---------- time & date ----------

    private fun currentTime(): String {
        val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        return "It's $now."
    }

    private fun currentDate(): String {
        val today = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        return "Today is $today."
    }

    // ---------- long-term memory ----------

    private fun rememberFact(fact: String): String {
        val f = fact.trim().removePrefix("to ").trim()
        if (f.isBlank()) return "What would you like me to remember?"
        store.remember(f)
        return "Got it — I'll remember that."
    }

    private fun forgetFact(query: String): String {
        val removed = store.forget(query.trim())
        return if (removed > 0) "Okay, I've forgotten that."
        else "I didn't have anything remembered about that."
    }

    private fun describeMemory(): String {
        val facts = store.memoryFacts()
        return if (facts.isEmpty())
            "You haven't told me anything to remember yet. Say \"remember that…\" and I'll keep it."
        else "Here's what I know about you: " + facts.joinToString("; ") + "."
    }

    private fun clearMemory(): String {
        store.clearMemory()
        return "I've cleared everything I remembered about you."
    }

    // ---------- productivity: lists & notes ----------

    private fun listKeyFor(spoken: String): String = when {
        spoken.startsWith("shop") || spoken.startsWith("groc") -> "shopping"
        else -> "todo"
    }

    private fun listLabel(key: String): String = when (key) {
        "shopping" -> "shopping list"
        "todo" -> "to-do list"
        "notes" -> "notes"
        else -> "$key list"
    }

    private fun addToList(key: String, item: String): String {
        val i = item.trim()
        if (i.isBlank()) return "What should I add?"
        store.addToList(key, i)
        return "Added $i to your ${listLabel(key)}."
    }

    private fun addNote(note: String): String {
        val n = note.trim()
        if (n.isBlank()) return "What should the note say?"
        store.addToList("notes", n)
        return "Noted."
    }

    private fun removeFromList(key: String, query: String): String {
        val removed = store.removeFromList(key, query.trim())
        return if (removed > 0) "Removed it from your ${listLabel(key)}."
        else "I couldn't find that on your ${listLabel(key)}."
    }

    private fun readList(key: String, labelOverride: String? = null): String {
        val label = labelOverride ?: listLabel(key)
        val items = store.list(key)
        return if (items.isEmpty()) "Your $label is empty."
        else "Your $label: " + items.joinToString(", ") + "."
    }

    private fun clearList(key: String, labelOverride: String? = null): String {
        store.clearList(key)
        return "Cleared your ${labelOverride ?: listLabel(key)}."
    }

    // ---------- stopwatch & pomodoro ----------

    private fun startStopwatch(): String {
        store.setStopwatchStart(System.currentTimeMillis())
        return "Stopwatch started."
    }

    private fun reportStopwatch(reset: Boolean): String {
        val start = store.stopwatchStart()
        if (start == 0L) return "The stopwatch isn't running."
        val elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
        if (reset) store.setStopwatchStart(0L)
        val word = if (reset) "Stopped at" else "The stopwatch is at"
        return "$word ${describeDuration(elapsed.coerceAtLeast(1))}."
    }

    private fun startPomodoro(): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, 25 * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Pomodoro — focus time")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Starting a 25-minute Pomodoro. I'll chime when it's time for a break."
        } catch (e: ActivityNotFoundException) {
            "I couldn't find a clock app to start the Pomodoro."
        }
    }

    // ---------- media ----------

    private inline fun mediaAction(success: String, action: () -> Boolean): String {
        if (!NotificationAccess.isGranted(context)) return needAccess()
        return if (action()) success else "There's nothing playing right now."
    }

    // ---------- messages ----------

    private fun readMessages(): String {
        if (!NotificationAccess.isGranted(context)) return needAccess()
        val msgs = MessageInbox.recent(3)
        if (msgs.isEmpty()) return "You have no new messages that I can see."
        return msgs.joinToString(" ") { "${it.sender} on ${it.app} says: ${it.text}." }
    }

    private fun replyToMessage(name: String, body: String): String {
        if (!NotificationAccess.isGranted(context)) return needAccess()
        val action = MessageInbox.replyActionFor(name)
            ?: return "I don't have a recent message from $name to reply to. Once they message you, I can reply."
        return if (MessageInbox.sendReply(context, action, body.trim())) "Replied to $name."
        else "I couldn't send that reply."
    }

    private fun sendMessage(rawName: String, body: String, wantsWhatsApp: Boolean): String {
        val name = rawName.trim().removePrefix("to ").removeSuffix(" on whatsapp").trim()
        val contact = lookupContact(name) ?: return "I couldn't find $name in your contacts."
        val (displayName, number) = contact
        return if (wantsWhatsApp) openWhatsApp(displayName, number, body.trim())
        else openSms(displayName, number, body.trim())
    }

    private fun openSms(name: String, number: String, body: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                .putExtra("sms_body", body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "I've opened a text to $name saying \"$body\". Tap send to send it."
        } catch (e: ActivityNotFoundException) {
            "I couldn't open your messaging app."
        }
    }

    private fun openWhatsApp(name: String, number: String, body: String): String {
        val digits = number.filter { it.isDigit() }
        val url = "https://wa.me/$digits?text=${Uri.encode(body)}"
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent(view).setPackage("com.whatsapp"))
            "I've opened WhatsApp to $name with your message. Tap send."
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(view)
                "I've opened your message to $name. Tap send."
            } catch (e2: ActivityNotFoundException) {
                "I couldn't open WhatsApp — is it installed?"
            }
        }
    }

    // ---------- calls ----------

    private fun endCall(): String {
        if (Build.VERSION.SDK_INT < 28) return "Ending calls needs Android 9 or newer."
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            return "I need phone-call permission to end calls. Please grant it in the app and try again."
        }
        val tm = context.getSystemService(TelecomManager::class.java)
            ?: return "I can't reach the phone service."
        return try {
            if (tm.endCall()) "Call ended." else "There's no active call to end."
        } catch (e: SecurityException) {
            "I wasn't allowed to end the call."
        }
    }

    private fun callContact(rawName: String): String {
        val name = rawName.replace(Regex("^(my)\\s+"), "").trim()
        if (name.isBlank()) return "Who should I call?"
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return "I need phone permission to make calls. Please grant it in the app."
        }
        val contact = lookupContact(name) ?: return "I couldn't find $name in your contacts."
        val (displayName, number) = contact
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Calling $displayName."
        } catch (e: SecurityException) {
            "I wasn't allowed to place the call. Check the phone permission."
        }
    }

    private fun lookupContact(name: String): Pair<String, String>? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        var found: Pair<String, String>? = null
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                found = cursor.getString(0) to cursor.getString(1)
            }
        }
        return found
    }

    // ---------- apps ----------

    private fun openApp(rawName: String): String {
        val spoken = rawName
            .replace(Regex("^(my|the)\\s+"), "")
            .replace(Regex("\\s+app$"), "")
            .trim()
        if (spoken.isBlank()) return "Which app should I open?"
        val target = normalise(spoken)
        if (target.isBlank()) return "Which app should I open?"

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)

        val exact = apps.firstOrNull { normalise(it.loadLabel(pm).toString()) == target }
        val partial = apps.firstOrNull {
            val label = normalise(it.loadLabel(pm).toString())
            label.contains(target) || target.contains(label)
        }
        val match = exact ?: partial ?: return "I couldn't find an app called $spoken."

        val label = match.loadLabel(pm).toString()
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I found $label but couldn't open it."
        return try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            "Opening $label."
        } catch (e: Exception) {
            "I couldn't open $label."
        }
    }

    private fun normalise(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    // ---------- play / search ----------

    private fun playOnSpotify(rawQuery: String): String {
        val query = rawQuery.replace(Regex("\\s+on spotify$"), "").trim()
        if (query.isBlank()) return "What should I play?"
        val uri = Uri.parse("spotify:search:${Uri.encode(query)}")
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent(view).setPackage("com.spotify.music"))
            "Here's \"$query\" on Spotify — tap the top result to play."
        } catch (e: ActivityNotFoundException) {
            try {
                val web = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(web)
                "Opening Spotify search for \"$query\"."
            } catch (e2: ActivityNotFoundException) {
                "I couldn't open Spotify — is it installed?"
            }
        }
    }

    private fun webSearch(rawQuery: String): String {
        val query = rawQuery.trim()
        if (query.isBlank()) return "What should I search for?"
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Here are the results for $query."
        } catch (e: ActivityNotFoundException) {
            try {
                val web = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(web)
                "Searching the web for $query."
            } catch (e2: ActivityNotFoundException) {
                "I couldn't open a browser to search."
            }
        }
    }

    // ---------- alarms, timers, reminders (with follow-ups) ----------

    private fun setTimer(text: String): String {
        val seconds = parseDuration(text)
        if (seconds <= 0) {
            pending = { follow -> completeTimer(parseDuration(follow)) }
            return "How long should the timer be?"
        }
        return completeTimer(seconds)
    }

    private fun completeTimer(seconds: Int): String {
        if (seconds <= 0) return "I didn't catch a length. Try: 5 minutes."
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Zuela timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Timer set for ${describeDuration(seconds)}."
        } catch (e: ActivityNotFoundException) {
            "I couldn't find a clock app to set the timer."
        }
    }

    private fun setAlarm(text: String): String {
        val time = parseClockTime(text)
        if (time == null) {
            pending = { follow -> completeAlarm(parseClockTime(follow), null) }
            return "What time should the alarm be?"
        }
        return completeAlarm(time, null)
    }

    private fun setReminder(text: String): String {
        val what = Regex("remind me to (.+?)(?: (?:at|by|around|tomorrow|tonight|later).*)?$")
            .find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val time = parseClockTime(text)
        if (time == null) {
            pending = { follow -> completeAlarm(parseClockTime(follow), what ?: "reminder") }
            return if (what != null) "Sure — what time should I remind you to $what?"
            else "What time should I remind you?"
        }
        return completeAlarm(time, what ?: "reminder")
    }

    /** Shared alarm/reminder scheduler. [label] non-null → reminder wording. */
    private fun completeAlarm(time: Pair<Int, Int>?, label: String?): String {
        if (time == null) {
            // Re-ask once more, keeping the same intent.
            pending = { follow -> completeAlarm(parseClockTime(follow), label) }
            return "Sorry, what time? For example, 6 pm or 6:30 am."
        }
        val (hour, minute) = time
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (label != null && label != "reminder") {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                } else if (label != null) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Zuela reminder")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            when {
                label == null -> "Alarm set for ${formatClockTime(hour, minute)}."
                label == "reminder" -> "Okay, I'll remind you at ${formatClockTime(hour, minute)}."
                else -> "Okay, I'll remind you to $label at ${formatClockTime(hour, minute)}."
            }
        } catch (e: ActivityNotFoundException) {
            "I couldn't find a clock app for that."
        }
    }

    private fun parseDuration(text: String): Int {
        var seconds = 0
        Regex("(\\d+)\\s*hour").find(text)?.let { seconds += it.groupValues[1].toInt() * 3600 }
        Regex("(\\d+)\\s*min").find(text)?.let { seconds += it.groupValues[1].toInt() * 60 }
        Regex("(\\d+)\\s*sec").find(text)?.let { seconds += it.groupValues[1].toInt() }
        // Bare number in a follow-up ("5") assumed to be minutes.
        if (seconds == 0) {
            Regex("^(\\d+)$").find(text.trim())?.let { seconds = it.groupValues[1].toInt() * 60 }
        }
        return seconds
    }

    private fun parseClockTime(text: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?:[:. ](\\d{2}))?\\s*([ap])\\.?\\s?m\\.?", RegexOption.IGNORE_CASE)
            .find(text)
        if (m != null) {
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifBlank { "0" }.toInt()
            val isPm = m.groupValues[3].lowercase(Locale.ROOT) == "p"
            if (isPm && hour < 12) hour += 12
            if (!isPm && hour == 12) hour = 0
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour to minute
        }
        val m24 = Regex("(?:at|for) (\\d{1,2})[:. ](\\d{2})").find(text)
        if (m24 != null) {
            val hour = m24.groupValues[1].toInt()
            val minute = m24.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) return hour to minute
        }
        return null
    }

    private fun formatClockTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val h12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.ROOT, "%d:%02d %s", h12, minute, amPm)
    }

    private fun describeDuration(seconds: Int): String {
        val parts = mutableListOf<String>()
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        if (h > 0) parts += "$h hour${if (h > 1) "s" else ""}"
        if (m > 0) parts += "$m minute${if (m > 1) "s" else ""}"
        if (s > 0) parts += "$s second${if (s > 1) "s" else ""}"
        return if (parts.isEmpty()) "0 seconds" else parts.joinToString(" and ")
    }

    // ---------- helpers ----------

    private fun needAccess(): String {
        NotificationAccess.openSettings(context)
        return "I need notification access to control music and read or reply to messages. " +
            "I've opened the setting — please switch Zuela on, then ask me again."
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val TIME_REGEX = Regex("what('s| is)? the time|what time is it|^time$|tell me the time")
        val DATE_REGEX = Regex("what('s| is)? (the date|today's date)|what day is it|^date$")
        val TIMER_REGEX = Regex("(set|start)( a)? timer")
        val ALARM_REGEX = Regex("(set|create)( an?)? alarm|wake me( up)?")
        val OPEN_REGEX = Regex("\\b(open|launch|go to|start up|fire up)\\s+(.+)$")
        val CALL_REGEX = Regex("\\b(call|phone|dial|ring)\\s+(.+)$")

        val REMEMBER_REGEX = Regex("^(remember|keep in mind)\\s+(?:that\\s+)?(.+)$")
        val FORGET_REGEX = Regex("^forget\\s+(?:that\\s+|about\\s+)?(.+)$")
        val CLEAR_MEMORY_REGEX =
            Regex("(clear|wipe|erase|reset)( your| my)? memory|forget everything")
        val WHAT_KNOW_REGEX = Regex("what do you (know|remember) about me")

        val END_CALL_REGEX =
            Regex("\\b(end|hang up|drop|cut|disconnect|reject|decline)( the| this)? call\\b|^hang ?up$")
        val MEDIA_PAUSE = Regex("(pause|hold on)( the| my)?( music| song| playback| video| track)?")
        val MEDIA_RESUME = Regex("(play|resume|continue|unpause)( the| my)?( music| song| playback| video| track)?")
        val MEDIA_NEXT = Regex("(next|skip)( song| track| one| this| this song| forward| ahead)?")
        val MEDIA_PREV = Regex("(previous|last|go back|back)( song| track| a song| one)?")
        val MEDIA_STOP = Regex("stop( the| my)?( music| song| playback| video)?")

        val READ_MSGS_REGEX = Regex(
            "read( me| out| aloud)?( my| the)?( new| recent| latest)? messages?|" +
                "any( new)? messages?|check my messages?|do i have( any)? messages?"
        )
        val REPLY_REGEX = Regex("^reply to (.+?) (?:that |saying |with |and say )(.+)$")
        val SEND_MSG_REGEX = Regex(
            "^(?:text|message|whatsapp|send(?: a)?(?: text| message| whatsapp)?(?: to)?)\\s+" +
                "(.+?)\\s+(?:that |saying |with |and say |: )(.+)$"
        )

        // Lists: group1 = item (add/remove), group2 = list type; READ/CLEAR group1 = list type.
        val ADD_LIST_REGEX =
            Regex("^add (.+?) to (?:my |the )?(shopping|grocery|to-?do|task)s? list$")
        val REMOVE_LIST_REGEX =
            Regex("^(?:remove|delete|take off|cross off) (.+?) (?:from|off)(?: my| the)? (shopping|grocery|to-?do|task)s? list$")
        val READ_LIST_REGEX =
            Regex("(?:what'?s on|read|show|check|what is on)(?: my| the)? (shopping|grocery|to-?do|task)s? list")
        val CLEAR_LIST_REGEX =
            Regex("(?:clear|empty|wipe)(?: my| the)? (shopping|grocery|to-?do|task)s? list")
        val NOTE_ADD_REGEX =
            Regex("^(?:make|take|write|add|jot down|new) (?:a |an )?note(?:s)?(?: that| saying| to)?[: ]+(.+)$|^note(?: that| to)? (.+)$")
        val NOTE_READ_REGEX = Regex("(?:read|show|check|what are)(?: my| the)? notes")
        val NOTE_CLEAR_REGEX = Regex("(?:clear|empty|wipe)(?: my| the)? notes")

        val POMODORO_REGEX = Regex("(?:start|begin)(?: a| the)? pomodoro|pomodoro timer")
        val STOPWATCH_START_REGEX = Regex("(?:start|begin)(?: a| the)? stopwatch")
        val STOPWATCH_STOP_REGEX =
            Regex("stop(?: the)? stopwatch|stopwatch time|how long (?:on|has|is) the stopwatch|check(?: the)? stopwatch")

        val SPOTIFY_REGEX = Regex("^(?:play|put on)\\s+(.+)$")
        val WEB_SEARCH_REGEX = Regex("^(?:search|google|look up|search for)(?: for)?\\s+(.+)$")
        val WEATHER_REGEX = Regex("weather|forecast|how (hot|cold) is it|temperature outside")
        val WEATHER_CITY_REGEX = Regex("weather (?:in|for|at|around) (.+)$")

        val CANCEL_REGEX = Regex("^(cancel|never ?mind|forget it|stop|no thanks)$")
    }
}
