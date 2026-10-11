package com.nascozz.dictee.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

/** Enregistre en AAC mono 16 kHz (.m4a) : léger à envoyer et accepté par tous les fournisseurs. */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null

    fun start(): Boolean {
        cancel()
        val f = File(context.cacheDir, "dictation-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(48_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            r.release()
            f.delete()
            false
        }
    }

    /** Niveau sonore 0..1, avec une courbe adoucie pour que la voix normale remplisse bien les barres. */
    fun level(): Float {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return min(1f, sqrt(amp / 32767f) * 1.5f)
    }

    /** Arrête l'enregistrement. Renvoie le fichier et la durée, ou null si c'était trop court ou vide. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        recorder = null
        val duration = SystemClock.elapsedRealtime() - startedAt
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file.also { file = null } ?: return null
        if (!ok || duration < 400) {
            f.delete()
            return null
        }
        return f to duration
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }
}
