package com.nascozz.dictee.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascozz.dictee.Graph
import com.nascozz.dictee.service.DictationService
import com.nascozz.dictee.update.Updater

private data class SetupStatus(
    val mic: Boolean,
    val accessibility: Boolean,
    val notifications: Boolean,
    val installs: Boolean,
) {
    companion object {
        fun read(ctx: Context) = SetupStatus(
            mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            accessibility = DictationService.isEnabled(ctx),
            notifications = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            installs = ctx.packageManager.canRequestPackageInstalls(),
        )
    }
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(SetupStatus.read(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = SetupStatus.read(ctx) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = SetupStatus.read(ctx)
    }
    val hasKey = settings.hasKey(settings.provider)
    val ready = status.mic && status.accessibility && hasKey

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Hero(ready, "${settings.provider.label} · ${settings.modelFor(settings.provider)}")
        UpdateBanner()

        SectionCard(title = if (ready) "Tout est prêt" else "Configuration") {
            SetupStep(
                icon = Icons.Rounded.Mic,
                title = "Micro",
                subtitle = "Pour entendre ta voix",
                done = status.mic,
                action = "Autoriser",
            ) { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            SetupStep(
                icon = Icons.Rounded.Key,
                title = "Clé API ${settings.provider.label}",
                subtitle = if (hasKey) "Enregistrée et chiffrée sur ce téléphone" else "Pour transcrire ta voix",
                done = hasKey,
                action = "Ajouter",
                onAction = onOpenSettings,
            )
            SetupStep(
                icon = Icons.Rounded.Accessibility,
                title = "Bulle flottante",
                subtitle = "Active « Bulle Dictée » dans Accessibilité",
                done = status.accessibility,
                action = "Activer",
            ) { openAccessibilitySettings(ctx) }
            AnimatedVisibility(!status.accessibility) {
                Column(
                    Modifier
                        .padding(start = 52.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        "Option grisée (« Paramètre restreint ») ? Ouvre les infos de l'appli, touche ⋮ en haut à droite, " +
                            "puis « Autoriser les paramètres restreints ». Reviens ensuite activer la bulle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = { openAppDetails(ctx) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Ouvrir les infos de l'appli")
                    }
                }
            }
            SetupStep(
                icon = Icons.Rounded.SystemUpdate,
                title = "Mises à jour automatiques",
                subtitle = "Autoriser Dictée à installer ses nouvelles versions",
                done = status.installs,
                action = "Autoriser",
            ) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                )
            }
            if (Build.VERSION.SDK_INT >= 33) {
                SetupStep(
                    icon = Icons.Rounded.Notifications,
                    title = "Notifications",
                    subtitle = "Optionnel : être prévenu des mises à jour",
                    done = status.notifications,
                    action = "Autoriser",
                ) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }

        SectionCard(title = "Essaie ici") {
            var text by remember { mutableStateOf("") }
            Text(
                "Touche le champ : la bulle apparaît au-dessus, à droite. Touche-la, parle, puis touche-la encore. " +
                    "Appui long pour annuler.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ton texte apparaîtra ici…") },
                minLines = 3,
                shape = RoundedCornerShape(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Hero(ready: Boolean, subtitle: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(BrandGradient)
            .padding(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Dictée", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("Parle, ça s'écrit.", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ready) Color(0xFF7CFFB2) else Color(0xFFFFD27C)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (ready) "Prêt · $subtitle" else "Encore quelques réglages",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    icon: ImageVector,
    title: String,
    subtitle: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (done) Success.copy(alpha = 0.16f) else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (done) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = if (done) Success else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val state by Updater.state.collectAsStateWithLifecycle()
    val release = when (val s = state) {
        is Updater.State.Available -> s.release
        is Updater.State.Downloading -> s.release
        is Updater.State.Installing -> s.release
        else -> null
    } ?: return

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(20.dp)
    ) {
        Text(
            "Nouvelle version ${release.versionName}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (release.notes.isNotBlank()) {
            Text(
                release.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        when (val s = state) {
            is Updater.State.Downloading -> LinearProgressIndicator(
                progress = { s.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            is Updater.State.Installing -> Text("Installation…", color = MaterialTheme.colorScheme.onPrimaryContainer)
            else -> Button(onClick = { Updater.install(ctx, release) }) { Text("Mettre à jour") }
        }
    }
}

private fun openAccessibilitySettings(ctx: Context) {
    val component = ComponentName(ctx, DictationService::class.java).flattenToString()
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        // Sur beaucoup de téléphones, ces extras mettent directement en avant notre service.
        putExtra(":settings:fragment_args_key", component)
        putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
    }
    ctx.startActivity(intent)
}

private fun openAppDetails(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}
