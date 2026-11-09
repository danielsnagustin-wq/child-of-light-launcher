package com.danielsnagustin.childoflight.launcher.model

/**
 * Modelo de ajustes del launcher.
 *
 * Regla de honestidad de este archivo: cada ajuste sabe si tiene una clave
 * real en config.yml de Vita3K (ver Vita3kConfigGenerator) o si es solo del
 * launcher. Los ajustes sin clave verificada en el config.h de Vita3K
 * (master, 2026-11-07) NO se emiten como claves reales en el YAML:
 * se escriben como comentario TODO-VERIFY. Ver el generador.
 */

// ---------------------------------------------------------------------------
// GPU / dispositivo
// ---------------------------------------------------------------------------

enum class GpuVendor { ADRENO, MALI, POWERVR, OTHER, UNKNOWN }

enum class GpuBackend(val vita3kValue: String, val label: String) {
    VULKAN("Vulkan", "Vulkan (recomendado)"),
    OPENGL("OpenGL", "OpenGL (fallback si hay glitches)"),
}

enum class ScreenFilter(val vita3kValue: String) {
    BILINEAR("Bilinear"),
    NEAREST("Nearest"),
    BICUBIC("Bicubic"),
    FXAA("FXAA"),
    FSR("FSR"),
}

enum class MemoryMapping(val vita3kValue: String, val label: String) {
    /**
     * Strings YAML verificados contra el núcleo (pin 3e43bc80,
     * renderer.cpp VKState::late_init): "double-buffer" -> DoubleBuffer,
     * "external-host" -> ExernalHost, "page-table" -> PageTable,
     * "native-buffer" -> NativeBuffer (solo Android). "off" no tiene
     * literal propio: cae en Disabled por fall-through, mismo efecto.
     */
    DOUBLE_BUFFER("double-buffer", "Double Buffer"),
    PAGE_TABLE("page-table", "Page Table"),
    NATIVE_BUFFER("native-buffer", "Native Buffer"),
    EXTERNAL_HOST("external-host", "External Host"),
    OFF("off", "Off"),
}

enum class GpuDriver { SYSTEM, TURNIP }

data class GpuInfo(
    val vendor: GpuVendor = GpuVendor.UNKNOWN,
    /** Renderer string real leído por GLES, p. ej. "Mali-G57 MC2". */
    val renderer: String = "No detectado",
    val vendorString: String = "",
    val glesVersion: String = "",
    /** SoC: Build.SOC_MODEL, o Build.HARDWARE si no está disponible. */
    val socModel: String = "",
    /** Etiqueta aproximada por system feature, p. ej. "Vulkan 1.1+". */
    val vulkanVersionLabel: String = "Desconocido",
    val vulkanSupported: Boolean = false,
    val detectedAtMillis: Long = 0L,
) {
    /** Helio G99 = MediaTek MT6789. Se detecta solo por procesador (SoC o hardware), nunca por modelo de dispositivo. */
    val isHelioG99: Boolean
        get() {
            val soc = socModel.uppercase()
            return soc.contains("G99") || soc.contains("MT6789")
        }
    val isMaliG57: Boolean
        get() = renderer.uppercase().contains("MALI-G57") ||
            renderer.uppercase().contains("G57")
    /** El usuario confirmó el 2026-11-07: Child of Light va MUY BIEN en este teléfono. */
    val isUserVerifiedDevice: Boolean get() = isHelioG99 && isMaliG57
    val shortLabel: String
        get() = when {
            isUserVerifiedDevice -> "Helio G99 · Mali-G57 MC2 (verificado)"
            vendor == GpuVendor.MALI -> "Mali ($renderer)"
            vendor == GpuVendor.ADRENO -> "Adreno ($renderer)"
            vendor == GpuVendor.POWERVR -> "PowerVR ($renderer)"
            else -> renderer
        }
}

// ---------------------------------------------------------------------------
// Perfiles por GPU
// ---------------------------------------------------------------------------

