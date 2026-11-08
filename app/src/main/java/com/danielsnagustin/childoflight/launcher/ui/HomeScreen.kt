package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Pantalla principal: portada grande + JUGAR/IMPORTAR, Dark Fantasy
 * Premium (AMOLED #10131D + azul hielo, sin tonos cálidos).
 *
 * Rediseño 2026-11-08 (petición del usuario: portada sin logo
 * duplicado + estados reales + tarjetas pulsables + sesiones):
 * - Portada 4:3 con el arte oficial SIN logo: col_cover_clean.jpg
 *   (1024x734, recorte de col_cover_full.jpg quitando los 290 px
 *   superiores donde estaba el logo "CHILD OF LIGHT"). El título
 *   "Child of Light" queda solo como texto, sin duplicar. La
 *   proporción 1.4 (1024/734) muestra la ilustración entera con
 *   ContentScale.Fit, sin recorte ni bandas. Gradiente inferior
 *   mejorado (transparente → #10131D) y luz ambiental muy sutil
 *   (tinte azul hielo al 8% arriba).
 * - Chips de estado (Juego, DLCs, Update, Firmware) con el estado
 *   REAL en palabras (instalado / pendiente / ausente) + detalle
 *   real (Title ID, cantidad de DLCs, versión de firmware instalada
 *   o "importado" si el PUP solo está guardado), derivados del
 *   estado real con la función pura homeStatusChips. Punto turquesa
 *   = instalado, lavanda = pendiente, gris azulado = ausente.
 * - Botón principal azul hielo: JUGAR con subtítulo (Title ID ·
 *   resolución) e icono vectorial si está importado, IMPORTAR JUEGO
 *   si no (va a la pantalla de Importación, no a Configuración).
 * - Fila de dos tarjetas de DIMENSIONES IDÉNTICAS: Rendimiento (GPU
 *   + perfil compacto) y Logros (X de 27 + porcentaje real + barra).
 *   Las dos son PULSABLES: Rendimiento → GPU y diagnóstico,
 *   Logros → pantalla de Logros.
 * - Sección "Sesiones y guardados": última sesión real (nombre,
 *   última vez, tiempo jugado, saves), exportar copia (respaldo
 *   .zip real) e importar sesión, y enlace a "Ver todas". Si no
 *   hay sesiones, estado vacío honesto ("Sin sesiones todavía").
 *   Nada simulado: todo sale de SessionManager.
 * - Fila "Configuración avanzada" que abre Configuración. El estado
 *   del juego y su importación viven solo en la tarjeta superior:
 *   sin duplicados.
 * - Movimiento: la portada entra con fundido + zoom suave (una sola vez
 *   por sesión) y un brillo estático en su borde inferior; las
 *   tarjetas entran en cascada (FadeSlideIn, solo la primera vez). En las
 *   vueltas a Home la pantalla aparece al instante, sin esperar ~800 ms
 *   de animaciones. Nada de bucles infinitos ni animaciones que mantengan
 *   la GPU despierta.
 * - Cada sección recoge solo sus flujos: antes HomeScreen recogía 6
 *   flujos arriba y cualquier emisión recomponía toda la pantalla.
 * - AMOLED #10131D con acentos azul hielo; sin "Vita3K" en
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
            // El orden de secciones lo elige el usuario (GAME_CARD / GPU_CARD).
            // Aparición progresiva: cada bloque entra con fundido + subida,
            // con ~90 ms entre ellos. Solo la primera vez por sesión. La fila
            // de Logros vive dentro de la sección GPU_CARD (fila de dos
            // tarjetas: Rendimiento + Logros).
            val order = uiPrefs?.sectionOrder ?: HomeSection.entries.toList()
            order.forEach { section ->
                when (section) {
                    HomeSection.GAME_CARD -> { FadeSlideIn(0, play = !introDone) { GameCard(vm, uiPrefs, strings, introDone, onNavigate) }; Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.GPU_CARD -> { FadeSlideIn(90, play = !introDone) { InfoRow(vm, uiPrefs, strings, onNavigate) }; Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
            // Sección fija "Sesiones y guardados" (rediseño 2026-11-08):
            // no es reordenable como las tarjetas; vive siempre aquí,
            // entre las tarjetas y la fila de ajustes.
            FadeSlideIn(135, play = !introDone) { SessionsRow(vm, uiPrefs, strings, onNavigate) }
            Spacer(modifier = Modifier.height(16.dp))
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
            // Portada 4:3 sin logo: entra con zoom suave + fundido (una vez).
            // col_cover_clean.jpg (1024x734) es el arte oficial con los
            // 290 px superiores recortados (ahí estaba el logo
            // "CHILD OF LIGHT" incrustado). El título va solo como
            // texto, sin duplicar el logo. Proporción 1.4 = 1024/734:
            // ContentScale.Fit muestra la ilustración entera, sin
            // recortar nada y sin bandas. Título y subtítulo sobre
            // gradiente inferior, dentro de la tarjeta.
            CoverEntrance(play = !introDone) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ColNight),
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
                            painter = painterResource(R.drawable.col_cover_clean),
                            contentDescription = "Child of Light",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Luz ambiental muy sutil: tinte azul hielo al 8%
                    // arriba, que se desvanece. Sin animaciones infinitas.
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        ColIceBlue.copy(alpha = 0.08f),
                                        Color.Transparent,
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                    // Gradiente inferior estático para que el título se
                    // lea sobre el arte. Sin animaciones infinitas.
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Transparent,
                                        ColNight.copy(alpha = 0.55f),
                                        ColNight.copy(alpha = 0.85f),
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
                    val stateWord = when (chip.level) {
                        StatusLevel.OK -> strings.installed
                        StatusLevel.PENDING -> strings.pending
                        StatusLevel.MISSING -> strings.absent
                    }
                    StatusChip(chip, label, stateWord)
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
                    color = ColAccent,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (gameOk) {
                val subtitle = listOfNotNull(
                    status.titleId?.id,
                    settings?.resolutionLabel?.takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                PrimaryButtonWithSubtitle(strings.playShort, subtitle, onClick = { vm.play() }, enabled = !launching)
            } else {
                PrimaryButton(strings.importGame.uppercase(), onClick = { onNavigate(Screen.IMPORT) })
            }
        }
    }

// Fila de dos tarjetas: Rendimiento (GPU + perfil) y Logros (X/27).
// La de Logros respeta "Mostrar logros en la pantalla principal": si
// está oculta, Rendimiento ocupa todo el ancho. Recoge solo sus flujos.
// Las dos tarjetas son PULSABLES y tienen DIMENSIONES IDÉNTICAS
// (IntrinsicSize.Max + fillMaxHeight): Rendimiento → GPU y
// diagnóstico (prueba de 30 s), Logros → pantalla de Logros.
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
                onClick = { tap(); onNavigate(Screen.GPU) },
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
                    "${settings?.activeProfile?.label ?: "—"} · ${settings?.resolutionLabel ?: ""}",
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
                        "$unlocked de 27 · ${if (achievements.isEmpty()) 0 else unlocked * 100 / 27}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColMutedText,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PrimaryProgressBar(progress = if (achievements.isEmpty()) 0f else unlocked / 27f)
                }
            }
        }
}

