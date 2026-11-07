package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import com.danielsnagustin.childoflight.launcher.model.trophyProgressPercent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logros: los 27 trofeos de la versión Vita.
 *
 * - Secretos: nombre y descripción ocultos ("Trofeo secreto / ???")
 *   hasta desbloquearlos.
 * - Progreso % y fecha de desbloqueo.
 * - "Through the Looking Glass" avisa que puede no desbloquearse sin
 *   la función online de Vita (enviar Oculi a un amigo).
 * - Son logros LOCALES: no se sincronizan con PSN ni con Steam.
 * - No hay platino: el juego no tiene platino en PS4 ni en Vita.
 * - El popup de desbloqueo lo muestra MainActivity (vm.justUnlocked)
 *   con sonido de notificación del sistema.
 *
 * En Etapa 1 el desbloqueo se marca desde aquí (tocando el trofeo) o
 * al importar progreso. La detección automática dentro de la partida
 * requiere el núcleo embebido (Etapa 2).
 */
@Composable
fun AchievementsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val progress by vm.achievementsFlow.collectAsState(initial = emptyList())
    val scan by vm.trophyScan.collectAsState()
    var selected by remember { mutableStateOf<TrophyProgress?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val unlocked = progress.count { it.unlocked }
    val percent = trophyProgressPercent(progress)

    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush()),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader("Logros", "27 trofeos de la versión Vita · $unlocked/27 desbloqueados ($percent%)")
        PremiumCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏆", fontSize = 28.sp)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        "$unlocked/27 desbloqueados ($percent%)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    GoldProgressBar(progress = if (progress.isEmpty()) 0f else unlocked / 27f)
                }
            }
        }
        NoteText("Logros LOCALES del launcher: no se sincronizan con PSN / PlayStation Network ni con Steam. No hay platino en este juego (ni en PS4 ni en Vita).")
        scan?.let { s ->
            NoteText("Vita3K (ux0/user/00/trophy/): ${s.note}")
        }
        Spacer(modifier = Modifier.height(8.dp))

        progress.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (item.unlocked) ColCardElevated else MaterialTheme.colorScheme.surfaceVariant,
                ),
                onClick = { selected = item },
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Text(if (item.unlocked) "🏆" else "🔒", style = MaterialTheme.typography.titleMedium)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            "${item.trophy.number}. ${item.displayName}",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(item.displayDescription, style = MaterialTheme.typography.bodySmall)
                        if (item.trophy.tier != null) Text("Grado: ${item.trophy.tier}", style = MaterialTheme.typography.bodySmall)
                        if (item.trophy.onlineRequired) {
                            Text("⚠ Puede no desbloquearse sin la función online de Vita (enviar Oculi a un amigo).", style = MaterialTheme.typography.bodySmall, color = ColGold)
                        }
                        item.unlockedAtMillis?.let { at ->
                            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(at))
                            Text("Desbloqueado: $date", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Borrar progreso de logros") }
        BackButton(onBack)
    }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.displayName) },
            text = {
                Column {
                    Text(item.displayDescription)
                    if (item.trophy.onlineRequired) Text("Puede no desbloquearse sin la función online de Vita.")
                    if (item.unlocked) Text("Ya desbloqueado. Puedes volver a bloquearlo si fue un error.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (item.unlocked) vm.lockTrophy(item.trophy.number) else vm.unlockTrophy(item.trophy.number)
                    selected = null
                }) { Text(if (item.unlocked) "Bloquear de nuevo" else "Marcar como desbloqueado") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cerrar") } },
        )
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("¿Borrar el progreso de logros?") },
            text = { Text("Se borran los 27 trofeos desbloqueados en este launcher. Es solo local: no afecta a PSN porque nunca se sincroniza.") },
            confirmButton = {
                TextButton(onClick = { vm.resetTrophies(); showResetConfirm = false }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancelar") } },
        )
    }
}