enum class GpuProfileId(val label: String, val description: String) {
    /** FOCO PRINCIPAL. Dato real del usuario: el juego va muy bien en el G99. */
    G99_RECOMMENDED(
        "G99 / Mali-G57 — Recomendado",
        "Preset por defecto en Helio G99. 1x estable, sin recortes de más.",
    ),
    G99_QUALITY(
        "G99 — Calidad (probar 2x)",
        "Igual que Recomendado pero a 2x con FSR. Probar 30 s: si baja de 28 fps, vuelve a 1x.",
    ),
    MALI_PERFORMANCE(
        "Mali — Rendimiento",
        "Mali genérico (no G99): 1x siempre, Double Buffer forzado.",
    ),
    MALI_BALANCED(
        "Mali — Equilibrado",
        "Mali genérico: 1x con FSR/Bilinear si el FPS es estable.",
    ),
    ADRENO_PERFORMANCE(
        "Snapdragon / Adreno — Rendimiento",
        "Vulkan, 1x, Page Table. Segundo perfil, no el foco principal.",
    ),
    ADRENO_QUALITY_TURNIP(
        "Snapdragon / Adreno — Calidad / Turnip",
        "Vulkan + Turnip, 2x. Solo Adreno: Turnip no funciona en Mali.",
    ),
}


/**
 * Backend de audio de Vita3K.
 * SDL es el default verificado (config.h: audio-backend "SDL").
 * Cubeb es la alternativa con string exacto VERIFICADO en upstream
 * (pin 3e43bc80, audio.cpp AudioState::set_backend: acepta
 * exactamente "SDL" y "Cubeb").
 */
enum class AudioBackend(val vita3kValue: String, val label: String) {
    SDL("SDL", "SDL (recomendado)"),
    CUBEB("Cubeb", "Cubeb (alternativa)"),
}

/**
 * Detalle del HUD de rendimiento (performance-overlay-detail en
 * config.yml). VERIFICADO en upstream (pin 3e43bc80, config.h:52-62
 * enum PerformanceOverlayDetail y config.h:362): int con default
 * MINIMUM (0). Más detalle = más métricas en el overlay. El contenido
 * exacto de cada nivel no se verificó en el código del renderer: las
 * etiquetas son neutras (Mínimo/Bajo/Medio/Máximo), sin prometer qué
 * métrica trae cada nivel.
 */
enum class PerformanceOverlayDetail(val value: Int, val label: String) {
    MINIMUM(0, "Mínimo"),
    LOW(1, "Bajo"),
    MEDIUM(2, "Medio"),
    MAXIMUM(3, "Máximo"),
}

/**
 * Posición del HUD de rendimiento (performance-overlay-position en
 * config.yml). VERIFICADO (pin 3e43bc80, config.h:65-79 enum
 * PerformanceOverlayPosition y config.h:364): int con default
 * TOP_LEFT (0).
 */
enum class PerformanceOverlayPosition(val value: Int, val label: String) {
    TOP_LEFT(0, "Arriba a la izquierda"),
    TOP_CENTER(1, "Arriba al centro"),
    TOP_RIGHT(2, "Arriba a la derecha"),
    BOTTOM_LEFT(3, "Abajo a la izquierda"),
    BOTTOM_CENTER(4, "Abajo al centro"),
    BOTTOM_RIGHT(5, "Abajo a la derecha"),
}

// ---------------------------------------------------------------------------
// Ajustes
// ---------------------------------------------------------------------------

