package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DeviceCompat
import com.danielsnagustin.childoflight.launcher.data.DeviceMemory
import com.danielsnagustin.childoflight.launcher.model.BenchmarkSource
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.ui.settings.extractRecentErrorLines

/**
 * GPU y diagnóstico.
 * - Detección: SoC, GPU, versión Vulkan, driver.
 * - En G99/Mali-G57 muestra "Procesador verificado por el usuario".
 * - Prueba de 30 s: el núcleo mide el FPS solo; si no hay
 *   medición, registro manual del FPS del overlay.
 * - Optimización de batería, núcleo, log reciente y reporte de bug.
 */
/** Etiqueta legible del origen de la medición (el enum se guarda en DataStore). */
private fun benchmarkSourceLabel(source: BenchmarkSource): String = when (source) {
    BenchmarkSource.MANUAL_OVERLAY -> "manual"
    BenchmarkSource.AUTO_MEASURED -> "automático"
    BenchmarkSource.NOT_MEASURED -> "sin medir"
}

@Composable
fun GpuDiagnosticsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())
    val logText by vm.logText.collectAsState()
    // R2-P1-02 (parte pantalla): el log en pantalla mostraba rutas
    // absolutas con el nombre del paquete. Mismo replace que la
    // exportación del reporte (LauncherViewModel.buildBugReportText):
    // el nombre del paquete nunca sale en texto visible.
    // Ronda 0.2.55 P1-13: el replace se memoriza por logText (antes
    // recorría el string entero en cada recomposición).
    val safeLogText = remember(logText) { logText.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher") }
    val pending by vm.pendingBenchmark.collectAsState()
    // B3: firmware y batería se leen como flujo, no con llamada plana.
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val batteryOptDisabled by vm.batteryOptDisabledFlow.collectAsState()
    val launching by vm.launchingFlow.collectAsState()
    var fpsInput by rememberSaveable { mutableStateOf("") }
    var showFpsDialog by rememberSaveable { mutableStateOf(false) }
    var fpsError by rememberSaveable { mutableStateOf(false) }
    var showBatteryDialog by rememberSaveable { mutableStateOf(false) }

    val exportBug = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val s = settings
        if (uri != null && s != null) vm.exportBugReport(uri, s, gpu)
    }
    val requestBatteryOpt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshBatteryOptimizationState()
        vm.refreshLog()
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
        ScreenHeader("GPU y diagnóstico", "Tu GPU, el núcleo y la prueba de 30 segundos.")
        val g = gpu
        if (g == null) {
            Text("Detectando GPU…")
        } else {
            SectionTitle("Diagnóstico de GPU")
            Text("SoC: ${g.socModel.ifBlank { "no detectado" }}", fontWeight = FontWeight.SemiBold)
            Text("GPU: ${g.renderer}")
            Text("Vendor: ${g.vendorString.ifBlank { "—" }}")
            Text("GLES: ${g.glesVersion.ifBlank { "—" }}")
            Text("Vulkan: ${g.vulkanVersionLabel} (soportado: ${if (g.vulkanSupported) "sí" else "no"})")
            // Dispositivo: arquitectura y RAM (lecturas reales, no
            // estimadas). La RAM la lee DeviceMemory (auditoría ≤ 4 GB).
            val context = LocalContext.current
            val ramGb = remember { DeviceMemory.totalRamBytes(context)?.let { it / 1_073_741_824.0 } }
            Text("Arquitectura: ${DeviceCompat.architectureLabel()}")
            Text("RAM total: ${ramGb?.let { "%.1f GB".format(it) } ?: "no detectada"}")
            // `settings` es propiedad delegada (collectAsState): no admite
            // smart cast tras el null-check; se captura en un val local.
            val currentSettings = settings
            if (currentSettings != null) {
                Text("Backend efectivo: ${resolveBackend(currentSettings, g).label}")
            }
            if (!DeviceCompat.is64BitDevice()) {
                Text(
                    "⚠ Este teléfono es de 32 bits: el núcleo embebido necesita 64 bits (arm64), así que el juego se abrirá con la app externa.",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!g.vulkanSupported) {
                Text(
                    "⚠ Sin Vulkan en este teléfono. El núcleo usará OpenGL en su lugar: puede ir más lento o con fallos gráficos. Si el juego no arranca o se ve mal, activa el modo seguro en Ajustes.",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (g.isUserVerifiedDevice) {
                Text("✓ Procesador verificado por el usuario: Child of Light va muy bien en este procesador.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                NoteText("Por eso el preset por defecto es G99 Recomendado (1x): se optimiza para mantener esa fluidez estable, no para recortar de más.")
            } else if (g.vendor.name == "MALI") {
                NoteText("Mali: hay cosas que definitivamente no funcionan y es posible que nunca funcionen, pero el núcleo trae arreglos para Mali (vertex fix, Double Buffer mejorado) y Child of Light, al ser 2D UbiArt, es más ligero que un juego 3D: a 1x tiene más chance. No se prometen 60 fps en Mali.")
            } else if (g.vendor.name == "ADRENO") {
                NoteText("Snapdragon/Adreno: usa Turnip para mejor compatibilidad (ver Ajustes → Driver GPU).")
            } else if (g.vendor.name == "POWERVR") {
                NoteText("PowerVR: no hay perfil específico para esta GPU; se usa el perfil Genérico seguro (1x, Double Buffer). Si el juego va mal, prueba el backend OpenGL o el modo seguro en Ajustes.")
            } else {
                NoteText("GPU sin perfil propio (o no detectada): se usa el perfil Genérico seguro (1x, Double Buffer). Ninguna GPU se queda sin perfil.")
            }
            OutlinedButton(onClick = { vm.detectGpu() }, modifier = Modifier.fillMaxWidth()) { Text("Volver a detectar GPU") }
        }

        SectionTitle("Núcleo")
        Text("Núcleo embebido: ${if (vm.embeddedCorePresent()) "presente" else "NO presente"}")
        Text("App externa: ${vm.externalVita3kVersion() ?: "no instalada"}")
        Text("Firmware: ${com.danielsnagustin.childoflight.launcher.data.FirmwareResolver.label(firmwareStatus)}")
        NoteText("El firmware nunca es un paso inicial: el primer uso solo pide juego + DLCs. Se importa en Importar, junto al juego y los DLC.")
        Text("Backend: ${settings?.backend?.label ?: "—"} · Driver: ${settings?.gpuDriver ?: "—"}${if (settings?.turnipInstalled == true) " (${settings?.turnipVersion})" else ""}")
        Text("Resolución: ${settings?.resolutionLabel ?: "—"} · FPS overlay: ${if (settings?.showFpsOverlay == true) "ON" else "OFF"}")

        // Ola 3, Etapa 10: configuración activa resumida, último FPS y
        // errores recientes del log (el modelo del dispositivo solo se
        // muestra en Ajustes → Diagnóstico, no aquí).
        SectionTitle("Resumen de la configuración activa")
        val lastFps = benchmarks.firstOrNull { it.fpsAvg != null }?.fpsAvg
        Text("Perfil activo: ${settings?.activeProfile?.label ?: "—"}")
        Text("Backend efectivo: ${settings?.let { resolveBackend(it, g) }?.label ?: "—"} · Resolución: ${settings?.resolutionLabel ?: "—"} · Filtro: ${settings?.screenFilter ?: "—"}")
        // R5-A1-14: VSync EFECTIVO (con 60 FPS ON, apply60FpsMode lo
        // fuerza ON aunque el guardado sea OFF): settings es delegada,
        // así que se captura con let, no con smart cast. Igual que
        // SettingsDiagnosticsScreen.
        Text("VSync: ${if (settings?.let { effectiveSettings(it).vSync } == true) "ON" else "OFF"} · Modo 60 FPS: ${if (settings?.fpsHack == true) "ON" else "OFF"} · Modo seguro: ${if (settings?.safeMode == true) "ON" else "OFF"}")
        Text("Último FPS (prueba de 30 s): ${lastFps?.let { "$it fps" } ?: "sin medir"}")
        SectionTitle("Errores recientes del log")
        // Ronda 0.2.55 P1-13: el filtrado se memoriza por safeLogText
        // (líneas + lowercase + regex por línea en cada recomposición).
        val recentErrors = remember(safeLogText) { extractRecentErrorLines(safeLogText) }
        if (recentErrors.isEmpty()) {
            Text("Sin errores recientes en el log.", color = ColTurquoise)
        } else {
            recentErrors.forEach { line -> Text("• $line", style = MaterialTheme.typography.bodySmall) }
        }
        NoteText("El informe exportado (Reportar bug) es solo texto: dispositivo, ajustes, estado y errores recientes. No adjunta ningún archivo privado.")

        SectionTitle("Prueba de 30 segundos")
        NoteText("Lanza el juego con el preset actual. El FPS se mide automáticamente mientras juegas y al volver se registra solo. Si no hay medición (app externa), juega 30 segundos mirando el FPS del overlay y regístralo a mano. Si es G99 Calidad (2x) y baja de 28 fps, vuelve a 1x automáticamente.")
        PrimaryButton("Jugar 30 segundos", onClick = { vm.startBenchmark() }, enabled = !launching)
        if (pending != null) {
            Text("Prueba en marcha: ${pending?.profile?.label}. Al volver, el FPS se registra solo si el juego lo midió; si no, regístralo a mano.", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { showFpsDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Registrar FPS visto en el overlay") }
            OutlinedButton(onClick = { vm.dismissBenchmark() }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar prueba") }
        }
        benchmarks.take(3).forEach { run ->
            Text("· ${run.profile.label}: ${run.fpsAvg?.let { "$it fps" } ?: "sin medir"} (${benchmarkSourceLabel(run.source)})${if (run.autoReverted) " — volvió a 1x" else ""}")
        }

        SectionTitle("Batería y rendimiento")
        Text("Optimización de batería desactivada: ${if (batteryOptDisabled) "sí" else "no"}")
        OutlinedButton(
            onClick = { showBatteryDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Desactivar optimización de batería") }
        NoteText("Evita que Android mate el juego en segundo plano o limite la GPU. Lo concede el usuario en el diálogo del sistema.")

        SectionTitle("Log reciente")
        Text(safeLogText, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.refreshLog() }, modifier = Modifier.fillMaxWidth()) { Text("Actualizar log") }
        OutlinedButton(
            onClick = { exportBug.launch("col-bug-report.txt") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reportar bug (exportar log + ajustes + dispositivo)") }
        BackButton(onBack)
    }
    }

    if (showFpsDialog) {
        AlertDialog(
            onDismissRequest = { showFpsDialog = false; fpsError = false },
            title = { Text("FPS medio de la prueba") },
            text = {
                Column {
                    Text("Escribe el FPS medio que viste en el overlay durante los 30 segundos (por ejemplo: 30).")
                    androidx.compose.material3.OutlinedTextField(
                        value = fpsInput,
                        onValueChange = { fpsInput = it.filter { c -> c.isDigit() || c == '.' }; fpsError = false },
                        label = { Text("FPS medio") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // B6 (auditoría UI): antes un valor inválido (vacío o
                    // ".") se descartaba en silencio y el diálogo se
                    // cerraba igual. Ahora no se registra nada inválido.
                    if (fpsError) {
                        Text(
                            "Escribe un número válido (por ejemplo: 30).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = fpsInput.toFloatOrNull() != null,
                    onClick = {
                        val fps = fpsInput.toFloatOrNull()
                        if (fps == null) {
                            fpsError = true
                        } else {
                            vm.recordBenchmarkFps(fps)
                            fpsInput = ""
                            fpsError = false
                            showFpsDialog = false
                        }
                    },
                ) { Text("Registrar") }
            },
            dismissButton = { TextButton(onClick = { showFpsDialog = false; fpsError = false }) { Text("Cancelar") } },
        )
    }
    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("Desactivar optimización de batería") },
            text = { Text("Android abrirá el diálogo del sistema para eximir a este launcher de la optimización de batería. Es opcional y reversible.") },
            confirmButton = {
                TextButton(onClick = {
                    requestBatteryOpt.launch(vm.batteryOptimizationIntent())
                    showBatteryDialog = false
                }) { Text("Abrir diálogo del sistema") }
            },
            dismissButton = { TextButton(onClick = { showBatteryDialog = false }) { Text("Cancelar") } },
        )
    }
}
