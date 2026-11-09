package com.danielsnagustin.childoflight.launcher.ui.settings

import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
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
 * tal cual y el ViewModel lo dice en el mensaje. Lo mismo vale para
 * General (P2-18): los presets no definen ningún campo de General.
 */
fun resetCategorySettings(current: LauncherSettings, category: SettingsCategory): LauncherSettings {
    val preset = presetSettings(current.activeProfile)
    return when (category) {
        // P2-18: General NO tiene campos de preset. psnSignedIn y
        // allowExternalVita3k son interruptores del usuario y los
        // presets no los definen (al construir el preset toman el
        // default). Restablecer General los pisaba con el default:
        // reactivaba la PSN simulada aunque el usuario la hubiera
        // apagado. Se conserva todo: categoryHasResettableSettings
        // (GENERAL) es false y el ViewModel lo dice en el mensaje.
        SettingsCategory.GENERAL -> current
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
            ngsEnable = preset.ngsEnable,
        )
        SettingsCategory.PERFORMANCE -> current.copy(
            // R4-P0-9: textureCache sale del preset CANÓNICO (explícito
            // en presetSettings: true en G99_RECOMMENDED). La variante
            // conservadora lowRam (false) solo existe en el primer
            // arranque y nunca se re-aplica aquí, ni al aplicar el
            // preset a mano, ni en el auto-revert de la prueba de 30 s.
            textureCache = preset.textureCache,
            shaderCache = preset.shaderCache,
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
        SettingsCategory.STORAGE -> current
        SettingsCategory.DIAGNOSTICS -> current.copy(
            closeBackgroundApps = preset.closeBackgroundApps,
            safeMode = preset.safeMode,
        )
    }
}

/**
 * Solo Personalización tiene campos de UiPreferences: el resto de
 * categorías devuelven las preferencias tal cual. Restablecer la
 * personalización NO toca los perfiles de controles táctiles (eso lo
 * hace "Restablecer personalización y editor táctil", que además resetea el
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
    category != SettingsCategory.STORAGE && category != SettingsCategory.GENERAL

/**
 * P1-8 (2026-11-09): Turnip solo vale en Adreno con el driver
 * instalado. Si los ajustes traen gpuDriver=TURNIP en un teléfono
 * donde no puede funcionar (Mali, otro vendor, vendor desconocido, o
 * Adreno sin Turnip instalado), se vuelve al driver del sistema. Sin
 * esto, restablecer la categoría GPU con el perfil "Calidad / Turnip"
 * activo dejaba TURNIP en Mali sin forma de revertirlo: la UI de Mali
 * ni siquiera muestra los chips de driver (SettingsGpuScreen solo
 * muestra texto). También se aplica al restablecer todo y al importar
 * una copia de seguridad de otro teléfono. Pura, testeable en JVM.
 */
fun guardTurnipDriver(settings: LauncherSettings, gpuVendor: GpuVendor?): LauncherSettings =
    if (settings.gpuDriver == GpuDriver.TURNIP &&
        (gpuVendor != GpuVendor.ADRENO || !settings.turnipInstalled)
    ) {
        settings.copy(gpuDriver = GpuDriver.SYSTEM)
    } else {
        settings
    }