data class LauncherSettings(
    // --- GPU (claves verificadas en config.h salvo nota) ---
    val backend: GpuBackend = GpuBackend.VULKAN,
    /** Multiplicador de resolución interna. 1 = 960x544, 2 = 1920x1088, 3 = 2880x1632, 4 = 3840x2176. */
    val resolutionMultiplier: Float = 1f,
    val screenFilter: ScreenFilter = ScreenFilter.BILINEAR,
    val anisotropicFiltering: Int = 1, // 1, 2, 4, 8, 16
    val vSync: Boolean = false,
    val asyncPipelineCompilation: Boolean = true,
    val highAccuracy: Boolean = false,
    /** true = Surface Sync activado (más preciso). false = Disabled (más rápido). */
    val surfaceSyncEnabled: Boolean = false,
    val memoryMapping: MemoryMapping = MemoryMapping.DOUBLE_BUFFER,
    val textureCache: Boolean = true,
    val shaderCache: Boolean = true,
    // --- CPU ---
    /** cpu-opt en config.yml. El backend Dynarmic es el default de Vita3K; no hay clave cpu-backend en config.h. */
    val cpuOpt: Boolean = true,
    /** fps-hack en config.yml (30 -> 60). */
    val fpsHack: Boolean = false,
    val fileLoadingDelay: Int = 0,
    // --- Audio ---
    val audioVolume: Int = 100, // 0..100, clave audio-volume verificada
    val audioBackend: AudioBackend = AudioBackend.SDL,
    /** NGS (audio del sistema Vita). Clave ngs-enable verificada en config.h. */
    val ngsEnable: Boolean = true,
    // --- Overlay / rendimiento ---
    val showFpsOverlay: Boolean = false, // performance-overlay, verificado
    /**
     * Detalle del HUD (performance-overlay-detail, int, default
     * MINIMUM = 0 en upstream). Solo se nota si el overlay está ON.
     */
    val performanceOverlayDetail: PerformanceOverlayDetail = PerformanceOverlayDetail.MINIMUM,
    /** Posición del HUD (performance-overlay-position, int, default TOP_LEFT = 0 en upstream). */
    val performanceOverlayPosition: PerformanceOverlayPosition = PerformanceOverlayPosition.TOP_LEFT,
    /**
     * Caché de texturas sin hash (hashless-texture-cache, bool,
     * default false en upstream). Punto de consumo VERIFICADO
     * (pin 3e43bc80, renderer/src/texture/cache.cpp):
     * hashless=true pone use_protect=true; al cargar una textura
     * GRANDE (>= 4 páginas del host) el núcleo NO recalcula el hash
     * XXH3 de sus datos en cada frame: protege sus páginas en
     * solo-lectura y solo la vuelve a subir si el juego la modifica
     * (el page fault marca dirty). Las texturas pequeñas siguen con
     * hash siempre. Por eso es seguro probarlo (OFF por defecto): no
     * se confía a ciegas en dirección/tamaño, la detección de cambios
     * sigue existiendo, solo cambia el mecanismo. Si alguna textura
     * se ve incorrecta, se apaga y vuelve al hash de siempre. Medir
     * con la prueba de 30 s (ON/OFF).
     */
    val hashlessTextureCache: Boolean = false,
    /**
     * Mostrar mensajes de compilación de shaders
     * (show-compile-shaders, bool, default true en upstream).
     * En el launcher es false por defecto: pantalla limpia, sin
     * avisos de shaders al arrancar o cambiar de escena. El
     * interruptor en Ajustes lo vuelve a mostrar.
     */
    val showCompileShaders: Boolean = false,
    // --- Driver GPU ---
    val gpuDriver: GpuDriver = GpuDriver.SYSTEM,
    val customDriverName: String = "",
    val turnipVersion: String = "",
    val turnipInstalled: Boolean = false,
    // --- Comportamiento del launcher ---
    val activeProfile: GpuProfileId = GpuProfileId.G99_RECOMMENDED,
    /** Si una prueba de 30 s con el preset Calidad baja de 28 fps, volver a 1x. */
    val autoRevertTo1xIfBelow28Fps: Boolean = true,
    /** Modo rendimiento del launcher: mantener pantalla encendida durante el juego. */
    val performanceMode: Boolean = true,
    /**
     * Modo seguro: cuando está ON, el generador de config.yml fuerza
     * 1x, SDL y High Accuracy OFF, por encima de los ajustes
     * individuales. Para cuando algo falle.
     */
    val safeMode: Boolean = false,
    /** Cerrar apps en segundo plano antes de jugar (best-effort, ver Vita3KLauncher). */
    val closeBackgroundApps: Boolean = true,
    /**
     * Permitir la app externa como último recurso (Ajustes → General → Motor).
     * OFF por defecto: JUGAR siempre usa el motor integrado. Solo si
     * está ON y el motor integrado no está disponible, JUGAR abre el
     * juego en otra app instalada. Nunca es un respaldo automático:
     * con núcleo presente, este interruptor no cambia nada.
     */
    val allowExternalVita3k: Boolean = false,
    /**
     * Sesión PSN (simulada): si está ON, config.yml emite
     * psn-signed-in: 1 y el núcleo responde "sesión iniciada" a los
     * juegos que preguntan antes de activar sus DLC (clave
     * verificada en config.h:398; consumo en SceNpManager.cpp:131
     * y :149). NO es una conexión real a PSN: no hay cuenta, ni
     * red, ni sincronización de trofeos. Solo le indica al juego
     * que hay sesión iniciada. ON por defecto: con OFF, los DLC
     * pueden no activarse. Se lee en el init del núcleo: el cambio
     * aplica en el siguiente lanzamiento en proceso fresco.
     */
    val psnSignedIn: Boolean = true,
    /**
     * .pkg entregado a Vita3K: el launcher copió el .pkg + work.bin a
     * Descargas/ChildOfLight/ y abrió Vita3K con la guía. Se resetea
     * al importar un .pkg nuevo o borrar los datos del juego.
     */
    val pkgDeliveredToVita3k: Boolean = false,
    /**
     * El usuario confirmó ("Ya lo instalé en Vita3K") que instaló el
     * .pkg dentro de Vita3K. Es una confirmación HONESTA: el launcher
     * no puede leer la biblioteca de Vita3K para comprobarlo.
     */
    val pkgInstalledInVita3k: Boolean = false,
    /**
     * Ocultar controles táctiles si hay un mando físico conectado.
     * Si el usuario borró todos los botones (usa mando físico), el
     * overlay no estorba: con este interruptor ON, el launcher oculta
     * el overlay táctil siempre que detecte un mando conectado.
     */
    val hideTouchWhenGamepadConnected: Boolean = true,
    // --- Overlay táctil dentro del juego embebido (Etapa 2) ---
    // SOLO LAUNCHER: no hay ninguna clave de overlay en el config.h
    // del núcleo (verificado en el pin 3e43bc80: config.yml solo tiene
    // "performance-overlay", el HUD de FPS). La personalización vive
    // en el launcher y llega al núcleo como botones/ejes de un mando
    // virtual SDL (input_overlay.cpp de upstream); por eso NO se emite
    // nada de esto en config.yml. Las posiciones, tamaños, opacidad y
    // color POR BOTÓN no están aquí: están en el perfil activo de
    // TouchLayoutRepository (el editor), que el overlay del juego lee
    // tal cual.
    /** Interruptor general: el overlay aparece sobre el juego embebido. */
    val showTouchOverlayInGame: Boolean = true,
    /** Opacidad general del overlay (0.1..1): multiplica la opacidad de cada botón. */
    val touchOverlayOpacity: Float = 1f,
    /** Halo azul hielo (neón) alrededor de los botones del overlay. */
    val touchOverlayNeonGlow: Boolean = false,
    /** Ids de perfiles de control en los que el overlay queda oculto en el juego. */
    val touchOverlayHiddenProfileIds: Set<String> = emptySet(),
) {
    val resolutionLabel: String
        get() = when (resolutionMultiplier) {
            1f -> "1x (960x544)"
            2f -> "2x (1920x1088)"
            3f -> "3x (2880x1632)"
            4f -> "4x (3840x2176)"
            else -> "${resolutionMultiplier}x"
        }
}

