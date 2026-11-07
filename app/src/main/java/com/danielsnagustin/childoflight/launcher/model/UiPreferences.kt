package com.danielsnagustin.childoflight.launcher.model

/**
 * Preferencias de personalización del launcher (apariencia y portada).
 * Viven en su propio DataStore ("col_ui_prefs") para no mezclarse
 * con los ajustes de rendimiento de Vita3K.
 */
enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }

enum class AccentColor(val hex: String, val label: String) {
    GOLD("#F2B705", "Dorado"),
    BLUE("#7FB3D5", "Azul"),
    GREEN("#4CAF50", "Verde"),
    RED("#E57373", "Rojo"),
    PURPLE("#BA68C8", "Morado"),
}

enum class HomeBackground(val hex: String, val label: String) {
    // AMOLED (2026-11-07): tintes casi negros. El fondo de la Home es
    // negro puro #000000 con un leve tinte arriba; nada de azul marino.
    // Se guardan por nombre, así que cambiar el hex es seguro.
    NIGHT("#000000", "Negro AMOLED"),
    BLUE("#05070D", "Azul noche (AMOLED)"),
    GREEN("#040805", "Verde oscuro (AMOLED)"),
    PURPLE("#080510", "Morado oscuro (AMOLED)"),
}

enum class AppLanguage(val label: String) { SPANISH("Español"), ENGLISH("English") }

enum class TextSize(val scale: Float, val label: String) {
    SMALL(0.9f, "Pequeño"),
    MEDIUM(1.0f, "Mediano"),
    LARGE(1.15f, "Grande"),
}

/** Secciones de la pantalla principal, en el orden que elija el usuario. */
enum class HomeSection(val label: String) {
    GAME_CARD("Tarjeta del juego"),
    GPU_CARD("Tarjeta GPU"),
    NAV_BUTTONS("Botones de navegación"),
}

data class UiPreferences(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentColor = AccentColor.GOLD,
    val homeBackground: HomeBackground = HomeBackground.NIGHT,
    val textSize: TextSize = TextSize.MEDIUM,
    val language: AppLanguage = AppLanguage.SPANISH,
    val showFpsOnHome: Boolean = true,
    val showAchievementsOnHome: Boolean = true,
    val sectionOrder: List<HomeSection> = HomeSection.entries.toList(),
    /** Presets de GPU ocultos por "Limpiar no usados" (siguen existiendo, no se listan). */
    val hiddenPresets: Set<GpuProfileId> = emptySet(),
)
