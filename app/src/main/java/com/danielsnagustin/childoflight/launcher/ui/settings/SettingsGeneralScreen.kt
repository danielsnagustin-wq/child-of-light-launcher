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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.model.CardSize
import com.danielsnagustin.childoflight.launcher.model.CardTransparency
import com.danielsnagustin.childoflight.launcher.model.GameLanguage
import com.danielsnagustin.childoflight.launcher.model.divergesFromPreset
import com.danielsnagustin.childoflight.launcher.model.isLowRamConservativeVariant
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.model.profilesFor
import com.danielsnagustin.childoflight.launcher.ui.ColLavenderBlue
import com.danielsnagustin.childoflight.launcher.ui.ColTurquoise
import com.danielsnagustin.childoflight.launcher.ui.EnumChips
import com.danielsnagustin.childoflight.launcher.ui.LauncherViewModel
import com.danielsnagustin.childoflight.launcher.ui.LocalUiPreferences
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.PremiumCard
import com.danielsnagustin.childoflight.launcher.ui.Screen
import com.danielsnagustin.childoflight.launcher.ui.SecondaryButton
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.SettingSwitch
import com.danielsnagustin.childoflight.launcher.ui.cardTextColor
import com.danielsnagustin.childoflight.launcher.ui.isDarkUi

/**
 * Categoría "General" (Ola 2, Etapa 1): tarjeta compacta del perfil
 * de rendimiento, presets, enlaces a secciones, motor y aviso legal.
 * "Ver config.yml" y "Restablecer" también viven aquí.
 */
