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
)

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    fun add(text: String, provider: String, model: String, durationMs: Long) {
        val entry = HistoryEntry(System.currentTimeMillis(), text, provider, model, durationMs)
        save((listOf(entry) + _items.value).take(MAX))
    }

    fun delete(id: Long) = save(_items.value.filterNot { it.id == id })
    fun clear() = save(emptyList())

    private fun save(list: List<HistoryEntry>) {
        _items.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("text", it.text).put("provider", it.provider)
                    .put("model", it.model).put("durationMs", it.durationMs)
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }

    private fun read(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(o.getLong("id"), o.getString("text"), o.optString("provider"), o.optString("model"), o.optLong("durationMs"))
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 300
    }
}
