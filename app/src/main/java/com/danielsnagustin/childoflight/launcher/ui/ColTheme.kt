package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.ThemeMode
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import kotlinx.coroutines.delay

/** Ancho máximo del contenido en pantallas grandes (tablet / horizontal): las tarjetas y botones no se estiran a todo el ancho. En teléfonos (< 720 dp) no hace nada. */
val ContentMaxWidth: Dp = 720.dp

/**
 * Altura de la vista previa del panel táctil según la altura disponible:
 * en pantallas bajas (teléfono en horizontal, ~360-400 dp de alto) se
 * achica a 200 dp para que quepan la cabecera y los botones sin tanto
 * scroll; en vertical se mantiene en 300 dp. Puro: testeable en JVM
 * (DownloadsAndLayoutTest). El arrastre usa fracciones, así que el
 * tamaño no cambia la posición guardada.
 */
fun previewHeightForScreen(maxHeight: Dp): Dp = if (maxHeight < 500.dp) 200.dp else 300.dp

// --- Paleta Dark Fantasy Premium (2026-11-08): AMOLED #10131D,
// azul hielo como acento principal. Sustituye por completo a la
// paleta anterior: la nueva identidad no usa tonos cálidos en
// ninguna parte de la UI.
// - Azul hielo #8CCBEB: acciones principales y acento por defecto.
// - Azul lavanda #6578B8 y lavanda #A99ACD: selecciones y secundarios.
// - Turquesa #75D6CE: estados activos y confirmaciones.
// - Plata #DCE8F5: textos destacados y superficies claras.
// - Fondo AMOLED #10131D (sustituye al negro #000000).
// El texto es blanco o gris azulado (#B8C2D4): nunca gris oscuro
// sobre fondo oscuro, para no perder legibilidad.
val ColNight = Color(0xFF10131D)
val ColAccent = Color(0xFF8CCBEB)
val ColIceBlue = Color(0xFF8CCBEB)
val ColLavenderBlue = Color(0xFF6578B8)
val ColLavender = Color(0xFFA99ACD)
val ColTurquoise = Color(0xFF75D6CE)
val ColSilver = Color(0xFFDCE8F5)
val ColBlue = Color(0xFF8CCBEB)
val ColCard = Color(0xFF232A44)

// --- Superficies ---
// Tarjetas sobre el fondo #10131D: un azul noche apenas más claro,
// con borde azul hielo sutil.
val ColCardElevated = Color(0xFF1A2036)
val ColMutedText = Color(0xFFB8C2D4)

// Paleta clara (Tema Claro): fondo y tarjetas claras, texto oscuro.
val ColBackgroundLight = Color(0xFFE9EFF6)
val ColCardLight = Color(0xFFFFFFFF)
val ColCardElevatedLight = Color(0xFFF1F5FA)
val ColMutedTextLight = Color(0xFF54657E)
val ColTextLight = Color(0xFF1B2436)

/**
 * Personalización que funciona (2026-11-09, 0.2.15): el acento y el
 * tema elegidos por el usuario NO se veían en el inicio porque
 * HomeScreen pintaba colores fijos (ColAccent/ColNight/ColCard) y el
 * MaterialTheme solo tiñía los interruptores. Estos helpers son el
 * único sitio donde se resuelven: el acento viene de UiPreferences
 * (o azul hielo por defecto) y el tema sigue la misma regla que
 * LauncherContent en MainActivity (Claro/Oscuro/Sistema).
 */
fun userAccent(uiPrefs: UiPreferences?): Color = try {
    Color(android.graphics.Color.parseColor(uiPrefs?.accent?.hex ?: "#8CCBEB"))
} catch (_: Exception) {
    ColIceBlue
}

@Composable
fun isDarkUi(uiPrefs: UiPreferences?): Boolean = when (uiPrefs?.theme) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM, null -> isSystemInDarkTheme()
}

/** Texto secundario según el tema (legible en ambos fondos). */
fun mutedTextColor(dark: Boolean): Color = if (dark) ColMutedText else ColMutedTextLight

/** Texto principal sobre tarjeta según el tema. */
fun cardTextColor(dark: Boolean): Color = if (dark) Color.White else ColTextLight

/** Superficie de tarjeta según el tema. */
fun cardColor(dark: Boolean): Color = if (dark) ColCard else ColCardLight

