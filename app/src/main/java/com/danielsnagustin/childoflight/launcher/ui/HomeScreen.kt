package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.R
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.trophyProgressPercent

/**
 * Pantalla principal: UNA sola tarjeta de juego, Child of Light.
 * Sin biblioteca, sin lista de emulador: es un launcher de un solo juego.
 *
 * Personalización aplicada aquí: fondo elegido, idioma (portada y
 * navegación), mostrar/ocultar FPS y logros, y orden de secciones.
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
    val background = try {
        Color(android.graphics.Color.parseColor(uiPrefs?.homeBackground?.hex ?: "#0E1420"))
    } catch (_: Exception) {
        ColNight
    }
    val lastFps = benchmarks.firstOrNull()?.fpsAvg

    val pickGame = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importGame(uri)
    }
    val pickDlc = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importDlc(uri)
    }
    val pickUpdate = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUpdate(uri)
    }

    val gameCard: @Composable () -> Unit = {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ColCard),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_col_foreground),
                        contentDescription = "Portada del launcher (icono original, sin arte de Ubisoft)",
                        modifier = Modifier.size(72.dp),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Child of Light", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("PS Vita · Ubisoft · UbiArt", style = MaterialTheme.typography.bodySmall)
                        Text("${strings.state}: ${status.installedLabel}", style = MaterialTheme.typography.bodyMedium)
                        Text("${strings.dlcs}: ${status.dlcCount} · ${strings.update}: ${if (status.updateInstalled) "sí" else "no"}", style = MaterialTheme.typography.bodyMedium)
                        if (uiPrefs?.showAchievementsOnHome != false) {
                            Text("${strings.achievements}: ${achievements.count { it.unlocked }}/27 (${trophyProgressPercent(achievements)}%)", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (uiPrefs?.showFpsOnHome != false && lastFps != null) {
                            Text("FPS (última prueba): $lastFps", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { vm.play() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(strings.play, style = MaterialTheme.typography.titleMedium) }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickGame.launch("*/*") }, modifier = Modifier.weight(1f)) { Text(strings.importGame) }
                    OutlinedButton(onClick = { pickDlc.launch("*/*") }, modifier = Modifier.weight(1f)) { Text(strings.importDlc) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickUpdate.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text(strings.importUpdate) }
                }
                NoteText("Importa tu propia copia legal (.zip / .vpk / .pkg). Solo se aceptan PCSE00480 (US) y PCSB00598 (EU). El primer uso solo pide juego + DLCs: el firmware nunca es un paso inicial. El launcher no incluye juego, firmware ni claves.")
            }
        }
    }

    val gpuCard: @Composable () -> Unit = {
        gpu?.let { g ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tu GPU: ${g.shortLabel}", fontWeight = FontWeight.Bold)
                    if (g.isUserVerifiedDevice) {
                        Text("✓ Dispositivo verificado por ti: Child of Light va muy bien en este teléfono.", color = ColGold)
                    }
                    Text("SoC: ${g.socModel.ifBlank { "no detectado" }} · ${g.vulkanVersionLabel}")
                    Text("Perfil activo: ${settings?.activeProfile?.label ?: "—"} · ${settings?.resolutionLabel ?: ""}")
                }
            }
        }
    }

    val navButtons: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onNavigate(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text(strings.settings) }
            OutlinedButton(onClick = { onNavigate(Screen.GPU) }, modifier = Modifier.fillMaxWidth()) { Text(strings.gpuAndDiagnostics) }
            OutlinedButton(onClick = { onNavigate(Screen.ACHIEVEMENTS) }, modifier = Modifier.fillMaxWidth()) { Text(strings.achievements) }
            OutlinedButton(onClick = { onNavigate(Screen.TOUCH) }, modifier = Modifier.fillMaxWidth()) { Text(strings.touchControls) }
            OutlinedButton(onClick = { onNavigate(Screen.SAVES) }, modifier = Modifier.fillMaxWidth()) { Text(strings.savesAndData) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(background).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Child of Light", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(strings.appSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))

        val order = uiPrefs?.sectionOrder ?: HomeSection.entries.toList()
        order.forEach { section ->
            when (section) {
                HomeSection.GAME_CARD -> { gameCard(); Spacer(modifier = Modifier.height(12.dp)) }
                HomeSection.GPU_CARD -> { gpuCard(); Spacer(modifier = Modifier.height(12.dp)) }
                HomeSection.NAV_BUTTONS -> navButtons()
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        NoteText("Etapa 1: el núcleo Vita3K aún no está embebido. JUGAR escribe config.yml y abre Vita3K si está instalado. Ver README → Etapa 2.")
    }
}
