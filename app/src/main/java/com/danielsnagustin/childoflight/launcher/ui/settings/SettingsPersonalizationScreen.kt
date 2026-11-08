package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.PrimaryButton
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Personalización" (Ola 2, Etapa 1; Ola 3, Etapa 6):
 * apariencia del launcher y de la portada.
 *
 * La pantalla edita una COPIA LOCAL (borrador) de UiPreferences: tocar
 * una opción solo cambia el borrador. Nada se guarda hasta pulsar
 * "Aplicar personalización" (saveUiPrefs con el borrador);
 * "Descartar cambios" vuelve a lo guardado. Hay DOS resets con ámbito
 * distinto y cada uno dice lo que hace (R2-P2-26): "Restablecer solo
 * esta pantalla" (pie de pantalla) solo toca tema/acento/fondo/texto/
 * idioma/portada; "Restablecer personalización y editor táctil"
 * resetea UiPreferences Y los perfiles del editor táctil.
 *
 * Sobre los iconos, honesto: existe UN solo juego de iconos
 * vectoriales consistente (ColIcons.kt); este ajuste cambia su
 * TAMAÑO, no el pack. No hay selector de paquetes de iconos.
 */

/** Saver del borrador (R2-P2-27): UiPreferences no es Parcelable, así
 * que se guarda por nombres de enum + primitivos para que el borrador
 * sobreviva a rotación. Un valor desconocido cae al default. */
