package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.danielsnagustin.childoflight.launcher.model.BenchmarkRun
import com.danielsnagustin.childoflight.launcher.model.BenchmarkSource
import com.danielsnagustin.childoflight.launcher.model.GameLanguage
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayDetail
import com.danielsnagustin.childoflight.launcher.model.PerformanceOverlayPosition
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter
import com.danielsnagustin.childoflight.launcher.model.applyLowRamDefaults
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.preserveManualCacheChoices
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Crash al abrir (run #80): mismo arreglo que UiPreferencesRepository
// (ver su comentario): archivo preferences_pb corrupto -> el
// corruptionHandler lo reemplaza por vacío y el catch cubre otros
// fallos de lectura con defaults.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "col_launcher_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
        // Ronda 0.2.68 (load-p1, hallazgo Codex paso 1): la carga
        // rápida necesita su clave DataStore como shaderCache: sin
        // ella el interruptor se perdía al recargar ajustes (el campo
        // caía a su default false y no llegaba a config.yml).
        val SKIP_SHADER_PRECOMPILE = booleanPreferencesKey("skip_shader_precompile")
        // Tercera pasada (N-1): la máscara necesita su clave DataStore
        // como skipShaderPrecompile: sin ella el interruptor se perdía
        // al recargar ajustes.
        val DISABLE_MASK_BIT = booleanPreferencesKey("disable_mask_bit")
        val CPU_OPT = booleanPreferencesKey("cpu_opt")
        val FPS_HACK = booleanPreferencesKey("fps_hack")
        val TURBO_MODE = booleanPreferencesKey("turbo_mode")
        val FILE_LOADING_DELAY = intPreferencesKey("file_loading_delay")
        val AUDIO_VOLUME = intPreferencesKey("audio_volume")
        val AUDIO_BACKEND = stringPreferencesKey("audio_backend")
        val NGS_ENABLE = booleanPreferencesKey("ngs_enable")
        // Ronda 0.2.66: interruptor experimental Audio SDL (R1).
        val EXP_AUDIO_SDL = booleanPreferencesKey("exp_audio_sdl")
        val SHOW_FPS = booleanPreferencesKey("show_fps_overlay")
        // Ronda 0.2.66 (Fase L): perfil de frames (diagnóstico de
        // lag). Mismo patrón que SHOW_FPS; OFF por defecto.
        val FRAME_PROFILE = booleanPreferencesKey("frame_profile")
        val OVERLAY_DETAIL = stringPreferencesKey("performance_overlay_detail")
        val OVERLAY_POSITION = stringPreferencesKey("performance_overlay_position")
        val HASHLESS_TEXTURE_CACHE = booleanPreferencesKey("hashless_texture_cache")
        val SHOW_COMPILE_SHADERS = booleanPreferencesKey("show_compile_shaders")
        val GPU_DRIVER = stringPreferencesKey("gpu_driver")
        val CUSTOM_DRIVER_NAME = stringPreferencesKey("custom_driver_name")
        val TURNIP_VERSION = stringPreferencesKey("turnip_version")
        val TURNIP_INSTALLED = booleanPreferencesKey("turnip_installed")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val AUTO_REVERT = booleanPreferencesKey("auto_revert_1x")
        val PERFORMANCE_MODE = booleanPreferencesKey("performance_mode")
        val SAFE_MODE = booleanPreferencesKey("safe_mode")
        val CLOSE_BACKGROUND = booleanPreferencesKey("close_background_apps")
        val ALLOW_EXTERNAL_VITA3K = booleanPreferencesKey("allow_external_vita3k")
        val PSN_SIGNED_IN = booleanPreferencesKey("psn_signed_in")
        // 0.2.63 (E): idioma del juego (sys-lang). Se guarda el
        // nombre del enum; el valor int va en GameLanguage.value.
        val GAME_LANGUAGE = stringPreferencesKey("game_language")
        // clave opcional de RAWG.io, vacía por defecto
        val RAWG_API_KEY = stringPreferencesKey("rawg_api_key")
        val PKG_DELIVERED = booleanPreferencesKey("pkg_delivered_to_vita3k")
        val PKG_INSTALLED_IN_VITA3K = booleanPreferencesKey("pkg_installed_in_vita3k")
        val HIDE_TOUCH_GAMEPAD = booleanPreferencesKey("hide_touch_when_gamepad")
        val OVERLAY_SHOW_IN_GAME = booleanPreferencesKey("show_touch_overlay_in_game")
        val OVERLAY_OPACITY = floatPreferencesKey("touch_overlay_opacity")
        val OVERLAY_NEON = booleanPreferencesKey("touch_overlay_neon")
        val OVERLAY_HIDDEN_PROFILES = stringPreferencesKey("touch_overlay_hidden_profiles")
        // M34: true cuando ya se guardó alguna configuración (preset o ajuste).
        val PRESET_APPLIED = booleanPreferencesKey("preset_applied")
        // GPU detectada
        val GPU_DETECTED = booleanPreferencesKey("gpu_detected")
        val GPU_VENDOR = stringPreferencesKey("gpu_vendor")
        val GPU_RENDERER = stringPreferencesKey("gpu_renderer")
        val GPU_VENDOR_STR = stringPreferencesKey("gpu_vendor_str")
        val GPU_GLES = stringPreferencesKey("gpu_gles")
        val GPU_SOC = stringPreferencesKey("gpu_soc")
        val GPU_VULKAN = stringPreferencesKey("gpu_vulkan")
        val GPU_VULKAN_SUPPORTED = booleanPreferencesKey("gpu_vulkan_supported")
        val GPU_DETECTED_AT = longPreferencesKey("gpu_detected_at")
        // Ronda 0.2.64 (P2-2 Mali): anisotrópico máximo leído por GLES.
        val GPU_MAX_ANISO = intPreferencesKey("gpu_max_aniso")
        // Historial de pruebas: hasta 5, codificadas como "profile|gpu|started|fps|source|reverted|note"
        val BENCHMARKS = stringPreferencesKey("benchmarks")
        // FPS medio medido automáticamente por el núcleo durante una
        // prueba de 30 s (EmbeddedGameActivity muestrea getFps cada
        // segundo y guarda la media al salir del juego). Se consume
        // una sola vez al volver al launcher; si caduca (> 10 min) o
        // no es válido, la prueba queda para registro manual.
        val BENCHMARK_AUTO_FPS = floatPreferencesKey("benchmark_auto_fps")
        val BENCHMARK_AUTO_FPS_AT = longPreferencesKey("benchmark_auto_fps_at")
    }

    val settingsFlow: Flow<LauncherSettings> = context.settingsDataStore.data.map { p ->
        LauncherSettings(
            // M22: los valores leídos se clampan: un DataStore editado a mano
            // (o corrupto) no debe pasar 0 / -1 / 99 / NaN al config.yml.
            backend = runCatching { GpuBackend.valueOf(p[Keys.BACKEND] ?: "VULKAN") }.getOrDefault(GpuBackend.VULKAN),
            resolutionMultiplier = clampResolution(p[Keys.RESOLUTION] ?: 1f),
            screenFilter = runCatching { ScreenFilter.valueOf(p[Keys.SCREEN_FILTER] ?: "BILINEAR") }.getOrDefault(ScreenFilter.BILINEAR),
            anisotropicFiltering = clampAniso(p[Keys.ANISO] ?: 1),
            vSync = p[Keys.VSYNC] ?: false,
            asyncPipelineCompilation = p[Keys.ASYNC] ?: true,
            highAccuracy = p[Keys.HIGH_ACCURACY] ?: false,
            surfaceSyncEnabled = p[Keys.SURFACE_SYNC] ?: false,
            memoryMapping = runCatching { MemoryMapping.valueOf(p[Keys.MEMORY_MAPPING] ?: "PAGE_TABLE") }.getOrDefault(MemoryMapping.PAGE_TABLE),
            textureCache = p[Keys.TEXTURE_CACHE] ?: true,
            shaderCache = p[Keys.SHADER_CACHE] ?: true,
            skipShaderPrecompile = p[Keys.SKIP_SHADER_PRECOMPILE] ?: false,
            disableMaskBit = p[Keys.DISABLE_MASK_BIT] ?: false,
            cpuOpt = p[Keys.CPU_OPT] ?: true,
            fpsHack = p[Keys.FPS_HACK] ?: false,
            turboMode = p[Keys.TURBO_MODE] ?: false,
            fileLoadingDelay = clampFileLoadingDelay(p[Keys.FILE_LOADING_DELAY] ?: 0),
            audioVolume = (p[Keys.AUDIO_VOLUME] ?: 100).coerceIn(0, 100),
            audioBackend = runCatching { com.danielsnagustin.childoflight.launcher.model.AudioBackend.valueOf(p[Keys.AUDIO_BACKEND] ?: "SDL") }.getOrDefault(com.danielsnagustin.childoflight.launcher.model.AudioBackend.SDL),
            ngsEnable = p[Keys.NGS_ENABLE] ?: true,
            expAudioSdl = p[Keys.EXP_AUDIO_SDL] ?: false,
            showFpsOverlay = p[Keys.SHOW_FPS] ?: false,
            frameProfile = p[Keys.FRAME_PROFILE] ?: false,
            performanceOverlayDetail = runCatching { PerformanceOverlayDetail.valueOf(p[Keys.OVERLAY_DETAIL] ?: "MINIMUM") }.getOrDefault(PerformanceOverlayDetail.MINIMUM),
            performanceOverlayPosition = runCatching { PerformanceOverlayPosition.valueOf(p[Keys.OVERLAY_POSITION] ?: "TOP_LEFT") }.getOrDefault(PerformanceOverlayPosition.TOP_LEFT),
            hashlessTextureCache = p[Keys.HASHLESS_TEXTURE_CACHE] ?: false,
            showCompileShaders = p[Keys.SHOW_COMPILE_SHADERS] ?: false,
            gpuDriver = runCatching { GpuDriver.valueOf(p[Keys.GPU_DRIVER] ?: "SYSTEM") }.getOrDefault(GpuDriver.SYSTEM),
            customDriverName = p[Keys.CUSTOM_DRIVER_NAME] ?: "",
            turnipVersion = p[Keys.TURNIP_VERSION] ?: "",
            turnipInstalled = p[Keys.TURNIP_INSTALLED] ?: false,
            activeProfile = runCatching { GpuProfileId.valueOf(p[Keys.ACTIVE_PROFILE] ?: "G99_RECOMMENDED") }.getOrDefault(GpuProfileId.G99_RECOMMENDED),
            autoRevertTo1xIfBelow28Fps = p[Keys.AUTO_REVERT] ?: true,
            performanceMode = p[Keys.PERFORMANCE_MODE] ?: true,
            safeMode = p[Keys.SAFE_MODE] ?: false,
            closeBackgroundApps = p[Keys.CLOSE_BACKGROUND] ?: true,
            allowExternalVita3k = p[Keys.ALLOW_EXTERNAL_VITA3K] ?: false,
            psnSignedIn = p[Keys.PSN_SIGNED_IN] ?: true,
            gameLanguage = runCatching { GameLanguage.valueOf(p[Keys.GAME_LANGUAGE] ?: "SPANISH") }.getOrDefault(GameLanguage.SPANISH),
            rawgApiKey = p[Keys.RAWG_API_KEY] ?: "",
            pkgDeliveredToVita3k = p[Keys.PKG_DELIVERED] ?: false,
            pkgInstalledInVita3k = p[Keys.PKG_INSTALLED_IN_VITA3K] ?: false,
            hideTouchWhenGamepadConnected = p[Keys.HIDE_TOUCH_GAMEPAD] ?: true,
            showTouchOverlayInGame = p[Keys.OVERLAY_SHOW_IN_GAME] ?: true,
            touchOverlayOpacity = clampOverlayOpacity(p[Keys.OVERLAY_OPACITY] ?: 1f),
            touchOverlayNeonGlow = p[Keys.OVERLAY_NEON] ?: true,
            touchOverlayHiddenProfileIds = decodeHiddenProfiles(p[Keys.OVERLAY_HIDDEN_PROFILES] ?: ""),
        )
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore settings ilegible, usando defaults: ${e.message}")
        emit(LauncherSettings())
    }

    val gpuInfoFlow: Flow<GpuInfo?> = context.settingsDataStore.data.map { p ->
        if (p[Keys.GPU_DETECTED] != true) null else GpuInfo(
            vendor = runCatching { GpuVendor.valueOf(p[Keys.GPU_VENDOR] ?: "UNKNOWN") }.getOrDefault(GpuVendor.UNKNOWN),
            renderer = p[Keys.GPU_RENDERER] ?: "No detectado",
            vendorString = p[Keys.GPU_VENDOR_STR] ?: "",
            glesVersion = p[Keys.GPU_GLES] ?: "",
            socModel = p[Keys.GPU_SOC] ?: "",
            vulkanVersionLabel = p[Keys.GPU_VULKAN] ?: "Desconocido",
            vulkanSupported = p[Keys.GPU_VULKAN_SUPPORTED] ?: false,
            maxAnisotropy = p[Keys.GPU_MAX_ANISO],
            detectedAtMillis = p[Keys.GPU_DETECTED_AT] ?: 0L,
        )
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore settings (gpu) ilegible, usando null: ${e.message}")
        emit(null)
    }

    val benchmarksFlow: Flow<List<BenchmarkRun>> = context.settingsDataStore.data.map { p ->
        decodeBenchmarks(p[Keys.BENCHMARKS] ?: "")
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore settings (benchmarks) ilegible, usando vacío: ${e.message}")
        emit(emptyList())
    }

    /**
     * P1-18: un fallo de E/S al escribir (disco lleno, archivo corrupto)
     * no puede tumbar la app: viewModelScope tiene SupervisorJob y una
     * excepción no capturada en un hijo mata el proceso. Toda escritura
     * DataStore pasa por aquí: registra el fallo y devuelve false para
     * que el llamador pueda avisar. La lectura ya tiene su .catch en el
     * flow (usa defaults); esto cubre la escritura.
     */
    private suspend fun safeEdit(tag: String, transform: suspend (MutablePreferences) -> Unit): Boolean {
        return try {
            context.settingsDataStore.edit(transform)
            true
        } catch (e: Exception) {
            Log.w("ChildOfLight", "DataStore $tag no se pudo escribir: ${e.message}")
            false
        }
    }

    /** Guarda el estado completo. La UI siempre pasa el estado actual modificado. */
    suspend fun save(settings: LauncherSettings): Boolean {
        return safeEdit("ajustes") { p ->
            p[Keys.BACKEND] = settings.backend.name
            // M22: mismo clamp que en lectura, antes de escribir.
            p[Keys.RESOLUTION] = clampResolution(settings.resolutionMultiplier)
            p[Keys.SCREEN_FILTER] = settings.screenFilter.name
            p[Keys.ANISO] = clampAniso(settings.anisotropicFiltering)
            p[Keys.VSYNC] = settings.vSync
            p[Keys.ASYNC] = settings.asyncPipelineCompilation
            p[Keys.HIGH_ACCURACY] = settings.highAccuracy
            p[Keys.SURFACE_SYNC] = settings.surfaceSyncEnabled
            p[Keys.MEMORY_MAPPING] = settings.memoryMapping.name
            p[Keys.TEXTURE_CACHE] = settings.textureCache
            p[Keys.SHADER_CACHE] = settings.shaderCache
            p[Keys.DISABLE_MASK_BIT] = settings.disableMaskBit
            p[Keys.SKIP_SHADER_PRECOMPILE] = settings.skipShaderPrecompile
            p[Keys.CPU_OPT] = settings.cpuOpt
            p[Keys.FPS_HACK] = settings.fpsHack
            p[Keys.TURBO_MODE] = settings.turboMode
            p[Keys.FILE_LOADING_DELAY] = clampFileLoadingDelay(settings.fileLoadingDelay)
            p[Keys.AUDIO_VOLUME] = settings.audioVolume.coerceIn(0, 100)
            p[Keys.AUDIO_BACKEND] = settings.audioBackend.name
            p[Keys.NGS_ENABLE] = settings.ngsEnable
            p[Keys.EXP_AUDIO_SDL] = settings.expAudioSdl
            p[Keys.SHOW_FPS] = settings.showFpsOverlay
            p[Keys.FRAME_PROFILE] = settings.frameProfile
            p[Keys.OVERLAY_DETAIL] = settings.performanceOverlayDetail.name
            p[Keys.OVERLAY_POSITION] = settings.performanceOverlayPosition.name
            p[Keys.HASHLESS_TEXTURE_CACHE] = settings.hashlessTextureCache
            p[Keys.SHOW_COMPILE_SHADERS] = settings.showCompileShaders
            p[Keys.GPU_DRIVER] = settings.gpuDriver.name
            p[Keys.CUSTOM_DRIVER_NAME] = settings.customDriverName
            p[Keys.TURNIP_VERSION] = settings.turnipVersion
            p[Keys.TURNIP_INSTALLED] = settings.turnipInstalled
            p[Keys.ACTIVE_PROFILE] = settings.activeProfile.name
            p[Keys.AUTO_REVERT] = settings.autoRevertTo1xIfBelow28Fps
            p[Keys.PERFORMANCE_MODE] = settings.performanceMode
            p[Keys.SAFE_MODE] = settings.safeMode
            p[Keys.CLOSE_BACKGROUND] = settings.closeBackgroundApps
            p[Keys.ALLOW_EXTERNAL_VITA3K] = settings.allowExternalVita3k
            p[Keys.PSN_SIGNED_IN] = settings.psnSignedIn
            p[Keys.GAME_LANGUAGE] = settings.gameLanguage.name
            p[Keys.RAWG_API_KEY] = settings.rawgApiKey
            p[Keys.PKG_DELIVERED] = settings.pkgDeliveredToVita3k
            p[Keys.PKG_INSTALLED_IN_VITA3K] = settings.pkgInstalledInVita3k
            p[Keys.HIDE_TOUCH_GAMEPAD] = settings.hideTouchWhenGamepadConnected
            p[Keys.OVERLAY_SHOW_IN_GAME] = settings.showTouchOverlayInGame
            p[Keys.OVERLAY_OPACITY] = clampOverlayOpacity(settings.touchOverlayOpacity)
            p[Keys.OVERLAY_NEON] = settings.touchOverlayNeonGlow
            p[Keys.OVERLAY_HIDDEN_PROFILES] = settings.touchOverlayHiddenProfileIds
                .filter { it.isNotBlank() && !it.contains(',') }.joinToString(",")
            // M34: hay ajustes guardados (aunque sean los del primer preset).
            // detectGpu() solo aplica un preset automáticamente si esto es false.
            p[Keys.PRESET_APPLIED] = true
        }
    }

    /** ¿Se ha guardado ya alguna vez una configuración (preset o ajuste)? */
    suspend fun isPresetApplied(): Boolean {
        return context.settingsDataStore.data.first()[Keys.PRESET_APPLIED] == true
    }

    /**
     * Aplica un preset completo (sobrescribe los ajustes actuales).
     * lowRam=true aplica además el perfil conservador de ≤ 4 GB
     * (applyLowRamDefaults) sobre el preset; solo lo pasa el primer
     * arranque automático (detectGpu), nunca la elección manual de un
     * perfil por el usuario ni resetToDefaults (R4-P0-9: el reseteo
     * usa el preset canónico; la variante lowRam solo existe en el
     * primer arranque y queda registrada como divergencia).
     */
    suspend fun applyPreset(profile: GpuProfileId, current: LauncherSettings? = null, lowRam: Boolean = false) {
        val base = if (lowRam) applyLowRamDefaults(presetSettings(profile)) else presetSettings(profile)
        // El driver sí forma parte del preset: Turnip solo tiene sentido en
        // ADRENO_QUALITY_TURNIP; en Mali/G99 el driver siempre es Sistema.
        // La info de Turnip instalado (nombre/versión) se conserva siempre.
        val driver = when (profile) {
            GpuProfileId.ADRENO_QUALITY_TURNIP -> com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP
            // M23: ADRENO_PERFORMANCE es el perfil sin Turnip; antes con
            // keepDriver=true un usuario que venía de ADRENO_QUALITY_TURNIP
            // conservaba TURNIP en el perfil de rendimiento. Ahora siempre
            // Sistema aquí; quien quiera Turnip elige el perfil Turnip.
            else -> com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM
        }
        // M21: el preset cambia rendimiento/calidad, no los gustos del
        // usuario: audio, modo seguro, cerrar apps y ocultar controles se
        // conservan (antes se reseteaban en silencio al aplicar un preset).
        // El estado de entrega del .pkg también se conserva: un preset no
        // es importar un juego nuevo.
        // R4-P1-13: las cachés y el overlay manuales también se
        // conservan (textureCache, shaderCache, carga rápida,
        // overlay FPS, caché sin hash, avisos de shaders, detalle y
        // posición del HUD; ver preserveManualCacheChoices). La
        // divergencia que eso produce frente al preset queda visible
        // en General como "Perfil modificado" (ver
        // divergesFromPreset), con la caché de texturas nombrada
        // explícitamente cuando difiere.
        // R5-A2-2: el modo rendimiento y el auto-revert a 1x también se
        // conservan AQUÍ, dentro de applyPreset, y ya no en el
        // ViewModel: el auto-revert de la prueba de 30 s (completeBenchmark)
        // y detectGpu llaman directo al repo, y el bloque del VM no los
        // cubría (un usuario con performanceMode=false acababa con
        // performanceMode=true en silencio tras un auto-revert).
        val fromPreset = mergePresetWithUserChoices(base, current, driver)
        val merged = preserveManualCacheChoices(fromPreset, current, lowRam = lowRam)
        save(merged)
    }

    suspend fun resetToDefaults(gpu: GpuInfo?) {        val profile = if (gpu != null) defaultProfileFor(gpu) else GpuProfileId.G99_RECOMMENDED
        // R4-P0-9: "Restablecer ajustes" usa el preset CANÓNICO, sin
        // lowRam: la variante conservadora de ≤ 4 GB solo se aplica en
        // el primer arranque (detectGpu) y queda registrada como
        // divergencia (ver isLowRamConservativeVariant). Antes el
        // reseteo aplicaba lowRam y el mismo preset daba
        // textureCache=false aquí y true al aplicarlo a mano.
        val base = presetSettings(profile)
        // El estado de entrega del .pkg NO es un ajuste: restablecer no
        // debe obligar a volver a copiar 1.1 GB a Descargas ni a
        // reconfirmar la instalación (igual que applyPreset, M21).
        val current = runCatching { settingsFlow.first() }.getOrNull()
        save(resetDefaultsWithCurrent(base, current))
    }

    suspend fun saveGpuInfo(gpu: GpuInfo): Boolean {
        return safeEdit("info GPU") { p ->
            p[Keys.GPU_DETECTED] = true
            p[Keys.GPU_VENDOR] = gpu.vendor.name
            p[Keys.GPU_RENDERER] = gpu.renderer
            p[Keys.GPU_VENDOR_STR] = gpu.vendorString
            p[Keys.GPU_GLES] = gpu.glesVersion
            p[Keys.GPU_SOC] = gpu.socModel
            p[Keys.GPU_VULKAN] = gpu.vulkanVersionLabel
            p[Keys.GPU_VULKAN_SUPPORTED] = gpu.vulkanSupported
            if (gpu.maxAnisotropy != null) {
                p[Keys.GPU_MAX_ANISO] = gpu.maxAnisotropy
            } else {
                p.remove(Keys.GPU_MAX_ANISO)
            }
            p[Keys.GPU_DETECTED_AT] = gpu.detectedAtMillis
        }
    }

    suspend fun recordBenchmark(run: BenchmarkRun): Boolean {
        return safeEdit("benchmarks") { p ->
            val existing = decodeBenchmarks(p[Keys.BENCHMARKS] ?: "")
            val updated = (listOf(run) + existing).take(5)
            p[Keys.BENCHMARKS] = updated.joinToString(";;") { encodeBenchmark(it) }
        }
    }

    /**
     * Guarda el FPS medio medido por el núcleo durante una prueba de
     * 30 s. Lo escribe EmbeddedGameActivity al salir del juego.
     */
    suspend fun saveAutoBenchmarkFps(fps: Float): Boolean {
        return safeEdit("fps automático") { p ->
            p[Keys.BENCHMARK_AUTO_FPS] = fps
            p[Keys.BENCHMARK_AUTO_FPS_AT] = System.currentTimeMillis()
        }
    }

    /**
     * Lee y consume (borra) el FPS medido automáticamente. Devuelve
     * null si no hay medición, si no es válida (1..240) o si caducó
     * (más de [maxAgeMillis] desde que se guardó). Consumirla una
     * sola vez evita que una medición vieja se registre en una
     * prueba posterior.
     */
    suspend fun takeAutoBenchmarkFps(maxAgeMillis: Long = 10 * 60 * 1000L): Float? {
        val p = context.settingsDataStore.data.first()
        val fps = p[Keys.BENCHMARK_AUTO_FPS] ?: return null
        val at = p[Keys.BENCHMARK_AUTO_FPS_AT] ?: 0L
        safeEdit("fps automático") { e ->
            e.remove(Keys.BENCHMARK_AUTO_FPS)
            e.remove(Keys.BENCHMARK_AUTO_FPS_AT)
        }
        if (!fps.isFinite() || fps < 1f || fps > 240f) return null
        if (System.currentTimeMillis() - at > maxAgeMillis) return null
        return fps
    }

    companion object {
        // M22 + R2-S04: resolución solo con los valores que ofrece la
        // UI, y finita. Un backup editado a mano con un valor
        // intermedio se ajusta al valor ofrecido más cercano (en
        // empate, al menor: 1.375x -> 1.25x, conservador en Mali).
        // El mismo clamp se aplica al leer DataStore, al guardar y
        // al importar el backup (SettingsBackup lo llama).
        // 0.2.63 (B/C, P1-3): 0.5x es un valor ofrecido (el núcleo
        // lo acepta: resolution-multiplier es float sin clamp en
        // settings.cpp:193 del pin): el mínimo pasa de 1f a 0.5f y
        // la lista de snap lo incluye. Un valor inválido (0f, -1f)
        // cae al ofrecido más cercano, 0.5x, como 99f cae a 4x.
        // Ronda 0.2.65 (W-P1-1): 1.25x y 1.5x ENTRAN en el snap.
        // Verificado en el pin: resolution-multiplier es float
        // (config.h:152, state.h:88) sin recorte en el núcleo, y el
        // renderer Vulkan lo multiplica tal cual (creation.cpp:80-81
        // "960.f * res_multiplier", :168-171 "params.width *
        // res_multiplier"): 1.25x renderiza a 1200x680 y 1.5x a
        // 1440x816 de verdad, no se recortan a 1x. (Algunos caminos
        // OpenGL castean el multiplicador a int, pero el teléfono
        // usa Vulkan.) Antes el snap {0.5, 1, 2, 3, 4} los recortaba
        // en silencio a 1x aunque la UI los ofreciera.
        fun clampResolution(value: Float): Float {
            if (!value.isFinite()) return 1f
            val clamped = value.coerceIn(0.5f, 4f)
            return listOf(0.5f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).minByOrNull { kotlin.math.abs(it - clamped) } ?: 1f
        }

        fun clampAniso(value: Int): Int =
            if (value in setOf(1, 2, 4, 8, 16)) value else 1

        // Retraso de carga de archivos (R7-P2-03): la UI solo ofrece
        // 0..30 ms (SettingsCpuScreen) y el núcleo duerme esos ms en
        // cada sceIoOpen: un backup editado a mano con 99999 dejaría
        // el juego inutilizable. El mismo clamp se aplica al leer
        // DataStore, al guardar y al importar el backup (SettingsBackup
        // lo llama), como resolución, aniso y opacidad.
        fun clampFileLoadingDelay(value: Int): Int = value.coerceIn(0, 30)

        // Opacidad general del overlay: 0.1..1 y finita.
        fun clampOverlayOpacity(value: Float): Float =
            if (!value.isFinite()) 1f else value.coerceIn(0.1f, 1f)

        /** Ids de perfiles con el overlay oculto, guardados como "id1,id2" (los ids nunca llevan coma). */
        fun decodeHiddenProfiles(raw: String): Set<String> =
            if (raw.isBlank()) emptySet() else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

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

/**
 * Ronda 0.2.70 (11-P2-4): "Restablecer ajustes" YA NO devuelve el
 * idioma a español, la sesión PSN (simulada) a ON ni "Permitir app
 * externa" a OFF sin avisar: son elecciones del usuario, no valores
 * de preset, y aplicar un preset ya las conserva expresamente
 * (mergePresetWithUserChoices: el idioma "NO es campo de preset").
 * El reseteo total las conservaba solo para Turnip y la entrega del
 * .pkg; ahora también estas tres. Pura, testeable en JVM
 * (ResetToDefaultsTest).
 * Fix del run #640: función top-level, FUERA de SettingsRepository,
 * para que el test pueda importarla (antes era miembro de la clase
 * y el import no resolvía). No usa nada de la clase.
 */
internal fun resetDefaultsWithCurrent(base: LauncherSettings, current: LauncherSettings?): LauncherSettings =
    base.copy(
        pkgDeliveredToVita3k = current?.pkgDeliveredToVita3k ?: false,
        pkgInstalledInVita3k = current?.pkgInstalledInVita3k ?: false,
        gameLanguage = current?.gameLanguage ?: GameLanguage.SPANISH,
        psnSignedIn = current?.psnSignedIn ?: true,
        allowExternalVita3k = current?.allowExternalVita3k ?: false,
        // Ronda 0.2.69 (RAWG): la clave RAWG es elección del usuario,
        // no valor de preset: restablecer no debe borrarla (igual que
        // mergePresetWithUserChoices la conserva al aplicar un preset).
        rawgApiKey = current?.rawgApiKey ?: "",
    )

/**
 * R6-P1-08: fusión pura del preset con los gustos del usuario.
 * El preset cambia rendimiento/calidad, no los gustos del usuario:
 * audio, modo seguro, cerrar apps, PSN, modo rendimiento, auto-revert,
 * entrega del .pkg, overlay táctil y "Permitir app externa" se
 * conservan (antes allowExternalVita3k se perdía en silencio al tocar
 * cualquier preset: presetSettings() no lo define y el copy no lo
 * llevaba, así que siempre quedaba en false). Pura, testeable en JVM
 * (R5PresetPreservationTest).
 */
internal fun mergePresetWithUserChoices(
    base: LauncherSettings,
    current: LauncherSettings?,
    driver: GpuDriver,
): LauncherSettings = base.copy(
    gpuDriver = driver,
    customDriverName = current?.customDriverName ?: "",
    turnipVersion = current?.turnipVersion ?: "",
    turnipInstalled = current?.turnipInstalled ?: false,
    // Ronda 0.2.65 (RG-P2-15): el modo turbo es una opción manual del
    // driver, no un valor de preset (ningún preset lo enciende): al
    // aplicar un preset se conserva, como el modo rendimiento y el
    // auto-revert. Si no, encenderlo y cambiar de preset lo apagaría
    // en silencio y no habría forma de que sobreviviera.
    turboMode = current?.turboMode ?: false,
    audioVolume = current?.audioVolume ?: 100,
    audioBackend = current?.audioBackend ?: com.danielsnagustin.childoflight.launcher.model.AudioBackend.SDL,
    ngsEnable = current?.ngsEnable ?: true,
    safeMode = current?.safeMode ?: false,
    closeBackgroundApps = current?.closeBackgroundApps ?: true,
    psnSignedIn = current?.psnSignedIn ?: true,
    // 0.2.63 (E): el idioma NO es campo de preset: al aplicar un
    // preset se conserva la elección del usuario (si no, cada
    // preset lo devolvería a español sin avisar).
    gameLanguage = current?.gameLanguage ?: GameLanguage.SPANISH,
    // la llave no es campo de preset, aplicar un preset no debe borrarla (como gameLanguage)
    rawgApiKey = current?.rawgApiKey ?: "",
    performanceMode = current?.performanceMode ?: true,
    autoRevertTo1xIfBelow28Fps = current?.autoRevertTo1xIfBelow28Fps ?: true,
    pkgDeliveredToVita3k = current?.pkgDeliveredToVita3k ?: false,
    pkgInstalledInVita3k = current?.pkgInstalledInVita3k ?: false,
    hideTouchWhenGamepadConnected = current?.hideTouchWhenGamepadConnected ?: true,
    showTouchOverlayInGame = current?.showTouchOverlayInGame ?: true,
    touchOverlayOpacity = current?.touchOverlayOpacity ?: 1f,
    touchOverlayNeonGlow = current?.touchOverlayNeonGlow ?: true,
    touchOverlayHiddenProfileIds = current?.touchOverlayHiddenProfileIds ?: emptySet(),
    allowExternalVita3k = current?.allowExternalVita3k ?: false,
)
