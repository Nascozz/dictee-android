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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascozz.dictee.Graph
import com.nascozz.dictee.service.DictationService
import com.nascozz.dictee.update.Updater
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineRequester = remember { BringIntoViewRequester() }
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(SetupStatus.read(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = SetupStatus.read(ctx) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = SetupStatus.read(ctx)
    }
    val hasKey = settings.hasKey(settings.provider)
    val ready = status.mic && status.accessibility && hasKey
    val steps = listOf(status.mic, hasKey, status.accessibility, status.installs)
    val allDone = steps.all { it }
    // Une fois tout configuré, la checklist se replie en une ligne ; on peut la rouvrir.
    var setupOpen by rememberSaveable(allDone) { mutableStateOf(!allDone) }
    val modeLabel = if (settings.useRealtime) "${settings.provider.label} · temps réel"
    else "${settings.provider.label} · ${settings.modelFor(settings.provider)}"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            ScreenTitle("Dictée")
            Spacer(Modifier.width(12.dp))
            Text(
                "Parle. Ça s'écrit.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Muted,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        Showcase(ready, modeLabel)
        UpdateBanner()
        EngineCard(Modifier.bringIntoViewRequester(engineRequester))

        SectionCard {
            SetupHeader(allDone, "${steps.count { it }}/${steps.size}", setupOpen) { setupOpen = !setupOpen }
            AnimatedVisibility(setupOpen) {
                Column(Modifier.padding(top = 6.dp)) {
                    SetupStep(Icons.Rounded.Mic, "Micro", "Pour entendre ta voix", status.mic, "Autoriser") {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    SetupStep(
                        Icons.Rounded.Key,
                        "Clé API ${settings.provider.label}",
                        if (hasKey) "Chiffrée sur ce téléphone" else "Pour transcrire ta voix",
                        hasKey,
                        "Ajouter",
                        onAction = { scope.launch { engineRequester.bringIntoView() } },
                    )
                    SetupStep(
                        Icons.Rounded.Accessibility,
                        "Île de dictée",
                        "Active « Bulle Dictée » dans Accessibilité",
                        status.accessibility,
                        "Activer",
                    ) { openAccessibilitySettings(ctx) }
                    AnimatedVisibility(!status.accessibility) {
                        Column(
                            Modifier
                                .padding(start = 52.dp, top = 2.dp, bottom = 10.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Ink.SurfaceHigh)
                                .clickable { openAppDetails(ctx) }
                                .padding(14.dp)
                        ) {
                            Text(
                                "Option grisée ? Infos de l'appli → ⋮ → « Autoriser les paramètres restreints », puis reviens l'activer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.Muted,
                            )
                            Text(
                                "Ouvrir les infos de l'appli",
                                style = MaterialTheme.typography.labelLarge,
                                color = Ink.RedSoft,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    SetupStep(
                        Icons.Rounded.SystemUpdate,
                        "Mises à jour auto",
                        "Installer les nouvelles versions",
                        status.installs,
                        "Autoriser",
                    ) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        SetupStep(
                            Icons.Rounded.Notifications,
                            "Notifications",
                            "Optionnel · prévenir des mises à jour",
                            status.notifications,
                            "Autoriser",
                        ) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
            }
        }

        SectionCard(title = "Essaie ici") {
            var text by remember { mutableStateOf("") }
            Text(
                "Touche le champ, puis l'île en haut. Parle, touche encore. Appui long pour annuler.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Muted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ton texte apparaîtra ici…") },
                minLines = 2,
                shape = RoundedCornerShape(18.dp),
                colors = inkFieldColors(),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Vitrine : l'île en action sur fond noir, avec un halo rouge, et l'état de l'app dessous. */
@Composable
private fun Showcase(ready: Boolean, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Ink.Surface)
            .border(1.dp, Ink.Line, CardShape)
            .padding(vertical = 18.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 260.dp, height = 84.dp).background(RedGlow))
            IslandDemo()
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Ink.SurfaceHigh)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (ready) Ink.Green else Ink.Amber, pulse = !ready)
            Spacer(Modifier.width(8.dp))
            Text(
                if (ready) "Prêt · $subtitle" else "Encore quelques réglages",
                style = MaterialTheme.typography.labelLarge,
                color = Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** En-tête de la checklist : une seule ligne « terminée » quand tout est prêt, cliquable pour déplier. */
@Composable
private fun SetupHeader(done: Boolean, progress: String, open: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Ink.Green.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = Ink.Green, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(10.dp))
            Text("Configuration terminée", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        } else {
            Eyebrow("Configuration · $progress", Modifier.weight(1f))
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = if (open) "Replier" else "Déplier",
            tint = Ink.Muted,
            modifier = Modifier.rotate(rotation),
        )
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
                .background(if (done) Ink.Green.copy(alpha = 0.12f) else Ink.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (done) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = if (done) Ink.Green else Ink.Text,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (done) Ink.Muted else Ink.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Faint)
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            PillButton(action, onClick = onAction)
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
            .clip(CardShape)
            .background(Ink.Surface)
            .border(1.dp, Ink.Red.copy(alpha = 0.45f), CardShape)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Ink.Red, pulse = true)
            Spacer(Modifier.width(10.dp))
            Eyebrow("Nouvelle version ${release.versionName}", color = Ink.RedSoft)
        }
        if (release.notes.isNotBlank()) {
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Muted,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        when (val s = state) {
            is Updater.State.Downloading -> LinearProgressIndicator(
                progress = { s.progress },
                color = Ink.Red,
                trackColor = Ink.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            is Updater.State.Installing -> Text("Installation…", style = MaterialTheme.typography.labelLarge, color = Ink.Text)
            else -> PillButton("Mettre à jour") { Updater.install(ctx, release) }
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
