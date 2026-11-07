package com.danielsnagustin.childoflight.launcher.ui

import com.danielsnagustin.childoflight.launcher.model.AppLanguage

/**
 * Cadenas de la interfaz principal del launcher en español e inglés.
 * Alcance honesto: traduce la portada, la navegación y los títulos de
 * sección. Las descripciones técnicas de los ajustes de Vita3K
 * (nombres de claves, notas de compatibilidad) permanecen en español
 * en Etapa 1; la traducción completa de esas notas es trabajo de
 * Etapa 2. El idioma elegido se guarda en DataStore (UiPreferences).
 */
data class AppStrings(
    val appSubtitle: String,
    val play: String,
    val importGame: String,
    val importDlc: String,
    val importUpdate: String,
    val importFirmware: String,
    val settings: String,
    val gpuAndDiagnostics: String,
    val achievements: String,
    val touchControls: String,
    val savesAndData: String,
    val back: String,
    val close: String,
    val cancel: String,
    val delete: String,
    val reset: String,
    val state: String,
    val dlcs: String,
    val update: String,
    val firmware: String,
    val notInstalled: String,
    val installed: String,
)

val STRINGS_ES = AppStrings(
    appSubtitle = "Launcher — un solo juego",
    play = "▶  JUGAR",
    importGame = "Importar juego",
    importDlc = "Importar DLC",
    importUpdate = "Importar update",
    importFirmware = "Importar firmware",
    settings = "Ajustes de rendimiento y calidad",
    gpuAndDiagnostics = "GPU y diagnóstico",
    achievements = "Logros (27)",
    touchControls = "Controles táctiles",
    savesAndData = "Guardados y datos",
    back = "Volver",
    close = "Cerrar",
    cancel = "Cancelar",
    delete = "Borrar",
    reset = "Restablecer",
    state = "Estado",
    dlcs = "DLCs",
    update = "Update",
    firmware = "Firmware",
    notInstalled = "No instalado",
    installed = "Instalado",
)

val STRINGS_EN = AppStrings(
    appSubtitle = "Launcher — single game",
    play = "▶  PLAY",
    importGame = "Import game",
    importDlc = "Import DLC",
    importUpdate = "Import update",
    importFirmware = "Import firmware",
    settings = "Performance and quality settings",
    gpuAndDiagnostics = "GPU and diagnostics",
    achievements = "Trophies (27)",
    touchControls = "Touch controls",
    savesAndData = "Saves and data",
    back = "Back",
    close = "Close",
    cancel = "Cancel",
    delete = "Delete",
    reset = "Reset",
    state = "Status",
    dlcs = "DLCs",
    update = "Update",
    firmware = "Firmware",
    notInstalled = "Not installed",
    installed = "Installed",
)

fun stringsFor(language: AppLanguage): AppStrings =
    if (language == AppLanguage.ENGLISH) STRINGS_EN else STRINGS_ES