/** Perfil por defecto según la GPU detectada. El G99 manda: es el teléfono del usuario. */
fun defaultProfileFor(gpu: GpuInfo): GpuProfileId = when {
    gpu.isUserVerifiedDevice -> GpuProfileId.G99_RECOMMENDED
    gpu.isHelioG99 || gpu.isMaliG57 -> GpuProfileId.G99_RECOMMENDED
    gpu.vendor == GpuVendor.MALI -> GpuProfileId.MALI_PERFORMANCE
    gpu.vendor == GpuVendor.ADRENO -> GpuProfileId.ADRENO_PERFORMANCE
    // PowerVR, OTHER y UNKNOWN: perfil Mali seguro (1x, Double
    // Buffer, sin caché de texturas). NINGUNA GPU se queda sin perfil:
    // el else es el suelo.
    else -> GpuProfileId.MALI_PERFORMANCE
}

/**
 * Backend efectivo para el núcleo embebido. Si el ajuste pide Vulkan
 * pero el teléfono NO tiene Vulkan (GpuInfo.vulkanSupported == false),
 * se usa OpenGL: sin esto el núcleo arrancaría con un backend que el
 * teléfono no tiene y fallaría. Con gpu == null (aún sin detectar) no
 * se cambia nada: la detección ya ocurrió al primer arranque. Puro y
 * testeable (GpuProfileTest).
 */
