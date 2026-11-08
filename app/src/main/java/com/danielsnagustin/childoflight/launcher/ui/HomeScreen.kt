package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielsnagustin.childoflight.launcher.R
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.trophyProgressPercent

/**
 * Pantalla principal: portada grande + JUGAR/IMPORTAR, premium AMOLED.
 *
 * Rediseño 2026-11-07 (petición del usuario: "arregla la interfaz",
 * referencia de diseño oscuro + dorado):
 * - Portada grande arriba con esquinas redondeadas: recorte panorámico
 *   LIMPIO del arte oficial (drawable-nodpi/col_cover.png), sin la
 *   marca de agua que tenía la portada de la referencia. El arte vive
 *   también en el icono de la app, pero la Home ya no es solo un
 *   degradado: la portada es lo primero que se ve.
 * - Título "Child of Light", subtítulo "PS Vita · Ubisoft · UbiArt",
 *   estado del juego (instalado / DLCs / update), aviso dorado cuando
 *   falta importar y botón dorado grande: JUGAR si está importado,
 *   IMPORTAR JUEGO si no (va a Configuración, donde vive la importación).
 * - Fila de dos tarjetas: Rendimiento (GPU detectada + perfil) y Logros
 *   (X de 27 + barra; toca para ver la lista). Fila "Configuración
 *   avanzada" que abre Configuración. La tarjeta "Para jugar" sigue
 *   debajo: explica qué falta (juego, app externa, licencia) con las
 *   acciones directas.
 * - Movimiento: la portada entra con fundido + zoom suave (una vez) y
 *   un brillo dorado lento respira en su borde inferior; las tarjetas
 *   entran en cascada (FadeSlideIn). Nada de bucles rápidos.
 * - AMOLED negro puro #000000 con acentos dorados; sin "Vita3K" en
 *   ningún texto visible (la app externa se llama siempre "app externa")
 *   y sin modelo de teléfono (solo procesador/GPU detectados).
 */
