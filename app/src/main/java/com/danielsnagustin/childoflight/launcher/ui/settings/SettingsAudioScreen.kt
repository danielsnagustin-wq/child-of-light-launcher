package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.ui.ColMutedText
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.PremiumCard
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSlider
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "Audio" (Ola 2, Etapa 4).
 *
 * "Corregir audio" ya no actúa a ciegas: antes de aplicar muestra un
 * diálogo con cada parámetro que va a cambiar (antes -> después),
 * guarda los valores anteriores y deja un botón "Deshacer" que los
 * restaura. Honesto en el diálogo: la caché de shaders limpiada no
 * se recupera, se vuelve a llenar sola al jugar.
 *
 */
@Composable
fun SettingsAudioScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val audioFixSnapshot by vm.audioFixSnapshot.collectAsState()
    var showFixPreview by rememberSaveable { mutableStateOf(false) }
    val s = settings
    SettingsCategoryScaffold(
        title = "Audio",
        subtitle = "Backend, NGS y volumen.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.AUDIO) },
        dialogs = {
            if (showFixPreview && s != null) {
                val changes = audioFixChanges(s)
                AlertDialog(
                    onDismissRequest = { showFixPreview = false },
                    title = { Text("¿Corregir audio?") },
                    text = {
                        Column {
                            Text("Se cambiarán estos ajustes:")
                            Spacer(modifier = Modifier.height(8.dp))
                            changes.forEach { change ->
                                val same = change.before == change.after
                                Text(
                                    "· ${change.label}: ${change.before} → ${change.after}" + if (same) " (ya está así)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (same) ColMutedText else Color.White,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "También se limpia la caché de shaders. Lo limpiado no se recupera: se vuelve a llenar sola al jugar (el stutter de la primera partida es normal). Tus valores anteriores quedan guardados y puedes deshacer el cambio después.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ColMutedText,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.fixAudio(); showFixPreview = false }) { Text("Corregir audio") }
                    },
                    dismissButton = { TextButton(onClick = { showFixPreview = false }) { Text("Cancelar") } },
                )
            }
        },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Audio")
                EnumChips("Backend de audio", AudioBackend.entries, s.audioBackend, { it.label }) { vm.saveSettings(s.copy(audioBackend = it)) }
                NoteText("SDL es el predeterminado desde v7 porque arregla ruido. Cubeb es la alternativa (v1.3: cubeb y SDL ya no deberían crepitar/cortar).")
                SettingSwitch("NGS (audio del sistema Vita)", "ngs-enable en config.yml. ON recomendado.", s.ngsEnable) { vm.saveSettings(s.copy(ngsEnable = it)) }
                SettingSlider("Volumen", s.audioVolume, 0..100, { vm.saveSettings(s.copy(audioVolume = it)) }) { "$it%" }
            }
            item {
                SectionTitle("Corregir audio")
                Button(onClick = { showFixPreview = true }, modifier = Modifier.fillMaxWidth()) { Text("Corregir audio") }
                NoteText("Muestra antes qué va a cambiar (SDL + NGS ON + caché de shaders ON y limpiada) y guarda tus valores para poder deshacer. Vuelve a lanzar el juego para aplicar el cambio.")
                if (audioFixSnapshot != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PremiumCard {
                        Text(
                            "Audio corregido: tus valores anteriores están guardados.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        NoteText("Deshacer restaura el backend, NGS y la caché de shaders de antes. La caché de shaders limpiada no se recupera: se vuelve a llenar sola al jugar.")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.undoFixAudio() }, modifier = Modifier.fillMaxWidth()) { Text("Deshacer") }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { vm.clearAudioFixSnapshot() }) { Text("Entendido, no quiero deshacer") }
                    }
                }
            }
        }
    }
}
