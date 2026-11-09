package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.ui.ColTurquoise
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.PremiumCard
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SecondaryButton
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.cardTextColor
import com.danielsnagustin.childoflight.launcher.ui.isDarkUi
import com.danielsnagustin.childoflight.launcher.ui.mutedTextColor
import com.danielsnagustin.childoflight.launcher.ui.rememberUiPreferences

/**
 * Categoría "Almacenamiento" (Ola 2, Etapa 1): todo vive en la data
 * interna; Descargas solo es la entrega explícita. Incluye el enlace
 * a "Limpiar no usados" (botones ocultos, perfiles y presets sin
 * usar, Turnip sin usar, DLCs y saves).
 */
@Composable
fun SettingsStorageScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val downloadsCopies by vm.downloadsCopies.collectAsState()
    // Ola 3, Etapa 7: estado de archivos (GameStatus + firmware).
    val status by vm.status.collectAsState()
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)
    var showDeleteDownloadsConfirm by rememberSaveable { mutableStateOf(false) }
    // R4-P1-14: textos segun el tema (en Claro, blanco sobre fondo claro no se lee).
    val dark = isDarkUi(rememberUiPreferences())
    // R4-P1-12: pedir la lista de Descargas/ChildOfLight/ al entrar:
    // la query de MediaStore ya solo corre en el primer refresco, y sin
    // esto los archivos metidos desde otra app no se verian hasta el
    // proximo arranque. LaunchedEffect(Unit) corre una sola vez al
    // entrar, no en cada recomposicion.
    LaunchedEffect(Unit) { vm.refreshDownloadsCopiesNow() }
    SettingsCategoryScaffold(
        title = "Almacenamiento",
        subtitle = "Dónde vive cada cosa y qué se puede borrar.",
        onBack = onBack,
        dialogs = {
            if (showDeleteDownloadsConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteDownloadsConfirm = false },
                    title = { Text("¿Borrar las copias de Descargas?") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                "Se borrarán estos archivos de Descargas/ChildOfLight/:\n\n" +
                                    DownloadsCleanup.summarize(downloadsCopies) +
                                    "\n\nEl juego, los DLCs y la licencia del almacenamiento interno del launcher NO se tocan.\n\n" +
                                    "Si aún no instalaste el juego en la app externa, tendrás que volver a copiarlo («Copiar a la app externa»).",
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.deleteDownloadsCopies(); showDeleteDownloadsConfirm = false }) { Text("Borrar") }
                    },
                    dismissButton = { TextButton(onClick = { showDeleteDownloadsConfirm = false }) { Text("Cancelar") } },
                )
            }
        },
    ) {
        item {
            SectionTitle("Almacenamiento")
            PremiumCard {
                Text(
                    "El juego, los DLCs, los updates y la licencia viven en el almacenamiento interno del launcher (privado, sin carpeta visible en Descargas ni en el almacenamiento compartido).",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cardTextColor(dark),
                )
                // R2-P2-18: sin "Cuando el núcleo embebido esté listo…":
                // el núcleo YA está embebido. Estado real: con núcleo,
                // el juego se instala solo al pulsar JUGAR y no hace
                // falta copiar nada a Descargas.
                NoteText("La única copia en Descargas es la entrega explícita a la app externa («Copiar a la app externa», con confirmación antes de copiar). Con el núcleo embebido, el juego se instala solo al pulsar JUGAR y no hace falta copiar nada.")
                Spacer(modifier = Modifier.height(12.dp))
                if (downloadsCopies.isEmpty()) {
                    Text("Descargas/ChildOfLight/: vacía ✓", style = MaterialTheme.typography.bodyMedium, color = ColTurquoise)
                } else {
                    Text(
                        "Copias en Descargas/ChildOfLight/:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cardTextColor(dark),
                    )
                    Text(
                        DownloadsCleanup.summarize(downloadsCopies),
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedTextColor(dark),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SecondaryButton("Borrar copias de Descargas", onClick = { showDeleteDownloadsConfirm = true })
                    NoteText("Borra solo las copias de Descargas/ChildOfLight/. El juego, los DLCs y la licencia del almacenamiento interno no se tocan.")
                }
            }
        }
        item {
            SectionTitle("Limpiar no usados")
            OutlinedButton(onClick = { onNavigate(Screen.CLEANUP) }, modifier = Modifier.fillMaxWidth()) { Text("Limpiar no usados…") }
            NoteText("Botones ocultos, perfiles sin usar, presets sin usar, Turnip sin usar, DLCs y saves.")
        }
        item {
            // P2-13: "Sesiones y guardados" vive aquí (Almacenamiento),
            // no en General: son los guardados del juego. El plan de
            // dedup lo decía así.
            SectionTitle("Secciones")
            PremiumCard {
                SecondaryButton("Sesiones y guardados", onClick = { onNavigate(Screen.SAVES) })
            }
        }
        item {
            // Ola 3, Etapa 7: los 6 estados, leídos del disco. Guardado
            // no es instalado: un .pkg guardado no es un juego
            // instalado, y un PUP guardado no es firmware instalado.
            FileStateSection(
                status = status,
                firmwareStatus = firmwareStatus,
                pkgInstalledInVita3k = settings?.pkgInstalledInVita3k ?: false,
            )
        }
    }
}
