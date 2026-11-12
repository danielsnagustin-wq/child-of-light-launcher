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

enum class GpuVendor { ADRENO, MALI, XCLIPSE, POWERVR, OTHER, UNKNOWN }

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

/**
 * Máscara de métodos de mapeo de memoria soportados por el driver
 * (ronda 0.2.64, P1-3 Mali). Bits VERIFICADOS en el pin (3e43bc80,
 * renderer/include/renderer/state.h:51-57 enum MappingMethod y
 * renderer/src/vulkan/renderer.cpp:747-766,1673-1733): Disabled = 0,
 * DoubleBuffer = 1, ExernalHost = 2, PageTable = 3, NativeBuffer = 4
 * (solo Android). La máscara la devuelve el núcleo por JNI
 * (NativeLib.getSupportedMemoryMappingMask, native_config.cpp:804;
 * ver Vita3kCoreBridge.getSupportedMemoryMappingMask): con el
 * driver del sistema y sin sesión, enumera los dispositivos Vulkan
 * y devuelve la máscara del primero. Si el método pedido no está en
 * la máscara, el núcleo vuelve a Double Buffer EN SILENCIO
 * (renderer.cpp:1019-1022): por eso la UI muestra el método
 * efectivo, no solo el pedido.
 *
 * Sin núcleo (máscara 0 o null) no se sabe nada: la UI ofrece los 5
 * métodos como antes y no filtra nada.
 */
fun MemoryMapping.maskBit(): Int = when (this) {
    MemoryMapping.DOUBLE_BUFFER -> 1 shl 1
    MemoryMapping.EXTERNAL_HOST -> 1 shl 2
    MemoryMapping.PAGE_TABLE -> 1 shl 3
    MemoryMapping.NATIVE_BUFFER -> 1 shl 4
    MemoryMapping.OFF -> 1 shl 0
}

/** true si este método está en la máscara soportada (máscara 0 = desconocida: true, no se filtra). */
fun MemoryMapping.isSupportedByMask(mask: Int): Boolean =
    mask == 0 || (mask and maskBit()) != 0

/**
 * Método de mapeo EFECTIVO: el pedido si el driver lo soporta;
 * Double Buffer si no (es el valor inicial del núcleo,
 * renderer.cpp:750, al que cae en silencio). Con máscara
 * desconocida (0) se devuelve el pedido sin afirmar nada.
 * Puro y testeable en JVM.
 */
fun effectiveMappingMethod(requested: MemoryMapping, mask: Int): MemoryMapping =
    if (requested.isSupportedByMask(mask)) requested else MemoryMapping.DOUBLE_BUFFER

