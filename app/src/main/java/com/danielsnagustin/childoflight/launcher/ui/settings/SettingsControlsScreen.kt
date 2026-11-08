package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSlider
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Controles y HUD" (Ola 2, Etapa 1): ajustes GENERALES del
 * overlay táctil dentro del juego. Los botones, posiciones, tamaños y
 * opacidad POR BOTÓN se editan en "Controles táctiles" (General →
 * Secciones): lo que se ve en su vista previa es lo que aparece
 * sobre el juego.
 */
@Composable
fun SettingsControlsScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val controlProfiles by vm.profilesFlow.collectAsState(initial = emptyList())
    val activeControlProfileId by vm.activeProfileIdFlow.collectAsState(initial = "default-g99")
    val s = settings
    SettingsCategoryScaffold(
        title = "Controles y HUD",
        subtitle = "Overlay táctil dentro del juego.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.CONTROLS) },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Controles en el juego")
                SettingSwitch(
                    "Mostrar controles táctiles en el juego",
                    "El overlay con tus botones aparece sobre el juego embebido. OFF = juegas solo con mando físico.",
                    s.showTouchOverlayInGame,
                ) { vm.saveSettings(s.copy(showTouchOverlayInGame = it)) }
                SettingSlider(
                    "Opacidad general del overlay",
                    (s.touchOverlayOpacity * 100).toInt(),
                    10..100,
                    { vm.saveSettings(s.copy(touchOverlayOpacity = it / 100f)) },
                ) { "$it%" }
                SettingSwitch(
                    "Brillo neón",
                    "Halo azul hielo alrededor de los botones. En AMOLED #10131D se ve premium; OFF = botones planos.",
                    s.touchOverlayNeonGlow,
                ) { vm.saveSettings(s.copy(touchOverlayNeonGlow = it)) }
                SettingSwitch(
                    "Ocultar controles táctiles si hay mando conectado",
                    "Con un mando físico conectado, el overlay no estorba en el juego. En el editor se sigue viendo para poder editarlo.",
                    s.hideTouchWhenGamepadConnected,
                ) { vm.saveSettings(s.copy(hideTouchWhenGamepadConnected = it)) }
                NoteText("Ocultar el overlay en un perfil concreto (p. ej. jugar solo con mando en \"Mando externo + táctil mínimo\" y con táctil en el resto):")
                controlProfiles.forEach { profile ->
                    val hidden = profile.id in s.touchOverlayHiddenProfileIds
                    SettingSwitch(
                        "Sin overlay en \"${profile.name}\"",
                        if (profile.id == activeControlProfileId) "Este es el perfil activo." else "El overlay no aparece en el juego con este perfil.",
                        hidden,
                    ) { checked ->
                        val updated = if (checked) {
                            s.touchOverlayHiddenProfileIds + profile.id
                        } else {
                            s.touchOverlayHiddenProfileIds - profile.id
                        }
                        vm.saveSettings(s.copy(touchOverlayHiddenProfileIds = updated))
                    }
                }
            }
        }
    }
}
