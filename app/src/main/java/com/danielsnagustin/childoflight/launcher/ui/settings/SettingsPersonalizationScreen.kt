package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.AccentColor
import com.danielsnagustin.childoflight.launcher.model.AppLanguage
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.HomeBackground
import com.danielsnagustin.childoflight.launcher.model.IconSize
import com.danielsnagustin.childoflight.launcher.model.TextSize
import com.danielsnagustin.childoflight.launcher.model.ThemeMode
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.ui.ColGold
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.GoldButton
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.PlayIcon
import com.danielsnagustin.childoflight.launcher.ui.PremiumCard
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Personalización" (Ola 2, Etapa 1; Ola 3, Etapa 6):
 * apariencia del launcher y de la portada, con VISTA PREVIA ANTES DE
 * APLICAR.
 *
 * La pantalla edita una COPIA LOCAL (borrador) de UiPreferences: tocar
 * una opción solo cambia el borrador y la vista previa en vivo. Nada
 * se guarda hasta pulsar "Aplicar personalización" (saveUiPrefs con el
 * borrador); "Descartar cambios" vuelve a lo guardado. El botón
 * "Restablecer toda la personalización" sigue siendo inmediato: es una
 * acción explícita de restablecimiento, no una opción.
 *
 * La vista previa es real, no una maqueta: usa PremiumCard con el
 * tamaño y la transparencia del borrador, el acento y el fondo del
 * borrador, y un icono vectorial real escalado al tamaño del borrador.
 * Sobre los iconos, honesto: existe UN solo juego de iconos
 * vectoriales consistente (ColIcons.kt); este ajuste cambia su
 * TAMAÑO, no el pack. No hay selector de paquetes de iconos.
 */
@Composable
fun SettingsPersonalizationScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val saved by vm.uiFlow.collectAsState(initial = null)
    var draft by remember { mutableStateOf<UiPreferences?>(null) }
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
        onResetCategory = { vm.resetCategory(SettingsCategory.PERSONALIZATION) },
    ) {
        item {
            current?.let { ui ->
                SectionTitle("Vista previa")
                NoteText("La vista previa se actualiza al cambiar cada opción. Nada se guarda hasta pulsar Aplicar.")
                val accentColor = runCatching { Color(android.graphics.Color.parseColor(ui.accent.hex)) }.getOrDefault(ColGold)
                PremiumCard(cardSize = ui.cardSize, cardTransparency = ui.cardTransparency) {
                    Text(
                        "Child of Light",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Los iconos dibujan su Canvas a 24 dp fijos
                        // (ColIcons.kt, sin tocar); la vista previa los
                        // escala de verdad al tamaño del borrador.
                        val iconScale = ui.iconSize.sizeDp / 24f
                        Box(
                            modifier = Modifier.size(ui.iconSize.sizeDp.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            PlayIcon(
                                modifier = Modifier.graphicsLayer(scaleX = iconScale, scaleY = iconScale),
                                tint = accentColor,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "JUGAR",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "27 logros",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ColGold).padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("JUGAR", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Muestra del botón dorado y de la tarjeta con el tema, el acento, el fondo, el tamaño y la transparencia del borrador.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                GoldButton(
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
                NoteText("Idioma ES/EN: traduce la portada, la navegación y los títulos de sección. Las notas técnicas permanecen en español en Etapa 1.")
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
                NoteText("Los botones de navegación ya no se muestran en la portada: viven en Configuración → Secciones.")
                ui.sectionOrder.forEachIndexed { index, section ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${section.label}", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (index > 0) {
                                update { prefs ->
                                    val reordered = prefs.sectionOrder.toMutableList()
                                    val tmp = reordered[index - 1]; reordered[index - 1] = reordered[index]; reordered[index] = tmp
                                    prefs.copy(sectionOrder = reordered)
                                }
                            }
                        }) { Text("↑") }
                        TextButton(onClick = {
                            if (index < ui.sectionOrder.size - 1) {
                                update { prefs ->
                                    val reordered = prefs.sectionOrder.toMutableList()
                                    val tmp = reordered[index + 1]; reordered[index + 1] = reordered[index]; reordered[index] = tmp
                                    prefs.copy(sectionOrder = reordered)
                                }
                            }
                        }) { Text("↓") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.resetAllCustomization(); draft = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restablecer toda la personalización") }
            } ?: Text("Cargando personalización…")
        }
    }
}
