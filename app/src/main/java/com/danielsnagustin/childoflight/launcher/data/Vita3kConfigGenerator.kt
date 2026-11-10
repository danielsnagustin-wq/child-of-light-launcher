package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
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
 * - backend-renderer ("Vulkan"), resolution-multiplier (float 1.0/2.0/3.0/4.0),
 *   screen-filter ("Bilinear"), v-sync (bool), anisotropic-filtering (int),
 *   async-pipeline-compilation (bool), high-accuracy (bool),
 *   disable-surface-sync (bool, INVERTIDO: true = Surface Sync Disabled),
 *   memory-mapping ("double-buffer" / "external-host" / "page-table" /
 *   "native-buffer" — solo Android — y cualquier otro valor, p. ej. "off",
 *   equivale a Disabled por fall-through en el parser del renderer),
 *   texture-cache, shader-cache, audio-backend ("SDL" / "Cubeb"),
 *   audio-volume (int), ngs-enable (bool), cpu-opt (bool),
 *   fps-hack (bool), file-loading-delay (int),
 *   performance-overlay (bool), performance-overlay-detail (int,
 *   default MINIMUM = 0; enum MINIMUM/LOW/MEDIUM/MAXIMUM verificado
 *   en config.h:29-34, clave en :187), performance-overlay-position (int,
 *   default TOP_LEFT = 0; enum verificado en config.h:36-43, clave en :188),
 *   hashless-texture-cache (bool, default false; consumo verificado
 *   en renderer/src/texture/cache.cpp: use_protect + protección de
 *   páginas para texturas grandes), show-compile-shaders (bool,
 *   default true en upstream; el launcher emite false por defecto =
 *   mensajes ocultos, pantalla limpia),
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
 * - frame limiter, frame skipping, accurate thread scheduling y
 *   audio buffer size: sin clave en config.h ni en config.cpp.
 *   No tienen efecto real en el launcher y se eliminaron del
 *   modelo de ajustes (auditoría de funcionalidad 2026-11-08).
 * - cpu-backend separado (no hay clave: Dynarmic es el backend por
 *   defecto y cpu-opt controla sus optimizaciones)
 */
object Vita3kConfigGenerator {

