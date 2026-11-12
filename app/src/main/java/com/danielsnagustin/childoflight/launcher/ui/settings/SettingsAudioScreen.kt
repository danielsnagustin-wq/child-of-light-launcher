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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.PremiumCard
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSlider
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch
import com.danielsnagustin.childoflight.launcher.ui.cardTextColor
import com.danielsnagustin.childoflight.launcher.ui.isDarkUi
import com.danielsnagustin.childoflight.launcher.ui.mutedTextColor
import com.danielsnagustin.childoflight.launcher.ui.LocalUiPreferences

/**
 * Categoría "Audio" (Ola 2, Etapa 4).
 *
 * "Corregir audio" ya no actúa a ciegas: antes de aplicar muestra un
 * diálogo con cada parámetro que va a cambiar (antes -> después),
 * guarda los valores anteriores y deja un botón "Deshacer" que los
 * restaura. Ronda 0.2.64 (H-1 tiempo de carga): ya NO limpia la caché
 * de shaders — el audio no tiene nada que ver con ella y borrarla
 * dejaba el siguiente arranque recompilando todo (~2 min). La
 * limpieza sigue existiendo como botón aparte en Rendimiento, con su
 * aviso.
 *
 */
@Composable
fun SettingsAudioScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val audioFixSnapshot by vm.audioFixSnapshot.collectAsState()
    var showFixPreview by rememberSaveable { mutableStateOf(false) }
    val s = settings
    // R4-P1-14: textos segun el tema (en Claro, blanco sobre fondo claro no se lee).
    val dark = isDarkUi(LocalUiPreferences.current)
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
                                // Ronda 0.2.65 (A-P2-3): la fila "Caché de
                                // shaders" es el AJUSTE (activar su uso),
                                // no borrar archivos: se etiqueta como
                                // tal para que no se contradiga con el
                                // "no se borra la caché" de abajo.
                                val displayLabel = if (change.label == "Caché de shaders") "Caché de shaders (ajuste)" else change.label
                                Text(
                                    "· $displayLabel: ${change.before} → ${change.after}" + if (same) " (ya está así)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (same) mutedTextColor(dark) else cardTextColor(dark),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "«Caché de shaders (ajuste)» solo activa su uso: no se borra la caché guardada. La limpieza es un botón aparte en Rendimiento. Tus valores anteriores quedan guardados y puedes deshacer el cambio después.",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor(dark),
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
                // R2-P2-09: sin referencias de versión incomprensibles
                // ("v7"/"v1.3", versiones internas sin verificar): en
                // lenguaje de usuario, SDL es el recomendado y Cubeb la
                // alternativa. El audio funciona en Mali igual que en
                // el resto: lo que cambia es el backend recomendado,
                // no si suena o no.
                NoteText("SDL es el predeterminado porque arregla el ruido. Cubeb es la alternativa si SDL te da problemas: en las versiones actuales ambos deberían sonar sin crepitaciones ni cortes.")
                SettingSwitch("NGS (audio del sistema Vita)", "ngs-enable en config.yml. ON recomendado.", s.ngsEnable) { vm.saveSettings(s.copy(ngsEnable = it)) }
                // Ronda 0.2.66 (AUD-P2-3): con NGS OFF el núcleo
                // devuelve OK sin inicializar nada (pin
                // SceNgs.cpp:403-417) y un juego que depende de NGS
                // puede quedarse sin sonido o colgarse. El cambio
                // exige relanzar el juego (se lee al iniciar el núcleo).
                if (!s.ngsEnable) {
                    NoteText("⚠ Con NGS apagado, los juegos que dependen del audio del sistema Vita pueden quedarse sin sonido o colgarse: el núcleo devuelve OK sin inicializar nada. Vuelve a lanzar el juego para aplicar el cambio.")
                }
                SettingSlider("Volumen", s.audioVolume, 0..100, { vm.saveSettings(s.copy(audioVolume = it)) }) { "$it%" }
            }
            item {
                SectionTitle("Corregir audio")
                Button(onClick = { showFixPreview = true }, modifier = Modifier.fillMaxWidth()) { Text("Corregir audio") }
                NoteText("Muestra antes qué va a cambiar (SDL + NGS ON + ajuste de caché de shaders ON) y guarda tus valores para poder deshacer. No borra la caché guardada (eso es un botón aparte en Rendimiento). Vuelve a lanzar el juego para aplicar el cambio.")
                if (audioFixSnapshot != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PremiumCard {
                        Text(
                            "Audio corregido: tus valores anteriores están guardados.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cardTextColor(dark),
                        )
                        NoteText("Deshacer restaura el backend, NGS y el ajuste de caché de shaders de antes.")
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
