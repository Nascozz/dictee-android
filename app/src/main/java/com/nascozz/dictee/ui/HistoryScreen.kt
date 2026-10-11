package com.nascozz.dictee.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascozz.dictee.Graph
import com.nascozz.dictee.data.HistoryEntry
import com.nascozz.dictee.data.ModelUsage
import com.nascozz.dictee.data.UsageStore
import com.nascozz.dictee.stt.MistralRealtime
import com.nascozz.dictee.stt.Pricing
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

@Composable
fun HistoryScreen() {
    val items by Graph.history.items.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenTitle("Activité")
            Spacer(Modifier.height(10.dp))
            UsageCard()
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Historique")
                    if (items.isNotEmpty()) {
                        Text(
                            "${items.size} dictée${if (items.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                            color = Ink.Faint,
                        )
                    }
                }
                if (items.isNotEmpty()) GhostButton("Tout effacer") { Graph.history.clear() }
            }
        }
        if (items.isEmpty()) item { EmptyHistory() }
        items(items, key = { it.id }) { HistoryCard(it) }
    }
}

// --- Utilisation et coût -----------------------------------------------------------------------

@Composable
private fun UsageCard() {
    val ctx = LocalContext.current
    val days by Graph.usage.days.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val monthKey = today.toString().substring(0, 7)
    val month = UsageStore.byModel(days.filterKeys { it.startsWith(monthKey) }.values)
    val total = UsageStore.byModel(days.values)
    val monthCost = UsageStore.cost(month)
    val monthSeconds = month.values.sumOf { it.seconds }
    val monthCount = month.values.sumOf { it.count }
    val last14 = (13 downTo 0).map { UsageStore.cost(days[today.minusDays(it.toLong()).toString()].orEmpty()) }
    val monthName = today.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)

    SectionCard {
        Eyebrow("Ce mois-ci · $monthName")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(usd(monthCost), style = MaterialTheme.typography.displaySmall.merge(TabularNums))
            Spacer(Modifier.width(8.dp))
            Text("estimé", style = MaterialTheme.typography.bodyMedium, color = Ink.Faint, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("de voix", duration(monthSeconds), Modifier.weight(1f))
            Stat("dictées", monthCount.toString(), Modifier.weight(1f))
            Stat("par minute", if (monthSeconds > 0) usd(monthCost / (monthSeconds / 60)) else "—", Modifier.weight(1f))
        }

        // Tarif du mode en cours : change dès qu'on bascule le temps réel ou qu'on change de modèle.
        val currentModel = if (settings.useRealtime) MistralRealtime.MODEL else settings.modelFor(settings.provider)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Ink.Red, size = 6.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Tarif actuel · ${prettyModel(currentModel)}",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                Pricing.perMinute(currentModel)?.let { usd(it) + "/min" } ?: "prix inconnu",
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = Ink.Text,
            )
        }

        Spacer(Modifier.height(20.dp))
        CostBars(last14)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("il y a 2 sem.", style = MaterialTheme.typography.labelSmall, color = Ink.Faint, modifier = Modifier.weight(1f))
            Text("aujourd'hui", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        }

        if (month.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Hairline()
            Spacer(Modifier.height(8.dp))
            month.entries
                .sortedByDescending { (m, u) -> Pricing.cost(m, u.seconds) ?: 0.0 }
                .forEach { (model, u) -> ModelRow(model, u) }
        }

        Spacer(Modifier.height(10.dp))
        Hairline()
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Depuis le début", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(usd(UsageStore.cost(total)), style = MaterialTheme.typography.titleSmall.merge(TabularNums))
        }
        val provider = settings.provider
        if (provider.billingUrl.isNotEmpty()) {
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.billingUrl))) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Consommation officielle ${provider.label}", style = MaterialTheme.typography.labelLarge, color = Ink.RedSoft)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = Ink.RedSoft, modifier = Modifier.size(15.dp))
            }
        }
        Text(
            "Estimation d'après la durée de tes dictées et les tarifs publics. Le montant exact est sur la console du fournisseur.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.SurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium.merge(TabularNums), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
    }
}

/** Coût des 14 derniers jours, en barres façon onde : aujourd'hui en rouge vif. */
@Composable
private fun CostBars(values: List<Double>) {
    val peak = max(values.maxOrNull() ?: 0.0, 1e-9)
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val n = values.size
        val step = size.width / n
        val w = step * 0.42f
        values.forEachIndexed { i, v ->
            val x = step * (i + 0.5f)
            val h = if (v <= 0) 3.dp.toPx() else max(6.dp.toPx(), (v / peak).toFloat() * size.height)
            val color = when {
                v <= 0 -> Ink.Line
                i == n - 1 -> Ink.Red
                else -> Ink.Red.copy(alpha = 0.45f)
            }
            drawLine(color, Offset(x, size.height - h + w / 2), Offset(x, size.height - w / 2), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ModelRow(model: String, u: ModelUsage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(prettyModel(model), style = MaterialTheme.typography.titleSmall)
            Text(
                "${duration(u.seconds)} · ${u.count} dictée${if (u.count > 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = Ink.Faint,
            )
        }
        val cost = Pricing.cost(model, u.seconds)
        Text(
            cost?.let(::usd) ?: "prix inconnu",
            style = MaterialTheme.typography.titleSmall.merge(TabularNums),
            color = if (cost == null) Ink.Faint else Ink.Text,
        )
    }
}

private fun prettyModel(m: String) = when {
    m.startsWith("voxtral-mini-transcribe-realtime") -> "Voxtral · temps réel"
    else -> m
}

/** Les dictées coûtent quelques millièmes de dollar : on garde assez de décimales pour voir chacune compter. */
private fun usd(v: Double): String = when {
    v <= 0.0 -> "0 $"
    v < 0.001 -> String.format(Locale.FRANCE, "%.4f $", v)
    v < 1.0 -> String.format(Locale.FRANCE, "%.3f $", v)
    else -> String.format(Locale.FRANCE, "%.2f $", v)
}

private fun duration(seconds: Double): String {
    val s = seconds.toLong()
    return if (s >= 3600) "%dh%02d".format(s / 3600, (s % 3600) / 60) else "%d:%02d".format(s / 60, s % 60)
}

// --- Historique --------------------------------------------------------------------------------

@Composable
private fun EmptyHistory() {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 120.dp, height = 40.dp)
                .clip(RoundedCornerShape(50))
                .background(Ink.Surface)
                .border(1.dp, Ink.Line, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { StatusDot(Ink.Faint) }
        Spacer(Modifier.height(20.dp))
        Text("Rien pour l'instant", style = MaterialTheme.typography.titleMedium)
        Text("Tes dictées apparaîtront ici.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.Surface)
            .border(1.dp, Ink.Line, RoundedCornerShape(24.dp))
            .padding(start = 18.dp, end = 6.dp, top = 16.dp, bottom = 4.dp)
    ) {
        Text(entry.text, style = MaterialTheme.typography.bodyLarge, color = Ink.Text, modifier = Modifier.padding(end = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Ink.Red.copy(alpha = 0.7f), size = 6.dp)
            Spacer(Modifier.width(8.dp))
            val sec = entry.durationMs / 1000
            Text(
                "${sec / 60}:%02d · ".format(sec % 60) +
                    DateUtils.getRelativeTimeSpanString(entry.id).toString() + " · ${entry.provider}",
                style = MaterialTheme.typography.labelSmall.merge(TabularNums),
                color = Ink.Faint,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(entry.text))
                Toast.makeText(ctx, "Copié", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copier", tint = Ink.Muted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { Graph.history.delete(entry.id) }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer", tint = Ink.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}
