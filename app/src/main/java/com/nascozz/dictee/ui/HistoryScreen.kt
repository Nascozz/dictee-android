package com.nascozz.dictee.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nascozz.dictee.Graph
import com.nascozz.dictee.data.HistoryEntry

@Composable
fun HistoryScreen() {
    val items by Graph.history.items.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                ScreenTitle("Historique", Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    GhostButton("Tout effacer", Modifier.padding(bottom = 10.dp)) { Graph.history.clear() }
                }
            }
            if (items.isNotEmpty()) {
                val totalSec = items.sumOf { it.durationMs } / 1000
                Text(
                    "${items.size} dictée${if (items.size > 1) "s" else ""} · ${totalSec / 60}:%02d de voix".format(totalSec % 60),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = Ink.Muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        if (items.isEmpty()) item { EmptyHistory() }
        items(items, key = { it.id }) { HistoryCard(it) }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        Modifier.fillMaxWidth().padding(top = 90.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Une île au repos, vide.
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
