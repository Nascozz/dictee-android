package com.nascozz.dictee.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

private val FieldShape = RoundedCornerShape(18.dp)

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
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Réglages")

        SectionCard(title = "Fournisseur") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    label = { Text("URL de base") },
                    placeholder = { Text("https://api.exemple.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = FieldShape,
                    colors = inkFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }

            if (provider == Provider.MISTRAL) {
                Spacer(Modifier.height(14.dp))
                Hairline()
                Spacer(Modifier.height(6.dp))
                SettingSwitch(
                    "Temps réel",
                    "Le texte s'écrit pendant que tu parles · Voxtral Realtime",
                    s.realtime,
                    store::setRealtime,
                )
            }

            Spacer(Modifier.height(18.dp))
            Hairline()
            Spacer(Modifier.height(18.dp))
            Eyebrow(if (s.useRealtime) "Modèle hors temps réel" else "Modèle", Modifier.padding(bottom = 10.dp))
            var model by remember(provider) { mutableStateOf(s.modelFor(provider)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.models.forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = { model = m; store.setModel(provider, m) },
                        label = { Text(m) },
                        shape = RoundedCornerShape(50),
                        colors = inkChipColors(),
                        border = inkChipBorder(model == m),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; store.setModel(provider, it) },
                label = { Text("Ou un autre modèle") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = FieldShape,
                colors = inkFieldColors(),
            )
        }

        SectionCard(title = "Langue") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LANGUAGES.forEach { (code, label) ->
                    FilterChip(
                        selected = s.language == code,
                        onClick = { store.setLanguage(code) },
                        label = { Text(label) },
                        shape = RoundedCornerShape(50),
                        colors = inkChipColors(),
                        border = inkChipBorder(s.language == code),
                    )
                }
            }
            Text(
                "Choisir ta langue améliore la précision.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (provider.supportsPrompt) {
            SectionCard(title = "Vocabulaire") {
                var prompt by remember { mutableStateOf(s.prompt) }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; store.setPrompt(it) },
                    placeholder = { Text("Noms propres, jargon… ex : Guillaume, Kotlin, Spokenly") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = FieldShape,
                    colors = inkFieldColors(),
                )
            }
        }

        SectionCard(title = "Île") {
            SettingSwitch("Afficher l'île", "Quand un champ de texte est actif", s.bubbleEnabled, store::setBubbleEnabled)
            Hairline()
            SettingSwitch("Vibrations", "Au début et à la fin de la dictée", s.haptics, store::setHaptics)
            Hairline()
            SettingSwitch("Espaces automatiques", "Autour du texte dicté si besoin", s.autoSpace, store::setAutoSpace)
        }

        SectionCard(title = "Mises à jour") {
            SettingSwitch("Automatiques", "Installe les nouvelles versions publiées", s.autoUpdate, store::setAutoUpdate)
            Hairline()
            Spacer(Modifier.height(14.dp))
            val update by Updater.state.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall.merge(TabularNums))
                    Text(
                        when (val u = update) {
                            Updater.State.Checking -> "Vérification…"
                            Updater.State.UpToDate -> "À jour"
                            is Updater.State.Available -> "Version ${u.release.versionName} disponible"
                            is Updater.State.Downloading -> "Téléchargement ${(u.progress * 100).toInt()} %"
                            is Updater.State.Installing -> "Installation…"
                            is Updater.State.Failed -> u.message
                            Updater.State.Idle -> "github.com/${Updater.REPO}"
                        },
                        style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                        color = Ink.Muted,
                    )
                }
                val available = update as? Updater.State.Available
                if (available != null) {
                    PillButton("Installer") { Updater.install(ctx, available.release) }
                } else {
                    GhostButton("Vérifier") { Updater.check() }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ProviderRow(p: Provider, selected: Boolean, hasKey: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Ink.SurfaceHigh else Color.Transparent, label = "bg")
    val border by animateColorAsState(if (selected) Ink.LineStrong else Color.Transparent, label = "border")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) StatusDot(Ink.Red, size = 10.dp)
            else Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, Ink.Faint, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.titleSmall, color = if (selected) Ink.Text else Ink.Muted)
            Text(p.tagline, style = MaterialTheme.typography.bodySmall, color = Ink.Faint)
        }
        if (hasKey) {
            Text(
                "CLÉ",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Green,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Ink.Green.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
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
            StatusDot(Ink.Green)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Clé API enregistrée", style = MaterialTheme.typography.titleSmall)
                Text("•••• $hint", style = MaterialTheme.typography.bodySmall.merge(TabularNums), color = Ink.Muted)
            }
            GhostButton("Modifier") { editing = true }
            IconButton(onClick = { store.setApiKey(p, null) }) {
                Icon(Icons.Rounded.Delete, contentDescription = "Supprimer la clé", tint = Ink.Muted)
            }
        }
    } else {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Clé API ${p.label}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = FieldShape,
            colors = inkFieldColors(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Afficher",
                        tint = Ink.Muted,
                    )
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Enregistrer", enabled = input.isNotBlank()) {
                store.setApiKey(p, input)
                input = ""
                editing = false
            }
            if (hasKey) GhostButton("Annuler") { editing = false; input = "" }
            Spacer(Modifier.weight(1f))
            if (p.keyUrl.isNotEmpty()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl))) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Obtenir une clé", style = MaterialTheme.typography.labelLarge, color = Ink.RedSoft)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = Ink.RedSoft, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}