fun resolveBackend(settings: LauncherSettings, gpu: GpuInfo?): GpuBackend =
    if (settings.backend == GpuBackend.VULKAN && gpu != null && !gpu.vulkanSupported) {
        GpuBackend.OPENGL
    } else {
        settings.backend
    }

/**
 * Todos los presets. Valores pensados para Child of Light (2D, UbiArt):
 * es ligero comparado con un juego 3D, así que en el G99 no se recorta
 * de más: 1x es la base porque el usuario ya confirmó que va muy
 * bien, no un "ojalá funcione".
 */
fun presetSettings(profile: GpuProfileId): LauncherSettings = when (profile) {
    GpuProfileId.G99_RECOMMENDED -> LauncherSettings(
        activeProfile = profile,
        backend = GpuBackend.VULKAN,
        resolutionMultiplier = 1f,
        screenFilter = ScreenFilter.BILINEAR,
        anisotropicFiltering = 1,
        vSync = false,
        asyncPipelineCompilation = true,
        highAccuracy = false,
        surfaceSyncEnabled = false,
        memoryMapping = MemoryMapping.DOUBLE_BUFFER,
        cpuOpt = true, fpsHack = false, fileLoadingDelay = 0,
        showFpsOverlay = false,
        gpuDriver = GpuDriver.SYSTEM, shaderCache = true, textureCache = true, performanceMode = true,
    )
    GpuProfileId.G99_QUALITY -> LauncherSettings(
        activeProfile = profile,
        backend = GpuBackend.VULKAN,
        resolutionMultiplier = 2f,
        screenFilter = ScreenFilter.FSR,
        anisotropicFiltering = 1,
        vSync = false,
        asyncPipelineCompilation = true,
        highAccuracy = false,
        surfaceSyncEnabled = false,
        memoryMapping = MemoryMapping.DOUBLE_BUFFER,
        cpuOpt = true, fpsHack = false, fileLoadingDelay = 0,
        showFpsOverlay = true,
        gpuDriver = GpuDriver.SYSTEM, shaderCache = true, textureCache = true, performanceMode = true,
    )
    GpuProfileId.MALI_PERFORMANCE -> LauncherSettings(
        activeProfile = profile,
        backend = GpuBackend.VULKAN,
        resolutionMultiplier = 1f, // 1x SIEMPRE en Mali genérico
        screenFilter = ScreenFilter.BILINEAR,
        anisotropicFiltering = 1,
        vSync = false,
        asyncPipelineCompilation = true,
        highAccuracy = false,
        surfaceSyncEnabled = false,
        memoryMapping = MemoryMapping.DOUBLE_BUFFER, // forzado: Mali hace fallback a Double Buffer
        cpuOpt = true, fpsHack = false,
        textureCache = false, shaderCache = true,
    )
    GpuProfileId.MALI_BALANCED -> LauncherSettings(
        activeProfile = profile,
        backend = GpuBackend.VULKAN,
        resolutionMultiplier = 1f, // nunca 2x/3x/4x como recomendado en Mali
        screenFilter = ScreenFilter.FSR,
        anisotropicFiltering = 1,
        vSync = false,
        asyncPipelineCompilation = true,
        highAccuracy = false,
        surfaceSyncEnabled = false,
        memoryMapping = MemoryMapping.DOUBLE_BUFFER,
        cpuOpt = true, fpsHack = false,
        textureCache = true, shaderCache = true,
    )
    GpuProfileId.ADRENO_PERFORMANCE -> LauncherSettings(
        activeProfile = profile,
        backend = GpuBackend.VULKAN,
        resolutionMultiplier = 1f,
        screenFilter = ScreenFilter.BILINEAR,
        anisotropicFiltering = 1,
        vSync = false,
        asyncPipelineCompilation = true,
        highAccuracy = false,
        surfaceSyncEnabled = false,
        memoryMapping = MemoryMapping.PAGE_TABLE, // recomendado en Android para Adreno
        cpuOpt = true, fpsHack = false,
        gpuDriver = GpuDriver.SYSTEM,
        textureCache = true,
    )
    GpuProfileId.ADRENO_QUALITY_TURNIP -> LauncherSettings(
        activeProfile = profile,
        backend = GpuBackend.VULKAN,
        resolutionMultiplier = 2f,
        screenFilter = ScreenFilter.FXAA,
        anisotropicFiltering = 4,
        vSync = true,
        asyncPipelineCompilation = true,
        highAccuracy = false,
        surfaceSyncEnabled = false,
        memoryMapping = MemoryMapping.PAGE_TABLE,
        cpuOpt = true, fpsHack = false,
        gpuDriver = GpuDriver.TURNIP,
        textureCache = true,
    )
}