@Composable
fun HomeScreen(vm: LauncherViewModel, onNavigate: (Screen) -> Unit) {
    val status by vm.status.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val achievements by vm.achievementsFlow.collectAsState(initial = emptyList())
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())
    // Deshabilitar JUGAR mientras hay un lanzamiento en curso (doble
    // toque = dos launch() a la vez); la guarda vive en el ViewModel.
    val launching by vm.launchingFlow.collectAsState()

    val strings = stringsFor(uiPrefs?.language ?: com.danielsnagustin.childoflight.launcher.model.AppLanguage.SPANISH)
    val lastFps = benchmarks.firstOrNull()?.fpsAvg
    val unlocked = achievements.count { it.unlocked }
    val percent = trophyProgressPercent(achievements)
    val gameOk = status.gameInstalled

    val gameCard: @Composable () -> Unit = {
        PremiumCard {
            // Portada grande: entra con zoom suave + fundido (una vez).
            CoverEntrance {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp)),
                ) {
                    // Portada 1080x608 en drawable-nodpi: sin escalado por
                    // densidad y decodificada ~2,6 MB (ARGB_8888), que es
                    // casi el tamaño al que se muestra (ancho de pantalla
                    // a ~3x). No se subescala: no hay sobrecarga que
                    // corregir aquí. La miniatura de Logros usa
                    // col_cover_thumb (384x216). Auditoría RAM 2026-11-07.
                    Image(
                        painter = painterResource(R.drawable.col_cover),
                        contentDescription = "Child of Light",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Brillo dorado sutil que respira en el borde inferior:
                    // la portada se siente viva. Solo un alpha animado.
                    val coverGlow by rememberInfiniteTransition(label = "coverGlow").animateFloat(
                        initialValue = 0.02f,
                        targetValue = 0.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(4000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "coverGlowAlpha",
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Transparent,
                                        ColGold.copy(alpha = coverGlow),
                                    ),
                                ),
                            ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Child of Light", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text("PS Vita · Ubisoft · UbiArt", style = MaterialTheme.typography.bodyMedium, color = ColMutedText)
            Spacer(modifier = Modifier.height(10.dp))
            Text("${strings.state}: ${status.installedLabel}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                "${strings.dlcs}: ${status.dlcCount} · ${strings.update}: ${if (status.updateInstalled) "sí" else "no"}",
                style = MaterialTheme.typography.bodySmall,
                color = ColMutedText,
            )
            if (uiPrefs?.showFpsOnHome != false && lastFps != null) {
                Text("FPS (última prueba): $lastFps", style = MaterialTheme.typography.bodySmall, color = ColMutedText)
            }
            if (!gameOk) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "⚠ ${strings.pendingImport}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ColGold,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (gameOk) {
                GoldButton(strings.play, onClick = { vm.play() }, enabled = !launching)
            } else {
                GoldButton("⬇  ${strings.importGame.uppercase()}", onClick = { onNavigate(Screen.SETTINGS) })
            }
        }
    }

    // Fila de dos tarjetas: Rendimiento (GPU + perfil) y Logros (X/27).
    // La de Logros respeta "Mostrar logros en la pantalla principal": si
    // está oculta, Rendimiento ocupa todo el ancho.
    val infoRow: @Composable () -> Unit = {
        val showAchievements = uiPrefs?.showAchievementsOnHome != false
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HalfCard(modifier = Modifier.weight(1f)) {
                Text("▣", fontSize = 22.sp, color = ColGold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Rendimiento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    gpu?.shortLabel ?: "GPU no detectada",
                    style = MaterialTheme.typography.bodySmall,
                    color = ColMutedText,
                )
                Text(
                    "Perfil: ${settings?.activeProfile?.label ?: "—"} · ${settings?.resolutionLabel ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ColMutedText,
                )
            }
            if (showAchievements) {
                HalfCard(modifier = Modifier.weight(1f), onClick = { onNavigate(Screen.ACHIEVEMENTS) }) {
                    Text("🏆", fontSize = 22.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strings.achievementsShort, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$unlocked de 27 completados",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColMutedText,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GoldProgressBar(progress = if (achievements.isEmpty()) 0f else unlocked / 27f)
                }
            }
        }
    }

    val advancedRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onNavigate(Screen.SETTINGS) }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlidersIcon()
            Text(
                strings.advancedSettings,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Text("›", style = MaterialTheme.typography.headlineMedium, color = ColMutedText)
        }
    }

    // Tarjeta "Para jugar": lo que falta para poder jugar, con la acción
    // directa. La Home quedaba medio vacía y el usuario no veía por qué
    // JUGAR no hacía nada: el juego y la app externa son dos pasos
    // separados y aquí se ven los dos. Sin esto, el estado real solo
    // vivía en Diagnóstico, donde el usuario no mira.
    val playStatusCard: @Composable () -> Unit = {
        val vita3k by vm.externalVita3k.collectAsState()
        val vitaOk = vita3k != null
        val pkgOnly = status.pkgInstalled && !status.gameExtracted
        val delivered = settings?.pkgDeliveredToVita3k == true
        val confirmedInVita3k = settings?.pkgInstalledInVita3k == true
        PremiumCard {
            Text("Para jugar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                when {
                    pkgOnly -> "✓ Juego (.pkg) guardado (${status.pkgTitleId?.id ?: ""})"
                    gameOk -> "✓ Juego instalado (${status.titleId?.id ?: ""})"
                    else -> "✗ Juego sin instalar"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (gameOk) ColSuccessGreen else Color.White,
            )
            Text(
                if (vitaOk) "✓ App externa instalada ($vita3k)" else "✗ App externa no instalada",
                style = MaterialTheme.typography.bodyMedium,
                color = if (vitaOk) ColSuccessGreen else Color.White,
            )
            if (pkgOnly) {
                Text(
                    if (status.pkgLicensePresent) "✓ Licencia (work.bin) guardada" else "✗ Falta la licencia (work.bin)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.pkgLicensePresent) ColSuccessGreen else Color.White,
                )
                Text(
                    when {
                        confirmedInVita3k -> "✓ Instalado en la app externa"
                        delivered -> "✓ Copiado a Descargas — falta instalarlo en la app externa"
                        else -> "✗ Falta instalarlo en la app externa"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (confirmedInVita3k) ColSuccessGreen else Color.White,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (!gameOk) {
                SecondaryButton("Importar juego", onClick = { onNavigate(Screen.SETTINGS) })
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (!vitaOk) {
                SecondaryButton("Instalar app externa", onClick = { vm.openVita3kReleases() })
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (pkgOnly && !status.pkgLicensePresent) {
                SecondaryButton("Elegir work.bin", onClick = { onNavigate(Screen.SETTINGS) })
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (pkgOnly && status.pkgLicensePresent && !delivered) {
                SecondaryButton("Instalar en la app externa", onClick = { vm.deliverPkgToVita3k() })
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (pkgOnly && delivered && !confirmedInVita3k) {
                SecondaryButton("Ya lo instalé en la app externa", onClick = { vm.confirmPkgInstalledInVita3k() })
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                when {
                    !gameOk -> "Importa tu copia de Child of Light (.zip, .vpk o .pkg — el nombre del archivo da igual)."
                    !vitaOk -> "Instala la app externa para jugar: el launcher no trae núcleo embebido en Etapa 1."
                    pkgOnly && !status.pkgLicensePresent -> "Sin la licencia (work.bin) no se puede instalar el .pkg."
                    pkgOnly && !delivered -> "El .pkg está guardado. Instálalo en la app externa para poder jugar."
                    pkgOnly && !confirmedInVita3k -> "Copiado a Descargas/ChildOfLight/. Instálalo en la app externa (File → Install Package (.pkg)) y confirma aquí. Cuando lo instales, puedes borrar la copia desde Ajustes → Almacenamiento."
                    pkgOnly -> "Todo listo: pulsa JUGAR y abre el juego en la biblioteca de la app externa."
                    else -> "Todo listo: pulsa JUGAR. Recuerda importar tu copia también dentro de la app externa."
                },
                style = MaterialTheme.typography.bodySmall,
                color = ColMutedText,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(homeBackgroundBrush(uiPrefs?.homeBackground?.hex)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = ContentMaxWidth)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.Top,
        ) {
            // El orden de secciones lo elige el usuario (GAME_CARD / GPU_CARD);
            // NAV_BUTTONS ya no se muestra en Home: vive en Configuración.
            // Aparición progresiva: cada bloque entra con fundido + subida,
            // con ~90 ms entre ellos. Una sola vez al abrir la Home. La fila
            // de Logros vive dentro de la sección GPU_CARD (fila de dos
            // tarjetas: Rendimiento + Logros).
            val order = uiPrefs?.sectionOrder ?: HomeSection.entries.toList()
            order.forEach { section ->
                when (section) {
                    HomeSection.GAME_CARD -> { FadeSlideIn(0) { gameCard() }; Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.GPU_CARD -> { FadeSlideIn(90) { infoRow() }; Spacer(modifier = Modifier.height(8.dp)) }
                    HomeSection.NAV_BUTTONS -> { /* En Configuración (engranaje). */ }
                }
            }
            FadeSlideIn(180) { advancedRow() }
            Spacer(modifier = Modifier.height(8.dp))
            FadeSlideIn(270) { playStatusCard() }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Entrada de la portada: fundido + zoom suave (1.06 → 1.00, 650 ms).
 * Una sola vez al componer.
 */
@Composable
private fun CoverEntrance(content: @Composable () -> Unit) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val scale by animateFloatAsState(
        targetValue = if (started) 1f else 1.06f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "coverScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "coverAlpha",
    )
    Box(
        modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha),
    ) {
        content()
    }
}

/**
 * Media tarjeta premium (la mitad de una fila de dos): mismo estilo que
 * PremiumCard (negro #111111, borde dorado sutil) pero con ancho por
 * peso, no a todo lo ancho.
 */
@Composable
private fun HalfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(1.dp, ColGold.copy(alpha = 0.22f))
    if (onClick != null) {
        Card(
            modifier = modifier,
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
            modifier = modifier,
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

/** Icono de "ajustes" (dos deslizadores): líneas grises, pomos dorados. */
@Composable
private fun SlidersIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.5.dp.toPx()
        val knob = 4.5.dp.toPx()
        drawLine(
            color = ColMutedText,
            start = Offset(0f, h * 0.32f),
            end = Offset(w, h * 0.32f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ColMutedText,
            start = Offset(0f, h * 0.68f),
            end = Offset(w, h * 0.68f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = ColGold, radius = knob, center = Offset(w * 0.32f, h * 0.32f))
        drawCircle(color = ColGold, radius = knob, center = Offset(w * 0.68f, h * 0.68f))
    }
}
