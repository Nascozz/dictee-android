package com.nascozz.dictee.audio

import java.io.File
import java.io.RandomAccessFile

/** Transforme un enregistrement PCM 16 bits mono brut en fichier WAV lisible par les API de transcription. */
object WavFile {
    fun fromPcm(pcm: File, out: File, sampleRate: Int = PcmStreamer.SAMPLE_RATE): File {
        val dataLen = pcm.length()
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            f.write(header(dataLen, sampleRate))
            pcm.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    f.write(buf, 0, n)
                }
            }
        }
        return out
    }

    private fun header(dataLen: Long, rate: Int): ByteArray {
        val byteRate = rate * 2
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return "RIFF".toByteArray() + le32(36 + dataLen) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(rate.toLong()) + le32(byteRate.toLong()) +
            le16(2) + le16(16) + "data".toByteArray() + le32(dataLen)
    }
}
