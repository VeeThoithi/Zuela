package com.nessa.zuela.data

import android.content.Context
import com.nessa.zuela.brain.Personality
import org.json.JSONArray

/**
 * Single source of truth for everything Zuela persists: the API key, the chosen
 * wake word, personality, conversation-mode toggle, and long-term memory facts.
 * Backed by one SharedPreferences file, so the UI (ZuelaViewModel) and the
 * background WakeWordService always see the same state.
 */
class ZuelaStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("zuela_prefs", Context.MODE_PRIVATE)

    // ---------- API key ----------

    fun apiKey(): String? = prefs.getString(KEY_API, null)?.trim()?.ifBlank { null }

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API, value.trim()).apply()
    }

    // ---------- wake word ----------

    /** Lower-case token, e.g. "zuela", "nova", "nessa", "assistant". */
    fun wakeWord(): String = prefs.getString(KEY_WAKE, DEFAULT_WAKE) ?: DEFAULT_WAKE

    fun setWakeWord(token: String) {
        prefs.edit().putString(KEY_WAKE, token.lowercase()).apply()
    }

    /** Plausible speech-to-text spellings for the current wake word. */
    fun wakeVariants(): List<String> =
        WAKE_VARIANTS[wakeWord()] ?: listOf(wakeWord())

    // ---------- personality ----------

    fun personality(): Personality = Personality.from(prefs.getString(KEY_PERSONALITY, null))

    fun setPersonality(p: Personality) {
        prefs.edit().putString(KEY_PERSONALITY, p.name).apply()
    }

    // ---------- conversation mode ----------

    fun conversationMode(): Boolean = prefs.getBoolean(KEY_CONVO, false)

    fun setConversationMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONVO, enabled).apply()
    }

    // ---------- background wake toggle (persisted so it survives reboots/kills) ----------

    fun wakeEnabled(): Boolean = prefs.getBoolean(KEY_WAKE_ON, false)

    fun setWakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_ON, enabled).apply()
    }

    // ---------- generic lists (to-do / notes / shopping) ----------

    fun list(key: String): List<String> {
        val raw = prefs.getString(listKey(key), "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun addToList(key: String, item: String) {
        val i = item.trim()
        if (i.isBlank()) return
        val items = list(key).toMutableList()
        items.add(i)
        saveList(key, items)
    }

    /** Removes items containing [query]; returns how many were removed. */
    fun removeFromList(key: String, query: String): Int {
        val q = query.trim().lowercase()
        if (q.isBlank()) return 0
        val items = list(key).toMutableList()
        val before = items.size
        items.removeAll { it.lowercase().contains(q) }
        saveList(key, items)
        return before - items.size
    }

    fun clearList(key: String) = saveList(key, emptyList())

    private fun saveList(key: String, items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString(listKey(key), arr.toString()).apply()
    }

    private fun listKey(key: String) = "list_$key"

    // ---------- stopwatch (epoch millis of start; 0 = not running) ----------

    fun stopwatchStart(): Long = prefs.getLong(KEY_STOPWATCH, 0L)

    fun setStopwatchStart(millis: Long) {
        prefs.edit().putLong(KEY_STOPWATCH, millis).apply()
    }

    // ---------- long-term memory ----------

    fun memoryFacts(): List<String> {
        val raw = prefs.getString(KEY_MEMORY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun remember(fact: String) {
        val f = fact.trim()
        if (f.isBlank()) return
        val facts = memoryFacts().toMutableList()
        if (facts.none { it.equals(f, ignoreCase = true) }) {
            facts.add(f)
            saveFacts(facts)
        }
    }

    /** Removes any facts containing [query]. Returns how many were removed. */
    fun forget(query: String): Int {
        val q = query.trim().lowercase()
        if (q.isBlank()) return 0
        val facts = memoryFacts().toMutableList()
        val before = facts.size
        facts.removeAll { it.lowercase().contains(q) }
        saveFacts(facts)
        return before - facts.size
    }

    fun clearMemory() = saveFacts(emptyList())

    private fun saveFacts(facts: List<String>) {
        val arr = JSONArray()
        facts.forEach { arr.put(it) }
        prefs.edit().putString(KEY_MEMORY, arr.toString()).apply()
    }

    /** Personality instruction + memory facts, appended to the AI system prompt. */
    fun systemContext(): String {
        val sb = StringBuilder(personality().prompt)
        val facts = memoryFacts()
        if (facts.isNotEmpty()) {
            sb.append("\n\nThings you know about Nessa (use naturally when relevant):")
            facts.forEach { sb.append("\n- ").append(it) }
        }
        return sb.toString()
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_WAKE = "wake_word"
        const val KEY_WAKE_ON = "wake_enabled"
        const val KEY_PERSONALITY = "personality"
        const val KEY_CONVO = "conversation_mode"
        const val KEY_MEMORY = "memory_facts"
        const val KEY_STOPWATCH = "stopwatch_start"
        const val DEFAULT_WAKE = "zuela"

        // Speech recognition rarely spells invented names consistently, so match
        // plausible transcriptions of each supported wake word.
        val WAKE_VARIANTS: Map<String, List<String>> = mapOf(
            "zuela" to listOf(
                "zuela", "zwela", "zoela", "zuella", "zwella", "zwayla"
            ),
            "nova" to listOf("nova", "noba", "nowa", "novah"),
            "nessa" to listOf("nessa", "nesa", "nasa", "nessah", "nesha"),
            "assistant" to listOf("assistant", "assistance", "asistant")
        )
    }
}
