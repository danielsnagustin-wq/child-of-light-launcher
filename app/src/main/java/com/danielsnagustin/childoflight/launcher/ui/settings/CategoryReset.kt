package com.danielsnagustin.childoflight.launcher.ui.settings

import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.presetSettings

/**
 * Restauración individual por categoría (Ola 3, Etapa 9b). Puro,
 * testeable en JVM (CategoryResetTest).
 *
 * Cada función restablece SOLO los campos de esa categoría a los
 * valores del preset del perfil activo (o a los defaults para
 * Personalización, que no es un preset del núcleo) y conserva TODO lo
 * demás. El reparto de campos por categoría es el mismo que el de la
 * Ola 2 (ver col-ola2-manifest: inventario de funciones conservadas):
 * la caché de texturas y la de shaders van en Rendimiento, no en GPU;
 * el Modo seguro va en Diagnóstico; el overlay táctil va en Controles.
 *
 * Firmware y Almacenamiento NO tienen campos de ajustes: el firmware
 * son archivos (PUP guardado / instalado en el núcleo) y el
 * almacenamiento son archivos (copias en Descargas, juego, DLCs). No
 * hay nada que restablecer ahí: ambas funciones devuelven los valores
 * tal cual y el ViewModel lo dice en el mensaje.
 */
fun resetCategorySettings(current: LauncherSettings, category: SettingsCategory): LauncherSettings {
    val preset = presetSettings(current.activeProfile)
    return when (category) {
        SettingsCategory.GENERAL -> current.copy(
            allowExternalVita3k = preset.allowExternalVita3k,
        )
        SettingsCategory.PERSONALIZATION -> current
        SettingsCategory.GPU -> current.copy(
            backend = preset.backend,
            resolutionMultiplier = preset.resolutionMultiplier,
            screenFilter = preset.screenFilter,
            anisotropicFiltering = preset.anisotropicFiltering,
            vSync = preset.vSync,
            asyncPipelineCompilation = preset.asyncPipelineCompilation,
            highAccuracy = preset.highAccuracy,
            surfaceSyncEnabled = preset.surfaceSyncEnabled,
            memoryMapping = preset.memoryMapping,
            // El driver sí forma parte del preset (Turnip solo en
            // ADRENO_QUALITY_TURNIP); la info del driver instalado
            // se conserva, igual que en applyPreset (M21).
            gpuDriver = preset.gpuDriver,
        )
        SettingsCategory.CPU -> current.copy(
            cpuOpt = preset.cpuOpt,
            fpsHack = preset.fpsHack,
            fileLoadingDelay = preset.fileLoadingDelay,
        )
        SettingsCategory.AUDIO -> current.copy(
            audioVolume = preset.audioVolume,
            audioBackend = preset.audioBackend,
            audioBufferSize = preset.audioBufferSize,
            ngsEnable = preset.ngsEnable,
        )
        SettingsCategory.PERFORMANCE -> current.copy(
            textureCache = preset.textureCache,
            shaderCache = preset.shaderCache,
            frameLimiter = preset.frameLimiter,
            frameSkipping = preset.frameSkipping,
            showFpsOverlay = preset.showFpsOverlay,
            performanceOverlayDetail = preset.performanceOverlayDetail,
            performanceOverlayPosition = preset.performanceOverlayPosition,
            hashlessTextureCache = preset.hashlessTextureCache,
            showCompileShaders = preset.showCompileShaders,
            autoRevertTo1xIfBelow28Fps = preset.autoRevertTo1xIfBelow28Fps,
            performanceMode = preset.performanceMode,
        )
        SettingsCategory.CONTROLS -> current.copy(
            hideTouchWhenGamepadConnected = preset.hideTouchWhenGamepadConnected,
            showTouchOverlayInGame = preset.showTouchOverlayInGame,
            touchOverlayOpacity = preset.touchOverlayOpacity,
            touchOverlayNeonGlow = preset.touchOverlayNeonGlow,
            touchOverlayHiddenProfileIds = preset.touchOverlayHiddenProfileIds,
        )
        SettingsCategory.FIRMWARE -> current
        SettingsCategory.STORAGE -> current
        SettingsCategory.DIAGNOSTICS -> current.copy(
            accurateThreadScheduling = preset.accurateThreadScheduling,
            closeBackgroundApps = preset.closeBackgroundApps,
            safeMode = preset.safeMode,
        )
    }
}

/**
 * Solo Personalización tiene campos de UiPreferences: el resto de
 * categorías devuelven las preferencias tal cual. Restablecer la
 * personalización NO toca los perfiles de controles táctiles (eso lo
 * hace "Restablecer toda la personalización", que además resetea el
 * editor táctil): aquí solo vuelven tema, acento, fondo, texto,
 * idioma, portada, tamaño y transparencia de tarjetas e iconos.
 */
fun resetCategoryUiPrefs(current: UiPreferences, category: SettingsCategory): UiPreferences =
    if (category == SettingsCategory.PERSONALIZATION) {
        // Los presets ocultos se conservan: son de "Limpiar no usados",
        // no de la apariencia; restablecer el tema no debe re-listar
        // presets que el usuario ocultó a propósito.
        UiPreferences(hiddenPresets = current.hiddenPresets)
    } else {
        current
    }

/** true si la categoría tiene algún campo de ajustes que restablecer. */
fun categoryHasResettableSettings(category: SettingsCategory): Boolean =
    category != SettingsCategory.FIRMWARE && category != SettingsCategory.STORAGE
