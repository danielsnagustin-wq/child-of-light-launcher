package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.danielsnagustin.childoflight.launcher.model.BenchmarkRun
import com.danielsnagustin.childoflight.launcher.model.BenchmarkSource
import com.danielsnagustin.childoflight.launcher.model.FrameLimiter
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "col_launcher_settings")

/**
 * Persistencia de ajustes + GPU detectada + historial de pruebas, en DataStore.
 * Cada cambio de ajuste se guarda al momento (la UI llama a save()).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val BACKEND = stringPreferencesKey("backend")
        val RESOLUTION = floatPreferencesKey("resolution_multiplier")
        val SCREEN_FILTER = stringPreferencesKey("screen_filter")
        val ANISO = intPreferencesKey("anisotropic_filtering")
        val VSYNC = booleanPreferencesKey("vsync")
        val ASYNC = booleanPreferencesKey("async_pipeline")
        val HIGH_ACCURACY = booleanPreferencesKey("high_accuracy")
        val SURFACE_SYNC = booleanPreferencesKey("surface_sync_enabled")
        val MEMORY_MAPPING = stringPreferencesKey("memory_mapping")
        val TEXTURE_CACHE = booleanPreferencesKey("texture_cache")
        val SHADER_CACHE = booleanPreferencesKey("shader_cache")
        val CPU_OPT = booleanPreferencesKey("cpu_opt")
        val FPS_HACK = booleanPreferencesKey("fps_hack")
        val FILE_LOADING_DELAY = intPreferencesKey("file_loading_delay")
        val AUDIO_VOLUME = intPreferencesKey("audio_volume")
        val AUDIO_BACKEND = stringPreferencesKey("audio_backend")
        val AUDIO_BUFFER = intPreferencesKey("audio_buffer_size")
        val NGS_ENABLE = booleanPreferencesKey("ngs_enable")
        val FRAME_LIMITER = stringPreferencesKey("frame_limiter")
        val FRAME_SKIPPING = intPreferencesKey("frame_skipping")
        val ACCURATE_THREAD = booleanPreferencesKey("accurate_thread_scheduling")
        val SHOW_FPS = booleanPreferencesKey("show_fps_overlay")
        val GPU_DRIVER = stringPreferencesKey("gpu_driver")
        val CUSTOM_DRIVER_NAME = stringPreferencesKey("custom_driver_name")
        val TURNIP_VERSION = stringPreferencesKey("turnip_version")
        val TURNIP_INSTALLED = booleanPreferencesKey("turnip_installed")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val AUTO_REVERT = booleanPreferencesKey("auto_revert_1x")
        val PERFORMANCE_MODE = booleanPreferencesKey("performance_mode")
        val BATTERY_OPT_DISABLED = booleanPreferencesKey("battery_opt_disabled")
        val SAFE_MODE = booleanPreferencesKey("safe_mode")
        val CLOSE_BACKGROUND = booleanPreferencesKey("close_background_apps")
        val HIDE_TOUCH_GAMEPAD = booleanPreferencesKey("hide_touch_when_gamepad")
        // GPU detectada
        val GPU_DETECTED = booleanPreferencesKey("gpu_detected")
        val GPU_VENDOR = stringPreferencesKey("gpu_vendor")
        val GPU_RENDERER = stringPreferencesKey("gpu_renderer")
        val GPU_VENDOR_STR = stringPreferencesKey("gpu_vendor_str")
        val GPU_GLES = stringPreferencesKey("gpu_gles")
        val GPU_SOC = stringPreferencesKey("gpu_soc")
        val GPU_DEVICE = stringPreferencesKey("gpu_device")
        val GPU_VULKAN = stringPreferencesKey("gpu_vulkan")
        val GPU_VULKAN_SUPPORTED = booleanPreferencesKey("gpu_vulkan_supported")
        val GPU_DETECTED_AT = longPreferencesKey("gpu_detected_at")
        // Historial de pruebas: hasta 5, codificadas como "profile|gpu|started|fps|source|reverted|note"
        val BENCHMARKS = stringPreferencesKey("benchmarks")
    }

    val settingsFlow: Flow<LauncherSettings> = context.settingsDataStore.data.map { p ->
        LauncherSettings(
            backend = runCatching { GpuBackend.valueOf(p[Keys.BACKEND] ?: "VULKAN") }.getOrDefault(GpuBackend.VULKAN),
            resolutionMultiplier = p[Keys.RESOLUTION] ?: 1f,
            screenFilter = runCatching { ScreenFilter.valueOf(p[Keys.SCREEN_FILTER] ?: "BILINEAR") }.getOrDefault(ScreenFilter.BILINEAR),
            anisotropicFiltering = p[Keys.ANISO] ?: 1,
            vSync = p[Keys.VSYNC] ?: false,
            asyncPipelineCompilation = p[Keys.ASYNC] ?: true,
            highAccuracy = p[Keys.HIGH_ACCURACY] ?: false,
            surfaceSyncEnabled = p[Keys.SURFACE_SYNC] ?: false,
            memoryMapping = runCatching { MemoryMapping.valueOf(p[Keys.MEMORY_MAPPING] ?: "DOUBLE_BUFFER") }.getOrDefault(MemoryMapping.DOUBLE_BUFFER),
            textureCache = p[Keys.TEXTURE_CACHE] ?: true,
            shaderCache = p[Keys.SHADER_CACHE] ?: true,
            cpuOpt = p[Keys.CPU_OPT] ?: true,
            fpsHack = p[Keys.FPS_HACK] ?: false,
            fileLoadingDelay = p[Keys.FILE_LOADING_DELAY] ?: 0,
            audioVolume = (p[Keys.AUDIO_VOLUME] ?: 100).coerceIn(0, 100),
            audioBackend = runCatching { com.danielsnagustin.childoflight.launcher.model.AudioBackend.valueOf(p[Keys.AUDIO_BACKEND] ?: "SDL") }.getOrDefault(com.danielsnagustin.childoflight.launcher.model.AudioBackend.SDL),
            ngsEnable = p[Keys.NGS_ENABLE] ?: true,
            audioBufferSize = (p[Keys.AUDIO_BUFFER] ?: 512).coerceIn(256, 2048),
            frameLimiter = runCatching { FrameLimiter.valueOf(p[Keys.FRAME_LIMITER] ?: "FPS_30") }.getOrDefault(FrameLimiter.FPS_30),
            frameSkipping = (p[Keys.FRAME_SKIPPING] ?: 0).coerceIn(0, 2),
            accurateThreadScheduling = p[Keys.ACCURATE_THREAD] ?: true,
            showFpsOverlay = p[Keys.SHOW_FPS] ?: false,
            gpuDriver = runCatching { GpuDriver.valueOf(p[Keys.GPU_DRIVER] ?: "SYSTEM") }.getOrDefault(GpuDriver.SYSTEM),
            customDriverName = p[Keys.CUSTOM_DRIVER_NAME] ?: "",
            turnipVersion = p[Keys.TURNIP_VERSION] ?: "",
            turnipInstalled = p[Keys.TURNIP_INSTALLED] ?: false,
            activeProfile = runCatching { GpuProfileId.valueOf(p[Keys.ACTIVE_PROFILE] ?: "G99_RECOMMENDED") }.getOrDefault(GpuProfileId.G99_RECOMMENDED),
            autoRevertTo1xIfBelow28Fps = p[Keys.AUTO_REVERT] ?: true,
            performanceMode = p[Keys.PERFORMANCE_MODE] ?: true,
            batteryOptimizationDisabled = p[Keys.BATTERY_OPT_DISABLED] ?: false,
            safeMode = p[Keys.SAFE_MODE] ?: false,
            closeBackgroundApps = p[Keys.CLOSE_BACKGROUND] ?: true,
            hideTouchWhenGamepadConnected = p[Keys.HIDE_TOUCH_GAMEPAD] ?: true,
        )
    }

    val gpuInfoFlow: Flow<GpuInfo?> = context.settingsDataStore.data.map { p ->
        if (p[Keys.GPU_DETECTED] != true) null else GpuInfo(
            vendor = runCatching { GpuVendor.valueOf(p[Keys.GPU_VENDOR] ?: "UNKNOWN") }.getOrDefault(GpuVendor.UNKNOWN),
            renderer = p[Keys.GPU_RENDERER] ?: "No detectado",
            vendorString = p[Keys.GPU_VENDOR_STR] ?: "",
            glesVersion = p[Keys.GPU_GLES] ?: "",
            socModel = p[Keys.GPU_SOC] ?: "",
            deviceModel = p[Keys.GPU_DEVICE] ?: "",
            vulkanVersionLabel = p[Keys.GPU_VULKAN] ?: "Desconocido",
            vulkanSupported = p[Keys.GPU_VULKAN_SUPPORTED] ?: false,
            detectedAtMillis = p[Keys.GPU_DETECTED_AT] ?: 0L,
        )
    }

    val benchmarksFlow: Flow<List<BenchmarkRun>> = context.settingsDataStore.data.map { p ->
        decodeBenchmarks(p[Keys.BENCHMARKS] ?: "")
    }

    /** Guarda el estado completo. La UI siempre pasa el estado actual modificado. */
    suspend fun save(settings: LauncherSettings) {
        context.settingsDataStore.edit { p ->
            p[Keys.BACKEND] = settings.backend.name
            p[Keys.RESOLUTION] = settings.resolutionMultiplier
            p[Keys.SCREEN_FILTER] = settings.screenFilter.name
            p[Keys.ANISO] = settings.anisotropicFiltering
            p[Keys.VSYNC] = settings.vSync
            p[Keys.ASYNC] = settings.asyncPipelineCompilation
            p[Keys.HIGH_ACCURACY] = settings.highAccuracy
            p[Keys.SURFACE_SYNC] = settings.surfaceSyncEnabled
            p[Keys.MEMORY_MAPPING] = settings.memoryMapping.name
            p[Keys.TEXTURE_CACHE] = settings.textureCache
            p[Keys.SHADER_CACHE] = settings.shaderCache
            p[Keys.CPU_OPT] = settings.cpuOpt
            p[Keys.FPS_HACK] = settings.fpsHack
            p[Keys.FILE_LOADING_DELAY] = settings.fileLoadingDelay
            p[Keys.AUDIO_VOLUME] = settings.audioVolume
            p[Keys.AUDIO_BACKEND] = settings.audioBackend.name
            p[Keys.AUDIO_BUFFER] = settings.audioBufferSize
            p[Keys.NGS_ENABLE] = settings.ngsEnable
            p[Keys.FRAME_LIMITER] = settings.frameLimiter.name
            p[Keys.FRAME_SKIPPING] = settings.frameSkipping
            p[Keys.ACCURATE_THREAD] = settings.accurateThreadScheduling
            p[Keys.SHOW_FPS] = settings.showFpsOverlay
            p[Keys.GPU_DRIVER] = settings.gpuDriver.name
            p[Keys.CUSTOM_DRIVER_NAME] = settings.customDriverName
            p[Keys.TURNIP_VERSION] = settings.turnipVersion
            p[Keys.TURNIP_INSTALLED] = settings.turnipInstalled
            p[Keys.ACTIVE_PROFILE] = settings.activeProfile.name
            p[Keys.AUTO_REVERT] = settings.autoRevertTo1xIfBelow28Fps
            p[Keys.PERFORMANCE_MODE] = settings.performanceMode
            p[Keys.BATTERY_OPT_DISABLED] = settings.batteryOptimizationDisabled
            p[Keys.SAFE_MODE] = settings.safeMode
            p[Keys.CLOSE_BACKGROUND] = settings.closeBackgroundApps
            p[Keys.HIDE_TOUCH_GAMEPAD] = settings.hideTouchWhenGamepadConnected
        }
    }

    /** Aplica un preset completo (sobrescribe los ajustes actuales). */
    suspend fun applyPreset(profile: GpuProfileId, keepDriver: Boolean = true, current: LauncherSettings? = null) {
        val base = presetSettings(profile)
        // El driver sí forma parte del preset: Turnip solo tiene sentido en
        // ADRENO_QUALITY_TURNIP; en Mali/G99 el driver siempre es Sistema.
        // La info de Turnip instalado (nombre/versión) se conserva siempre.
        val driver = when (profile) {
            GpuProfileId.ADRENO_QUALITY_TURNIP -> com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP
            GpuProfileId.ADRENO_PERFORMANCE -> if (keepDriver) {
                current?.gpuDriver ?: com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM
            } else {
                com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM
            }
            else -> com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM
        }
        val merged = base.copy(
            gpuDriver = driver,
            customDriverName = current?.customDriverName ?: "",
            turnipVersion = current?.turnipVersion ?: "",
            turnipInstalled = current?.turnipInstalled ?: false,
        )
        save(merged)
    }

    suspend fun resetToDefaults(gpu: GpuInfo?) {
        val profile = if (gpu != null) defaultProfileFor(gpu) else GpuProfileId.G99_RECOMMENDED
        save(presetSettings(profile))
    }

    suspend fun saveGpuInfo(gpu: GpuInfo) {
        context.settingsDataStore.edit { p ->
            p[Keys.GPU_DETECTED] = true
            p[Keys.GPU_VENDOR] = gpu.vendor.name
            p[Keys.GPU_RENDERER] = gpu.renderer
            p[Keys.GPU_VENDOR_STR] = gpu.vendorString
            p[Keys.GPU_GLES] = gpu.glesVersion
            p[Keys.GPU_SOC] = gpu.socModel
            p[Keys.GPU_DEVICE] = gpu.deviceModel
            p[Keys.GPU_VULKAN] = gpu.vulkanVersionLabel
            p[Keys.GPU_VULKAN_SUPPORTED] = gpu.vulkanSupported
            p[Keys.GPU_DETECTED_AT] = gpu.detectedAtMillis
        }
    }

    suspend fun recordBenchmark(run: BenchmarkRun) {
        context.settingsDataStore.edit { p ->
            val existing = decodeBenchmarks(p[Keys.BENCHMARKS] ?: "")
            val updated = (listOf(run) + existing).take(5)
            p[Keys.BENCHMARKS] = updated.joinToString(";;") { encodeBenchmark(it) }
        }
    }

    companion object {
        fun encodeBenchmark(run: BenchmarkRun): String = listOf(
            run.profile.name,
            run.gpuLabel.replace("|", "/"),
            run.startedAtMillis.toString(),
            run.durationSeconds.toString(),
            run.fpsAvg?.toString() ?: "",
            run.source.name,
            run.autoReverted.toString(),
            run.note.replace("|", "/").replace(";;", " "),
        ).joinToString("|")

        fun decodeBenchmarks(raw: String): List<BenchmarkRun> {
            if (raw.isBlank()) return emptyList()
            return raw.split(";;").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size < 8) return@mapNotNull null
                runCatching {
                    BenchmarkRun(
                        profile = GpuProfileId.valueOf(parts[0]),
                        gpuLabel = parts[1],
                        startedAtMillis = parts[2].toLong(),
                        durationSeconds = parts[3].toInt(),
                        fpsAvg = parts[4].toFloatOrNull(),
                        source = BenchmarkSource.valueOf(parts[5]),
                        autoReverted = parts[6].toBoolean(),
                        note = parts[7],
                    )
                }.getOrNull()
            }
        }
    }
}
