# Zuela — Feature Audit & Roadmap

Senior-engineer audit of the existing app against the requested checklist.
**Platform reality:** Zuela is a **native Android phone app** (Kotlin + Jetpack
Compose). Several checklist items assume a **desktop** assistant and cannot be
done from a normal Android app due to the OS security model — those are marked
**N/A (platform)** with the closest phone-appropriate alternative.

Ratings are 1–10 for the *current* implementation (before this pass).
"Phase 1" = implemented in this iteration. Later phases are proposed, not yet built.

---

## Priority 1 — Voice Experience

| Feature | Exists? | Rating | Notes / plan |
|---|---|---|---|
| Speech-to-text | ✅ | 6 | Uses Android `SpeechRecognizer` (Google backend). Fast, accurate, handles accents, supports adding Swahili later via `EXTRA_LANGUAGE`. Weakness: it's one-shot and needs a network for best accuracy. A fully-offline upgrade (Vosk / whisper.cpp) is a later phase. **Kept**, works well. |
| Text-to-speech | ✅ | 5 → **7 (Phase 1)** | Android `TextToSpeech`. **Phase 1:** now auto-selects the highest-quality neural voice installed and tunes rate for naturalness. True offline neural TTS (Piper/Kokoro via `sherpa-onnx`) is the recommended big upgrade — see "TTS recommendation" below. |
| Wake word | ✅ | 5 → **7 (Phase 1)** | "Hey Zuela" background service existed. **Phase 1:** wake word is now **customizable** (Zuela / Nova / Nessa / Assistant) from Settings. |
| Continuous conversation | ❌ → ✅ **(Phase 1)** | – → 7 | **Phase 1:** new *Conversation mode* — after answering, Zuela keeps listening until you turn it off, no button press per turn. |
| Interruptions | ⚠️ → ✅ **(Phase 1)** | 4 → 7 | Tapping the mic already interrupted speech. **Phase 1:** kept + integrated with conversation mode (speak → auto-listen). True hands-free barge-in (talking over the voice) needs acoustic echo cancellation and is a later phase. |
| Context awareness (follow-ups, "it/that project") | ⚠️ | 6 | The AI brain already keeps rolling conversation history, so pronoun/follow-up questions to the AI work. On-device *command* follow-ups ("remind me tomorrow" → "what time?") are a later phase (needs a pending-intent slot-filling state machine). |

### TTS recommendation (researched)

| Option | Quality | Offline | Android fit | Verdict |
|---|---|---|---|---|
| **Android built-in (Google neural)** | Good | Partial | Native | **Use now** — free, zero-integration, decent. Done in Phase 1. |
| **Piper (via sherpa-onnx)** | Very good | ✅ | AAR + ONNX model | **Best offline upgrade.** ~30–60 MB model, real-time on-device. Recommended Phase 2. |
| **Kokoro (via sherpa-onnx)** | Excellent | ✅ | AAR + ~80 MB ONNX | Highest quality open TTS; heavier. Phase 2 alt. |
| StyleTTS2 / Coqui | Excellent | ✅ (PC) | ❌ Python/GPU | Not runnable in an Android app. Would need a self-hosted server. |
| Voice cloning (free + offline + Android) | – | – | ❌ | Not currently practical on-device. Skip. |

**Decision:** ship the built-in-voice improvement now; offer Piper-via-sherpa-onnx
as an opt-in offline pack in Phase 2. This is the honest best balance of quality,
speed, free, and offline for Android today.

---

## Priority 2 — Memory

| Feature | Exists? | Rating | Notes / plan |
|---|---|---|---|
| Short-term (this conversation) | ✅ | 7 | AI brain keeps the last ~10 exchanges. |
| Long-term (preferences, favourites, people, routines) | ❌ → ✅ **(Phase 1)** | – → 7 | **Phase 1:** persistent memory store; facts are injected into the AI's system prompt so it uses them naturally. |
| "Remember that…" / "Forget…" / "What do you know about me?" / "Clear memory" | ❌ → ✅ **(Phase 1)** | – → 8 | **Phase 1:** all four voice commands implemented, handled instantly on-device. |

---

## Priority 3 — Personality

| Feature | Exists? | Rating | Notes |
|---|---|---|---|
| Friendly / Professional / Funny / Motivational Coach, switchable live | ❌ → ✅ **(Phase 1)** | – → 8 | **Phase 1:** four modes, switch in Settings without restarting; changes the AI's tone immediately. |

---

## Priority 4 — Productivity

