package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.R
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.IconSize
import kotlinx.coroutines.delay

/**
 * Pantalla principal: portada grande + JUGAR/IMPORTAR, premium AMOLED.
 *
 * Rediseño 2026-11-07 (petición del usuario: "arregla la interfaz",
 * referencia de diseño oscuro + dorado):
 * - Portada grande arriba con esquinas redondeadas: el arte oficial
 *   COMPLETO, sin recorte. El arte original es cuadrado 1254x1254
 *   (1:1) y la portada anterior (1080x608, 16:9) recortaba ~44% de
 *   la altura. Ahora la tarjeta es cuadrada (aspectRatio 1f) y la
 *   imagen se muestra con ContentScale.Fit sobre fondo negro: a
 *   igualdad de proporción no hay recorte ni bandas. El título y el
 *   subtítulo van sobre un gradiente inferior DENTRO de la tarjeta.
 *   La imagen vive en drawable-nodpi/col_cover_full.jpg (1024x1024,
 *   del arte original) y se decodifica una vez en el ViewModel.
 * - Chips de estado (Juego, DLCs, Update, Firmware) con punto de
 *   color (verde instalado, dorado pendiente, gris no instalado),
 *   derivados del estado real con la función pura homeStatusChips.
 * - Botón dorado grande: JUGAR con subtítulo (Title ID · resolución)
 *   e icono vectorial si está importado, IMPORTAR JUEGO si no (va a
 *   la pantalla de Importación, no a Configuración).
 * - Fila de dos tarjetas: Rendimiento (GPU detectada + perfil) y Logros
 *   (X de 27 + barra; toca para ver la lista). Fila "Configuración
 *   avanzada" que abre Configuración. El estado del juego y su
 *   importación viven solo en la tarjeta superior: sin duplicados.
 * - Movimiento: la portada entra con fundido + zoom suave (una sola vez
 *   por sesión) y un brillo dorado estático en su borde inferior; las
 *   tarjetas entran en cascada (FadeSlideIn, solo la primera vez). En las
 *   vueltas a Home la pantalla aparece al instante, sin esperar ~800 ms
 *   de animaciones. Nada de bucles infinitos ni animaciones que mantengan
 *   la GPU despierta.
 * - Cada sección recoge solo sus flujos: antes HomeScreen recogía 6
 *   flujos arriba y cualquier emisión recomponía toda la pantalla.
 * - AMOLED negro puro #000000 con acentos dorados; sin "Vita3K" en
 *   ningún texto visible (la app externa se llama siempre "app externa")
 *   y sin modelo de teléfono (solo procesador/GPU detectados).
 */
