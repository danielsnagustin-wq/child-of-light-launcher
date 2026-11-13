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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
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
import com.danielsnagustin.childoflight.launcher.model.GpuBackend
import com.danielsnagustin.childoflight.launcher.model.effectiveMappingMethod
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.model.supportedMappingMethods
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
    var fpsInput by rememberSaveable { mutableStateOf("") }
    var showFpsDialog by rememberSaveable { mutableStateOf(false) }
    var fpsError by rememberSaveable { mutableStateOf(false) }
    var showBatteryDialog by rememberSaveable { mutableStateOf(false) }

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
        // Ronda 0.2.70 Area 6 (P1-14/P1-15/P1-16): la pantalla ya no
        // recoge 10 flujos en la raiz: cada seccion recoge solo su
        // flujo y solo se recompone cuando cambia lo que dibuja. El
        // log vive en su propio hijo (P1-16) y el nucleo y la app
        // externa se leen de flujos (P1-15): nunca System.loadLibrary
        // ni getPackageInfo en composicion.
        GpuHeaderSection(vm)
        CoreSection(vm)
        ConfigSummarySection(vm)
        RecentErrorsSection(vm)
        BenchmarkSection(vm, onRegisterFps = { showFpsDialog = true })
        BatterySection(vm, onRequestBatteryDialog = { showBatteryDialog = true })
        LogSection(vm)
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

// Ronda 0.2.70 Area 6 (P1-14): cada seccion recoge solo su flujo.

@Composable
private fun GpuHeaderSection(vm: LauncherViewModel) {
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val settings by vm.settingsFlow.collectAsState(initial = null)
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
            // Ronda 0.2.65 (U-P2-4): quitada la frase "Child of
            // Light va muy bien en este procesador": promete un
            // rendimiento que el síntoma abierto de lentitud
            // contradice. Queda solo el hecho verificado (el
            // procesador lo verificó el usuario) y el preset.
            Text("✓ Procesador verificado por el usuario.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            NoteText("Por eso el preset por defecto es G99 Recomendado (1x): prima la estabilidad, no recortar de más.")
        } else if (g.vendor.name == "MALI") {
            // Ronda 0.2.64 (P2-3 Mali): la nota anterior decía que
            // el núcleo trae un "vertex fix" para Mali: ese arreglo
            // solo existe en el fork Vita3K-Plus (su README), NO en
            // el pin (3e43bc80). Lo que sí trae el núcleo son las
            // mejoras de Double Buffer de Vita3K-Android v9/v10.
            NoteText("Mali: hay cosas que definitivamente no funcionan y es posible que nunca funcionen, pero el núcleo trae mejoras de Double Buffer y Child of Light, al ser 2D UbiArt, es más ligero que un juego 3D: a 1x tiene más chance. No se prometen 60 fps en Mali.")
        } else if (g.vendor.name == "ADRENO") {
            NoteText("Snapdragon/Adreno: usa Turnip para mejor compatibilidad (ver Ajustes → Driver GPU).")
        } else if (g.vendor.name == "XCLIPSE") {
            // Ronda 0.2.64 (H-02): Xclipse (Samsung/AMD RDNA,
            // Exynos 2200/2400/1480/2600): sin perfil propio
            // todavía, 1x seguro por ahora. Vulkan 1.1 (Exynos
            // 2200) / 1.3 (2400): suficiente para el núcleo. Sin
            // Turnip (solo Adreno). Se puede probar 2x a mano con
            // la prueba de 30 s; si baja de 28 fps, vuelve a 1x.
            // Ronda 0.2.65 (U-P2-3): sin el fabricante del teléfono
            // en la UI visible (Xclipse es AMD RDNA; el nombre del
            // fabricante no aporta nada al diagnóstico).
            NoteText("Xclipse (AMD RDNA): sin perfil propio todavía; se usa el perfil Mali seguro (1x, Page Table). Sin Turnip. Se puede probar 2x a mano con la prueba de 30 s; si baja de 28 fps, vuelve a 1x.")
        } else if (g.vendor.name == "POWERVR") {
            // Ronda 0.2.64 (H-03/H-06): nota por gama, no una sola
            // para todo PowerVR. DXT/CXTP = gama alta (Tensor
            // G5/G6, DXT-48-1536): 1x seguro por ahora, se puede
            // probar 2x a mano. Rogue GE8xxx = gama baja: quedarse
            // en 1x. Y OpenGL ya NO se recomienda como primer
            // intento: sin evidencia en PowerVR (la mejora histórica
            // de PowerVR en el fork fue del lado Vulkan).
            if (g.isPowerVrDxt) {
                NoteText("PowerVR de gama alta (DXT/CXTP, Tensor G5/G6): se usa el perfil seguro (1x, Page Table). Se puede probar 2x a mano con la prueba de 30 s; si baja de 28 fps, vuelve a 1x. Si el juego va mal, prueba el modo seguro en Ajustes; el backend OpenGL es un último recurso sin probar en PowerVR.")
            } else {
                NoteText("PowerVR (gama baja, Rogue GE8xxx): se usa el perfil seguro (1x, Page Table). Quedarse en 1x: esta gama no sostiene 2x. Si el juego va mal, prueba el modo seguro en Ajustes; el backend OpenGL es un último recurso sin probar en PowerVR.")
            }
        } else {
            NoteText("GPU sin perfil propio (o no detectada): se usa el perfil Genérico seguro (1x, Page Table). Ninguna GPU se queda sin perfil.")
        }
        OutlinedButton(onClick = { vm.detectGpu() }, modifier = Modifier.fillMaxWidth()) { Text("Volver a detectar GPU") }
    }
}

