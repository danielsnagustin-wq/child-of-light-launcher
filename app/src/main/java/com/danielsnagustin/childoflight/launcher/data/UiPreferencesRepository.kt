package com.danielsnagustin.childoflight.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.MutablePreferences
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// Crash al abrir (run #80, 2026-11-08): si el archivo preferences_pb
// está corrupto (muertes duras del proceso: crash 15:06, cierres
// durante la instalación de 1,14 GB), `data` lanza y el primer
// colector (MainActivity.setContent) muere sin mostrar UI. El
// corruptionHandler reemplaza el archivo roto por preferencias
// vacías (la app abre con defaults) y el catch cubre cualquier
// otro fallo de lectura. El juego/firmware/saves son archivos, no
// viven en DataStore: no se pierden.
private val Context.uiDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "col_ui_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Personalización del launcher: tema, acento, fondo, texto, idioma, portada y presets ocultos. */
class UiPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("ui_theme")
        val ACCENT = stringPreferencesKey("ui_accent")
        val BACKGROUND = stringPreferencesKey("ui_background")
        val TEXT_SIZE = stringPreferencesKey("ui_text_size")
        val LANGUAGE = stringPreferencesKey("ui_language")
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
            sectionOrder = decodeSectionOrder(p[Keys.SECTION_ORDER]),
            hiddenPresets = decodeHiddenPresets(p[Keys.HIDDEN_PRESETS] ?: ""),
            // Claves nuevas (Etapa 6): un valor guardado desconocido o
            // ausente cae al default seguro (comportamiento actual).
            cardSize = runCatching { CardSize.valueOf(p[Keys.CARD_SIZE] ?: "NORMAL") }.getOrDefault(CardSize.NORMAL),
            cardTransparency = runCatching { CardTransparency.valueOf(p[Keys.CARD_TRANSPARENCY] ?: "OPAQUE") }.getOrDefault(CardTransparency.OPAQUE),
            iconSize = runCatching { IconSize.valueOf(p[Keys.ICON_SIZE] ?: "MEDIUM") }.getOrDefault(IconSize.MEDIUM),
        )
    }.catch { e ->
        Log.w("ChildOfLight", "DataStore ui_prefs ilegible, usando defaults: ${e.message}")
        emit(UiPreferences())
    }

    /**
     * P1-18: un fallo de E/S al escribir (disco lleno, archivo corrupto)
     * no puede tumbar la app: viewModelScope tiene SupervisorJob y una
     * excepción no capturada en un hijo mata el proceso. Toda escritura
     * DataStore pasa por aquí: registra el fallo y devuelve false para
     * que el llamador pueda avisar.
     */
    private suspend fun safeEdit(tag: String, transform: suspend (MutablePreferences) -> Unit): Boolean {
        return try {
            context.uiDataStore.edit(transform)
            true
        } catch (e: Exception) {
            Log.w("ChildOfLight", "DataStore $tag no se pudo escribir: ${e.message}")
            false
        }
    }

    suspend fun save(prefs: UiPreferences): Boolean {
        safeEdit("preferencias de interfaz") { p ->
            p[Keys.THEME] = prefs.theme.name
            p[Keys.ACCENT] = prefs.accent.name
            p[Keys.BACKGROUND] = prefs.homeBackground.name
            p[Keys.TEXT_SIZE] = prefs.textSize.name
            p[Keys.LANGUAGE] = prefs.language.name
            p[Keys.SECTION_ORDER] = prefs.sectionOrder.joinToString(",") { it.name }
            p[Keys.HIDDEN_PRESETS] = prefs.hiddenPresets.joinToString(",") { it.name }
            p[Keys.CARD_SIZE] = prefs.cardSize.name
            p[Keys.CARD_TRANSPARENCY] = prefs.cardTransparency.name
            p[Keys.ICON_SIZE] = prefs.iconSize.name
        }
    }

    /**
     * P1-17: oculta presets en UNA sola escritura atómica. Antes el
     * ViewModel leía las prefs (uiFlow.first()), sumaba un preset y
     * guardaba; en el lote, cada llamada corría en su propia corrutina,
     * todas leían el mismo estado inicial y la última escritura ganaba:
     * solo quedaba oculto un preset. Aquí la lectura y la escritura
     * ocurren dentro del mismo edit{}: DataStore serializa los edit y
     * cada llamada ve lo que escribió la anterior.
     */
    suspend fun hideGpuPresets(profiles: Set<GpuProfileId>): Boolean {
        if (profiles.isEmpty()) return true
        safeEdit("presets ocultos") { p ->
            val current = decodeHiddenPresets(p[Keys.HIDDEN_PRESETS] ?: "")
            p[Keys.HIDDEN_PRESETS] = (current + profiles).joinToString(",") { it.name }
        }
    }

    suspend fun resetAll(): Boolean {
        return save(UiPreferences())
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
