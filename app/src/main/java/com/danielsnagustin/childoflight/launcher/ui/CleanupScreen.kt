package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId

/**
 * "Limpiar no usados": lista lo que no se usa y permite borrarlo.
 * - Botones táctiles ocultos (se borran definitivamente del layout).
 * - Perfiles de control que no son el activo.
 * - Presets de GPU que no son el activo (se OCULTAN de la lista, no se
 *   borra código: los presets son predefinidos; "Mostrar todos" los recupera).
 * - Driver Turnip instalado pero sin usar (driver actual = Sistema).
 * - DLCs instalados (borra de ux0/addcont/<TITLE>/<DLC>/).
 * - Saves (borra solo los saves, sin tocar juego/DLCs).
 * El borrado en lote pide confirmación y dice exactamente qué borra.
 */
@Composable
fun CleanupScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val layout by vm.touchLayoutFlow.collectAsState(initial = emptyList())
    val profiles by vm.profilesFlow.collectAsState(initial = emptyList())
    val activeProfileId by vm.activeProfileIdFlow.collectAsState(initial = "default-g99")
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    val status by vm.status.collectAsState()
    var showBatchConfirm by rememberSaveable { mutableStateOf(false) }
    var deleteDlcTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteSavesConfirm by rememberSaveable { mutableStateOf(false) }

    val hiddenButtons = layout.filter { !it.visible }
    val unusedProfiles = profiles.filter { it.id != activeProfileId }
    val unusedPresets = GpuProfileId.entries.filter {
        it != settings?.activeProfile && it !in (uiPrefs?.hiddenPresets ?: emptySet())
    }
    val turnipUnused = settings?.turnipInstalled == true &&
        settings?.gpuDriver?.name == "SYSTEM"

    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush()),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
    ) {
        ScreenHeader("Limpiar no usados", "Todo lo que se borra aquí se puede volver a crear o importar, salvo DLCs y saves.")

        SectionTitle("Botones táctiles ocultos (${hiddenButtons.size})")
        if (hiddenButtons.isEmpty()) Text("Ninguno: todos los botones están visibles.")
        hiddenButtons.forEach { control ->
            OutlinedButton(
                onClick = { vm.deleteTouchControl(control.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("🗑 Borrar definitivamente: ${control.label}") }
        }

        SectionTitle("Perfiles de control sin usar (${unusedProfiles.size})")
        if (unusedProfiles.isEmpty()) Text("Ninguno: solo queda el perfil activo.")
        unusedProfiles.forEach { profile ->
            OutlinedButton(
                onClick = { vm.deleteControlProfile(profile.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("🗑 Borrar perfil: ${profile.name}") }
        }

        SectionTitle("Presets de GPU sin usar (${unusedPresets.size})")
        NoteText("Ocultarlos de la lista de presets. No se borra código; \"Mostrar todos\" los recupera.")
        unusedPresets.forEach { preset ->
            OutlinedButton(
                onClick = { vm.hideGpuPreset(preset) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("Ocultar preset: ${preset.label}") }
        }
        OutlinedButton(onClick = { vm.showAllGpuPresets() }, modifier = Modifier.fillMaxWidth()) { Text("Mostrar todos los presets") }

        SectionTitle("Driver Turnip")
        if (turnipUnused) {
            Text("Turnip instalado (${settings?.turnipVersion}) pero no se usa: el driver actual es Sistema.", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { vm.deleteTurnipDriver() }, modifier = Modifier.fillMaxWidth()) { Text("🗑 Borrar driver Turnip") }
        } else if (settings?.turnipInstalled == true) {
            Text("Turnip en uso (${settings?.turnipVersion}). No se marca como no usado.")
        } else {
            Text("No hay driver Turnip instalado.")
        }

        SectionTitle("DLCs instalados (${status.dlcCount})")
        if (status.dlcs.isEmpty()) Text("Ninguno.")
        status.dlcs.forEach { dlc ->
            OutlinedButton(
                onClick = { deleteDlcTarget = dlc.dlcId },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("🗑 Borrar DLC: ${dlc.dlcId} (${dlc.sizeBytes} bytes)") }
        }

        SectionTitle("Saves (${status.saves.size} archivos)")
        if (status.saves.isEmpty()) Text("No hay saves.")
        OutlinedButton(
            onClick = { showDeleteSavesConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = status.saves.isNotEmpty(),
        ) { Text("🗑 Borrar saves (el juego y los DLCs siguen)") }

        SectionTitle("Borrar en lote")
        OutlinedButton(onClick = { showBatchConfirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Borrar todo lo no usado de esta pantalla")
        }
        NoteText("En lote: borra botones ocultos, perfiles sin usar, oculta presets sin usar y borra el Turnip sin usar. NO borra DLCs ni saves: esos siempre se borran uno a uno con confirmación.")

        BackButton(onBack)
    }
    }

    if (showBatchConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchConfirm = false },
            title = { Text("¿Borrar todo lo no usado?") },
            text = {
                Text(
                    "Se borrarán: ${hiddenButtons.size} botones ocultos, ${unusedProfiles.size} perfiles sin usar, " +
                        "${unusedPresets.size} presets se ocultarán, y el Turnip sin usar si lo hay. " +
                        "DLCs y saves NO se tocan en lote.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    hiddenButtons.forEach { vm.deleteTouchControl(it.id) }
                    unusedProfiles.forEach { vm.deleteControlProfile(it.id) }
                    unusedPresets.forEach { vm.hideGpuPreset(it) }
                    if (turnipUnused) vm.deleteTurnipDriver()
                    showBatchConfirm = false
                }) { Text("Borrar todo") }
            },
            dismissButton = { TextButton(onClick = { showBatchConfirm = false }) { Text("Cancelar") } },
        )
    }
    deleteDlcTarget?.let { dlcId ->
        val isPkgDlc = status.dlcs.firstOrNull { it.dlcId == dlcId }?.isPkg == true
        AlertDialog(
            onDismissRequest = { deleteDlcTarget = null },
            title = { Text("¿Borrar el DLC $dlcId?") },
            text = {
                Text(
                    if (isPkgDlc) {
                        "Se borra el .pkg guardado (ux0/pkg/dlc/$dlcId.pkg) y su licencia. Solo vuelve si importas de nuevo tu copia del DLC."
                    } else {
                        "Se borra de ux0/addcont/${status.titleId?.id ?: "—"}/$dlcId/. Solo vuelve si importas de nuevo tu copia del DLC."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteDlc(dlcId); deleteDlcTarget = null }) { Text("Borrar DLC") }
            },
            dismissButton = { TextButton(onClick = { deleteDlcTarget = null }) { Text("Cancelar") } },
        )
    }
    if (showDeleteSavesConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSavesConfirm = false },
            title = { Text("¿Borrar los saves?") },
            text = { Text("Se borran todos los saves de ${status.titleId?.id}. El juego y los DLCs siguen instalados.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSavesOnly(); showDeleteSavesConfirm = false }) { Text("Borrar saves") }
            },
            dismissButton = { TextButton(onClick = { showDeleteSavesConfirm = false }) { Text("Cancelar") } },
        )
    }
}
