package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.effectiveMappingMethod
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.model.resolveBackendReason
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Generador de config.yml de Vita3K a partir de los ajustes del launcher.
 *
 * VERIFICADO contra el código fuente de Vita3K, pin
 * 3e43bc803a04c775737a3474e0d54f3e10ca61bf (leído 2026-11-07):
 * vita3k/config/include/config/config.h (CONFIG_INDIVIDUAL),
 * vita3k/renderer/src/vulkan/renderer.cpp (memory-mapping),
 * vita3k/audio/src/audio.cpp (audio-backend),
 * vita3k/android/jni/native_drivers.cpp + vita3k/util/src/android_driver.cpp
 * (custom-driver-name). Cada clave emitida abajo existe en
 * CONFIG_INDIVIDUAL con ese nombre exacto. Los ajustes del
 * launcher SIN clave verificada en config.h no se emiten en
 * absoluto: no existen en el modelo de ajustes (se eliminaron
 * por no tener efecto real), así que no hay líneas TODO-VERIFY.
 *
 * Claves verificadas usadas:
 * - backend-renderer ("Vulkan"), resolution-multiplier (float 0.5/1.0/2.0/3.0/4.0),
 *   screen-filter ("Bilinear"), v-sync (bool), anisotropic-filtering (int),
 *   async-pipeline-compilation (bool), high-accuracy (bool),
 *   validation-layer (bool, false siempre: ronda 0.2.66, G11-07),
 *   disable-surface-sync (bool, INVERTIDO: true = Surface Sync Disabled),
 *   memory-mapping ("double-buffer" / "external-host" / "page-table" /
 *   "native-buffer" — solo Android — y cualquier otro valor, p. ej. "off",
 *   equivale a Disabled por fall-through en el parser del renderer),
 *   texture-cache, shader-cache, audio-backend ("SDL" / "Cubeb"),
 *   audio-volume (int), ngs-enable (bool),
 *   audio-device-sample-frames (int, default 512; 0.2.66, parche
 *   aud-p2-1: tamaño de búfer de dispositivo SDL),
 *   cpu-opt (bool),
 *   exp-audio-sdl (bool, OFF por defecto; ronda 0.2.66, parche
 *   experimental R1 en app/src/main/cpp/patches/r1-audio-sdl-exp.patch:
 *   la clave la DEFINE ese parche en config.h del núcleo (CONFIG_INDIVIDUAL),
 *   no el pin 3e43bc80: sin el parche aplicado, el núcleo ignora la clave
 *   en silencio y el interruptor no hace nada. El generador la emite
 *   siempre para que el config.yml sea el mismo con y sin parche),
 *   fps-hack (bool), turbo-mode (bool, solo Adreno con driver
 *   personalizado; en Mali no hace nada, pin config.h:149),
 *   file-loading-delay (int),
 *   performance-overlay (bool), performance-overlay-detail (int,
 *   default MINIMUM = 0; enum MINIMUM/LOW/MEDIUM/MAXIMUM verificado
 *   en config.h:29-34, clave en :187), performance-overlay-position (int,
 *   default TOP_LEFT = 0; enum verificado en config.h:36-43, clave en :188),
 *   hashless-texture-cache (bool, default false; consumo verificado
 *   en renderer/src/texture/cache.cpp: use_protect + protección de
 *   páginas para texturas grandes),
 *   show-compile-shaders (bool,
 *   default true en upstream; el launcher emite false por defecto =
 *   mensajes ocultos, pantalla limpia),
 *
 * Semántica real en Vulkan (ronda 0.2.65, R-P1-5/6/7 — leer antes de
 * tocar estos interruptores; actualizado 0.2.66 con los parches C-01/C-02):
 * - texture-cache: desde la 0.2.66 (parche c-01) Vulkan SÍ la honra:
 *   con false no se consulta ni se rellena la caché (cada draw vuelve
 *   a subir la textura). Antes era INERTE en Vulkan (ningún código
 *   Vulkan leía config.texture_cache; texture.cpp:130 cacheaba
 *   incondicional) y solo OpenGL la usaba (gl/sync_state.cpp:420-424).
 * - shader-cache: en Vulkan controla el PRECOMPILADO de shaders
 *   conocidos al arrancar (app.cpp:281) y, desde la 0.2.66 (parche
 *   c-02), TAMBIÉN la lectura y la escritura de los .spv en disco:
 *   con false no se lee ni se escribe caché. Antes la caché en disco
 *   se cargaba/guardaba incondicionalmente (pipeline_cache.cpp,
 *   renderer.cpp:1285,1840) y OFF no ahorraba disco. La etiqueta
 *   honesta sigue siendo "Precompilar shaders conocidos al arrancar"
 *   (ver LauncherSettings.shaderCache) porque el precompilado es lo
 *   que más se nota; el ahorro de disco es un efecto añadido.
 * - hashless-texture-cache: IGNORADO en Vulkan, siempre ON
 *   (renderer.cpp:1046 hardcodeado a true; solo GL pasa el valor,
 *   gl/renderer.cpp:268). Se sigue emitiendo para OpenGL.
 *
 * Validación (ronda 0.2.65, T-P2-4): generate() emite solo desde
 * ajustes saneados (sanitizedSettings) y writeConfig() valida el
 * YAML generado (claves conocidas, tipos y rangos) antes de
 * escribir; si algo es inválido, se registra y se usa el default
 * seguro, nunca se escribe un valor inválido.
 *   show-welcome (bool, default true en upstream; verificado en
 *   config.h:196; su único consumo verificado es el diálogo de
 *   bienvenida de la GUI Qt (gui-qt/src/main_window.cpp:1031-1037):
 *   en el camino Android/SDL ese diálogo ni siquiera se ejecuta,
 *   pero la clave es real y se emite false siempre),
 *   psn-signed-in (int, default false en upstream; verificado en
 *   config.h:205; lo consumen sceNpCheckCallback y
 *   sceNpGetServiceState en modules/SceNpManager/SceNpManager.cpp
 *   (:73 y :83): con 0 el núcleo responde siempre
 *   SCE_NP_SERVICE_STATE_SIGNED_OUT y los juegos que piden el
 *   estado de sesión antes de activar sus DLC nunca los aplican.
 *   El launcher emite 1 o 0 según el ajuste psnSignedIn
 *   (Ajustes → General → Motor, ON por defecto): es inicio de sesión
 *   EMULADO (sin cuenta real ni red), lo que el usuario llama "señal
 *   PSN emulada"; sin él, los DLC instalados no se activan),
 *   custom-driver-name (string: nombre del driver instalado = stem
 *   del zip, sin .zip; el driver debe estar instalado en el núcleo
 *   con installCustomDriver, ver driver_bridge.cpp),
 *   pref-path (string, ruta del vita_fs).
 *
 * NO verificadas (no se emiten):
 * - display-info-message: NO existe en config.h (lectura completa
 *   0-239, pin 3e43bc80) ni en Config::CurrentConfig
 *   (config/state.h:136-224). No hay ningún mecanismo de "mensajes
 *   informativos" en config; lo más cercano es warn-missing-firmware
 *   (config.h:197) y show-live-area-screen (:167), ninguno es un
 *   aviso informativo general. Por eso NO se emite.
 * - frame limiter, frame skipping y accurate thread scheduling:
 *   sin clave en config.h ni en config.cpp. No tienen efecto real
 *   en el launcher y se eliminaron del modelo de ajustes (auditoría
 *   de funcionalidad 2026-11-08). (El "audio buffer size" salió de
 *   esta lista en la 0.2.66: ahora SÍ tiene clave real,
 *   audio-device-sample-frames, AUD-P2-1.)
 * - cpu-backend separado (no hay clave: Dynarmic es el backend por
 *   defecto y cpu-opt controla sus optimizaciones)
 */
object Vita3kConfigGenerator {

    /**
     * gpu = GPU detectada (opcional, null = sin detectar): si el ajuste
     * pide Vulkan pero el teléfono NO tiene Vulkan, el backend emitido
     * es OpenGL (resolveBackend). Sin gpu no se cambia nada.
     * memoryMappingMask = máscara de métodos de mapeo soportados por
     * el driver (ronda 0.2.66, G11-02; ver
     * Vita3kCoreBridge.getSupportedMemoryMappingMask): null o 0 =
     * desconocida, se emite el pedido tal cual; conocida, se emite el
     * método EFECTIVO (effectiveMappingMethod) para que el núcleo no
     * caiga a Double Buffer en silencio.
     */
    fun generate(settings: LauncherSettings, vitaFsPath: String, gpu: GpuInfo? = null, memoryMappingMask: Int? = null): String {
        // T-P2-4: solo se emite desde ajustes saneados (claves
        // conocidas, tipos y rangos): un valor inválido (DataStore o
        // backup editado a mano) se corrige al default seguro antes
        // de llegar al YAML, nunca se escribe tal cual.
        val s = sanitizedSettings(effectiveSettings(settings))
        val backend = resolveBackend(s, gpu)
        val sb = StringBuilder()
        sb.appendLine("# config.yml generado por Child of Light Launcher")
        sb.appendLine("# Perfil activo: ${s.activeProfile.label}")
        if (settings.safeMode) {
            sb.appendLine("# MODO SEGURO activo: 1x, SDL, High Accuracy OFF, driver del sistema (Turnip neutralizado).")
        }
        if (s.fpsHack) {
            sb.appendLine("# Modo 60 FPS activo: fps-hack + paquete rápido (1x, Double Buffer, cachés ON). El único mecanismo real del modo es fps-hack (VSync no se fuerza: en Vulkan es inerte). Se revierte apagando el interruptor en Ajustes.")
        }
        sb.appendLine("# Fuente de claves: config.h del núcleo (pin 3e43bc80, 2026-11-07).")
        sb.appendLine("# No se inventan claves: solo se emiten las verificadas en config.h.")
        sb.appendLine()
        // --- GPU ---
        sb.appendLine("# GPU — backend-renderer verificado (\"Vulkan\" / \"OpenGL\").")
        if (backend == GpuBackend.OPENGL && s.backend == GpuBackend.VULKAN) {
            sb.appendLine("# Sin Vulkan usable en este teléfono (versión reportada: ${gpu?.vulkanVersionLabel ?: "desconocida"}): fallback automático a OpenGL (el ajuste pedía Vulkan).")
        }
        sb.appendLine("backend-renderer: \"${escapeYamlDoubleQuoted(backend.vita3kValue)}\"")
        sb.appendLine("# GPU — resolution-multiplier es float en config.h: 0.5 = 480x272, 1.0 = 960x544, 2.0 = 1920x1088, 3.0 = 2880x1632, 4.0 = 3840x2176.")
        sb.appendLine("resolution-multiplier: ${formatFloat(s.resolutionMultiplier)}")
        sb.appendLine("# GPU — screen-filter verificado (default \"Bilinear\").")
        sb.appendLine("screen-filter: \"${escapeYamlDoubleQuoted(s.screenFilter.vita3kValue)}\"")
        // v-sync VERIFICADO (config.h:155): bool, default true en
        // upstream. OJO (0.2.63, B/C P1-2): en Vulkan es INERTE:
        // solo alimenta pending_vsync (renderer/state.h:197), que
        // solo consume el renderer OpenGL (gl/renderer.cpp:759-760).
        // Con backend Vulkan (el del teléfono) activarlo o
        // desactivarlo NO cambia el rendimiento ni el tearing: no es
        // causa de lentitud ni arreglo. El launcher lo deja en false
        // por defecto; en OpenGL sí tiene efecto (el usuario puede
        // activarlo en Ajustes).
        sb.appendLine("v-sync: ${s.vSync}")
        // Ronda 0.2.64 (P2-2 Mali) + 0.2.65 (RC-P2-6): anisotrópico
        // limitado al máximo del dispositivo (GpuInfo.maxAnisotropy,
        // leído por GLES en la detección): el núcleo NO lo limita
        // (set_anisotropic_filtering solo asigna, renderer.cpp:1651-
        // 1653) y el valor va crudo a VkSamplerCreateInfo.maxAnisotropy
        // (texture.cpp:588): por encima del límite del dispositivo es
        // uso inválido de Vulkan. Sin máximo conocido el tope seguro
        // es 8x (todas las familias que el launcher conoce — Adreno,
        // Mali, PowerVR, Xclipse — exponen 8x o más); la UI ofrece
        // como mucho 16x.
        val aniso = gpu?.maxAnisotropy?.let { minOf(s.anisotropicFiltering, it) } ?: minOf(s.anisotropicFiltering, 8)
        if (aniso != s.anisotropicFiltering) {
            sb.appendLine("# Anisotrópico limitado al máximo del dispositivo (${gpu?.maxAnisotropy}x): el ajuste pedía ${s.anisotropicFiltering}x.")
        }
        sb.appendLine("anisotropic-filtering: $aniso")
        sb.appendLine("async-pipeline-compilation: ${s.asyncPipelineCompilation}")
        sb.appendLine("high-accuracy: ${s.highAccuracy}")
        // Ronda 0.2.66 (G11-07 / fork P15): validation-layer
        // VERIFICADO (config.h:138, bool, default true en upstream).
        // Si la capa de validación de Vulkan está presente, el pin la
        // activa con su coste; en Android de serie normalmente no
        // existe, así que en este teléfono probablemente no hace nada
        // — pero el launcher lo cierra de forma determinista
        // emitiendo false siempre.
        sb.appendLine("validation-layer: false")
        // disable-surface-sync es el inverso de surfaceSyncEnabled.
        sb.appendLine("# GPU — disable-surface-sync es el INVERSO de Surface Sync: true = Disabled (más rápido), false = Enabled (más preciso).")
        sb.appendLine("disable-surface-sync: ${!s.surfaceSyncEnabled}")
        // memory-mapping VERIFICADO (renderer.cpp, VKState::late_init):
        // el núcleo compara el string exacto: "double-buffer" -> DoubleBuffer,
        // "external-host" -> ExernalHost, "page-table" -> PageTable,
        // "native-buffer" -> NativeBuffer (solo Android). Cualquier otro
        // valor ("off" incluido) cae en Disabled por fall-through. Los 5
        // valores del enum MemoryMapping son, por tanto, válidos tal cual.
        // Ronda 0.2.66 (G11-02): con máscara conocida se emite el método
        // EFECTIVO, no el pedido: si el pedido no está en la máscara, el
        // núcleo caería a Double Buffer EN SILENCIO (renderer.cpp:1019-
        // 1022). Con máscara desconocida (null/0) se deja el pedido.
        val effectiveMapping = effectiveMappingMethod(s.memoryMapping, memoryMappingMask ?: 0)
        if (effectiveMapping != s.memoryMapping) {
            sb.appendLine("# GPU — memory-mapping: el pedido (${s.memoryMapping.vita3kValue}) no está en la máscara soportada por el driver (máscara ${memoryMappingMask ?: 0}): se emite el efectivo (${effectiveMapping.vita3kValue}).")
        }
        sb.appendLine("# GPU — memory-mapping verificado (renderer.cpp late_init): \"double-buffer\" / \"external-host\" / \"page-table\" / \"native-buffer\" (solo Android); \"off\" = Disabled.")
        sb.appendLine("memory-mapping: \"${escapeYamlDoubleQuoted(effectiveMapping.vita3kValue)}\"")
        // Ronda 0.2.65 (R-P1-5): texture-cache es INERTE en Vulkan
        // (ningún código Vulkan lee config.texture_cache;
        // texture.cpp:130 cachea incondicional). Solo OpenGL la usa
        // (gl/sync_state.cpp:420-424). Se sigue emitiendo para ese
        // camino; en el teléfono no hace nada. El arreglo real
        // (leerla en Vulkan) necesita pin.
        sb.appendLine("texture-cache: ${s.textureCache}")
        // Ronda 0.2.65 (R-P1-6): shader-cache en Vulkan solo
        // controla el precompilado de shaders conocidos al arrancar
        // (app.cpp:281); la caché en disco se carga/guarda siempre.
        sb.appendLine("shader-cache: ${s.shaderCache}")
        // --- Driver ---
        // Turnip SOLO si el driver activo es TURNIP: antes bastaba con
        // tenerlo instalado + nombre guardado, así que un usuario que
        // instalaba Turnip en Adreno y luego cambiaba a "Sistema"
        // (ADRENO_PERFORMANCE o useSystemDriver) seguía con Turnip
        // activo en el config.yml sin saberlo. El nombre/versión
        // guardados se conservan, pero no se emiten si no está activo.
        // custom-driver-name VERIFICADO (native_drivers.cpp + android_driver.cpp):
        // el valor es el NOMBRE DEL DRIVER instalado = stem del zip
        // (p. ej. "Turnip_v26.0.0_R8_Gmem"), sin .zip y sin ruta. El núcleo
        // lo busca como carpeta <filesDir>/driver/<nombre>/ (con meta.json
        // o driver_name.txt); el zip por sí solo NO basta: el launcher
        // instala el driver en el núcleo con installCustomDriver al
        // instalar el zip y al lanzar el juego (ver driver_bridge.cpp y
        // Vita3KLauncher.ensureTurnipDriverInstalled). Si el nombre no
        // está instalado, el núcleo avisa en el log y usa el driver del
        // sistema; el config.yml no puede "activar" un driver ausente.
        if (s.gpuDriver == GpuDriver.TURNIP && s.turnipInstalled && s.customDriverName.isNotBlank()) {
            sb.appendLine("# Driver personalizado Turnip (solo Adreno). custom-driver-name verificado: es el stem del zip instalado en el núcleo (<filesDir>/driver/<nombre>/).")
            sb.appendLine("custom-driver-name: \"${escapeYamlDoubleQuoted(s.customDriverName)}\"")
        } else {
            sb.appendLine("custom-driver-name: \"\"")
        }
        // --- CPU ---
        sb.appendLine("# CPU — cpu-opt verificado. No hay clave cpu-backend en config.h: Dynarmic es el backend por defecto.")
        sb.appendLine("cpu-opt: ${s.cpuOpt}")
        sb.appendLine("fps-hack: ${s.fpsHack}")
        // Ronda 0.2.65 (RG-P2-15): turbo-mode verificado en el pin
        // (config.h:149, bool, default false). Solo Adreno con driver
        // personalizado: el núcleo lo aplica vía adrenotools_set_turbo
        // (vulkan/renderer.cpp:1849-1856); en Mali y con el driver del
        // sistema no hace nada. Se emite siempre (OFF por defecto).
        sb.appendLine("turbo-mode: ${s.turboMode}")
        sb.appendLine("file-loading-delay: ${s.fileLoadingDelay}")
        // --- Audio ---
        // audio-backend VERIFICADO (audio.cpp:64-74, AudioState::set_backend):
        // el núcleo acepta exactamente "SDL" y "Cubeb" (case-sensitive);
        // cualquier otro valor deja el adapter a null y el audio no arranca.
        sb.appendLine("# Audio — audio-backend verificado (audio.cpp set_backend): \"SDL\" o \"Cubeb\".")
        sb.appendLine("audio-backend: \"${escapeYamlDoubleQuoted(s.audioBackend.vita3kValue)}\"")
        // Ronda 0.2.66 (parches experimentales, R1): exp-audio-sdl es la
        // clave que lee el parche r1-audio-sdl-exp.patch (la define él en
        // config.h; el pin no la tiene). OFF = el audio es el actual.
        // Sin el parche aplicado en la compilación, el núcleo ignora la
        // clave en silencio: el interruptor no hace nada hasta que el
        // workflow aplique los parches (paso git apply, otro equipo).
        sb.appendLine("exp-audio-sdl: ${s.expAudioSdl}")
        sb.appendLine("audio-volume: ${s.audioVolume}")
        sb.appendLine("ngs-enable: ${s.ngsEnable}")
        // Ronda 0.2.66 (AUD-P2-1): clave REAL en config.h
        // (audio-device-sample-frames, default 512; parche
        // aud-p2-1-audio-device-sample-frames.patch): tamaño de
        // búfer de dispositivo SDL (SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES).
        // Se emite siempre con el default del pin (512, el valor que
        // ya usaba el adaptador SDL): no hay ajuste de UI para esto en
        // esta ronda; el valor fijo evita que el generador emita una
        // clave sin efecto (eso era el AUD-P2-1 original).
        sb.appendLine("audio-device-sample-frames: 512")
        // --- Overlay ---
        // performance-overlay-detail/position VERIFICADOS (pin 3e43bc80,
        // config.h:187,188): ints con default MINIMUM = 0 y TOP_LEFT = 0.
        // hashless-texture-cache VERIFICADO (pin 3e43bc80,
        // renderer/src/texture/cache.cpp): true = use_protect, las
        // texturas grandes se vigilan por protección de páginas en vez
        // de re-hashear en cada frame; las pequeñas siguen con hash.
        // Ronda 0.2.65 (R-P1-7): en Vulkan es IGNORADO y siempre ON
        // (renderer.cpp:1046: texture_cache.init(true, ...); solo GL
        // pasa el valor, gl/renderer.cpp:268). Se sigue emitiendo
        // para OpenGL; en el teléfono el valor no tiene efecto.
        sb.appendLine("performance-overlay: ${s.showFpsOverlay}")
        sb.appendLine("performance-overlay-detail: ${s.performanceOverlayDetail.value}")
        sb.appendLine("performance-overlay-position: ${s.performanceOverlayPosition.value}")
        sb.appendLine("hashless-texture-cache: ${s.hashlessTextureCache}")
        // show-compile-shaders VERIFICADO (config.h:159): bool, default
        // true en upstream; el launcher emite false por defecto
        // (mensajes ocultos, pantalla limpia).
        sb.appendLine("show-compile-shaders: ${s.showCompileShaders}")
        // --- Interfaz del núcleo ---
        // show-welcome VERIFICADO (config.h:196): bool, default true.
        // Su único consumo verificado es el diálogo de bienvenida de
        // la GUI Qt (gui-qt/src/main_window.cpp:1031-1037); en el
        // camino Android/SDL ese diálogo ni siquiera se ejecuta. Se
        // emite false siempre: el launcher es un port, no muestra la
        // bienvenida del emulador.
        sb.appendLine("show-welcome: false")
        // --- PSN emulado (DLC) ---
        // psn-signed-in VERIFICADO (config.h:205): int, default false
        // en upstream. Lo consumen sceNpCheckCallback y
        // sceNpGetServiceState (modules/SceNpManager/SceNpManager.cpp
        // :73 y :83): con 0 el núcleo responde siempre
        // SCE_NP_SERVICE_STATE_SIGNED_OUT. Los juegos que preguntan
        // el estado de sesión antes de activar sus DLC nunca los
        // aplican sin esta señal (reporte del usuario desde Vita3K:
        // "si pongo los DLC y no está activada la señal PSN emulada
        // los DLC nunca se ponen"). El ajuste psnSignedIn del
        // launcher (Ajustes → General → Motor, ON por defecto) decide
        // el valor: 1 si está ON, 0 si está OFF. Es inicio de sesión
        // EMULADO, sin cuenta real ni red. La vía Android/SDL
        // (main_android.cpp, 201 líneas) no toca este valor ni lo
        // anula: basta con emitirlo en config.yml, no hace falta nada
        // por JNI. La licencia de cada DLC (.rif / work.bin) ya se
        // instala aparte con su .pkg (ver
        // EmbeddedPkgInstaller.installDlcPkg): esto no la sustituye,
        // la complementa. Se lee en el init del núcleo: el cambio
        // aplica en el siguiente lanzamiento en proceso fresco.
        // R4-P2-10: se emite desde el effective (s), no desde settings
        // directo: hoy ninguna transformación lo toca (modo seguro y
        // 60 FPS no cambian psnSignedIn), pero el generador debe emitir
        // siempre valores efectivos, no una mezcla.
        sb.appendLine("psn-signed-in: ${if (s.psnSignedIn) 1 else 0}")
        // --- Idioma del juego ---
        // sys-lang VERIFICADO (config.h:172): int, default
        // ENGLISH_US (1) en upstream. Valores del enum
        // SceSystemParamLang (util/system.h, implícito desde 0):
        // SPANISH 3, ENGLISH_US 1, ENGLISH_GB 18, FRENCH 2,
        // GERMAN 4, ITALIAN 5 (ver GameLanguage en
        // LauncherSettings.kt). El ajuste gameLanguage del launcher
        // (Ajustes → General → Motor, ESPAÑOL por defecto) decide
        // el valor. El juego EU PCSB00598 trae español: con 3 el
        // juego pide sus textos y subtítulos en español (p. ej.
        // .es.srt en los vídeos) en vez de los .en.srt que pedía en
        // inglés. Se lee en el init del núcleo: el cambio aplica en
        // el siguiente lanzamiento en proceso fresco. Se emite desde
        // el effective (s), como psn-signed-in: ni el modo seguro ni
        // 60 FPS lo tocan, pero el generador debe emitir siempre
        // valores efectivos, no una mezcla.
        sb.appendLine("sys-lang: ${s.gameLanguage.value}")
        // --- Rutas ---
        sb.appendLine("pref-path: \"${escapeYamlDoubleQuoted(vitaFsPath)}\"")
        return sb.toString()
    }

    /**
     * T-P2-4: problemas de un [LauncherSettings] ANTES de sanearlo:
     * un valor fuera de su tipo/rango sensato (p. ej. volumen 500 o
     * retraso -5 de un backup editado a mano). Los campos enum
     * siempre son válidos por construcción (DataStore hace
     * runCatching con default); solo se revisan los numéricos y
     * los libres. Vacío = válido. Puro y testeable en JVM.
     */
    fun validateSettings(settings: LauncherSettings): List<String> {
        val problems = mutableListOf<String>()
        val res = settings.resolutionMultiplier
        if (!res.isFinite() || res < 0.5f || res > 4f) {
            problems += "resolution-multiplier fuera de rango: $res (válido 0.5..4)"
        }
        if (settings.anisotropicFiltering !in setOf(1, 2, 4, 8, 16)) {
            problems += "anisotropic-filtering inválido: ${settings.anisotropicFiltering} (válido 1/2/4/8/16)"
        }
        if (settings.audioVolume !in 0..100) {
            problems += "audio-volume fuera de rango: ${settings.audioVolume} (válido 0..100)"
        }
        if (settings.fileLoadingDelay !in 0..30) {
            problems += "file-loading-delay fuera de rango: ${settings.fileLoadingDelay} (válido 0..30)"
        }
        if (!settings.touchOverlayOpacity.isFinite() || settings.touchOverlayOpacity < 0.1f || settings.touchOverlayOpacity > 1f) {
            problems += "touch-overlay-opacity fuera de rango: ${settings.touchOverlayOpacity} (válido 0.1..1)"
        }
        return problems
    }

    /**
     * T-P2-4: devuelve los ajustes con cada valor inválido
     * corregido a su default seguro (nunca se escribe un valor
     * inválido en config.yml). Resolución al snap más cercano
     * (SettingsRepository.clampResolution, con 1.25x/1.5x desde
     * 0.2.65), anisotrópico a 1, volumen a 0..100, retraso a 0..30,
     * opacidad a 0.1..1. generate() lo aplica siempre; writeConfig()
     * registra las correcciones con validateSettings().
     * Puro y testeable en JVM.
     */
    fun sanitizedSettings(settings: LauncherSettings): LauncherSettings = settings.copy(
        resolutionMultiplier = SettingsRepository.clampResolution(settings.resolutionMultiplier),
        anisotropicFiltering = if (settings.anisotropicFiltering in setOf(1, 2, 4, 8, 16)) settings.anisotropicFiltering else 1,
        audioVolume = settings.audioVolume.coerceIn(0, 100),
        fileLoadingDelay = settings.fileLoadingDelay.coerceIn(0, 30),
        touchOverlayOpacity = if (!settings.touchOverlayOpacity.isFinite()) 1f else settings.touchOverlayOpacity.coerceIn(0.1f, 1f),
    )

    /**
     * T-P2-4: valida un config.yml YA GENERADO, línea a línea:
     * claves conocidas (solo las 31 que emite generate()), tipos
     * (bool/int/float/string entrecomillada) y rangos sensatos, sin
     * duplicados. Es la segunda red tras sanitizedSettings(): si
     * esto falla, hay un bug en el generador, no en los ajustes.
     * Vacío = válido. Puro y testeable en JVM.
     *
     * Ronda 0.2.66 (REGLA DE ORO): cada clave nueva que emita generate()
     * DEBE estar aquí. Las 4 de esta ronda: "validation-layer" (bool,
     * G11-07, Fase 0), "audio-backend" (string, AUD-P0-1 de la Fase 0:
     * generate() la emitía desde siempre pero el validador no la conocía
     * y writeConfig() regeneraba con defaults en cada lanzamiento),
     * "audio-device-sample-frames" (int, AUD-P2-1, Fase 2) y
     * "exp-audio-sdl" (bool, R1, interruptores experimentales).
     */
    fun validateGeneratedConfig(content: String): List<String> {
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val boolKeys = setOf(
            "v-sync", "async-pipeline-compilation", "high-accuracy",
            "validation-layer",
            "disable-surface-sync", "texture-cache", "shader-cache",
            "cpu-opt", "fps-hack", "turbo-mode", "ngs-enable", "performance-overlay",
            "hashless-texture-cache", "show-compile-shaders", "show-welcome",
            "exp-audio-sdl",
        )
        val intRanges = mapOf(
            "anisotropic-filtering" to 1..16,
            "audio-volume" to 0..100,
            // Ronda 0.2.66 (AUD-P2-1): la clave nueva que emite
            // generate() tiene que estar aquí o se repite AUD-P0-1
            // (clave desconocida → writeConfig regenera defaults).
            "audio-device-sample-frames" to 64..4096,
            "file-loading-delay" to 0..30,
            "performance-overlay-detail" to 0..3,
            "performance-overlay-position" to 0..5,
            "psn-signed-in" to 0..1,
        )
        val stringKeys = mapOf(
            "backend-renderer" to setOf("Vulkan", "OpenGL"),
            "screen-filter" to setOf("Bilinear", "Nearest", "Bicubic", "FXAA", "FSR"),
            "memory-mapping" to setOf("double-buffer", "external-host", "page-table", "native-buffer", "off"),
            // Ronda 0.2.66 (AUD-P0-1): generate() emite audio-backend
            // siempre (:230-236) y el validador no lo conocía: caía en
            // "clave desconocida" y writeConfig regeneraba con
            // defaults en CADA lanzamiento (ningún ajuste llegaba al
            // núcleo). Nunca más: cada clave que emite generate()
            // DEBE estar en este validador (ver el test invariante
            // en R11Fase0Test).
            "audio-backend" to setOf("SDL", "Cubeb"),
        )
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(": ")
            if (colon < 0) {
                problems += "línea sin 'clave: valor': $line"
                continue
            }
            val key = line.substring(0, colon)
            val value = line.substring(colon + 2)
            if (!seen.add(key)) problems += "clave duplicada: $key"
            when {
                key in boolKeys -> if (value != "true" && value != "false") {
                    problems += "$key no es bool: $value"
                }
                key in intRanges -> {
                    val n = value.toIntOrNull()
                    if (n == null || n !in intRanges.getValue(key)) {
                        problems += "$key fuera de rango: $value (válido ${intRanges.getValue(key)})"
                    }
                }
                key == "resolution-multiplier" -> {
                    val f = value.toFloatOrNull()
                    if (f == null || !f.isFinite() || f < 0.5f || f > 4f) {
                        problems += "resolution-multiplier fuera de rango: $value (válido 0.5..4)"
                    }
                }
                key == "sys-lang" -> {
                    val n = value.toIntOrNull()
                    if (n == null || n !in setOf(1, 2, 3, 4, 5, 18)) {
                        problems += "sys-lang inválido: $value"
                    }
                }
                key in stringKeys -> {
                    val unquoted = value.removeSurrounding("\"")
                    if (value != "\"$unquoted\"" || unquoted !in stringKeys.getValue(key)) {
                        problems += "$key inválido: $value"
                    }
                }
                key == "custom-driver-name" || key == "pref-path" -> {
                    if (!value.startsWith("\"") || !value.endsWith("\"") || value.length < 2) {
                        problems += "$key no entrecomillado: $value"
                    } else if (key == "pref-path" && value == "\"\"") {
                        problems += "pref-path vacío"
                    }
                }
                else -> problems += "clave desconocida: $key"
            }
        }
        return problems
    }

    private fun formatFloat(value: Float): String {
        return if (value == value.toInt().toFloat()) "${value.toInt()}.0" else value.toString()
    }

    /**
     * Escapa un valor para interpolarlo entre comillas dobles en el
     * YAML generado (P2-B8, 2026-11-08).
     *
     * El parser del núcleo es yaml-cpp (config.cpp:22 del pin
     * 3e43bc80; config/CMakeLists.txt:18 lo enlaza) y parse() devuelve
     * FileNotFound ante YAML::Exception (config.cpp:301-328): un valor
     * sin escapar con una comilla o un salto de línea rompía todo el
     * config.yml y el núcleo arrancaba con defaults en silencio.
     * Los escapes emitidos son los estándar del estilo entrecomillado
     * doble de YAML, que yaml-cpp acepta: barra invertida, comilla
     * doble, salto de línea, retorno de carro y tabulador con su
     * escape corto, y cualquier otro carácter de control como
     * escape unicode de 4 dígitos. El resto de caracteres se deja
     * tal cual: un nombre normal sale idéntico.
     *
     * Pura y sin estado, para poder probarla en la JVM.
     */
    fun escapeYamlDoubleQuoted(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20 || c.code == 0x7F) {
                        sb.append("\\u%04X".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Ruta del vita_fs del núcleo para una raíz de almacenamiento:
     * <vitaRoot>/vita (initialize_session: vita_fs = storage / "vita").
     * Pura para poder probarla en la JVM: pref-path DEBE ser esta
     * ruta, no la raíz (ver writeConfig).
     */
    fun vitaFsPathFor(vitaRoot: File): File = File(vitaRoot, "vita")

    /**
     * Escribe config.yml en <vitaRoot>/config.yml y devuelve el archivo.
     *
     * R4-P0-7 + R4-P2-4: IDEMPOTENTE. Si el archivo ya tiene el mismo
     * contenido, NO se reescribe (ni se registra la línea de log):
     * JUGAR escribía el mismo config.yml 3 veces (play + firmware +
     * launchImpl) y el log lo confirmaba 3 veces por segundo. La única
     * escritura por JUGAR la hace play(); esto es la red de seguridad
     * para cualquier otro camino que vuelva a llamar aquí con los
     * mismos ajustes.
     *
     * OJO (bug -3, 2026-11-08): pref-path NO es la raíz de
     * almacenamiento: upstream lo trata COMO el vita_fs (config.h:182
     * del pin 3e43bc80: pref-path es cfg.vita_fs_path; app_init.cpp
     * :434-440 usa ese valor como state.vita_fs_path cuando difiere
     * del root). El vita_fs del núcleo es <vitaRoot>/vita
     * (initialize_session: vita_fs = storage_path / "vita"), así que
     * pref-path DEBE ser <vitaRoot>/vita. Emitir <vitaRoot> hacía que
     * en un proceso fresco el núcleo buscara los juegos en el ux0
     * antiguo (vacío) en vez de en vita/ux0: loadGame devolvía -3
     * ("no encontró el juego instalado") aunque el juego estuviera
     * instalado, y refreshAppsList no lo arreglaba porque re-escanea
     * ese mismo ux0 equivocado. generate() ya recibe el parámetro
     * como vitaFsPath: aquí se le pasa la ruta correcta.
     */
    fun writeConfig(context: Context, settings: LauncherSettings, vitaRoot: File, gpu: GpuInfo? = null, memoryMappingMask: Int? = null): File {
        val vitaFs = vitaFsPathFor(vitaRoot)
        // T-P2-4: validar ANTES de escribir. Los valores inválidos
        // se corrigen a defaults seguros en generate() (vía
        // sanitizedSettings); aquí queda registrado qué se corrigió.
        val settingProblems = validateSettings(effectiveSettings(settings))
        if (settingProblems.isNotEmpty()) {
            LauncherLog.append(context, "config", "config.yml: valores inválidos corregidos a defaults seguros: ${settingProblems.joinToString("; ")}")
        }
        // RG-P2-12: la decisión de backend queda registrada.
        val backendReason = resolveBackendReason(effectiveSettings(settings), gpu)
        if (backendReason != null) {
            LauncherLog.append(context, "gpu", "config.yml: $backendReason")
        }
        // Ronda 0.2.66 (G11-02): con máscara conocida se registra el
        // método de mapeo EFECTIVO que se emite (el pedido puede no
        // estar soportado por el driver). Se calcula sobre los
        // ajustes efectivos (modo seguro / 60 FPS ya fuerzan Double
        // Buffer antes de resolver), igual que hace generate().
        if (memoryMappingMask != null && memoryMappingMask != 0) {
            val requested = effectiveSettings(settings).memoryMapping
            val effective = effectiveMappingMethod(requested, memoryMappingMask)
            if (effective != requested) {
                LauncherLog.append(context, "gpu", "config.yml: memory-mapping efectivo ${effective.label} (pedido: ${requested.label}, máscara: $memoryMappingMask): el pedido no está soportado por el driver; sin esto el núcleo caería a Double Buffer en silencio")
            } else {
                LauncherLog.append(context, "gpu", "config.yml: memory-mapping efectivo ${effective.label} (pedido: ${requested.label}, máscara: $memoryMappingMask)")
            }
        }
        var content = generate(settings, vitaFs.absolutePath, gpu, memoryMappingMask)
        // T-P2-4, segunda red: si el YAML generado sigue siendo
        // inválido (bug del generador, no de los ajustes), no se
        // escribe tal cual: se regenera con los defaults seguros y
        // queda registrado. Nunca se escribe un valor inválido.
        val yamlProblems = validateGeneratedConfig(content)
        if (yamlProblems.isNotEmpty()) {
            LauncherLog.append(context, "config", "config.yml generado inválido (${yamlProblems.joinToString("; ")}): se regenera con defaults seguros")
            content = generate(LauncherSettings(), vitaFs.absolutePath, gpu, memoryMappingMask)
        }
        if (!vitaRoot.exists()) vitaRoot.mkdirs()
        val file = File(vitaRoot, "config.yml")
        val report = writeConfigIfChangedDetailed(file, content)
        if (report.wrote) {
            LauncherLog.append(context, "config", "config.yml escrito (${content.length} bytes, perfil ${settings.activeProfile.name})")
        }
        // R6-P2-29: la lectura previa falló (contenido desconocido):
        // se reescribió entero; queda en el log para que el próximo
        // diagnóstico sepa que el config anterior no se pudo leer.
        if (report.previousReadFailed) {
            LauncherLog.append(context, "config", "config.yml no se pudo leer antes de escribir (contenido previo desconocido): se reescribió entero")
        }
        return file
    }

    /**
     * Informe de una escritura de config.yml (R6-P2-29): [wrote] es
     * true si se escribió, [previousReadFailed] es true si la lectura
     * del contenido previo falló y se escribió sin saber qué había
     * (el llamador lo registra en el log).
     */
    data class ConfigWriteReport(
        val wrote: Boolean,
        val previousReadFailed: Boolean,
    )

    /**
     * Escribe [content] en [file] SOLO si difiere del contenido actual
     * del archivo; devuelve true si escribió y false si ya era idéntico
     * (entonces no toca el archivo: ni bytes ni fecha). Si el archivo
     * no existe, lo crea (con sus carpetas padre). Pura salvo File,
     * para poder probarla en la JVM sin Android (el log vive en
     * writeConfig, no aquí).
     *
     * R6-P2-29: si config.yml es un directorio, el error es explícito
     * y documentado (IllegalStateException con la ruta): antes era un
     * FileNotFoundException incidental de writeText. El llamador lo
     * muestra como bloqueo, no como crash. La lectura es UTF-8
     * estricta (decodificador con CodingErrorAction.REPORT: los
     * bytes inválidos lanzan MalformedInputException): si falla, el
     * contenido previo se trata como desconocido (no como "vacío")
     * y se reintenta escribir; el informe marca previousReadFailed
     * para que writeConfig lo registre en el log. (File.readText()
     * NO vale aquí: sustituye los bytes inválidos por U+FFFD sin
     * lanzar. Tampoco vale Files.readString: no existe en el
     * android.jar — run #198 — ni Files.readAllBytes, que es API
     * 33+ y el minSdk es 28; se leen los bytes con file.readBytes()
     * y se decodifican a mano, APIs presentes desde API 1.)
     */
    fun writeConfigIfChangedDetailed(file: File, content: String): ConfigWriteReport {
        if (file.isDirectory) {
            throw IllegalStateException("config.yml es un directorio, no un archivo: ${file.absolutePath}")
        }
        var previousReadFailed = false
        val existing = if (file.isFile) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.readBytes()))
                    .toString()
            } catch (e: Exception) {
                previousReadFailed = true
                null
            }
        } else {
            null
        }
        if (existing == content) return ConfigWriteReport(wrote = false, previousReadFailed = previousReadFailed)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ConfigWriteReport(wrote = true, previousReadFailed = previousReadFailed)
    }

    /**
     * Escribe [content] en [file] SOLO si difiere del contenido actual
     * del archivo; devuelve true si escribió y false si ya era idéntico
     * (entonces no toca el archivo: ni bytes ni fecha). Si el archivo
     * no existe, lo crea (con sus carpetas padre). Pura salvo File,
     * para poder probarla en la JVM sin Android (el log vive en
     * writeConfig, no aquí). Delega en writeConfigIfChangedDetailed.
     */
    fun writeConfigIfChanged(file: File, content: String): Boolean {
        return writeConfigIfChangedDetailed(file, content).wrote
    }
}
