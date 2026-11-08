package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.FrameLimiter
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SecondaryButton
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Rendimiento" (Ola 2, Etapa 5).
 *
 * Límite de FPS y Frame Skipping son SOLO-LAUNCHER (sin clave en
 * config.h) y van etiquetados así. La caché de texturas (texture-cache,
 * clave real) NO tenía interruptor en la antigua pantalla única: se
 * añade aquí. La caché de shaders se muda aquí desde la antigua
 * sección "Corrección de errores" (es una caché, no una mitigación).
 * "Restaurar valores recomendados" aplica el preset del perfil activo
 * con confirmación (vm.applyPreset, la vía que ya existe).
 */
@Composable
fun SettingsPerformanceScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    var showRestoreConfirm by rememberSaveable { mutableStateOf(false) }
    val s = settings
    SettingsCategoryScaffold(
        title = "Rendimiento",
        subtitle = "FPS, cachés, overlay y valores recomendados.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.PERFORMANCE) },
        dialogs = {
            if (showRestoreConfirm && s != null) {
                AlertDialog(
                    onDismissRequest = { showRestoreConfirm = false },
                    title = { Text("¿Restaurar valores recomendados?") },
                    text = { Text("Se aplicará el preset del perfil activo (${s.activeProfile.label}) y se perderán tus cambios manuales de rendimiento.") },
                    confirmButton = {
                        TextButton(onClick = { vm.applyPreset(s.activeProfile); showRestoreConfirm = false }) { Text("Restaurar") }
                    },
                    dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancelar") } },
                )
            }
        },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Fotogramas")
                EnumChips("Límite de FPS", FrameLimiter.entries, s.frameLimiter, { it.label }) { vm.saveSettings(s.copy(frameLimiter = it)) }
                NoteText("Ajuste solo del launcher: no hay clave de límite de FPS en config.h del núcleo.")
                EnumChips("Frame Skipping", listOf(0, 1, 2), s.frameSkipping, { "$it" }) { vm.saveSettings(s.copy(frameSkipping = it)) }
                NoteText("En el G99 Recomendado el Frame Skip es 0: el juego ya va bien, no hace falta saltar frames. Ajuste solo del launcher: sin clave en config.h.")
                SettingSwitch("Volver a 1x si baja de 28 fps", "Solo con el preset G99 Calidad (2x). Automático al registrar el FPS de la prueba.", s.autoRevertTo1xIfBelow28Fps) { vm.saveSettings(s.copy(autoRevertTo1xIfBelow28Fps = it)) }
                SettingSwitch("Modo rendimiento del launcher", "Mantiene la pantalla encendida mientras juegas.", s.performanceMode) { vm.saveSettings(s.copy(performanceMode = it)) }
            }
            item {
                SectionTitle("Cachés")
                SettingSwitch("Caché de texturas", "texture-cache en config.yml. ON recomendado: sin ella las texturas se recargan al cambiar de escena. En teléfonos con 4 GB o menos, OFF libera RAM a cambio de ese tirón.", s.textureCache) { vm.saveSettings(s.copy(textureCache = it)) }
                SettingSwitch("Caché de shaders", "shader-cache en config.yml. ON siempre: el stutter de la primera partida se va cuando la caché se llena.", s.shaderCache) { vm.saveSettings(s.copy(shaderCache = it)) }
                SettingSwitch("Caché de texturas sin hash (experimental)", "Solo texturas grandes: el núcleo vigila su memoria en vez de recalcular el hash en cada frame. Puede dar más FPS en escenas con muchas texturas. Si alguna textura se ve incorrecta, apágalo. OFF por defecto; mide con la prueba de 30 s.", s.hashlessTextureCache) { vm.saveSettings(s.copy(hashlessTextureCache = it)) }
            }
            item {
                SectionTitle("Overlay de FPS")
                SettingSwitch("Mostrar overlay de FPS", "performance-overlay en config.yml. Actívalo para la prueba de 30 s.", s.showFpsOverlay) { vm.saveSettings(s.copy(showFpsOverlay = it)) }
                EnumChips("Detalle del overlay de FPS", PerformanceOverlayDetail.entries, s.performanceOverlayDetail, { it.label }) { vm.saveSettings(s.copy(performanceOverlayDetail = it)) }
                EnumChips("Posición del overlay de FPS", PerformanceOverlayPosition.entries, s.performanceOverlayPosition, { it.label }) { vm.saveSettings(s.copy(performanceOverlayPosition = it)) }
                NoteText("El detalle y la posición solo se notan con el overlay de FPS activado.")
                SettingSwitch("Mostrar mensajes de compilación de shaders", "OFF por defecto: pantalla limpia, sin avisos de shaders al arrancar o cambiar de escena. Actívalo si quieres verlos.", s.showCompileShaders) { vm.saveSettings(s.copy(showCompileShaders = it)) }
            }
            item {
                SectionTitle("Prueba y recomendados")
                SecondaryButton("Prueba de 30 s (GPU y diagnóstico)", onClick = { onNavigate(Screen.GPU) })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { showRestoreConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar valores recomendados") }
                NoteText("Aplica el preset del perfil activo (${s.activeProfile.label}). Es lo mismo que elegir el preset en General.")
            }
        }
    }
}