/** Métodos soportados según la máscara (vacío si es desconocida: la UI ofrece todos). */
fun supportedMappingMethods(mask: Int): List<MemoryMapping> =
    if (mask == 0) emptyList() else MemoryMapping.entries.filter { it.isSupportedByMask(mask) }

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
    /**
     * Anisotrópico máximo del dispositivo (ronda 0.2.64, P2-2 Mali):
     * GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT leído por GLES en la
     * detección (GpuDetector), null si no se pudo leer (sin
     * extensión o sin contexto EGL). Es el límite del muestreador
     * del mismo silicio que el límite Vulkan (maxSamplerAnisotropy);
     * el generador lo usa como tope del valor emitido y la UI lo usa
     * para no ofrecer valores por encima. Sin él no hay clamp: el
     * núcleo no expone su máximo por JNI en el pin (verificado).
     */
    val maxAnisotropy: Int? = null,
    val detectedAtMillis: Long = 0L,
) {
    /** Helio G99 = MediaTek MT6789. Se detecta solo por procesador (SoC o hardware), nunca por modelo de dispositivo. */
    val isHelioG99: Boolean
        get() {
            val soc = socModel.uppercase()
            return soc.contains("G99") || soc.contains("MT6789")
        }
    /**
     * Mali-G57. ANCLADO a "MALI-G57" (ronda 0.2.64, H-07): la rama
     * suelta contains("G57") casaba cualquier renderer con "G57"
     * dentro. Verificado contra la lista oficial de GPUs de Arm
     * (documentation-service.arm.com, 2026-11-11): "G57" solo existe
     * como Mali-G57 (Valhall; G57 MP1/MP2/MP6, en MediaTek y Unisoc);
     * ninguna otra familia (Adreno, PowerVR, Xclipse, Immortalis)
     * usa ese número. El renderer real siempre trae el prefijo
     * ("Mali-G57 MC2"); se acepta también "MALI G57" con espacio por
     * si algún driver lo reporta así. isHelioG99 se deja como está:
     * el SoC varía más entre fabricantes ("MT6789" frente a
     * "Helio G99") y un falso positivo ahí solo da el preset seguro
     * de 1x, no una vía 2x.
     */
    val isMaliG57: Boolean
        get() {
            val r = renderer.uppercase()
            return r.contains("MALI-G57") || r.contains("MALI G57")
        }
    /**
     * PowerVR de gama alta (Tensor G5/G6): renderer DXT (DXT-48-1536)
     * o CXTP. El dato ya estaba recogido (renderer); solo se usa
     * para matizar la nota de Diagnóstico por gama (ronda 0.2.64,
     * H-03). NO sube a 2x sin prueba en teléfono.
     */
    val isPowerVrDxt: Boolean
        get() {
            val r = renderer.uppercase()
            return r.contains("DXT") || r.contains("CXTP")
        }
    /** El usuario confirmó el 2026-11-07: Child of Light va MUY BIEN en este teléfono. */
    val isUserVerifiedDevice: Boolean get() = isHelioG99 && isMaliG57
    val shortLabel: String
        get() = when {
            isUserVerifiedDevice -> "Helio G99 · Mali-G57 MC2 (verificado)"
            vendor == GpuVendor.MALI -> "Mali ($renderer)"
            vendor == GpuVendor.ADRENO -> "Adreno ($renderer)"
            vendor == GpuVendor.XCLIPSE -> "Xclipse ($renderer)"
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
 * config.yml). VERIFICADO en upstream (pin 3e43bc80, config.h:29-34
 * enum PerformanceOverlayDetail y config.h:187): int con default
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
 * config.yml). VERIFICADO (pin 3e43bc80, config.h:36-43 enum
 * PerformanceOverlayPosition y config.h:188): int con default
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

/**
 * Idioma del sistema emulado (sys-lang en config.yml).
 * VERIFICADO en el pin (util/system.h enum SceSystemParamLang,
 * implícito desde 0: JAPANESE 0, ENGLISH_US 1, FRENCH 2, SPANISH 3,
 * GERMAN 4, ITALIAN 5, DUTCH 6, PORTUGUESE_PT 7, RUSSIAN 8, KOREAN 9,
 * CHINESE_T 10, CHINESE_S 11, FINNISH 12, SWEDISH 13, DANISH 14,
 * NORWEGIAN 15, POLISH 16, PORTUGUESE_BR 17, ENGLISH_GB 18,
 * TURKISH 19; clave sys-lang en config.h:172, int, default
 * ENGLISH_US). El juego EU PCSB00598 trae español: con sys-lang 3
 * el juego pide sus textos y subtítulos en español (p. ej.
 * .es.srt en los vídeos) en vez de los .en.srt que pedía en
 * inglés. Solo se ofrecen los 6 idiomas con traducción completa
 * habitual en los juegos de Vita; el resto del enum no se expone
 * en la interfaz. ESPAÑOL por defecto: es el idioma del usuario.
 */
