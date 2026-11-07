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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * GPU y diagnóstico.
 * - Detección: SoC, GPU, versión Vulkan, driver.
 * - En G99/Mali-G57 muestra "Dispositivo verificado por el usuario".
 * - Prueba de 30 s + registro manual del FPS del overlay.
 * - Optimización de batería, núcleo, log reciente y reporte de bug.
 */
@Composable
fun GpuDiagnosticsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())
    val logText by vm.logText.collectAsState()
    val pending by vm.pendingBenchmark.collectAsState()
    var fpsInput by remember { mutableStateOf("") }
    var showFpsDialog by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }

    val exportBug = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val s = settings
        if (uri != null && s != null) vm.exportBugReport(uri, s, gpu)
    }
    val requestBatteryOpt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.markBatteryOptimizationDisabled()
        vm.refreshLog()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("GPU y diagnóstico", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
            Text("Dispositivo: ${g.deviceModel.ifBlank { "—" }}")
            if (g.isUserVerifiedDevice) {
                Text("✓ Dispositivo verificado por el usuario: Child of Light va muy bien en este teléfono.", color = ColGold, fontWeight = FontWeight.Bold)
                NoteText("Por eso el preset por defecto es G99 Recomendado (1x, Frame Skip 0): se optimiza para mantener esa fluidez estable, no para recortar de más.")
            } else if (g.vendor.name == "MALI") {
                NoteText("Mali: hay cosas que definitivamente no funcionan y es posible que nunca funcionen, pero Vita3K+ trae arreglos para Mali (vertex fix, Double Buffer mejorado) y Child of Light, al ser 2D UbiArt, es más ligero que un juego 3D: a 1x tiene más chance. No se prometen 60 fps en Mali.")
            } else if (g.vendor.name == "ADRENO") {
                NoteText("Snapdragon/Adreno: usa Turnip para mejor compatibilidad (ver Ajustes → Driver GPU).")
            }
            OutlinedButton(onClick = { vm.detectGpu() }, modifier = Modifier.fillMaxWidth()) { Text("Volver a detectar GPU") }
        }

        SectionTitle("Núcleo")
        Text("Núcleo embebido (Etapa 2): ${if (vm.embeddedCorePresent()) "presente" else "NO presente"}")
        Text("Vita3K externo: ${vm.externalVita3kVersion() ?: "no instalado"}")
        Text("Firmware: ${vm.firmwareStatusLabel()}")
        NoteText("El firmware nunca es un paso inicial: el primer uso solo pide juego + DLCs. Importarlo es una opción avanzada en Ajustes.")
        Text("Backend: ${settings?.backend?.label ?: "—"} · Driver: ${settings?.gpuDriver ?: "—"}${if (settings?.turnipInstalled == true) " (${settings?.turnipVersion})" else ""}")
        Text("Resolución: ${settings?.resolutionLabel ?: "—"} · FPS overlay: ${if (settings?.showFpsOverlay == true) "ON" else "OFF"}")

        SectionTitle("Prueba de 30 segundos")
        NoteText("Lanza el juego con el preset actual. Juega 30 segundos mirando el FPS del overlay y al volver regístralo. Si es G99 Calidad (2x) y baja de 28 fps, vuelve a 1x automáticamente.")
        Button(onClick = { vm.startBenchmark() }, modifier = Modifier.fillMaxWidth()) { Text("Probar 30 segundos") }
        if (pending != null) {
            Text("Prueba en marcha: ${pending?.profile?.label}. Al volver, registra el FPS.", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { showFpsDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Registrar FPS visto en el overlay") }
            OutlinedButton(onClick = { vm.dismissBenchmark() }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar prueba") }
        }
        benchmarks.take(3).forEach { run ->
            Text("· ${run.profile.label}: ${run.fpsAvg?.let { "$it fps" } ?: "sin medir"} (${run.source})${if (run.autoReverted) " — volvió a 1x" else ""}")
        }

        SectionTitle("Batería y rendimiento")
        Text("Optimización de batería desactivada: ${if (vm.isBatteryOptimizationDisabled()) "sí" else "no"}")
        OutlinedButton(
            onClick = { showBatteryDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Desactivar optimización de batería") }
        NoteText("Evita que Android mate el juego en segundo plano o limite la GPU. Lo concede el usuario en el diálogo del sistema.")

        SectionTitle("Log reciente")
        Text(logText, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.refreshLog() }, modifier = Modifier.fillMaxWidth()) { Text("Actualizar log") }
        OutlinedButton(
            onClick = { exportBug.launch("col-bug-report.txt") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reportar bug (exportar log + ajustes + dispositivo)") }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showFpsDialog) {
        AlertDialog(
            onDismissRequest = { showFpsDialog = false },
            title = { Text("FPS medio de la prueba") },
            text = {
                Column {
                    Text("Escribe el FPS medio que viste en el overlay durante los 30 segundos (por ejemplo: 30).")
                    androidx.compose.material3.OutlinedTextField(
                        value = fpsInput,
                        onValueChange = { fpsInput = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("FPS medio") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fpsInput.toFloatOrNull()?.let { vm.recordBenchmarkFps(it) }
                    fpsInput = ""
                    showFpsDialog = false
                }) { Text("Registrar") }
            },
            dismissButton = { TextButton(onClick = { showFpsDialog = false }) { Text("Cancelar") } },
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
