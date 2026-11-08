package com.danielsnagustin.childoflight.launcher.model

/**
 * Preferencias de personalización del launcher (apariencia y portada).
 * Viven en su propio DataStore ("col_ui_prefs") para no mezclarse
 * con los ajustes de rendimiento de Vita3K.
 */
enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }

/**
 * Color de acento (2026-11-08): paleta Dark Fantasy Premium, sin
 * tonos cálidos. El acento alimenta MaterialTheme (Switch,
 * Slider, FilterChip, primaryContainer) y el botón principal. Por
 * defecto azul hielo. Los valores antiguos ya no existen: un valor
 * guardado desconocido cae al valor por defecto
 * (ver UiPreferencesRepository).
 */
enum class AccentColor(val hex: String, val label: String) {
    ICE_BLUE("#8CCBEB", "Azul hielo"),
    LAVENDER_BLUE("#6578B8", "Azul lavanda"),
    LAVENDER("#A99ACD", "Lavanda"),
    TURQUOISE("#75D6CE", "Turquesa"),
    SILVER("#DCE8F5", "Plata"),
}

enum class HomeBackground(val hex: String, val label: String) {
    // AMOLED (2026-11-08): fondo #10131D con un leve tinte arriba;
    // nada de azul marino. Se guardan por nombre, así que cambiar el
    // hex es seguro.
    NIGHT("#10131D", "Negro AMOLED"),
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

/**
 * Tamaño de las tarjetas (Ola 3, Etapa 6): escala real de padding y
 * radio de PremiumCard. Normal = el comportamiento actual (16 dp de
 * padding, radio 20 dp); Compacta y Amplia lo achican o agrandan.
 */
enum class CardSize(val label: String, val paddingDp: Int, val cornerDp: Int) {
    COMPACT("Compacta", 10, 14),
    NORMAL("Normal", 16, 20),
    LARGE("Amplia", 22, 26),
}

/**
 * Transparencia de las tarjetas (Ola 3, Etapa 6): alpha real del fondo
 * de la tarjeta. Opaco = el comportamiento actual (alpha 1.0).
 */
enum class CardTransparency(val label: String, val alpha: Float) {
    OPAQUE("Opaco", 1f),
    SOFT("Suave", 0.92f),
    TRANSLUCENT("Translúcido", 0.8f),
}

/**
 * Tamaño de los iconos (Ola 3, Etapa 6): tamaño real de los iconos
 * vectoriales Canvas. Mediano = el tamaño actual (24 dp).
 *
 * Honesto: existe UN solo juego de iconos vectoriales consistente
 * (ColIcons.kt). NO hay un segundo pack de iconos que elegir: este
 * ajuste cambia el TAMAÑO, no el pack. No se inventa un selector de
 * paquetes que no existe.
 */
enum class IconSize(val label: String, val sizeDp: Int) {
    SMALL("Pequeño", 18),
    MEDIUM("Mediano", 24),
    LARGE("Grande", 32),
}

/** Secciones de la pantalla principal, en el orden que elija el usuario. */
enum class HomeSection(val label: String) {
    GAME_CARD("Tarjeta del juego"),
    GPU_CARD("Tarjeta GPU"),
}

data class UiPreferences(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentColor = AccentColor.ICE_BLUE,
    val homeBackground: HomeBackground = HomeBackground.NIGHT,
    val textSize: TextSize = TextSize.MEDIUM,
    val language: AppLanguage = AppLanguage.SPANISH,
    val showFpsOnHome: Boolean = true,
    val showAchievementsOnHome: Boolean = true,
    val sectionOrder: List<HomeSection> = HomeSection.entries.toList(),
    /** Presets de GPU ocultos por "Limpiar no usados" (siguen existiendo, no se listan). */
    val hiddenPresets: Set<GpuProfileId> = emptySet(),
    /** Tamaño de las tarjetas (Etapa 6). Default = comportamiento actual. */
    val cardSize: CardSize = CardSize.NORMAL,
    /** Transparencia de las tarjetas (Etapa 6). Default = opaco, como hoy. */
    val cardTransparency: CardTransparency = CardTransparency.OPAQUE,
    /** Tamaño de los iconos vectoriales (Etapa 6). Default = 24 dp, como hoy. */
    val iconSize: IconSize = IconSize.MEDIUM,
)