private val uiPreferencesDraftSaver = Saver<UiPreferences?, Map<String, Any>>(
    save = { prefs ->
        prefs?.let {
            mapOf(
                "theme" to it.theme.name,
                "accent" to it.accent.name,
                "homeBackground" to it.homeBackground.name,
                "textSize" to it.textSize.name,
                "language" to it.language.name,
                "showFpsOnHome" to it.showFpsOnHome,
                "showAchievementsOnHome" to it.showAchievementsOnHome,
                "sectionOrder" to it.sectionOrder.map { section -> section.name },
                "hiddenPresets" to it.hiddenPresets.map { preset -> preset.name },
                "cardSize" to it.cardSize.name,
                "cardTransparency" to it.cardTransparency.name,
                "iconSize" to it.iconSize.name,
            )
        }
    },
    restore = { map ->
        fun <T : Enum<T>> enumOrDefault(values: Array<T>, name: Any?, default: T): T =
            (name as? String)?.let { n -> values.firstOrNull { it.name == n } } ?: default
        UiPreferences(
            theme = enumOrDefault(ThemeMode.entries.toTypedArray(), map["theme"], ThemeMode.DARK),
            accent = enumOrDefault(AccentColor.entries.toTypedArray(), map["accent"], AccentColor.ICE_BLUE),
            homeBackground = enumOrDefault(HomeBackground.entries.toTypedArray(), map["homeBackground"], HomeBackground.NIGHT),
            textSize = enumOrDefault(TextSize.entries.toTypedArray(), map["textSize"], TextSize.MEDIUM),
            language = enumOrDefault(AppLanguage.entries.toTypedArray(), map["language"], AppLanguage.SPANISH),
            showFpsOnHome = map["showFpsOnHome"] as? Boolean ?: true,
            showAchievementsOnHome = map["showAchievementsOnHome"] as? Boolean ?: true,
            sectionOrder = (map["sectionOrder"] as? List<*>)?.mapNotNull { item ->
                (item as? String)?.let { n -> HomeSection.entries.firstOrNull { it.name == n } }
            }?.takeIf { it.isNotEmpty() } ?: HomeSection.entries.toList(),
            hiddenPresets = (map["hiddenPresets"] as? List<*>)?.mapNotNull { item ->
                (item as? String)?.let { n -> GpuProfileId.entries.firstOrNull { it.name == n } }
            }?.toSet() ?: emptySet(),
            cardSize = enumOrDefault(CardSize.entries.toTypedArray(), map["cardSize"], CardSize.NORMAL),
            cardTransparency = enumOrDefault(CardTransparency.entries.toTypedArray(), map["cardTransparency"], CardTransparency.OPAQUE),
            iconSize = enumOrDefault(IconSize.entries.toTypedArray(), map["iconSize"], IconSize.MEDIUM),
        )
    },
)
@Composable
fun SettingsPersonalizationScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val saved by vm.uiFlow.collectAsState(initial = null)
    // R2-P2-27: rememberSaveable (con Saver propio) en vez de remember:
    // el borrador se perdía al rotar la pantalla.
    var draft by rememberSaveable(stateSaver = uiPreferencesDraftSaver) { mutableStateOf<UiPreferences?>(null) }
    val current = draft ?: saved
    val hasChanges = draft != null && saved != null && draft != saved
    fun update(transform: (UiPreferences) -> UiPreferences) {
        val base = draft ?: saved
        if (base != null) draft = transform(base)
    }
    SettingsCategoryScaffold(
        title = "Personalización",
        subtitle = "Apariencia del launcher y de la portada.",
        onBack = onBack,
        // R2-P1-10: al restablecer la categoría hay que vaciar el
        // borrador: antes el draft viejo seguía visible y "Aplicar"
        // re-aplicaba el reset. R2-P2-26: el botón del pie se etiqueta
        // "Restablecer solo esta pantalla" para distinguirlo del reset
        // de personalización + editor táctil de abajo.
        onResetCategory = { vm.resetCategory(SettingsCategory.PERSONALIZATION); draft = null },
        resetLabel = "Restablecer solo esta pantalla",
    ) {
        item {
            current?.let { ui ->
                PrimaryButton(
                    "Aplicar personalización",
                    onClick = { vm.saveUiPrefs(ui); draft = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasChanges,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { draft = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasChanges,
                ) { Text("Descartar cambios") }
                EnumChips("Tema", ThemeMode.entries, ui.theme, { it.label }) {
                    update { prefs -> prefs.copy(theme = it) }
                }
                EnumChips("Color de acento", AccentColor.entries, ui.accent, { it.label }) {
                    update { prefs -> prefs.copy(accent = it) }
                }
                EnumChips("Fondo de la pantalla principal", HomeBackground.entries, ui.homeBackground, { it.label }) {
                    update { prefs -> prefs.copy(homeBackground = it) }
                }
                EnumChips("Tamaño de texto", TextSize.entries, ui.textSize, { it.label }) {
                    update { prefs -> prefs.copy(textSize = it) }
                }
                EnumChips("Idioma", AppLanguage.entries, ui.language, { it.label }) {
                    update { prefs -> prefs.copy(language = it) }
                }
                NoteText("Idioma ES/EN: traduce la portada, la navegación y los títulos de sección. Las notas técnicas permanecen en español.")
                EnumChips("Tamaño de tarjetas", CardSize.entries, ui.cardSize, { it.label }) {
                    update { prefs -> prefs.copy(cardSize = it) }
                }
                EnumChips("Transparencia de tarjetas", CardTransparency.entries, ui.cardTransparency, { it.label }) {
                    update { prefs -> prefs.copy(cardTransparency = it) }
                }
                EnumChips("Tamaño de iconos", IconSize.entries, ui.iconSize, { it.label }) {
                    update { prefs -> prefs.copy(iconSize = it) }
                }
                NoteText("Iconos: hay un solo juego de iconos vectoriales. Este ajuste cambia su tamaño en la portada, no el diseño: no hay otro pack de iconos que elegir.")
                SettingSwitch("Mostrar FPS en la pantalla principal", "Muestra el FPS medio de la última prueba de 30 s en la portada.", ui.showFpsOnHome) {
                    update { prefs -> prefs.copy(showFpsOnHome = it) }
                }
                SettingSwitch("Mostrar logros en la pantalla principal", "Muestra el progreso X/27 en la portada.", ui.showAchievementsOnHome) {
                    update { prefs -> prefs.copy(showAchievementsOnHome = it) }
                }
                Text("Orden de secciones en la portada", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                // R2-P2-04: la ruta real es Ajustes → General →
                // Secciones (no "Configuración → Secciones").
                NoteText("Los botones de navegación ya no se muestran en la portada: viven en Ajustes → General → Secciones.")
                ui.sectionOrder.forEachIndexed { index, section ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${section.label}", modifier = Modifier.weight(1f))
                        // R2-P2-27: flechas deshabilitadas en los
                        // extremos: antes seguían activas y no hacían nada.
                        TextButton(
                            enabled = index > 0,
                            onClick = {
                                if (index > 0) {
                                    update { prefs ->
                                        val reordered = prefs.sectionOrder.toMutableList()
                                        val tmp = reordered[index - 1]; reordered[index - 1] = reordered[index]; reordered[index] = tmp
                                        prefs.copy(sectionOrder = reordered)
                                    }
                                }
                            },
                        ) { Text("↑") }
                        TextButton(
                            enabled = index < ui.sectionOrder.size - 1,
                            onClick = {
                                if (index < ui.sectionOrder.size - 1) {
                                    update { prefs ->
                                        val reordered = prefs.sectionOrder.toMutableList()
                                        val tmp = reordered[index + 1]; reordered[index + 1] = reordered[index]; reordered[index] = tmp
                                        prefs.copy(sectionOrder = reordered)
                                    }
                                }
                            },
                        ) { Text("↓") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.resetAllCustomization(); draft = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restablecer personalización y editor táctil") }
            } ?: Text("Cargando personalización…")
        }
    }
}