@Composable
private fun CoreSection(vm: LauncherViewModel) {
    // P1-15: nucleo y app externa se leen de flujos. embeddedCoreFlow
    // ya existia (presencia del archivo .so, sin cargarlo) y
    // externalVita3k es el StateFlow que refreshStatus() alimenta con
    // la version cacheada: antes se llamaba a embeddedCorePresent()
    // (System.loadLibrary en la primera llamada, en mitad de la
    // composicion) y a externalVita3kVersion() (getPackageInfo, IPC)
    // en CADA recomposicion.
    val embeddedCore by vm.embeddedCoreFlow.collectAsState()
    val externalVersion by vm.externalVita3k.collectAsState()
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val turnipNotLoaded by vm.turnipRequestedButNotLoaded.collectAsState()
    // Ronda 0.2.64 (P1-3 Mali): al entrar, preguntar al núcleo la
    // máscara de mapeo soportada (solo si el núcleo está presente).
    // Antes era LaunchedEffect(Unit) con embeddedCorePresent(): ahora
    // el efecto depende del flujo y no carga la .so en composicion.
    LaunchedEffect(embeddedCore) {
        if (embeddedCore == true) vm.refreshMappingMask()
    }
    SectionTitle("Núcleo")
    Text("Núcleo embebido: ${when (embeddedCore) { true -> "presente"; false -> "NO presente"; null -> "comprobando…" }}")
    Text("App externa: ${externalVersion ?: "no instalada"}")
    Text("Firmware: ${com.danielsnagustin.childoflight.launcher.data.FirmwareResolver.label(firmwareStatus)}")
    NoteText("El firmware nunca es un paso inicial: el primer uso solo pide juego + DLCs. Se importa en Importar, junto al juego y los DLC.")
    Text("Backend: ${settings?.backend?.label ?: "—"} · Driver: ${settings?.gpuDriver ?: "—"}${if (settings?.turnipInstalled == true) " (${settings?.turnipVersion})" else ""}")
    // Ronda 0.2.65 (C-P2-5): Turnip pedido pero no cargado por el
    // núcleo: la máscara y el rendimiento son los del sistema.
    if (turnipNotLoaded) {
        Text("⚠ Turnip pedido pero no cargado: se usa el driver del sistema.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
    Text("Resolución: ${settings?.resolutionLabel ?: "—"} · FPS overlay: ${if (settings?.showFpsOverlay == true) "sí" else "no"}")
}

@Composable
private fun ConfigSummarySection(vm: LauncherViewModel) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())
    val mappingMask by vm.supportedMappingMask.collectAsState(initial = null)
    val embeddedCore by vm.embeddedCoreFlow.collectAsState()
    val g = gpu
    // Ola 3, Etapa 10: configuración activa resumida, último FPS y
    // errores recientes del log (el modelo del dispositivo solo se
    // muestra en Ajustes → Diagnóstico, no aquí).
    SectionTitle("Resumen de la configuración activa")
    // Ronda 0.2.70 Area 6 (6-P2-7): derivado del State de
    // benchmarks: solo se recalcula cuando cambia la lista.
    val lastFps by remember { derivedStateOf { benchmarks.firstOrNull { it.fpsAvg != null }?.fpsAvg } }
    Text("Perfil activo: ${settings?.activeProfile?.label ?: "—"}")
    Text("Backend efectivo: ${settings?.let { resolveBackend(it, g) }?.label ?: "—"} · Resolución: ${settings?.resolutionLabel ?: "—"} · Filtro: ${settings?.screenFilter ?: "—"}")
    // R5-A1-14: VSync EFECTIVO (el modo seguro y el 60 FPS lo
    // fuerzan OFF aunque el guardado sea ON — ronda 0.2.64: el
    // 60 FPS YA NO lo fuerza ON): settings es delegada, así que
    // se captura con let, no con smart cast. Igual que
    // SettingsDiagnosticsScreen.
    // Ronda 0.2.66 (G11-06): con backend Vulkan la VSync elige
    // el modo de presentación (ON = Fifo, OFF = Mailbox): se
    // muestra el modo, no "— (sin efecto en Vulkan)" (eso era
    // verdad hasta la 0.2.65). Y el resumen muestra los valores
    // EFECTIVOS (frame pacing): mapeo de memoria efectivo
    // (ronda 0.2.66, G11-02: el launcher resuelve el efectivo con
    // la máscara del driver al escribir config.yml; antes el
    // núcleo caía a Double Buffer en silencio si el pedido no
    // estaba soportado) y Surface Sync efectiva.
    val effectiveBackend = settings?.let { resolveBackend(it, g) }
    val vsyncText = if (effectiveBackend == GpuBackend.VULKAN) {
        // Ronda 0.2.68 (G11-06): en Vulkan la VSync es el selector
        // de present mode: ON = Fifo, OFF = Mailbox.
        if (settings?.let { effectiveSettings(it).vSync } == true) "sí (Fifo)" else "no (Mailbox)"
    } else if (settings?.let { effectiveSettings(it).vSync } == true) {
        "sí"
    } else {
        "no"
    }
    Text("VSync: $vsyncText · Modo 60 FPS: ${if (settings?.fpsHack == true) "sí" else "no"} · Modo seguro: ${if (settings?.safeMode == true) "sí" else "no"}")
    val effectiveMapping = settings?.let {
        val eff = effectiveSettings(it)
        effectiveMappingMethod(eff.memoryMapping, mappingMask ?: 0)
    }
    Text("Mapeo de memoria: pedido ${settings?.memoryMapping?.label ?: "—"} · efectivo ${effectiveMapping?.label ?: "—"}${if (mappingMask != null && mappingMask != 0) " · soportados: ${supportedMappingMethods(mappingMask ?: 0).joinToString(", ") { it.label }}" else ""} · Surface Sync: ${if (settings?.let { effectiveSettings(it).surfaceSyncEnabled } == true) "sí" else "no"}")
    if (mappingMask == null && embeddedCore == true) {
        Text("Mapeo de memoria soportado: preguntando al núcleo…")
    }
    Text("Último FPS (prueba de 30 s): ${lastFps?.let { "$it fps" } ?: "sin medir"}")
}

