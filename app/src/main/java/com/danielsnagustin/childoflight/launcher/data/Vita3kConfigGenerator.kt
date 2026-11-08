package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import java.io.File

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
 * CONFIG_INDIVIDUAL con ese nombre exacto. Las claves con
 * "TODO-VERIFY" NO se emiten como reales: se escriben como
 * comentario, porque no aparecen en config.h y emitirlas sería
 * inventar que Vita3K las lee.
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
 *   performance-overlay (bool),
 *   custom-driver-name (string: nombre del driver instalado = stem
 *   del zip, sin .zip; el driver debe estar instalado en el núcleo
 *   con installCustomDriver, ver driver_bridge.cpp),
 *   pref-path (string, ruta del vita_fs).
 *
 * NO verificadas (comentario TODO, no se emiten):
 * - frame limiter (no hay clave en config.h ni en config.cpp)
 * - frame skipping (no hay clave en config.h ni en config.cpp)
 * - accurate thread scheduling (no hay clave en config.h ni en
 *   config.cpp; es ajuste del fork Vita3K-Android / Vita3K+, se
 *   guarda en el launcher)
 * - audio buffer size (no hay clave de buffer en config.h ni en
 *   config.cpp)
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
        sb.appendLine("# config.yml generado por Child of Light Launcher (Etapa 1)")
        sb.appendLine("# Perfil activo: ${s.activeProfile.label}")
        if (settings.safeMode) {
            sb.appendLine("# MODO SEGURO activo: 1x, SDL, buffer grande, Thread Scheduling ON, High Accuracy OFF.")
        }
        sb.appendLine("# Fuente de claves: config.h del núcleo (pin 3e43bc80, 2026-11-07).")
        sb.appendLine("# Las líneas TODO-VERIFY son ajustes del launcher SIN clave verificada en config.h.")
        sb.appendLine("# No se inventan claves: esos ajustes no se escriben como claves reales.")
        sb.appendLine()
        // --- GPU ---
        sb.appendLine("# GPU — backend-renderer verificado (\"Vulkan\" / \"OpenGL\").")
        if (backend == GpuBackend.OPENGL && s.backend == GpuBackend.VULKAN) {
            sb.appendLine("# Sin Vulkan en este teléfono: fallback automático a OpenGL (el ajuste pedía Vulkan).")
        }
        sb.appendLine("backend-renderer: \"${backend.vita3kValue}\"")
        sb.appendLine("# GPU — resolution-multiplier es float en config.h: 1.0 = 960x544, 2.0 = 1920x1088, 3.0 = 2880x1632, 4.0 = 3840x2176.")
        sb.appendLine("resolution-multiplier: ${formatFloat(s.resolutionMultiplier)}")
        sb.appendLine("# GPU — screen-filter verificado (default \"Bilinear\").")
        sb.appendLine("screen-filter: \"${s.screenFilter.vita3kValue}\"")
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
        sb.appendLine("memory-mapping: \"${s.memoryMapping.vita3kValue}\"")
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
            sb.appendLine("custom-driver-name: \"${s.customDriverName}\"")
        } else {
            sb.appendLine("custom-driver-name: \"\"")
        }
        // --- CPU ---
        sb.appendLine("# CPU — cpu-opt verificado. No hay clave cpu-backend en config.h: Dynarmic es el backend por defecto.")
        sb.appendLine("cpu-opt: ${s.cpuOpt}")
        sb.appendLine("fps-hack: ${s.fpsHack}")
        sb.appendLine("file-loading-delay: ${s.fileLoadingDelay}")
        // --- Audio ---
        // audio-backend VERIFICADO (audio.cpp, AudioState::set_backend):
        // el núcleo acepta exactamente "SDL" y "Cubeb" (case-sensitive);
        // cualquier otro valor deja el adapter a null y el audio no arranca.
        sb.appendLine("# Audio — audio-backend verificado (audio.cpp set_backend): \"SDL\" o \"Cubeb\".")
        sb.appendLine("audio-backend: \"${s.audioBackend.vita3kValue}\"")
        sb.appendLine("audio-volume: ${s.audioVolume}")
        sb.appendLine("ngs-enable: ${s.ngsEnable}")
        sb.appendLine("# TODO-VERIFY: audio-buffer-size (${s.audioBufferSize}) es ajuste del launcher; no hay clave de buffer en config.h ni en config.cpp (pin 3e43bc80), así que NO se emite como clave real.")
        // --- Overlay ---
        sb.appendLine("performance-overlay: ${s.showFpsOverlay}")
        // --- Ajustes solo-launcher ---
        sb.appendLine("# TODO-VERIFY: frame-limiter (${s.frameLimiter.label}) no tiene clave en config.h ni en config.cpp (pin 3e43bc80); lo aplica el launcher al lanzar (límite objetivo), no el núcleo vía YAML.")
        sb.appendLine("# TODO-VERIFY: frame-skipping (${s.frameSkipping}) no tiene clave en config.h ni en config.cpp (pin 3e43bc80); ajuste del launcher.")
        sb.appendLine("# TODO-VERIFY: accurate-thread-scheduling (${s.accurateThreadScheduling}) no tiene clave en config.h ni en config.cpp (pin 3e43bc80; es ajuste del fork Android); se guarda en el launcher y se aplica si el build lo soporta.")
        // --- Rutas ---
        sb.appendLine("pref-path: \"$vitaFsPath\"")
        return sb.toString()
    }

    private fun formatFloat(value: Float): String {
        return if (value == value.toInt().toFloat()) "${value.toInt()}.0" else value.toString()
    }

    /** Escribe config.yml en <vitaFs>/config.yml y devuelve el archivo. */
    fun writeConfig(context: Context, settings: LauncherSettings, vitaRoot: File, gpu: GpuInfo? = null): File {
        val content = generate(settings, vitaRoot.absolutePath, gpu)
        if (!vitaRoot.exists()) vitaRoot.mkdirs()
        val file = File(vitaRoot, "config.yml")
        file.writeText(content)
        LauncherLog.append(context, "config", "config.yml escrito (${content.length} bytes, perfil ${settings.activeProfile.name})")
        return file
    }
}
