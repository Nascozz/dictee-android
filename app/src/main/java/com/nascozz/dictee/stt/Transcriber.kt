package com.nascozz.dictee.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class SttException(message: String) : Exception(message)

data class SttRequest(
    val provider: Provider,
    val apiKey: String,
    val model: String,
    /** Code ISO 639-1, ou null pour la détection automatique. */
    val language: String?,
    val prompt: String?,
    val customBaseUrl: String,
)

object Transcriber {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val AUDIO = "audio/mp4".toMediaType()

    suspend fun transcribe(audio: File, r: SttRequest): String = withContext(Dispatchers.IO) {
        val request = buildRequest(audio, r)
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw SttException(errorMessage(resp.code, body))
                parse(r.provider.shape, body).trim()
            }
        } catch (e: UnknownHostException) {
            throw SttException("Pas de connexion internet")
        } catch (e: SocketTimeoutException) {
            throw SttException("Le serveur met trop de temps à répondre")
        } catch (e: IOException) {
            throw SttException("Erreur réseau : ${e.message}")
        }
    }

    private fun buildRequest(audio: File, r: SttRequest): Request {
        val base = (if (r.provider == Provider.CUSTOM) r.customBaseUrl else r.provider.baseUrl).trimEnd('/')
        if (base.isBlank()) throw SttException("Renseigne l'URL de l'API dans les réglages")
        val fileBody = audio.asRequestBody(AUDIO)
        return when (r.provider.shape) {
            ApiShape.OPENAI_COMPATIBLE -> {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.m4a", fileBody)
                    .addFormDataPart("model", r.model)
                r.language?.let { form.addFormDataPart("language", it) }
                if (r.provider.supportsPrompt && !r.prompt.isNullOrBlank()) form.addFormDataPart("prompt", r.prompt)
                Request.Builder().url("$base/audio/transcriptions")
                    .header("Authorization", "Bearer ${r.apiKey}")
                    .post(form.build()).build()
            }
            ApiShape.ELEVENLABS -> {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.m4a", fileBody)
                    .addFormDataPart("model_id", r.model)
                    .addFormDataPart("tag_audio_events", "false")
                r.language?.let { form.addFormDataPart("language_code", it) }
                Request.Builder().url("$base/speech-to-text")
                    .header("xi-api-key", r.apiKey)
                    .post(form.build()).build()
            }
            ApiShape.DEEPGRAM -> {
                val url = "$base/listen".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("model", r.model)
                    .addQueryParameter("smart_format", "true")
                    .addQueryParameter("punctuate", "true")
                    .apply {
                        if (r.language != null) addQueryParameter("language", r.language)
                        else addQueryParameter("detect_language", "true")
                    }.build()
                Request.Builder().url(url)
                    .header("Authorization", "Token ${r.apiKey}")
                    .post(fileBody).build()
            }
        }
    }

    private fun parse(shape: ApiShape, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body
        return when (shape) {
            ApiShape.OPENAI_COMPATIBLE, ApiShape.ELEVENLABS -> json.optString("text")
            ApiShape.DEEPGRAM -> json.optJSONObject("results")
                ?.optJSONArray("channels")?.optJSONObject(0)
                ?.optJSONArray("alternatives")?.optJSONObject(0)
                ?.optString("transcript").orEmpty()
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            when (val err = o.opt("error") ?: o.opt("detail") ?: o.opt("message") ?: o.opt("err_msg")) {
                is JSONObject -> err.optString("message").ifBlank { err.toString() }
                null -> body
                else -> err.toString()
            }
        }.getOrDefault(body).take(180)
        val prefix = when (code) {
            401, 403 -> "Clé API refusée"
            404 -> "Modèle ou URL introuvable"
            413 -> "Enregistrement trop long"
            429 -> "Limite atteinte (quota ou trop de requêtes)"
            in 500..599 -> "Le fournisseur a un problème"
            else -> "Erreur"
        }
        return if (detail.isBlank()) "$prefix ($code)" else "$prefix ($code) : $detail"
    }
}
