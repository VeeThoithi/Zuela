package com.nessa.zuela.wake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nessa.zuela.brain.AssistantBrain
import com.nessa.zuela.brain.CommandProcessor
import com.nessa.zuela.data.ZuelaStore
import com.nessa.zuela.speech.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that keeps the microphone open listening for "Hey Zuela".
 * When the wake word is heard it beeps, listens for one command, answers it
 * out loud (rule-based first, then Claude), and goes back to waiting.
 *
 * Note: continuous listening uses noticeable battery — the in-app toggle
 * makes that trade-off explicit to the user.
 */
class WakeWordService : Service() {

    private enum class Mode { WAKE, COMMAND, BUSY }

    private var mode = Mode.WAKE
    private var running = false
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tts: TextToSpeechManager
    private lateinit var commands: CommandProcessor
    private lateinit var brain: AssistantBrain
    private lateinit var store: ZuelaStore
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        store = ZuelaStore(this)
        tts = TextToSpeechManager(this)
        commands = CommandProcessor(this, store)
        brain = AssistantBrain(
            apiKeyProvider = { store.apiKey() },
            contextProvider = { store.systemContext() },
        )

        // Keep the CPU awake so listening continues with the screen off.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zuela:wakeword")
            .apply {
                setReferenceCounted(false)
                try {
                    acquire(6 * 60 * 60 * 1000L) // safety timeout: 6 hours
                } catch (_: Exception) {
                }
            }

        running = true
        mode = Mode.WAKE
        listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts.shutdown()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Hey Zuela listening",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zuela is listening")
            .setContentText("Say your wake word, then your request")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ---------- listening loop ----------

    private fun listen() {
        if (!running) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, mode == Mode.WAKE)
                }
            )
        }
    }

    private fun scheduleRestart(delayMs: Long = 400) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            if (mode != Mode.WAKE) return
            val heard = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            if (containsWakeWord(heard)) onWakeDetected()
        }

        override fun onResults(results: Bundle?) {
            val heard = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            when (mode) {
                Mode.WAKE -> {
                    if (containsWakeWord(heard)) onWakeDetected() else scheduleRestart()
                }
                Mode.COMMAND -> {
                    val command = stripWakeWord(heard)
                    if (command.isBlank()) {
                        mode = Mode.WAKE
                        scheduleRestart()
                    } else {
                        onCommand(command)
                    }
                }
                Mode.BUSY -> Unit
            }
        }

        override fun onError(error: Int) {
            if (mode == Mode.BUSY) return
            if (mode == Mode.COMMAND &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                mode = Mode.WAKE
            }
            // The recognizer needs a moment to recover from busy/client errors,
            // otherwise it error-loops and hammers the CPU.
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> 1500L
                else -> 500L
            }
            scheduleRestart(delay)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun onWakeDetected() {
        if (mode != Mode.WAKE) return
        mode = Mode.COMMAND
        beep()
        recognizer?.cancel()
        scheduleRestart(200)
    }

    private fun onCommand(command: String) {
        mode = Mode.BUSY
        recognizer?.cancel()
        scope.launch {
            val reply = commands.handle(command) ?: brain.ask(command)
            tts.speak(reply) {
                mode = Mode.WAKE
                scheduleRestart(300)
            }
        }
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                .startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (_: Exception) {
            // Tone is a nicety; never crash over it.
        }
    }

    /**
     * Requires the full "hey <name>" phrase (with a leading trigger word and
     * word boundaries) — NOT just the name as a substring. This stops music
     * and background speech from constantly false-triggering the wake word.
     */
    private fun containsWakeWord(heard: String): Boolean =
        wakeRegex().containsMatchIn(heard.lowercase(Locale.ROOT))

    private fun wakeRegex(): Regex {
        val names = store.wakeVariants().joinToString("|") { Regex.escape(it) }
        return Regex("\\b(hey|hay|hi|ok|okay|hello|yo)\\s+($names)\\b")
    }

    /** If the user said "hey zuela what's the weather" in one breath, keep the command part. */
    private fun stripWakeWord(heard: String): String {
        var text = heard.lowercase(Locale.ROOT)
        text = wakeRegex().replace(text, " ")
        text = text.replace(Regex("\\b(hey|hi|ok|okay|hello|yo)\\b"), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private const val CHANNEL_ID = "zuela_wake"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
