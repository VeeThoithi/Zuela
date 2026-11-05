package com.nessa.zuela.brain

/** Selectable assistant personalities. The [prompt] is appended to the AI system prompt. */
enum class Personality(val label: String, val prompt: String) {
    FRIENDLY(
        "Friendly",
        "Adopt a warm, friendly, encouraging tone — like a close friend who's genuinely glad to help."
    ),
    PROFESSIONAL(
        "Professional",
        "Adopt a concise, precise, professional tone. Skip filler and small talk; get straight to the point."
    ),
    FUNNY(
        "Funny",
        "Adopt a witty, playful tone with light humour and the occasional joke, while still being genuinely helpful."
    ),
    COACH(
        "Coach",
        "Adopt an upbeat motivational-coach tone: energetic, positive, and action-oriented, nudging Nessa toward her next step."
    );

    companion object {
        fun from(name: String?): Personality = entries.firstOrNull { it.name == name } ?: FRIENDLY
    }
}
