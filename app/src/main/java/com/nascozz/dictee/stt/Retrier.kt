package com.nascozz.dictee.stt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.nascozz.dictee.Graph
import com.nascozz.dictee.data.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * « Réessayer » sur une dictée ratée : renvoie l'audio gardé avec les réglages actuels
 * (mode classique, même si le temps réel est activé), puis met le texte dans le presse-papiers.
 */
object Retrier {
    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running: StateFlow<Set<Long>> = _running.asStateFlow()

    fun retry(context: Context, entry: HistoryEntry) {
        val audio = entry.audioPath?.let(::File)?.takeIf { it.exists() } ?: run {
            Toast.makeText(context, "L'audio de cette dictée n'existe plus", Toast.LENGTH_SHORT).show()
            return
        }
        if (entry.id in _running.value) return
        _running.value += entry.id
        val app = context.applicationContext
        Graph.appScope.launch {
            val s = Graph.settings.current
            val provider = s.provider
            val request = SttRequest(
                provider = provider,
                apiKey = Graph.settings.apiKey(provider).orEmpty(),
                model = s.modelFor(provider),
                language = s.language.takeIf { it != "auto" },
                prompt = s.prompt,
                customBaseUrl = s.customBaseUrl,
            )
            try {
                if (request.apiKey.isEmpty()) throw SttException("Ajoute ta clé ${provider.label} dans les réglages")
                val text = TextFormatter.format(Transcriber.transcribe(audio, request), s.formatOptions).trim()
                Graph.usage.record(request.model, entry.durationMs)
                if (text.isEmpty()) throw SttException("Je n'ai rien entendu")
                Graph.history.resolve(entry.id, text, provider.label, request.model)
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Dictée", text))
                Toast.makeText(app, "Transcrit et copié dans le presse-papiers", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e as? SttException)?.message ?: "Erreur : ${e.message}"
                Graph.history.setError(entry.id, msg)
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            } finally {
                _running.value -= entry.id
            }
        }
    }
}
