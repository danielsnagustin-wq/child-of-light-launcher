package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielsnagustin.childoflight.launcher.R
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import com.danielsnagustin.childoflight.launcher.model.trophyProgressPercent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Logros: los 27 trofeos de la versión Vita, más ilustrativos.
 *
 * Rediseño 2026-11-07 (referencia del usuario: lista con cabecera y
 * filas con icono circular): cabecera con mini portada + "Child of
 * Light" + "X / 27 desbloqueados" con barra dorada; cada fila lleva un
 * icono circular azul acuarela pálido con un símbolo blanco dibujado
 * (estrella, luna, sol, corona...) según el trofeo, nombre y
 * descripción. Desbloqueado: color pleno. Bloqueado: atenuado + 🔒.
 * Secreto sin desbloquear: "Trofeo secreto / ???" con "?" en el icono.
 *
 * - Son logros LOCALES: no se sincronizan con PSN ni con Steam.
 * - No hay platino: el juego no tiene platino en PS4 ni en Vita.
 * - "Through the Looking Glass" avisa que puede no desbloquearse sin
 *   la función online de Vita (enviar Oculi a un amigo).
 * - Los nombres y descripciones son los 27 trofeos locales que ya lee
 *   la app (TrophyStoreReader): no se inventa ni se renombra ninguno.
 * - El popup de desbloqueo lo muestra MainActivity (vm.justUnlocked)
 *   con sonido de notificación del sistema.
 *
 * En Etapa 1 el desbloqueo se marca desde aquí (tocando el trofeo) o
 * al importar progreso. La detección automática dentro de la partida
 * requiere el núcleo embebido (Etapa 2).
 */
