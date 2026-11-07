package com.danielsnagustin.childoflight.launcher.ui

import android.app.Application
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danielsnagustin.childoflight.launcher.data.AchievementRepository
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.GpuDetector
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.SessionManager
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
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
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

/** Acción que puede ofrecer el diálogo de aviso importante. */
enum class AlertAction { INSTALL_VITA3K, GO_IMPORT, DELIVER_PKG_TO_VITA3K, CONFIRM_PKG_INSTALLED, REPLACE_DUPLICATE }

/**
 * Un .pkg guardado al que le falta su work.bin (licencia). La tarjeta
 * de Ajustes lo muestra con el botón "Elegir work.bin".
 */
data class PendingPkgLicense(
    val titleId: String,
    val kind: com.danielsnagustin.childoflight.launcher.model.PkgKind,
    val contentId: String,
    /** Para DLC: id del .pkg guardado (nombre del contenido saneado). */
    val dlcId: String?,
    val storedPath: String,
)

/**
 * Mensaje importante que la UI muestra como DIÁLOGO (no como Snackbar
 * fugaz): el usuario debe poder leerlo entero y actuar. Los errores de
 * importar y de JUGAR llegaban solo como Snackbar de 4 segundos: el
 * usuario veía "no pasa nada" y no sabía qué había fallado.
 */
