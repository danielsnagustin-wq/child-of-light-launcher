package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle

/**
 * Categoría "Firmware" (Ola 2, Etapa 1). Flujo simple, 100% en el
 * teléfono: el usuario aporta su propio PSVUPDAT.PUP con el selector
 * de archivos (una sola vez, sin exigir nombre ni ruta), el launcher
 * lo guarda en su almacenamiento privado y lo instala en el núcleo
 * embebido. El archivo nunca sale del teléfono, nunca entra en el
 * repo ni en el APK y nunca se sube a ningún sitio.
 */
@Composable
fun SettingsFirmwareScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    // B3: el firmware se lee como flujo (antes llamada plana sin refresco).
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val firmwareInstalling by vm.firmwareInstallingFlow.collectAsState()
    val embeddedCore by vm.embeddedCoreFlow.collectAsState()
    // Ola 3, Etapa 7: estado de archivos (GameStatus + confirmación de la app externa).
    val status by vm.status.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)
    var showDeleteFirmwareConfirm by rememberSaveable { mutableStateOf(false) }
    val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importFirmware(uri)
    }
    SettingsCategoryScaffold(
        title = "Firmware",
        subtitle = "Tu copia del firmware, solo en tu teléfono.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.FIRMWARE) },
        dialogs = {
            if (showDeleteFirmwareConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteFirmwareConfirm = false },
                    title = { Text("¿Borrar el firmware?") },
                    text = { Text("Se borra el PUP guardado y el firmware instalado en el núcleo. El juego, los DLCs y los saves no se tocan. Para jugar con el núcleo embebido habrá que importar el PUP de nuevo.") },
                    confirmButton = {
                        TextButton(onClick = { vm.deleteFirmware(); showDeleteFirmwareConfirm = false }) { Text("Borrar") }
                    },
                    dismissButton = { TextButton(onClick = { showDeleteFirmwareConfirm = false }) { Text("Cancelar") } },
                )
            }
        },
    ) {
        item {
            SectionTitle("Firmware")
            Text("Firmware: ${FirmwareResolver.label(firmwareStatus)}", style = MaterialTheme.typography.bodyMedium)
            if (firmwareStatus is FirmwareStatus.Missing) {
                NoteText(FirmwareResolver.NO_FIRMWARE_MESSAGE)
            }
            OutlinedButton(onClick = { pickFirmware.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Importar firmware (.PUP, tu copia)") }
            if (firmwareInstalling) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                NoteText("Instalando el firmware en el núcleo embebido… puede tardar unos minutos. No cierres la app.")
            } else if (firmwareStatus is FirmwareStatus.Saved) {
                if (embeddedCore == true) {
                    OutlinedButton(onClick = { vm.installSavedFirmwareInCore() }, modifier = Modifier.fillMaxWidth()) { Text("Instalar en el núcleo embebido") }
                } else {
                    NoteText(
                        "El PUP ya está guardado en el teléfono. El núcleo embebido aún no está en esta versión: " +
                            "se instalará solo cuando llegue (al importar, al pulsar JUGAR o desde aquí). " +
                            "Mientras tanto, la app externa usa su propio firmware.",
                    )
                }
            }
            if (firmwareStatus is FirmwareStatus.Ready) {
                OutlinedButton(onClick = { showDeleteFirmwareConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Borrar firmware") }
            }
            NoteText(
                "Opción avanzada, nunca obligatoria: el primer uso solo pide juego + DLCs. " +
                    "Tu PUP se queda solo en tu teléfono: el launcher lo lee de ahí y lo instala en el núcleo embebido (Etapa 2). " +
                    "No tienes que enviarlo por chat ni subirlo a ningún sitio: se lee directamente de tu teléfono (Descargas o donde lo tengas guardado). " +
                    "La app externa no puede usarlo (cada app tiene su propio almacenamiento) y usa su propio firmware. " +
                    "El launcher no incluye firmware ni claves.",
            )
        }
        item {
            // Ola 3, Etapa 7: los 6 estados, leídos del disco. Guardado
            // no es instalado: el PUP guardado no cuenta como instalado
            // hasta que el núcleo lo instala de verdad.
            FileStateSection(
                status = status,
                firmwareStatus = firmwareStatus,
                pkgInstalledInVita3k = settings?.pkgInstalledInVita3k ?: false,
            )
        }
    }
}
