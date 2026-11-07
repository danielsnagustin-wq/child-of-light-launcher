package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val ColNight = Color(0xFF0E1420)
val ColGold = Color(0xFFF2B705)
val ColBlue = Color(0xFF7FB3D5)
val ColCard = Color(0xFF1A2436)

// --- Paleta premium (auditoría + rediseño 2026-11-07) ---
// Degradado noche → violeta como el arte de Child of Light (cielo,
// estrellas, aurora). Dorado solo para lo importante (JUGAR, progreso).
val ColViolet = Color(0xFF241A33)
val ColVioletDeep = Color(0xFF171226)
val ColCardElevated = Color(0xFF232F47)
val ColMutedText = Color(0xFFB8C2D4)
val ColSuccessGreen = Color(0xFF6FCF97)

/** Degradado premium de fondo: azul noche arriba → violeta abajo. */
fun premiumBackgroundBrush(): Brush = Brush.verticalGradient(
    listOf(ColNight, ColVioletDeep, ColViolet),
)

/** Degradado de fondo respetando el color elegido por el usuario. */
fun homeBackgroundBrush(baseHex: String?): Brush {
    val base = try {
        Color(android.graphics.Color.parseColor(baseHex ?: "#0E1420"))
    } catch (_: Exception) {
        ColNight
    }
    return Brush.verticalGradient(listOf(base, ColVioletDeep, ColViolet))
}

/** Tarjeta premium: superficie elevada, bordes grandes, sombra suave. */
@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColCardElevated),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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

/** Botón principal dorado (solo para lo importante: JUGAR, acciones clave). */
@Composable
fun GoldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ColGold,
            contentColor = Color.Black,
            disabledContainerColor = ColCard,
            disabledContentColor = ColMutedText,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Botón secundario: contorno claro, sin amarillo. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
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

/** Barra de progreso dorada (logros). */
@Composable
fun GoldProgressBar(progress: Float, modifier: Modifier = Modifier) {
    // Antes era LinearProgressIndicator: con progreso 0 dibujaba un
    // punto dorado (la tapa redondeada del indicador) y parecía un error
    // de la barra. Barra propia: sin punto cuando no hay progreso.
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier.fillMaxWidth().height(8.dp)
            .clip(RoundedCornerShape(4.dp)).background(ColCard),
    ) {
        if (p > 0f) {
            Box(
                modifier = Modifier.fillMaxWidth(p).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(ColGold),
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
        color = ColGold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
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