@Composable
fun AchievementsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val progress by vm.achievementsFlow.collectAsState(initial = emptyList())
    val scan by vm.trophyScan.collectAsState()
    var selected by remember { mutableStateOf<TrophyProgress?>(null) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }

    val unlocked = progress.count { it.unlocked }
    val percent = trophyProgressPercent(progress)

    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush()),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
    ) {
        // Cabecera: mini portada + título + progreso con barra dorada.
        PremiumCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.col_cover),
                    contentDescription = "Child of Light",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(12.dp)),
                )
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        "Child of Light",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$unlocked / 27 desbloqueados ($percent%)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    GoldProgressBar(progress = if (progress.isEmpty()) 0f else unlocked / 27f)
                }
            }
        }
        NoteText("Logros LOCALES del launcher: no se sincronizan con PSN / PlayStation Network ni con Steam. No hay platino en este juego (ni en PS4 ni en Vita).")
        scan?.let { s ->
            NoteText("Trofeos del núcleo (ux0/user/00/trophy/): ${s.note}")
        }
        Spacer(modifier = Modifier.height(8.dp))

        progress.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (item.unlocked) ColCardElevated else MaterialTheme.colorScheme.surfaceVariant,
                ),
                onClick = { selected = item },
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrophyIcon(item)
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            "${item.trophy.number}. ${item.displayName}",
                            fontWeight = FontWeight.Bold,
                            color = if (item.unlocked) Color.White else ColMutedText,
                        )
                        Text(
                            item.displayDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = ColMutedText,
                        )
                        if (item.trophy.tier != null) Text("Grado: ${item.trophy.tier}", style = MaterialTheme.typography.bodySmall, color = ColMutedText)
                        if (item.trophy.onlineRequired) {
                            Text("⚠ Puede no desbloquearse sin la función online de Vita (enviar Oculi a un amigo).", style = MaterialTheme.typography.bodySmall, color = ColGold)
                        }
                        item.unlockedAtMillis?.let { at ->
                            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(at))
                            Text("Desbloqueado: $date", style = MaterialTheme.typography.bodySmall, color = ColMutedText)
                        }
                    }
                    if (!item.unlocked) {
                        Text("🔒", style = MaterialTheme.typography.bodyMedium, color = ColMutedText)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Borrar progreso de logros") }
        BackButton(onBack)
    }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.displayName) },
            text = {
                Column {
                    Text(item.displayDescription)
                    if (item.trophy.onlineRequired) Text("Puede no desbloquearse sin la función online de Vita.")
                    if (item.unlocked) Text("Ya desbloqueado. Puedes volver a bloquearlo si fue un error.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (item.unlocked) vm.lockTrophy(item.trophy.number) else vm.unlockTrophy(item.trophy.number)
                    selected = null
                }) { Text(if (item.unlocked) "Bloquear de nuevo" else "Marcar como desbloqueado") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cerrar") } },
        )
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("¿Borrar el progreso de logros?") },
            text = { Text("Se borran los 27 trofeos desbloqueados en este launcher. Es solo local: no afecta a PSN porque nunca se sincroniza.") },
            confirmButton = {
                TextButton(onClick = { vm.resetTrophies(); showResetConfirm = false }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancelar") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Iconos ilustrativos de trofeo: círculo azul acuarela pálido (degradado
// radial) con un símbolo blanco dibujado con Canvas según el trofeo.
// Sin PNGs nuevos: todo es vectorial y pesa cero. Bloqueado: círculo
// grisáceo atenuado + símbolo a media opacidad. Secreto sin desbloquear:
// "?" en vez del símbolo (el nombre y la descripción ya se ocultan).
// ---------------------------------------------------------------------------

/** Símbolo blanco de cada trofeo (por número, 1..27). */
private enum class TrophySymbol {
    STAR, MOON, SUN, CROWN, MASK, SPARKLE, FEATHER, HEART, SHIELD, SWORD,
    DROPLET, ARROW_UP, GEM, EYE, BURST, FLAME, KEY, WINGS, CHEST, SCROLL,
    LEAF, APPLE,
}

private fun symbolFor(number: Int): TrophySymbol = when (number) {
    1 -> TrophySymbol.STAR // Starcatcher: recupera las estrellas.
    2 -> TrophySymbol.MOON // Over the Moon: recupera la luna.
    3 -> TrophySymbol.SUN // Sunrise: recupera el sol.
    4 -> TrophySymbol.CROWN // Coronation: destrona a la Reina.
    5 -> TrophySymbol.MASK // Comedy: Rubella se une (bufona).
    6 -> TrophySymbol.SPARKLE // The Girl and the Firefly: Igniculus.
    7 -> TrophySymbol.FEATHER // Capilli Catch: Finn se une.
    8 -> TrophySymbol.HEART // Sweet Sister: Norah se une.
    9 -> TrophySymbol.SHIELD // Bolmus Bond: Robert se une.
    10 -> TrophySymbol.SWORD // Kategida Kindred: Óengus se une.
    11 -> TrophySymbol.DROPLET // Piscean Promise: Gen se une.
    12 -> TrophySymbol.MASK // Tragedy: Tristis se une (máscara trágica).
    13 -> TrophySymbol.ARROW_UP // Tenth Birthday: nivel 10 con Aurora.
    14 -> TrophySymbol.GEM // Perfectionist: habilidad al máximo.
    15 -> TrophySymbol.EYE // Master Oculist: fabrica Oculi.
    16 -> TrophySymbol.BURST // Scene Stealer: 3 ataques sorpresa.
    17 -> TrophySymbol.FLAME // Showstopper: interrumpe a 3 enemigos.
    18 -> TrophySymbol.KEY // Through the Looking Glass: envía Oculi.
    19 -> TrophySymbol.WINGS // Winged Fury: devasta a 5 enemigos.
    20 -> TrophySymbol.CHEST // Explorer: 30 cofres.
    21 -> TrophySymbol.SCROLL // Perfect Sonnet: todas las Confesiones.
    22 -> TrophySymbol.LEAF // Grassroots: peticiones lemurianas.
    23 -> TrophySymbol.SHIELD // General of Light: batalla sin perder PS.
    24 -> TrophySymbol.SWORD // Guardians of Lemuria: batalla sin Aurora.
    25 -> TrophySymbol.SPARKLE // Aurora Borealis: 5 depósitos de polvo.
    26 -> TrophySymbol.WINGS // When Pigs Fly: la odisea de Aquiles.
    27 -> TrophySymbol.APPLE // Golden Apple: libre comercio.
    else -> TrophySymbol.STAR
}

@Composable
private fun TrophyIcon(item: TrophyProgress, modifier: Modifier = Modifier) {
    val secretLocked = item.trophy.isSecret && !item.unlocked
    val iconAlpha = if (item.unlocked) 1f else 0.5f
    // Azul acuarela pálido si está desbloqueado; gris azulado si no.
    val gradientColors = if (item.unlocked) {
        listOf(Color(0xFFC7D6EC), Color(0xFF93AED6))
    } else {
        listOf(Color(0xFF9AA5B5), Color(0xFF7C8AA0))
    }
    Box(modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().alpha(iconAlpha)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(colors = gradientColors, center = center, radius = radius),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        if (secretLocked) {
            Text(
                "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                modifier = Modifier.alpha(iconAlpha),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize(0.62f).alpha(if (item.unlocked) 1f else 0.65f)) {
                drawTrophySymbol(symbolFor(item.trophy.number), Color.White)
            }
        }
    }
}

/** Estrella de N puntas (para STAR, SPARKLE de 4 y BURST de 8). */
private fun starPath(center: Offset, outer: Float, inner: Float, points: Int): Path {
    val path = Path()
    val step = 360f / points / 2
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outer else inner
        val angle = Math.toRadians((-90.0 + i * step).toDouble())
        val x = center.x + radius * cos(angle).toFloat()
        val y = center.y + radius * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Dibuja el símbolo blanco centrado en el lienzo del icono. */
private fun DrawScope.drawTrophySymbol(kind: TrophySymbol, color: Color) {
    val s = size.minDimension
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = s * 0.32f
    // Tono del borde del icono: para calar detalles (pupila, vetas).
    val detail = Color(0xFF8FA8CC)
    when (kind) {
        TrophySymbol.STAR -> drawPath(starPath(c, r, r * 0.45f, 5), color)
        TrophySymbol.SPARKLE -> drawPath(starPath(c, r, r * 0.18f, 4), color)
        TrophySymbol.BURST -> drawPath(starPath(c, r, r * 0.5f, 8), color)
        TrophySymbol.MOON -> {
            // Luna creciente: dos óvalos con relleno par-impar.
            val path = Path().apply {
                addOval(Rect(c, r))
                addOval(Rect(Offset(c.x + r * 0.55f, c.y - r * 0.25f), r * 0.85f))
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, color)
        }
        TrophySymbol.SUN -> {
            drawCircle(color, radius = r * 0.45f, center = c)
            for (i in 0 until 8) {
                val angle = Math.toRadians((i * 45.0).toDouble())
                val d = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(color, c + d * r * 0.62f, c + d * r * 0.95f, strokeWidth = s * 0.06f, cap = StrokeCap.Round)
            }
        }
        TrophySymbol.CROWN -> {
            val path = Path().apply {
                moveTo(c.x - r, c.y + r * 0.6f)
                lineTo(c.x - r, c.y - r * 0.1f)
                lineTo(c.x - r * 0.5f, c.y + r * 0.3f)
                lineTo(c.x, c.y - r * 0.7f)
                lineTo(c.x + r * 0.5f, c.y + r * 0.3f)
                lineTo(c.x + r, c.y - r * 0.1f)
                lineTo(c.x + r, c.y + r * 0.6f)
                close()
            }
            drawPath(path, color)
            drawRect(color, topLeft = Offset(c.x - r, c.y + r * 0.6f), size = androidx.compose.ui.geometry.Size(r * 2, r * 0.18f))
        }
        TrophySymbol.MASK -> {
            // Máscara de teatro: óvalo con cuencas y sonrisa (par-impar).
            val path = Path().apply {
                addOval(Rect(c.x - r, c.y - r * 0.75f, c.x + r, c.y + r * 0.75f))
                addOval(Rect(Offset(c.x - r * 0.45f, c.y - r * 0.25f), r * 0.18f))
                addOval(Rect(Offset(c.x + r * 0.45f, c.y - r * 0.25f), r * 0.18f))
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, color)
            drawArc(
                color = color,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.5f, c.y - r * 0.1f),
                size = androidx.compose.ui.geometry.Size(r, r * 0.8f),
                style = Stroke(width = s * 0.06f, cap = StrokeCap.Round),
            )
        }
        TrophySymbol.FEATHER -> {
            rotate(35f, c) {
                drawOval(color, topLeft = Offset(c.x - r * 0.28f, c.y - r * 0.95f), size = androidx.compose.ui.geometry.Size(r * 0.56f, r * 1.9f))
                drawLine(detail, Offset(c.x, c.y - r * 0.8f), Offset(c.x, c.y + r * 0.8f), strokeWidth = s * 0.05f, cap = StrokeCap.Round)
            }
        }
        TrophySymbol.HEART -> {
            val path = Path().apply {
                moveTo(c.x, c.y + r * 0.85f)
                cubicTo(c.x - r * 1.1f, c.y - r * 0.1f, c.x - r * 0.55f, c.y - r * 0.95f, c.x, c.y - r * 0.25f)
                cubicTo(c.x + r * 0.55f, c.y - r * 0.95f, c.x + r * 1.1f, c.y - r * 0.1f, c.x, c.y + r * 0.85f)
                close()
            }
            drawPath(path, color)
        }
        TrophySymbol.SHIELD -> {
            val path = Path().apply {
                moveTo(c.x - r * 0.9f, c.y - r * 0.7f)
                quadraticTo(c.x, c.y - r * 0.95f, c.x + r * 0.9f, c.y - r * 0.7f)
                quadraticTo(c.x + r * 0.9f, c.y + r * 0.35f, c.x, c.y + r * 0.95f)
                quadraticTo(c.x - r * 0.9f, c.y + r * 0.35f, c.x - r * 0.9f, c.y - r * 0.7f)
                close()
            }
            drawPath(path, color)
        }
        TrophySymbol.SWORD -> {
            rotate(45f, c) {
                drawRect(color, topLeft = Offset(c.x - r * 0.14f, c.y - r * 0.95f), size = androidx.compose.ui.geometry.Size(r * 0.28f, r * 1.15f))
                val tip = Path().apply {
                    moveTo(c.x - r * 0.14f, c.y - r * 0.95f)
                    lineTo(c.x, c.y - r * 1.25f)
                    lineTo(c.x + r * 0.14f, c.y - r * 0.95f)
                    close()
                }
                drawPath(tip, color)
                drawRect(color, topLeft = Offset(c.x - r * 0.45f, c.y + r * 0.2f), size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 0.16f))
                drawRect(color, topLeft = Offset(c.x - r * 0.09f, c.y + r * 0.36f), size = androidx.compose.ui.geometry.Size(r * 0.18f, r * 0.5f))
            }
        }
        TrophySymbol.DROPLET -> {
            val path = Path().apply {
                moveTo(c.x, c.y - r)
                cubicTo(c.x + r * 0.95f, c.y - r * 0.05f, c.x + r * 0.8f, c.y + r * 0.85f, c.x, c.y + r * 0.9f)
                cubicTo(c.x - r * 0.8f, c.y + r * 0.85f, c.x - r * 0.95f, c.y - r * 0.05f, c.x, c.y - r)
                close()
            }
            drawPath(path, color)
        }
        TrophySymbol.ARROW_UP -> {
            val tri = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r * 0.7f, c.y - r * 0.1f)
                lineTo(c.x - r * 0.7f, c.y - r * 0.1f)
                close()
            }
            drawPath(tri, color)
            drawRect(color, topLeft = Offset(c.x - r * 0.18f, c.y - r * 0.05f), size = androidx.compose.ui.geometry.Size(r * 0.36f, r * 0.95f))
        }
        TrophySymbol.GEM -> {
            val path = Path().apply {
                moveTo(c.x - r * 0.85f, c.y - r * 0.25f)
                lineTo(c.x - r * 0.4f, c.y - r * 0.75f)
                lineTo(c.x + r * 0.4f, c.y - r * 0.75f)
                lineTo(c.x + r * 0.85f, c.y - r * 0.25f)
                lineTo(c.x, c.y + r * 0.85f)
                close()
            }
            drawPath(path, color)
            drawLine(color, Offset(c.x - r * 0.85f, c.y - r * 0.25f), Offset(c.x + r * 0.85f, c.y - r * 0.25f), strokeWidth = s * 0.05f)
        }
        TrophySymbol.EYE -> {
            val path = Path().apply {
                moveTo(c.x - r, c.y)
                quadraticTo(c.x, c.y - r * 0.95f, c.x + r, c.y)
                quadraticTo(c.x, c.y + r * 0.95f, c.x - r, c.y)
                close()
            }
            drawPath(path, color)
            drawCircle(detail, radius = r * 0.38f, center = c)
        }
        TrophySymbol.FLAME -> {
            val path = Path().apply {
                moveTo(c.x, c.y - r)
                cubicTo(c.x + r * 0.55f, c.y - r * 0.35f, c.x + r * 0.95f, c.y + r * 0.1f, c.x + r * 0.7f, c.y + r * 0.55f)
                cubicTo(c.x + r * 0.5f, c.y + r * 0.9f, c.x - r * 0.5f, c.y + r * 0.9f, c.x - r * 0.7f, c.y + r * 0.55f)
                cubicTo(c.x - r * 0.95f, c.y + r * 0.1f, c.x - r * 0.4f, c.y - r * 0.3f, c.x, c.y - r)
                close()
            }
            drawPath(path, color)
        }
        TrophySymbol.KEY -> {
            drawCircle(color, radius = r * 0.42f, center = Offset(c.x - r * 0.45f, c.y - r * 0.45f), style = Stroke(width = s * 0.09f))
            drawLine(color, Offset(c.x - r * 0.15f, c.y - r * 0.15f), Offset(c.x + r * 0.85f, c.y + r * 0.85f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
            drawLine(color, Offset(c.x + r * 0.45f, c.y + r * 0.45f), Offset(c.x + r * 0.75f, c.y + r * 0.15f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
            drawLine(color, Offset(c.x + r * 0.65f, c.y + r * 0.65f), Offset(c.x + r * 0.95f, c.y + r * 0.35f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
        }
        TrophySymbol.WINGS -> {
            val left = Path().apply {
                moveTo(c.x, c.y + r * 0.3f)
                quadraticTo(c.x - r * 0.5f, c.y - r * 0.7f, c.x - r, c.y - r * 0.4f)
                quadraticTo(c.x - r * 0.5f, c.y - r * 0.1f, c.x, c.y + r * 0.3f)
                close()
            }
            val right = Path().apply {
                moveTo(c.x, c.y + r * 0.3f)
                quadraticTo(c.x + r * 0.5f, c.y - r * 0.7f, c.x + r, c.y - r * 0.4f)
                quadraticTo(c.x + r * 0.5f, c.y - r * 0.1f, c.x, c.y + r * 0.3f)
                close()
            }
            drawPath(left, color)
            drawPath(right, color)
        }
        TrophySymbol.CHEST -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(c.x - r * 0.85f, c.y - r * 0.1f),
                size = androidx.compose.ui.geometry.Size(r * 1.7f, r * 0.95f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.15f),
            )
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.85f, c.y - r * 0.85f),
                size = androidx.compose.ui.geometry.Size(r * 1.7f, r * 1.5f),
                style = Stroke(width = s * 0.08f, cap = StrokeCap.Round),
            )
            drawCircle(detail, radius = r * 0.14f, center = Offset(c.x, c.y + r * 0.35f))
        }
        TrophySymbol.SCROLL -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(c.x - r * 0.7f, c.y - r * 0.55f),
                size = androidx.compose.ui.geometry.Size(r * 1.4f, r * 1.1f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.2f),
            )
            drawCircle(detail, radius = r * 0.2f, center = Offset(c.x - r * 0.7f, c.y))
            drawLine(detail, Offset(c.x - r * 0.3f, c.y - r * 0.2f), Offset(c.x + r * 0.45f, c.y - r * 0.2f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
            drawLine(detail, Offset(c.x - r * 0.3f, c.y + r * 0.15f), Offset(c.x + r * 0.45f, c.y + r * 0.15f), strokeWidth = s * 0.06f, cap = StrokeCap.Round)
        }
        TrophySymbol.LEAF -> {
            rotate(45f, c) {
                val path = Path().apply {
                    moveTo(c.x, c.y - r)
                    quadraticTo(c.x + r * 0.85f, c.y, c.x, c.y + r)
                    quadraticTo(c.x - r * 0.85f, c.y, c.x, c.y - r)
                    close()
                }
                drawPath(path, color)
                drawLine(detail, Offset(c.x, c.y - r * 0.8f), Offset(c.x, c.y + r * 0.8f), strokeWidth = s * 0.05f, cap = StrokeCap.Round)
            }
        }
        TrophySymbol.APPLE -> {
            drawCircle(color, radius = r * 0.72f, center = Offset(c.x - r * 0.28f, c.y + r * 0.15f))
            drawCircle(color, radius = r * 0.72f, center = Offset(c.x + r * 0.28f, c.y + r * 0.15f))
            drawLine(color, Offset(c.x, c.y - r * 0.55f), Offset(c.x + r * 0.1f, c.y - r * 0.95f), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
            val leaf = Path().apply {
                moveTo(c.x + r * 0.1f, c.y - r * 0.8f)
                quadraticTo(c.x + r * 0.6f, c.y - r * 1.0f, c.x + r * 0.75f, c.y - r * 0.55f)
                quadraticTo(c.x + r * 0.3f, c.y - r * 0.5f, c.x + r * 0.1f, c.y - r * 0.8f)
                close()
            }
            drawPath(leaf, color)
        }
    }
}
