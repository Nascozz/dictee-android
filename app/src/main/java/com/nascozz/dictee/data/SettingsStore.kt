package com.nascozz.dictee.data

import android.content.Context
import android.content.SharedPreferences
import com.nascozz.dictee.stt.Provider
import com.nascozz.dictee.stt.TextFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val providerId: String,
    val models: Map<String, String>,
    val language: String,
    val prompt: String,
    val customBaseUrl: String,
    val bubbleEnabled: Boolean,
    val haptics: Boolean,
    val autoSpace: Boolean,
    val autoUpdate: Boolean,
    /** Mistral : le texte s'écrit pendant qu'on parle (Voxtral Realtime). */
    val realtime: Boolean,
    /** « virgule », « point », « à la ligne »… remplacés par la ponctuation. */
    val voiceCommands: Boolean,
    /** Ponctuation minimale (style SMS) plutôt que celle du modèle telle quelle. */
    val minimalPunctuation: Boolean,
    /** Temps réel : retour à la ligne après une pause de [pauseSeconds]. */
    val pauseBreak: Boolean,
    val pauseSeconds: Float,
    /** Temps réel : quand le message part (champ vidé), l'écoute s'arrête. */
    val stopOnSend: Boolean,
    /** Fournisseurs pour lesquels une clé API est enregistrée. */
    val keyed: Set<String>,
) {
    val provider: Provider get() = Provider.fromId(providerId)
    fun modelFor(p: Provider): String = models[p.id]?.takeIf { it.isNotBlank() } ?: p.models.first()
    fun hasKey(p: Provider): Boolean = p.id in keyed
    val useRealtime: Boolean get() = realtime && provider == Provider.MISTRAL
    val formatOptions: TextFormatter.Options
        get() = TextFormatter.Options(voiceCommands, minimalPunctuation, french = language == "fr" || language == "auto")
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun load() = AppSettings(
        providerId = prefs.getString("provider", Provider.MISTRAL.id)!!,
        models = Provider.entries.mapNotNull { p -> prefs.getString("model_${p.id}", null)?.let { p.id to it } }.toMap(),
        language = prefs.getString("language", "auto")!!,
        prompt = prefs.getString("prompt", "")!!,
        customBaseUrl = prefs.getString("custom_base_url", "")!!,
        bubbleEnabled = prefs.getBoolean("bubble_enabled", true),
        haptics = prefs.getBoolean("haptics", true),
        autoSpace = prefs.getBoolean("auto_space", true),
        autoUpdate = prefs.getBoolean("auto_update", true),
        realtime = prefs.getBoolean("realtime", true),
        voiceCommands = prefs.getBoolean("voice_commands", true),
        minimalPunctuation = prefs.getBoolean("minimal_punctuation", false),
        pauseBreak = prefs.getBoolean("pause_break", true),
        pauseSeconds = prefs.getFloat("pause_seconds", 2.5f),
        stopOnSend = prefs.getBoolean("stop_on_send", true),
        keyed = Provider.entries.filter { prefs.contains("key_${it.id}") }.map { it.id }.toSet(),
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = load()
    }

    fun setProvider(p: Provider) = edit { putString("provider", p.id) }
    fun setModel(p: Provider, model: String) = edit { putString("model_${p.id}", model.trim()) }
    fun setLanguage(code: String) = edit { putString("language", code) }
    fun setPrompt(prompt: String) = edit { putString("prompt", prompt) }
    fun setCustomBaseUrl(url: String) = edit { putString("custom_base_url", url.trim()) }
    fun setBubbleEnabled(v: Boolean) = edit { putBoolean("bubble_enabled", v) }
    fun setHaptics(v: Boolean) = edit { putBoolean("haptics", v) }
    fun setAutoSpace(v: Boolean) = edit { putBoolean("auto_space", v) }
    fun setAutoUpdate(v: Boolean) = edit { putBoolean("auto_update", v) }
    fun setRealtime(v: Boolean) = edit { putBoolean("realtime", v) }
    fun setVoiceCommands(v: Boolean) = edit { putBoolean("voice_commands", v) }
    fun setMinimalPunctuation(v: Boolean) = edit { putBoolean("minimal_punctuation", v) }
    fun setPauseBreak(v: Boolean) = edit { putBoolean("pause_break", v) }
    fun setPauseSeconds(v: Float) = edit { putFloat("pause_seconds", v) }
    fun setStopOnSend(v: Boolean) = edit { putBoolean("stop_on_send", v) }

    fun setApiKey(p: Provider, key: String?) = edit {
        if (key.isNullOrBlank()) remove("key_${p.id}") else putString("key_${p.id}", SecretBox.encrypt(key.trim()))
    }

    fun apiKey(p: Provider): String? = prefs.getString("key_${p.id}", null)?.let(SecretBox::decrypt)
}
