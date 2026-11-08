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
        "Mali genérico: 1x con FSR/Bilinear y Frame Skip 0 si el FPS es estable.",
    ),
    ADRENO_PERFORMANCE(
        "Snapdragon / Adreno — Rendimiento",
        "Vulkan, 1x, Page Table. Segundo perfil, no el foco principal.",
    ),
    ADRENO_QUALITY_TURNIP(
        "Snapdragon / Adreno — Calidad / Turnip",
        "Vulkan + Turnip, 2x. Solo Adreno: Turnip no funciona en Mali.",
    ),
    GENERIC_PERFORMANCE(
        "Genérico — Rendimiento",
        "GPU no detectada: 1x, valores seguros.",
    ),
}

enum class FrameLimiter(val label: String, val fps: Int?) {
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60),
    UNLIMITED("Sin límite", null),
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
    /**
     * Tamaño de buffer de audio en muestras (256/512/1024/2048).
     * SOLO LAUNCHER: no hay clave de buffer en el config.h de Vita3K
     * (master 2026-11-07). Se guarda, se muestra en diagnóstico y se
     * aplica en "Corregir audio", pero NO se emite como clave inventada.
     */
    val audioBufferSize: Int = 512,
    // --- Solo launcher (SIN clave verificada en config.yml: van como TODO en el YAML) ---
    val frameLimiter: FrameLimiter = FrameLimiter.FPS_30,
    val frameSkipping: Int = 0, // 0, 1, 2
    val accurateThreadScheduling: Boolean = true,
    // --- Overlay / rendimiento ---
    val showFpsOverlay: Boolean = false, // performance-overlay, verificado
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
    val batteryOptimizationDisabled: Boolean = false,
    /**
     * Modo seguro: cuando está ON, el generador de config.yml fuerza
     * 1x, SDL, buffer grande, Thread Scheduling ON y High Accuracy OFF,
     * por encima de los ajustes individuales. Para cuando algo falle.
     */
    val safeMode: Boolean = false,
    /** Cerrar apps en segundo plano antes de jugar (best-effort, ver Vita3KLauncher). */
    val closeBackgroundApps: Boolean = true,
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
    /** Halo dorado (neón) alrededor de los botones del overlay. */
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
    // PowerVR, OTHER y UNKNOWN: perfil Genérico seguro (1x, Double
    // Buffer). NINGUNA GPU se queda sin perfil: el else es el suelo.
    else -> GpuProfileId.GENERIC_PERFORMANCE
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
 * de más: 1x + Frame Skip 0 es la base porque el usuario ya confirmó
 * que va muy bien, no un "ojalá funcione".
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
        frameLimiter = FrameLimiter.FPS_30, frameSkipping = 0,
        accurateThreadScheduling = true, showFpsOverlay = false,
        gpuDriver = GpuDriver.SYSTEM, shaderCache = true, performanceMode = true,
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
        frameLimiter = FrameLimiter.FPS_30, frameSkipping = 0,
        accurateThreadScheduling = true, showFpsOverlay = true,
        gpuDriver = GpuDriver.SYSTEM, shaderCache = true, performanceMode = true,
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
        frameLimiter = FrameLimiter.FPS_30, frameSkipping = 1,
        accurateThreadScheduling = true, shaderCache = true,
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
        frameLimiter = FrameLimiter.FPS_30, frameSkipping = 0,
        accurateThreadScheduling = true, shaderCache = true,
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
        frameLimiter = FrameLimiter.FPS_30, frameSkipping = 0,
        accurateThreadScheduling = true, gpuDriver = GpuDriver.SYSTEM,
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
        frameLimiter = FrameLimiter.FPS_60, frameSkipping = 0,
        accurateThreadScheduling = true, gpuDriver = GpuDriver.TURNIP,
    )
    GpuProfileId.GENERIC_PERFORMANCE -> LauncherSettings(
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
        cpuOpt = true, fpsHack = false,
        frameLimiter = FrameLimiter.FPS_30, frameSkipping = 0,
        accurateThreadScheduling = true,
    )
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
 * 1x, SDL, buffer grande, Thread Scheduling ON, High Accuracy OFF,
 * Async ON, shader cache ON, Surface Sync Disabled.
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
    audioBufferSize = 2048,
    ngsEnable = true,
    accurateThreadScheduling = true,
    shaderCache = true,
    frameSkipping = 0,
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
 * No toca frameLimiter ni frameSkipping: son ajustes solo del launcher,
 * sin clave en el núcleo, así que no mueven el FPS.
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
 * Se aplica SOLO por defecto en el primer arranque y al restablecer
 * ajustes; nunca sobrescribe un perfil que el usuario eligió a mano
 * (p. ej. G99 Calidad 2x sigue disponible si el usuario lo elige, y
 * el auto-revert a 1x por debajo de 28 fps sigue activo).
 *
 * Forzado: 1x (960x544; 2x cuadruplica el framebuffer y las texturas),
 * filtro Bilinear (sin FXAA ni FSR: ambos cuestan GPU/memoria extra),
 * anisotrópico 1, Double Buffer, VSync off, High Accuracy off,
 * Surface Sync off, límite 30 fps.
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
    frameLimiter = FrameLimiter.FPS_30,
)

// ---------------------------------------------------------------------------
// Prueba de rendimiento (30 s)
// ---------------------------------------------------------------------------

enum class BenchmarkSource { MANUAL_OVERLAY, NOT_MEASURED }

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
