package com.nessa.zuela.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The smart half of the hybrid brain. Sends anything the on-device
 * [CommandProcessor] can't handle to Groq's free API (Llama 3.3 70B) and
 * returns a short, speakable answer. Keeps a rolling conversation history so
 * follow-up questions work.
 *
 * Groq is used because its free tier works worldwide with no credit card and
 * generous limits. It's OpenAI-compatible, so switching to another
 * OpenAI-style provider (OpenRouter, Together, etc.) later means changing only
 * [ENDPOINT] and [MODEL] here.
 *
 * Uses only Android's built-in HttpURLConnection + org.json — no third-party
 * networking or AI SDK.
 */
class AssistantBrain(
    private val apiKeyProvider: () -> String?,
    /** Extra system-prompt context (personality + long-term memory). */
    private val contextProvider: () -> String = { "" },
) {

    // Each entry is role ("user" or "assistant") to the spoken text.
    private val history = mutableListOf<Pair<String, String>>()

    suspend fun ask(question: String): String = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrBlank()) {
            return@withContext "I can answer that with my smart brain, but I don't have an API key yet. " +
                "Tap the settings icon and paste your free Groq API key."
        }

        history.add("user" to question)
        try {
            val reply = request(key)
            history.add("assistant" to reply)
            trimHistory()
            reply
        } catch (e: ApiException) {
            history.removeAt(history.lastIndex)
            e.spokenMessage
        } catch (e: Exception) {
            history.removeAt(history.lastIndex)
            "Something went wrong while I was thinking. Please try again."
        }
    }

    fun resetConversation() {
        history.clear()
    }

    private fun request(key: String): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
        }

        connection.outputStream.use { it.write(buildRequestBody().toByteArray()) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            throw ApiException(
                when (code) {
                    401, 403 -> "That Groq API key doesn't seem to be valid. Double-check it in settings."
                    429 -> "I've hit the free usage limit for the moment. Give it a minute and try again."
                    else -> "The AI service returned an error. Please try again in a moment."
                }
            )
        }

        val text = parseReply(body)
        return text.ifBlank { "Hmm, I didn't come up with an answer. Try asking another way?" }
    }

    private fun buildRequestBody(): String {
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system").put("content", systemPrompt())
        )
        for ((role, text) in history) {
            messages.put(JSONObject().put("role", role).put("content", text))
        }

        return JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("max_tokens", 500)
            .put("temperature", 0.7)
            .toString()
    }

    private fun parseReply(body: String): String {
        val choices = JSONObject(body).optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw ApiException("I wasn't able to answer that one. Try rephrasing?")
        }
        return choices
            .getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
    }

    private fun systemPrompt(): String {
        val extra = contextProvider().trim()
        return if (extra.isEmpty()) BASE_PROMPT else "$BASE_PROMPT\n\n$extra"
    }

    private fun trimHistory() {
        // Keep the last ~10 exchanges, dropping in user+assistant pairs.
        while (history.size > 20) {
            history.removeAt(0)
            if (history.isNotEmpty()) history.removeAt(0)
        }
    }

    private class ApiException(val spokenMessage: String) : Exception(spokenMessage)

    private companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        // Free on Groq. Swap for "llama-3.1-8b-instant" if you want faster
        // replies / higher rate limits at slightly lower quality.
        const val MODEL = "llama-3.3-70b-versatile"

        const val BASE_PROMPT =
            "You are Zuela, Nessa's personal voice assistant running on her Android phone. " +
                "Your replies are read aloud by text-to-speech, so keep them short and natural: " +
                "one to three conversational sentences, plain text only — no markdown, no lists, " +
                "no emoji, no code blocks. Be warm, helpful, and direct. If a question truly needs " +
                "a long answer, give the key point first and offer to go deeper. " +
                "You cannot open apps, place calls, or set alarms, timers, or reminders yourself — " +
                "the phone handles those directly. If the user asks you to do one of those, do NOT " +
                "claim you did it; instead tell them to say it as a direct command, for example " +
                "\"open TikTok\" or \"call Mum\"."
    }
}
