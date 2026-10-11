package com.nascozz.dictee

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.nascozz.dictee.data.HistoryStore
import com.nascozz.dictee.data.SettingsStore
import com.nascozz.dictee.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DicteeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Graph.UPDATE_CHANNEL, "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT)
        )
        UpdateWorker.schedule(this)
    }
}

/** Dépendances partagées entre l'interface, la bulle et les tâches de fond (même processus). */
object Graph {
    const val UPDATE_CHANNEL = "updates"

    lateinit var settings: SettingsStore
        private set
    lateinit var history: HistoryStore
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        settings = SettingsStore(context)
        history = HistoryStore(context)
    }
}