/**
 * R4-P0-9: campos que definen el perfil activo (los que applyPreset
 * toma del preset; los conservados por el usuario — audio, modo seguro,
 * cerrar apps, PSN, entrega del .pkg, overlay táctil — y los que el
 * ViewModel restaura encima — performanceMode, autoRevert — NO entran:
 * que difieran no es divergir del preset). Puro y testeable en JVM.
 */
private fun presetProfileFields(s: LauncherSettings): List<Any> = listOf(
    s.backend,
    s.resolutionMultiplier,
    s.screenFilter,
    s.anisotropicFiltering,
    s.vSync,
    s.asyncPipelineCompilation,
    s.highAccuracy,
    s.surfaceSyncEnabled,
    s.memoryMapping,
    s.textureCache,
    s.shaderCache,
    s.cpuOpt,
    s.fpsHack,
    s.fileLoadingDelay,
    s.showFpsOverlay,
    s.performanceOverlayDetail,
    s.performanceOverlayPosition,
    s.hashlessTextureCache,
    s.showCompileShaders,
    s.gpuDriver,
)

/**
 * R4-P0-9: true si los ajustes guardados difieren del preset de su
 * perfil activo en algún campo del perfil. Es lo que la UI muestra
 * como "perfil modificado": el usuario ve "G99 Recomendado" pero corre
 * otra cosa. Un preset recién aplicado (o un reseteo) da false.
 * Puro y testeable en JVM (LowRamProfileTest).
 */
fun divergesFromPreset(settings: LauncherSettings): Boolean {
    val preset = presetSettings(settings.activeProfile)
    return presetProfileFields(settings) != presetProfileFields(preset)
}

/** Campos que toca applyLowRamDefaults (la variante conservadora). */
private fun lowRamVariantFields(s: LauncherSettings): List<Any> = listOf(
    s.resolutionMultiplier,
    s.screenFilter,
    s.anisotropicFiltering,
    s.vSync,
    s.highAccuracy,
    s.surfaceSyncEnabled,
    s.memoryMapping,
    s.textureCache,
    s.shaderCache,
)

