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
            // Ronda 0.2.65 (RG-P2-15): el modo turbo es opción de GPU:
            // restablecer la categoría lo devuelve al valor del preset
            // (OFF; ningún preset lo enciende).
            turboMode = preset.turboMode,
            // El driver sí forma parte del preset (Turnip solo en
            // ADRENO_QUALITY_TURNIP); la info del driver instalado
            // se conserva, igual que en applyPreset (M21).
            // R5-A2-10: pero el driver ACTIVO no se suelta sin aviso:
            // un usuario Adreno con Turnip instalado y activo que
            // restablece la GPU con el perfil G99 (preset SYSTEM)
            // perdía Turnip y no hay forma de recuperarlo desde la
            // UI sin reinstalar el zip. Se conserva TURNIP cuando
            // está instalado; la guarda guardTurnipDriver (que el
            // ViewModel aplica después del reset) sigue mandando
            // sobre el vendor: en Mali o sin Turnip instalado vuelve
            // a SYSTEM, así que conservarlo aquí nunca deja un
            // TURNIP huérfano.
            gpuDriver = if (current.gpuDriver == GpuDriver.TURNIP && current.turnipInstalled) {
                GpuDriver.TURNIP
            } else {
                preset.gpuDriver
            },
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
        // presets que el usuario ocultó a propósito. Igual
        // homeIntroSeen (R4-P2-6): no es apariencia; restablecer la
        // personalización no debe hacer sonar la intro otra vez.
        UiPreferences(hiddenPresets = current.hiddenPresets, homeIntroSeen = current.homeIntroSeen)
    } else {
        current
    }

/**
 * R6-P2-09: nota cuando restablecer la GPU conserva Turnip aunque el
 * preset diga Sistema (resetCategorySettings lo conserva si estaba
 * instalado y activo: R5-A2-10). El mensaje del momento debe decirlo:
 * sin la nota, "restablecida a los valores del preset" miente por
 * omisión. Pura, testeable en JVM (CategoryResetTest).
 */
fun turnipKeptNote(reset: LauncherSettings, preset: LauncherSettings): String =
    if (reset.gpuDriver == GpuDriver.TURNIP && preset.gpuDriver == GpuDriver.SYSTEM) {
        " Turnip se conservó (estaba instalado y activo)."
    } else {
        ""
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
 *
 * Ronda 0.2.65 (G-P2-7): al volver al sistema se limpian TAMBIÉN los
 * tres flags del driver (turnipInstalled, turnipVersion,
 * customDriverName): antes solo se corregía gpuDriver y el estado
 * fantasma ("Turnip instalado" sin zip en disco, visible en
 * Diagnóstico) sobrevivía a la importación de una copia de otro
 * teléfono. Cuando el guard NO actúa (Adreno con Turnip instalado),
 * los flags se conservan tal cual.
 */
fun guardTurnipDriver(settings: LauncherSettings, gpuVendor: GpuVendor?): LauncherSettings =
    if (settings.gpuDriver == GpuDriver.TURNIP &&
        (gpuVendor != GpuVendor.ADRENO || !settings.turnipInstalled)
    ) {
        settings.copy(
            gpuDriver = GpuDriver.SYSTEM,
            turnipInstalled = false,
            turnipVersion = "",
            customDriverName = "",
        )
    } else {
        settings
    }
