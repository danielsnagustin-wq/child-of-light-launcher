package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.danielsnagustin.childoflight.launcher.model.GameSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sesiones / partidas múltiples.
 *
 * Se muestra como sección dentro de SavesScreen (no es una pantalla
 * aparte incompatible): los saves y las sesiones son lo mismo desde
 * el punto de vista del usuario, y la sesión activa es la que está
 * en la carpeta viva ux0/user/00/savedata/<TITLE_ID>/.
 *
 * - "Nueva sesión": snapshot automático de la actual y luego una
 *   sesión vacía (nuevo juego sin perder la anterior).
 * - "Continuar": guarda la actual y carga la seleccionada.
 * - Por sesión: renombrar (+ capítulo manual), duplicar, exportar,
 *   importar, borrar (con confirmación; nunca la única sin avisar).
 * - La sesión activa se marca con ★ ACTIVA.
 */
@Composable
fun SessionsSection(vm: LauncherViewModel) {
    val sessions by vm.sessionsFlow.collectAsState(initial = emptyList())
    val activeId by vm.activeSessionIdFlow.collectAsState(initial = null)
    val status by vm.status.collectAsState()

    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<GameSession?>(null) }
    var renameName by remember { mutableStateOf("") }
    var renameChapter by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<GameSession?>(null) }
    var exportTarget by remember { mutableStateOf<GameSession?>(null) }

    val exportSession = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val target = exportTarget
        if (uri != null && target != null) vm.exportSession(uri, target)
        exportTarget = null
    }
    val importSession = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importSession(uri)
    }

    SectionTitle("Sesiones / partidas")
    NoteText(
        "En Vita el guardado es una sola carpeta, así que cada sesión guarda su propia copia " +
            "en sessions/<sesión>/. La activa está en la carpeta viva. Antes de crear una sesión " +
            "nueva o de cambiar, se guarda automáticamente la actual: nunca se sobrescribe sin avisar.",
    )
    if (status.titleId == null && sessions.isEmpty()) {
        NoteText("Instala primero el juego para crear tu primera sesión (se crea sola como «Partida 1» al importar).")
    }
    sessions.forEach { session ->
        val isActive = session.id == activeId
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    session.name + if (isActive) "  ★ ACTIVA" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("Juego: ${session.titleId.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Creada: ${formatDate(session.createdAtMillis)} · Última vez: " +
                        if (session.lastPlayedAtMillis == 0L) "nunca" else formatDate(session.lastPlayedAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Tiempo jugado: ${session.playTimeLabel()}", style = MaterialTheme.typography.bodyMedium)
                if (session.chapter.isNotBlank()) {
                    Text("Capítulo: ${session.chapter}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Saves: ${session.savedataFiles} archivos (${session.savedataBytes} bytes) · Logros: ${session.unlockedTrophies}/27",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.switchSession(session.id) },
                        modifier = Modifier.weight(1f),
                        enabled = !isActive,
                    ) { Text(if (isActive) "En curso" else "Continuar") }
                    OutlinedButton(
                        onClick = {
                            renameTarget = session
                            renameName = session.name
                            renameChapter = session.chapter
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Renombrar") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.duplicateSession(session.id) }, modifier = Modifier.weight(1f)) { Text("Duplicar") }
                    OutlinedButton(
                        onClick = { exportTarget = session; exportSession.launch("col-sesion-${safeFileName(session.name)}.zip") },
                        modifier = Modifier.weight(1f),
                    ) { Text("Exportar") }
                    OutlinedButton(onClick = { deleteTarget = session }, modifier = Modifier.weight(1f)) { Text("Borrar") }
                }
            }
        }
    }

    OutlinedButton(onClick = { showNewDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Nueva sesión (nuevo juego)") }
    OutlinedButton(onClick = { importSession.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Importar sesión (.zip)") }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("Nueva sesión") },
            text = {
                Column {
                    Text("Se guardará automáticamente la sesión actual (saves + logros + controles) y la nueva empezará vacía, desde cero. La anterior no se borra.")
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nombre (p. ej. Partida 2)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createSession(newName.ifBlank { "Partida ${sessions.size + 1}" })
                    newName = ""
                    showNewDialog = false
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("Cancelar") } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar sesión") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameName,
                        onValueChange = { renameName = it },
                        label = { Text("Nombre") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = renameChapter,
                        onValueChange = { renameChapter = it },
                        label = { Text("Capítulo / progreso (manual)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "El capítulo es manual: los saves de Vita son binarios y el launcher no lo lee del save.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameSession(target.id, renameName, renameChapter)
                    renameTarget = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } },
        )
    }

    deleteTarget?.let { target ->
        val onlyOne = sessions.size <= 1
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("¿Borrar la sesión «${target.name}»?") },
            text = {
                Text(
                    if (onlyOne) {
                        "Es la ÚNICA sesión que queda. Se borrarán sus saves guardados y sus logros de esa sesión. " +
                            "Expórtala antes si la quieres conservar."
                    } else if (target.id == activeId) {
                        "Es la sesión ACTIVA. Se guardará primero lo jugado hasta ahora y luego se borrará su copia; " +
                            "se activará otra sesión."
                    } else {
                        "Se borrarán sus saves guardados y sus logros de esa sesión. La sesión activa no se toca."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteSession(target.id); deleteTarget = null }) { Text("Borrar sesión") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
        )
    }
}

private fun formatDate(millis: Long): String {
    return try {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))
    } catch (_: Exception) {
        "—"
    }
}

private fun safeFileName(name: String): String {
    val clean = name.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }.joinToString("")
    return clean.ifBlank { "sesion" }.take(30)
}