enum class GameLanguage(val value: Int, val label: String) {
    SPANISH(3, "Español"),
    ENGLISH_US(1, "Inglés (EE. UU.)"),
    ENGLISH_GB(18, "Inglés (Reino Unido)"),
    FRENCH(2, "Francés"),
    GERMAN(4, "Alemán"),
    ITALIAN(5, "Italiano"),
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
    /**
     * R4-P2-8: VSync OFF por defecto DELIBERADO, no un descuido: el
     * default de upstream en config.h:155 es true, pero con el backend
     * Vulkan (el de todos los presets) el valor es INERTE: solo
     * alimenta pending_vsync (renderer/state.h:196-197), que solo
     * consume el renderer OpenGL (gl/renderer.cpp:759-760), y el modo
     * de presentación Vulkan es fijo (Mailbox > FifoRelaxed > Fifo,
     * screen_renderer.cpp:197-218). En Vulkan no cuesta frames ni los
     * gana: no hace nada (ronda 0.2.64: la justificación anterior,
     * "en Mali la VSync cuesta frames", era falsa por ese mismo
     * motivo y se borró). El default false se mantiene porque alinear
     * con upstream (true) no cambiaría nada en Vulkan y en OpenGL el
     * usuario lo activa a mano. Ningún preset lleva true desde 0.2.64:
     * el modo 60 FPS tampoco lo fuerza (su único mecanismo real es
     * fps-hack). El interruptor en Ajustes → GPU lo cambia a mano en
     * cualquier momento; la etiqueta de esa pantalla dice que en
     * Vulkan no tiene efecto.
     */
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
     * verificada en config.h:205; consumo en SceNpManager.cpp:73
     * y :83). NO es una conexión real a PSN: no hay cuenta, ni
     * red, ni sincronización de trofeos. Solo le indica al juego
     * que hay sesión iniciada. ON por defecto: con OFF, los DLC
     * pueden no activarse. Se lee en el init del núcleo: el cambio
     * aplica en el siguiente lanzamiento en proceso fresco.
     */
    val psnSignedIn: Boolean = true,
    /**
     * Idioma del juego (0.2.63, ítem E): el idioma del sistema
     * emulado que el núcleo anuncia al juego (sys-lang en
     * config.yml, ver GameLanguage). ESPAÑOL por defecto. Se lee en
     * el init del núcleo: el cambio aplica en el siguiente
     * lanzamiento en proceso fresco. No es un campo de preset
     * (como psnSignedIn): mergePresetWithUserChoices lo conserva
     * al aplicar un preset, y CategoryReset no lo toca.
     */
    val gameLanguage: GameLanguage = GameLanguage.SPANISH,
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
    // 0.2.63 (A-8): el halo neón venía APAGADO por defecto y los
    // botones se veían planos sobre el juego; el mockup 51025
    // lleva el halo encendido. Nuevo default true (solo
    // instalaciones nuevas: quien ya tiene el ajuste guardado
    // conserva su valor).
    val touchOverlayNeonGlow: Boolean = true,
    /** Ids de perfiles de control en los que el overlay queda oculto en el juego. */
    val touchOverlayHiddenProfileIds: Set<String> = emptySet(),
) {
    val resolutionLabel: String
        get() = when (resolutionMultiplier) {
            0.5f -> "0.5x (480x272)"
            1f -> "1x (960x544)"
            1.25f -> "1.25x (1200x680)"
            1.5f -> "1.5x (1440x816)"
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
    // Xclipse (ronda 0.2.64, H-02): sin datos de teléfono todavía,
    // perfil seguro provisional con etiqueta propia (no el genérico
    // anónimo): 1x, Double Buffer, sin caché de texturas. Cuando haya
    // mediciones en un Exynos, aquí colgará su perfil 2x si lo sostiene.
    gpu.vendor == GpuVendor.XCLIPSE -> GpuProfileId.MALI_PERFORMANCE
    // PowerVR, OTHER y UNKNOWN: perfil Mali seguro (1x, Double
    // Buffer, sin caché de texturas). NINGUNA GPU se queda sin perfil:
    // el else es el suelo.
    else -> GpuProfileId.MALI_PERFORMANCE
}

/**
 * Presets que se ofrecen en Ajustes → General para una GPU (ronda
 * 0.2.64, H-01). Pura y testeable en JVM (GpuProfileTest). Antes la
 * lista vivía inline en la pantalla y la rama `else` ofrecía LOS 6
 * presets (incluido Calidad/Turnip) a PowerVR / Xclipse / OTHER /
 * UNKNOWN: el guard de applyPreset solo corregía el driver y el resto
 * del preset Adreno (2x, FXAA, anisotrópico 4x, Page Table) quedaba
 * aplicado en una GPU donde nunca se validó. Ahora la rama genérica
 * ofrece solo los dos seguros, igual que Mali. Sin GPU detectada
 * (null) no se ofrece nada: la pantalla muestra "Detectando GPU…".
 */
fun profilesFor(gpu: GpuInfo?): List<GpuProfileId> {
    if (gpu == null) return emptyList()
    return when {
        gpu.vendor == GpuVendor.ADRENO -> listOf(
            GpuProfileId.ADRENO_PERFORMANCE,
            GpuProfileId.ADRENO_QUALITY_TURNIP,
            GpuProfileId.G99_RECOMMENDED,
        )
        gpu.isUserVerifiedDevice || gpu.isHelioG99 || gpu.isMaliG57 -> listOf(
            GpuProfileId.G99_RECOMMENDED,
            GpuProfileId.G99_QUALITY,
            GpuProfileId.MALI_BALANCED,
        )
        gpu.vendor == GpuVendor.MALI -> listOf(
            GpuProfileId.MALI_PERFORMANCE,
            GpuProfileId.MALI_BALANCED,
        )
        // Xclipse, PowerVR, OTHER y UNKNOWN: solo los dos seguros.
        else -> listOf(
            GpuProfileId.MALI_PERFORMANCE,
            GpuProfileId.MALI_BALANCED,
        )
    }
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
        memoryMapping = MemoryMapping.DOUBLE_BUFFER, // 0.2.63 (B/C): elección del preset, NO fallback del núcleo: en el pin (renderer.cpp:748-766) DoubleBuffer y PageTable están SIEMPRE en la máscara soportada y NativeBuffer solo si el driver expone la importación de buffers; no hay ningún fallback especial para Mali. Double Buffer es además el método por defecto del núcleo (:750). Si va lento, probar Page Table.
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
        memoryMapping = MemoryMapping.PAGE_TABLE, // 0.2.64: sin
        // "recomendado en Adreno": las fuentes se contradicen (el
        // mantenedor de Vita3K-Android v5 decía Native Buffer en
        // Adreno / Page Table en Mali; Vita3K-Plus defaultea Page
        // Table) y hay cero mediciones en Child of Light sobre
        // Adreno. Page Table es válido y está siempre en la máscara
        // soportada; si va mal, probar Double Buffer (ver la nota en
        // Ajustes → GPU).
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
        // 0.2.64: SIN vSync = true (antes true): en Vulkan es inerte
        // (pending_vsync solo lo consume OpenGL) y se contaba como
        // una de las 6 diferencias con Recomendado sin serlo. El
        // default false queda.
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

/**
 * R4-P1-13: al aplicar un preset se CONSERVAN los cambios manuales
 * de cachés y overlay del usuario, igual que ya se conservaban
 * audio, modo seguro, cerrar apps, PSN, entrega del .pkg y overlay
 * táctil (ver SettingsRepository.applyPreset). Campos conservados:
 * textureCache, shaderCache, showFpsOverlay, hashlessTextureCache,
 * showCompileShaders y (R5-A2-1) performanceOverlayDetail y
 * performanceOverlayPosition: antes, un detalle o una posición del
 * HUD elegidos a mano volvían a Mínimo/Arriba-izquierda al tocar
 * cualquier preset, sin aviso, y presetProfileFields SÍ los incluía,
 * así que la pérdida era invisible ("Perfil modificado" no salía).
 *
 * DECISIÓN (verificada campo a campo en presetSettings):
 * G99_RECOMMENDED y MALI_PERFORMANCE solo se diferencian en
 * textureCache (true frente a false): mismo backend, 1x, Bilinear,
 * anisotrópico 1, VSync OFF, async ON, High Accuracy OFF, Surface
 * Sync OFF, Double Buffer, cpuOpt, fpsHack OFF, fileLoadingDelay 0,
 * showFpsOverlay OFF, driver Sistema, shaderCache ON y
 * performanceMode ON por defecto. Por eso conservar la caché manual
 * puede borrar esa diferencia real: aplicar Mali Rendimiento sobre
 * un estado G99 deja textureCache ON y el resultado es idéntico al
 * preset G99 salvo la etiqueta. Eso NO se esconde: la divergencia
 * queda registrada (textureCache forma parte de
 * presetProfileFields, así que divergesFromPreset da true) y la
 * pantalla General muestra "Perfil modificado" nombrando la caché
 * de texturas explícitamente. El aviso es obligatorio precisamente
 * en este caso; sin él, conservar sería tan silencioso como
 * restablecer.
 *
 * EXCEPCIÓN lowRam: en el primer arranque (lowRam = true) NO se
 * conserva nada de esto: la variante conservadora ES el valor del
 * preset en ese camino (textureCache OFF) y el current que llega
 * son los defaults de fábrica (textureCache ON), así que conservar
 * desharía la variante y rompería la cadena R4-P0-9 (la variante
 * lowRam solo existe en el primer arranque y queda registrada como
 * divergencia conservadora, ver isLowRamConservativeVariant).
 * Sin current tampoco se conserva nada: el preset queda tal cual
 * (así MALI_PERFORMANCE mantiene textureCache OFF cuando no hay
 * ajustes guardados).
 *
 * Puro y testeable en JVM (GpuProfileTest).
 */
fun preserveManualCacheChoices(preset: LauncherSettings, current: LauncherSettings?, lowRam: Boolean = false): LauncherSettings {
    if (current == null || lowRam) return preset
    return preset.copy(
        textureCache = current.textureCache,
        shaderCache = current.shaderCache,
        showFpsOverlay = current.showFpsOverlay,
        hashlessTextureCache = current.hashlessTextureCache,
        showCompileShaders = current.showCompileShaders,
        performanceOverlayDetail = current.performanceOverlayDetail,
        performanceOverlayPosition = current.performanceOverlayPosition,
    )
}

/**
 * Regla de la prueba de 30 s: menos de 28 fps con un perfil 2x ->
 * volver a 1x (ronda 0.2.64, P1-3 Adreno + H-05 otras GPU).
 * Generalizada: antes solo cubría G99_QUALITY por su id; ahora cubre
 * CUALQUIER perfil 2x (ADRENO_QUALITY_TURNIP incluido) y cualquier
 * 2x manual (la sobrecarga por resolución mira el valor efectivo).
 * El destino del revert NO vive aquí: lo elige el llamador según la
 * GPU (ADRENO_PERFORMANCE en Adreno, G99_RECOMMENDED en G99/Mali).
 */
fun shouldAutoRevertTo1x(fpsAvg: Float?, profile: GpuProfileId, autoRevertEnabled: Boolean): Boolean {
    if (!autoRevertEnabled) return false
    if (fpsAvg == null) return false
    return shouldAutoRevertTo1x(fpsAvg, presetSettings(profile).resolutionMultiplier, autoRevertEnabled)
}

/** Sobrecarga por resolución efectiva (p. ej. un 2x puesto a mano sobre un perfil 1x). */
fun shouldAutoRevertTo1x(fpsAvg: Float?, resolutionMultiplier: Float, autoRevertEnabled: Boolean): Boolean {
    if (!autoRevertEnabled) return false
    if (fpsAvg == null) return false
    if (resolutionMultiplier < 2f) return false
    return fpsAvg < 28f
}

/**
 * Modo seguro: valores forzados cuando algo falla.
 * 1x, SDL, High Accuracy OFF, Async ON, shader cache ON,
 * Surface Sync Disabled, fps-hack OFF (P2-14: con 60 FPS ON el
 * config.yml emitía fps-hack: true + v-sync: false y el "modo
 * seguro" no era seguro).
 * R6-P1-09: también neutraliza los ajustes experimentales que más
 * pueden romper el juego: caché de texturas sin hash OFF, Double
 * Buffer (un Page Table en Mali puede ser la causa del fallo) y
 * retraso de carga de archivos 0.
 * Ronda 0.2.64 (P1-4 Adreno): también neutraliza Turnip
 * (gpuDriver = SYSTEM): un Turnip malo deja la app sin abrir
 * (upstream Vita3K-Android #731) o crashea al añadir el driver
 * (#946), y el modo seguro es justo el rescate para eso. El estado
 * instalado (turnipInstalled / customDriverName / turnipVersion) se
 * CONSERVA para poder reactivarlo al salir del modo seguro; solo el
 * driver ACTIVO vuelve a Sistema. effectiveSettings() lo aplica, así
 * que el config.yml emite custom-driver-name: "" mientras dure.
 * restoreSafeModeSnapshot restaura el driver anterior al salir.
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
    hashlessTextureCache = false,
    memoryMapping = MemoryMapping.DOUBLE_BUFFER,
    fileLoadingDelay = 0,
    gpuDriver = GpuDriver.SYSTEM,
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
 *
 * Ronda 0.2.64: el paquete YA NO fuerza vSync = true (antes sí):
 * en Vulkan es inerte (pending_vsync solo lo consume OpenGL) y el
 * único mecanismo real del modo 60 FPS es fps-hack; el valor guardado
 * de VSync se respeta. Double Buffer se mantiene a propósito: es el
 * método por defecto del núcleo (renderer.cpp:750) y el menos
 * dependiente del driver, para que el modo 60 no dependa del método
 * de mapeo que el usuario tuviera puesto. Ronda 0.2.64 (frame pacing):
 * el fps-hack está DESCARTADO como causa de la lentitud (el usuario
 * probó OFF y ON con el mismo resultado): no se toca como causa.
 */
fun apply60FpsMode(settings: LauncherSettings): LauncherSettings = settings.copy(
    resolutionMultiplier = 1f,
    screenFilter = ScreenFilter.BILINEAR,
    anisotropicFiltering = 1,
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
