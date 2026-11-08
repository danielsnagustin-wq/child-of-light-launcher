package com.danielsnagustin.childoflight.launcher.ui

enum class Screen {
    HOME, SETTINGS, GPU, TOUCH, SAVES, ACHIEVEMENTS, CLEANUP, IMPORT,
    // Ajustes por categoría (Fase 2, Ola 2): el hub es SETTINGS y cada
    // categoría es una pantalla propia. Atrás: categoría -> hub -> Home
    // (ver MainActivity). Los valores sobreviven a rotación porque la
    // pantalla se guarda por nombre con rememberSaveable.
    SETTINGS_GENERAL,
    SETTINGS_PERSONALIZATION,
    SETTINGS_GPU,
    SETTINGS_CPU,
    SETTINGS_AUDIO,
    SETTINGS_PERFORMANCE,
    SETTINGS_CONTROLS,
    SETTINGS_STORAGE,
    SETTINGS_DIAGNOSTICS,
}
