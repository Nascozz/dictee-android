package com.nascozz.dictee.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Capture le micro en PCM 16 bits mono 16 kHz et livre de petits morceaux (~80 ms)
 * au fil de l'eau, pour la transcription en temps réel.
 */
class PcmStreamer(private val onChunk: (ByteArray) -> Unit) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var lastLevel = 0f
    private var startedAt = 0L

    val durationMs get() = SystemClock.elapsedRealtime() - startedAt

    @SuppressLint("MissingPermission") // vérifié par l'appelant
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, CHUNK_BYTES * 4),
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return false
        }
        record = r
        running = true
        startedAt = SystemClock.elapsedRealtime()
        thread = Thread({ loop(r) }, "dictee-pcm").apply { start() }
        return true
    }

    private fun loop(r: AudioRecord) {
        val buf = ByteArray(CHUNK_BYTES)
        while (running) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) continue
            lastLevel = levelOf(buf, n)
            onChunk(buf.copyOf(n))
        }
    }

    /** Niveau 0..1 du dernier morceau, même courbe que l'enregistreur classique. */
    fun level(): Float = lastLevel

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    private fun levelOf(b: ByteArray, n: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < n) {
            val s = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
            peak = max(peak, abs(s.toShort().toInt()))
            i += 2
        }
        return min(1f, sqrt(peak / 32767f) * 1.5f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms de son : 16000 échantillons/s × 2 octets × 0,08 s. */
        const val CHUNK_BYTES = 2560
    }
}
