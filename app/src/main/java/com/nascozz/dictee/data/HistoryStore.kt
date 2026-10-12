package com.nascozz.dictee.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: Long,
    val text: String,
    val provider: String,
    val model: String,
    val durationMs: Long,
    /** Transcription ratée : l'audio est gardé dans [audioPath] pour pouvoir réessayer. */
    val failed: Boolean = false,
    val error: String? = null,
    val audioPath: String? = null,
)

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val failedDir = File(context.filesDir, "failed").apply { mkdirs() }
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    fun add(text: String, provider: String, model: String, durationMs: Long) {
        val entry = HistoryEntry(System.currentTimeMillis(), text, provider, model, durationMs)
        save((listOf(entry) + _items.value).take(MAX))
    }

    /** Garde une dictée ratée et son audio (déplacé dans le stockage de l'app) pour la réessayer plus tard. */
    fun addFailed(provider: String, model: String, durationMs: Long, error: String, audio: File) {
        val id = System.currentTimeMillis()
        val kept = File(failedDir, "$id.${audio.extension}")
        val moved = audio.renameTo(kept) || runCatching { audio.copyTo(kept, overwrite = true); audio.delete() }.isSuccess
        if (!moved) return
        val entry = HistoryEntry(id, "", provider, model, durationMs, failed = true, error = error, audioPath = kept.absolutePath)
        save((listOf(entry) + _items.value).take(MAX))
    }

    /** Une dictée ratée a fini par être transcrite : elle devient une entrée normale. */
    fun resolve(id: Long, text: String, provider: String, model: String) {
        _items.value.firstOrNull { it.id == id }?.audioPath?.let { File(it).delete() }
        save(_items.value.map {
            if (it.id == id) it.copy(text = text, provider = provider, model = model, failed = false, error = null, audioPath = null) else it
        })
    }

    fun setError(id: Long, error: String) = save(_items.value.map { if (it.id == id) it.copy(error = error) else it })

    fun delete(id: Long) {
        _items.value.firstOrNull { it.id == id }?.audioPath?.let { File(it).delete() }
        save(_items.value.filterNot { it.id == id })
    }

    fun clear() {
        failedDir.listFiles()?.forEach { it.delete() }
        save(emptyList())
    }

    private fun save(list: List<HistoryEntry>) {
        // Les entrées poussées hors de la limite emportent leur audio.
        val keep = list.mapNotNull { it.audioPath }.toSet()
        _items.value.mapNotNull { it.audioPath }.filterNot { it in keep }.forEach { File(it).delete() }
        _items.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("text", it.text).put("provider", it.provider)
                    .put("model", it.model).put("durationMs", it.durationMs)
                    .put("failed", it.failed).put("error", it.error).put("audioPath", it.audioPath)
            )
        }
        DiskWriter.write(file, arr.toString())
    }

    private fun read(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(
                o.getLong("id"), o.getString("text"), o.optString("provider"), o.optString("model"), o.optLong("durationMs"),
                failed = o.optBoolean("failed"),
                error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                audioPath = o.optString("audioPath").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 300
    }
}
