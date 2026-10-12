package com.nascozz.dictee.update

import android.content.Context
import com.nascozz.dictee.BuildConfig
import com.nascozz.dictee.Http
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * « Nouveautés » : au premier lancement d'une nouvelle version, un petit récap de ce qui a changé.
 * Le texte est celui de la Release GitHub (gardé au moment de la mise à jour, sinon récupéré).
 */
object WhatsNew {
    data class Notes(val title: String, val points: List<String>)

    private const val PREFS = "whats_new"
    private const val SEEN = "seen_version"
    private const val NOTES_CODE = "notes_code"
    private const val NOTES = "notes"

    private val client = Http.base.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /** Appelé juste avant d'installer une mise à jour : on garde ses notes pour après. */
    fun remember(context: Context, versionCode: Int, notes: String) {
        prefs(context).edit().putInt(NOTES_CODE, versionCode).putString(NOTES, notes).apply()
    }

    /** Les nouveautés à montrer maintenant, ou null (déjà vues, ou première installation). */
    suspend fun pending(context: Context): Notes? {
        val p = prefs(context)
        val current = BuildConfig.VERSION_CODE
        if (!p.contains(SEEN)) {
            // Première installation : rien à annoncer. Mise à jour depuis une version sans ce récap : on l'affiche.
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (info.lastUpdateTime <= info.firstInstallTime) {
                markSeen(context)
                return null
            }
        } else if (p.getInt(SEEN, 0) >= current) {
            return null
        }
        val raw = if (p.getInt(NOTES_CODE, -1) == current) p.getString(NOTES, null) else null
        return parse(raw ?: fetch() ?: return null)
    }

    fun markSeen(context: Context) {
        prefs(context).edit().putInt(SEEN, BuildConfig.VERSION_CODE).remove(NOTES).remove(NOTES_CODE).apply()
    }

    private suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/${Updater.REPO}/releases/tags/v${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else JSONObject(resp.body!!.string()).optString("body").ifBlank { null }
            }
        }.getOrNull()
    }

    /** Message de commit → titre + points (« - … »), sans les lignes techniques. */
    fun parse(raw: String): Notes? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("Co-Authored-By", ignoreCase = true) }
        if (lines.isEmpty()) return null
        val points = lines.drop(1).map { it.removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }
        return Notes(lines.first(), points)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
