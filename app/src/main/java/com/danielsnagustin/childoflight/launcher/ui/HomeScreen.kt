package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielsnagustin.childoflight.launcher.R
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.trophyProgressPercent

/**
 * Pantalla principal: mínima y premium (rediseño 2026-11-07).
 *
 * En la Home SOLO queda: tarjeta del juego (arte vectorial propio con
 * marco, estado, DLCs), botón JUGAR grande y dorado, insignia de GPU
 * y tarjeta compacta de logros (X/27 + barra; toca para ver la lista
 * completa). Nada más: sin botones de importar, sin 5 botones grandes,
 * sin párrafos largos.
 *
 * Todo lo demás vive en Configuración (engranaje ⚙ arriba a la derecha):
 * importar juego/DLC/update/carpeta, sesiones, rendimiento, GPU,
 * controles, guardados, avanzado. La navegación (Navigation.kt) sigue
 * igual; la Home ya no muestra esos botones.
 *
 * La portada oficial vive SOLO en el icono de la app (imagen mandada
 * por el usuario, mipmap/ic_launcher). La Home usa el arte vectorial
 * propio, nunca la portada.
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
        PremiumCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ColNight)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_col_foreground),
                        contentDescription = "Arte del launcher (vectorial propio, sin arte de Ubisoft)",
                        modifier = Modifier.size(76.dp),
                    )
                }
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("Child of Light", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("PS Vita · Ubisoft · UbiArt", style = MaterialTheme.typography.bodySmall, color = ColMutedText)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${strings.state}: ${status.installedLabel}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Etapa 1: JUGAR escribe config.yml y abre Vita3K si está instalado.",
                style = MaterialTheme.typography.bodySmall,
                color = ColMutedText,
            )
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
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = ColMutedText)
                }
            }
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
            val order = uiPrefs?.sectionOrder ?: HomeSection.entries.toList()
            order.forEach { section ->
                when (section) {
                    HomeSection.GAME_CARD -> { gameCard(); Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.GPU_CARD -> { gpuBadge(); Spacer(modifier = Modifier.height(16.dp)) }
                    HomeSection.NAV_BUTTONS -> { /* En Configuración (engranaje). */ }
                }
            }
            achievementsCard()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
