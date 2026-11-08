package com.danielsnagustin.childoflight.launcher.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.data.DeviceCompat
import com.danielsnagustin.childoflight.launcher.data.DeviceMemory
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.ui.ColMutedText
import com.danielsnagustin.childoflight.launcher.ui.ColTurquoise
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SecondaryButton
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Diagnóstico" (Ola 2, Etapa 1; Ola 3, Etapa 10): enlace a
 * GPU y diagnóstico (prueba de 30 s, log y reporte de bug) y las
 * mitigaciones de "Corrección de errores". Esas mitigaciones no
 * reescriben el núcleo del juego; si algo falla, se empieza por el
 * Modo seguro.
 *
 * La caché de shaders NO está aquí: se mudó a Rendimiento, que es
 * donde van las cachés.
 *
 * Ola 3, Etapa 10: resumen del dispositivo en esta pantalla — SoC/CPU,
 * GPU, RAM, backend efectivo, resolución, último FPS, estado del
 * núcleo, errores recientes del log y configuración activa resumida.
 *
 * MODELO DEL DISPOSITIVO: el usuario lo pidió explícitamente para el
 * diagnóstico. Build.MODEL se muestra SOLO aquí (información técnica
 * del propio teléfono mostrada al propio usuario) y en el informe de
 * diagnóstico exportado; nunca en README, docs ni otras pantallas.
 */
@Composable
fun SettingsDiagnosticsScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())
    val logText by vm.logText.collectAsState()
    // R2-P1-02 (parte pantalla): mismo replace que la exportación del
    // reporte: el nombre del paquete no sale en texto visible.
    val safeLogText = logText.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher")
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val embeddedCore by vm.embeddedCoreFlow.collectAsState()
    val s = settings
    val lastFps = benchmarks.firstOrNull { it.fpsAvg != null }?.fpsAvg
    val recentErrors = extractRecentErrorLines(safeLogText)
    SettingsCategoryScaffold(
        title = "Diagnóstico",
        subtitle = "Prueba de 30 s y corrección de errores.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.DIAGNOSTICS) },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Resumen del dispositivo")
                // Modelo: solo en diagnóstico (ver KDoc de arriba).
                Text("Modelo: ${Build.MODEL ?: "no detectado"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("SoC: ${gpu?.socModel?.ifBlank { "no detectado" } ?: "no detectado"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Color.White)
                Text("CPU: ${DeviceCompat.architectureLabel()}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Color.White)
                Text("GPU: ${gpu?.renderer ?: "no detectada"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Color.White)
                val context = LocalContext.current
                val ramGb = remember { DeviceMemory.totalRamBytes(context)?.let { it / 1_073_741_824.0 } }
                Text("RAM total: ${ramGb?.let { "%.1f GB".format(it) } ?: "no detectada"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Color.White)
                Text(
                    "Último FPS (prueba de 30 s): ${lastFps?.let { "$it fps" } ?: "sin medir"}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = if (lastFps != null) ColTurquoise else ColMutedText,
                )
                Text(
                    "Núcleo embebido: ${if (embeddedCore == true) "presente" else "no presente"}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text("Firmware: ${FirmwareResolver.label(firmwareStatus)}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                // R2-P2-19: "Configuración activa" muestra la EFECTIVA
                // (la que emite el generador): effectiveSettings aplica
                // el Modo seguro (1x, SDL, High Accuracy OFF) y el
                // paquete del modo 60 FPS, y resolveBackend el fallback
                // Vulkan→OpenGL cuando el teléfono no tiene Vulkan.
                // Antes se mostraban los valores guardados, que con
                // Modo seguro o fallback no son los que corre el juego.
                val effective = effectiveSettings(s)
                Text("Configuración efectiva: perfil ${effective.activeProfile.label} · ${resolveBackend(effective, gpu).label} · ${effective.resolutionLabel} · filtro ${effective.screenFilter} · VSync ${if (effective.vSync) "ON" else "OFF"} · modo 60 FPS ${if (effective.fpsHack) "ON" else "OFF"}.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Color.White)
                if (s.safeMode) {
                    NoteText("Modo seguro activado: los valores efectivos de arriba (1x, SDL, High Accuracy OFF) mandan sobre los guardados.")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Errores recientes del log:", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (recentErrors.isEmpty()) {
                    Text("Sin errores recientes en el log.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = ColTurquoise)
                } else {
                    recentErrors.forEach { line ->
                        Text("• $line", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Color.White)
                    }
                }
                NoteText("El informe de diagnóstico (GPU y diagnóstico → Reportar bug) exporta solo este texto: dispositivo, ajustes, estado y errores recientes. No adjunta ningún archivo privado.")
            }
            item {
                SectionTitle("Prueba y diagnóstico")
                SecondaryButton("GPU y diagnóstico (prueba de 30 s)", onClick = { onNavigate(Screen.GPU) })
                NoteText("GPU detectada, núcleo, prueba de 30 s con registro de FPS, log reciente y reporte de bug: todo en GPU y diagnóstico.")
            }
            item {
                SectionTitle("Corrección de errores")
                NoteText("Mitigaciones del launcher (no reescriben el núcleo del juego). Si algo falla, empieza por el Modo seguro.")
                SettingSwitch("Cerrar apps en segundo plano antes de jugar", "Best-effort con el permiso del sistema. Libera RAM antes de lanzar.", s.closeBackgroundApps) { vm.saveSettings(s.copy(closeBackgroundApps = it)) }
                SettingSwitch("Modo seguro", "Fuerza 1x, SDL, High Accuracy OFF. Para cuando algo falle.", s.safeMode) { vm.setSafeMode(it) }
            }
        }
    }
}
