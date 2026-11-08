package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.danielsnagustin.childoflight.launcher.model.AccentColor
import com.danielsnagustin.childoflight.launcher.model.AppLanguage
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.HomeBackground
import com.danielsnagustin.childoflight.launcher.model.HomeSection
import com.danielsnagustin.childoflight.launcher.model.IconSize
import com.danielsnagustin.childoflight.launcher.model.TextSize
import com.danielsnagustin.childoflight.launcher.model.ThemeMode
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiDataStore: DataStore<Preferences> by preferencesDataStore(name = "col_ui_prefs")

/** Personalización del launcher: tema, acento, fondo, texto, idioma, portada y presets ocultos. */
class UiPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("ui_theme")
        val ACCENT = stringPreferencesKey("ui_accent")
        val BACKGROUND = stringPreferencesKey("ui_background")
        val TEXT_SIZE = stringPreferencesKey("ui_text_size")
        val LANGUAGE = stringPreferencesKey("ui_language")
        val SHOW_FPS_HOME = booleanPreferencesKey("ui_show_fps_home")
        val SHOW_ACHIEVEMENTS_HOME = booleanPreferencesKey("ui_show_achievements_home")
        val SECTION_ORDER = stringPreferencesKey("ui_section_order")
        val HIDDEN_PRESETS = stringPreferencesKey("ui_hidden_presets")
        val CARD_SIZE = stringPreferencesKey("ui_card_size")
        val CARD_TRANSPARENCY = stringPreferencesKey("ui_card_transparency")
        val ICON_SIZE = stringPreferencesKey("ui_icon_size")
    }

    val uiFlow: Flow<UiPreferences> = context.uiDataStore.data.map { p ->
        UiPreferences(
            theme = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            accent = runCatching { AccentColor.valueOf(p[Keys.ACCENT] ?: "ICE_BLUE") }.getOrDefault(AccentColor.ICE_BLUE),
            homeBackground = runCatching { HomeBackground.valueOf(p[Keys.BACKGROUND] ?: "NIGHT") }.getOrDefault(HomeBackground.NIGHT),
            textSize = runCatching { TextSize.valueOf(p[Keys.TEXT_SIZE] ?: "MEDIUM") }.getOrDefault(TextSize.MEDIUM),
            language = runCatching { AppLanguage.valueOf(p[Keys.LANGUAGE] ?: "SPANISH") }.getOrDefault(AppLanguage.SPANISH),
            showFpsOnHome = p[Keys.SHOW_FPS_HOME] ?: true,
            showAchievementsOnHome = p[Keys.SHOW_ACHIEVEMENTS_HOME] ?: true,
            sectionOrder = decodeSectionOrder(p[Keys.SECTION_ORDER]),
            hiddenPresets = decodeHiddenPresets(p[Keys.HIDDEN_PRESETS] ?: ""),
            // Claves nuevas (Etapa 6): un valor guardado desconocido o
            // ausente cae al default seguro (comportamiento actual).
            cardSize = runCatching { CardSize.valueOf(p[Keys.CARD_SIZE] ?: "NORMAL") }.getOrDefault(CardSize.NORMAL),
            cardTransparency = runCatching { CardTransparency.valueOf(p[Keys.CARD_TRANSPARENCY] ?: "OPAQUE") }.getOrDefault(CardTransparency.OPAQUE),
            iconSize = runCatching { IconSize.valueOf(p[Keys.ICON_SIZE] ?: "MEDIUM") }.getOrDefault(IconSize.MEDIUM),
        )
    }

    suspend fun save(prefs: UiPreferences) {
        context.uiDataStore.edit { p ->
            p[Keys.THEME] = prefs.theme.name
            p[Keys.ACCENT] = prefs.accent.name
            p[Keys.BACKGROUND] = prefs.homeBackground.name
            p[Keys.TEXT_SIZE] = prefs.textSize.name
            p[Keys.LANGUAGE] = prefs.language.name
            p[Keys.SHOW_FPS_HOME] = prefs.showFpsOnHome
            p[Keys.SHOW_ACHIEVEMENTS_HOME] = prefs.showAchievementsOnHome
            p[Keys.SECTION_ORDER] = prefs.sectionOrder.joinToString(",") { it.name }
            p[Keys.HIDDEN_PRESETS] = prefs.hiddenPresets.joinToString(",") { it.name }
            p[Keys.CARD_SIZE] = prefs.cardSize.name
            p[Keys.CARD_TRANSPARENCY] = prefs.cardTransparency.name
            p[Keys.ICON_SIZE] = prefs.iconSize.name
        }
    }

    suspend fun resetAll() {
        save(UiPreferences())
    }

    companion object {
        fun decodeSectionOrder(raw: String?): List<HomeSection> {
            if (raw.isNullOrBlank()) return HomeSection.entries.toList()
            val ordered = raw.split(",").mapNotNull { runCatching { HomeSection.valueOf(it) }.getOrNull() }
            if (ordered.isEmpty()) return HomeSection.entries.toList()
            // Las secciones que falten se añaden al final (compatibilidad).
            return ordered + HomeSection.entries.filter { it !in ordered }
        }

        fun decodeHiddenPresets(raw: String): Set<GpuProfileId> {
            if (raw.isBlank()) return emptySet()
            return raw.split(",").mapNotNull { runCatching { GpuProfileId.valueOf(it) }.getOrNull() }.toSet()
        }
    }
}
