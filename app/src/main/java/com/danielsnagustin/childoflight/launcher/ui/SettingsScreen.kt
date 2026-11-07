package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
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
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.FrameLimiter
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.MemoryMapping
import com.danielsnagustin.childoflight.launcher.model.ScreenFilter

/**
 * Pantalla de Ajustes. Todo lo que se cambia se guarda en DataStore
 * al momento (vm.saveSettings). Nada es decorativo.
 *
 * La sección GPU es CONDICIONAL según la GPU detectada:
 * - Adreno: muestra Turnip (instalar / versión / sistema).
 * - Mali: OCULTA Turnip y avisa que solo funciona en Adreno.
 * - G99/Mali-G57: muestra el aviso de procesador verificado.
 */
@Composable
fun SettingsScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    // B3: el firmware se lee como flujo (antes llamada plana sin refresco).
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    // Importación en curso: se muestra el progreso y se bloquean los
    // botones (antes no se veía NADA mientras copiaba ~2 GB y el usuario
    // creía que "al cargar no funciona").
    val importing by vm.importing.collectAsState()
    var showConfig by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val configText by vm.generatedConfig.collectAsState()

    val pickTurnip = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.installTurnip(uri)
    }
    val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importFirmware(uri)
    }
    // Importación (antes en la Home; ahora toda aquí, en Configuración).
    val pickGame = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importGame(uri)
    }
    val pickDlc = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importDlc(uri)
    }
    val pickUpdate = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUpdate(uri)
    }
    val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importGameFolder(uri)
    }
    val pickDlcFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importDlcFolder(uri)
    }

    val s = settings
    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush()),
    ) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader("Configuración", "Todo lo del launcher, en un solo lugar.")
        Text("Perfil activo: ${s?.activeProfile?.label ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = ColMutedText)
        if (gpu?.isUserVerifiedDevice == true) {
            NoteText("✓ Helio G99 · Mali-G57 MC2 — procesador verificado por ti. El preset por defecto es G99 Recomendado: 1x estable, sin recortar de más.")
        }
        if (s == null) {
            Text("Cargando ajustes…")
            return@Column
        }

        // --- Importar (antes en la Home; ahora toda aquí) ---
        SectionTitle("Importar")
        PremiumCard {
            Text(
                "Juego y DLCs: solo se aceptan PCSE00480 (US) y PCSB00598 (EU).",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (importing) {
                Text(
                    "⏳ Importando… no cierres la app. Un juego completo puede tardar varios minutos.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ColGold,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            SecondaryButton("Importar juego (.zip / .vpk / .pkg)", onClick = { pickGame.launch("*/*") }, enabled = !importing)
            NoteText("Elige el .zip o .vpk COMPLETO del juego (dentro lleva eboot.bin). Un eboot.bin suelto no basta.")
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("Importar carpeta del juego", onClick = { pickGameFolder.launch(null) }, enabled = !importing)
            NoteText("Elige la carpeta PCSE00480 o PCSB00598 que contiene eboot.bin dentro.")
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("Importar DLC (.zip)", onClick = { pickDlc.launch("*/*") }, enabled = !importing)
            NoteText("Elige el .zip del DLC (con la ruta addcont/ dentro).")
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("Importar carpeta de DLC", onClick = { pickDlcFolder.launch(null) }, enabled = !importing)
            NoteText("Elige la carpeta del juego que contiene addcont/ dentro.")
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("Importar update (.zip / .vpk)", onClick = { pickUpdate.launch("*/*") }, enabled = !importing)
            NoteText("Elige el .zip o .vpk del update.")
        }

        // --- Secciones del launcher (antes botones en la Home) ---
        SectionTitle("Secciones")
        PremiumCard {
            SecondaryButton("Sesiones y guardados", onClick = { onNavigate(Screen.SAVES) })
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("Logros (27)", onClick = { onNavigate(Screen.ACHIEVEMENTS) })
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("Controles táctiles", onClick = { onNavigate(Screen.TOUCH) })
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton("GPU y diagnóstico", onClick = { onNavigate(Screen.GPU) })
        }

        // --- Presets ---
        SectionTitle("Presets por GPU")
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
        }
        NoteText("G99 Calidad (2x): prueba 30 s desde GPU y diagnóstico. Si baja de 28 fps, vuelve a 1x automáticamente si el ajuste está activado.")

        // --- GPU ---
        SectionTitle("GPU")
        EnumChips("Backend", GpuBackend.entries, s.backend, { it.label }) { vm.saveSettings(s.copy(backend = it)) }
        EnumChips(
            "Resolución interna",
            listOf(1f, 2f, 3f, 4f),
            s.resolutionMultiplier,
            { m -> when (m) { 1f -> "1x (960x544)"; 2f -> "2x (1920x1088)"; 3f -> "3x (2880x1632)"; else -> "4x (3840x2176)" } },
        ) { vm.saveSettings(s.copy(resolutionMultiplier = it)) }
        if (gpu?.vendor == GpuVendor.MALI && s.resolutionMultiplier > 1f) {
            NoteText("⚠ En Mali no se recomienda 2x/3x/4x. Es una opción manual: si el FPS cae, vuelve a 1x. En el G99, usa la prueba de 30 s.")
        }
        EnumChips("Filtro de pantalla", ScreenFilter.entries, s.screenFilter, { it.vita3kValue }) { vm.saveSettings(s.copy(screenFilter = it)) }
        EnumChips("Anisotrópico", listOf(1, 2, 4, 8, 16), s.anisotropicFiltering, { "${it}x" }) { vm.saveSettings(s.copy(anisotropicFiltering = it)) }
        SettingSwitch("V-Sync", "Sincronización vertical. OFF en Rendimiento, ON en Calidad Adreno.", s.vSync) { vm.saveSettings(s.copy(vSync = it)) }
        SettingSwitch("Async Pipeline Compilation", "Compila shaders en segundo plano, menos tirones. ON siempre.", s.asyncPipelineCompilation) { vm.saveSettings(s.copy(asyncPipelineCompilation = it)) }
        SettingSwitch("High Accuracy Rendering", "Más preciso, mucho más lento. OFF en todos los presets.", s.highAccuracy) { vm.saveSettings(s.copy(highAccuracy = it)) }
        SettingSwitch("Surface Sync activado", "ON = más preciso. OFF = más rápido (recomendado en G99/Mali).", s.surfaceSyncEnabled) { vm.saveSettings(s.copy(surfaceSyncEnabled = it)) }
        EnumChips("Memory Mapping", MemoryMapping.entries, s.memoryMapping, { it.label }) { vm.saveSettings(s.copy(memoryMapping = it)) }
        NoteText(
            when (gpu?.vendor) {
                GpuVendor.ADRENO -> "Recomendado en Adreno: Page Table."
                GpuVendor.MALI -> "Recomendado en Mali: Double Buffer (forzado en los presets Mali). Mali suele hacer fallback a Double Buffer aunque se elija otro."
                else -> "Recomendado: Double Buffer. Solo el string \"double-buffer\" está verificado en config.h; los demás se marcan TODO-VERIFY en el YAML."
            },
        )

        // --- Driver: condicional ---
        SectionTitle("Driver GPU")
        if (gpu?.vendor == GpuVendor.ADRENO) {
            EnumChips("Driver personalizado", GpuDriver.entries, s.gpuDriver, { if (it == GpuDriver.SYSTEM) "Sistema" else "Turnip" }) {
                if (it == GpuDriver.SYSTEM) vm.useSystemDriver() else vm.saveSettings(s.copy(gpuDriver = it))
            }
            Text("Turnip instalado: ${if (s.turnipInstalled) s.turnipVersion else "no"}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { pickTurnip.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Instalar / cambiar Turnip (.zip)") }
            NoteText("Turnip es el driver Mesa open-source para Adreno. Se carga solo para esta app. Al cambiar de driver, Vita3K limpia la caché de shaders al arrancar.")
        } else {
            Text("Driver del sistema (${gpu?.renderer ?: "GPU no detectada"})", fontWeight = FontWeight.SemiBold)
            NoteText("Turnip solo funciona en Qualcomm Adreno, no en Mali. Por eso no se muestra aquí.")
            if (gpu?.vendor == GpuVendor.MALI) {
                NoteText("En Mali, si ves glitches gráficos, prueba el backend OpenGL como fallback (sección GPU, arriba).")
            }
        }
        OutlinedButton(onClick = { vm.clearShaderCache() }, modifier = Modifier.fillMaxWidth()) { Text("Limpiar caché de shaders") }
        NoteText("Limpia la caché solo si hay glitches. El stutter de la primera partida es normal hasta que la caché se vuelva a llenar.")

        // --- CPU ---
        SectionTitle("CPU")
        SettingSwitch("Optimización CPU (JIT Dynarmic)", "cpu-opt en config.yml. Dynarmic es el backend por defecto de Vita3K.", s.cpuOpt) { vm.saveSettings(s.copy(cpuOpt = it)) }
        SettingSwitch("FPS Hack (30 → 60)", "fps-hack en config.yml. En Child of Light déjalo OFF salvo que sepas que el juego lo soporta.", s.fpsHack) { vm.saveSettings(s.copy(fpsHack = it)) }
        SettingSlider("Retraso de carga de archivos", s.fileLoadingDelay, 0..100, { vm.saveSettings(s.copy(fileLoadingDelay = it)) })

        // --- Audio ---
        SectionTitle("Audio")
        EnumChips("Backend de audio", AudioBackend.entries, s.audioBackend, { it.label }) { vm.saveSettings(s.copy(audioBackend = it)) }
        NoteText("SDL es el default de Vita3K-Android desde v7 porque arregla ruido. Cubeb es la alternativa (Vita3K+ v1.3: cubeb y SDL ya no deberían crepitar/cortar).")
        EnumChips("Tamaño de buffer de audio", listOf(256, 512, 1024, 2048), s.audioBufferSize, { "$it" }) { vm.saveSettings(s.copy(audioBufferSize = it)) }
        NoteText("Si hay crackles (chasquidos), sube el buffer un paso (p. ej. 512 → 1024). Es ajuste del launcher: no hay clave de buffer en config.h.")
        SettingSwitch("NGS (audio del sistema Vita)", "ngs-enable en config.yml. ON recomendado.", s.ngsEnable) { vm.saveSettings(s.copy(ngsEnable = it)) }
        SettingSlider("Volumen", s.audioVolume, 0..100, { vm.saveSettings(s.copy(audioVolume = it)) }) { "$it%" }
        Button(onClick = { vm.fixAudio() }, modifier = Modifier.fillMaxWidth()) { Text("Corregir audio") }
        NoteText("Pone SDL + buffer 1024 + NGS ON + limpia la caché de shaders. Vuelve a lanzar el juego para aplicar el cambio.")

        // --- Rendimiento ---
        SectionTitle("Rendimiento")
        EnumChips("Límite de FPS", FrameLimiter.entries, s.frameLimiter, { it.label }) { vm.saveSettings(s.copy(frameLimiter = it)) }
        EnumChips("Frame Skipping", listOf(0, 1, 2), s.frameSkipping, { "$it" }) { vm.saveSettings(s.copy(frameSkipping = it)) }
        NoteText("En el G99 Recomendado el Frame Skip es 0: el juego ya va bien, no hace falta saltar frames.")
        SettingSwitch("Mostrar overlay de FPS", "performance-overlay en config.yml. Actívalo para la prueba de 30 s.", s.showFpsOverlay) { vm.saveSettings(s.copy(showFpsOverlay = it)) }
        SettingSwitch("Volver a 1x si baja de 28 fps", "Solo con el preset G99 Calidad (2x). Automático al registrar el FPS de la prueba.", s.autoRevertTo1xIfBelow28Fps) { vm.saveSettings(s.copy(autoRevertTo1xIfBelow28Fps = it)) }
        SettingSwitch("Modo rendimiento del launcher", "Mantiene la pantalla encendida mientras juegas.", s.performanceMode) { vm.saveSettings(s.copy(performanceMode = it)) }

        // --- Corrección de errores ---
        SectionTitle("Corrección de errores")
        NoteText("Mitigaciones del launcher (no reescriben el núcleo Vita3K). Si algo falla, empieza por el Modo seguro.")
        SettingSwitch("Accurate Thread Scheduling", "Default nuevo en Vita3K: evita freezes/cuelgues. ON recomendado. Sin clave verificada en config.h: se guarda en el launcher.", s.accurateThreadScheduling) { vm.saveSettings(s.copy(accurateThreadScheduling = it)) }
        SettingSwitch("Caché de shaders", "ON siempre: el stutter de la primera partida se va cuando la caché se llena.", s.shaderCache) { vm.saveSettings(s.copy(shaderCache = it)) }
        SettingSwitch("Cerrar apps en segundo plano antes de jugar", "Best-effort con el permiso del sistema. Libera RAM antes de lanzar.", s.closeBackgroundApps) { vm.saveSettings(s.copy(closeBackgroundApps = it)) }
        SettingSwitch("Modo seguro", "Fuerza 1x, SDL, buffer 2048, Thread Scheduling ON, High Accuracy OFF. Para cuando algo falle.", s.safeMode) { vm.setSafeMode(it) }

        // --- Personalización del launcher ---
        SectionTitle("Personalización del launcher")
        uiPrefs?.let { ui ->
            EnumChips("Tema", com.danielsnagustin.childoflight.launcher.model.ThemeMode.entries, ui.theme, { it.label }) {
                vm.saveUiPrefs(ui.copy(theme = it))
            }
            EnumChips("Color de acento", com.danielsnagustin.childoflight.launcher.model.AccentColor.entries, ui.accent, { it.label }) {
                vm.saveUiPrefs(ui.copy(accent = it))
            }
            EnumChips("Fondo de la pantalla principal", com.danielsnagustin.childoflight.launcher.model.HomeBackground.entries, ui.homeBackground, { it.label }) {
                vm.saveUiPrefs(ui.copy(homeBackground = it))
            }
            EnumChips("Tamaño de texto", com.danielsnagustin.childoflight.launcher.model.TextSize.entries, ui.textSize, { it.label }) {
                vm.saveUiPrefs(ui.copy(textSize = it))
            }
            EnumChips("Idioma", com.danielsnagustin.childoflight.launcher.model.AppLanguage.entries, ui.language, { it.label }) {
                vm.saveUiPrefs(ui.copy(language = it))
            }
            NoteText("Idioma ES/EN: traduce la portada, la navegación y los títulos de sección. Las notas técnicas de Vita3K permanecen en español en Etapa 1.")
            SettingSwitch("Mostrar FPS en la pantalla principal", "Muestra el FPS medio de la última prueba de 30 s en la portada.", ui.showFpsOnHome) {
                vm.saveUiPrefs(ui.copy(showFpsOnHome = it))
            }
            SettingSwitch("Mostrar logros en la pantalla principal", "Muestra el progreso X/27 en la portada.", ui.showAchievementsOnHome) {
                vm.saveUiPrefs(ui.copy(showAchievementsOnHome = it))
            }
            Text("Orden de secciones en la portada", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            NoteText("Los botones de navegación ya no se muestran en la portada: viven en Configuración → Secciones.")
            ui.sectionOrder.forEachIndexed { index, section ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${section.label}", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (index > 0) {
                            val reordered = ui.sectionOrder.toMutableList()
                            val tmp = reordered[index - 1]; reordered[index - 1] = reordered[index]; reordered[index] = tmp
                            vm.saveUiPrefs(ui.copy(sectionOrder = reordered))
                        }
                    }) { Text("↑") }
                    TextButton(onClick = {
                        if (index < ui.sectionOrder.size - 1) {
                            val reordered = ui.sectionOrder.toMutableList()
                            val tmp = reordered[index + 1]; reordered[index + 1] = reordered[index]; reordered[index] = tmp
                            vm.saveUiPrefs(ui.copy(sectionOrder = reordered))
                        }
                    }) { Text("↓") }
                }
            }
            OutlinedButton(onClick = { vm.resetAllCustomization() }, modifier = Modifier.fillMaxWidth()) { Text("Restablecer toda la personalización") }
        }

        // --- Limpiar no usados ---
        SectionTitle("Limpiar no usados")
        OutlinedButton(onClick = { onNavigate(Screen.CLEANUP) }, modifier = Modifier.fillMaxWidth()) { Text("Limpiar no usados…") }
        NoteText("Botones ocultos, perfiles sin usar, presets sin usar, Turnip sin usar, DLCs y saves.")

        // --- Avanzado: firmware (nunca un paso inicial) ---
        SectionTitle("Avanzado — Firmware")
        Text("Firmware: ${com.danielsnagustin.childoflight.launcher.data.FirmwareResolver.label(firmwareStatus)}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { pickFirmware.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Importar firmware (.PUP, avanzado)") }
        NoteText(
            "Opción avanzada, nunca obligatoria: el primer uso solo pide juego + DLCs. " +
                "El launcher detecta solo el firmware que ya tengas guardado (PSVUPDAT.PUP o vs0 extraído); " +
                "si Vita3K ya tiene firmware instalado, usa el suyo. Esto solo guarda una copia local " +
                "para el núcleo embebido (Etapa 2). El launcher no incluye firmware ni claves.",
        )

        // --- Aviso legal ---
        SectionTitle("Aviso legal")
        PremiumCard {
            Text(
                "Proyecto independiente, sin afiliación con Ubisoft, Sony / PlayStation ni Vita3K.",
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

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = { vm.refreshConfigPreview(s); showConfig = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Ver config.yml generado") }
        OutlinedButton(
            onClick = { showResetConfirm = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Restablecer ajustes (preset de tu GPU)") }
        BackButton(onBack)
    }
    }

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
}
