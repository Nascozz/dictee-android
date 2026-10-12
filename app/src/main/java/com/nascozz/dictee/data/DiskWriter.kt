package com.nascozz.dictee.data

import java.io.File
import java.util.concurrent.Executors

/**
 * Écritures disque de l'historique et du suivi d'utilisation : sur un thread à part (jamais sur le
 * thread de l'interface ou de l'île), une à la fois et dans l'ordre.
 */
object DiskWriter {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dictee-disk").apply { priority = Thread.MIN_PRIORITY } }

    fun write(file: File, content: String) {
        executor.execute {
            runCatching {
                // Écriture atomique : un fichier temporaire puis renommage, pour ne jamais laisser un fichier à moitié écrit.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(file)) {
                    file.writeText(content)
                    tmp.delete()
                }
            }
        }
    }
}