/** Superficie elevada de tarjeta según el tema. */
fun cardElevatedColor(dark: Boolean): Color = if (dark) ColCardElevated else ColCardElevatedLight

/**
 * Texto sobre el acento: oscuro si el acento es claro (azul hielo,
 * lavanda, turquesa, plata), blanco si es oscuro (azul lavanda).
 * Brillo simple 0.299/0.587/0.114; el umbral 0.6 deja blanco solo
 * para el azul lavanda #6578B8.
 */
@Composable
fun onAccentColor(accent: Color = MaterialTheme.colorScheme.primary): Color {
    val lum = 0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue
    return if (lum > 0.6f) ColNight else Color.White
}

/** Fondo AMOLED #10131D (sustituye al negro puro #000000). */
fun premiumBackgroundBrush(): Brush = Brush.verticalGradient(
    listOf(ColNight, ColNight),
)

/** Fondo de la Home: el tinte elegido por el usuario (casi negro) arriba → AMOLED #10131D abajo. En tema claro, fondo claro (los tintes guardados son oscuros). */
fun homeBackgroundBrush(baseHex: String?, dark: Boolean): Brush {
    if (!dark) return Brush.verticalGradient(listOf(ColBackgroundLight, Color(0xFFDCE8F5)))
    val base = try {
        Color(android.graphics.Color.parseColor(baseHex ?: "#10131D"))
    } catch (_: Exception) {
        ColNight
    }
    return Brush.verticalGradient(listOf(base, ColNight))
}

fun homeBackgroundBrush(baseHex: String?): Brush = homeBackgroundBrush(baseHex, true)

/**
 * Tarjeta premium: superficie azul noche (#1A2036) con borde azul hielo sutil. Sin sombra: sobre #10131D no se ve.
 *
 * Ola 3, Etapa 6: tamaño y transparencia reales desde UiPreferences.
 * Los defaults son el comportamiento actual (padding 16 dp, radio 20 dp,
 * opaco), así que los call sites que no pasan nada no cambian.
 */
@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cardSize: CardSize = CardSize.NORMAL,
    cardTransparency: CardTransparency = CardTransparency.OPAQUE,
    content: @Composable () -> Unit,
) {
    // Borde con el acento del usuario (0.2.15): antes ColAccent fijo.
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    val shape = RoundedCornerShape(cardSize.cornerDp.dp)
    val containerColor = ColCardElevated.copy(alpha = cardTransparency.alpha)
    val innerPadding = cardSize.paddingDp.dp
    if (onClick != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = border,
            onClick = onClick,
        ) {
            Column(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = border,
        ) {
            Column(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    }
}

/** Encabezado de pantalla: título grande + subtítulo pequeño en gris. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
    )
    if (subtitle != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = ColMutedText,
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
}

// ---------------------------------------------------------------------------
// Movimiento (2026-11-07): la interfaz se sentía estática. Animaciones
// baratas y de una sola vez (aparición, pulsación, progreso); solo dos
// infinitas y lentas: el brillo del banner de la Home y el pulso de
// "Importando…", que solo existe mientras se importa. Nada de bucles
// rápidos ni animaciones que mantengan la GPU despierta.
// ---------------------------------------------------------------------------

/** Aparición progresiva: fundido + subida suave con retardo. Una sola vez al componer.
 * play = false muestra el contenido directamente (la intro de Home solo se
 * reproduce una vez por sesión; en las vueltas siguientes no se espera). */
@Composable
fun FadeSlideIn(delayMillis: Int, play: Boolean = true, content: @Composable () -> Unit) {
    if (!play) {
        content()
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis.toLong())
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(450, easing = FastOutSlowInEasing)) +
            slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(450, easing = FastOutSlowInEasing),
            ),
    ) {
        content()
    }
}

/** Vibración ligera al tocar: el toque se siente al instante aunque el
 * trabajo que dispara tarde un poco. Respeta el ajuste de vibración del
 * sistema (si está apagada, no hace nada). */
@Composable
fun rememberTapHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/** Pulso suave de alpha (0.55 ↔ 1) para textos de progreso. Solo mientras se muestra. */
@Composable
fun PulsingText(text: String, color: Color, style: androidx.compose.ui.text.TextStyle) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.alpha(alpha),
    )
}

