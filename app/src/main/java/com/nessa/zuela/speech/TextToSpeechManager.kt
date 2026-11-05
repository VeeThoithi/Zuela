package com.nessa.zuela.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Wrapper around Android's built-in text-to-speech.
 * [speak] flushes anything currently playing; the optional callback fires
 * on the main thread when speaking finishes (or fails).
 */
class TextToSpeechManager(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingOnDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val result = tts.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
            selectBestVoice()
            // Slightly slower than default reads more naturally and is easier to follow.
            tts.setSpeechRate(0.97f)
            tts.setPitch(1.0f)
        }
    }

    /**
     * Pick the highest-quality installed voice for the current language, so we
     * get Google's neural/"natural" voice instead of the robotic fallback where
     * one is present. Best-effort — never throws.
     */
    private fun selectBestVoice() {
        try {
            val lang = tts.language?.language ?: Locale.getDefault().language
            val candidates = tts.voices
                ?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
                ?.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?: return
            val best = candidates.maxByOrNull { scoreVoice(it) } ?: return
            tts.voice = best
        } catch (_: Exception) {
            // Keep the default voice.
        }
    }

    private fun scoreVoice(voice: Voice): Int {
        var score = voice.quality // Voice.QUALITY_* constants: higher is better
        val name = voice.name.lowercase(Locale.ROOT)
        if ("neural" in name || "natural" in name || "wavenet" in name) score += 1000
        if ("female" in name) score += 50 // Zuela reads as a female assistant by default
        if (voice.isNetworkConnectionRequired) score -= 500
        return score
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                fireDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                fireDone()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                fireDone()
            }
        })
    }

    private fun fireDone() {
        mainHandler.post {
            val cb = pendingOnDone
            pendingOnDone = null
            cb?.invoke()
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        pendingOnDone = onDone
        if (!ready) {
            // Engine not ready — don't leave the caller hanging.
            fireDone()
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zuela-${System.currentTimeMillis()}")
    }

    fun stop() {
        pendingOnDone = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        pendingOnDone = null
        tts.shutdown()
    }
}
