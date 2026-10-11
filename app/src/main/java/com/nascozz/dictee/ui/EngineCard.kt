package com.nascozz.dictee.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascozz.dictee.Graph
import com.nascozz.dictee.stt.Pricing
import com.nascozz.dictee.stt.MistralRealtime
import com.nascozz.dictee.stt.Provider

private val FieldShape = RoundedCornerShape(18.dp)

/**
 * Le « moteur » de la dictée, sur l'accueil : fournisseur, mode, modèle et clé API.
 * Tout est visible d'un coup d'œil et se change sur place.
 */
@Composable
fun EngineCard(modifier: Modifier = Modifier) {
    val store = Graph.settings
    val s by store.state.collectAsStateWithLifecycle()
    val provider = s.provider

    SectionCard(title = "Moteur", modifier = modifier) {
        // Fournisseurs en une rangée qui défile ; point vert = clé enregistrée.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Provider.entries.forEach { p ->
                val selected = p == provider
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Ink.RedTint else Ink.Bg)
                        .border(1.dp, if (selected) Ink.Red.copy(alpha = 0.6f) else Ink.LineStrong, RoundedCornerShape(50))
                        .clickable { store.setProvider(p) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (s.hasKey(p)) {
                        StatusDot(Ink.Green, size = 6.dp)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        if (p == Provider.CUSTOM) "Autre" else p.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Ink.RedSoft else Ink.Muted,
                    )
                }
            }
        }
        Text(provider.tagline, style = MaterialTheme.typography.bodySmall, color = Ink.Faint, modifier = Modifier.padding(top = 8.dp))

        if (provider == Provider.MISTRAL) {
            Spacer(Modifier.height(14.dp))
            Segmented(
                listOf(true to "Temps réel", false to "Classique"),
                s.realtime,
                store::setRealtime,
            )
            Text(
                if (s.realtime) "Le texte s'écrit pendant que tu parles" else "Le texte arrive quand tu as fini de parler",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (!s.useRealtime) {
            Spacer(Modifier.height(14.dp))
            var model by remember(provider) { mutableStateOf(s.modelFor(provider)) }
            var custom by remember(provider) { mutableStateOf(model !in provider.models) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (provider.models.map { it to it } + ("" to "Autre…")).forEach { (m, label) ->
                    val selected = if (m.isEmpty()) custom else !custom && model == m
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Ink.Text else Ink.Muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Ink.SurfaceHigh else Ink.Bg)
                            .border(1.dp, if (selected) Ink.LineStrong else Ink.Line, RoundedCornerShape(50))
                            .clickable {
                                if (m.isEmpty()) custom = true else {
                                    custom = false
                                    model = m
                                    store.setModel(provider, m)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            AnimatedVisibility(custom) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; store.setModel(provider, it) },
                    label = { Text("Nom du modèle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    shape = FieldShape,
                    colors = inkFieldColors(),
                )
            }
        }

        // Tarif du réglage actuel, pour savoir ce que coûte une minute de dictée.
        val currentModel = if (s.useRealtime) MistralRealtime.MODEL else s.modelFor(provider)
        Pricing.perMinute(currentModel)?.let { price ->
            Text(
                String.format(java.util.Locale.FRANCE, if (price >= 0.001) "≈ %.3f $ par minute de dictée" else "≈ %.4f $ par minute de dictée", price),
                style = MaterialTheme.typography.labelSmall.merge(TabularNums),
                color = Ink.Faint,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Hairline()
        Spacer(Modifier.height(14.dp))
        ApiKeyEditor(provider, s.hasKey(provider))
        if (provider == Provider.CUSTOM) {
            Spacer(Modifier.height(12.dp))
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
    }
}

@Composable
fun ApiKeyEditor(p: Provider, hasKey: Boolean) {
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
