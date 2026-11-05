# Zuela — your personal voice assistant 🎙️

Zuela is a Kotlin Android app: you talk, she listens, answers out loud, and can
control your phone. She has a **hybrid brain**:

- **Instant, on-device (free, works offline for most things):** telling the time
  and date, opening apps ("open WhatsApp"), calling contacts ("call Mum"),
  alarms ("set an alarm for 6:30 am"), timers ("set a timer for 10 minutes"),
  and reminders ("remind me to feed the cat at 6 pm").
- **AI-powered (needs a free Groq API key):** anything else — questions,
  advice, conversations. Answers are kept short so they sound natural spoken aloud.

Plus a **"Hey Zuela" wake word**: flip the switch in the app and she keeps
listening in the background after you leave the app — say "Hey Zuela", wait for
the beep, then speak your request. She answers out loud without you touching
the phone. (This keeps the microphone on, so it uses noticeable battery — the
notification shows while it's active.)

---

## 1. Get a free Groq API key (for the smart answers)

1. Go to **[console.groq.com/keys](https://console.groq.com/keys)** and sign up
   (Google/email — no credit card).
2. Click **Create API Key**, name it anything, and copy it.
3. It's **free and works worldwide**, with generous limits (~30 requests/minute,
   ~1,000/day) — far more than a personal assistant needs.

You'll paste this into Zuela on first launch (step 4). The basic commands (time,
alarms, calling, opening apps) work **without** any key.

## 2. Put your phone in developer mode (one time)

1. On your phone: **Settings → About phone → tap "Build number" 7 times.**
2. Go to **Settings → System → Developer options** and turn on **USB debugging**.
3. Plug the phone into this PC with a USB cable. When the phone asks
   "Allow USB debugging?", tap **Allow**.

## 3. Build & install the app

Open a terminal in this folder and run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat installDebug
```

Zuela appears in your app drawer with a purple mic icon.

(You can also open this folder in Android Studio and press the green ▶ Run button.)

## 4. First launch

1. Grant the permissions Zuela asks for — microphone is essential; contacts and
   phone are only needed for "call ..." commands; notifications are for the
   background listener.
2. To enable the smart brain: tap the ⚙️ settings icon and paste your Groq API
   key from step 1, then Save. The key is stored only in the app's private
   storage on your phone.

## 5. Things to try

**Work with no extra setup**
- "What time is it?" · "Open YouTube" · "Call Mum"
- "Set an alarm for 7 am" · "Set a timer for 3 minutes"
- "Remember that I love jollof rice" · "What do you know about me?"
- "Play Burna Boy on Spotify" · "Search for the news"
- "What's the weather?" (uses your location) · "What's the weather in Lagos?"
- "What should I cook with rice, tomatoes and beans?"

**Productivity**
- Lists: "add milk to my shopping list" · "what's on my shopping list" ·
  "add call the bank to my to-do list" · "clear my shopping list"
- Notes: "make a note that the wifi password is 1234" · "read my notes"
- "start a stopwatch" / "stop the stopwatch" · "start a pomodoro"

**Follow-up questions** — if you leave out a detail, Zuela asks:
- You: "remind me tomorrow" → Zuela: "What time should I remind you?" → You: "8 am"

**Need Notification access** (Settings → ⚙️ → *Grant notification access*, switch on Zuela)
- Music: "pause", "play", "next song", "previous", "stop"
- Messages: "read my messages", "reply to Mum that I'm on my way"

**Sending & calls**
- "Text Mum that I'll call later" / "WhatsApp John saying happy birthday" — opens the
  chat with your message typed in; you tap send (Android doesn't let apps send silently).
- "End the call" / "Hang up"

### What Android does NOT allow (and why)
A normal app is sandboxed from other apps. So Zuela **cannot** drive another app's
screen — e.g. search people inside Instagram, tap around WhatsApp, or auto-send a
WhatsApp message. The supported paths are the ones above: reading and *replying to*
messages through Android's notification system (how smartwatches do it), controlling
media through the media-session API, and opening chats pre-filled for you to send.

## Wake word tips

- Turn on the switch, then leave the app (press home). A notification shows
  Zuela is listening.
- Say **"Hey Zuela"**, wait for the *beep*, then speak your request.
- Turn the switch off (or swipe the app away) when you want the mic released
  and battery saved.
- Speech recognition sometimes hears the name creatively — Zuela already
  matches common variations, but speak the name clearly.

## Project layout

```
app/src/main/java/com/nessa/zuela/
├── MainActivity.kt          # Compose UI: chat, mic button, settings, wake toggle
├── ZuelaViewModel.kt        # listen → understand → reply → speak pipeline
├── speech/
│   ├── SpeechRecognizerManager.kt   # Android speech-to-text
│   └── TextToSpeechManager.kt       # Android text-to-speech
├── brain/
│   ├── CommandProcessor.kt  # instant on-device commands
│   └── AssistantBrain.kt    # Groq free API (Llama 3.3 70B), conversation memory
└── wake/WakeWordService.kt  # "Hey Zuela" background listener
```

The only file that talks to the cloud is `brain/AssistantBrain.kt` — it makes a
plain HTTPS call, so you can point Zuela at a different AI provider later by
changing just that one file.

## Ideas for version 2

- A proper wake-word engine (e.g. Picovoice Porcupine) — far more accurate and
  battery-friendly than looping the speech recognizer.
- Streaming responses so Zuela starts speaking sooner.
- WhatsApp message dictation, music control, smart-home commands.
```