/** Botón principal (solo para lo importante: JUGAR, acciones clave). El color es el acento del usuario: LauncherContent ya pone el acento como primary del MaterialTheme, así que el botón lo lee de ahí (0.2.15; antes era ColAccent fijo y el acento no se veía). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val tap = rememberTapHaptic()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "primaryPress",
    )
    Button(
        onClick = { tap(); onClick() },
        modifier = modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        interactionSource = interactionSource,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = onAccentColor(),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * Botón con subtítulo (Fase 2, Ola 1): JUGAR con el Title ID
 * y la resolución debajo, e icono vectorial a la izquierda. El icono
 * va en el botón, nunca en la cadena de texto. Color: el acento del
 * usuario (0.2.15; antes ColAccent fijo).
 */
@Composable
fun PrimaryButtonWithSubtitle(
    text: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable () -> Unit = { PlayIcon(tint = onAccentColor()) },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val tap = rememberTapHaptic()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "primaryPressSubtitle",
    )
    Button(
        onClick = { tap(); onClick() },
        modifier = modifier.fillMaxWidth().height(64.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        interactionSource = interactionSource,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = onAccentColor(),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(modifier = Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


/**
 * Variante de tarjeta con borde azul hielo sutil (Fase 2, Ola 1):
 * mismo fondo que PremiumCard, con el acento ColBlue. Para tarjetas
 * informativas; JUGAR y lo importante usan el botón principal azul
 * hielo.
 */
@Composable
fun AquaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(1.dp, ColBlue.copy(alpha = 0.35f))
    if (onClick != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColCardElevated),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = border,
            onClick = onClick,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColCardElevated),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = border,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

/** Botón secundario: contorno claro, sin tonos cálidos. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val tap = rememberTapHaptic()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "secondaryPress",
    )
    OutlinedButton(
        onClick = { tap(); onClick() },
        modifier = modifier.fillMaxWidth().height(52.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        interactionSource = interactionSource,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** Botón "Volver" discreto al final de cada pantalla. */
@Composable
fun BackButton(onBack: () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    SecondaryButton("Volver", onBack)
    Spacer(modifier = Modifier.height(24.dp))
}

/** Barra de progreso azul hielo (logros). Anima el progreso al cambiar (800 ms), no salta. */
@Composable
fun PrimaryProgressBar(progress: Float, modifier: Modifier = Modifier) {
    // Antes era LinearProgressIndicator: con progreso 0 dibujaba un
    // punto azul hielo (la tapa redondeada del indicador) y parecía un error
    // de la barra. Barra propia: sin punto cuando no hay progreso.
    val p = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = p,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "primaryProgress",
    )
    Box(
        modifier = modifier.fillMaxWidth().height(8.dp)
            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth(animated).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun NoteText(text: String) {
    // R2-P1-07: color fijo ColMutedText (gris azulado legible sobre el
    // fondo AMOLED #10131D fijo). Antes usaba
    // MaterialTheme.colorScheme.onSurfaceVariant, que con Tema Claro es
    // gris oscuro sobre fondo oscuro: ilegible.
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ColMutedText,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            // R2-P1-07: mismo arreglo que NoteText: ColMutedText fijo en
            // vez de onSurfaceVariant (ilegible con Tema Claro sobre el
            // fondo AMOLED fijo).
            Text(description, style = MaterialTheme.typography.bodySmall, color = ColMutedText)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> EnumChips(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        // B1 (auditoría UI): antes era un Row sin scroll: los chips largos
        // (MemoryMapping, Backend, resoluciones) se recortaban y quedaban
        // inaccesibles. FlowRow los envuelve y todos quedan visibles.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tap = rememberTapHaptic()
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { tap(); onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}

@Composable
fun SettingSlider(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    valueLabel: (Int) -> String = { "$it" },
) {
    // B5 (auditoría UI): antes cada tick del slider escribía en DataStore
    // (jank). Ahora el valor vive en estado local mientras se arrastra y
    // solo se guarda al soltar (onValueChangeFinished). La etiqueta sí
    // muestra el valor en vivo. Sin cambios en los call sites: onValueChange
    // ahora se dispara al soltar en vez de en cada tick.
    var local by remember(value) { mutableStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("$title: ${valueLabel(local)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Slider(
            value = local.toFloat(),
            onValueChange = { local = it.toInt() },
            onValueChangeFinished = { onValueChange(local) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}