    /**
     * gpu = GPU detectada (opcional, null = sin detectar): si el ajuste
     * pide Vulkan pero el teléfono NO tiene Vulkan, el backend emitido
     * es OpenGL (resolveBackend). Sin gpu no se cambia nada.
     */
    fun generate(settings: LauncherSettings, vitaFsPath: String, gpu: GpuInfo? = null): String {
        val s = effectiveSettings(settings)
        val backend = resolveBackend(s, gpu)
        val sb = StringBuilder()
        sb.appendLine("# config.yml generado por Child of Light Launcher")
        sb.appendLine("# Perfil activo: ${s.activeProfile.label}")
        if (settings.safeMode) {
            sb.appendLine("# MODO SEGURO activo: 1x, SDL, High Accuracy OFF.")
        }
        if (s.fpsHack) {
            sb.appendLine("# Modo 60 FPS activo: fps-hack + paquete rápido (1x, VSync ON, Double Buffer, cachés ON). Se revierte apagando el interruptor en Ajustes.")
        }
        sb.appendLine("# Fuente de claves: config.h del núcleo (pin 3e43bc80, 2026-11-07).")
        sb.appendLine("# No se inventan claves: solo se emiten las verificadas en config.h.")
        sb.appendLine()
        // --- GPU ---
        sb.appendLine("# GPU — backend-renderer verificado (\"Vulkan\" / \"OpenGL\").")
        if (backend == GpuBackend.OPENGL && s.backend == GpuBackend.VULKAN) {
            sb.appendLine("# Sin Vulkan en este teléfono: fallback automático a OpenGL (el ajuste pedía Vulkan).")
        }
        sb.appendLine("backend-renderer: \"${escapeYamlDoubleQuoted(backend.vita3kValue)}\"")
        sb.appendLine("# GPU — resolution-multiplier es float en config.h: 1.0 = 960x544, 2.0 = 1920x1088, 3.0 = 2880x1632, 4.0 = 3840x2176.")
        sb.appendLine("resolution-multiplier: ${formatFloat(s.resolutionMultiplier)}")
        sb.appendLine("# GPU — screen-filter verificado (default \"Bilinear\").")
        sb.appendLine("screen-filter: \"${escapeYamlDoubleQuoted(s.screenFilter.vita3kValue)}\"")
        // v-sync VERIFICADO (config.h:155): bool, default true en
        // upstream. El launcher lo pone en false DELIBERADAMENTE
        // (LauncherSettings.vSync = false en todos los perfiles):
        // con v-sync activado el Mali-G57 del teléfono pierde
        // fotogramas esperando al refresco; desactivado, el juego
        // corre más fluido y el tearing no se nota en esta pantalla.
        // No es un olvido: el usuario puede activarlo en Ajustes.
        sb.appendLine("v-sync: ${s.vSync}")
        sb.appendLine("anisotropic-filtering: ${s.anisotropicFiltering}")
        sb.appendLine("async-pipeline-compilation: ${s.asyncPipelineCompilation}")
        sb.appendLine("high-accuracy: ${s.highAccuracy}")
        // disable-surface-sync es el inverso de surfaceSyncEnabled.
        sb.appendLine("# GPU — disable-surface-sync es el INVERSO de Surface Sync: true = Disabled (más rápido), false = Enabled (más preciso).")
        sb.appendLine("disable-surface-sync: ${!s.surfaceSyncEnabled}")
        // memory-mapping VERIFICADO (renderer.cpp, VKState::late_init):
        // el núcleo compara el string exacto: "double-buffer" -> DoubleBuffer,
        // "external-host" -> ExernalHost, "page-table" -> PageTable,
        // "native-buffer" -> NativeBuffer (solo Android). Cualquier otro
        // valor ("off" incluido) cae en Disabled por fall-through. Los 5
        // valores del enum MemoryMapping son, por tanto, válidos tal cual.
        sb.appendLine("# GPU — memory-mapping verificado (renderer.cpp late_init): \"double-buffer\" / \"external-host\" / \"page-table\" / \"native-buffer\" (solo Android); \"off\" = Disabled.")
        sb.appendLine("memory-mapping: \"${escapeYamlDoubleQuoted(s.memoryMapping.vita3kValue)}\"")
        sb.appendLine("texture-cache: ${s.textureCache}")
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
        sb.appendLine("file-loading-delay: ${s.fileLoadingDelay}")
        // --- Audio ---
        // audio-backend VERIFICADO (audio.cpp:64-74, AudioState::set_backend):
        // el núcleo acepta exactamente "SDL" y "Cubeb" (case-sensitive);
        // cualquier otro valor deja el adapter a null y el audio no arranca.
        sb.appendLine("# Audio — audio-backend verificado (audio.cpp set_backend): \"SDL\" o \"Cubeb\".")
        sb.appendLine("audio-backend: \"${escapeYamlDoubleQuoted(s.audioBackend.vita3kValue)}\"")
        sb.appendLine("audio-volume: ${s.audioVolume}")
        sb.appendLine("ngs-enable: ${s.ngsEnable}")
        // --- Overlay ---
        // performance-overlay-detail/position VERIFICADOS (pin 3e43bc80,
        // config.h:187,188): ints con default MINIMUM = 0 y TOP_LEFT = 0.
        // hashless-texture-cache VERIFICADO (pin 3e43bc80,
        // renderer/src/texture/cache.cpp): true = use_protect, las
        // texturas grandes se vigilan por protección de páginas en vez
        // de re-hashear en cada frame; las pequeñas siguen con hash.
        // show-compile-shaders VERIFICADO (config.h:159): bool, default
        // true en upstream; el launcher emite false por defecto
        // (mensajes ocultos, pantalla limpia).
        sb.appendLine("performance-overlay: ${s.showFpsOverlay}")
        sb.appendLine("performance-overlay-detail: ${s.performanceOverlayDetail.value}")
        sb.appendLine("performance-overlay-position: ${s.performanceOverlayPosition.value}")
        sb.appendLine("hashless-texture-cache: ${s.hashlessTextureCache}")
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
        // --- Rutas ---
        sb.appendLine("pref-path: \"${escapeYamlDoubleQuoted(vitaFsPath)}\"")
        return sb.toString()
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
     * Escribe config.yml en <vitaFs>/config.yml y devuelve el archivo.
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
    fun writeConfig(context: Context, settings: LauncherSettings, vitaRoot: File, gpu: GpuInfo? = null): File {
        val vitaFs = vitaFsPathFor(vitaRoot)
        val content = generate(settings, vitaFs.absolutePath, gpu)
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
