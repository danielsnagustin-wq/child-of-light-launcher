package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.trophyProgressPercent

/**
 * Pantalla principal: mínima y premium (rediseño 2026-11-07).
 *
 * En la Home SOLO queda: tarjeta del juego (banner con degradado +
 * título grande, estado, DLCs), botón JUGAR grande y dorado, insignia
 * de GPU, tarjeta compacta de logros (X/27 + barra; toca para ver la
 * lista completa) y tarjeta "Para jugar" (qué falta para jugar, con la
 * acción directa). Nada más: sin botones de importar, sin 5 botones
 * grandes, sin párrafos largos, sin texto de desarrollador.
 *
 * Todo lo demás vive en Configuración (engranaje ⚙ arriba a la derecha):
 * importar juego/DLC/update/carpeta, sesiones, rendimiento, GPU,
 * controles, guardados, avanzado. La navegación (Navigation.kt) sigue
 * igual; la Home ya no muestra esos botones.
 *
 * La portada oficial vive SOLO en el icono de la app (imagen mandada
 * por el usuario, mipmap/ic_launcher). La Home usa un banner con
 * degradado, nunca la portada.
 */
@Composable
fun HomeScreen(vm: LauncherViewModel, onNavigate: (Screen) -> Unit) {
    val status by vm.status.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val achievements by vm.achievementsFlow.collectAsState(initial = emptyList())
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())

    val strings = stringsFor(uiPrefs?.language ?: com.danielsnagustin.childoflight.launcher.model.AppLanguage.SPANISH)
    val lastFps = benchmarks.firstOrNull()?.fpsAvg
    val unlocked = achievements.count { it.unlocked }
    val percent = trophyProgressPercent(achievements)

    val gameCard: @Composable () -> Unit = {
        // Brillo dorado sutil que respira (ciclo lento de 8 s): la
        // tarjeta se siente viva. Solo un alpha animado, barato.
        val bannerGlow by rememberInfiniteTransition(label = "bannerGlow").animateFloat(
            initialValue = 0.03f,
            targetValue = 0.09f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bannerGlowAlpha",
        )
        PremiumCard {
            // Banner premium: negro profundo con el título grande.
            // Sin el arte vectorial cutre (sol/estrella) y SIN la portada
            // oficial: la portada vive solo en el icono de la app
            // (decisión del usuario). Sin texto "Etapa 1...": la
            // explicación técnica vive en Configuración → GPU y diagnóstico.
            // AMOLED: el degradado es negro (#0A0A0A → #000000), no azul marino.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(ColViolet, ColNight, ColVioletDeep)))
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(ColGold.copy(alpha = bannerGlow), Color.Transparent, Color.Transparent),
                            ),
                        ),
                )
                Column {
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
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            GoldButton(strings.play, onClick = { vm.play() })
        }
    }

    val gpuBadge: @Composable () -> Unit = {
        gpu?.let { g ->
            PremiumCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = ColSuccessGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(
                            g.shortLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            "Perfil: ${settings?.activeProfile?.label ?: "—"} · ${settings?.resolutionLabel ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ColMutedText,
                        )
                    }
                }
            }
        }
    }

    val achievementsCard: @Composable () -> Unit = {
        if (uiPrefs?.showAchievementsOnHome != false) {
            PremiumCard(onClick = { onNavigate(Screen.ACHIEVEMENTS) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🏆", fontSize = 28.sp)
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            "${strings.achievements}  $unlocked/27 ($percent%)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        GoldProgressBar(progress = if (achievements.isEmpty()) 0f else unlocked / 27f)
                    }
                    // El chevron iba pegado a la barra y parecía montarse
                    // sobre ella (además del punto dorado de la barra).
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = ColMutedText)
                }
            }
        }
    }

    // Tarjeta "Para jugar": lo que falta para poder jugar, con la acción
    // directa. La Home quedaba medio vacía y el usuario no veía por qué
    // JUGAR no hacía nada: el juego y Vita3K son dos pasos separados y
    // aquí se ven los dos. Sin esto, el estado real solo vivía en
    // Diagnóstico, donde el usuario no mira.
    val playStatusCard: @Composable () -> Unit = {
        val vita3k by vm.externalVita3k.collectAsState()
        val gameOk = status.gameInstalled
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
                if (vitaOk) "✓ Vita3K instalado ($vita3k)" else "✗ Vita3K no instalado",
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
                        confirmedInVita3k -> "✓ Instalado en Vita3K"
                        delivered -> "✓ Copiado a Descargas — falta instalarlo en Vita3K"
                        else -> "✗ Falta instalarlo en Vita3K"
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
                SecondaryButton("Instalar Vita3K", onClick = { vm.openVita3kReleases() })
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (pkgOnly && !status.pkgLicensePresent) {
                SecondaryButton("Elegir work.bin", onClick = { onNavigate(Screen.SETTINGS) })
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (pkgOnly && status.pkgLicensePresent && !delivered) {
                SecondaryButton("Instalar en Vita3K", onClick = { vm.deliverPkgToVita3k() })
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (pkgOnly && delivered && !confirmedInVita3k) {
                SecondaryButton("Ya lo instalé en Vita3K", onClick = { vm.confirmPkgInstalledInVita3k() })
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                when {
                    !gameOk -> "Importa tu copia de Child of Light (.zip, .vpk o .pkg — el nombre del archivo da igual)."
                    !vitaOk -> "Instala Vita3K para jugar: el launcher no trae núcleo embebido en Etapa 1."
                    pkgOnly && !status.pkgLicensePresent -> "Sin la licencia (work.bin) Vita3K no puede instalar el .pkg."
                    pkgOnly && !delivered -> "El .pkg está guardado. Instálalo en Vita3K para poder jugar."
                    pkgOnly && !confirmedInVita3k -> "Copiado a Descargas/ChildOfLight/. Instálalo en Vita3K (File → Install Package (.pkg)) y confirma aquí."
                    pkgOnly -> "Todo listo: pulsa JUGAR y abre el juego en la biblioteca de Vita3K."
                    else -> "Todo listo: pulsa JUGAR. Recuerda importar tu copia también dentro de Vita3K."
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            // Barra superior: título + engranaje de Configuración.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Child of Light", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(strings.appSubtitle, style = MaterialTheme.typography.bodyMedium, color = ColMutedText)
                }
                TextButton(onClick = { onNavigate(Screen.SETTINGS) }) {
                    Text("⚙", fontSize = 28.sp, color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            // El orden de secciones lo elige el usuario (GAME_CARD / GPU_CARD);
            // NAV_BUTTONS ya no se muestra en Home: vive en Configuración.
            // Aparición progresiva: cada tarjeta entra con fundido + subida,
            // con 90 ms entre ellas. Una sola vez al abrir la Home.
            val order = uiPrefs?.sectionOrder ?: HomeSection.entries.toList()
            order.forEach { section ->
                when (section) {
                    HomeSection.GAME_CARD -> { FadeSlideIn(0) { gameCard() }; Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.GPU_CARD -> { FadeSlideIn(90) { gpuBadge() }; Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.NAV_BUTTONS -> { /* En Configuración (engranaje). */ }
                }
            }
            FadeSlideIn(180) { achievementsCard() }
            Spacer(modifier = Modifier.height(16.dp))
            FadeSlideIn(270) { playStatusCard() }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