@Composable
private fun RecentErrorsSection(vm: LauncherViewModel) {
    val logText by vm.logText.collectAsState()
    // R2-P1-02 (parte pantalla): el log en pantalla mostraba rutas
    // absolutas con el nombre del paquete. Mismo replace que la
    // exportación del reporte (LauncherViewModel.buildBugReportText):
    // el nombre del paquete nunca sale en texto visible.
    // Ronda 0.2.55 P1-13: el replace se memoriza por logText (antes
    // recorría el string entero en cada recomposición).
    val safeLogText = remember(logText) { logText.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher") }
    val uiPrefs = LocalUiPreferences.current
    SectionTitle("Errores recientes del log")
    // Ronda 0.2.55 P1-13: el filtrado se memoriza por safeLogText
    // (líneas + lowercase + regex por línea en cada recomposición).
    val recentErrors = remember(safeLogText) { extractRecentErrorLines(safeLogText) }
    if (recentErrors.isEmpty()) {
        Text("Sin errores recientes en el log.", color = if (isDarkUi(uiPrefs)) ColTurquoise else ColLavenderBlue)
    } else {
        recentErrors.forEach { line -> Text("• $line", style = MaterialTheme.typography.bodySmall) }
    }
    NoteText("El informe exportado (Reportar bug) es solo texto: dispositivo, ajustes, estado y errores recientes. No adjunta ningún archivo privado.")
}

@Composable
private fun BenchmarkSection(vm: LauncherViewModel, onRegisterFps: () -> Unit) {
    val pending by vm.pendingBenchmark.collectAsState()
    val benchmarks by vm.benchmarksFlow.collectAsState(initial = emptyList())
    val launching by vm.launchingFlow.collectAsState()
    SectionTitle("Prueba de 30 segundos")
    NoteText("Lanza el juego con el preset actual. El FPS se mide automáticamente mientras juegas y al volver se registra solo. Si no hay medición (app externa), juega 30 segundos mirando el FPS del overlay y regístralo a mano. La prueba mide solo en partida confirmada: juega hasta estar dentro de la partida (no en el título ni en los menús) antes de contar los 30 s; las muestras del título no miden el juego. Si el perfil es 2x (G99 Calidad o Adreno Calidad/Turnip) y baja de 28 fps, vuelve a 1x automáticamente.")
    PrimaryButton("Jugar 30 segundos", onClick = { vm.startBenchmark() }, enabled = !launching)
    if (pending != null) {
        Text("Prueba en marcha: ${pending?.profile?.label}. Al volver, el FPS se registra solo si el juego lo midió; si no, regístralo a mano.", fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onRegisterFps, modifier = Modifier.fillMaxWidth()) { Text("Registrar FPS visto en el overlay") }
        OutlinedButton(onClick = { vm.dismissBenchmark() }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar prueba") }
    }
    benchmarks.take(3).forEach { run ->
        key(run.profile) {
            Text("· ${run.profile.label}: ${run.fpsAvg?.let { "$it fps" } ?: "sin medir"} (${benchmarkSourceLabel(run.source)})${if (run.autoReverted) " — volvió a 1x" else ""}")
        }
    }
}

@Composable
private fun BatterySection(vm: LauncherViewModel, onRequestBatteryDialog: () -> Unit) {
    val batteryOptDisabled by vm.batteryOptDisabledFlow.collectAsState()
    SectionTitle("Batería y rendimiento")
    Text("Optimización de batería desactivada: ${if (batteryOptDisabled) "sí" else "no"}")
    OutlinedButton(
        onClick = onRequestBatteryDialog,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Desactivar optimización de batería") }
    NoteText("Evita que Android mate el juego en segundo plano o limite la GPU. Lo concede el usuario en el diálogo del sistema.")
}

@Composable
private fun LogSection(vm: LauncherViewModel) {
    // P1-16: el log entero vive en este hijo: solo se recompone
    // cuando cambia logText, no con cada emision de los demas flujos
    // de la pantalla. El replace se memoriza por logText (R2-P1-02 /
    // ronda 0.2.55 P1-13: el nombre del paquete nunca sale en texto
    // visible).
    val logText by vm.logText.collectAsState()
    val safeLogText = remember(logText) { logText.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher") }
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val exportBug = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val s = settings
        if (uri != null && s != null) vm.exportBugReport(uri, s, gpu)
    }
    SectionTitle("Log reciente")
    Text(safeLogText, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { vm.refreshLog() }, modifier = Modifier.fillMaxWidth()) { Text("Actualizar log") }
    OutlinedButton(
        onClick = { exportBug.launch("col-bug-report.txt") },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Reportar bug (exportar log + ajustes + dispositivo)") }
}
