package com.nascozz.dictee.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.nascozz.dictee.BuildConfig
import com.nascozz.dictee.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mises à jour via GitHub Releases : chaque push sur `main` publie un APK signé,
 * l'app le détecte, le télécharge et l'installe par-dessus elle-même.
 */
object Updater {
    const val REPO = "Nascozz/dictee-android"

    data class Release(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var lastCheckAt = 0L

    /** Version disponible, si la dernière vérification en a trouvé une. */
    val available: Release? get() = (_state.value as? State.Available)?.release

    fun check() {
        if (job?.isActive == true) return
        val before = _state.value
        if (before is State.Downloading || before is State.Installing) return
        job = Graph.appScope.launch {
            // Pas de « Vérification… » si une version est déjà annoncée : la bannière ne clignote pas.
            if (before !is State.Available) _state.value = State.Checking
            _state.value = try {
                val r = fetchLatest()
                lastCheckAt = System.currentTimeMillis()
                if (r != null && r.versionCode > BuildConfig.VERSION_CODE) State.Available(r) else State.UpToDate
            } catch (e: Exception) {
                if (before is State.Available) before else State.Failed("Impossible de vérifier les mises à jour")
            }
        }
    }

    /** Vérifie seulement si la dernière vérification date de plus de [maxAgeMs]. */
    fun checkIfStale(maxAgeMs: Long) {
        if (System.currentTimeMillis() - lastCheckAt > maxAgeMs) check()
    }

    /**
     * [reopen] : rouvrir Dictée après l'installation. Vrai depuis l'écran ; faux depuis l'île,
     * pour ne pas te sortir de l'appli où tu écrivais.
     */
    fun install(context: Context, release: Release, reopen: Boolean = true) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        if (reopen) Relauncher.request(app)
        WhatsNew.remember(app, release.versionCode, release.notes)
        job = Graph.appScope.launch {
            try {
                downloadAndInstall(app, release)
            } catch (e: Exception) {
                _state.value = State.Failed("Échec de la mise à jour : ${e.message}")
            }
        }
    }

    fun onInstallFinished(success: Boolean, message: String?) {
        if (!success) _state.value = State.Failed(message ?: "Mise à jour annulée")
    }

    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) error("GitHub ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            val tag = json.getString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return@withContext null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            Release(code, tag.removePrefix("v"), json.optString("body"), apk.getString("browser_download_url"))
        }
    }

    suspend fun downloadAndInstall(context: Context, release: Release) {
        _state.value = State.Downloading(release, 0f)
        val apk = download(context, release)
        _state.value = State.Installing(release)
        withContext(Dispatchers.IO) { commit(context, apk) }
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "update.apk")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("téléchargement ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0 && read - lastEmit > 256 * 1024) {
                            lastEmit = read
                            _state.value = State.Downloading(release, read.toFloat() / total)
                        }
                    }
                }
            }
        }
        out
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+ : la mise à jour peut s'installer sans confirmation quand c'est l'app qui se met à jour elle-même.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallReceiver::class.java), flags
            )
            session.commit(pending.intentSender)
        }
    }
}
