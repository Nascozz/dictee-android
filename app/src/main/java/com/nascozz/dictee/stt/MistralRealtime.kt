package com.nascozz.dictee.stt

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transcription en temps réel avec Voxtral Realtime (Mistral), via WebSocket.
 *
 * Protocole (repris du SDK officiel mistralai) :
 *  → connexion  wss://api.mistral.ai/v1/audio/transcriptions/realtime?model=…  (Authorization: Bearer …)
 *  ← session.created
 *  → session.update        { audio_format: { encoding: pcm_s16le, sample_rate }, target_streaming_delay_ms }
 *  → input_audio.append    { audio: base64 } … à répétition
 *  → input_audio.flush, input_audio.end
 *  ← transcription.text.delta { text } … puis transcription.done { text }   (ou error)
 *
 * Tous les rappels du [Listener] arrivent sur le thread principal.
 */
class MistralRealtime(
    private val apiKey: String,
    private val listener: Listener,
    private val model: String = MODEL,
    private val delayMs: Int = DEFAULT_DELAY_MS,
) {
    interface Listener {
        fun onDelta(text: String)
        fun onDone(finalText: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var socket: WebSocket? = null
    private var ready = false
    private var ended = false
    private var finished = false
    private val pending = ArrayList<String>()
    private val transcript = StringBuilder()

    fun start() {
        val request = Request.Builder()
            .url("wss://api.mistral.ai/v1/audio/transcriptions/realtime?model=$model")
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = when (response?.code) {
                    401, 403 -> "Clé API Mistral refusée"
                    429 -> "Limite Mistral atteinte"
                    null -> "Connexion au temps réel impossible : ${t.message ?: "réseau"}"
                    else -> "Temps réel indisponible (${response.code})"
                }
                fail(msg)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Fermeture sans « done » : on livre ce qu'on a reçu.
                finish(transcript.toString())
            }
        })
    }

    private fun handle(ws: WebSocket, raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("type")) {
            "session.created" -> {
                val update = JSONObject()
                    .put("type", "session.update")
                    .put(
                        "session",
                        JSONObject()
                            .put("audio_format", JSONObject().put("encoding", "pcm_s16le").put("sample_rate", SAMPLE_RATE))
                            .put("target_streaming_delay_ms", delayMs),
                    )
                ws.send(update.toString())
                synchronized(lock) {
                    ready = true
                    pending.forEach(ws::send)
                    pending.clear()
                    if (ended) sendEnd(ws)
                }
            }
            "transcription.text.delta" -> {
                val delta = json.optString("text")
                if (delta.isNotEmpty()) {
                    transcript.append(delta)
                    main.post { if (!finished) listener.onDelta(delta) }
                }
            }
            "transcription.done" -> {
                val text = json.optString("text").ifBlank { transcript.toString() }
                finish(text)
                ws.close(1000, null)
            }
            "error" -> {
                val err = json.opt("error")
                val msg = when (err) {
                    is JSONObject -> when (val m = err.opt("message")) {
                        is JSONObject -> m.optString("detail").ifBlank { m.toString() }
                        null -> err.toString()
                        else -> m.toString()
                    }
                    else -> err?.toString() ?: "erreur inconnue"
                }
                fail("Mistral : $msg")
            }
        }
    }

    /** Envoie un morceau de PCM 16 bits. Appelable depuis n'importe quel thread. */
    fun sendAudio(pcm: ByteArray) {
        val msg = JSONObject()
            .put("type", "input_audio.append")
            .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .toString()
        synchronized(lock) {
            if (ended) return
            val ws = socket ?: return
            if (ready) ws.send(msg) else pending.add(msg)
        }
    }

    /** Fin de la parole : le serveur termine la transcription puis envoie « done ». */
    fun endAudio() {
        synchronized(lock) {
            if (ended) return
            ended = true
            val ws = socket
            if (ready && ws != null) sendEnd(ws)
        }
        // Filet de sécurité si le serveur ne répond jamais.
        main.postDelayed({ finish(transcript.toString()) }, DONE_TIMEOUT_MS)
    }

    private fun sendEnd(ws: WebSocket) {
        ws.send(JSONObject().put("type", "input_audio.flush").toString())
        ws.send(JSONObject().put("type", "input_audio.end").toString())
    }

    fun cancel() {
        finished = true
        main.removeCallbacksAndMessages(null)
        socket?.cancel()
        socket = null
    }

    private fun finish(text: String) {
        main.post {
            if (finished) return@post
            finished = true
            main.removeCallbacksAndMessages(null)
            listener.onDone(text.trim())
        }
    }

    private fun fail(message: String) {
        main.post {
            if (finished) return@post
            finished = true
            main.removeCallbacksAndMessages(null)
            socket?.cancel()
            listener.onError(message)
        }
    }

    companion object {
        const val MODEL = "voxtral-mini-transcribe-realtime-2602"
        /** ~0,5 s : le bon compromis vitesse / précision d'après Mistral. */
        const val DEFAULT_DELAY_MS = 480
        private const val SAMPLE_RATE = 16_000
        private const val DONE_TIMEOUT_MS = 8_000L

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
