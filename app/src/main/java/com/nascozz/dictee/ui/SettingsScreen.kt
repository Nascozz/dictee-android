package com.nascozz.dictee.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascozz.dictee.BuildConfig
import com.nascozz.dictee.Graph
import com.nascozz.dictee.stt.TextFormatter
import com.nascozz.dictee.update.Updater
import java.util.Locale
import kotlin.math.roundToInt

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

/** Ce que renvoie le modèle + des commandes dictées : l'exemple de l'aperçu. */
private const val PREVIEW_RAW = "Salut, ça va ? On se voit demain vers 20h. Ramène à boire virgule merci point à la ligne bisous"

/**
 * Une seule page, sans sous-menus : chaque réglage est visible et se change sur place.
 * Le fournisseur, le modèle et la clé sont sur l'accueil (carte « Moteur »).
 */
@Composable
fun SettingsScreen() {
    val store = Graph.settings
    val s by store.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Réglages")
        Text(
            "Fournisseur, modèle et clé API sont sur l'accueil.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Faint,
        )

        // --- Texte -------------------------------------------------------------------------
        SectionCard(title = "Texte") {
            Preview(TextFormatter.format(PREVIEW_RAW, s.formatOptions).trim())
            Spacer(Modifier.height(16.dp))

            Label("Ponctuation")
            Segmented(listOf(false to "Normale", true to "Minimale"), s.minimalPunctuation, store::setMinimalPunctuation)
            Spacer(Modifier.height(14.dp))

            Label("Les points deviennent")
            Segmented(listOf(0 to "Des points", 1 to "À la ligne", 2 to "Ligne vide"), s.periodBreaks, store::setPeriodBreaks)
            Spacer(Modifier.height(8.dp))

            Hairline()
            SettingSwitch("Commandes vocales", "Dis « virgule », « point », « à la ligne »…", s.voiceCommands, store::setVoiceCommands)
            AnimatedVisibility(s.voiceCommands) { CommandList() }
            Hairline()
            SettingSwitch(
                "À la ligne après un silence",
                "En temps réel, une pause termine la phrase",
                s.pauseBreak,
                store::setPauseBreak,
            )
            AnimatedVisibility(s.pauseBreak) { PauseSlider(s.pauseSeconds, store::setPauseSeconds) }
            Hairline()
            SettingSwitch("Espaces automatiques", "Autour du texte dicté si besoin", s.autoSpace, store::setAutoSpace)
        }

        // --- Langue -------------------------------------------------------------------------
        SectionCard(title = "Langue") {
            LanguageChips(s.language, store::setLanguage)
            if (s.provider.supportsPrompt && !s.useRealtime) {
                Spacer(Modifier.height(14.dp))
                var prompt by remember { mutableStateOf(s.prompt) }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; store.setPrompt(it) },
                    label = { Text("Vocabulaire") },
                    placeholder = { Text("Noms propres, jargon… ex : Guillaume, Kotlin") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(18.dp),
                    colors = inkFieldColors(),
                )
            }
        }

        // --- Île ----------------------------------------------------------------------------
        SectionCard(title = "Île") {
            SettingSwitch("Afficher l'île", "Quand un champ de texte est actif", s.bubbleEnabled, store::setBubbleEnabled)
            Hairline()
            SettingSwitch("Arrêter à l'envoi", "Envoyer le message coupe l'écoute", s.stopOnSend, store::setStopOnSend)
            Hairline()
            SettingSwitch("Vibrations", "Au début et à la fin de la dictée", s.haptics, store::setHaptics)
        }

        // --- Mises à jour -------------------------------------------------------------------
        SectionCard(title = "Mises à jour") {
            UpdateRow()
            Hairline()
            SettingSwitch("Automatiques", "En arrière-plan, sans rien faire", s.autoUpdate, store::setAutoUpdate)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
}

/** Le texte d'exemple tel qu'il sera écrit avec les réglages actuels, en bulle de message. */
@Composable
private fun Preview(formatted: String) {
    Text("Aperçu", style = MaterialTheme.typography.labelSmall, color = Ink.Faint, modifier = Modifier.padding(bottom = 6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
            .background(Ink.SurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(formatted, style = MaterialTheme.typography.bodyLarge, color = Ink.Text)
    }
}

@Composable
private fun CommandList() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            if (open) "Masquer les commandes" else "Voir les commandes",
            style = MaterialTheme.typography.labelLarge,
            color = Ink.RedSoft,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { open = !open }
                .padding(vertical = 4.dp),
        )
        AnimatedVisibility(open) {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Ink.SurfaceHigh)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "virgule" to ",",
                    "point" to ".",
                    "point d'interrogation" to "?",
                    "point d'exclamation" to "!",
                    "deux points" to ":",
                    "point-virgule" to ";",
                    "points de suspension" to "…",
                    "à la ligne" to "↵",
                    "nouveau paragraphe" to "↵↵",
                ).forEach { (say, sign) ->
                    Row {
                        Text("« $say »", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.weight(1f))
                        Text(sign, style = MaterialTheme.typography.titleSmall, color = Ink.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseSlider(seconds: Float, onChange: (Float) -> Unit) {
    var pause by remember { mutableFloatStateOf(seconds) }
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Silence de", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.weight(1f))
            Text(String.format(Locale.FRANCE, "%.1f s", pause), style = MaterialTheme.typography.titleSmall.merge(TabularNums))
        }
        Slider(
            value = pause,
            onValueChange = { pause = (it * 2).roundToInt() / 2f },
            onValueChangeFinished = { onChange(pause) },
            valueRange = 1f..5f,
            steps = 7,
            colors = SliderDefaults.colors(
                thumbColor = Ink.Red,
                activeTrackColor = Ink.Red,
                inactiveTrackColor = Ink.SurfaceHigh,
                activeTickColor = Ink.RedSoft,
                inactiveTickColor = Ink.LineStrong,
            ),
        )
        Row {
            Text("Coupe souvent", style = MaterialTheme.typography.labelSmall, color = Ink.Faint, modifier = Modifier.weight(1f))
            Text("Vraies pauses seulement", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGUAGES.forEach { (code, label) ->
            FilterChip(
                selected = selected == code,
                onClick = { onSelect(code) },
                label = { Text(label) },
                shape = RoundedCornerShape(50),
                colors = inkChipColors(),
                border = inkChipBorder(selected == code),
            )
        }
    }
    Text(
        "Choisir ta langue améliore la précision.",
        style = MaterialTheme.typography.bodySmall,
        color = Ink.Faint,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun UpdateRow() {
    val ctx = LocalContext.current
    val update by Updater.state.collectAsStateWithLifecycle()
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall.merge(TabularNums))
            Text(
                when (val u = update) {
                    Updater.State.Checking -> "Vérification…"
                    Updater.State.UpToDate -> "Tu as la dernière version"
                    is Updater.State.Available -> "Version ${u.release.versionName} disponible"
                    is Updater.State.Downloading -> "Téléchargement ${(u.progress * 100).toInt()} %"
                    is Updater.State.Installing -> "Installation…"
                    is Updater.State.Failed -> u.message
                    Updater.State.Idle -> "github.com/${Updater.REPO}"
                },
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = if (update is Updater.State.Available) Ink.RedSoft else Ink.Muted,
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}/releases")))
                },
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
