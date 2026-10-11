package com.nascozz.dictee.stt

/**
 * Tarifs publics en dollars par minute d'audio (relevés en octobre 2026).
 * Sert à estimer le coût : le montant exact reste celui de la console du fournisseur.
 */
object Pricing {
    // L'ordre compte : les noms les plus précis d'abord.
    private val perMinute = listOf(
        "voxtral-mini-transcribe-realtime" to 0.006,
        "voxtral-small" to 0.004,
        "voxtral" to 0.003,
        "gpt-4o-mini-transcribe" to 0.003,
        "gpt-4o-transcribe" to 0.006,
        "whisper-1" to 0.006,
        "whisper-large-v3-turbo" to 0.04 / 60,
        "whisper-large-v3" to 0.111 / 60,
        "scribe" to 0.40 / 60,
        "nova" to 0.0043,
    )

    /** Prix par minute pour ce modèle, ou null s'il est inconnu (modèle personnalisé…). */
    fun perMinute(model: String): Double? =
        perMinute.firstOrNull { (prefix, _) -> model.startsWith(prefix) }?.second

    fun cost(model: String, seconds: Double): Double? = perMinute(model)?.let { it * seconds / 60.0 }
}
