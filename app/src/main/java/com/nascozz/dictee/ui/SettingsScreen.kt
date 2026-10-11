package com.nascozz.dictee.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascozz.dictee.BuildConfig
import com.nascozz.dictee.Graph
import com.nascozz.dictee.stt.Provider
import com.nascozz.dictee.update.Updater

private val LANGUAGES = listOf(
    "auto" to "Auto",
    "fr" to "Français",
    "en" to "English",
    "es" to "Español",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
    "ar" to "العربية",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val store = Graph.settings
    val s by store.state.collectAsStateWithLifecycle()
    val provider = s.provider

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Réglages")

        SectionCard(title = "Fournisseur") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.entries.forEach { p ->
                    ProviderRow(p, selected = p == provider, hasKey = s.hasKey(p)) { store.setProvider(p) }
                }
            }
        }

        SectionCard(title = provider.label) {
            ApiKeyEditor(provider, s.hasKey(provider))

            if (provider == Provider.CUSTOM) {
                Spacer(Modifier.height(16.dp))
                var url by remember { mutableStateOf(s.customBaseUrl) }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; store.setCustomBaseUrl(it) },
                    label = { Text("URL de base (ex. https://api.exemple.com/v1)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }

            Spacer(Modifier.height(20.dp))
            var model by remember(provider) { mutableStateOf(s.modelFor(provider)) }
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; store.setModel(provider, it) },
                label = { Text("Modèle") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.models.forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = { model = m; store.setModel(provider, m) },
                        label = { Text(m) },
                    )
                }
            }
        }

        SectionCard(title = "Langue") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LANGUAGES.forEach { (code, label) ->
                    FilterChip(
                        selected = s.language == code,
                        onClick = { store.setLanguage(code) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Choisir ta langue améliore la précision. « Auto » détecte toute seule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (provider.supportsPrompt) {
            SectionCard(title = "Vocabulaire") {
                var prompt by remember { mutableStateOf(s.prompt) }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; store.setPrompt(it) },
                    placeholder = { Text("Noms propres, jargon, style… ex : Guillaume, Kotlin, Spokenly") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }

        SectionCard(title = "Bulle") {
            SettingSwitch("Afficher la bulle", "Sur les champs de texte quand le clavier est ouvert", s.bubbleEnabled, store::setBubbleEnabled)
            SettingSwitch("Vibrations", "Petit retour quand ça démarre et se termine", s.haptics, store::setHaptics)
            SettingSwitch("Espaces automatiques", "Ajoute un espace avant/après le texte dicté si besoin", s.autoSpace, store::setAutoSpace)
        }

        SectionCard(title = "Mises à jour") {
            SettingSwitch(
                "Mise à jour automatique",
                "Installe les nouvelles versions publiées sur GitHub",
                s.autoUpdate,
                store::setAutoUpdate,
            )
            Spacer(Modifier.height(8.dp))
            val update by Updater.state.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (val u = update) {
                            Updater.State.Checking -> "Vérification…"
                            Updater.State.UpToDate -> "Tu as la dernière version"
                            is Updater.State.Available -> "Version ${u.release.versionName} disponible"
                            is Updater.State.Downloading -> "Téléchargement… ${(u.progress * 100).toInt()} %"
                            is Updater.State.Installing -> "Installation…"
                            is Updater.State.Failed -> u.message
                            Updater.State.Idle -> "github.com/${Updater.REPO}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val available = update as? Updater.State.Available
                if (available != null) {
                    Button(onClick = { Updater.install(ctx, available.release) }) { Text("Installer") }
                } else {
                    OutlinedButton(onClick = { Updater.check() }) { Text("Vérifier") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProviderRow(p: Provider, selected: Boolean, hasKey: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "border",
    )
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent,
        label = "bg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(if (selected) 2.dp else 1.dp, border), RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.titleSmall)
            Text(p.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hasKey) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "Clé enregistrée", tint = Success, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ApiKeyEditor(p: Provider, hasKey: Boolean) {
    val ctx = LocalContext.current
    val store = Graph.settings
    var editing by remember(p, hasKey) { mutableStateOf(!hasKey) }
    var input by remember(p) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    if (hasKey && !editing) {
        val hint = remember(p, hasKey) { store.apiKey(p)?.takeLast(4).orEmpty() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Clé API enregistrée", style = MaterialTheme.typography.bodyLarge)
                Text("•••• $hint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { editing = true }) { Text("Modifier") }
            IconButton(onClick = { store.setApiKey(p, null) }) {
                Icon(Icons.Rounded.Delete, contentDescription = "Supprimer la clé")
            }
        }
    } else {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Clé API ${p.label}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = "Afficher")
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    store.setApiKey(p, input)
                    input = ""
                    editing = false
                },
                enabled = input.isNotBlank(),
            ) { Text("Enregistrer") }
            if (hasKey) {
                TextButton(onClick = { editing = false; input = "" }) { Text("Annuler") }
            }
            Spacer(Modifier.weight(1f))
            if (p.keyUrl.isNotEmpty()) {
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl))) }) {
                    Text("Obtenir une clé")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
