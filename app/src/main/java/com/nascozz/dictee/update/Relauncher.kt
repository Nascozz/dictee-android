package com.nascozz.dictee.update

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.nascozz.dictee.Graph
import com.nascozz.dictee.R
import com.nascozz.dictee.ui.MainActivity

/**
 * Android ferme toujours une appli qui se met à jour. Quand la mise à jour a été lancée
 * depuis l'écran (« Mettre à jour »), on rouvre Dictée tout de suite après l'installation.
 */
object Relauncher {
    private const val PREFS = "update"
    private const val KEY = "relaunch_requested_at"
    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val NOTIF_ID = 43

    fun request(context: Context) {
        prefs(context).edit().putLong(KEY, System.currentTimeMillis()).apply()
    }

    /** Vrai si l'utilisateur vient de lancer une mise à jour depuis l'écran. */
    fun pending(context: Context): Boolean {
        val at = prefs(context).getLong(KEY, 0L)
        return at > 0 && System.currentTimeMillis() - at < WINDOW_MS
    }

    /** L'écran s'est rouvert : plus rien à faire. */
    fun done(context: Context) {
        prefs(context).edit().remove(KEY).apply()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun relaunch(context: Context) {
        if (!pending(context)) return
        runCatching { context.startActivity(openIntent(context)) }
    }

    /** Filet de sécurité si Android bloque l'ouverture automatique. */
    fun notifyUpdated(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            context, 2, openIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, Graph.UPDATE_CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Dictée est à jour")
            .setContentText("Touche pour l'ouvrir")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun openIntent(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Reçu par la nouvelle version juste après son installation. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED || !Relauncher.pending(context)) return
        Relauncher.notifyUpdated(context)
        Relauncher.relaunch(context)
    }
}
