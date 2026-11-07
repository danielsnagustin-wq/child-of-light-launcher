package com.danielsnagustin.childoflight.launcher.ui

import android.app.Application
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielsnagustin.childoflight.launcher.data.AchievementRepository
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.GpuDetector
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreReader
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreScan
import com.danielsnagustin.childoflight.launcher.data.TurnipDriverRepository
import com.danielsnagustin.childoflight.launcher.data.UiPreferencesRepository
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.AudioBackend
import com.danielsnagustin.childoflight.launcher.model.BenchmarkRun
import com.danielsnagustin.childoflight.launcher.model.BenchmarkSource
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.ImportResult
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.TROPHIES_27
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.model.shouldAutoRevertTo1x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val settingsRepo = SettingsRepository(context)
    private val library = GameLibraryRepository(context)
    private val touchRepo = TouchLayoutRepository(context)
    private val achievementsRepo = AchievementRepository(context)
    private val turnipRepo = TurnipDriverRepository(context)
    private val launcher = Vita3KLauncher(context)
    private val uiRepo = UiPreferencesRepository(context)

    val settingsFlow = settingsRepo.settingsFlow
    val gpuFlow = settingsRepo.gpuInfoFlow
    val benchmarksFlow = settingsRepo.benchmarksFlow
    val touchLayoutFlow = touchRepo.layoutFlow
    val profilesFlow = touchRepo.profilesFlow
    val activeProfileIdFlow = touchRepo.activeProfileIdFlow
    val achievementsFlow = achievementsRepo.progressFlow
    val uiFlow = uiRepo.uiFlow

    private val _status = MutableStateFlow(GameStatus())
    val status: StateFlow<GameStatus> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Trofeo recién desbloqueado: la UI muestra popup + sonido. */
    private val _justUnlocked = MutableStateFlow<TrophyProgress?>(null)
    val justUnlocked: StateFlow<TrophyProgress?> = _justUnlocked.asStateFlow()

    private val _trophyScan = MutableStateFlow<TrophyStoreScan?>(null)
    val trophyScan: StateFlow<TrophyStoreScan?> = _trophyScan.asStateFlow()

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    private val _pendingBenchmark = MutableStateFlow<BenchmarkRun?>(null)
    val pendingBenchmark: StateFlow<BenchmarkRun?> = _pendingBenchmark.asStateFlow()

    private val _generatedConfig = MutableStateFlow("")
    val generatedConfig: StateFlow<String> = _generatedConfig.asStateFlow()

    init {
        refreshStatus()
        refreshLog()
        viewModelScope.launch {
            val gpu = settingsRepo.gpuInfoFlow.first()
            if (gpu == null) detectGpu()
        }
    }

    fun clearMessage() { _message.value = null }
    fun clearJustUnlocked() { _justUnlocked.value = null }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = library.scanStatus()
            val title = _status.value.titleId?.id
            _trophyScan.value = if (title != null) {
                TrophyStoreReader.scan(library.vitaRoot, title)
            } else null
        }
    }

    fun refreshLog() {
        _logText.value = LauncherLog.readLast(context, 60)
    }

    fun refreshConfigPreview(settings: LauncherSettings) {
        _generatedConfig.value = Vita3kConfigGenerator.generate(settings, library.vitaRoot.absolutePath)
    }

    // --- GPU ---

    fun detectGpu() {
        viewModelScope.launch(Dispatchers.IO) {
            val gpu = GpuDetector.detect(context)
            settingsRepo.saveGpuInfo(gpu)
            val current = settingsRepo.settingsFlow.first()
            // Primer arranque: aplicar el preset del GPU detectado.
            val profile = defaultProfileFor(gpu)
            settingsRepo.applyPreset(profile, keepDriver = true, current = current)
            withContext(Dispatchers.Main) {
                _message.value = if (gpu.isUserVerifiedDevice) {
                    "GPU detectada: Helio G99 · Mali-G57 MC2. Dispositivo verificado por ti: " +
                        "Child of Light va muy bien. Preset aplicado: G99 / Mali-G57 — Recomendado."
                } else {
                    "GPU detectada: ${gpu.shortLabel}. Preset aplicado: ${defaultProfileFor(gpu).label}."
                }
            }
            LauncherLog.append(context, "gpu", "Detectada: ${gpu.renderer} | SoC ${gpu.socModel} | ${gpu.vulkanVersionLabel}")
        }
    }

    // --- Ajustes ---

    fun saveSettings(settings: LauncherSettings) {
        viewModelScope.launch { settingsRepo.save(settings) }
    }

    fun applyPreset(profile: GpuProfileId) {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.applyPreset(profile, keepDriver = true, current = current)
            _message.value = "Preset aplicado: ${profile.label}"
            LauncherLog.append(context, "preset", "Preset aplicado: ${profile.name}")
        }
    }

    fun resetSettings() {
        viewModelScope.launch {
            val gpu = settingsRepo.gpuInfoFlow.first()
            settingsRepo.resetToDefaults(gpu)
            _message.value = "Ajustes restablecidos al preset de tu GPU."
        }
    }

    /** Modo seguro: fuerza 1x, SDL, buffer grande, Thread Scheduling ON, High Accuracy OFF. */
    fun setSafeMode(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            val updated = if (enabled) applySafeMode(current) else current.copy(safeMode = false)
            settingsRepo.save(updated)
            _message.value = if (enabled) {
                "Modo seguro activado: 1x, SDL, buffer 2048, Thread Scheduling ON, High Accuracy OFF."
            } else {
                "Modo seguro desactivado. Tus ajustes anteriores no se restauran solos: vuelve a aplicar tu preset si lo necesitas."
            }
        }
    }

    /**
     * "Corregir audio": SDL + buffer 1024 + limpiar caché de shaders.
     * No reinicia el juego solo: el núcleo no está embebido en Etapa 1,
     * así que se avisa de que hay que volver a lanzar el juego.
     */
    fun fixAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(
                current.copy(
                    audioBackend = AudioBackend.SDL,
                    audioBufferSize = 1024,
                    ngsEnable = true,
                    shaderCache = true,
                ),
            )
            val cleared = turnipRepo.clearShaderCache()
            withContext(Dispatchers.Main) {
                _message.value = "Audio corregido: SDL + buffer 1024 + NGS ON + caché de shaders limpiada " +
                    "(${cleared.deletedFiles} archivos). Vuelve a lanzar el juego para aplicar el cambio."
            }
            LauncherLog.append(context, "audio", "Corregir audio: SDL + buffer 1024, caché limpiada")
        }
    }

    fun clearShaderCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val cleared = turnipRepo.clearShaderCache()
            withContext(Dispatchers.Main) {
                _message.value = "Caché de shaders limpiada: ${cleared.deletedFiles} archivos. " +
                    "El stutter de la primera partida es normal hasta que la caché se vuelva a llenar."
            }
        }
    }

    // --- Importación ---

    private fun fileNameOf(uri: Uri): String {
        // El nombre real viene de OpenableColumns.DISPLAY_NAME; el último
        // segmento de una URI SAF es un ID de documento, no el nombre.
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
        }
    }

    fun importGame(uri: Uri) = importWith(uri) { name -> library.importGame(uri, name) }
    fun importDlc(uri: Uri) = importWith(uri) { name -> library.importDlc(uri, name) }
    fun importUpdate(uri: Uri) = importWith(uri) { name -> library.importUpdate(uri, name) }
    fun importFirmware(uri: Uri) = importWith(uri) { name -> library.importFirmware(uri, name) }

    private fun importWith(uri: Uri, block: (String) -> ImportResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            val result = block(name)
            withContext(Dispatchers.Main) {
                _message.value = when (result) {
                    is ImportResult.Success -> result.detail
                    is ImportResult.DlcSuccess -> "DLC ${result.dlcId} instalado para ${result.titleId.id}."
                    is ImportResult.UpdateSuccess -> "Update instalado para ${result.titleId.id}."
                    is ImportResult.FirmwareSuccess -> "Firmware ${result.fileName} guardado."
                    is ImportResult.PkgStored -> ".pkg guardado en ${result.storedPath}. " +
                        "Su instalación real la hace Vita3K (Etapa 2): NO se muestra como juego instalado."
                    is ImportResult.Rejected -> "No se instaló nada: ${result.reason}"
                    is ImportResult.Failed -> "Error: ${result.reason}"
                }
            }
            refreshStatus()
        }
    }

    // --- Turnip ---

    fun installTurnip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            val result = turnipRepo.installDriverZip(uri, name)
            val current = settingsRepo.settingsFlow.first()
            result.onSuccess { savedName ->
                settingsRepo.save(
                    current.copy(
                        turnipInstalled = true,
                        turnipVersion = savedName,
                        customDriverName = savedName.removeSuffix(".zip"),
                        gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP,
                    ),
                )
                withContext(Dispatchers.Main) {
                    _message.value = "Turnip instalado: $savedName. Vita3K limpiará la caché de shaders al arrancar con el nuevo driver."
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { _message.value = "No se instaló Turnip: ${e.message}" }
            }
        }
    }

    fun useSystemDriver() {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(current.copy(gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM))
            _message.value = "Driver del sistema activado."
        }
    }

    // --- Jugar / benchmark ---

    fun play() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = settingsRepo.settingsFlow.first()
            val outcome = launcher.launch(library, settings)
            withContext(Dispatchers.Main) {
                _message.value = when (outcome) {
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternal ->
                        "Vita3K abierto. Config escrita en ${outcome.configPath}."
                    is Vita3KLauncher.LaunchOutcome.EmbeddedCorePresent ->
                        "Núcleo embebido detectado, pero falta el punto de entrada JNI (Etapa 2)."
                    is Vita3KLauncher.LaunchOutcome.Blocked -> outcome.reason
                }
            }
            refreshStatus()
        }
    }

    /** Prueba de 30 s: lanza el juego con el preset actual y deja pendiente registrar el FPS visto en el overlay. */
    fun startBenchmark() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = settingsRepo.settingsFlow.first()
            val gpu = settingsRepo.gpuInfoFlow.first()
            val pre = launcher.checkPrerequisites(library)
            if (!pre.ready) {
                withContext(Dispatchers.Main) { _message.value = pre.missing.joinToString("\n") }
                return@launch
            }
            val run = BenchmarkRun(
                profile = settings.activeProfile,
                gpuLabel = gpu?.shortLabel ?: "GPU no detectada",
                startedAtMillis = System.currentTimeMillis(),
                durationSeconds = 30,
                source = BenchmarkSource.NOT_MEASURED,
                note = "Prueba de 30 s con preset ${settings.activeProfile.label}. FPS sin medir: anota el FPS del overlay al volver.",
            )
            _pendingBenchmark.value = run
            val outcome = launcher.launch(library, settings)
            withContext(Dispatchers.Main) {
                _message.value = when (outcome) {
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternal ->
                        "Prueba de 30 s en marcha. Juega 30 segundos, mira el FPS del overlay y al volver regístralo aquí."
                    is Vita3KLauncher.LaunchOutcome.Blocked -> {
                        _pendingBenchmark.value = null
                        outcome.reason
                    }
                    is Vita3KLauncher.LaunchOutcome.EmbeddedCorePresent ->
                        "Núcleo embebido detectado, falta el punto de entrada JNI (Etapa 2)."
                }
            }
        }
    }

    /**
     * Registra el FPS medio visto en el overlay. Si es el preset G99
     * Calidad 2x y baja de 28 fps, vuelve a 1x automáticamente (si el
     * ajuste está activado). La medición es manual porque en Etapa 1
     * no hay núcleo embebido que mida FPS por dentro.
     */
    fun recordBenchmarkFps(fps: Float) {
        val pending = _pendingBenchmark.value ?: return
        viewModelScope.launch {
            val settings = settingsRepo.settingsFlow.first()
            val revert = shouldAutoRevertTo1x(fps, pending.profile, settings.autoRevertTo1xIfBelow28Fps)
            val completed = pending.copy(fpsAvg = fps, source = BenchmarkSource.MANUAL_OVERLAY, autoReverted = revert)
            settingsRepo.recordBenchmark(completed)
            _pendingBenchmark.value = null
            if (revert) {
                settingsRepo.applyPreset(GpuProfileId.G99_RECOMMENDED, keepDriver = true, current = settings)
                _message.value = "FPS medio: $fps. Menos de 28 fps con Calidad 2x: vuelto a 1x automáticamente (preset G99 Recomendado)."
            } else {
                _message.value = "Prueba registrada: $fps fps medios con ${pending.profile.label}."
            }
            LauncherLog.append(context, "benchmark", "Prueba ${pending.profile.name}: $fps fps, autoRevert=$revert")
        }
    }

    fun dismissBenchmark() { _pendingBenchmark.value = null }

    // --- Logros ---

    fun unlockTrophy(number: Int) {
        viewModelScope.launch {
            val isNew = achievementsRepo.unlock(number)
            if (isNew) {
                val progress = achievementsRepo.progressFlow.first()
                val trophy = progress.firstOrNull { it.trophy.number == number }
                _justUnlocked.value = trophy
                playUnlockSound()
                val name = TROPHIES_27.firstOrNull { it.number == number }?.name ?: "Trofeo $number"
                LauncherLog.append(context, "trophy", "Logro desbloqueado: $name")
            } else {
                _message.value = "Ese logro ya estaba desbloqueado."
            }
        }
    }

    fun lockTrophy(number: Int) {
        viewModelScope.launch { achievementsRepo.lock(number) }
    }

    fun resetTrophies() {
        viewModelScope.launch {
            achievementsRepo.resetAll()
            _message.value = "Progreso de logros borrado (local, no afecta a PSN porque nunca se sincroniza)."
        }
    }

    private fun playUnlockSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (_: Exception) {
            // Sin sonido si el dispositivo no puede reproducirlo; el popup sigue.
        }
    }

    fun exportAchievements(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = achievementsRepo.exportText()
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                withContext(Dispatchers.Main) { _message.value = "Progreso de logros exportado." }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo exportar logros: ${e.message}" }
            }
        }
    }

    fun importAchievements(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val result = achievementsRepo.importText(text)
                withContext(Dispatchers.Main) {
                    _message.value = "Logros importados: ${result.newlyUnlocked} nuevos, " +
                        "${result.addedOrKept} en total, ${result.ignoredInvalid} entradas inválidas ignoradas."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo importar logros: ${e.message}" }
            }
        }
    }

    // --- Layout táctil ---

    fun saveTouchLayout(layout: List<com.danielsnagustin.childoflight.launcher.data.TouchControl>) {
        viewModelScope.launch { touchRepo.save(layout) }
    }

    fun resetTouchLayout() {
        viewModelScope.launch {
            touchRepo.reset()
            _message.value = "Layout táctil restablecido al diseño por defecto."
        }
    }

    /** Borra un botón del perfil activo (con confirmación en la UI). Desaparece del overlay. */
    fun deleteTouchControl(controlId: String) {
        viewModelScope.launch {
            val layout = touchRepo.layoutFlow.first()
            val updated = layout.filter { it.id != controlId }
            if (updated.size == layout.size) return@launch
            touchRepo.save(updated)
            _message.value = "Botón borrado del layout. Ya no aparece en el overlay."
            LauncherLog.append(context, "touch", "Botón $controlId borrado del layout")
        }
    }

    /** Añade un botón nuevo al perfil activo (asignado a Cruz por defecto, se puede remapear). */
    fun addTouchControl() {
        viewModelScope.launch {
            val layout = touchRepo.layoutFlow.first()
            val n = layout.count { it.kind == "button" } + 1
            val control = com.danielsnagustin.childoflight.launcher.data.TouchControl(
                id = "btn_custom_$n-${System.currentTimeMillis() % 100000}",
                label = "B$n",
                kind = "button",
                vitaButton = com.danielsnagustin.childoflight.launcher.data.VitaButton.CROSS,
                xFraction = 0.5f, yFraction = 0.5f, sizeDp = 64, opacity = 0.8f,
            )
            touchRepo.save(layout + control)
            _message.value = "Botón nuevo añadido en el centro. Muévelo y remapealo."
        }
    }

    fun setActiveControlProfile(id: String) {
        viewModelScope.launch {
            touchRepo.setActiveProfile(id)
            _message.value = "Perfil de control activado."
        }
    }

    fun createControlProfile(name: String) {
        viewModelScope.launch {
            val layout = touchRepo.layoutFlow.first()
            val profile = touchRepo.createProfile(name, layout)
            _message.value = "Perfil \"${profile.name}\" creado y activado."
        }
    }

    fun duplicateControlProfile(id: String) {
        viewModelScope.launch {
            val copy = touchRepo.duplicateProfile(id)
            _message.value = if (copy != null) "Perfil duplicado: \"${copy.name}\"." else "No se pudo duplicar el perfil."
        }
    }

    fun renameControlProfile(id: String, newName: String) {
        viewModelScope.launch {
            val ok = touchRepo.renameProfile(id, newName)
            _message.value = if (ok) "Perfil renombrado." else "Nombre no válido."
        }
    }

    fun deleteControlProfile(id: String) {
        viewModelScope.launch {
            val ok = touchRepo.deleteProfile(id)
            _message.value = if (ok) "Perfil borrado." else "No se puede borrar: es el último perfil que queda."
        }
    }

    fun exportControlProfile(uri: Uri, profile: ControlProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = touchRepo.exportProfileText(profile)
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                withContext(Dispatchers.Main) { _message.value = "Perfil \"${profile.name}\" exportado." }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo exportar el perfil: ${e.message}" }
            }
        }
    }

    fun importControlProfile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val profile = touchRepo.importProfileText(text)
                withContext(Dispatchers.Main) {
                    _message.value = if (profile != null) "Perfil \"${profile.name}\" importado." else "Archivo no válido: no es un perfil de control exportado."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo importar el perfil: ${e.message}" }
            }
        }
    }

    // --- Personalización del launcher ---

    fun saveUiPrefs(prefs: com.danielsnagustin.childoflight.launcher.model.UiPreferences) {
        viewModelScope.launch { uiRepo.save(prefs) }
    }

    fun resetAllCustomization() {
        viewModelScope.launch {
            uiRepo.resetAll()
            touchRepo.reset()
            _message.value = "Personalización restablecida: tema, acento, fondo, texto, idioma, portada y controles."
        }
    }

    // --- Borrar no usados ---

    fun deleteDlc(dlcId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _status.value.titleId?.id ?: return@launch
            val ok = library.deleteDlc(title, dlcId)
            withContext(Dispatchers.Main) {
                _message.value = if (ok) "DLC $dlcId borrado de ux0/addcont." else "No se pudo borrar el DLC $dlcId."
            }
            refreshStatus()
        }
    }

    fun deleteTurnipDriver() {
        viewModelScope.launch(Dispatchers.IO) {
            val hadFile = turnipRepo.deleteDriver()
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(
                current.copy(
                    turnipInstalled = false,
                    turnipVersion = "",
                    customDriverName = "",
                    gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM,
                ),
            )
            withContext(Dispatchers.Main) {
                _message.value = if (hadFile) "Driver Turnip borrado. Se usa el driver del sistema." else "No había driver Turnip instalado."
            }
        }
    }

    fun deleteSavesOnly() {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _status.value.titleId?.id ?: return@launch
            val ok = library.deleteSavesOnly(title)
            withContext(Dispatchers.Main) {
                _message.value = if (ok) "Saves borrados. El juego y los DLCs siguen instalados." else "No había saves que borrar."
            }
            refreshStatus()
        }
    }

    fun hideGpuPreset(profile: GpuProfileId) {
        viewModelScope.launch {
            val prefs = uiRepo.uiFlow.first()
            val current = settingsRepo.settingsFlow.first()
            if (profile == current.activeProfile) {
                _message.value = "No se puede ocultar el preset activo. Cambia primero a otro preset."
                return@launch
            }
            uiRepo.save(prefs.copy(hiddenPresets = prefs.hiddenPresets + profile))
            _message.value = "Preset ${profile.label} oculto de la lista."
        }
    }

    fun showAllGpuPresets() {
        viewModelScope.launch {
            val prefs = uiRepo.uiFlow.first()
            uiRepo.save(prefs.copy(hiddenPresets = emptySet()))
            _message.value = "Todos los presets de GPU visibles de nuevo."
        }
    }

    /** ¿Hay mando físico conectado ahora mismo? (InputDevice real). */
    fun connectedGamepads(): List<String> = launcher.connectedGamepads()

    // --- Saves ---

    fun exportSaves(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _status.value.titleId?.id
            if (title == null) {
                withContext(Dispatchers.Main) { _message.value = "No hay juego instalado: no hay saves que exportar." }
                return@launch
            }
            val result = library.exportSaves(title, uri)
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Saves exportados: $it archivos." },
                    onFailure = { "No se pudo exportar saves: ${it.message}" },
                )
            }
        }
    }

    fun importSaves(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _status.value.titleId?.id
            if (title == null) {
                withContext(Dispatchers.Main) { _message.value = "Instala primero el juego para importar saves." }
                return@launch
            }
            val result = library.importSaves(title, uri)
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Saves importados: $it archivos." },
                    onFailure = { "No se pudo importar saves: ${it.message}" },
                )
            }
            refreshStatus()
        }
    }

    fun deleteGameData() {
        viewModelScope.launch(Dispatchers.IO) {
            val title = _status.value.titleId?.id ?: return@launch
            val deleted = library.deleteGameData(title)
            withContext(Dispatchers.Main) {
                _message.value = "Datos borrados: ${deleted.joinToString(", ")}. El launcher queda vacío, listo para importar de nuevo."
            }
            refreshStatus()
        }
    }

    // --- Diagnóstico / reporte de bug ---

    fun externalVita3kVersion(): String? = launcher.externalVita3kVersion()
    fun embeddedCorePresent(): Boolean = launcher.embeddedCorePresent()
    fun isBatteryOptimizationDisabled(): Boolean = launcher.isBatteryOptimizationDisabled()
    fun batteryOptimizationIntent() = launcher.batteryOptimizationIntent()

    fun markBatteryOptimizationDisabled() {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(current.copy(batteryOptimizationDisabled = true))
        }
    }

    fun buildBugReportText(settings: LauncherSettings, gpu: GpuInfo?): String {
        val status = _status.value
        return buildString {
            appendLine("=== Reporte de bug — Child of Light Launcher (Etapa 1) ===")
            appendLine("Fecha: ${java.util.Date()}")
            appendLine("App: com.danielsnagustin.childoflight.launcher v0.1.0")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})")
            appendLine("SoC: ${gpu?.socModel ?: "no detectado"}")
            appendLine("GPU: ${gpu?.renderer ?: "no detectada"} (${gpu?.vendor})")
            appendLine("GLES: ${gpu?.glesVersion ?: ""}")
            appendLine("Vulkan: ${gpu?.vulkanVersionLabel ?: "desconocido"} (soportado: ${gpu?.vulkanSupported})")
            appendLine("Dispositivo verificado por el usuario: ${gpu?.isUserVerifiedDevice}")
            appendLine()
            appendLine("--- Juego ---")
            appendLine("Instalado: ${status.gameInstalled} (${status.titleId?.id ?: "-"})")
            appendLine("DLCs: ${status.dlcCount} | Update: ${status.updateInstalled} | Firmware: ${status.firmwareInstalled}")
            appendLine("Saves: ${status.saves.size} archivos")
            appendLine()
            appendLine("--- Núcleo ---")
            appendLine("Núcleo embebido (Etapa 2): ${if (embeddedCorePresent()) "presente" else "NO presente"}")
            appendLine("Vita3K externo: ${externalVita3kVersion() ?: "no instalado"}")
            appendLine()
            appendLine("--- Ajustes activos ---")
            appendLine("Perfil: ${settings.activeProfile.label}")
            appendLine("Backend: ${settings.backend} | Resolución: ${settings.resolutionLabel} | Filtro: ${settings.screenFilter}")
            appendLine("Aniso: ${settings.anisotropicFiltering}x | VSync: ${settings.vSync} | Async: ${settings.asyncPipelineCompilation}")
            appendLine("High Accuracy: ${settings.highAccuracy} | Surface Sync: ${settings.surfaceSyncEnabled} | Memory Mapping: ${settings.memoryMapping}")
            appendLine("Audio: ${settings.audioBackend} | Buffer: ${settings.audioBufferSize} | Volumen: ${settings.audioVolume} | NGS: ${settings.ngsEnable}")
            appendLine("CPU opt (JIT): ${settings.cpuOpt} | FPS hack: ${settings.fpsHack} | Frame limiter: ${settings.frameLimiter.label} | Frame skip: ${settings.frameSkipping}")
            appendLine("Thread Scheduling: ${settings.accurateThreadScheduling} | Modo seguro: ${settings.safeMode}")
            appendLine("Driver: ${settings.gpuDriver} | Turnip: ${settings.turnipVersion} (instalado: ${settings.turnipInstalled})")
            appendLine()
            appendLine("--- config.yml generado ---")
            appendLine(Vita3kConfigGenerator.generate(settings, library.vitaRoot.absolutePath))
            appendLine("--- Log del launcher (últimas líneas) ---")
            appendLine(LauncherLog.readLast(context, 60))
        }
    }

    fun exportBugReport(uri: Uri, settings: LauncherSettings, gpu: GpuInfo?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = buildBugReportText(settings, gpu)
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                withContext(Dispatchers.Main) { _message.value = "Reporte de bug exportado. Compártelo con el desarrollador del launcher." }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo exportar el reporte: ${e.message}" }
            }
        }
    }
}
