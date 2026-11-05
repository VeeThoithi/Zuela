package com.nessa.zuela

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nessa.zuela.brain.AssistantBrain
import com.nessa.zuela.brain.CommandProcessor
import com.nessa.zuela.brain.Personality
import com.nessa.zuela.data.ZuelaStore
import com.nessa.zuela.speech.SpeechRecognizerManager
import com.nessa.zuela.speech.TextToSpeechManager
import com.nessa.zuela.wake.WakeWordService
import kotlinx.coroutines.launch

class ZuelaViewModel(app: Application) : AndroidViewModel(app) {

    data class ChatMessage(val fromUser: Boolean, val text: String)

    enum class Status { IDLE, LISTENING, THINKING, SPEAKING }

    private val store = ZuelaStore(app)

    val messages = mutableStateListOf<ChatMessage>()
    var status by mutableStateOf(Status.IDLE)
        private set
    var wakeWordEnabled by mutableStateOf(store.wakeEnabled())
        private set

    // Settings surfaced to the UI.
    var apiKeySet by mutableStateOf(store.apiKey() != null)
        private set
    var personality by mutableStateOf(store.personality())
        private set
    var wakeWord by mutableStateOf(store.wakeWord())
        private set
    var conversationMode by mutableStateOf(store.conversationMode())
        private set
    var memoryCount by mutableIntStateOf(store.memoryFacts().size)
        private set

    private val speech = SpeechRecognizerManager(app)
    private val tts = TextToSpeechManager(app)
    private val commands = CommandProcessor(app, store)
    private val brain = AssistantBrain(
        apiKeyProvider = { store.apiKey() },
        contextProvider = { store.systemContext() },
    )

    init {
        messages.add(
            ChatMessage(
                fromUser = false,
                text = "Hi Nessa! I'm Zuela. Tap the mic and ask me anything — the time, " +
                    "\"open WhatsApp\", \"call Mum\", \"set an alarm for 7 am\", \"remember that " +
                    "I love jollof rice\", or any question at all. Turn on Conversation mode in " +
                    "settings and I'll keep listening so you don't have to tap each time."
            )
        )
    }

    fun onMicTap() {
        when (status) {
            Status.LISTENING -> {
                speech.stop()
                status = Status.IDLE
            }
            // Barge-in: interrupt speech and start listening immediately.
            Status.SPEAKING -> {
                tts.stop()
                startListening()
            }
            Status.IDLE -> startListening()
            Status.THINKING -> Unit
        }
    }

    private fun startListening(auto: Boolean = false) {
        tts.stop()
        status = Status.LISTENING
        speech.startListening(
            onResult = { heard -> handleUtterance(heard) },
            onError = { message ->
                status = Status.IDLE
                // In hands-free mode, don't spam "I didn't hear anything" — just stop.
                if (!auto) messages.add(ChatMessage(fromUser = false, text = message))
            }
        )
    }

    private fun handleUtterance(text: String) {
        messages.add(ChatMessage(fromUser = true, text = text))
        status = Status.THINKING
        viewModelScope.launch {
            val reply = commands.handle(text) ?: brain.ask(text)
            memoryCount = store.memoryFacts().size // memory commands may have changed it
            messages.add(ChatMessage(fromUser = false, text = reply))
            status = Status.SPEAKING
            tts.speak(reply) {
                if (status == Status.SPEAKING) {
                    status = Status.IDLE
                    // Conversation mode: keep the exchange going hands-free.
                    if (conversationMode) startListening(auto = true)
                }
            }
        }
    }

    // ---------- settings ----------

    fun saveApiKey(key: String) {
        store.setApiKey(key)
        apiKeySet = store.apiKey() != null
    }

    fun selectPersonality(p: Personality) {
        store.setPersonality(p)
        personality = p
    }

    fun selectWakeWord(token: String) {
        store.setWakeWord(token)
        wakeWord = store.wakeWord()
    }

    fun updateConversationMode(enabled: Boolean) {
        store.setConversationMode(enabled)
        conversationMode = enabled
        if (!enabled && status == Status.LISTENING) {
            speech.stop()
            status = Status.IDLE
        }
    }

    fun clearMemory() {
        store.clearMemory()
        memoryCount = 0
    }

    /**
     * The wake-word service only runs while the app is in the background —
     * MainActivity starts it in onPause and stops it in onResume — so the
     * in-app mic and the background listener never fight over the microphone.
     */
    fun toggleWakeWord(enabled: Boolean) {
        wakeWordEnabled = enabled
        store.setWakeEnabled(enabled)
        if (!enabled) {
            WakeWordService.stop(getApplication<Application>())
        }
    }

    override fun onCleared() {
        speech.destroy()
        tts.shutdown()
        super.onCleared()
    }
}
