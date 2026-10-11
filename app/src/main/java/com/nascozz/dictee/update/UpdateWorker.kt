package com.nascozz.dictee.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nascozz.dictee.BuildConfig
import com.nascozz.dictee.Graph
import com.nascozz.dictee.service.DictationService
import java.util.concurrent.TimeUnit

/** Vérifie GitHub toutes les quelques heures et installe la nouvelle version en arrière-plan. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Graph.settings.current.autoUpdate) return Result.success()
        return try {
            val release = Updater.fetchLatest() ?: return Result.success()
            if (release.versionCode <= BuildConfig.VERSION_CODE) return Result.success()
            // Ne jamais couper une dictée en cours : on réessaiera plus tard.
            if (DictationService.busy) return Result.retry()
            Updater.downloadAndInstall(applicationContext, release)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("auto-update", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
