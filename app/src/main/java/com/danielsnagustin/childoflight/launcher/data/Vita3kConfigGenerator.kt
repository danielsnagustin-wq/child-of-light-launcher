package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import java.io.File

/**
 * Generador de config.yml de Vita3K a partir de los ajustes del launcher.
 *
 * VERIFICADO contra el código fuente de Vita3K:
 * vita3k/config/include/config/config.h (master, leído 2026-11-07).
 * Cada clave emitida abajo existe en CONFIG_INDIVIDUAL con ese nombre
 * exacto. Las claves con "TODO-VERIFY" NO se emiten como reales:
 * se escriben como comentario, porque no aparecen en config.h y
 * emitirlas sería inventar que Vita3K las lee.
 *
 * Claves verificadas usadas:
 * - backend-renderer ("Vulkan"), resolution-multiplier (float 1.0/2.0/3.0/4.0),
 *   screen-filter ("Bilinear"), v-sync (bool), anisotropic-filtering (int),
 *   async-pipeline-compilation (bool), high-accuracy (bool),
 *   disable-surface-sync (bool, INVERTIDO: true = Surface Sync Disabled),
 *   memory-mapping ("double-buffer" es el default verificado),
 *   texture-cache, shader-cache, audio-backend ("SDL"),
 *   audio-volume (int), ngs-enable (bool), cpu-opt (bool),
 *   fps-hack (bool), file-loading-delay (int),
 *   performance-overlay (bool), custom-driver-name (string),
 *   pref-path (string, ruta del vita_fs).
 *
 * NO verificadas (comentario TODO, no se emiten):
 * - frame limiter (no hay clave en config.h)
 * - frame skipping (no hay clave en config.h)
 * - accurate thread scheduling (no hay clave en config.h; es ajuste
 *   del fork Vita3K-Android / Vita3K+, se guarda en el launcher)
 * - audio buffer size (no hay clave en config.h)
 * - strings exactos de memory-mapping distintos de "double-buffer"
 *   (libretro lista Off/Double Buffer/External Host/Page Table/
 *   Native Buffer; solo "double-buffer" está confirmado como default)
 * - string exacto de Cubeb en audio-backend (Vita3K+ v1.3 lo documenta
 *   como alternativa; se emite "Cubeb" con TODO-VERIFY)
 * - cpu-backend separado (no hay clave: Dynarmic es el backend por
 *   defecto y cpu-opt controla sus optimizaciones)
 */
object Vita3kConfigGenerator {

    fun generate(settings: LauncherSettings, vitaFsPath: String): String {
        val s = effectiveSettings(settings)
        val sb = StringBuilder()
        sb.appendLine("# config.yml generado por Child of Light Launcher (Etapa 1)")
        sb.appendLine("# Perfil activo: ${s.activeProfile.label}")
        if (settings.safeMode) {
            sb.appendLine("# MODO SEGURO activo: 1x, SDL, buffer grande, Thread Scheduling ON, High Accuracy OFF.")
        }
        sb.appendLine("# Fuente de claves: config.h del núcleo (master, 2026-11-07).")
        sb.appendLine("# Las líneas TODO-VERIFY son ajustes del launcher SIN clave verificada en config.h.")
        sb.appendLine("# No se inventan claves: esos ajustes no se escriben como claves reales.")
        sb.appendLine()
        // --- GPU ---
        sb.appendLine("# GPU — backend-renderer verificado (\"Vulkan\" / \"OpenGL\").")
        sb.appendLine("backend-renderer: \"${s.backend.vita3kValue}\"")
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
        sb.appendLine("# GPU — memory-mapping: solo \"double-buffer\" está verificado como default de config.h.")
        if (s.memoryMapping.name == "DOUBLE_BUFFER") {
            sb.appendLine("memory-mapping: \"double-buffer\"")
        } else {
            sb.appendLine("# TODO-VERIFY: string exacto de memory-mapping \"${s.memoryMapping.vita3kValue}\" no confirmado en config.h (libretro lista Double Buffer / Page Table / Native Buffer / External Host / Off).")
            sb.appendLine("memory-mapping: \"${s.memoryMapping.vita3kValue}\"")
        }
        sb.appendLine("texture-cache: ${s.textureCache}")
        sb.appendLine("shader-cache: ${s.shaderCache}")
        // --- Driver ---
        if (s.turnipInstalled && s.customDriverName.isNotBlank()) {
            sb.appendLine("# Driver personalizado Turnip (solo Adreno). custom-driver-name verificado como clave; el valor esperado exacto (nombre del zip) es TODO-VERIFY contra el build de Android.")
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
        if (s.audioBackend.name == "SDL") {
            sb.appendLine("audio-backend: \"SDL\"")
        } else {
            sb.appendLine("# TODO-VERIFY: string exacto de Cubeb en audio-backend. La v1.3 lo documenta como alternativa a SDL; se emite \"Cubeb\" y debe verificarse contra el build usado.")
            sb.appendLine("audio-backend: \"Cubeb\"")
        }
        sb.appendLine("audio-volume: ${s.audioVolume}")
        sb.appendLine("ngs-enable: ${s.ngsEnable}")
        sb.appendLine("# TODO-VERIFY: audio-buffer-size (${s.audioBufferSize}) es ajuste del launcher; no hay clave de buffer en config.h, así que NO se emite como clave real.")
        // --- Overlay ---
        sb.appendLine("performance-overlay: ${s.showFpsOverlay}")
        // --- Ajustes solo-launcher ---
        sb.appendLine("# TODO-VERIFY: frame-limiter (${s.frameLimiter.label}) no tiene clave en config.h; lo aplica el launcher al lanzar (límite objetivo), no el núcleo vía YAML.")
        sb.appendLine("# TODO-VERIFY: frame-skipping (${s.frameSkipping}) no tiene clave en config.h; ajuste del launcher.")
        sb.appendLine("# TODO-VERIFY: accurate-thread-scheduling (${s.accurateThreadScheduling}) no tiene clave en config.h (es ajuste del fork Android); se guarda en el launcher y se aplica si el build lo soporta.")
        // --- Rutas ---
        sb.appendLine("pref-path: \"$vitaFsPath\"")
        return sb.toString()
    }

    private fun formatFloat(value: Float): String {
        return if (value == value.toInt().toFloat()) "${value.toInt()}.0" else value.toString()
    }

    /** Escribe config.yml en <vitaFs>/config.yml y devuelve el archivo. */
    fun writeConfig(context: Context, settings: LauncherSettings, vitaRoot: File): File {
        val content = generate(settings, vitaRoot.absolutePath)
        if (!vitaRoot.exists()) vitaRoot.mkdirs()
        val file = File(vitaRoot, "config.yml")
        file.writeText(content)
        LauncherLog.append(context, "config", "config.yml escrito (${content.length} bytes, perfil ${settings.activeProfile.name})")
        return file
    }
}
