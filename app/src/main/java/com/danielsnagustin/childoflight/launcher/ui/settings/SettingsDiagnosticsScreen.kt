package com.danielsnagustin.childoflight.launcher.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.data.DeviceCompat
import com.danielsnagustin.childoflight.launcher.data.DeviceMemory
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.ui.ColLavenderBlue
import com.danielsnagustin.childoflight.launcher.ui.ColTurquoise
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SecondaryButton
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch
import com.danielsnagustin.childoflight.launcher.ui.cardTextColor
import com.danielsnagustin.childoflight.launcher.ui.isDarkUi
import com.danielsnagustin.childoflight.launcher.ui.mutedTextColor
import com.danielsnagustin.childoflight.launcher.ui.LocalUiPreferences

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
    // Ronda 0.2.55 P1-13: el replace y el filtrado de errores se
    // memorizan por logText: antes se re-ejecutaban en cada
    // recomposición de la pantalla (el string entero + regex por línea).
    val safeLogText = remember(logText) { logText.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher") }
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val embeddedCore by vm.embeddedCoreFlow.collectAsState()
    val s = settings
    // Ronda 0.2.70 Area 6 (6-P2-7): el ultimo FPS es un derivado del
    // State de benchmarks: con derivedStateOf solo se recalcula cuando
    // cambia la lista, no en cada recomposicion de la pantalla.
    val lastFps by remember { derivedStateOf { benchmarks.firstOrNull { it.fpsAvg != null }?.fpsAvg } }
    val recentErrors = remember(safeLogText) { extractRecentErrorLines(safeLogText) }
    // R4-P1-14: textos segun el tema (en Claro, blanco sobre fondo claro no se lee).
    val dark = isDarkUi(LocalUiPreferences.current)
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
                Text("Modelo: ${Build.MODEL ?: "no detectado"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cardTextColor(dark))
                Text("SoC: ${gpu?.socModel?.ifBlank { "no detectado" } ?: "no detectado"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                Text("CPU: ${DeviceCompat.architectureLabel()}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                Text("GPU: ${gpu?.renderer ?: "no detectada"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                val context = LocalContext.current
                val ramGb = remember { DeviceMemory.totalRamBytes(context)?.let { it / 1_073_741_824.0 } }
                Text("RAM total: ${ramGb?.let { "%.1f GB".format(it) } ?: "no detectada"}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                Text(
                    "Último FPS (prueba de 30 s): ${lastFps?.let { "$it fps" } ?: "sin medir"}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = if (lastFps != null) { if (dark) ColTurquoise else ColLavenderBlue } else mutedTextColor(dark),
                )
                Text(
                    "Núcleo embebido: ${if (embeddedCore == true) "presente" else "no presente"}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = cardTextColor(dark),
                )
                Text("Firmware: ${FirmwareResolver.label(firmwareStatus)}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                Spacer(modifier = Modifier.height(8.dp))
                // R2-P2-19: "Configuración activa" muestra la EFECTIVA
                // (la que emite el generador): effectiveSettings aplica
                // el Modo seguro (1x, SDL, High Accuracy OFF) y el
                // paquete del modo 60 FPS, y resolveBackend el fallback
                // Vulkan→OpenGL cuando el teléfono no tiene Vulkan.
                // Antes se mostraban los valores guardados, que con
                // Modo seguro o fallback no son los que corre el juego.
                val effective = effectiveSettings(s)
                // R5-A2-11: también las cachés efectivas: con 60 FPS ON
                // apply60FpsMode las fuerza ON aunque la guardada sea
                // OFF (p. ej. textureCache OFF en ≤ 4 GB), y sin esto
                // la línea mentía igual que R4-P2-10 en los demás sitios.
                // Ronda 0.2.66 (G11-06) + 0.2.68: con backend Vulkan
                // la VSync elige el modo de presentación (ON = Fifo,
                // OFF = Mailbox): se muestra el modo en vez del
                // "— (sin efecto en Vulkan)" de la 0.2.64.
                val vsyncEffectiveText = if (resolveBackend(effective, gpu) == GpuBackend.VULKAN) {
                    if (effective.vSync) "sí (Fifo)" else "no (Mailbox)"
                } else if (effective.vSync) {
                    "sí"
                } else {
                    "no"
                }
                Text("Configuración efectiva: perfil ${effective.activeProfile.label} · ${resolveBackend(effective, gpu).label} · ${effective.resolutionLabel} · filtro ${effective.screenFilter} · VSync $vsyncEffectiveText · modo 60 FPS ${if (effective.fpsHack) "sí" else "no"} · caché de texturas ${if (effective.textureCache) "sí" else "no"} · caché de shaders ${if (effective.shaderCache) "sí" else "no"} · carga rápida ${if (effective.skipShaderPrecompile) "sí" else "no"}.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                if (s.safeMode) {
                    NoteText("Modo seguro activado: los valores efectivos de arriba (1x, SDL, High Accuracy OFF) mandan sobre los guardados.")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Errores recientes del log:", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cardTextColor(dark))
                if (recentErrors.isEmpty()) {
                    Text("Sin errores recientes en el log.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = if (dark) ColTurquoise else ColLavenderBlue)
                } else {
                    recentErrors.forEach { line ->
                        Text("• $line", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = cardTextColor(dark))
                    }
                }
                NoteText("El informe de diagnóstico (GPU y diagnóstico → Reportar bug) exporta solo este texto: dispositivo, ajustes, estado y errores recientes. No adjunta ningún archivo privado.")
            }
            item {
                SectionTitle("Prueba y diagnóstico")
                SecondaryButton("GPU y diagnóstico (prueba de 30 s)", onClick = { onNavigate(Screen.GPU) })
                NoteText("GPU detectada, núcleo, prueba de 30 s con registro de FPS, log reciente y reporte de bug: todo en GPU y diagnóstico.")
                // Ronda 0.2.66 (Fase L, INST-P0-2/INST-P0-3): perfil
                // de frames. Mide la cadencia de la pantalla, el
                // tiempo del overlay y el FPS emulado mientras se
                // juega (1 línea por segundo en el log). NO mide
                // frames presentados: eso necesita el núcleo (Fase P).
                SettingSwitch("Perfil de frames (diagnóstico de lag)", "Mide cadencia de pantalla, tiempo del overlay y FPS emulado mientras juegas. No mide frames presentados.", s.frameProfile) { vm.saveSettings(s.copy(frameProfile = it)) }
                NoteText("Cómo usarlo: actívalo, juega 1 minuto en la zona donde se nota lento (no en el título) y al salir exporta el reporte de bug: trae la sección «Perfil de frames». Para medir el suelo de ruido del propio instrumento, deja el juego en pausa un minuto con el perfil activado.")
            }
            item {
                SectionTitle("Corrección de errores")
                NoteText("Mitigaciones del launcher (no reescriben el núcleo del juego). Si algo falla, empieza por el Modo seguro.")
                SettingSwitch("Cerrar apps en segundo plano antes de jugar", "Best-effort con el permiso del sistema. Libera RAM antes de lanzar.", s.closeBackgroundApps) { vm.saveSettings(s.copy(closeBackgroundApps = it)) }
                SettingSwitch("Modo seguro", "Fuerza 1x, SDL, High Accuracy OFF, sin caché sin hash, Double Buffer, driver del sistema (neutraliza Turnip), sin retraso de carga, optimización de CPU ON y audio SDL experimental OFF. Para cuando algo falle.", s.safeMode) { vm.setSafeMode(it) }
            }
        }
    }
}
