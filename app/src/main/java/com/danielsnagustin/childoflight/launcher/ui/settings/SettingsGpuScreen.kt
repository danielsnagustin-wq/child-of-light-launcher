package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
                // R4-P2-8: la etiqueta anterior mentía: decía "OFF en
                // Rendimiento, ON en Calidad Adreno", pero G99_QUALITY
                // también lleva vSync false; solo el preset
                // Calidad/Turnip (Adreno) lleva true.
                SettingSwitch("V-Sync", "Sincronización vertical. ON solo en el preset Calidad/Turnip (Adreno).", s.vSync) { vm.saveSettings(s.copy(vSync = it)) }
                // R2-P2-08: sin "ON siempre": el interruptor lo puede
                // apagar el usuario (los presets lo ponen ON por
                // defecto, pero nada lo fuerza).
                SettingSwitch("Async Pipeline Compilation", "Compila shaders en segundo plano, menos tirones.", s.asyncPipelineCompilation) { vm.saveSettings(s.copy(asyncPipelineCompilation = it)) }
                SettingSwitch("High Accuracy Rendering", "Más preciso, mucho más lento. OFF en todos los presets.", s.highAccuracy) { vm.saveSettings(s.copy(highAccuracy = it)) }
                SettingSwitch("Surface Sync activado", "ON = más preciso. OFF = más rápido (recomendado en G99/Mali).", s.surfaceSyncEnabled) { vm.saveSettings(s.copy(surfaceSyncEnabled = it)) }
                EnumChips("Memory Mapping", MemoryMapping.entries, s.memoryMapping, { it.label }) { vm.saveSettings(s.copy(memoryMapping = it)) }
                NoteText(
                    when (gpu?.vendor) {
                        GpuVendor.ADRENO -> "Recomendado en Adreno: Page Table."
                        GpuVendor.MALI -> "Recomendado en Mali: Double Buffer (forzado en los presets Mali)."
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
                    // R2-P2-15: sin Turnip instalado, el chip "Turnip"
                    // no se ofrece (antes se podía seleccionar sin
                    // efecto). Si el driver guardado es Turnip pero ya
                    // no está instalado, se sigue mostrando para poder
                    // volver a Sistema.
                    val driverOptions = if (s.turnipInstalled || s.gpuDriver == GpuDriver.TURNIP) {
                        GpuDriver.entries
                    } else {
                        listOf(GpuDriver.SYSTEM)
                    }
                    EnumChips("Driver personalizado", driverOptions, s.gpuDriver, { if (it == GpuDriver.SYSTEM) "Sistema" else "Turnip" }) {
                        if (it == GpuDriver.SYSTEM) vm.useSystemDriver() else vm.saveSettings(s.copy(gpuDriver = it))
                    }
                    Text("Turnip instalado: ${if (s.turnipInstalled) s.turnipVersion else "no"}", style = MaterialTheme.typography.bodyMedium)
                    if (!s.turnipInstalled) {
                        NoteText("Turnip: no instalado. Instala un archivo .zip de Turnip abajo para poder elegirlo como driver.")
                    }
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
            }
        }
    }
}
