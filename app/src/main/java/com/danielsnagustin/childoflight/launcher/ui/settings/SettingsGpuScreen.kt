package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * NO se ofrece: spirv-shader (sin verificar) ni cpu-pool-size:
 * existe en config.h del núcleo (config.h:175) pero NADIE la lee
 * (ronda 0.2.66, CPU-05): es una clave muerta, no se emite.
 * turbo-mode SÍ se ofrece, abajo en Driver GPU (solo Adreno con
 * Turnip).
 */
@Composable
fun SettingsGpuScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val pickTurnip = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.installTurnip(uri)
    }
    val s = settings
    // Ronda 0.2.65 (C-P2-2): al entrar, preguntar al núcleo la máscara
    // de mapeo soportada: antes solo se preguntaba en Diagnóstico o al
    // cambiar de driver, y esta pantalla mostraba el aviso de métodos
    // no soportados con una máscara obsoleta (o null = sin aviso).
    // Mismo patrón que GpuDiagnosticsScreen (solo si hay núcleo).
    LaunchedEffect(Unit) {
        if (vm.embeddedCorePresent()) vm.refreshMappingMask()
    }
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
                // Ronda 0.2.65 (RG-P2-4): FSR, FXAA y Bicúbico son pases
                // de pantalla completa que cuestan FPS en Mali sin subir
                // la resolución interna: se avisa, como ya se avisa de 2x.
                if (gpu?.vendor == GpuVendor.MALI && s.screenFilter in listOf(ScreenFilter.FSR, ScreenFilter.FXAA, ScreenFilter.BICUBIC)) {
                    NoteText("⚠ En Mali, ${s.screenFilter.vita3kValue} es un pase extra de pantalla completa: cuesta FPS sin subir la resolución interna. Si va lento, vuelve a Bilinear.")
                }
                // Ronda 0.2.64 (P2-2 Mali): los valores por encima del
                // máximo del dispositivo (GpuInfo.maxAnisotropy, leído
                // por GLES) no se ofrecen: el núcleo no limita el valor
                // y por encima del límite es uso inválido de Vulkan.
                // Sin máximo conocido se ofrecen todos (1x–16x).
                val anisoOptions = gpu?.maxAnisotropy?.let { max ->
                    listOf(1, 2, 4, 8, 16).filter { it <= max }
                } ?: listOf(1, 2, 4, 8, 16)
                // Ronda 0.2.65 (G-P2-10): si el valor guardado supera el
                // máximo del teléfono (de antes de la detección, o de
                // una copia importada), ningún chip quedaba marcado: se
                // muestra recortado al máximo (el generador ya emite el
                // valor recortado; el guardado no se toca aquí).
                val displayedAniso = gpu?.maxAnisotropy?.let { minOf(s.anisotropicFiltering, it) } ?: s.anisotropicFiltering
                EnumChips("Anisotrópico", anisoOptions, displayedAniso, { "${it}x" }) { vm.saveSettings(s.copy(anisotropicFiltering = it)) }
                if (displayedAniso != s.anisotropicFiltering) {
                    NoteText("El valor guardado (${s.anisotropicFiltering}x) supera el máximo de este teléfono (${gpu?.maxAnisotropy}x): se usa ${displayedAniso}x.")
                }
                // Ronda 0.2.70 (11-P2-3): con máximo desconocido la UI
                // ofrecía 16x sin avisar de que el generador lo recorta
                // al tope seguro 8x: se muestra la misma nota que en el
                // config.yml cuando el valor guardado supera 8x.
                if (gpu?.maxAnisotropy == null && s.anisotropicFiltering > 8) {
                    NoteText("El máximo de anisotrópico de este teléfono es desconocido: por seguridad se usa 8x aunque el ajuste guardado sea ${s.anisotropicFiltering}x (tope seguro, ver config.yml).")
                }
                // Ronda 0.2.65 (RG-P2-4): el anisotrópico alto multiplica
                // las lecturas de textura y cuesta FPS en Mali.
                if (gpu?.vendor == GpuVendor.MALI && s.anisotropicFiltering >= 8) {
                    NoteText("⚠ En Mali, el anisotrópico ${s.anisotropicFiltering}x multiplica las lecturas de textura y cuesta FPS. Si va lento, baja a 1x o 2x.")
                }
                if (gpu?.maxAnisotropy != null) {
                    NoteText("Anisotrópico máximo de este teléfono: ${gpu?.maxAnisotropy}x (límite del driver).")
                }
                // Ronda 0.2.66 (G11-06, parche g11-06-vsync-present-
                // mode): con Vulkan la VSync YA NO es inerte — elige
                // el modo de presentación del swapchain en
                // VKState::create (screen_renderer.cpp): ON = Fifo
                // (sincronizado con el vsync del panel, sin trabajo
                // GPU de más en un juego a 30 fps), OFF = Mailbox
                // (máxima velocidad, como antes). Solo se aplica al
                // lanzar (el swapchain se crea ahí); cambiarla en
                // caliente no hace nada hasta el próximo JUGAR.
                // Ningún preset la pone ON desde 0.2.64 (el modo 60
                // FPS tampoco: su único mecanismo real es fps-hack).
                SettingSwitch("V-Sync", "Sincronización vertical. En Vulkan elige el modo de presentación: ON = Fifo (sincronizado con el panel), OFF = Mailbox (máxima velocidad). En OpenGL, la de siempre.", s.vSync) { vm.saveSettings(s.copy(vSync = it)) }
                if (s.backend == GpuBackend.VULKAN) {
                    NoteText("Con Vulkan, la VSync elige el present mode (ON = Fifo, OFF = Mailbox). Se aplica al lanzar el juego, no en caliente.")
                }
                // R2-P2-08: sin "ON siempre": el interruptor lo puede
                // apagar el usuario (los presets lo ponen ON por
                // defecto, pero nada lo fuerza).
                SettingSwitch("Async Pipeline Compilation", "Compila shaders en segundo plano, menos tirones.", s.asyncPipelineCompilation) { vm.saveSettings(s.copy(asyncPipelineCompilation = it)) }
                // Ronda 0.2.65 (RC-P2-2): con la compilación asíncrona
                // OFF, cada pipeline nuevo se compila en el hilo de
                // render: tirones en cada escena nueva.
                if (!s.asyncPipelineCompilation) {
                    NoteText("⚠ Con la compilación asíncrona OFF, cada shader nuevo se compila en el hilo de render: tirones al cambiar de escena o al ver efectos nuevos. Déjala ON salvo para diagnosticar.")
                }
                SettingSwitch("High Accuracy Rendering", "Más preciso, mucho más lento. OFF en todos los presets.", s.highAccuracy) { vm.saveSettings(s.copy(highAccuracy = it)) }
                SettingSwitch("Surface Sync activado", "ON = más preciso. OFF = más rápido (recomendado en G99/Mali).", s.surfaceSyncEnabled) { vm.saveSettings(s.copy(surfaceSyncEnabled = it)) }
                // Ronda 0.2.65 (RC-P2-1): la nota vieja decía que con
                // Double Buffer (Mali) la Surface Sync "no funciona":
                // falso según el pin — perform_surface_sync() solo exige
                // mapeo activo (surface_cache.cpp:1295-1300) y Double
                // Buffer SÍ activa el mapeo (renderer.cpp:750-752,
                // :1019-1023): con Double Buffer actúa por envío y
                // cuesta. Solo es inerte con el mapeo OFF.
                if (s.memoryMapping == MemoryMapping.OFF) {
                    NoteText("Con el mapeo de memoria OFF, la Surface Sync no hace nada: solo actúa cuando hay un método de mapeo activo, incluido Double Buffer. Con el mapeo activo, activarla ejecuta una sincronización por envío y puede costar fluidez.")
                }
                EnumChips("Memory Mapping", MemoryMapping.entries, s.memoryMapping, { it.label }) { vm.saveSettings(s.copy(memoryMapping = it)) }
                // Ronda 0.2.65 (RC-P2-3): OFF es el camino más lento
                // (sin mapeo, todo pasa por copias por CPU).
                if (s.memoryMapping == MemoryMapping.OFF) {
                    NoteText("⚠ Mapeo OFF = el camino más lento: sin mapeo, todo pasa por copias por CPU. Solo para diagnosticar glitches.")
                }
                NoteText(
                    when (gpu?.vendor) {
                        // Ronda 0.2.64 (P1-1 Adreno): quitado el
                        // "recomendado en Adreno": las fuentes se
                        // contradicen (Vita3K-Android v5: Native
                        // Buffer en Adreno / Page Table en Mali;
                        // Vita3K-Plus defaultea Page Table) y hay
                        // cero mediciones en Child of Light.
                        // Ronda 0.2.65 (RG-P2-10): el método por defecto
                        // aún no está medido en un Adreno real.
                        GpuVendor.ADRENO -> "Page Table (probar; si hay glitches, Double Buffer). El método por defecto aún no está medido en un Adreno real: es una recomendación sin medir, no un resultado."
                        // Ronda 0.2.65 (G-P2-5): "en Mali suele ir mejor
                        // según el núcleo" era falso: el núcleo no
                        // jerarquiza métodos; la recomendación es del
                        // mantenedor.
                        GpuVendor.MALI -> "Recomendado en Mali: Page Table (valor de los presets Mali desde la 0.2.66). Hipótesis aún sin medir en este teléfono: Double Buffer sería el método más caro en Mali. Si va lento o hay glitches, prueba Double Buffer y mide con la prueba de 30 s."
                        else -> "Recomendado: Page Table (valor de los presets desde la 0.2.66). Los 5 métodos están verificados contra el núcleo (pin 3e43bc80); \"off\" equivale a desactivado."
                    },
                )
                // Ronda 0.2.64 (P1-3 Mali): la máscara de métodos
                // soportados la pregunta el ViewModel al núcleo
                // (getSupportedMemoryMappingMask). Los chips NO se
                // filtran (EnumChips no soporta ítems deshabilitados
                // y un método elegido que saliera de la lista
                // rompería la selección): se avisa qué métodos el
                // driver no soporta. Ronda 0.2.66 (G11-02): si se
                // elige uno de ellos, el launcher ya no deja que el
                // núcleo caiga a Double Buffer en silencio: al lanzar
                // se emite el método efectivo soportado en config.yml
                // y queda en el log.
                val mappingMask by vm.supportedMappingMask.collectAsState(initial = null)
                val unsupported = mappingMask?.let { mask ->
                    MemoryMapping.entries.filter { !it.isSupportedByMask(mask) }
                } ?: emptyList()
                if (unsupported.isNotEmpty()) {
                    NoteText("En este teléfono el driver no soporta: ${unsupported.joinToString(", ") { it.label }}. Si eliges uno, el launcher emite el método efectivo soportado (Double Buffer) en config.yml y lo deja en el log: el núcleo ya no cae en silencio.")
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
                    // Ronda 0.2.65 (C-P2-5): Turnip pedido pero no
                    // cargado por el núcleo: se usa el sistema.
                    val turnipNotLoaded by vm.turnipRequestedButNotLoaded.collectAsState()
                    if (turnipNotLoaded) {
                        NoteText("⚠ Turnip pedido pero no cargado: el núcleo usa el driver del sistema. Reinstala Turnip o vuelve al driver del sistema.")
                    }
                    if (!s.turnipInstalled) {
                        NoteText("Turnip: no instalado. Instala un archivo .zip de Turnip abajo para poder elegirlo como driver.")
                    }
                    OutlinedButton(onClick = { pickTurnip.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Instalar / cambiar Turnip (.zip)") }
                    // Ronda 0.2.65 (A-P2-2): quitada la afirmación no
                    // verificada de que el núcleo limpia la caché de
                    // shaders al cambiar de driver.
                    NoteText("Turnip es el driver Mesa open-source para Adreno. Se carga solo para esta app. El cambio se aplica en el siguiente lanzamiento.")
                    // Ronda 0.2.65 (RG-P2-15): turbo-mode existe en el
                    // núcleo (config.h:149) pero nunca se ofrecía: pide
                    // al driver el modo de máximo rendimiento. Solo
                    // Adreno CON Turnip (adrenotools); en Mali y con el
                    // driver del sistema no hace nada.
                    SettingSwitch("Modo turbo (Adreno)", "Pide al driver el modo de máximo rendimiento. Solo funciona en Adreno con driver personalizado (Turnip): en Mali y con el driver del sistema no hace nada.", s.turboMode) { vm.saveSettings(s.copy(turboMode = it)) }
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
                        // Ronda 0.2.66 (G11-04): OpenGL SOLO como
                        // diagnóstico de glitches, avisando que será
                        // más lento (en OpenGL la compilación de
                        // shaders es síncrona en el hilo de render y
                        // no hay compilación asíncrona de pipelines).
                        // NO se quita el fallback automático de
                        // resolveBackend (sin Vulkan usable, OpenGL
                        // sigue siendo el último recurso).
                        NoteText("En Mali, si ves glitches gráficos, puedes probar el backend OpenGL SOLO como diagnóstico (sección Renderizado, arriba): suele ir más lento que Vulkan (los shaders se compilan en el hilo de render). Si el glitch desaparece con OpenGL, es un problema del camino Vulkan; vuelve a Vulkan para jugar.")
                    }
                }
            }
        }
    }
}
