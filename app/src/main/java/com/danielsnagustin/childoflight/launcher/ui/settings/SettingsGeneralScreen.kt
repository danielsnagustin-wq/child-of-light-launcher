package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.ui.ColTurquoise
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.PremiumCard
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SecondaryButton
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch

/**
 * Categoría "General" (Ola 2, Etapa 1): tarjeta compacta del perfil
 * de rendimiento, presets, enlaces a secciones, motor y aviso legal.
 * "Ver config.yml" y "Restablecer" también viven aquí.
 */
@Composable
fun SettingsGeneralScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    val externalVita3k by vm.externalVita3k.collectAsState()
    val configText by vm.generatedConfig.collectAsState()
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    // Ola 3, Etapa 9a: copia de seguridad de ajustes (JSON por SAF).
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSettingsBackup(uri)
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importSettingsBackup(uri)
    }

    val s = settings
    SettingsCategoryScaffold(
        title = "General",
        subtitle = "Perfil, presets y lo principal del launcher.",
        onBack = onBack,
        onResetCategory = { vm.resetCategory(SettingsCategory.GENERAL) },
        dialogs = {
            if (showConfig) {
                AlertDialog(
                    onDismissRequest = { showConfig = false },
                    title = { Text("config.yml generado") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(configText, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = { showConfig = false }) { Text("Cerrar") } },
                )
            }
            if (showResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showResetConfirm = false },
                    title = { Text("¿Restablecer ajustes?") },
                    text = { Text("Se aplicará el preset recomendado para tu GPU y se perderán tus cambios manuales.") },
                    confirmButton = {
                        TextButton(onClick = { vm.resetSettings(); showResetConfirm = false }) { Text("Restablecer") }
                    },
                    dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancelar") } },
                )
            }
        },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Perfil de rendimiento")
                PremiumCard(
                    cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                    cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                ) {
                    Text(
                        "Perfil activo: ${s.activeProfile.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("GPU: ${gpu?.shortLabel ?: "no detectada"}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
            item {
                SectionTitle("Presets por GPU")
                // R2-P2-14: sin presets hasta que termine la detección
                // de GPU: antes se listaban los 6, incluidos los de
                // Adreno, y un usuario Mali podía aplicar un preset
                // pensado para Adreno (el guard solo corregía el driver,
                // el resto de valores quedaban aplicados).
                if (gpu == null) {
                    Text("Detectando GPU…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                } else {
                val allProfiles = if (gpu?.vendor == GpuVendor.ADRENO) {
                    listOf(GpuProfileId.ADRENO_PERFORMANCE, GpuProfileId.ADRENO_QUALITY_TURNIP, GpuProfileId.G99_RECOMMENDED)
                } else if (gpu?.isUserVerifiedDevice == true || gpu?.isHelioG99 == true || gpu?.isMaliG57 == true) {
                    listOf(GpuProfileId.G99_RECOMMENDED, GpuProfileId.G99_QUALITY, GpuProfileId.MALI_BALANCED)
                } else if (gpu?.vendor == GpuVendor.MALI) {
                    listOf(GpuProfileId.MALI_PERFORMANCE, GpuProfileId.MALI_BALANCED)
                } else {
                    GpuProfileId.entries.toList()
                }
                // Los presets ocultos con "Limpiar no usados" no se listan (salvo el activo).
                val profiles = allProfiles.filter {
                    it == s.activeProfile || it !in (uiPrefs?.hiddenPresets ?: emptySet())
                }
                profiles.forEach { profile ->
                    OutlinedButton(
                        onClick = { vm.applyPreset(profile) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) { Text(profile.label) }
                    Text(profile.description, style = MaterialTheme.typography.bodySmall)
                    // Ola 3, Etapa 8: nota de compatibilidad por preset
                    // (aviso 2x en Mali, Turnip solo en Adreno). Los
                    // valores del preset no cambian: son los reales de
                    // presetSettings (verificados en LauncherSettings).
                    // R2-P2-23: la nota ya no recibe la GPU: su rama
                    // Mali/Turnip era muerta (el preset de Turnip nunca
                    // se lista en Mali) y el guard de applyPreset ya
                    // corrige el driver al aplicar.
                    NoteText(presetCompatibilityNote(profile))
                }
                NoteText("G99 Calidad (2x): prueba 30 s desde GPU y diagnóstico. Si baja de 28 fps, vuelve a 1x automáticamente si el ajuste está activado.")
                }
            }
            item {
                SectionTitle("Secciones")
                PremiumCard(
                    cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                    cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                ) {
                    SecondaryButton("Sesiones y guardados", onClick = { onNavigate(Screen.SAVES) })
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton("Logros (27)", onClick = { onNavigate(Screen.ACHIEVEMENTS) })
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton("Controles táctiles", onClick = { onNavigate(Screen.TOUCH) })
                }
            }
            item {
                // JUGAR siempre usa el motor integrado. Este interruptor es la
                // única vía a la app externa: solo si está ON y el motor
                // integrado no está disponible, JUGAR abre el juego fuera.
                // Con el motor presente, no cambia nada. Nunca es un respaldo
                // automático.
                SectionTitle("Motor")
                SettingSwitch(
                    "Permitir app externa (solo casos especiales)",
                    "Apagado: JUGAR siempre abre el juego dentro de este launcher. Encendido: si el motor integrado no está disponible, el juego se abre en otra app instalada.",
                    s.allowExternalVita3k,
                ) { vm.saveSettings(s.copy(allowExternalVita3k = it)) }
                SettingSwitch(
                    "Sesión PSN (simulada)",
                    "Encendido: el juego cree que hay sesión iniciada y los DLC se activan. No es una conexión real a PSN: no hay cuenta, ni red, ni sincronización. Apagado: el juego ve la sesión cerrada y los DLC pueden no activarse. Aplica en el siguiente lanzamiento.",
                    s.psnSignedIn,
                ) { vm.saveSettings(s.copy(psnSignedIn = it)) }
                Text(
                    if (externalVita3k != null) "✓ App externa instalada ($externalVita3k)" else "✗ App externa no instalada",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (externalVita3k != null) ColTurquoise else Color.White,
                )
            }
            item {
                SectionTitle("Aviso legal")
                PremiumCard(
                    cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                    cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                ) {
                    Text(
                        "Proyecto independiente, sin afiliación con Ubisoft, Sony / PlayStation ni con los autores del emulador.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    NoteText(
                        "Child of Light es marca registrada de Ubisoft. Este launcher no incluye el juego, " +
                            "ni DLCs, ni firmware, ni claves. El usuario debe aportar su propia copia legítima " +
                            "del juego y sus DLCs; el uso de copias no legítimas es responsabilidad exclusiva del usuario.",
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { vm.refreshConfigPreview(s); showConfig = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ver config.yml generado") }
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restablecer ajustes (preset de tu GPU)") }
            }
            item {
                // Ola 3, Etapa 9a: copia de seguridad de ajustes
                // (LauncherSettings + UiPreferences) como JSON por SAF.
                // Al importar se aplican los mismos clamps que al
                // guardar; un archivo inválido no aplica nada.
                SectionTitle("Copia de seguridad")
                OutlinedButton(
                    onClick = { exportBackup.launch("col-ajustes-backup.json") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Exportar copia de seguridad (JSON)") }
                OutlinedButton(
                    onClick = { importBackup.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Importar copia de seguridad…") }
                NoteText(
                    "La copia guarda tus ajustes y tu personalización (tema, tarjetas, iconos). " +
                        "No guarda el juego, los DLCs, el firmware ni los saves: esos son archivos y se quedan donde están. " +
                        "Tampoco guarda si el .pkg está instalado en la app externa ni el driver instalado: eso describe archivos de este teléfono, no preferencias.",
                )
            }
        }
    }
}
