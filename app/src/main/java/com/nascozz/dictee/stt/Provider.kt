package com.nascozz.dictee.stt

enum class ApiShape { OPENAI_COMPATIBLE, ELEVENLABS, DEEPGRAM }

enum class Provider(
    val id: String,
    val label: String,
    val tagline: String,
    val baseUrl: String,
    val shape: ApiShape,
    /** Modèles suggérés ; le premier est le modèle par défaut. N'importe quel autre nom peut être saisi. */
    val models: List<String>,
    val keyUrl: String,
    val supportsPrompt: Boolean = false,
    /** Page de consommation / facturation sur la console du fournisseur. */
    val billingUrl: String = "",
) {
    MISTRAL(
        "mistral", "Mistral", "Voxtral · excellent en français",
        "https://api.mistral.ai/v1", ApiShape.OPENAI_COMPATIBLE,
        listOf("voxtral-mini-latest", "voxtral-small-latest"),
        "https://console.mistral.ai/api-keys",
        billingUrl = "https://console.mistral.ai/",
    ),
    OPENAI(
        "openai", "OpenAI", "GPT-4o Transcribe & Whisper",
        "https://api.openai.com/v1", ApiShape.OPENAI_COMPATIBLE,
        listOf("gpt-4o-transcribe", "gpt-4o-mini-transcribe", "whisper-1"),
        "https://platform.openai.com/api-keys", supportsPrompt = true,
        billingUrl = "https://platform.openai.com/usage",
    ),
    GROQ(
        "groq", "Groq", "Whisper ultra-rapide, quasi gratuit",
        "https://api.groq.com/openai/v1", ApiShape.OPENAI_COMPATIBLE,
        listOf("whisper-large-v3-turbo", "whisper-large-v3"),
        "https://console.groq.com/keys", supportsPrompt = true,
        billingUrl = "https://console.groq.com/dashboard/usage",
    ),
    ELEVENLABS(
        "elevenlabs", "ElevenLabs", "Scribe · très précis",
        "https://api.elevenlabs.io/v1", ApiShape.ELEVENLABS,
        listOf("scribe_v1"),
        "https://elevenlabs.io/app/settings/api-keys",
        billingUrl = "https://elevenlabs.io/app/subscription",
    ),
    DEEPGRAM(
        "deepgram", "Deepgram", "Nova · rapide et ponctué",
        "https://api.deepgram.com/v1", ApiShape.DEEPGRAM,
        listOf("nova-3", "nova-2"),
        "https://console.deepgram.com",
        billingUrl = "https://console.deepgram.com",
    ),
    CUSTOM(
        "custom", "Compatible OpenAI", "Ton propre endpoint /audio/transcriptions",
        "", ApiShape.OPENAI_COMPATIBLE,
        listOf("whisper-1"),
        "", supportsPrompt = true,
    );

    companion object {
        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: MISTRAL
    }
}
