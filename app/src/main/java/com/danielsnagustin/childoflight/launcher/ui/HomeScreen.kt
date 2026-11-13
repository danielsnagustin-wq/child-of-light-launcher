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
import androidx.compose.material3.LinearProgressIndicator
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
import com.danielsnagustin.childoflight.launcher.data.DeviceCompat
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.IconSize
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_20
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Pantalla principal: portada grande + JUGAR/IMPORTAR, Dark Fantasy
 * Premium (AMOLED #10131D + azul hielo, sin tonos cálidos).
 *
 * Rediseño 2026-11-08 (petición del usuario: portada sin logo
 * duplicado + estados reales + tarjetas pulsables + sesiones):
 * - Portada 1,4:1 con el arte oficial SIN logo: col_cover_clean.jpg
 *   (1024x734, recorte de col_cover_full.jpg quitando los 290 px
 *   superiores donde estaba el logo "CHILD OF LIGHT"). El título
 *   "Child of Light" queda solo como texto, sin duplicar. La
 *   proporción 1.4 (1024/734) muestra la ilustración entera con
 *   ContentScale.Fit, sin recorte ni bandas. Gradiente inferior
 *   mejorado (transparente → #10131D) y luz ambiental muy sutil
 *   (tinte azul hielo al 8% arriba).
 * - Botón principal azul hielo: JUGAR con subtítulo (Title ID ·
 *   resolución) e icono vectorial si está importado, IMPORTAR JUEGO
 *   si no (va a la pantalla de Importación, no a Configuración).
 * - Tarjeta Logros (X de 20 + porcentaje real + barra), a todo lo
 *   ancho, PULSABLE: abre la pantalla de Logros. Es la segunda tarjeta
 *   del Inicio (la primera es la del juego): el estado (juego/DLCs/
 *   firmware) vive en Importar y en Diagnóstico, Rendimiento en
 *   Ajustes, y las sesiones en Ajustes → Almacenamiento → "Sesiones y
 *   guardados". Sin duplicados.
 * - Sección "Sesiones y guardados" (R7-P1-07): resumen real del índice
 *   de sesiones (sesión activa, nº de sesiones, logros y saves) con
 *   navegación a Guardados/Sesiones; el editor completo vive ahí, no
 *   se duplica en Home.
 * - Fila "Configuración avanzada" que abre Configuración. El estado
 *   del juego y su importación viven solo en la tarjeta superior:
 *   sin duplicados.
 * - Movimiento: la portada entra con fundido + zoom suave (una sola vez
 *   por instalación, R4-P2-6: marca persistente homeIntroSeen en
 *   UiPreferences) y un brillo estático en su borde inferior; las
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
    // R5-A1-5: se invoca al EMPEZAR la intro (primer frame), no al
    // terminarla: si el usuario sale de Home antes de los 900 ms o
    // gira el teléfono, la marca ya está persistida y la intro no
    // vuelve a sonar. MainActivity lo pasa como vm.markHomeIntroSeen().
    onIntroStarted: () -> Unit = {},
    onNavigate: (Screen) -> Unit,
) {
    val uiPrefs = LocalUiPreferences.current
    val strings = stringsFor(uiPrefs?.language ?: com.danielsnagustin.childoflight.launcher.model.AppLanguage.SPANISH)

    // La intro (portada + tarjetas) solo se reproduce una vez por
    // instalación (R4-P2-6): introDone viene de la marca persistente
    // UiPreferences.homeIntroSeen (MainActivity), no de una bandera por
    // sesión. AnimatedContent descarta y recrea la composición de Home
    // en cada navegación, así que sin esta bandera la entrada se
    // repetía (~800 ms) en cada vuelta a Home. 900 ms = portada 650 ms
    // + último retardo 180 ms. Al terminar, onIntroDone() persiste la
    // marca (ViewModel.markHomeIntroSeen) y la intro no vuelve a sonar.
    // R5-A1-5: la marca se persiste además AL EMPEZAR (onIntroStarted,
    // antes del delay): si el usuario sale de Home antes de los 900 ms
    // o gira el teléfono a mitad de intro, el LaunchedEffect se cancela
    // y la marca ya está escrita, así que la intro no se repite. Y la
    // animación no arranca hasta que uiPrefs != null: mientras DataStore
    // aún no emitió tras recrear la Activity, introDone es false y la
    // intro arrancaba de nuevo sin haber marcado nada.
    if (!introDone && uiPrefs != null) {
        LaunchedEffect(Unit) {
            onIntroStarted()
            delay(900)
            onIntroDone()
        }
    }

    // P-C4: el fondo de la pantalla entera se memoriza por sus dos
    // entradas exactas (hex del fondo elegido y tema oscuro/claro).
    val homeBackgroundHex = uiPrefs?.homeBackground?.hex
    val homeDark = isDarkUi(uiPrefs)
    val homeBackground = remember(homeBackgroundHex, homeDark) {
        homeBackgroundBrush(homeBackgroundHex, homeDark)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(homeBackground),
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
            // El orden de secciones lo elige el usuario (GAME_CARD / LOGROS_CARD / SESSIONS_CARD).
            // Aparición progresiva: cada bloque entra con fundido + subida,
            // con ~90 ms entre ellos. Solo la primera vez por instalación. La
            // tarjeta de Logros vive dentro de la sección LOGROS_CARD.
            val order = uiPrefs?.sectionOrder ?: HomeSection.entries.toList()
            order.forEach { section ->
                when (section) {
                    HomeSection.GAME_CARD -> { FadeSlideIn(0, play = !introDone) { GameCard(vm, uiPrefs, strings, introDone, onNavigate) }; Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.LOGROS_CARD -> { FadeSlideIn(90, play = !introDone) { InfoRow(vm, uiPrefs, strings, onNavigate) }; Spacer(modifier = Modifier.height(8.dp)) }
                    HomeSection.SESSIONS_CARD -> { FadeSlideIn(135, play = !introDone) { SessionsSummaryRow(vm, uiPrefs, onNavigate) }; Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            FadeSlideIn(180, play = !introDone) { AdvancedRow(strings, onNavigate, uiPrefs, iconSize = uiPrefs?.iconSize ?: IconSize.MEDIUM) }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// Tarjeta superior del juego (portada + JUGAR/IMPORTAR). Recoge
// solo sus flujos: estado, ajustes y lanzamiento.
@Composable
private fun GameCard(
    vm: LauncherViewModel,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
    strings: AppStrings,
    introDone: Boolean,
    onNavigate: (Screen) -> Unit,
) {
    val status by vm.status.collectAsState()
    // Ronda 0.2.55 P0-1: hasta la primera emisión real del escaneo,
    // la tarjeta muestra "Comprobando…" en vez de IMPORTAR: con el
    // GameStatus() vacío inicial, Home mentía "pendiente de importar"
    // aunque el juego estuviera instalado.
    val statusLoaded by vm.statusLoaded.collectAsState()
    // R6-P1-12: si el escaneo FALLÓ, ni "Comprobando…" para siempre
    // ni IMPORTAR mintiendo: error explícito con reintento.
    val statusError by vm.statusError.collectAsState()
    // Deshabilitar JUGAR mientras hay un lanzamiento en curso (doble
    // toque = dos launch() a la vez); la guarda vive en el ViewModel.
    val launching by vm.launchingFlow.collectAsState()
    // Personalización que funciona (0.2.15): el acento y el tema
    // elegidos por el usuario; antes el inicio pintaba Col* fijos.
    val dark = isDarkUi(uiPrefs)
    val accent = rememberedUserAccent(uiPrefs)
    // P1-16: el progreso de instalación NO se recoge aquí arriba:
    // cada % recomponía TODO el Inicio. Lo recoge LaunchProgressCard
    // dentro de sí (le pasamos el flujo), así solo la tarjeta se
    // recompone con cada %.
    val cover by vm.coverBitmap.collectAsState()

    val gameOk = status.gameInstalled

    PremiumCard(
        cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
        cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
    ) {
            // Portada 1.4:1 sin logo: entra con zoom suave + fundido (una vez).
            // col_cover_clean.jpg (1024x734) es el arte oficial con los
            // 290 px superiores recortados (ahí estaba el logo
            // "CHILD OF LIGHT" incrustado). El título va solo como
            // texto, sin duplicar el logo. Proporción 1.4 = 1024/734:
            // ContentScale.Fit muestra la ilustración entera, sin
            // recortar nada y sin bandas. Título y subtítulo sobre
            // gradiente inferior, dentro de la tarjeta.
            // P-C1: los dos gradientes de la portada se memorizan
            // (el ambiental depende del acento; el inferior es
            // constante) para no recrearlos en cada recomposición.
            val ambientGradient = remember(accent) {
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.08f),
                        Color.Transparent,
                        Color.Transparent,
                    ),
                )
            }
            val bottomGradient = remember {
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Transparent,
                        ColNight.copy(alpha = 0.55f),
                        ColNight.copy(alpha = 0.85f),
                    ),
                )
            }
            CoverEntrance(play = !introDone) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.4f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ColNight),
                ) {
                    // La imagen se decodifica UNA vez en el ViewModel (hilo
                    // IO, inSampleSize=2) y se reutiliza. Mientras llega,
                    // el respaldo es la miniatura (384x216, mucho más
                    // barata que decodificar la portada entera en Main
                    // en cada entrada a Home, como hacía antes el
                    // painterResource de col_cover_clean).
                    if (cover != null) {
                        Image(
                            bitmap = cover!!,
                            contentDescription = "Child of Light",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.col_cover_thumb),
                            contentDescription = "Child of Light",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Luz ambiental muy sutil: tinte del acento al 8%
                    // arriba, que se desvanece. Sin animaciones infinitas.
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(ambientGradient),
                    )
                    // Gradiente inferior estático para que el título se
                    // lea sobre el arte. Sin animaciones infinitas.
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(bottomGradient),
                    )
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    ) {
                        Text("Child of Light", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("PS Vita · Ubisoft · UbiArt", style = MaterialTheme.typography.bodyMedium, color = ColIceBlue)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (!statusLoaded) {
                // Ronda 0.2.55 P0-1: estado honesto mientras el primer
                // escaneo no terminó: ni JUGAR ni IMPORTAR, porque aún
                // no se sabe si el juego está instalado. Botón
                // deshabilitado, sin inventar ningún estado.
                // R6-P1-12: si el escaneo falló, el error es explícito
                // y se puede reintentar (antes Home pasaba a IMPORTAR
                // con un estado vacío que mentía).
                Spacer(modifier = Modifier.height(10.dp))
                if (statusError) {
                    Text(
                        "No se pudo comprobar el estado",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        // R9-P1-03: acento crudo ilegible en tema claro
                        // (1.53:1); en claro azul lavanda #6578B8.
                        color = if (dark) accent else ColLavenderBlue,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "El escaneo del almacenamiento falló. Puedes reintentarlo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor(dark),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SecondaryButton("Reintentar", onClick = { vm.retryStatusCheck() })
                } else {
                    Text(
                        "Comprobando…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        // R9-P1-03: acento crudo ilegible en tema claro;
                        // en claro azul lavanda #6578B8.
                        color = if (dark) accent else ColLavenderBlue,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    PrimaryButton("Comprobando…", onClick = {}, enabled = false)
                }
            } else {
                // R7-P1-12 parcial (0.2.57): un refresco POSTERIOR
                // fallido conserva el estado viejo, pero ya no lo
                // esconde: se avisa que está desactualizado, con
                // reintento, sin bloquear JUGAR ni IMPORTAR sobre el
                // último estado conocido.
                if (statusError) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "⚠ No se pudo actualizar el estado",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        // R9-P1-03: acento crudo ilegible en tema claro;
                        // en claro azul lavanda #6578B8.
                        color = if (dark) accent else ColLavenderBlue,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Se muestra el último estado conocido, que puede estar desactualizado.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor(dark),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton("Reintentar", onClick = { vm.retryStatusCheck() })
                }
                if (!gameOk) {
                    Spacer(modifier = Modifier.height(10.dp))
                    // P-G3: el aviso va en un contenedor con el tinte
                    // del acento al 8% (mismo patrón que la luz
                    // ambiental de la portada); el texto se mantiene
                    // en el acento elegido por el usuario.
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.08f))
                            .padding(12.dp),
                    ) {
                        Text(
                            "⚠ ${strings.pendingImport}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            // R9-P1-03: acento crudo ilegible en tema claro;
                            // en claro azul lavanda #6578B8.
                            color = if (dark) accent else ColLavenderBlue,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (gameOk) {
                    PlayButton(vm, status, launching, strings)
                    // R6-P2-17: UNA línea de estado real bajo JUGAR
                    // (DLCs, firmware y update, datos que ya trae
                    // GameStatus). Sin tarjetas ni secciones nuevas: el
                    // diseño de Home se mantiene mínimo.
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "DLCs: ${status.installedDlcCount} instalados" + (if (status.storedDlcPkgCount > 0) " (+${status.storedDlcPkgCount} en .pkg)" else "") + " · ${status.firmwareName ?: "firmware no instalado"} · Actualización: ${if (status.updateInstalled) "instalado" else "no instalado"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor(dark),
                    )
                } else {
                    PrimaryButton(strings.importGame.uppercase(), onClick = { onNavigate(Screen.IMPORT) })
                }
                // Ronda 0.2.65 (T-P2-2): el aviso de 32 bits ya no vive
                // dentro de la rama gameOk: en un teléfono de 32 bits
                // sin juego instalado no se veía y el usuario podía
                // importar (proceso largo) para descubrir al pulsar
                // JUGAR que el motor no se puede abrir. Se muestra
                // siempre que el estado ya cargó y el teléfono es de
                // 32 bits, con juego o sin él.
                if (statusLoaded && !DeviceCompat.is64BitDevice()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Este teléfono es de 32 bits y el motor integrado necesita 64 bits (arm64). En este teléfono el juego no se puede abrir con el motor integrado. Funciona en teléfonos con procesador de 64 bits y Android 9 o superior (por ejemplo, Helio G99, Snapdragon, Dimensity, Exynos o Tensor de 64 bits).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Mientras JUGAR trabaja (sobre todo instalando el .pkg en
            // el núcleo, varios minutos): tarjeta de progreso visible
            // debajo del botón, con el % real del núcleo cuando lo hay.
            if (launching) {
                Spacer(modifier = Modifier.height(12.dp))
                LaunchProgressCard(vm.installProgress, uiPrefs)
            }
        }
    }

/**
 * Botón JUGAR con su subtítulo (Title ID · resolución).
 *
 * Ronda 0.2.55 (refuerzo, hallazgo extra no listado en la auditoría):
 * GameCard recogía settingsFlow arriba solo para el subtítulo, así
 * que cada cambio de ajuste recomponía la tarjeta entera (portada
 * incluida). El subtítulo se recoge aquí dentro: solo el botón
 * recompone cuando cambia un ajuste.
 */
@Composable
private fun PlayButton(
    vm: LauncherViewModel,
    status: GameStatus,
    launching: Boolean,
    strings: AppStrings,
) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val subtitle = listOfNotNull(
        status.titleId?.id,
        settings?.resolutionLabel?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    PrimaryButtonWithSubtitle(strings.playShort, subtitle, onClick = { vm.play() }, enabled = !launching)
}

/**
 * Tarjeta de progreso del lanzamiento (Home): % real del núcleo +
 * su mensaje cuando installProgress trae percent; si no, texto
 * "Preparando el juego…" + barra indeterminada. El % nunca se
 * inventa: solo se muestra el de installProgress. Azul hielo /
 * turquesa sobre #10131D, sin dorado.
 *
 * P1-16: recibe el StateFlow y lo recoge DENTRO: cada % recompone
 * solo esta tarjeta, no todo el Inicio (antes GameCard recogía el
 * flujo arriba y cada emisión recomponía portada, chips y tarjetas).
 * P2-37: el % se recoge SOLO en InstallProgressContent (el texto +
 * la barra que lo muestran): el armazón de la tarjeta (fondo, aviso
 * "No cierres la app") ya no recompone con cada %.
 */
@Composable
private fun LaunchProgressCard(
    installProgressFlow: StateFlow<InstallProgress?>,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
) {
    // Personalización que funciona (0.2.15): fondo y textos según el
    // tema, acento del usuario en el texto pulsante.
    val dark = isDarkUi(uiPrefs)
    val accent = rememberedUserAccent(uiPrefs)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor(dark))
            .padding(12.dp),
    ) {
        InstallProgressContent(installProgressFlow, dark, accent)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "No cierres la app. Instalar el juego puede tardar varios minutos.",
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor(dark),
        )
    }
}

/**
 * Contenido que cambia con el progreso (P2-37): es el ÚNICO que
 * recoge installProgress. El texto del % y la barra leen el mismo
 * estado, así que van juntos aquí; el resto de la tarjeta es estático.
 */
@Composable
private fun InstallProgressContent(
    installProgressFlow: StateFlow<InstallProgress?>,
    dark: Boolean,
    accent: androidx.compose.ui.graphics.Color,
) {
    // Progreso REAL de la instalación del .pkg en el núcleo (el
    // ViewModel lo alimenta en play() vía onInstallProgress): antes
    // Home nunca lo recogía y al pulsar JUGAR con solo .pkg el botón
    // se apagaba sin mostrar nada durante varios minutos (1,14 GB),
    // así que parecía que el juego no iniciaba. Ahora se ve aquí.
    val installProgress by installProgressFlow.collectAsState()
    val progress = installProgress
    if (progress?.percent != null) {
        Text(
            "Instalando en el núcleo… ${progress.percent}% — ${progress.message}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = cardTextColor(dark),
        )
        Spacer(modifier = Modifier.height(10.dp))
        PrimaryProgressBar(progress = (progress.percent ?: 0) / 100f)
    } else {
        PulsingText(
            progress?.message ?: "Preparando el juego…",
            // R9-P1-03: acento crudo ilegible en tema claro; en claro
            // azul lavanda #6578B8 (dark y accent llegan por parámetro).
            if (dark) accent else ColLavenderBlue,
            MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// Tarjeta Logros (X/20 + porcentaje real + barra), a todo lo ancho:
// siempre se muestra (2026-11-08: los trofeos se quedan en el inicio;
// Rendimiento vive en Ajustes y en Diagnóstico). PULSABLE: abre la
// pantalla de Logros.
@Composable
private fun InfoRow(
    vm: LauncherViewModel,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
    strings: AppStrings,
    onNavigate: (Screen) -> Unit,
) {
    // P2-11: sin "0 de 20 · 0%" transitorio: antes de la primera emisión
    // del flujo la tarjeta mentía (0 trofeos) aunque el usuario los
    // tuviera. El total se deriva de la lista (no hardcodeado a 20).
    val achievements by vm.achievementsFlow.collectAsState(initial = null)
    val unlocked = achievements?.count { it.unlocked } ?: 0
    val total = achievements?.size ?: 0
    val tap = rememberTapHaptic()
    // Personalización que funciona (0.2.15): textos según el tema
    // elegido por el usuario (el borde y el fondo los resuelve HalfCard).
    val dark = isDarkUi(uiPrefs)
    // Los iconos vectoriales dibujan a 24 dp fijos: se escalan por
    // graphicsLayer (vectorial, sin pérdida) según el ajuste de tamaño.
    val iconScale = (uiPrefs?.iconSize ?: IconSize.MEDIUM).sizeDp / 24f
    Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HalfCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { tap(); onNavigate(Screen.ACHIEVEMENTS) },
                cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                uiPrefs = uiPrefs,
            ) {
                TrophyIcon(modifier = Modifier.graphicsLayer(scaleX = iconScale, scaleY = iconScale))
                Spacer(modifier = Modifier.height(8.dp))
                Text(strings.achievementsShort, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cardTextColor(dark))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (achievements == null) {
                        "…"
                    } else {
                        "$unlocked de $total · ${if (total == 0) 0 else unlocked * 100 / total}%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor(dark),
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryProgressBar(progress = if (total == 0) 0f else unlocked / total.toFloat())
            }
        }
}

// Resumen de "Sesiones y guardados" (R7-P1-07, pedida por el usuario
// el 2026-11-08): SOLO un resumen con datos reales del índice de
// sesiones — las mismas fuentes que SessionsSection (sessionsFlow /
// activeSessionIdFlow) — y navegación a Guardados/Sesiones, donde vive
// el editor completo (crear, continuar, renombrar, duplicar, exportar,
// importar, borrar). No se duplica nada del editor aquí. PULSABLE:
// abre la pantalla de Guardados.
@Composable
private fun SessionsSummaryRow(
    vm: LauncherViewModel,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
    onNavigate: (Screen) -> Unit,
) {
    val sessions by vm.sessionsFlow.collectAsState(initial = emptyList())
    val activeId by vm.activeSessionIdFlow.collectAsState(initial = null)
    val dark = isDarkUi(uiPrefs)
    val tap = rememberTapHaptic()
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
    HalfCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { tap(); onNavigate(Screen.SAVES) },
        cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
        cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
        uiPrefs = uiPrefs,
    ) {
        Text("Sesiones y guardados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cardTextColor(dark))
        Spacer(modifier = Modifier.height(4.dp))
        if (sessions.isEmpty()) {
            Text(
                "Sin sesiones todavía · Se crea sola al importar el juego",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor(dark),
            )
        } else {
            Text(
                "${sessions.size} ${if (sessions.size == 1) "sesión" else "sesiones"} · Activa: ${active?.name ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor(dark),
            )
            if (active != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Logros: ${active.unlockedTrophies} de ${TROPHIES_20.size} · Partidas guardadas: ${active.savedataFiles} archivos",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor(dark),
                )
            }
        }
    }
}

@Composable
private fun AdvancedRow(
    strings: AppStrings,
    onNavigate: (Screen) -> Unit,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences?,
    iconSize: IconSize = IconSize.MEDIUM,
) {
    // Personalización que funciona (0.2.15): textos según el tema.
    val dark = isDarkUi(uiPrefs)
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
                color = cardTextColor(dark),
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Text("›", style = MaterialTheme.typography.headlineMedium, color = mutedTextColor(dark))
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
 * Tarjeta premium (ancho por peso en su fila; hoy es la única hija y
 * ocupa todo el ancho): mismo estilo que PremiumCard (superficie
 * #1A2036, borde azul hielo sutil). Borde con el acento del usuario y
 * superficie según el tema (0.2.15).
 */
@Composable
private fun HalfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cardSize: CardSize = CardSize.NORMAL,
    cardTransparency: CardTransparency = CardTransparency.OPAQUE,
    uiPrefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences? = null,
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(1.dp, rememberedUserAccent(uiPrefs).copy(alpha = 0.22f))
    val shape = RoundedCornerShape(cardSize.cornerDp.dp)
    val containerColor = cardElevatedColor(isDarkUi(uiPrefs)).copy(alpha = cardTransparency.alpha)
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

