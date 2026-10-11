package com.nascozz.dictee.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nascozz.dictee.update.Relauncher
import com.nascozz.dictee.update.Updater
import com.nascozz.dictee.update.WhatsNew

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { DicteeTheme { AppRoot() } }
        Relauncher.done(this)
    }

    override fun onResume() {
        super.onResume()
        // À chaque retour dans l'app : y a-t-il une nouvelle version ? (au plus toutes les 2 min)
        Updater.checkIfStale(2 * 60 * 1000L)
    }
}

@Composable
private fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Accueil" to Icons.Rounded.Mic,
        "Réglages" to Icons.Rounded.Tune,
        "Activité" to Icons.Rounded.History,
    )
    val ctx = LocalContext.current
    var notes by remember { mutableStateOf<WhatsNew.Notes?>(null) }
    LaunchedEffect(Unit) { notes = WhatsNew.pending(ctx) }
    notes?.let {
        WhatsNewDialog(it) {
            WhatsNew.markSeen(ctx)
            notes = null
        }
    }
    Scaffold(
        containerColor = Ink.Bg,
        bottomBar = {
            Column {
                Hairline()
                NavigationBar(containerColor = Ink.Bg, tonalElevation = 0.dp) {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Ink.Text,
                                selectedTextColor = Ink.Text,
                                indicatorColor = Ink.SurfaceHigh,
                                unselectedIconColor = Ink.Faint,
                                unselectedTextColor = Ink.Faint,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tabs",
            ) { current ->
                when (current) {
                    0 -> HomeScreen(onOpenSettings = { tab = 1 })
                    1 -> SettingsScreen()
                    else -> HistoryScreen()
                }
            }
        }
    }
}
