package com.nascozz.dictee.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nascozz.dictee.BuildConfig
import com.nascozz.dictee.update.WhatsNew
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

val CardShape = RoundedCornerShape(28.dp)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.displaySmall,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** Petit libellé de section en capitales espacées, discret. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Ink.Muted) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier)
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Ink.Surface)
            .border(BorderStroke(1.dp, Ink.Line), CardShape)
            .padding(20.dp)
    ) {
        if (title != null) Eyebrow(title, Modifier.padding(bottom = 14.dp))
        content()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.Line))
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Ink.Red,
            contentColor = Color.White,
            disabledContainerColor = Ink.SurfaceHigh,
            disabledContentColor = Ink.Faint,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        border = BorderStroke(1.dp, Ink.LineStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.Text),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Point de statut, façon point d'enregistrement ; il « respire » quand [pulse] est vrai. */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, pulse: Boolean = false) {
    if (!pulse) {
        Box(Modifier.size(size).clip(CircleShape).background(color))
        return
    }
    val now = animationClock()
    // Lu dans graphicsLayer : la pulsation ne fait que retoucher le calque, sans recomposer l'écran.
    Box(
        Modifier
            .size(size)
            .graphicsLayer { alpha = 0.45f + 0.55f * abs(sin(now.longValue / 485.0)).toFloat() }
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ink.Red,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Ink.SurfaceHigh,
                uncheckedThumbColor = Ink.Muted,
                uncheckedBorderColor = Ink.LineStrong,
            ),
        )
    }
}

@Composable
fun inkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink.Red,
    unfocusedBorderColor = Ink.LineStrong,
    focusedLabelColor = Ink.Red,
    unfocusedLabelColor = Ink.Muted,
    cursorColor = Ink.Red,
    focusedContainerColor = Ink.Bg,
    unfocusedContainerColor = Ink.Bg,
    focusedPlaceholderColor = Ink.Faint,
    unfocusedPlaceholderColor = Ink.Faint,
)

@Composable
fun inkChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Ink.Bg,
    labelColor = Ink.Muted,
    selectedContainerColor = Ink.RedTint,
    selectedLabelColor = Ink.RedSoft,
)

@Composable
fun inkChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = Ink.LineStrong,
    selectedBorderColor = Ink.Red.copy(alpha = 0.6f),
    borderWidth = 1.dp,
    selectedBorderWidth = 1.dp,
)

/**
 * Réplique animée de l'île en enregistrement : point rouge, chrono et onde.
 * Sert de vitrine en haut de l'accueil.
 */
@Composable
fun IslandDemo(modifier: Modifier = Modifier) {
    val now = animationClock()
    // Le chrono ne recompose qu'une fois par seconde ; le point et l'onde ne font que se redessiner.
    val seconds by remember { derivedStateOf { (now.longValue / 1000) % 60 } }

    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black)
            .border(1.dp, Ink.Line, RoundedCornerShape(50))
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .graphicsLayer { alpha = 0.5f + 0.5f * abs(sin(now.longValue / 380.0)).toFloat() }
                .clip(CircleShape)
                .background(Ink.Red)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            "0:%02d".format(seconds),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            style = TabularNums,
        )
        Spacer(Modifier.width(26.dp))
        Canvas(Modifier.width(120.dp).height(26.dp)) {
            val t = now.longValue / 1000.0
            val n = 22
            val step = size.width / n
            for (i in 0 until n) {
                // Une onde « parlée » : plusieurs sinus superposés qui défilent.
                val v = abs(sin(t * 5.1 + i * 0.55) * 0.55 + sin(t * 2.3 + i * 0.27) * 0.45).toFloat()
                val fade = 0.4f + 0.6f * (i / (n - 1f))
                val h = (0.12f + v * 0.88f) * size.height
                val x = step * (i + 0.5f)
                drawLine(
                    color = Ink.Red.copy(alpha = fade),
                    start = Offset(x, (size.height - h) / 2),
                    end = Offset(x, (size.height + h) / 2),
                    strokeWidth = step * 0.55f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

// --- Horloge d'animation partagée ----------------------------------------------------------------

/**
 * Une seule horloge pour toutes les animations décoratives (démo de l'île, points qui respirent) :
 * 30 images/s au lieu de la fréquence de l'écran (60 à 120), toutes synchronisées sur la même image,
 * et à l'arrêt quand aucune animation n'est affichée ou que l'app n'est pas visible.
 */
class AnimationClock {
    val now = mutableLongStateOf(0L)
    internal var users by mutableIntStateOf(0)
}

val LocalAnimationClock = staticCompositionLocalOf { AnimationClock() }

private const val CLOCK_FRAME_MS = 33L

@Composable
fun ProvideAnimationClock(content: @Composable () -> Unit) {
    val clock = remember { AnimationClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { clock.users > 0 }.collectLatest { active ->
                if (!active) return@collectLatest
                val start = SystemClock.uptimeMillis() - clock.now.longValue
                while (true) {
                    clock.now.longValue = SystemClock.uptimeMillis() - start
                    delay(CLOCK_FRAME_MS)
                }
            }
        }
    }
    CompositionLocalProvider(LocalAnimationClock provides clock, content = content)
}

/** S'abonne à l'horloge tant que l'appelant est affiché. À lire dans les phases de dessin uniquement. */
@Composable
fun animationClock(): LongState {
    val clock = LocalAnimationClock.current
    DisposableEffect(clock) {
        clock.users++
        onDispose { clock.users-- }
    }
    return clock.now
}

/** Halo rouge très doux derrière la vitrine. */
val RedGlow = Brush.radialGradient(listOf(Ink.Red.copy(alpha = 0.22f), Color.Transparent))

/** Petit récap des nouveautés, au premier lancement d'une nouvelle version. */
@Composable
fun WhatsNewDialog(notes: WhatsNew.Notes, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Le dialogue vit hors de l'écran principal : couleur de texte par défaut à fixer nous-mêmes.
        CompositionLocalProvider(LocalContentColor provides Ink.Text) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(Ink.Surface)
                    .border(1.dp, Ink.Line, CardShape)
                    .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Mini île : point rouge + version, comme la pilule de dictée.
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black)
                        .border(1.dp, Ink.Line, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(Ink.Red, pulse = true)
                    Spacer(Modifier.width(9.dp))
                    Text("Nouveautés", style = MaterialTheme.typography.labelLarge, color = Ink.Text)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                        color = Ink.RedSoft,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    notes.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink.Text,
                    textAlign = TextAlign.Center,
                )
                if (notes.points.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Ink.SurfaceHigh)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        notes.points.take(6).forEach { point ->
                            Row {
                                Box(Modifier.padding(top = 7.dp)) { StatusDot(Ink.Red, size = 6.dp) }
                                Spacer(Modifier.width(12.dp))
                                Text(point, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                PillButton("C'est noté", Modifier.fillMaxWidth().height(46.dp), onClick = onDismiss)
            }
        }
    }
}

/** Sélecteur compact à segments (2 à 3 choix), plus lisible qu'une rangée de pastilles. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Ink.Bg)
            .border(1.dp, Ink.LineStrong, RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Ink.RedTint else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Ink.RedSoft else Ink.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}