/**
 * R4-P0-9: true si los ajustes son EXACTAMENTE el preset activo con
 * la variante conservadora de memoria baja encima (y esa variante
 * cambia algo respecto al preset: si el preset ya es conservador de
 * por sí, como MALI_PERFORMANCE, no hay divergencia que registrar).
 * Es la divergencia "conservadora" del primer arranque en un teléfono
 * de 4 GB o menos: en G99_RECOMMENDED solo cambia textureCache
 * (true del preset canónico a false). Puro y testeable en JVM.
 */
fun isLowRamConservativeVariant(settings: LauncherSettings): Boolean {
    val preset = presetSettings(settings.activeProfile)
    return lowRamVariantFields(settings) == lowRamVariantFields(applyLowRamDefaults(preset)) &&
        lowRamVariantFields(settings) != lowRamVariantFields(preset)
}

/** Regla de la prueba de 30 s: menos de 28 fps con Calidad 2x -> volver a 1x. */
fun shouldAutoRevertTo1x(fpsAvg: Float?, profile: GpuProfileId, autoRevertEnabled: Boolean): Boolean {
    if (!autoRevertEnabled) return false
    if (fpsAvg == null) return false
    if (profile != GpuProfileId.G99_QUALITY) return false
    return fpsAvg < 28f
}

/**
 * Modo seguro: valores forzados cuando algo falla.
 * 1x, SDL, High Accuracy OFF, Async ON, shader cache ON,
 * Surface Sync Disabled, fps-hack OFF (P2-14: con 60 FPS ON el
 * config.yml emitía fps-hack: true + v-sync: false y el "modo
 * seguro" no era seguro).
 */
fun applySafeMode(settings: LauncherSettings): LauncherSettings = settings.copy(
    safeMode = true,
    resolutionMultiplier = 1f,
    screenFilter = ScreenFilter.BILINEAR,
    anisotropicFiltering = 1,
    vSync = false,
    asyncPipelineCompilation = true,
    highAccuracy = false,
    surfaceSyncEnabled = false,
    audioBackend = AudioBackend.SDL,
    ngsEnable = true,
    shaderCache = true,
    fpsHack = false,
)

/**
 * Ajustes efectivos para generar config.yml: el modo seguro manda sobre
 * todo; si no, el modo 60 FPS (fpsHack) aplica su paquete completo.
 * El perfil guardado del usuario no se toca: solo los valores efectivos.
 */
fun effectiveSettings(settings: LauncherSettings): LauncherSettings =
    when {
        settings.safeMode -> applySafeMode(settings)
        settings.fpsHack -> apply60FpsMode(settings)
        else -> settings
    }

/**
 * Modo 60 FPS (opcional, OFF por defecto): paquete completo para intentar
 * 30 → 60 fps con fps-hack. Verificado contra upstream (pin 3e43bc80):
 * fps-hack hace que el juego espere 1 vblank en vez de 2 (SceDisplay).
 * Puede no hacer nada o correr al doble de velocidad: solo el teléfono
 * lo decide; se revierte con un toque (fpsHack = false). Se aplica al
 * reiniciar el juego (el hack se fija al crear el renderer).
 */
fun apply60FpsMode(settings: LauncherSettings): LauncherSettings = settings.copy(
    resolutionMultiplier = 1f,
    screenFilter = ScreenFilter.BILINEAR,
    anisotropicFiltering = 1,
    vSync = true,
    asyncPipelineCompilation = true,
    highAccuracy = false,
    surfaceSyncEnabled = false,
    memoryMapping = MemoryMapping.DOUBLE_BUFFER,
    textureCache = true,
    shaderCache = true,
    cpuOpt = true,
    ngsEnable = true,
    fpsHack = true,
)

