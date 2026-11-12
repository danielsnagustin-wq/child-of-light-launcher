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
import com.danielsnagustin.childoflight.launcher.model.isSupportedByMask
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
                    listOf(0.5f, 1f, 1.25f, 1.5f, 2f, 3f, 4f),
                    s.resolutionMultiplier,
                    { m -> when (m) { 0.5f -> "0.5x (480x272)"; 1f -> "1x (960x544)"; 1.25f -> "1.25x (1200x680)"; 1.5f -> "1.5x (1440x816)"; 2f -> "2x (1920x1088)"; 3f -> "3x (2880x1632)"; else -> "4x (3840x2176)" } },
                ) { vm.saveSettings(s.copy(resolutionMultiplier = it)) }
                if (s.resolutionMultiplier == 0.5f) {
                    NoteText("0.5x baja la resolución interna a la mitad (480x272): el juego se ve más borroso, pero es la opción más ligera si una escena va lenta. El núcleo lo acepta (resolution-multiplier es float sin límite en el núcleo).")
                }
                if (gpu?.vendor == GpuVendor.MALI && s.resolutionMultiplier > 1f) {
                    NoteText("⚠ En Mali no se recomienda 2x/3x/4x. Es una opción manual: si el FPS cae, vuelve a 1x. En el G99, usa la prueba de 30 s.")
                }
                EnumChips("Filtro de pantalla", ScreenFilter.entries, s.screenFilter, { it.vita3kValue }) { vm.saveSettings(s.copy(screenFilter = it)) }
                NoteText("FSR solo funciona con Vulkan y solo si la GPU lo soporta: si no, el núcleo vuelve solo a Bilinear sin avisar en la pantalla (solo queda en el log del núcleo). En OpenGL, FSR se comporta como Bilinear. Verificado en el código del núcleo (pin 3e43bc80).")
                // Ronda 0.2.64 (P2-2 Mali): los valores por encima del
                // máximo del dispositivo (GpuInfo.maxAnisotropy, leído
                // por GLES) no se ofrecen: el núcleo no limita el valor
                // y por encima del límite es uso inválido de Vulkan.
                // Sin máximo conocido se ofrecen todos (1x–16x).
                val anisoOptions = gpu?.maxAnisotropy?.let { max ->
                    listOf(1, 2, 4, 8, 16).filter { it <= max }
                } ?: listOf(1, 2, 4, 8, 16)
                EnumChips("Anisotrópico", anisoOptions, s.anisotropicFiltering, { "${it}x" }) { vm.saveSettings(s.copy(anisotropicFiltering = it)) }
                if (gpu?.maxAnisotropy != null) {
                    NoteText("Anisotrópico máximo de este teléfono: ${gpu?.maxAnisotropy}x (límite del driver).")
                }
                // Ronda 0.2.64 (consenso de 3 auditorías): con Vulkan
                // la VSync es INERTE — solo alimenta pending_vsync,
                // que solo consume el renderer OpenGL (pin:
                // gl/renderer.cpp:759-760); el modo de presentación
                // Vulkan es fijo (Mailbox > FifoRelaxed > Fifo).
                // Ningún preset la pone ON desde 0.2.64 (el modo 60
                // FPS tampoco: su único mecanismo real es fps-hack).
                SettingSwitch("V-Sync", "Sincronización vertical. Sin efecto con Vulkan (solo actúa con backend OpenGL).", s.vSync) { vm.saveSettings(s.copy(vSync = it)) }
                if (s.backend == GpuBackend.VULKAN) {
                    NoteText("Con Vulkan, la VSync no hace nada: ni cuesta frames ni los gana. Solo tiene efecto con el backend OpenGL.")
                }
                // R2-P2-08: sin "ON siempre": el interruptor lo puede
                // apagar el usuario (los presets lo ponen ON por
                // defecto, pero nada lo fuerza).
                SettingSwitch("Async Pipeline Compilation", "Compila shaders en segundo plano, menos tirones.", s.asyncPipelineCompilation) { vm.saveSettings(s.copy(asyncPipelineCompilation = it)) }
                SettingSwitch("High Accuracy Rendering", "Más preciso, mucho más lento. OFF en todos los presets.", s.highAccuracy) { vm.saveSettings(s.copy(highAccuracy = it)) }
                SettingSwitch("Surface Sync activado", "ON = más preciso. OFF = más rápido (recomendado en G99/Mali).", s.surfaceSyncEnabled) { vm.saveSettings(s.copy(surfaceSyncEnabled = it)) }
                // Ronda 0.2.64 (P2-1 Mali): la Surface Sync es inerte
                // con el mapeo OFF — todos los caminos Vulkan que la
                // usan exigen mapeo activo (transfer.cpp:251,343;
                // vulkan/context.cpp:626; surface_cache.cpp:1210) y
                // en Vulkan el camino de escena se salta
                // (scene.cpp:150). Con Page Table (Adreno) puede
                // actuar; con Double Buffer (Mali) no funciona según
                // las notas de Vita3K-Android v7.
                if (!s.surfaceSyncEnabled && s.memoryMapping == MemoryMapping.OFF) {
                    NoteText("Con el mapeo de memoria OFF, la Surface Sync no hace nada: solo tiene efecto con un método de mapeo activo. En Vulkan, la precisión extra va por la caché de superficies.")
                }
                EnumChips("Memory Mapping", MemoryMapping.entries, s.memoryMapping, { it.label }) { vm.saveSettings(s.copy(memoryMapping = it)) }
                NoteText(
                    when (gpu?.vendor) {
                        // Ronda 0.2.64 (P1-1 Adreno): quitado el
                        // "recomendado en Adreno": las fuentes se
                        // contradicen (Vita3K-Android v5: Native
                        // Buffer en Adreno / Page Table en Mali;
                        // Vita3K-Plus defaultea Page Table) y hay
                        // cero mediciones en Child of Light.
                        GpuVendor.ADRENO -> "Page Table (probar; si hay glitches, Double Buffer)."
                        GpuVendor.MALI -> "Recomendado en Mali: Double Buffer (valor de los presets Mali). Si el juego va lento, prueba Page Table: en Mali suele ir mejor según el núcleo, y mide con la prueba de 30 s."
                        else -> "Recomendado: Double Buffer. Los 5 métodos están verificados contra el núcleo (pin 3e43bc80); \"off\" equivale a desactivado."
                    },
                )
                // Ronda 0.2.64 (P1-3 Mali): la máscara de métodos
                // soportados la pregunta el ViewModel al núcleo
                // (getSupportedMemoryMappingMask). Los chips NO se
                // filtran (EnumChips no soporta ítems deshabilitados
                // y un método elegido que saliera de la lista
                // rompería la selección): se avisa qué métodos el
                // driver no soporta, porque si se elige uno de ellos
                // el núcleo vuelve a Double Buffer en silencio.
                val mappingMask by vm.supportedMappingMask.collectAsState(initial = null)
                val unsupported = mappingMask?.let { mask ->
                    MemoryMapping.entries.filter { !it.isSupportedByMask(mask) }
                } ?: emptyList()
                if (unsupported.isNotEmpty()) {
                    NoteText("En este teléfono el driver no soporta: ${unsupported.joinToString(", ") { it.label }}. Si eliges uno, el núcleo vuelve a Double Buffer en silencio.")
                }
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