| Feature | Exists? | Rating | Plan |
|---|---|---|---|
| Timers | ✅ | 7 | Works via clock app. |
| Reminders | ✅ | 6 | Works via alarm; slot-filling follow-ups later. |
| Alarms | ✅ | 7 | Works. |
| Calendar | ❌ | – | Phase 2 (Calendar Intent — add events by voice). |
| To-do list / Notes / Shopping list | ❌ | – | **Phase 2** (local storage + voice add/read). High value, planned next. |
| Stopwatch | ❌ | – | Phase 2 (clock-app intent). |
| Pomodoro | ❌ | – | Phase 2. |
| Morning briefing / Evening recap | ❌ | – | Phase 3 (needs weather + calendar + tasks wired together). |

---

## Priority 7 — Computer Control → **Phone control** (re-scoped)

**N/A (platform):** close other apps, switch windows, take screenshots of other
apps, type into other apps, copy/paste across apps, rename/delete arbitrary
files, create folders, open VS Code, shut down / restart / lock the *computer*.
Android sandboxes apps from each other and from the OS; a normal app cannot do
these. (Some need a rooted device or an Accessibility-Service, which is invasive.)

| Phone-appropriate equivalent | Exists? | Plan |
|---|---|---|
| Open apps | ✅ | Works (improved matching already shipped). |
| Open browser / Spotify / YouTube | ✅ | Covered by "open <app>". |
| Lock screen | ❌ | Possible via Device Admin API — Phase 3, opt-in. |
| Web search intent | ❌ | Phase 2 (fire a web-search intent). |

---

## Priorities 8–15 — Internet / Communication / Finance / Health / Entertainment / AI / Vision / Automation

| Priority | Status | Realistic plan |
|---|---|---|
| 8 Internet (search, weather, news) | ❌ | **Phase 2:** weather + web search (free APIs / intents). Summarize/research via the existing AI brain. |
| 9 Communication (email/SMS) | ❌ | Phase 3: SMS send via intent (easy); email read/draft needs Gmail API + OAuth (heavy) — modular provider interface. |
| 10 Finance | ❌ | Phase 3: local expense/budget/bills store + reminders. Self-contained, no bank integration. |
| 11 Health | ❌ | Phase 3: water/medication reminders (alarms) + simple local logs. Step/sleep need Health Connect API. |
| 12 Entertainment | Partial | "Open Spotify/YouTube" works. Recommendations = AI brain (Phase 2, trivial). Spotify *control* needs its SDK (Phase 3). |
| 13 AI features (translate, summarize, rewrite, brainstorm, etc.) | ⚠️ | Mostly **already possible** through the AI brain by asking. Phase 2: add explicit voice verbs so they're one-shot. |
| 14 Vision (OCR, receipts, objects) | ❌ | Phase 3: ML Kit (on-device OCR/object detection) + camera. Sizeable but self-contained. |
| 15 Automation ("when X do Y") | ❌ | Phase 4: rules engine + WorkManager/BroadcastReceivers (battery, incoming mail, "work mode"). Largest new subsystem. |

---

## Priority 16 — Multi-Agent Architecture

**Recommendation: not yet.** The current layered design is already clean and
correct for this size:

```
UI (Compose) → ZuelaViewModel → { CommandProcessor (on-device actions),
                                   AssistantBrain (cloud AI) }
                              → SpeechRecognizer / TextToSpeech
                              + ZuelaStore (memory, settings, personality)
```

`CommandProcessor` is effectively the "device-control + productivity agent" and
`AssistantBrain` the "conversation agent". A formal multi-agent split (separate
Memory/Voice/Web/Automation agents) adds coordination overhead that isn't paid
back until Phases 2–4 land. **Plan:** keep this structure; when the web,
automation, and vision subsystems arrive, promote each to its own module behind a
small `Skill` interface (`fun handle(intent): Result?`). That gives the
multi-agent benefit (isolation, testability) without a premature rewrite.

---

## UX

| Item | Status | Plan |
|---|---|---|
| Voice visualizer | ❌ → ✅ **(Phase 1)** | Pulsing mic while listening. |
| Loading / status states | ✅ | Listening / Thinking / Speaking already shown. |
| Better settings | ⚠️ → ✅ **(Phase 1)** | Settings now has personality, wake word, conversation mode, memory management. |
| Response streaming | ❌ | Phase 2 (stream AI tokens so it starts speaking sooner). |
| Error handling | ✅ | Typed, spoken error messages already in place. |
| Accessibility / keyboard shortcuts | ⚠️ | Phase 3. |

---

## What shipped in Phase 1 (this iteration)

1. **Long-term memory** + voice commands (remember / forget / what do you know / clear).
2. **Personality modes** (Friendly / Professional / Funny / Coach), switch live.
3. **Customizable wake word** (Zuela / Nova / Nessa / Assistant).
4. **Conversation mode** (keeps listening after answering; hands-free).
5. **TTS quality** — best neural voice auto-selected + tuned.
6. **Voice visualizer** + reorganised **Settings**.
7. Central **`ZuelaStore`** for settings/memory — the seam future skills plug into.

Architecture preserved; changes are additive.
