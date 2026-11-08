package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Rendimiento" (Ola 2, Etapa 5).
 *
 * La caché de texturas (texture-cache,
 * clave real) NO tenía interruptor en la antigua pantalla única: se
 * añade aquí. La caché de shaders se muda aquí desde la antigua
 * sección "Corrección de errores" (es una caché, no una mitigación),
 * junto con su botón de limpieza (antes en GPU y gráficos).
 * "Restaurar valores recomendados" NO está aquí: es lo mismo que
 * "Restablecer ajustes (preset de tu GPU)" en General, que es su
 * sitio canónico. La prueba de 30 s vive en Diagnóstico
 * ("GPU y diagnóstico (prueba de 30 s)").
 */
@Composable
fun SettingsPerformanceScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val s = settings
    SettingsCategoryScaffold(
        title = "Rendimiento",
        subtitle = "FPS, cachés y overlay.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.PERFORMANCE) },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Fotogramas")
                SettingSwitch("Volver a 1x si baja de 28 fps", "Solo con el preset G99 Calidad (2x). Automático al registrar el FPS de la prueba.", s.autoRevertTo1xIfBelow28Fps) { vm.saveSettings(s.copy(autoRevertTo1xIfBelow28Fps = it)) }
                SettingSwitch("Modo rendimiento del launcher", "Mantiene la pantalla encendida mientras juegas.", s.performanceMode) { vm.saveSettings(s.copy(performanceMode = it)) }
            }
            item {
                SectionTitle("Cachés")
                SettingSwitch("Caché de texturas", "texture-cache en config.yml. ON recomendado: sin ella las texturas se recargan al cambiar de escena. En teléfonos con 4 GB o menos, OFF libera RAM a cambio de ese tirón.", s.textureCache) { vm.saveSettings(s.copy(textureCache = it)) }
                // R2-P2-08: sin "ON siempre": el interruptor lo puede
                // apagar el usuario (los presets lo ponen ON por
                // defecto, pero nada lo fuerza).
                SettingSwitch("Caché de shaders", "shader-cache en config.yml: el stutter de la primera partida se va cuando la caché se llena.", s.shaderCache) { vm.saveSettings(s.copy(shaderCache = it)) }
                OutlinedButton(onClick = { vm.clearShaderCache() }, modifier = Modifier.fillMaxWidth()) { Text("Limpiar caché de shaders") }
                NoteText("Limpia la caché solo si hay glitches. El stutter de la primera partida es normal hasta que la caché se vuelva a llenar.")
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
        }
    }
}