@Composable
fun HomeScreen(
    vm: LauncherViewModel,
    introDone: Boolean = false,
    onIntroDone: () -> Unit = {},
    onNavigate: (Screen) -> Unit,
) {
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    val strings = stringsFor(uiPrefs?.language ?: com.danielsnagustin.childoflight.launcher.model.AppLanguage.SPANISH)

    // La intro (portada + tarjetas) solo se reproduce una vez por sesión:
    // AnimatedContent descarta y recrea la composición de Home en cada
    // navegación, así que sin esta bandera la entrada se repetía (~800 ms)
    // en cada vuelta a Home. 900 ms = portada 650 ms + último retardo 180 ms.
    if (!introDone) {
        LaunchedEffect(Unit) {
            delay(900)
            onIntroDone()
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
            // con ~90 ms entre ellos. Solo la primera vez por sesión. La fila
            // de Logros vive dentro de la sección GPU_CARD (fila de dos
            // tarjetas: Rendimiento + Logros).
            val order = uiPrefs?.sectionOrder ?: HomeSection.entries.toList()
            order.forEach { section ->
                when (section) {
                    HomeSection.GAME_CARD -> { FadeSlideIn(0, play = !introDone) { GameCard(vm, uiPrefs, strings, introDone, onNavigate) }; Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.GPU_CARD -> { FadeSlideIn(90, play = !introDone) { InfoRow(vm, uiPrefs, strings, onNavigate) }; Spacer(modifier = Modifier.height(8.dp)) }
                    HomeSection.NAV_BUTTONS -> { /* En Configuración (engranaje). */ }
                }
            }
            FadeSlideIn(180, play = !introDone) { AdvancedRow(strings, onNavigate, iconSize = uiPrefs?.iconSize ?: IconSize.MEDIUM) }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// Tarjeta superior del juego (portada + estado + JUGAR/IMPORTAR). Recoge
// solo sus flujos: estado, ajustes, benchmarks, firmware y lanzamiento.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(
    vm: LauncherViewModel,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
    strings: AppStrings,
    introDone: Boolean,
    onNavigate: (Screen) -> Unit,
) {
    val status by vm.status.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    // Deshabilitar JUGAR mientras hay un lanzamiento en curso (doble
    // toque = dos launch() a la vez); la guarda vive en el ViewModel.
    val launching by vm.launchingFlow.collectAsState()
    val cover by vm.coverBitmap.collectAsState()

    val lastFps = benchmarks.firstOrNull()?.fpsAvg
    val gameOk = status.gameInstalled
    val chips = homeStatusChips(status, firmwareStatus)

    PremiumCard(
        cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
        cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
    ) {
            // Portada cuadrada: entra con zoom suave + fundido (una vez).
            // Arte original 1254x1254 (1:1) servido como col_cover_full
            // (1024x1024): tarjeta aspectRatio 1f + ContentScale.Fit
            // sobre negro = la ilustración entera, sin recortar nada y
            // sin bandas (misma proporción). Título y subtítulo sobre
            // gradiente inferior, dentro de la tarjeta.
            CoverEntrance(play = !introDone) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                ) {
                    // La imagen se decodifica UNA vez en el ViewModel (hilo
                    // IO) y se reutiliza: painterResource decodifica en el
                    // hilo principal y lo repetía en cada vuelta a Home
                    // (la composición se descarta al navegar).
                    if (cover != null) {
                        Image(
                            bitmap = cover!!,
                            contentDescription = "Child of Light",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.col_cover_full),
                            contentDescription = "Child of Light",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Gradiente inferior estático para que el título se
                    // lea sobre el arte. Sin animaciones infinitas.
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.55f),
                                        Color.Black.copy(alpha = 0.85f),
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    ) {
                        Text("Child of Light", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("PS Vita · Ubisoft · UbiArt", style = MaterialTheme.typography.bodyMedium, color = ColMutedText)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    val label = when (chip.key) {
                        HomeStatusKey.GAME -> strings.game
                        HomeStatusKey.DLCS -> strings.dlcs
                        HomeStatusKey.UPDATE -> strings.update
                        HomeStatusKey.FIRMWARE -> strings.firmware
                    }
                    StatusChip(chip, label)
                }
            }
            if (uiPrefs?.showFpsOnHome != false && lastFps != null) {
                Spacer(modifier = Modifier.height(10.dp))
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
                val subtitle = listOfNotNull(
                    status.titleId?.id,
                    settings?.resolutionLabel?.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                GoldButtonWithSubtitle(strings.playShort, subtitle, onClick = { vm.play() }, enabled = !launching)
            } else {
                GoldButton(strings.importGame.uppercase(), onClick = { onNavigate(Screen.IMPORT) })
            }
        }
    }

// Fila de dos tarjetas: Rendimiento (GPU + perfil) y Logros (X/27).
// La de Logros respeta "Mostrar logros en la pantalla principal": si
// está oculta, Rendimiento ocupa todo el ancho. Recoge solo sus flujos.
@Composable
private fun InfoRow(
    vm: LauncherViewModel,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
    strings: AppStrings,
    onNavigate: (Screen) -> Unit,
) {
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val achievements by vm.achievementsFlow.collectAsState(initial = emptyList())
    val unlocked = achievements.count { it.unlocked }
    val tap = rememberTapHaptic()
    val showAchievements = uiPrefs?.showAchievementsOnHome != false
    // Los iconos vectoriales dibujan a 24 dp fijos: se escalan por
    // graphicsLayer (vectorial, sin pérdida) según el ajuste de tamaño.
    val iconScale = (uiPrefs?.iconSize ?: IconSize.MEDIUM).sizeDp / 24f
    // Misma altura en las dos tarjetas: la fila mide lo que mide la
    // más alta y cada tarjeta llena ese alto.
    Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HalfCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
            ) {
                PerformanceIcon(modifier = Modifier.graphicsLayer(scaleX = iconScale, scaleY = iconScale))
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
                HalfCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { tap(); onNavigate(Screen.ACHIEVEMENTS) },
                    cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                    cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                ) {
                    TrophyIcon(modifier = Modifier.graphicsLayer(scaleX = iconScale, scaleY = iconScale))
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

@Composable
private fun AdvancedRow(
    strings: AppStrings,
    onNavigate: (Screen) -> Unit,
    iconSize: IconSize = IconSize.MEDIUM,
) {
    val tap = rememberTapHaptic()
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { tap(); onNavigate(Screen.SETTINGS) }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlidersIcon(modifier = Modifier.graphicsLayer(scaleX = iconSize.sizeDp / 24f, scaleY = iconSize.sizeDp / 24f))
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

/**
 * Entrada de la portada: fundido + zoom suave (1.06 → 1.00, 650 ms).
 * Una sola vez al componer. play = false muestra la portada directamente
 * (en las vueltas a Home no se espera la entrada).
 */
@Composable
private fun CoverEntrance(play: Boolean = true, content: @Composable () -> Unit) {
    if (!play) {
        content()
        return
    }
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
    cardSize: CardSize = CardSize.NORMAL,
    cardTransparency: CardTransparency = CardTransparency.OPAQUE,
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(1.dp, ColGold.copy(alpha = 0.22f))
    val shape = RoundedCornerShape(cardSize.cornerDp.dp)
    val containerColor = ColCardElevated.copy(alpha = cardTransparency.alpha)
    val innerPadding = cardSize.paddingDp.dp
    if (onClick != null) {
        Card(
            modifier = modifier,
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
            modifier = modifier,
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