/**
 * Perfil conservador para teléfonos con ≤ 4 GB de RAM (ver DeviceMemory).
 * R4-P0-9, REGLA COHERENTE: se aplica SOLO en el primer arranque
 * (detectGpu). Es una variante VISIBLE, no silenciosa: los ajustes
 * guardados quedan como divergencia conservadora del preset (ver
 * isLowRamConservativeVariant y divergesFromPreset) y el mensaje de
 * detección dice que la caché de texturas quedó OFF. "Restablecer
 * ajustes", aplicar un preset a mano, "Restablecer esta categoría" y
 * el auto-revert de la prueba de 30 s usan el preset CANÓNICO
 * (textureCache explícito en presetSettings), nunca lowRam: el mismo
 * preset da siempre el mismo valor de texture-cache. Nunca sobrescribe
 * un perfil que el usuario eligió a mano
 * (p. ej. G99 Calidad 2x sigue disponible si el usuario lo elige, y
 * el auto-revert a 1x por debajo de 28 fps sigue activo).
 *
 * Forzado: 1x (960x544; 2x cuadruplica el framebuffer y las texturas),
 * filtro Bilinear (sin FXAA ni FSR: ambos cuestan GPU/memoria extra),
 * anisotrópico 1, Double Buffer, VSync off, High Accuracy off,
 * Surface Sync off.
 *
 * Caché: config.yml NO tiene claves de TAMAÑO de caché (verificado
 * en Vita3kConfigGenerator contra config.h: solo existen los
 * booleanos texture-cache y shader-cache). La única palanca es el
 * booleano: texture-cache OFF (las texturas decodificadas en RAM/VRAM
 * son el mayor gasto evitable del launcher; se pagan con recargas al
 * cambiar de escena) y shader-cache ON (es caché en DISCO: evita
 * recompilar shaders en cada arranque y su huella en RAM es
 * transitoria). Sin verificar en dispositivo: el coste real de
 * texture-cache OFF en este juego no está medido; si se nota
 * tirón al cambiar de escena, el usuario puede reactivarlo en Ajustes.
 */
fun applyLowRamDefaults(settings: LauncherSettings): LauncherSettings = settings.copy(
    resolutionMultiplier = 1f,
    screenFilter = ScreenFilter.BILINEAR,
    anisotropicFiltering = 1,
    vSync = false,
    highAccuracy = false,
    surfaceSyncEnabled = false,
    memoryMapping = MemoryMapping.DOUBLE_BUFFER,
    textureCache = false,
    shaderCache = true,
)

// ---------------------------------------------------------------------------
// Prueba de rendimiento (30 s)
// ---------------------------------------------------------------------------

enum class BenchmarkSource { MANUAL_OVERLAY, AUTO_MEASURED, NOT_MEASURED }

/**
 * Media de las muestras de FPS de una prueba de 30 s medida por el
 * núcleo (EmbeddedGameActivity muestrea Vita3kCoreBridge.getFps()
 * cada segundo). Solo cuentan las muestras válidas (> 0: -1 es "sin
 * juego" y 0 es "sin medir"). Media RECORTADA (R2-P2-29): se quitan
 * el mínimo y el máximo para que un tirón a 1 fps o un pico a 240 no
 * decidan la prueba; con menos de 3 muestras válidas no hay nada
 * que recortar y es la media simple. null = sin muestras válidas.
 * Pura y testeable en JVM.
 */
fun averageBenchmarkFps(samples: List<Float>): Float? {
    val valid = samples.filter { it > 0f }
    if (valid.isEmpty()) return null
    val trimmed = if (valid.size >= 3) {
        val sorted = valid.sorted()
        sorted.subList(1, sorted.size - 1)
    } else {
        valid
    }
    return trimmed.sum() / trimmed.size
}

data class BenchmarkRun(
    val profile: GpuProfileId,
    val gpuLabel: String,
    val startedAtMillis: Long,
    val durationSeconds: Int = 30,
    /** FPS medio leído por el usuario en el overlay. null = no medido. */
    val fpsAvg: Float? = null,
    val source: BenchmarkSource = BenchmarkSource.NOT_MEASURED,
    val autoReverted: Boolean = false,
    val note: String = "",
)
