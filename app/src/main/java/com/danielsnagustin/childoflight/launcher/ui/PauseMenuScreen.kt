package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.SessionManager
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import kotlinx.coroutines.launch

/**
 * Contenido del menú de pausa (Compose, AMOLED #10131D + azul hielo).
 * El juego está en pausa mientras se ve: Continuar o atrás lo cierran
 * y el juego sigue. Los ajustes rápidos se guardan y se aplican al
 * reiniciar el juego (el núcleo lee config.yml al arrancar).
 */
@Composable
fun PauseMenuScreen(
    settings: LauncherSettings?,
    sessionManager: SessionManager,
    settingsRepo: SettingsRepository,
    sessionsReady: Boolean = true,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var quickSettingsOpen by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Pausa",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = ColAccent,
            )
            Text(
                "El juego está en pausa.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB8C2D4),
            )
            Spacer(modifier = Modifier.height(4.dp))
            PrimaryButton("Continuar", onClick = onContinue, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = sessionManager.backupActiveSession()
                        saveMessage = if (ok) {
                            "Sesión guardada."
                        } else {
                            "No se pudo guardar la sesión."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                // R2-P2-10: deshabilitado hasta que refresh() complete
                // (sessionsReady): antes se podía pulsar con las
                // sesiones aún sin cargar.
                enabled = sessionsReady,
            ) { Text("Guardar sesión") }
            if (!sessionsReady) {
                Text("Cargando sesiones…", style = MaterialTheme.typography.bodySmall, color = ColAccent)
            }
            saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ColAccent) }
            TextButton(onClick = { quickSettingsOpen = !quickSettingsOpen }) {
                Text(if (quickSettingsOpen) "Ajustes rápidos ▴" else "Ajustes rápidos ▾")
            }
            if (quickSettingsOpen) {
                if (settings == null) {
                    Text("Cargando ajustes…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    EnumChips(
                        title = "Calidad",
                        options = listOf(1f, 2f),
                        selected = settings.resolutionMultiplier,
                        label = { "${it.toInt()}x" },
                        onSelect = { value ->
                            scope.launch { settingsRepo.save(settings.copy(resolutionMultiplier = value)) }
                        },
                    )
                    SettingSwitch(
                        "Modo 60 FPS (opcional)",
                        "Intenta que el juego corra a 60 fps. Puede no hacer nada o ir al doble de velocidad.",
                        settings.fpsHack,
                    ) { checked ->
                        scope.launch { settingsRepo.save(settings.copy(fpsHack = checked)) }
                    }
                    NoteText("Los cambios de calidad y FPS se aplican al reiniciar el juego.")
                }
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text("Salir al inicio")
            }
            NoteText("Atrás o Continuar para seguir jugando.")
        }
    }
}
