package com.nascozz.dictee.data

import android.content.Context
import com.nascozz.dictee.stt.Pricing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Utilisation d'un modèle : secondes d'audio envoyées et nombre de dictées. */
data class ModelUsage(val seconds: Double, val count: Int) {
    operator fun plus(o: ModelUsage) = ModelUsage(seconds + o.seconds, count + o.count)
}

/**
 * Journal d'utilisation, par jour et par modèle. Indépendant de l'historique :
 * effacer ses dictées ne remet pas le compteur de dépenses à zéro.
 */
class UsageStore(context: Context) {
    private val file = File(context.filesDir, "usage.json")
    private val _days = MutableStateFlow(read())
    /** Date ISO (aaaa-mm-jj) → modèle → utilisation. */
    val days: StateFlow<Map<String, Map<String, ModelUsage>>> = _days.asStateFlow()

    fun record(model: String, durationMs: Long) {
        if (durationMs <= 0) return
        val today = LocalDate.now().toString()
        val all = _days.value.toMutableMap()
        val day = all[today].orEmpty().toMutableMap()
        day[model] = (day[model] ?: ModelUsage(0.0, 0)) + ModelUsage(durationMs / 1000.0, 1)
        all[today] = day
        _days.value = all
        save(all)
    }

    private fun save(all: Map<String, Map<String, ModelUsage>>) {
        val root = JSONObject()
        all.forEach { (date, models) ->
            val d = JSONObject()
            models.forEach { (m, u) -> d.put(m, JSONObject().put("s", u.seconds).put("n", u.count)) }
            root.put(date, d)
        }
        DiskWriter.write(file, root.toString())
    }

    private fun read(): Map<String, Map<String, ModelUsage>> = runCatching {
        val root = JSONObject(file.readText())
        root.keys().asSequence().associateWith { date ->
            val d = root.getJSONObject(date)
            d.keys().asSequence().associateWith { m ->
                val u = d.getJSONObject(m)
                ModelUsage(u.getDouble("s"), u.getInt("n"))
            }
        }
    }.getOrDefault(emptyMap())

    companion object {
        /** Somme par modèle sur un ensemble de jours. */
        fun byModel(days: Collection<Map<String, ModelUsage>>): Map<String, ModelUsage> {
            val out = mutableMapOf<String, ModelUsage>()
            days.forEach { d -> d.forEach { (m, u) -> out[m] = (out[m] ?: ModelUsage(0.0, 0)) + u } }
            return out
        }

        /** Coût estimé ; les modèles au prix inconnu comptent pour 0. */
        fun cost(models: Map<String, ModelUsage>): Double =
            models.entries.sumOf { (m, u) -> Pricing.cost(m, u.seconds) ?: 0.0 }
    }
}
