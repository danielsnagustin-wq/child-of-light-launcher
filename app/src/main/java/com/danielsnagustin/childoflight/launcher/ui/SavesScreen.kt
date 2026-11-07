package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Guardados y datos del juego.
 * Ruta real: ux0/user/00/savedata/<TITLE_ID>/ dentro del vita_fs.
 * Exportar/importar saves por SAF (zip) y exportar/importar el
 * progreso de logros junto con los saves. Borrar datos pide
 * confirmación y borra juego + update + DLCs + saves.
 */
@Composable
fun SavesScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val status by vm.status.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val exportSaves = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportSaves(uri)
    }
    val importSaves = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importSaves(uri)
    }
    val exportTrophies = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportAchievements(uri)
    }
    val importTrophies = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importAchievements(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Guardados y datos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val title = status.titleId?.id

        // Sesiones / partidas: la gestión de saves vive aquí, ampliada a
        // sesiones, no duplicada en otra pantalla.
        SessionsSection(vm)

        Text(
            "Carpeta de saves (sesión activa): ux0/user/00/savedata/${title ?: "<TITLE_ID>"}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (title == null) {
            NoteText("Instala primero el juego para ver sus guardados.")
        } else if (status.saves.isEmpty()) {
            NoteText("No hay saves todavía. Aparecen aquí después de jugar y guardar en el juego.")
        } else {
            SectionTitle("Archivos de guardado (${status.saves.size})")
            status.saves.forEach { save ->
                Text("· ${save.name} — ${save.sizeBytes} bytes")
            }
        }

        SectionTitle("Saves")
        OutlinedButton(
            onClick = { exportSaves.launch("child-of-light-saves.zip") },
            modifier = Modifier.fillMaxWidth(),
            enabled = title != null && status.saves.isNotEmpty(),
        ) { Text("Exportar saves (.zip)") }
        OutlinedButton(
            onClick = { importSaves.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = title != null,
        ) { Text("Importar saves (.zip)") }

        SectionTitle("Logros (junto con los saves)")
        NoteText("El progreso de logros es local y se respalda en el mismo formato que se exporta aquí.")
        OutlinedButton(onClick = { exportTrophies.launch("child-of-light-logros.txt") }, modifier = Modifier.fillMaxWidth()) { Text("Exportar progreso de logros") }
        OutlinedButton(onClick = { importTrophies.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Importar progreso de logros") }

        SectionTitle("Borrar datos del juego")
        NoteText("Borra el juego, el update, los DLCs, los saves y TODAS las sesiones de ${title ?: "Child of Light"}. Esta acción no se puede deshacer. Exporta tus saves, sesiones y logros antes si los quieres conservar.")
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = status.gameInstalled,
        ) { Text("Borrar datos del juego…") }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("¿Borrar todos los datos del juego?") },
            text = { Text("Se borrarán el juego, el update, los DLCs, los saves y TODAS las sesiones de ${status.titleId?.id ?: "Child of Light"}. Exporta antes tus saves, sesiones y logros si los quieres conservar.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteGameData(); showDeleteConfirm = false }) { Text("Borrar todo") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } },
        )
    }
}
