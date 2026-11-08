package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "GPU y gráficos" (Ola 2, Etapa 2). Todas las claves son
 * reales y verificadas contra config.h del núcleo (pin 3e43bc80).
 *
 * FSR VERIFICADO (Ola 2): el string "FSR" lo acepta
 * VKState::set_screen_filter (vulkan/renderer.cpp) y existe
 * FSRScreenFilter con sus shaders EASU/RCAS
 * (vulkan/screen_filters.cpp). Si la GPU no lo soporta (hace falta
 * float16, imagen de almacenamiento y swapchain RGBA8), el núcleo
 * avisa en el log y vuelve solo a Bilinear. En OpenGL el filtro FSR
 * no existe: el parser GL cae en Bilinear por defecto. Por eso FSR
 * se queda en los chips y en los presets: es seguro elegirlo, nunca
 * rompe nada; como mucho se comporta como Bilinear.
 *
 * NO se ofrece: turbo-mode (existe, solo Adreno), spirv-shader ni
 * cpu-pool-size (existen, semántica sin verificar).
 */
@Composable
fun SettingsGpuScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val pickTurnip = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.installTurnip(uri)
    }
    val s = settings
    SettingsCategoryScaffold(
        title = "GPU y gráficos",
        subtitle = "Backend, resolución, filtros y driver.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.GPU) },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Renderizado")
                EnumChips("Backend", GpuBackend.entries, s.backend, { it.label }) { vm.saveSettings(s.copy(backend = it)) }
                EnumChips(
                    "Resolución interna",
                    listOf(1f, 2f, 3f, 4f),
                    s.resolutionMultiplier,
                    { m -> when (m) { 1f -> "1x (960x544)"; 2f -> "2x (1920x1088)"; 3f -> "3x (2880x1632)"; else -> "4x (3840x2176)" } },
                ) { vm.saveSettings(s.copy(resolutionMultiplier = it)) }
                if (gpu?.vendor == GpuVendor.MALI && s.resolutionMultiplier > 1f) {
                    NoteText("⚠ En Mali no se recomienda 2x/3x/4x. Es una opción manual: si el FPS cae, vuelve a 1x. En el G99, usa la prueba de 30 s.")
                }
                EnumChips("Filtro de pantalla", ScreenFilter.entries, s.screenFilter, { it.vita3kValue }) { vm.saveSettings(s.copy(screenFilter = it)) }
                NoteText("FSR solo funciona con Vulkan y solo si la GPU lo soporta: si no, el núcleo vuelve solo a Bilinear. En OpenGL, FSR se comporta como Bilinear. Verificado en el código del núcleo (pin 3e43bc80).")
                EnumChips("Anisotrópico", listOf(1, 2, 4, 8, 16), s.anisotropicFiltering, { "${it}x" }) { vm.saveSettings(s.copy(anisotropicFiltering = it)) }
                SettingSwitch("V-Sync", "Sincronización vertical. OFF en Rendimiento, ON en Calidad Adreno.", s.vSync) { vm.saveSettings(s.copy(vSync = it)) }
                SettingSwitch("Async Pipeline Compilation", "Compila shaders en segundo plano, menos tirones. ON siempre.", s.asyncPipelineCompilation) { vm.saveSettings(s.copy(asyncPipelineCompilation = it)) }
                SettingSwitch("High Accuracy Rendering", "Más preciso, mucho más lento. OFF en todos los presets.", s.highAccuracy) { vm.saveSettings(s.copy(highAccuracy = it)) }
                SettingSwitch("Surface Sync activado", "ON = más preciso. OFF = más rápido (recomendado en G99/Mali).", s.surfaceSyncEnabled) { vm.saveSettings(s.copy(surfaceSyncEnabled = it)) }
                EnumChips("Memory Mapping", MemoryMapping.entries, s.memoryMapping, { it.label }) { vm.saveSettings(s.copy(memoryMapping = it)) }
                NoteText(
                    when (gpu?.vendor) {
                        GpuVendor.ADRENO -> "Recomendado en Adreno: Page Table."
                        GpuVendor.MALI -> "Recomendado en Mali: Double Buffer (forzado en los presets Mali). Mali suele hacer fallback a Double Buffer aunque se elija otro."
                        else -> "Recomendado: Double Buffer. Los 5 métodos están verificados contra el núcleo (pin 3e43bc80); \"off\" equivale a desactivado."
                    },
                )
                if (gpu?.isUserVerifiedDevice == true) {
                    NoteText("✓ Helio G99 · Mali-G57 MC2 — procesador verificado por ti. El preset por defecto es G99 Recomendado: 1x estable, sin recortar de más.")
                }
            }
            item {
                SectionTitle("Driver GPU")
                if (gpu?.vendor == GpuVendor.ADRENO) {
                    EnumChips("Driver personalizado", GpuDriver.entries, s.gpuDriver, { if (it == GpuDriver.SYSTEM) "Sistema" else "Turnip" }) {
                        if (it == GpuDriver.SYSTEM) vm.useSystemDriver() else vm.saveSettings(s.copy(gpuDriver = it))
                    }
                    Text("Turnip instalado: ${if (s.turnipInstalled) s.turnipVersion else "no"}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { pickTurnip.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Instalar / cambiar Turnip (.zip)") }
                    NoteText("Turnip es el driver Mesa open-source para Adreno. Se carga solo para esta app. Al cambiar de driver, el núcleo limpia la caché de shaders al arrancar.")
                } else {
                    Text("Driver del sistema (${gpu?.renderer ?: "GPU no detectada"})", fontWeight = FontWeight.SemiBold)
                    NoteText(
                        if (gpu?.vendor == GpuVendor.MALI) {
                            "Turnip solo funciona en Qualcomm Adreno, no en Mali. Por eso no se muestra aquí."
                        } else {
                            "Turnip solo funciona en Qualcomm Adreno. Por eso no se muestra aquí."
                        },
                    )
                    if (gpu?.vendor == GpuVendor.MALI) {
                        NoteText("En Mali, si ves glitches gráficos, prueba el backend OpenGL como fallback (sección Renderizado, arriba).")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.clearShaderCache() }, modifier = Modifier.fillMaxWidth()) { Text("Limpiar caché de shaders") }
                NoteText("Limpia la caché solo si hay glitches. El stutter de la primera partida es normal hasta que la caché se vuelva a llenar.")
            }
        }
    }
}