/**
 * Sección "Sesiones y guardados" (rediseño 2026-11-08): última sesión
 * REAL (nombre, última vez, tiempo jugado, saves), exportar copia
 * (respaldo .zip real por SAF) e importar sesión, y enlace a ver
 * todas. Si no hay sesiones, estado vacío honesto. Nada simulado:
 * todo sale de SessionManager (vm.sessionsFlow). La copia de
 * seguridad y la restauración ya existen como funciones reales
 * (vm.exportSession / vm.importSession, las mismas de la pantalla
 * de Sesiones); aquí solo se exponen en la Home para la sesión
 * activa. El capítulo es manual (el usuario lo escribe en Sesiones);
 * aquí no se muestra si está vacío.
 */
@Composable
private fun SessionsRow(
    vm: LauncherViewModel,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
    strings: AppStrings,
    onNavigate: (Screen) -> Unit,
) {
    val sessions by vm.sessionsFlow.collectAsState(initial = emptyList())
    val activeId by vm.activeSessionIdFlow.collectAsState(initial = null)
    val tap = rememberTapHaptic()
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.maxByOrNull { it.lastPlayedAtMillis }
    // Respaldo de la sesión activa (exportar .zip por SAF) y
    // restauración (importar .zip): las mismas funciones reales de
    // la pantalla de Sesiones (SessionManager.export/importSession).
    var exportTarget by remember { mutableStateOf<com.danielsnagustin.childoflight.launcher.model.GameSession?>(null) }
    val exportSession = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val target = exportTarget
        if (uri != null && target != null) vm.exportSession(uri, target)
        exportTarget = null
    }
    val importSession = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importSession(uri)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("Sesiones y guardados")
        PremiumCard(
            cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
            cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
        ) {
            if (active == null) {
                Text(
                    "Sin sesiones todavía",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Juega una partida y aquí verás tu última sesión, el tiempo jugado y tus saves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ColMutedText,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        active.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    if (active.id == activeId) {
                        Text(
                            "ACTIVA",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = ColTurquoise,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Última vez: ${formatSessionDate(active.lastPlayedAtMillis)} · Tiempo jugado: ${active.playTimeLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ColMutedText,
                )
                Text(
                    "Saves: ${active.savedataFiles} archivos · Logros: ${active.unlockedTrophies}/27",
                    style = MaterialTheme.typography.bodySmall,
                    color = ColMutedText,
                )
                if (active.chapter.isNotBlank()) {
                    Text(
                        "Capítulo: ${active.chapter}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ColMutedText,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { exportTarget = active; exportSession.launch("col-sesion-${active.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(30).ifBlank { "sesion" }}.zip") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Exportar copia") }
                    OutlinedButton(
                        onClick = { importSession.launch("*/*") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Importar") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("Ver todas las sesiones", onClick = { tap(); onNavigate(Screen.SAVES) })
        }
    }
}

private fun formatSessionDate(millis: Long): String {
    if (millis <= 0L) return "nunca"
    return try {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))
    } catch (_: Exception) {
        "—"
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
 * PremiumCard (superficie #1A2036, borde azul hielo sutil) pero con ancho por
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
    val border = BorderStroke(1.dp, ColAccent.copy(alpha = 0.22f))
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

