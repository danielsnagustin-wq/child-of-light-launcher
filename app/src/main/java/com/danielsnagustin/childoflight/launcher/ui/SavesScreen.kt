package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

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

    // R5-A2-3: fondo según el tema (antes el overload sin argumentos
    // fijaba dark=true y la pantalla quedaba oscura con Tema Claro).
    val uiPrefs = LocalUiPreferences.current
    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush(isDarkUi(uiPrefs), uiPrefs?.homeBackground?.hex)),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
    ) {
        ScreenHeader("Guardados y datos", "Sesiones, partidas guardadas y logros, en un solo lugar.")
        NoteText(
            "Las sesiones y las partidas guardadas de aquí viven en el almacenamiento privado del launcher: solo los usará el núcleo embebido. " +
                "Con la app externa, las partidas guardadas viven dentro de la app externa y cambiar de sesión aquí no cambia el juego.",
        )
        if (status.pkgInstalled && !status.gameExtracted) {
            NoteText(
                "El juego está como .pkg: las partidas guardadas viven dentro de la app externa, no en el launcher. " +
                    "Las sesiones, las partidas guardadas y los logros de aquí solo aplican con el juego extraído o el núcleo embebido.",
            )
        }
        val title = status.titleId?.id

        // Sesiones / partidas: la gestión de saves vive aquí, ampliada a
        // sesiones, no duplicada en otra pantalla.
        SessionsSection(vm)

        // R2-P2-03: sin literal "<TITLE_ID>" como placeholder: la
        // ruta solo se muestra cuando hay juego instalado; si no, la
        // nota de abajo ya dice que hay que instalarlo primero.
        if (title != null) {
            Text(
                "Carpeta de partidas guardadas (sesión activa, dentro del almacenamiento privado del launcher): ux0/user/00/savedata/$title",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (title == null) {
            NoteText("Instala primero el juego para ver sus guardados.")
        } else if (status.saves.isEmpty()) {
            NoteText("No hay partidas guardadas todavía. Aparecen aquí después de jugar y guardar en el juego.")
        } else {
            SectionTitle("Archivos de guardado (${status.saves.size})")
            status.saves.forEach { save ->
                key(save.name) {
                    Text("· ${save.name} — ${DownloadsCleanup.formatBytes(save.sizeBytes)}")
                }
            }
        }

        SectionTitle("Partidas guardadas")
        // Los saves del launcher solo existen con el juego extraído:
        // con un .pkg los saves viven dentro de Vita3K.
        OutlinedButton(
            onClick = { exportSaves.launch("child-of-light-saves.zip") },
            modifier = Modifier.fillMaxWidth(),
            enabled = status.gameExtracted && status.saves.isNotEmpty(),
        ) { Text("Exportar partidas guardadas (.zip)") }
        OutlinedButton(
            onClick = { importSaves.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = status.gameExtracted,
        ) { Text("Importar partidas guardadas (.zip)") }

        SectionTitle("Logros (junto con las partidas guardadas)")
        NoteText("El progreso de logros es local y se respalda en el mismo formato que se exporta aquí.")
        OutlinedButton(onClick = { exportTrophies.launch("child-of-light-logros.txt") }, modifier = Modifier.fillMaxWidth()) { Text("Exportar progreso de logros") }
        OutlinedButton(onClick = { importTrophies.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Importar progreso de logros") }

        SectionTitle("Borrar datos del juego")
        NoteText("Borra el juego, la actualización, los DLCs, las partidas guardadas y TODAS las sesiones de ${title ?: "Child of Light"}. Esta acción no se puede deshacer. Exporta tus partidas guardadas, sesiones y logros antes si los quieres conservar.")
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = status.gameInstalled,
        ) { Text("Borrar datos del juego…") }

        BackButton(onBack)
    }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("¿Borrar todos los datos del juego?") },
            text = { Text("Se borrarán el juego, la actualización, los DLCs, las partidas guardadas y TODAS las sesiones de ${status.titleId?.id ?: "Child of Light"}. Exporta antes tus partidas guardadas, sesiones y logros si los quieres conservar.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteGameData(); showDeleteConfirm = false }) { Text("Borrar todo") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } },
        )
    }
}