@Composable
fun SettingsGeneralScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val uiPrefs = LocalUiPreferences.current
    // R4-P1-14: textos segun el tema (en Claro, blanco sobre fondo claro no se lee).
    val dark = isDarkUi(uiPrefs)
    val externalVita3k by vm.externalVita3k.collectAsState()
    // Ronda 0.2.55 P1-18: generatedConfig ya NO se recoge aquí arriba:
    // solo se usa en el diálogo "Ver config.yml" y cada emisión
    // recomponía la pantalla General entera. Lo recoge
    // ConfigPreviewDialog dentro de sí.
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    // 0.2.63 (F): "Borrar progreso de logros" vivía en la pantalla
    // de Logros, a un toque de borrarlos por error. Ahora vive aquí,
    // en General junto a "Restablecer ajustes", lejos de un toque
    // accidental, con su doble confirmación (R2-P2-02: el primer
    // diálogo avisa y el segundo deja explícito que borrará los
    // trofeos ganados jugando).
    var showTrophiesResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showTrophiesResetConfirm2 by rememberSaveable { mutableStateOf(false) }
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
        dialogs = {
            if (showConfig) {
                ConfigPreviewDialog(vm) { showConfig = false }
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
            if (showTrophiesResetConfirm) {
                AlertDialog(
                    onDismissRequest = { showTrophiesResetConfirm = false },
                    title = { Text("¿Borrar el progreso de logros?") },
                    text = { Text("Se borran los 20 trofeos desbloqueados en este launcher. Es solo local: no afecta a PSN porque nunca se sincroniza.") },
                    confirmButton = {
                        TextButton(onClick = { showTrophiesResetConfirm = false; showTrophiesResetConfirm2 = true }) { Text("Continuar") }
                    },
                    dismissButton = { TextButton(onClick = { showTrophiesResetConfirm = false }) { Text("Cancelar") } },
                )
            }
            if (showTrophiesResetConfirm2) {
                AlertDialog(
                    onDismissRequest = { showTrophiesResetConfirm2 = false },
                    title = { Text("¿Seguro? Se borrarán los trofeos ganados") },
                    text = { Text("Esto borrará los trofeos ganados jugando, con sus fechas, y no se pueden recuperar. Si quieres conservarlos, expórtalos antes desde Guardados → Exportar progreso de logros.") },
                    confirmButton = {
                        TextButton(onClick = { vm.resetTrophies(); showTrophiesResetConfirm2 = false }) { Text("Sí, borrar los trofeos ganados") }
                    },
                    dismissButton = { TextButton(onClick = { showTrophiesResetConfirm2 = false }) { Text("Cancelar") } },
                )
            }
        },
    ) {
        if (s == null) {
            item { Text("Cargando ajustes…") }
        } else {
            item {
                SectionTitle("Perfil de rendimiento")
                // R4-P0-9 / R4-P1-13: el estado del perfil deriva de
                // los ajustes guardados reales frente al preset
                // activo, nunca de etiquetas: "perfil conservador
                // (poca RAM)" es la variante lowRam del primer
                // arranque; "perfil modificado" es cualquier otra
                // divergencia (p. ej. una caché manual conservada al
                // aplicar un preset). Cuando la caché de texturas
                // difiere del preset se dice explícitamente en el
                // aviso (es el campo que más se conserva a mano al
                // aplicar un preset, ver preserveManualCacheChoices).
                // Ronda 0.2.66 (C-01): la caché ya NO es lo que
                // hace conservadora a la variante lowRam (en Vulkan
                // OFF re-subiría cada textura y la variante no la
                // toca): el sufijo "para ahorrar memoria" se quitó.
                val profileStatusText: String? = when {
                    isLowRamConservativeVariant(s) -> "Perfil conservador (poca RAM)"
                    divergesFromPreset(s) -> {
                        val presetCache = presetSettings(s.activeProfile).textureCache
                        "Perfil modificado" +
                            if (s.textureCache != presetCache) {
                                " — caché de texturas ${if (s.textureCache) "ON" else "OFF"} (el preset la trae ${if (presetCache) "ON" else "OFF"})"
                            } else {
                                ""
                            }
                    }
                    else -> null
                }
                PremiumCard(
                    cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                    cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                ) {
                    Text(
                        "Perfil activo: ${s.activeProfile.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = cardTextColor(dark),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("GPU: ${gpu?.shortLabel ?: "no detectada"}", style = MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                    if (profileStatusText != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            profileStatusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (dark) ColTurquoise else ColLavenderBlue,
                        )
                    }
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
                    Text("Detectando GPU…", style = MaterialTheme.typography.bodyMedium, color = cardTextColor(dark))
                } else {
                // Ronda 0.2.64 (H-01): la lista vive en profilesFor
                // (LauncherSettings): la rama genérica (PowerVR /
                // Xclipse / OTHER / UNKNOWN) solo ofrece los dos
                // presets seguros, no los 6 (el guard de applyPreset
                // solo corregía el driver y el resto del preset
                // Adreno — 2x, FXAA, anisotrópico 4x, Page Table —
                // quedaba aplicado en una GPU donde nunca se validó).
                val allProfiles = profilesFor(gpu)
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
                NoteText("Aplicar un preset conserva tus cambios manuales de cachés y overlay (caché de texturas, caché de shaders, overlay de FPS con su detalle y posición, caché sin hash y avisos de shaders): si alguno difiere del preset, arriba verás \"Perfil modificado\".")
                NoteText("G99 Calidad y Adreno Calidad/Turnip (2x): prueba 30 s desde GPU y diagnóstico. Si baja de 28 fps, vuelve a 1x automáticamente si el ajuste está activado. También cubre un 2x puesto a mano sobre cualquier perfil.")
                }
            }
            item {
                SectionTitle("Secciones")
                PremiumCard(
                    cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                    cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                ) {
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
                    "Encendido: si el motor integrado no está disponible, el juego se abre en otra app instalada. Apagado: JUGAR siempre abre el juego dentro de este launcher.",
                    s.allowExternalVita3k,
                ) { vm.saveSettings(s.copy(allowExternalVita3k = it)) }
                SettingSwitch(
                    "Sesión PSN (simulada)",
                    "Encendido: el juego cree que hay sesión iniciada y los DLC se activan. No es una conexión real a PSN: no hay cuenta, ni red, ni sincronización. Apagado: el juego ve la sesión cerrada y los DLC pueden no activarse. Aplica en el siguiente lanzamiento.",
                    s.psnSignedIn,
                ) { vm.saveSettings(s.copy(psnSignedIn = it)) }
                // 0.2.63 (E): idioma del juego. Es el idioma del
                // sistema emulado (sys-lang en config.yml), no el de
                // la interfaz del launcher: con Español, el juego
                // muestra sus menús, diálogos y subtítulos en
                // español. Aplica en el siguiente lanzamiento.
                EnumChips("Idioma del juego", GameLanguage.entries, s.gameLanguage, { it.label }) { vm.saveSettings(s.copy(gameLanguage = it)) }
                NoteText("El juego se guarda en este idioma: con Español, los menús, diálogos y subtítulos salen en español. Aplica en el siguiente lanzamiento (sal y vuelve a entrar con JUGAR).")
                Text(
                    if (externalVita3k != null) "✓ App externa instalada ($externalVita3k)" else "✗ App externa no instalada",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (externalVita3k != null) { if (dark) ColTurquoise else ColLavenderBlue } else cardTextColor(dark),
                )
            }
            item {
                SectionTitle("Aviso legal")
                PremiumCard(
                    cardSize = uiPrefs?.cardSize ?: CardSize.NORMAL,
                    cardTransparency = uiPrefs?.cardTransparency ?: CardTransparency.OPAQUE,
                ) {
                    Text(
                        "Proyecto independiente, sin afiliación con Ubisoft, Sony / PlayStation ni con los autores del motor.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cardTextColor(dark),
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
                // 0.2.63 (F): el borrado de trofeos vive aquí desde
                // esta versión (antes estaba en la pantalla de
                // Logros): mismo botón, misma doble confirmación,
                // lejos de un toque accidental.
                OutlinedButton(
                    onClick = { showTrophiesResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Borrar progreso de logros") }
                NoteText("Borra los trofeos ganados jugando, con sus fechas. Pide dos confirmaciones y no se puede deshacer: expórtalos antes desde Guardados → Exportar progreso de logros si quieres conservarlos.")
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

/**
 * Diálogo "config.yml generado" (Ronda 0.2.55 P1-18): recoge
 * generatedConfig DENTRO, solo mientras está visible. Antes la
 * pantalla General lo recogía siempre en la raíz y cada vista previa
 * generada recomponía la pantalla entera.
 */
@Composable
private fun ConfigPreviewDialog(vm: LauncherViewModel, onClose: () -> Unit) {
    val configText by vm.generatedConfig.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("config.yml generado") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(configText, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}