data class AlertInfo(val text: String, val action: AlertAction? = null)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val settingsRepo = SettingsRepository(context)
    private val library = GameLibraryRepository(context)
    private val touchRepo = TouchLayoutRepository(context)
    private val achievementsRepo = AchievementRepository(context)
    private val turnipRepo = TurnipDriverRepository(context)
    private val launcher = Vita3KLauncher(context)
    private val uiRepo = UiPreferencesRepository(context)
    private val sessionManager = SessionManager(context, library, achievementsRepo, touchRepo)

    val settingsFlow = settingsRepo.settingsFlow
    val gpuFlow = settingsRepo.gpuInfoFlow
    val benchmarksFlow = settingsRepo.benchmarksFlow
    val touchLayoutFlow = touchRepo.layoutFlow
    val profilesFlow = touchRepo.profilesFlow
    val activeProfileIdFlow = touchRepo.activeProfileIdFlow
    val achievementsFlow = achievementsRepo.progressFlow
    val uiFlow = uiRepo.uiFlow
    val sessionsFlow = sessionManager.sessionsFlow
    val activeSessionIdFlow = sessionManager.activeSessionIdFlow

    private val _firmwareStatus = MutableStateFlow<FirmwareStatus>(FirmwareStatus.Missing)
    val firmwareStatusFlow: StateFlow<FirmwareStatus> = _firmwareStatus.asStateFlow()

    // B3 (auditoría UI): la batería y los mandos se leían con llamadas
    // planas y la UI no se enteraba del cambio. Ahora son StateFlow.
    private val _batteryOptDisabled = MutableStateFlow(false)
    val batteryOptDisabledFlow: StateFlow<Boolean> = _batteryOptDisabled.asStateFlow()

    private val _gamepads = MutableStateFlow<List<String>>(emptyList())
    val gamepadsFlow: StateFlow<List<String>> = _gamepads.asStateFlow()

    /** Refresca la lista de mandos físicos conectados (InputDevice). */
    fun refreshGamepads() {
        _gamepads.value = launcher.connectedGamepads()
    }

    private val _status = MutableStateFlow(GameStatus())
    val status: StateFlow<GameStatus> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Aviso importante (diálogo, no Snackbar): errores de importar y de JUGAR. */
    private val _alert = MutableStateFlow<AlertInfo?>(null)
    val alert: StateFlow<AlertInfo?> = _alert.asStateFlow()

    /**
     * Importación en pausa porque el juego/DLC/update ya está subido:
     * espera a que el usuario elija Reemplazar o Cancelar en el diálogo.
     * kind: "GAME", "DLC", "UPDATE", "GAME_FOLDER", "DLC_FOLDER".
     */
    private data class PendingDuplicate(val uri: Uri, val kind: String)
    private val _pendingDuplicate = MutableStateFlow<PendingDuplicate?>(null)

    /** true mientras hay una importación en curso (para mostrar progreso y bloquear dobles toques). */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Versión de Vita3K externo instalado, o null si no está instalado (para la Home). */
    private val _externalVita3k = MutableStateFlow<String?>(null)
    val externalVita3k: StateFlow<String?> = _externalVita3k.asStateFlow()

    /** .pkg guardado sin work.bin: falta la licencia para instalarlo en Vita3K. */
    private val _pendingPkgLicense = MutableStateFlow<PendingPkgLicense?>(null)
    val pendingPkgLicense: StateFlow<PendingPkgLicense?> = _pendingPkgLicense.asStateFlow()

    fun clearMessage() { _message.value = null }
    fun clearAlert() {
        _alert.value = null
        // Si se cierra el diálogo de duplicado, el reemplazo pendiente se abandona.
        _pendingDuplicate.value = null
    }

    /** Abre la página oficial de releases de Vita3K para instalarlo. */
    fun openVita3kReleases() {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Vita3K/Vita3K/releases")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            _message.value = "No se pudo abrir el navegador. Busca «Vita3K releases» en GitHub."
        }
    }

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
        viewModelScope.launch(Dispatchers.IO) { sessionManager.ensureDefaultSession() }
        viewModelScope.launch {
            // M37: si el DataStore está corrupto, first() lanza y tumbaba
            // esta corrutina sin aviso. Best-effort: sin GPU, se usa el
            // preset G99 por defecto.
            runCatching {
                val gpu = settingsRepo.gpuInfoFlow.first()
                if (gpu == null) detectGpu()
            }
        }
    }

    fun clearJustUnlocked() { _justUnlocked.value = null }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            // BUG (auditoría): scanStatus() hace I/O y puede lanzar; se llama
            // desde init/onResume sin protección. Si falla, se conserva el
            // estado anterior en vez de tumbar la corrutina.
            runCatching { library.scanStatus() }
                .onSuccess { _status.value = it }
                .onFailure { LauncherLog.append(context, "status", "No se pudo escanear el estado: ${it.message}") }
            runCatching { FirmwareResolver.resolve(library.vitaRoot) }
                .onSuccess { _firmwareStatus.value = it }
            _externalVita3k.value =
                runCatching { launcher.externalVita3kVersion() }.getOrNull()
            _batteryOptDisabled.value =
                runCatching { launcher.isBatteryOptimizationDisabled() }.getOrDefault(false)
            val title = _status.value.titleId?.id
            _trophyScan.value = if (title != null) {
                runCatching { TrophyStoreReader.scan(library.vitaRoot, title) }.getOrNull()
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
            // M34: el preset solo se aplica automáticamente en el primer
            // arranque real. Si el usuario ya personalizó sus ajustes,
            // re-detectar no debe sobrescribirlos.
            val appliedPreset = if (!settingsRepo.isPresetApplied()) {
                val profile = defaultProfileFor(gpu)
                settingsRepo.applyPreset(profile, current = current)
                true
            } else {
                false
            }
            withContext(Dispatchers.Main) {
                _message.value = if (gpu.isUserVerifiedDevice) {
                    "GPU detectada: Helio G99 · Mali-G57 MC2. Procesador verificado por ti: " +
                        if (appliedPreset) "preset G99 Recomendado aplicado." else "tus ajustes personalizados no se tocaron."
                } else {
                    "GPU detectada: ${gpu.shortLabel}. " +
                        if (appliedPreset) "Preset ${defaultProfileFor(gpu).label} aplicado." else "Tus ajustes personalizados no se tocaron."
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
            settingsRepo.applyPreset(profile, current = current)
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

    fun importGame(uri: Uri, force: Boolean = false) = importWith(uri, "GAME") { name -> library.importGame(uri, name, force) }
    fun importDlc(uri: Uri, force: Boolean = false) = importWith(uri, "DLC") { name -> library.importDlc(uri, name, force) }
    fun importUpdate(uri: Uri, force: Boolean = false) = importWith(uri, "UPDATE") { name -> library.importUpdate(uri, name, force) }
    fun importFirmware(uri: Uri) = importWith(uri, "FIRMWARE") { name -> library.importFirmware(uri, name) }

    /** Importar el juego desde una carpeta (debe llamarse PCSE00480/PCSB00598 y tener eboot.bin). */
    fun importGameFolder(uri: Uri, force: Boolean = false) {
        if (!beginImport()) return
        _pendingDuplicate.value = PendingDuplicate(uri, "GAME_FOLDER")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = library.importGameFolder(uri, force)
                withContext(Dispatchers.Main) { showImportResult(result) }
            } finally {
                endImport()
            }
        }
    }

    /** Importar DLCs desde una carpeta (debe tener addcont/ dentro). */
    fun importDlcFolder(uri: Uri, force: Boolean = false) {
        if (!beginImport()) return
        _pendingDuplicate.value = PendingDuplicate(uri, "DLC_FOLDER")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = library.importDlcFolder(uri, force)
                withContext(Dispatchers.Main) { showImportResult(result) }
            } finally {
                endImport()
            }
        }
    }

    /**
     * El usuario aceptó reemplazar lo ya subido: se repite la misma
     * importación con force = true (sobrescribe la copia guardada).
     * Cancelar es solo cerrar el diálogo: clearAlert() ya limpia el
     * pendiente.
     */
    fun confirmReplaceDuplicate() {
        val pending = _pendingDuplicate.value ?: return
        clearAlert()
        when (pending.kind) {
            "GAME" -> importGame(pending.uri, force = true)
            "DLC" -> importDlc(pending.uri, force = true)
            "UPDATE" -> importUpdate(pending.uri, force = true)
            "GAME_FOLDER" -> importGameFolder(pending.uri, force = true)
            "DLC_FOLDER" -> importDlcFolder(pending.uri, force = true)
        }
    }

    /**
     * Marca el inicio de una importación. Sin esta guarda, tocar dos
     * veces el botón lanzaba dos importaciones a la vez sobre el mismo
     * temporal: se pisaban entre sí y el resultado era silencio o error.
     */
    private fun beginImport(): Boolean {
        if (_importing.value) {
            _message.value = "Ya hay una importación en curso. Espera a que termine."
            return false
        }
        _importing.value = true
        return true
    }

    private fun endImport() {
        _importing.value = false
        refreshStatus()
    }

    /**
     * Los resultados de importar: el éxito corto va a Snackbar; todo lo
     * que el usuario tiene que LEER (rechazo, fallo, .pkg) va al diálogo
     * de aviso. Antes todo iba a una Snackbar de 4 segundos y el usuario
     * solo veía que "no pasa nada".
     */
    private fun showImportResult(result: ImportResult) {
        // Duplicado: el juego/DLC/update ya está subido. No es un error:
        // se pregunta si se reemplaza o se cancela, en vez de volver a
        // copiar en silencio. El pendiente se guardó al empezar la
        // importación (importWith / importGameFolder / importDlcFolder).
        if (result is ImportResult.Rejected && result.reason.startsWith(com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.DUPLICATE_MARKER)) {
            _alert.value = AlertInfo(
                result.reason.removePrefix(com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.DUPLICATE_MARKER),
                AlertAction.REPLACE_DUPLICATE,
            )
            return
        }
        _pendingDuplicate.value = null
        when (result) {
            is ImportResult.Rejected, is ImportResult.Failed ->
                _alert.value = AlertInfo(importResultMessage(result))
            is ImportResult.PkgStored -> {
                // Un .pkg nuevo deja obsoleta la entrega anterior: lo que
                // se copió a Descargas es el .pkg viejo.
                if (result.kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME) {
                    resetPkgDeliveryFlags()
                }
                _alert.value = AlertInfo(importResultMessage(result), AlertAction.DELIVER_PKG_TO_VITA3K)
            }
            is ImportResult.PkgNeedsLicense -> {
                if (result.kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME) {
                    resetPkgDeliveryFlags()
                }
                val dlcId = if (result.kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC) {
                    java.io.File(result.storedPath).nameWithoutExtension
                } else null
                _pendingPkgLicense.value = PendingPkgLicense(
                    titleId = result.titleId.id,
                    kind = result.kind,
                    contentId = result.contentId,
                    dlcId = dlcId,
                    storedPath = result.storedPath,
                )
                _alert.value = AlertInfo(importResultMessage(result))
            }
            else -> _message.value = importResultMessage(result)
        }
    }

    /** Un .pkg nuevo (o borrado) invalida la entrega/confirmación anterior. */
    private fun resetPkgDeliveryFlags() {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            if (current.pkgDeliveredToVita3k || current.pkgInstalledInVita3k) {
                settingsRepo.save(current.copy(pkgDeliveredToVita3k = false, pkgInstalledInVita3k = false))
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "${bytes / 1_000} KB"
        else -> "$bytes bytes"
    }

    private fun importResultMessage(result: ImportResult): String = when (result) {
        is ImportResult.Success -> result.detail
        is ImportResult.DlcSuccess -> "DLC ${result.dlcId} instalado para ${result.titleId.id}."
        is ImportResult.UpdateSuccess -> "Update instalado para ${result.titleId.id}."
        is ImportResult.FirmwareSuccess -> "Firmware ${result.fileName} guardado."
        is ImportResult.PkgStored -> {
            val what = when (result.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME -> "El juego"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> "El DLC"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE -> "El update"
            }
            "$what se guardó como .pkg (${result.titleId.id} · ${formatBytes(result.sizeBytes)}).\n\n" +
                "El launcher no puede instalar un .pkg por sí mismo: lo instala Vita3K. " +
                "Pulsa «Instalar en Vita3K»: se copia el .pkg y su licencia a Descargas/ChildOfLight/ " +
                "y se abre Vita3K para instalarlo ahí una sola vez."
        }
        is ImportResult.PkgNeedsLicense -> {
            val what = when (result.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME -> "El juego"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> "El DLC"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE -> "El update"
            }
            "$what se guardó como .pkg (${result.titleId.id} · ${formatBytes(result.sizeBytes)}), " +
                "pero falta su licencia (work.bin de 512 B): sin ella Vita3K no puede instalarlo.\n\n" +
                "Elige el work.bin en Ajustes (tarjeta «Falta la licencia»): es el archivo work.bin " +
                "que acompaña al .pkg (a veces se descarga como «work (5).bin»)."
        }
        is ImportResult.Rejected -> "No se instaló nada:\n\n${result.reason.removePrefix(com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.DUPLICATE_MARKER)}"
        is ImportResult.Failed -> "No se pudo importar:\n\n${result.reason}\n\nSi el archivo es grande, espera sin cerrar la app: la copia puede tardar varios minutos."
    }

    private fun importWith(uri: Uri, kind: String, block: (String) -> ImportResult) {
        if (!beginImport()) return
        _pendingDuplicate.value = PendingDuplicate(uri, kind)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = fileNameOf(uri)
                val result = block(name)
                withContext(Dispatchers.Main) {
                    showImportResult(result)
                }
            } finally {
                endImport()
            }
        }
    }

    // --- .pkg + Vita3K externo ---

    /** Guarda el work.bin elegido por el usuario para el .pkg pendiente de licencia. */
    fun importWorkBin(uri: Uri) {
        val pending = _pendingPkgLicense.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val licenseDest = when (pending.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                    library.pkgWorkBinFile(pending.titleId)
                com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC ->
                    library.pkgDlcWorkBinFile(pending.dlcId ?: return@launch)
                com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE ->
                    library.pkgPatchWorkBinFile(pending.titleId)
            }
            val result = library.importWorkBin(uri, licenseDest, pending.contentId)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    _pendingPkgLicense.value = null
                    _alert.value = AlertInfo(
                        "Licencia guardada: el .pkg ya tiene su work.bin.\n\n" +
                            "Pulsa «Instalar en Vita3K» para copiar el .pkg y su licencia a " +
                            "Descargas/ChildOfLight/ e instalarlo en Vita3K.",
                        AlertAction.DELIVER_PKG_TO_VITA3K,
                    )
                }.onFailure { e ->
                    _alert.value = AlertInfo("Ese archivo no es un work.bin válido: ${e.message}")
                }
            }
            refreshStatus()
        }
    }

    /** Copia el .pkg + work.bin a Descargas/ChildOfLight/, abre Vita3K y muestra la guía de instalación. */
    fun deliverPkgToVita3k() {
        viewModelScope.launch(Dispatchers.IO) {
            // Cualquier .pkg guardado vale (juego, DLC o update), no solo el del juego.
            if (library.storedPkgFilesForDelivery().isEmpty()) {
                withContext(Dispatchers.Main) {
                    _alert.value = AlertInfo("No hay ningún .pkg guardado para entregar. Importa primero el .pkg del juego.")
                }
                return@launch
            }
            // Cada .pkg necesita su work.bin: si falta alguno, se pide
            // antes de copiar nada (copiar un .pkg sin licencia no sirve).
            val missing = library.storedPkgsMissingLicense()
            if (missing.isNotEmpty()) {
                val first = missing.first()
                val status = runCatching { library.scanStatus() }.getOrNull()
                withContext(Dispatchers.Main) {
                    _pendingPkgLicense.value = PendingPkgLicense(
                        titleId = first.titleId.id,
                        kind = first.kind,
                        contentId = if (first.kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME) {
                            status?.pkgContentId ?: ""
                        } else "",
                        dlcId = first.dlcId,
                        storedPath = when (first.kind) {
                            com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                                library.pkgGameFile(first.titleId.id).absolutePath
                            com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC ->
                                library.pkgDlcFile(first.dlcId ?: "").absolutePath
                            com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE ->
                                library.pkgPatchFile(first.titleId.id).absolutePath
                        },
                    )
                    _alert.value = AlertInfo(
                        "Falta la licencia (work.bin) del ${first.kind.label} ${first.titleId.id}: sin ella Vita3K no puede instalarlo.\n\n" +
                            "Elige el work.bin en Ajustes (tarjeta «Falta la licencia»).",
                    )
                }
                return@launch
            }
            val result = launcher.deliverPkgFiles(library)
            result.onSuccess { copied ->
                val current = settingsRepo.settingsFlow.first()
                settingsRepo.save(current.copy(pkgDeliveredToVita3k = true))
                val opened = launcher.openVita3k()
                withContext(Dispatchers.Main) {
                    if (opened) {
                        _alert.value = AlertInfo(pkgInstallGuideText(copied), AlertAction.CONFIRM_PKG_INSTALLED)
                    } else {
                        _alert.value = AlertInfo(
                            "Los archivos se copiaron a Descargas/ChildOfLight/, pero Vita3K no está instalado.\n\n" +
                                "Instálalo y luego vuelve a pulsar «Instalar en Vita3K».",
                            AlertAction.INSTALL_VITA3K,
                        )
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    _alert.value = AlertInfo("No se pudo entregar el .pkg a Vita3K: ${e.message}")
                }
            }
            refreshStatus()
        }
    }

    /** Guía paso a paso para instalar el .pkg dentro de Vita3K (una sola vez). */
    private fun pkgInstallGuideText(copied: List<String>): String {
        val files = copied.joinToString("\n") { "• $it" }
        return "Archivos copiados a Descargas/ChildOfLight/:\n$files\n\n" +
            "Vita3K está abierto. Instala el juego ahí una sola vez:\n\n" +
            "1. En Vita3K: File → Install Package (.pkg).\n" +
            "2. Elige el .pkg de Descargas/ChildOfLight/.\n" +
            "3. Pulsa «Select work.bin» y elige el work.bin de la misma carpeta.\n" +
            "4. Espera a que termine la instalación y vuelve aquí.\n\n" +
            "Cuando lo hayas instalado, pulsa «Ya lo instalé en Vita3K»."
    }

    /** El usuario confirma que instaló el .pkg en Vita3K (honesto: el launcher no puede comprobarlo). */
    fun confirmPkgInstalledInVita3k() {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(current.copy(pkgInstalledInVita3k = true))
            _message.value = "Anotado: el juego quedó instalado en Vita3K. Pulsa JUGAR y ábrelo en la biblioteca de Vita3K."
            LauncherLog.append(context, "pkg", "Usuario confirmó instalación del .pkg en Vita3K.")
        }
    }

    // --- Turnip ---

    fun installTurnip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            val result = turnipRepo.installDriverZip(uri, name)
            val current = settingsRepo.settingsFlow.first()
            result.onSuccess { savedName ->
                // H7: Turnip solo funciona en Adreno/Snapdragon. En Mali
                // (G99) instalar el zip no activa nada: el driver del
                // sistema sigue siendo el que manda.
                val gpu = settingsRepo.gpuInfoFlow.first()
                if (gpu?.vendor == GpuVendor.ADRENO) {
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = true,
                            turnipVersion = savedName,
                            customDriverName = savedName.substringBeforeLast("."),
                            gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP,
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        _message.value = "Turnip instalado: $savedName. Vita3K limpiará la caché de shaders al arrancar con el nuevo driver."
                    }
                } else {
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = true,
                            turnipVersion = savedName,
                            customDriverName = savedName.substringBeforeLast("."),
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        _message.value = "Turnip guardado ($savedName), pero NO activado: tu GPU es ${gpu?.shortLabel ?: "Mali"} y Turnip solo funciona en Adreno/Snapdragon. Se sigue usando el driver del sistema."
                    }
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
            val outcome = launcher.launch(library, settings, sessionManager)
            if (outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternal ||
                outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning ||
                outcome is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded
            ) {
                sessionManager.notePlayStarted()
            }
            withContext(Dispatchers.Main) {
                // Con el juego solo en .pkg, JUGAR abre Vita3K y el juego
                // se abre en SU biblioteca (si ya se instaló ahí); si no,
                // el diálogo ofrece entregarlo ("Instalar en Vita3K").
                val pkgOnly = _status.value.pkgInstalled && !_status.value.gameExtracted
                when (outcome) {
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternal ->
                        // H8: el config.yml escrito vive en el almacenamiento
                        // privado del launcher; Vita3K externo no puede
                        // leerlo (sandbox Android). Solo lo usa el núcleo
                        // embebido: se dice para no prometer lo que no es.
                        // Y el juego TAMPOCO lo ve Vita3K externo: hay que
                        // importarlo también dentro de Vita3K. Antes esto
                        // era una Snackbar fugaz y el usuario solo veía que
                        // JUGAR "no hacía nada".
                        _alert.value = if (pkgOnly) {
                            if (settings.pkgInstalledInVita3k) {
                                AlertInfo(
                                    "Vita3K abierto.\n\n" +
                                        "Abre Child of Light en la biblioteca de Vita3K (ya lo instalaste ahí).\n\n" +
                                        "Los ajustes del launcher (config.yml) no los lee Vita3K externo: configúralo dentro de Vita3K.",
                                )
                            } else {
                                AlertInfo(
                                    "Vita3K abierto, pero el juego aún no está instalado DENTRO de Vita3K.\n\n" +
                                        "El .pkg guardado en el launcher no lo ve Vita3K: hay que instalarlo ahí una sola vez.",
                                    AlertAction.DELIVER_PKG_TO_VITA3K,
                                )
                            }
                        } else {
                            AlertInfo(
                                "Vita3K abierto.\n\n" +
                                    "IMPORTANTE: el juego que instalaste en el launcher vive en su almacenamiento privado y Vita3K no lo ve. " +
                                    "Para jugar, importa tu copia también dentro de Vita3K (su biblioteca).\n\n" +
                                    "Los ajustes del launcher (config.yml) tampoco los lee Vita3K externo: configúralo dentro de Vita3K.",
                            )
                        }
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning ->
                        _alert.value = if (pkgOnly) {
                            AlertInfo(
                                Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL + "\n\n" +
                                    if (settings.pkgInstalledInVita3k) {
                                        "Abre Child of Light en la biblioteca de Vita3K."
                                    } else {
                                        "El .pkg aún no está instalado dentro de Vita3K."
                                    },
                                if (settings.pkgInstalledInVita3k) null else AlertAction.DELIVER_PKG_TO_VITA3K,
                            )
                        } else {
                            AlertInfo(
                                Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL + "\n\n" +
                                    "El juego que instalaste en el launcher vive en su almacenamiento privado y Vita3K no lo ve: " +
                                    "importa tu copia también dentro de Vita3K (su biblioteca).",
                            )
                        }
                    is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded ->
                        _message.value = "Núcleo embebido: juego lanzado (${outcome.configPath})."
                    is Vita3KLauncher.LaunchOutcome.EmbeddedCorePresent ->
                        _message.value = "Núcleo embebido detectado, pero falta el punto de entrada JNI (Etapa 2)."
                    is Vita3KLauncher.LaunchOutcome.Blocked -> {
                        // El diálogo ofrece la acción que resuelve el bloqueo:
                        // instalar Vita3K, o ir a importar el juego.
                        val action = when {
                            outcome.reason.contains("Vita3K no está instalado") -> AlertAction.INSTALL_VITA3K
                            outcome.reason.contains("El juego no está instalado") -> AlertAction.GO_IMPORT
                            else -> null
                        }
                        _alert.value = AlertInfo(outcome.reason, action)
                    }
                }
            }
            refreshStatus()
        }
    }

    /**
     * Al volver del juego (MainActivity.onResume): cierra la medición
     * de tiempo de la sesión activa y guarda el snapshot de lo jugado.
     * No hace nada si no hay una partida en curso registrada.
     */
    fun noteReturnedFromGame() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.notePlayEnded()
            refreshStatus()
        }
    }

    // --- Sesiones / partidas ---

    fun createSession(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sessionManager.createSession(name)
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Nueva sesión \"${it.name}\" creada y activa. La anterior quedó guardada: la viva empieza vacía." },
                    onFailure = { "No se pudo crear la sesión: ${it.message}" },
                )
            }
            refreshStatus()
        }
    }

    fun switchSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sessionManager.switchTo(id)
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Sesión activa: \"${it.name}\". Su savedata, logros y controles están cargados." },
                    onFailure = { "No se pudo cambiar de sesión: ${it.message}" },
                )
            }
            refreshStatus()
        }
    }

    fun renameSession(id: String, name: String, chapter: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sessionManager.renameSession(id, name, chapter)
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Sesión renombrada." },
                    onFailure = { "No se pudo renombrar: ${it.message}" },
                )
            }
        }
    }

    fun duplicateSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sessionManager.duplicateSession(id)
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Sesión duplicada: \"${it.name}\"." },
                    onFailure = { "No se pudo duplicar: ${it.message}" },
                )
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = sessionManager.deleteSession(id)
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Sesión borrada." },
                    onFailure = { "No se pudo borrar: ${it.message}" },
                )
            }
            refreshStatus()
        }
    }

    fun exportSession(uri: Uri, session: com.danielsnagustin.childoflight.launcher.model.GameSession) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = context.contentResolver.openOutputStream(uri)?.use { out ->
                sessionManager.exportSession(session.id, out)
            } ?: Result.failure(IllegalStateException("No se pudo abrir el destino."))
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Sesión \"${session.name}\" exportada." },
                    onFailure = { "No se pudo exportar la sesión: ${it.message}" },
                )
            }
        }
    }

    fun importSession(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = context.contentResolver.openInputStream(uri)?.use { input ->
                sessionManager.importSession(input)
            } ?: Result.failure(IllegalStateException("No se pudo abrir el archivo."))
            withContext(Dispatchers.Main) {
                _message.value = result.fold(
                    onSuccess = { "Sesión importada: \"${it.name}\"." },
                    onFailure = { "No se pudo importar la sesión: ${it.message}" },
                )
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
            val outcome = launcher.launch(library, settings, sessionManager)
            if (outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternal ||
                outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning ||
                outcome is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded
            ) {
                sessionManager.notePlayStarted()
            }
            withContext(Dispatchers.Main) {
                _message.value = when (outcome) {
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternal ->
                        "Prueba de 30 s en marcha. Juega 30 segundos, mira el FPS del overlay y al volver regístralo aquí."
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning ->
                        "Prueba de 30 s en marcha. ${Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL}"
                    is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded ->
                        "Prueba de 30 s en marcha (núcleo embebido). Juega 30 segundos y al volver registra el FPS."
                    is Vita3KLauncher.LaunchOutcome.Blocked -> {
                        _pendingBenchmark.value = null
                        outcome.reason
                    }
                    is Vita3KLauncher.LaunchOutcome.EmbeddedCorePresent -> {
                        // M32: la prueba nunca empezó (falta el JNI); sin
                        // limpiar el pendiente, recordBenchmarkFps registraría
                        // un FPS de una partida que no ocurrió.
                        _pendingBenchmark.value = null
                        "Núcleo embebido detectado, falta el punto de entrada JNI (Etapa 2)."
                    }
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
        // M25: un FPS inválido (negativo, NaN, absurdo) no se guarda ni se
        // usa para decidir el auto-revert; el pendiente se conserva para
        // poder reintentarlo.
        if (!fps.isFinite() || fps < 1f || fps > 240f) {
            _message.value = "FPS no válido: $fps. Anota el FPS del overlay (entre 1 y 240)."
            return
        }
        viewModelScope.launch {
            val settings = settingsRepo.settingsFlow.first()
            // M25: el auto-revert se decide con el perfil ACTUAL, no con el
            // de cuando empezó la prueba (el usuario pudo cambiarlo).
            val revert = shouldAutoRevertTo1x(fps, settings.activeProfile, settings.autoRevertTo1xIfBelow28Fps)
            val completed = pending.copy(fpsAvg = fps, source = BenchmarkSource.MANUAL_OVERLAY, autoReverted = revert)
            settingsRepo.recordBenchmark(completed)
            _pendingBenchmark.value = null
            if (revert) {
                settingsRepo.applyPreset(GpuProfileId.G99_RECOMMENDED, current = settings)
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
                    // BUG (auditoría): addedOrKept es el nº de entradas VÁLIDAS
                    // del archivo, no el total de logros desbloqueados.
                    _message.value = "Logros importados: ${result.newlyUnlocked} nuevos, " +
                        "${result.addedOrKept} entradas válidas, ${result.ignoredInvalid} inválidas ignoradas."
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

    /** Restaura los perfiles predefinidos que falten (G99, Calidad, Adreno). */
    fun restoreBuiltInProfiles() {
        viewModelScope.launch {
            touchRepo.restoreBuiltIns()
            _message.value = "Perfiles predefinidos restaurados."
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
            // BUG (auditoría): si el estado aún no tenía Title ID, el borrado
            // no hacía nada en silencio, sin avisar al usuario.
            val title = _status.value.titleId?.id
            if (title == null) {
                withContext(Dispatchers.Main) { _message.value = "No hay juego instalado: no hay DLC que borrar." }
                return@launch
            }
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
            // Solo con el juego extraído: con un .pkg los saves viven en Vita3K.
            val title = freshExtractedTitleId()
            if (title == null) {
                withContext(Dispatchers.Main) {
                    _message.value = if (freshTitleId() != null) {
                        "El juego está como .pkg: los saves viven dentro de Vita3K, no en el launcher."
                    } else {
                        "No hay juego instalado: no hay saves que borrar."
                    }
                }
                return@launch
            }
            val ok = library.deleteSavesOnly(title)
            // H6: la copia guardada de la sesión activa solo se borra si
            // realmente había saves vivos que borrar. Antes se borraba
            // siempre, incluso cuando el mensaje decía "No había saves".
            if (ok) sessionManager.clearActiveSessionSavedata()
            withContext(Dispatchers.Main) {
                _message.value = if (ok) "Saves borrados (también de la sesión activa). El juego y los DLCs siguen instalados." else "No había saves que borrar."
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

    /** Title ID fresco (no el estado de la UI, que puede ir desactualizado). */
    private fun freshTitleId(): String? =
        runCatching { library.scanStatus().titleId?.id }.getOrNull()

    /** Title ID del juego EXTRAÍDO, fresco. null si el juego es solo .pkg. */
    private fun freshExtractedTitleId(): String? =
        runCatching { library.scanStatus().extractedTitleId?.id }.getOrNull()

    fun exportSaves(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // Los saves del launcher solo existen con el juego extraído:
            // con un .pkg los saves viven dentro de Vita3K.
            val title = freshExtractedTitleId()
            if (title == null) {
                withContext(Dispatchers.Main) {
                    _message.value = if (freshTitleId() != null) {
                        "El juego está como .pkg: los saves viven dentro de Vita3K, no en el launcher."
                    } else {
                        "No hay juego instalado: no hay saves que exportar."
                    }
                }
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
            // M36: el Title ID se lee fresco de disco, no del estado de la
            // UI (puede ir desactualizado si cambió el juego). Y solo del
            // juego EXTRAÍDO: con un .pkg los saves viven en Vita3K.
            val title = freshExtractedTitleId()
            if (title == null) {
                withContext(Dispatchers.Main) {
                    _message.value = if (freshTitleId() != null) {
                        "El juego está como .pkg: los saves viven dentro de Vita3K, no en el launcher."
                    } else {
                        "Instala primero el juego para importar saves."
                    }
                }
                return@launch
            }
            // M14: respaldo de la sesión activa ANTES de importar: si el
            // zip trae saves corruptos, la partida anterior sigue guardada
            // en su sesión y se puede recuperar cambiando de sesión.
            if (!sessionManager.backupActiveSession()) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo respaldar la sesión activa: no se importan los saves." }
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
            // Título fresco de disco (extraído o .pkg), no el estado de la
            // UI, que puede ir desactualizado.
            val title = freshTitleId()
            if (title == null) {
                withContext(Dispatchers.Main) { _message.value = "No hay juego instalado: no hay datos que borrar." }
                return@launch
            }
            val deleted = library.deleteGameData(title)
            sessionManager.deleteAllSessions()
            _pendingPkgLicense.value = null
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(current.copy(pkgDeliveredToVita3k = false, pkgInstalledInVita3k = false))
            withContext(Dispatchers.Main) {
                _message.value = "Datos borrados: ${deleted.joinToString(", ")} + todas las sesiones. El launcher queda vacío, listo para importar de nuevo."
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
            // Se guarda el estado REAL (PowerManager), no un "sí" optimista:
            // este método se llama al volver del diálogo del sistema, lo
            // conceda el usuario o no. Antes se persistía true siempre.
            val disabled = launcher.isBatteryOptimizationDisabled()
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(current.copy(batteryOptimizationDisabled = disabled))
            _batteryOptDisabled.value = disabled
        }
    }

    fun buildBugReportText(settings: LauncherSettings, gpu: GpuInfo?): String {
        val status = _status.value
        return buildString {
            appendLine("=== Reporte de bug — Child of Light Launcher (Etapa 1) ===")
            appendLine("Fecha: ${java.util.Date()}")
            appendLine("App: com.danielsnagustin.childoflight.launcher v0.1.0")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Procesador: ${gpu?.socModel?.ifBlank { null } ?: Build.SOC_MODEL ?: Build.HARDWARE}")
            appendLine("SoC: ${gpu?.socModel ?: "no detectado"}")
            appendLine("GPU: ${gpu?.renderer ?: "no detectada"} (${gpu?.vendor})")
            appendLine("GLES: ${gpu?.glesVersion ?: ""}")
            appendLine("Vulkan: ${gpu?.vulkanVersionLabel ?: "desconocido"} (soportado: ${gpu?.vulkanSupported})")
            appendLine("Procesador verificado por el usuario: ${gpu?.isUserVerifiedDevice}")
            appendLine()
            appendLine("--- Juego ---")
            appendLine("Instalado: ${status.gameInstalled} (${status.titleId?.id ?: "-"})")
            appendLine("Extraído: ${status.gameExtracted} | .pkg: ${status.pkgTitleId?.id ?: "-"} (${status.pkgContentId ?: "-"}) | Licencia work.bin: ${status.pkgLicensePresent}")
            appendLine("Entregado a Vita3K: ${settings.pkgDeliveredToVita3k} | Instalado en Vita3K (confirmado por el usuario): ${settings.pkgInstalledInVita3k}")
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
