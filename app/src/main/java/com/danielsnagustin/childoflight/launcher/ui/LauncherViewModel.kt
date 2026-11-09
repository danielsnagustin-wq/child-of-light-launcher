package com.danielsnagustin.childoflight.launcher.ui

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.danielsnagustin.childoflight.launcher.R
import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import com.danielsnagustin.childoflight.launcher.core.EmbeddedPkgInstaller
import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.data.AchievementRepository
import com.danielsnagustin.childoflight.launcher.data.BundledTurnipDriver
import com.danielsnagustin.childoflight.launcher.data.ControlProfile
import com.danielsnagustin.childoflight.launcher.data.DeviceCompat
import com.danielsnagustin.childoflight.launcher.data.DeviceMemory
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.data.DownloadsCopy
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.GpuDetector
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.PkgHeaderParser
import com.danielsnagustin.childoflight.launcher.data.formatLaunchAttempt
import com.danielsnagustin.childoflight.launcher.data.SessionManager
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreReader
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreScan
import com.danielsnagustin.childoflight.launcher.data.TrophyTrpReader
import com.danielsnagustin.childoflight.launcher.data.TrpReadResult
import com.danielsnagustin.childoflight.launcher.data.TurnipDriverRepository
import com.danielsnagustin.childoflight.launcher.data.UiPreferencesRepository
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.data.decodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.BenchmarkRun
import com.danielsnagustin.childoflight.launcher.model.BenchmarkSource
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.ImportResult
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import com.danielsnagustin.childoflight.launcher.model.TrophyUnlockAnnouncer
import com.danielsnagustin.childoflight.launcher.model.TitleId
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.ui.settings.audioFixTarget
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.categoryHasResettableSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.extractRecentErrorLines
import com.danielsnagustin.childoflight.launcher.ui.settings.guardTurnipDriver
import com.danielsnagustin.childoflight.launcher.ui.settings.resetCategorySettings
import com.danielsnagustin.childoflight.launcher.ui.settings.resetCategoryUiPrefs
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.shouldAutoRevertTo1x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Acción que puede ofrecer el diálogo de aviso importante. */
enum class AlertAction { INSTALL_VITA3K, GO_IMPORT, DELIVER_PKG_TO_VITA3K, CONFIRM_PKG_INSTALLED, REPLACE_DUPLICATE, CONFIRM_DOWNLOADS_COPY, RETRY_LAUNCH, RESOLVE_LEGACY_CONFLICT }

/** Caducidad de una prueba de 30 s pendiente (P2-A2): pasado este tiempo sin registrarse, se descarta. */
internal const val BENCHMARK_PENDING_MAX_AGE_MILLIS = 15L * 60 * 1000

/**
 * Pura (JVM): true si una prueba empezada en [startedAtMillis] ya caducó
 * a las [nowMillis]. Una prueba vieja no debe registrar un FPS medido
 * días después contra aquel perfil/run (P2-A2).
 */
internal fun isBenchmarkPendingStale(startedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
    nowMillis - startedAtMillis > BENCHMARK_PENDING_MAX_AGE_MILLIS

/**
 * R2-P1-08 + R2-P1-09: restauración POR CAMPO de un snapshot de ajustes.
 * El snapshot se guarda con encodeSettingsBackup, que NO incluye 5 campos
 * (turnipInstalled, turnipVersion, customDriverName, pkgDeliveredToVita3k,
 * pkgInstalledInVita3k: describen archivos/el sistema, no preferencias) y
 * se decodifica contra LauncherSettings() por defecto. Restaurar el
 * snapshot entero borraba esos 5 campos Y cualquier cambio hecho mientras
 * el Modo seguro (o "Corregir audio") estaba activo. Por eso la
 * restauración parte de los ajustes ACTUALES y solo revierte los campos
 * que el mecanismo sobrescribe; los 5 campos y el resto de cambios
 * sobreviven. Pura (JVM): SafeModeSnapshotRestoreTest.
 */
internal fun restoreSafeModeSnapshot(snapshot: LauncherSettings, current: LauncherSettings): LauncherSettings =
    current.copy(
        safeMode = false,
        // Solo los campos que applySafeMode sobrescribe (model/LauncherSettings.kt).
        resolutionMultiplier = snapshot.resolutionMultiplier,
        screenFilter = snapshot.screenFilter,
        anisotropicFiltering = snapshot.anisotropicFiltering,
        vSync = snapshot.vSync,
        asyncPipelineCompilation = snapshot.asyncPipelineCompilation,
        highAccuracy = snapshot.highAccuracy,
        surfaceSyncEnabled = snapshot.surfaceSyncEnabled,
        audioBackend = snapshot.audioBackend,
        ngsEnable = snapshot.ngsEnable,
        shaderCache = snapshot.shaderCache,
    )

/**
 * R2-P1-09 (misma lógica para "Corregir audio" → Deshacer): solo se
 * revierten los 3 campos que audioFixTarget sobrescribe (audioBackend,
 * ngsEnable, shaderCache); el resto — incluidos los 5 campos que el
 * snapshot no guarda — se conserva como esté ahora.
 */
internal fun restoreAudioFixSnapshot(snapshot: LauncherSettings, current: LauncherSettings): LauncherSettings =
    current.copy(
        audioBackend = snapshot.audioBackend,
        ngsEnable = snapshot.ngsEnable,
        shaderCache = snapshot.shaderCache,
    )

/**
 * Un .pkg guardado al que le falta su work.bin (licencia). La tarjeta
 * de Ajustes lo muestra con el botón "Elegir work.bin".
 */
data class PendingPkgLicense(
    val titleId: String,
    val kind: com.danielsnagustin.childoflight.launcher.model.PkgKind,
    /**
     * Content ID esperado del .pkg (del header). null = sin expectativa:
     * el work.bin se valida sin comparar Content ID. Nunca "" (P1-7:
     * con "" un work.bin binario válido se rechazaba siempre como
     * "de otro juego" porque su Content ID != "").
     */
    val contentId: String?,
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

/**
 * Progreso de instalación en el núcleo (Fase 2, Ola 1): % real del
 * núcleo (0-100) + mensaje del núcleo, o percent null = trabajo en
 * curso sin % (la copia del archivo no tiene hook de progreso;
 * nunca se inventa un %). Null = no hay nada en curso. Lo alimenta
 * la instalación de firmware en el núcleo, la instalación del .pkg
 * al jugar y la copia de la importación (indeterminada, con nombre).
 */
data class InstallProgress(val percent: Int?, val message: String)

/**
 * Texto de confirmación antes de copiar a Descargas/ChildOfLight/.
 * Puro (sin Android): testeable en JVM (DownloadsAndLayoutTest).
 * Sin el nombre de la app externa: la UI solo habla de "la app externa".
 */
internal fun downloadsCopyConfirmText(fileNames: List<String>, totalBytes: Long): String {
    val files = fileNames.joinToString("\n") { "• $it" }
    return "El juego, los DLCs, los updates y la licencia viven en el almacenamiento interno del launcher (privado, sin carpeta visible).\n\n" +
        "La app externa no puede leerlos desde ahí: para instalarlos hay que copiarlos a la carpeta compartida Descargas/ChildOfLight/ (${DownloadsCleanup.formatBytes(totalBytes)}).\n\n" +
        "Archivos que se copiarán:\n$files\n\n" +
        "Esto solo hace falta mientras el núcleo embebido no esté listo: cuando lo esté, JUGAR instalará el juego sin copiar nada a Descargas. " +
        "Después de instalar, puedes borrar estas copias desde Ajustes → Almacenamiento."
}

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

    /** true mientras se instala el PUP guardado en el núcleo embebido (puede tardar minutos). */
    private val _firmwareInstalling = MutableStateFlow(false)
    val firmwareInstallingFlow: StateFlow<Boolean> = _firmwareInstalling.asStateFlow()

    /**
     * Serie de instalaciones de firmware (2026-11-07): instalar el PUP
     * dos veces a la vez (JUGAR + botón de Ajustes, o dos toques al
     * botón) lanzaba dos installFirmware nativos concurrentes sobre el
     * mismo vs0. _firmwareInstalling solo era un indicador, nunca se
     * consultaba como guarda. El Mutex es la guarda real.
     */
    private val firmwareInstallMutex = Mutex()

    /** true mientras hay un lanzamiento en curso (JUGAR o prueba de 30 s). La Home deshabilita JUGAR mientras tanto. */
    private val _launching = MutableStateFlow(false)
    val launchingFlow: StateFlow<Boolean> = _launching.asStateFlow()

    /**
     * Guarda anti-doble-JUGAR (2026-11-07): dos toques rápidos a JUGAR
     * lanzaban dos launch() a la vez (dos instalaciones de firmware,
     * dos instalaciones de .pkg con el núcleo, dos intents). El botón
     * no se deshabilitaba y play() no tenía guarda (la importación sí
     * la tiene con _importing). AtomicBoolean porque dos corrutinas IO
     * pueden llegar a la vez; el StateFlow solo refleja el estado en UI.
     */
    private val launchingGuard = AtomicBoolean(false)

    private fun tryBeginLaunch(): Boolean {
        if (!launchingGuard.compareAndSet(false, true)) {
            _message.value = "Ya hay un lanzamiento en curso."
            return false
        }
        _launching.value = true
        return true
    }

    private fun endLaunch() {
        _launching.value = false
        launchingGuard.set(false)
    }

    /** ¿Está presente el núcleo embebido (Etapa 2)? null = aún no comprobado. */
    private val _embeddedCore = MutableStateFlow<Boolean?>(null)
    val embeddedCoreFlow: StateFlow<Boolean?> = _embeddedCore.asStateFlow()

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

    // --- Descargas/ChildOfLight/ (copias para la app externa) ---
    // El juego/DLCs/updates/licencia viven en el almacenamiento interno
    // del launcher; la única copia a Descargas es la entrega explícita
    // a la app externa, que el usuario confirma antes. Aquí se lista
    // para ofrecer la limpieza en Ajustes → Almacenamiento.
    private val _downloadsCopies = MutableStateFlow<List<DownloadsCopy>>(emptyList())
    val downloadsCopies: StateFlow<List<DownloadsCopy>> = _downloadsCopies.asStateFlow()

    /** Llamar desde IO (refreshStatus, deleteDownloadsCopies). */
    private fun refreshDownloadsCopies() {
        _downloadsCopies.value = runCatching { library.listDownloadsCopies() }.getOrDefault(emptyList())
    }

    /**
     * Borra las copias de Descargas/ChildOfLight/ (solo se llama desde
     * la confirmación del usuario en Ajustes → Almacenamiento). El
     * juego, los DLCs y la licencia del almacenamiento interno NO se
     * tocan. Si aún no instaló el .pkg en la app externa, tendrá que
     * volver a copiarlo (el diálogo de confirmación lo avisa).
     */
    fun deleteDownloadsCopies() {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = runCatching { library.deleteDownloadsCopies() }.getOrDefault(0)
            refreshDownloadsCopies()
            withContext(Dispatchers.Main) {
                _message.value = if (deleted > 0) {
                    "Se borraron $deleted archivo(s) de Descargas/ChildOfLight/. El juego sigue guardado en el almacenamiento interno del launcher."
                } else {
                    "No había nada que borrar en Descargas/ChildOfLight/."
                }
            }
        }
    }

    /**
     * Borra el .pkg de juego guardado cuando el juego ya está
     * extraído: el .pkg es una copia redundante (1,14 GB) y el juego
     * que corre es el extraído, así que la app ocupaba el doble
     * (2,54 GB de datos con un juego de 1,14 GB). NUNCA se borra si
     * el juego no está extraído (sería la única copia). El work.bin
     * (licencia, 512 B) se conserva y el juego extraído no se toca.
     * Con confirmación en la UI (CleanupScreen).
     */
    fun deleteInstalledGamePkgs() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = _status.value
            val extractedId = status.extractedTitleId?.id
            val freed = if (extractedId != null && status.pkgTitleId?.id == extractedId) {
                val size = status.pkgSizeBytes
                if (runCatching { library.deleteStoredGamePkg(extractedId) }.getOrDefault(false)) size else 0L
            } else 0L
            refreshStatus()
            withContext(Dispatchers.Main) {
                _message.value = if (freed > 0) {
                    "Se borró el .pkg guardado (${DownloadsCleanup.formatBytes(freed)} liberados). El juego sigue instalado y la licencia no se tocó."
                } else {
                    "No había ningún .pkg guardado que borrar."
                }
            }
        }
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

    /** Progreso de instalación en el núcleo (ver InstallProgress). */
    private val _installProgress = MutableStateFlow<InstallProgress?>(null)
    val installProgress: StateFlow<InstallProgress?> = _installProgress.asStateFlow()

    /** Versión de la app externa instalada, o null si no está instalada (para Ajustes → General → Motor). */
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
            _message.value = "No se pudo abrir el navegador. Busca las releases de la app externa en GitHub."
        }
    }

    /** Trofeo recién desbloqueado: la UI muestra popup + sonido.
     * BUG 2026-11-08: el popup nombraba el trofeo equivocado (15 en
     * vez de 17) porque dos desbloqueos en vuelo escribían este
     * StateFlow sin orden ni identidad de evento. El anuncio pasa por
     * [TrophyUnlockAnnouncer] (secuencia por pulsación + construcción
     * directa desde TROPHIES_27).
     * El marcado manual se eliminó (2026-11-08): los trofeos solo se
     * ganan jugando. El anunciador y el sonido quedan para la
     * sincronización automática con el TROPHY.TRP del núcleo.
     * R2-P2-11/R2-P2-37: el mutex de desbloqueo y la secuencia
     * (unlockTrophyMutex, unlockSeq) ya no tenían llamador y se
     * eliminaron; solo queda el anunciador. */
    private val _justUnlocked = MutableStateFlow<TrophyProgress?>(null)
    val justUnlocked: StateFlow<TrophyProgress?> = _justUnlocked.asStateFlow()
    private val unlockAnnouncer = TrophyUnlockAnnouncer()

    private val _trophyScan = MutableStateFlow<TrophyStoreScan?>(null)
    val trophyScan: StateFlow<TrophyStoreScan?> = _trophyScan.asStateFlow()

    // R2-S01: lectura del TROPHY.TRP real del juego instalado (diagnóstico
    // en Logros): nº de trofeos y nombres según el juego, no según la lista
    // del launcher. Null mientras no se ha leído o sin juego instalado.
    private val _trpRead = MutableStateFlow<TrpReadResult?>(null)
    val trpRead: StateFlow<TrpReadResult?> = _trpRead.asStateFlow()

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    // Portada de Home decodificada UNA vez en el hilo IO: painterResource
    // decodifica en el hilo principal y lo repetía en cada vuelta a Home
    // (la composición se descarta al navegar). Null mientras carga: la
    // Home usa painterResource como respaldo transitorio.
    private val _coverBitmap = MutableStateFlow<ImageBitmap?>(null)
    val coverBitmap: StateFlow<ImageBitmap?> = _coverBitmap.asStateFlow()

    private val _pendingBenchmark = MutableStateFlow<BenchmarkRun?>(null)
    val pendingBenchmark: StateFlow<BenchmarkRun?> = _pendingBenchmark.asStateFlow()

    private val _generatedConfig = MutableStateFlow("")
    val generatedConfig: StateFlow<String> = _generatedConfig.asStateFlow()

    // ORDEN DE INICIALIZACIÓN (crash real 2026-11-08, runs #80/#82/#84):
    // en Kotlin los inicializadores y los bloques init se ejecutan EN
    // EL ORDEN EN QUE APARECEN en la clase. El init lanza corrutinas
    // que tocan estos flows; si un flow se declara DESPUÉS del init,
    // la corrutina puede correr antes de que su inicializador se
    // ejecute y el campo aún es null -> NullPointerException en
    // setValue y la app muere al abrir. A veces abre y a veces no:
    // es una carrera entre el hilo IO y el hilo principal terminando
    // el constructor. _audioFixSnapshot vivía 400 líneas después del
    // init: por eso el crash era intermitente. TODOS los flows que
    // toca el init deben declararse ANTES del init.
    private val _audioFixSnapshot = MutableStateFlow<LauncherSettings?>(null)
    val audioFixSnapshot: StateFlow<LauncherSettings?> = _audioFixSnapshot.asStateFlow()

    init {
        refreshStatus()
        refreshLog()
        loadCoverBitmap()
        viewModelScope.launch(Dispatchers.IO) { sessionManager.ensureDefaultSession() }
        // El snapshot de "Corregir audio" vive en un archivo privado:
        // si la app muere, "Deshacer" sigue disponible al volver.
        viewModelScope.launch(Dispatchers.IO) { _audioFixSnapshot.value = readSettingsSnapshot(audioFixSnapshotFile()) }
        viewModelScope.launch {
            // M37: si el DataStore está corrupto, first() lanza y tumbaba
            // esta corrutina sin aviso. Best-effort: sin GPU, se usa el
            // preset G99 por defecto.
            runCatching {
                val gpu = settingsRepo.gpuInfoFlow.first()
                if (gpu == null) {
                    detectGpu()
                } else if (gpu.vendor == GpuVendor.ADRENO) {
                    // GPU ya conocida de un arranque anterior: si una
                    // build posterior trae el driver incluido en
                    // assets, instalarlo sin esperar a re-detectar.
                    ensureBundledTurnipDriver()
                }
            }
        }
    }

    fun clearJustUnlocked() {
        unlockAnnouncer.clear()
        _justUnlocked.value = null
    }

    // P1-16 (2026-11-09): el Inicio se sentía lento porque
    // refreshStatus() se llamaba desde 17 sitios sin ninguna guarda:
    // cada llamada lanzaba un escaneo completo del disco (scanStatus +
    // firmware + TRP + ...). El reporte 21:46 mostró 5 scanStatus en
    // UN segundo. Mutex + coalescencia: si ya hay un escaneo en curso,
    // la llamada solo marca que hay un refresco pendiente y vuelve;
    // al terminar, el escaneo se repite UNA vez si algo lo pidió. Como
    // mucho 2 escaneos por ráfaga. Un withLock a secas NO vale:
    // encolaría los 5 escaneos. AtomicBoolean: el flag lo escribe
    // cualquier corrutina IO (fuera del mutex) y lo lee la que escanea.
    private val refreshMutex = Mutex()
    private val refreshPending = AtomicBoolean(false)

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!refreshMutex.tryLock()) {
                refreshPending.set(true)
                return@launch
            }
            try {
                do {
                    refreshPending.set(false)
                    refreshStatusOnce()
                } while (refreshPending.getAndSet(false))
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    /** Un escaneo completo del estado (disco + firmware + TRP). Solo lo llama refreshStatus(), bajo el mutex. */
    private fun refreshStatusOnce() {
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
            // Crash al abrir (#80/#82): NO cargar el .so aquí. `available`
            // ejecuta System.loadLibrary y un fallo nativo en sus
            // inicializadores mata el proceso sin stack trace Java antes
            // del primer frame. Solo se comprueba que el archivo existe;
            // la carga real es al lanzar el juego.
            _embeddedCore.value =
                runCatching { Vita3kCoreBridge.isLibraryFilePresent(context) }.getOrNull()
            _batteryOptDisabled.value =
                runCatching { launcher.isBatteryOptimizationDisabled() }.getOrDefault(false)
            refreshDownloadsCopies()
            val title = _status.value.titleId?.id
            _trophyScan.value = if (title != null) {
                runCatching { TrophyStoreReader.scan(library.vitaRoot, title) }.getOrNull()
            } else null
            // R2-S01: el TRP del juego instalado, leído del .pkg ya
            // extraído (appDir del ux0 efectivo). Unreadable si no trae
            // TRP o el XML no se puede interpretar: nunca se inventa un
            // conteo; la lista de 27 no cambia sin esta prueba.
            _trpRead.value = if (title != null) {
                runCatching { TrophyTrpReader.readBundledTrp(library.appDir(title)) }.getOrNull()
            } else null
            // P1-11: el pendiente de licencia es solo en memoria y se
            // pierde al reiniciar; se reconstruye desde disco (lo que
            // falta de verdad, según storedPkgsMissingLicense) para que
            // la tarjeta "Falta la licencia" de Importar aparezca
            // también tras reiniciar, sin reimportar el .pkg.
            rebuildPendingLicenseFromDisk()
    }

    /**
     * Content ID real del header de un .pkg guardado, o null si no se
     * puede leer. Solo lee el header (no el .pkg entero). Nunca ""
     * (P1-7): con "" la validación del work.bin binario falla siempre.
     */
    private fun readStoredPkgContentId(pkgFile: java.io.File?): String? {
        if (pkgFile == null || !pkgFile.isFile) return null
        return runCatching {
            pkgFile.inputStream().use { PkgHeaderParser.parse(it) }
        }.getOrNull()?.getOrNull()?.contentId
    }

    /** .pkg guardado real (ux0 efectivo o antiguo) para un pendiente de licencia. */
    private fun storedPkgFileFor(
        kind: com.danielsnagustin.childoflight.launcher.model.PkgKind,
        titleId: String,
        dlcId: String?,
    ): java.io.File? = when (kind) {
        com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
            library.findPkgGameFile(titleId)
        com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC ->
            dlcId?.let { library.findPkgDlcFile(it) }
        com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE ->
            library.findPkgPatchFile(titleId)
    }

    /**
     * Pendiente de licencia para un .pkg sin work.bin, con su Content
     * ID real (P1-7: del header del .pkg; para el juego, del estado
     * escaneado si lo trae, si no del header). null si el .pkg ya no
     * está en disco. Se llama en IO (lee el header del .pkg).
     */
    private fun pendingLicenseFor(
        missing: GameLibraryRepository.MissingPkgLicense,
    ): PendingPkgLicense? {
        val pkgFile = storedPkgFileFor(missing.kind, missing.titleId.id, missing.dlcId)
            ?: return null
        val contentId = when (missing.kind) {
            com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                _status.value.pkgContentId?.takeIf { it.isNotBlank() }
                    ?: readStoredPkgContentId(pkgFile)
            else -> readStoredPkgContentId(pkgFile)
        }
        return PendingPkgLicense(
            titleId = missing.titleId.id,
            kind = missing.kind,
            contentId = contentId,
            dlcId = missing.dlcId,
            storedPath = pkgFile.absolutePath,
        )
    }

    /**
     * Pendiente de licencia para REEMPLAZAR el work.bin del .pkg del
     * juego ya instalado en el núcleo (P1-12: InstalledWithoutLicense:
     * el juego quedó instalado pero su licencia no se registró; el
     * work.bin en disco existe, así que NO sale de
     * storedPkgsMissingLicense y hay que construirlo desde el estado).
     * null si no hay .pkg de juego guardado.
     */
    private fun pendingLicenseForInstalledGame(): PendingPkgLicense? {
        val titleId = _status.value.pkgTitleId?.id ?: _status.value.titleId?.id
            ?: return null
        val pkgFile = library.findPkgGameFile(titleId) ?: return null
        val contentId = _status.value.pkgContentId?.takeIf { it.isNotBlank() }
            ?: readStoredPkgContentId(pkgFile)
        return PendingPkgLicense(
            titleId = titleId,
            kind = com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME,
            contentId = contentId,
            dlcId = null,
            storedPath = pkgFile.absolutePath,
        )
    }

    /**
     * Sincroniza _pendingPkgLicense con el disco (P1-11). Reglas:
     * - Si no falta ninguna licencia y el pendiente apunta a un .pkg
     *   que ya no existe, se limpia. Si el .pkg sigue existiendo, el
     *   pendiente se CONSERVA: puede ser un reemplazo (P1-12, el
     *   work.bin existe en disco pero el núcleo no registró la
     *   licencia) y borrarlo dejaría sin acción al diálogo.
     * - Si falta alguna licencia y el pendiente actual ya es ese
     *   mismo .pkg, se conserva (su Content ID ya es el bueno); si
     *   no, se reconstruye desde el primero que falta.
     * Se llama en IO (lee disco y headers de .pkg).
     */
    private fun rebuildPendingLicenseFromDisk() {
        val missing = runCatching { library.storedPkgsMissingLicense() }
            .getOrDefault(emptyList())
        val current = _pendingPkgLicense.value
        if (missing.isEmpty()) {
            if (current != null &&
                storedPkgFileFor(current.kind, current.titleId, current.dlcId) == null
            ) {
                _pendingPkgLicense.value = null
            }
            return
        }
        val first = missing.first()
        val matchesCurrent = current != null &&
            current.kind == first.kind &&
            current.titleId == first.titleId.id &&
            current.dlcId == first.dlcId
        if (matchesCurrent) return
        pendingLicenseFor(first)?.let { _pendingPkgLicense.value = it }
    }

    /**
     * Tras un launch(): si el resultado es un bloqueo o fallo por
     * licencia, deja _pendingPkgLicense listo para que Importar
     * ofrezca "Elegir work.bin". Se llama en IO, antes de mostrar
     * el diálogo (P0-1 lado ViewModel, P1-12).
     */
    private fun prepareLicensePendingForOutcome(outcome: Vita3KLauncher.LaunchOutcome) {
        when (outcome) {
            is Vita3KLauncher.LaunchOutcome.Blocked ->
                if (outcome.reason.contains("Falta la licencia (work.bin)")) {
                    rebuildPendingLicenseFromDisk()
                }
            is Vita3KLauncher.LaunchOutcome.EmbeddedFailed ->
                if (outcome.reason.contains("no pudo copiar su licencia")) {
                    pendingLicenseForInstalledGame()?.let { _pendingPkgLicense.value = it }
                }
            else -> Unit
        }
    }

    fun refreshLog() {
        // El log se leía en el hilo principal (file.readLines() en init y
        // en cada onResume): hasta 200 KB de I/O bloqueando el primer
        // frame. Ahora en IO; la UI observa el flujo.
        viewModelScope.launch(Dispatchers.IO) {
            _logText.value = LauncherLog.readLast(context, 60)
        }
    }

    private fun loadCoverBitmap() {
        viewModelScope.launch(Dispatchers.IO) {
            _coverBitmap.value = runCatching {
                BitmapFactory.decodeResource(context.resources, R.drawable.col_cover_clean)?.asImageBitmap()
            }.getOrNull()
        }
    }

    fun refreshConfigPreview(settings: LauncherSettings) {
        // La vista previa debe mostrar lo mismo que se escribirá al
        // lanzar (incluido el fallback Vulkan -> OpenGL): por eso se
        // lee la GPU detectada antes de generar.
        viewModelScope.launch {
            val gpu = settingsRepo.gpuInfoFlow.first()
            // P1-14: generate() espera vitaFsPath, no vitaRoot: con la
            // raíz, la vista previa mostraba el pref-path ANTIGUO (el
            // del bug -3) mientras writeConfig escribía el bueno.
            _generatedConfig.value = Vita3kConfigGenerator.generate(
                settings,
                Vita3kConfigGenerator.vitaFsPathFor(library.vitaRoot).absolutePath,
                gpu,
            )
        }
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
            // RAM baja (≤ 4 GB): el primer preset es el conservador
            // (1x, Double Buffer, sin FXAA/FSR); el usuario puede
            // subir a 2x después si quiere.
            val lowRam = DeviceMemory.isLowRamDevice(context)
            val appliedPreset = if (!settingsRepo.isPresetApplied()) {
                val profile = defaultProfileFor(gpu)
                settingsRepo.applyPreset(profile, current = current, lowRam = lowRam)
                true
            } else {
                false
            }
            val lowRamNote = if (appliedPreset && lowRam) " Memoria baja (4 GB o menos): perfil conservador (1x) aplicado." else ""
            withContext(Dispatchers.Main) {
                // Sin Vulkan, el aviso va en el mismo mensaje de detección
                // (primer arranque): el config.yml ya cae a OpenGL solo
                // (resolveBackend), pero el usuario debe saberlo.
                val vulkanWarning = if (!gpu.vulkanSupported) {
                    " Aviso: este teléfono no tiene Vulkan; el juego usará OpenGL y puede ir más lento o con fallos gráficos."
                } else {
                    ""
                }
                _message.value = (if (gpu.isUserVerifiedDevice) {
                    "GPU detectada: Helio G99 · Mali-G57 MC2. Procesador verificado por ti: " +
                        if (appliedPreset) "preset G99 Recomendado aplicado." else "tus ajustes personalizados no se tocaron."
                } else {
                    "GPU detectada: ${gpu.shortLabel}. " +
                        if (appliedPreset) "Preset ${defaultProfileFor(gpu).label} aplicado." else "Tus ajustes personalizados no se tocaron."
                }) + lowRamNote + vulkanWarning
            }
            LauncherLog.append(context, "gpu", "Detectada: ${gpu.renderer} | SoC ${gpu.socModel} | ${gpu.vulkanVersionLabel}")
            // En Adreno, instalar el driver incluido si lo trae la APK
            // (no-op mientras assets no tenga el zip; nunca en Mali).
            ensureBundledTurnipDriver()
        }
    }

    // --- Ajustes ---

    fun saveSettings(settings: LauncherSettings) {
        viewModelScope.launch {
            // P1-18: si el guardado falla (disco lleno, archivo corrupto),
            // la app ya no muere (safeEdit lo registra y devuelve false),
            // pero el usuario debe saber que su cambio no quedó guardado.
            if (!settingsRepo.save(settings)) {
                _message.value = "No se pudieron guardar los ajustes (fallo al escribir). Inténtalo de nuevo."
            }
        }
    }

    fun applyPreset(profile: GpuProfileId) {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.applyPreset(profile, current = current)
            // R2-P2-13: el preset no debe reactivar en silencio el modo
            // rendimiento ni el auto-revert: presetSettings trae
            // performanceMode=true (G99) y autoRevert=true por defecto,
            // así que aplicar un preset de calidad con el modo
            // rendimiento apagado lo encendía sin avisar (y con él el
            // auto-revert a 1x). Se conservan los valores del usuario.
            val afterPreset = settingsRepo.settingsFlow.first()
            if (afterPreset.performanceMode != current.performanceMode ||
                afterPreset.autoRevertTo1xIfBelow28Fps != current.autoRevertTo1xIfBelow28Fps
            ) {
                settingsRepo.save(
                    afterPreset.copy(
                        performanceMode = current.performanceMode,
                        autoRevertTo1xIfBelow28Fps = current.autoRevertTo1xIfBelow28Fps,
                    ),
                )
                LauncherLog.append(context, "preset", "Preset aplicado: ${profile.name} (modo rendimiento y auto-revert conservados del usuario)")
            }
            // Turnip solo vale en Adreno y solo si el zip está
            // instalado: en Mali (o sin Turnip instalado) el preset
            // "Calidad / Turnip" dejaría gpuDriver=TURNIP sin efecto
            // y sin forma de revertirlo desde la UI de Mali. Se
            // vuelve al driver del sistema y se avisa.
            if (profile == GpuProfileId.ADRENO_QUALITY_TURNIP) {
                val gpu = settingsRepo.gpuInfoFlow.first()
                val updated = settingsRepo.settingsFlow.first()
                if (gpu?.vendor != GpuVendor.ADRENO || !updated.turnipInstalled) {
                    settingsRepo.save(updated.copy(gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM))
                    _message.value = "Preset aplicado: ${profile.label}. Turnip no está disponible en este teléfono (solo Adreno con el driver instalado): se usa el driver del sistema."
                    LauncherLog.append(context, "preset", "Preset aplicado: ${profile.name} (Turnip no disponible, driver Sistema)")
                    return@launch
                }
            }
            _message.value = "Preset aplicado: ${profile.label}"
            LauncherLog.append(context, "preset", "Preset aplicado: ${profile.name}")
        }
    }

    fun resetSettings() {
        viewModelScope.launch {
            val gpu = settingsRepo.gpuInfoFlow.first()
            val current = settingsRepo.settingsFlow.first()
            // Si el Modo seguro estaba ON, su copia queda huérfana al
            // restablecer todo: se borra (el reseteo es intencionado, los
            // ajustes previos se descartan a propósito).
            deleteSettingsSnapshot(safeModeSnapshotFile())
            settingsRepo.resetToDefaults(gpu)
            // R2-P2-12: "Restablecer ajustes" borraba también el estado
            // de Turnip (resetToDefaults no lo conserva): se restaura
            // encima del reseteo, incluido el driver TURNIP si era el
            // activo. La entrega del .pkg ya la conserva resetToDefaults.
            val reset = settingsRepo.settingsFlow.first()
            settingsRepo.save(
                reset.copy(
                    turnipInstalled = current.turnipInstalled,
                    turnipVersion = current.turnipVersion,
                    customDriverName = current.customDriverName,
                    // P1-8: el TURNIP activo solo se conserva si el
                    // teléfono puede usarlo (Adreno con el driver
                    // instalado). En Mali (o sin Turnip instalado) el
                    // reseteo volvía a dejar un TURNIP huérfano sin
                    // forma de revertirlo desde la UI.
                    gpuDriver = if (current.gpuDriver == com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP &&
                        gpu?.vendor == com.danielsnagustin.childoflight.launcher.model.GpuVendor.ADRENO &&
                        current.turnipInstalled
                    ) {
                        com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP
                    } else {
                        reset.gpuDriver
                    },
                ),
            )
            _message.value = "Ajustes restablecidos al preset de tu GPU."
        }
    }

    /**
     * Modo seguro: fuerza 1x, SDL, High Accuracy OFF. Al activarlo se
     * guarda una copia de los ajustes actuales en un archivo privado
     * (col-safe-mode-snapshot.json); al desactivarlo se restauran SOLO
     * los campos que el modo seguro sobrescribe
     * (restoreSafeModeSnapshot): los cambios hechos mientras estaba
     * encendido se conservan, y el estado de Turnip y de entrega del
     * .pkg — que el snapshot no guarda — sobreviven (R2-P1-08/09).
     */
    fun setSafeMode(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            if (enabled) {
                writeSettingsSnapshot(safeModeSnapshotFile(), current)
                settingsRepo.save(applySafeMode(current))
                _message.value = "Modo seguro activado: 1x, SDL, High Accuracy OFF. Tus ajustes anteriores quedan guardados: al desactivarlo se restauran los valores que el modo seguro cambió."
            } else {
                val snapshot = readSettingsSnapshot(safeModeSnapshotFile())
                deleteSettingsSnapshot(safeModeSnapshotFile())
                if (snapshot != null) {
                    settingsRepo.save(restoreSafeModeSnapshot(snapshot, current))
                    _message.value = "Modo seguro desactivado. Se restauraron los valores anteriores al modo seguro; los cambios hechos mientras estaba activado se conservan."
                } else {
                    settingsRepo.save(current.copy(safeMode = false))
                    _message.value = "Modo seguro desactivado. No había ajustes guardados que restaurar: vuelve a aplicar tu preset si lo necesitas."
                }
            }
        }
    }

    /**
     * "Corregir audio": SDL + NGS ON + shaderCache ON + limpiar caché
     * de shaders (Ola 2, Etapa 4). Antes de aplicar se guarda una
     * copia de los ajustes actuales en un archivo privado
     * (col-audio-fix-snapshot.json): la pantalla de Audio ofrece
     * "Deshacer" para restaurarlos, también si la app se cierra
     * entre medias. La caché limpiada no se recupera (se vuelve a
     * llenar sola al jugar) y el diálogo lo dice antes de aplicar.
     * El destino de los ajustes es audioFixTarget (misma fuente que
     * el diálogo de confirmación). No reinicia el juego solo: hay
     * que volver a lanzar el juego.
     *
     * La declaración de _audioFixSnapshot está antes del init (ver
     * arriba): el init lanza una corrutina que la toca y los
     * inicializadores se ejecutan en orden de aparición.
     */
    /**
     * Snapshots persistentes de ajustes (auditoría de funcionalidad
     * 2026-11-08): el mismo JSON que la copia de seguridad
     * (encode/decodeSettingsBackup), en archivos privados del
     * launcher. Solo se guarda la parte de ajustes; la de UI se
     * decodifica con los valores por defecto y se ignora.
     */
    private fun audioFixSnapshotFile() = java.io.File(context.filesDir, "col-audio-fix-snapshot.json")
    private fun safeModeSnapshotFile() = java.io.File(context.filesDir, "col-safe-mode-snapshot.json")

    private fun writeSettingsSnapshot(file: java.io.File, settings: LauncherSettings) {
        runCatching { file.writeText(encodeSettingsBackup(settings, UiPreferences())) }
    }

    private fun readSettingsSnapshot(file: java.io.File): LauncherSettings? {
        if (!file.exists()) return null
        return runCatching {
            decodeSettingsBackup(file.readText(), LauncherSettings(), UiPreferences()).getOrNull()?.first
        }.getOrNull()
    }

    private fun deleteSettingsSnapshot(file: java.io.File) {
        runCatching { file.delete() }
    }

    fun fixAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsRepo.settingsFlow.first()
            writeSettingsSnapshot(audioFixSnapshotFile(), current)
            _audioFixSnapshot.value = current
            settingsRepo.save(audioFixTarget(current))
            val cleared = turnipRepo.clearShaderCache()
            withContext(Dispatchers.Main) {
                _message.value = "Audio corregido: SDL + NGS ON + caché de shaders limpiada " +
                    "(${cleared.deletedFiles} archivos). Vuelve a lanzar el juego para aplicar el cambio."
            }
            LauncherLog.append(context, "audio", "Corregir audio: SDL + NGS ON, caché limpiada")
        }
    }

    /** Restaura los ajustes guardados antes de "Corregir audio" y borra el snapshot.
     * R2-P1-09: restauración por campo (restoreAudioFixSnapshot), igual
     * que el Modo seguro: solo vuelven los 3 campos de audio; el resto
     * — incluidos Turnip y entrega del .pkg, que el snapshot no guarda —
     * se conserva como esté ahora. */
    fun undoFixAudio() {
        viewModelScope.launch {
            val snapshot = readSettingsSnapshot(audioFixSnapshotFile()) ?: _audioFixSnapshot.value ?: return@launch
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(restoreAudioFixSnapshot(snapshot, current))
            deleteSettingsSnapshot(audioFixSnapshotFile())
            _audioFixSnapshot.value = null
            _message.value = "Audio restaurado a los valores anteriores a «Corregir audio» (backend, NGS y caché de shaders). Los demás ajustes no se tocaron. " +
                "La caché de shaders limpiada no se recupera: se vuelve a llenar sola al jugar."
            LauncherLog.append(context, "audio", "Corregir audio deshecho: valores anteriores restaurados")
        }
    }

    /** Oculta el botón "Deshacer" sin restaurar nada (el usuario acepta el cambio). */
    fun clearAudioFixSnapshot() {
        deleteSettingsSnapshot(audioFixSnapshotFile())
        _audioFixSnapshot.value = null
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
    fun importDlc(uri: Uri, force: Boolean = false) =
        importWith(uri, "DLC", { name -> library.importDlc(uri, name, force) }, ::installImportedPkg)

    fun importUpdate(uri: Uri, force: Boolean = false) =
        importWith(uri, "UPDATE", { name -> library.importUpdate(uri, name, force) }, ::installImportedPkg)
    // --- Firmware (núcleo embebido, Etapa 2) ---

    /**
     * Importar el PUP UNA SOLA VEZ: el usuario elige su propio
     * PSVUPDAT.PUP con el selector de archivos del sistema (SAF),
     * sin exigir nombre ni ruta concretos (la extensión se resuelve
     * por nombre, MIME o magia SCEUF). El archivo se guarda SOLO en
     * el teléfono (nunca sale de ahí, nunca entra en el repo/APK) y,
     * si el núcleo embebido ya está presente, se instala en él en el
     * mismo acto. Si el núcleo aún no está, el PUP queda guardado y
     * se instalará solo al jugar o desde Importar → Firmware. Si el usuario no tiene el PUP, no se le manda a
     * buscarlo por internet: la app lo dice en una frase y se sigue
     * jugando con la app externa.
     */
    fun importFirmware(uri: Uri) {
        if (!beginImport()) return
        _pendingDuplicate.value = PendingDuplicate(uri, "FIRMWARE")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = fileNameOf(uri)
                val result = library.importFirmware(uri, name)
                _pendingDuplicate.value = null
                if (result is ImportResult.FirmwareSuccess) {
                    when (val installed = tryInstallSavedFirmwareInCore()) {
                        is FirmwareCoreResult.Done ->
                            withContext(Dispatchers.Main) { _message.value = installed.message }
                        is FirmwareCoreResult.NotInstalled ->
                            withContext(Dispatchers.Main) { _message.value = installed.message }
                    }
                } else {
                    withContext(Dispatchers.Main) { showImportResult(result) }
                }
            } finally {
                endImport()
            }
        }
    }

    /** Resultado de intentar instalar el PUP guardado en el núcleo. message = frase para el usuario. */
    private sealed class FirmwareCoreResult {
        data class Done(val message: String) : FirmwareCoreResult()
        /** No se instaló (sin núcleo, sin PUP, o fallo del núcleo). */
        data class NotInstalled(val message: String) : FirmwareCoreResult()
    }

    /**
     * Instala el PUP guardado en el almacenamiento del núcleo
     * embebido (vía EmbeddedPkgInstaller.installFirmware) y registra
     * la versión instalada. No lanza excepciones. Si no hay núcleo,
     * el PUP simplemente queda guardado para cuando llegue.
     * Seriada con firmwareInstallMutex: dos instalaciones a la vez
     * pisarían el mismo vs0.
     */
    private suspend fun tryInstallSavedFirmwareInCore(): FirmwareCoreResult {
        return firmwareInstallMutex.withLock { tryInstallSavedFirmwareInCoreLocked() }
    }

    private suspend fun tryInstallSavedFirmwareInCoreLocked(): FirmwareCoreResult {
        // P1-15: escribir config.yml ANTES de instalar el firmware
        // (igual que play()): installFirmware inicializa el núcleo y
        // el núcleo lee config.yml al iniciar; con un config.yml viejo
        // (pref-path pre-arreglo -3) la sesión quedaría envenenada
        // hasta matar el proceso. Best-effort: si no se puede escribir,
        // se sigue (el fallo queda en el log, como en play()).
        runCatching {
            val settings = settingsRepo.settingsFlow.first()
            val gpu = settingsRepo.gpuInfoFlow.first()
            Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu)
        }.onFailure {
            LauncherLog.append(context, "config", "No se pudo escribir config.yml antes del firmware: ${it.message}")
        }
        if (!Vita3kCoreBridge.available) {
            return FirmwareCoreResult.NotInstalled(
                "Firmware guardado. El núcleo embebido aún no está en esta versión: " +
                    "el PUP queda guardado en el teléfono y se instalará solo cuando el núcleo esté disponible. " +
                    "Mientras tanto, la app externa usa su propio firmware.",
            )
        }
        when (val status = FirmwareResolver.resolve(library.vitaRoot)) {
            is FirmwareStatus.Ready -> {
                // Listo POR MARCADOR (con versión) = el PUP guardado es
                // el que ya se instaló: no hay nada que hacer.
                // Listo SOLO por vs0 (sin marcador) + PUP válido = PUP
                // NUEVO importado después de instalar otro: importar un
                // PUP borra el marcador, así que "ya instalado" sería
                // mentira y el PUP nuevo no se instalaría nunca (el
                // núcleo seguiría con el firmware viejo). En ese caso
                // se cae a la instalación del PUP guardado.
                val markerVersion = FirmwareResolver.installedVersion(library.vitaRoot)
                val pup = FirmwareResolver.pupFile(library.vitaRoot)
                if (markerVersion != null || !FirmwareResolver.isValidPupFile(pup)) {
                    return FirmwareCoreResult.Done("Firmware ya instalado en el núcleo (${status.source}).")
                }
                // Si no: PUP nuevo pendiente; seguir e instalarlo.
            }
            FirmwareStatus.Missing ->
                return FirmwareCoreResult.NotInstalled(FirmwareResolver.NO_FIRMWARE_MESSAGE)
            is FirmwareStatus.Saved -> { /* seguir: hay PUP guardado por instalar */ }
        }
        val pup = FirmwareResolver.pupFile(library.vitaRoot)
        if (!FirmwareResolver.isValidPupFile(pup)) {
            return FirmwareCoreResult.NotInstalled(
                "El archivo guardado no es un PUP válido (debe empezar por SCEUF). " +
                    "Elige de nuevo tu PSVUPDAT.PUP en Importar → Firmware.",
            )
        }
        _firmwareInstalling.value = true
        return try {
            val installer = EmbeddedPkgInstaller(library)
            val result = installer.installFirmware(pup) { percent, status ->
                _installProgress.value = InstallProgress(percent, status)
            }
            result.fold(
                onSuccess = { version ->
                    FirmwareResolver.markInstalled(library.vitaRoot, version)
                    refreshStatus()
                    LauncherLog.append(context, "firmware", "Firmware $version instalado en el núcleo embebido.")
                    FirmwareCoreResult.Done(
                        "Firmware $version instalado en el núcleo embebido. Ya puedes jugar sin la app externa.",
                    )
                },
                onFailure = { e ->
                    LauncherLog.append(context, "firmware", "Instalación en el núcleo falló: ${e.message}")
                    FirmwareCoreResult.NotInstalled(
                        "El PUP quedó guardado, pero el núcleo no pudo instalarlo: ${e.message}. " +
                            "Puedes seguir jugando con la app externa.",
                    )
                },
            )
        } finally {
            _firmwareInstalling.value = false
            _installProgress.value = null
        }
    }

    /** Botón "Instalar en el núcleo embebido" (Importar → Firmware). */
    fun installSavedFirmwareInCore() {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = tryInstallSavedFirmwareInCore()) {
                is FirmwareCoreResult.Done ->
                    withContext(Dispatchers.Main) { _message.value = result.message }
                is FirmwareCoreResult.NotInstalled ->
                    withContext(Dispatchers.Main) {
                        // Sin PUP o instalación fallida: diálogo, no
                        // Snackbar fugaz; el usuario tiene que leerlo.
                        if (result.message == FirmwareResolver.NO_FIRMWARE_MESSAGE ||
                            result.message.startsWith("El PUP quedó guardado")
                        ) {
                            _alert.value = AlertInfo(result.message)
                        } else {
                            _message.value = result.message
                        }
                    }
            }
        }
    }

    /**
     * Antes de JUGAR: si el núcleo está presente y hay un PUP
     * guardado sin instalar, instalarlo ahora (best-effort). NUNCA
     * bloquea el lanzamiento: si falla o no hay PUP, se sigue con el
     * fallback a la app externa (que avisa una vez y lanza igual).
     */
    private suspend fun ensureFirmwareForEmbedded() {
        runCatching { tryInstallSavedFirmwareInCore() }
            .onFailure {
                LauncherLog.append(context, "firmware", "No se pudo preparar el firmware: ${it.message}")
            }
    }

    /** Borra el firmware local (PUP guardado + marcador + vs0 extraído). No toca juego, DLCs ni saves. */
    fun deleteFirmware() {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = FirmwareResolver.deleteFirmware(library.vitaRoot)
            withContext(Dispatchers.Main) {
                _message.value = if (deleted) {
                    "Firmware borrado del launcher y del núcleo."
                } else {
                    "No había firmware que borrar."
                }
            }
            refreshStatus()
        }
    }

    /** Importar el juego desde una carpeta (debe llamarse PCSE00480/PCSB00598 y tener eboot.bin). */
    fun importGameFolder(uri: Uri, force: Boolean = false) {
        if (!beginImport()) return
        _pendingDuplicate.value = PendingDuplicate(uri, "GAME_FOLDER")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _installProgress.value = InstallProgress(null, "Importando… ${fileNameOf(uri)}")
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
                _installProgress.value = InstallProgress(null, "Importando… ${fileNameOf(uri)}")
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
        _installProgress.value = null
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
                // Sin acción de entrega: el motor integrado instala el
                // .pkg al pulsar JUGAR. La entrega a la app externa
                // sigue disponible en la pantalla Importar (fallback).
                _alert.value = AlertInfo(importResultMessage(result), null)
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

    // P2-C5: locale fijo (Locale.US): sin él, en locale español el
    // formato daba "1,14 GB" con coma.
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
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
            // El motor integrado instala el .pkg del JUEGO al pulsar
            // JUGAR (UseEmbeddedPkg). Para DLC/update, PkgStored ya
            // solo ocurre cuando la instalación en el acto falló (con
            // éxito se devuelve DlcSuccess/UpdateSuccess): el mensaje
            // NO promete una instalación en JUGAR que no existe (P1-1).
            val installNote = when (result.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                    "Pulsa JUGAR: el motor integrado instalará el .pkg y arrancará el juego."
                else -> "No se pudo instalar en el núcleo ahora mismo: el .pkg queda guardado. Comprueba que tienes instalada la versión con el motor (la APK nativa, no la pequeña) y vuelve a importarlo para reintentarlo. El motivo exacto queda en el reporte de bug."
            }
            "$what se guardó como .pkg (${result.titleId.id} · ${formatBytes(result.sizeBytes)}) en el almacenamiento interno del launcher.\n\n" +
                installNote
        }
        is ImportResult.PkgNeedsLicense -> {
            val what = when (result.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME -> "El juego"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> "El DLC"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE -> "El update"
            }
            "$what se guardó como .pkg (${result.titleId.id} · ${formatBytes(result.sizeBytes)}), " +
                "pero falta su licencia (work.bin de 512 B): sin ella no se puede instalar.\n\n" +
                "Elige el work.bin en Importar (tarjeta «Falta la licencia»): es el archivo work.bin " +
                "que acompaña al .pkg (a veces se descarga como «work (5).bin»)."
        }
        is ImportResult.Rejected -> "No se instaló nada:\n\n${result.reason.removePrefix(com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository.DUPLICATE_MARKER)}"
        is ImportResult.Failed -> "No se pudo importar:\n\n${result.reason}\n\nSi el archivo es grande, espera sin cerrar la app: la copia puede tardar varios minutos."
    }

    private fun importWith(
        uri: Uri,
        kind: String,
        block: (String) -> ImportResult,
        afterImport: (suspend (ImportResult) -> ImportResult)? = null,
    ) {
        if (!beginImport()) return
        _pendingDuplicate.value = PendingDuplicate(uri, kind)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = fileNameOf(uri)
                // Copia sin % real: estado indeterminado honesto con el
                // nombre del archivo; el % solo existe al instalar en
                // el núcleo (installProgress con percent).
                _installProgress.value = InstallProgress(null, "Importando… $name")
                val result = block(name)
                // P1-1 (2026-11-09): los .pkg de DLC/update se guardaban
                // pero NUNCA se instalaban (installDlcPkg /
                // installUpdatePkg no tenían ningún llamador) y el
                // mensaje prometía una instalación en JUGAR que no
                // existe. El paso afterImport los instala en el acto.
                val finalResult = afterImport?.invoke(result) ?: result
                withContext(Dispatchers.Main) {
                    showImportResult(finalResult)
                }
            } finally {
                endImport()
            }
        }
    }

    /**
     * P1-1 (2026-11-09): instala en el acto un .pkg de DLC o update
     * recién importado. Antes solo se guardaba el archivo y el juego
     * nunca veía el DLC/update. Solo actúa sobre PkgStored /
     * PkgNeedsLicense de DLC/UPDATE (.zip/.vpk ya extraen directo a
     * addcont/ y devuelven DlcSuccess/UpdateSuccess; el .pkg del juego
     * se instala en JUGAR por su tamaño). Con éxito devuelve
     * DlcSuccess/UpdateSuccess ("DLC X instalado"); si falla, devuelve
     * el resultado original (el .pkg queda guardado) y el motivo exacto
     * queda en el log para el reporte de bug. La licencia puede venir
     * del work.bin recién guardado o de una ya registrada en el núcleo
     * (findPkgZrif): installPkgFile lo resuelve.
     */
    private suspend fun installImportedPkg(result: ImportResult): ImportResult {
        val kind = when (result) {
            is ImportResult.PkgStored -> result.kind
            is ImportResult.PkgNeedsLicense -> result.kind
            else -> return result
        }
        if (kind != com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC &&
            kind != com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE
        ) return result
        val titleId = when (result) {
            is ImportResult.PkgStored -> result.titleId
            is ImportResult.PkgNeedsLicense -> result.titleId
            else -> return result
        }
        val dlcId = when (result) {
            is ImportResult.PkgStored -> java.io.File(result.storedPath).nameWithoutExtension
            is ImportResult.PkgNeedsLicense -> java.io.File(result.storedPath).nameWithoutExtension
            else -> return result
        }
        _installProgress.value = InstallProgress(null, "Instalando en el núcleo…")
        val installed = installStoredPkg(kind, titleId, dlcId)
        return installed.fold(
            onSuccess = {
                LauncherLog.append(context, "import", "DLC/update instalado en el núcleo: $dlcId (${titleId.id}).")
                if (kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC) {
                    ImportResult.DlcSuccess(titleId, dlcId)
                } else {
                    ImportResult.UpdateSuccess(titleId)
                }
            },
            onFailure = { e ->
                LauncherLog.append(context, "import", "No se pudo instalar el DLC/update $dlcId en el núcleo: ${e.message} (el .pkg queda guardado).")
                result
            },
        )
    }

    /**
     * Instala un .pkg de DLC o update ya guardado (por DLC ID o por
     * Title ID). Lo usan la importación (installImportedPkg) y el
     * guardado del work.bin: en ambos casos la licencia puede venir
     * del work.bin o de una ya registrada en el núcleo (findPkgZrif).
     */
    private suspend fun installStoredPkg(
        kind: com.danielsnagustin.childoflight.launcher.model.PkgKind,
        titleId: TitleId,
        dlcId: String,
    ): Result<Unit> {
        _installProgress.value = InstallProgress(null, "Instalando en el núcleo…")
        val installer = EmbeddedPkgInstaller(library)
        return if (kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC) {
            installer.installDlcPkg(dlcId) { percent, status ->
                _installProgress.value = InstallProgress(percent, status)
            }
        } else {
            installer.installUpdatePkg(titleId) { percent, status ->
                _installProgress.value = InstallProgress(percent, status)
            }
        }
    }

    // --- .pkg + Vita3K externo ---

    /**
     * Guarda el work.bin elegido por el usuario para el .pkg pendiente
     * de licencia. Con guarda beginImport (P2-C4: antes corría sin
     * ella y podía pisarse con otra importación sobre el mismo
     * destino). La licencia se escribe JUNTO AL .pkg REAL (P1-5c:
     * findPkg* dual legacy+embebido + findPkgWorkBinFile): escribirla
     * en el ux0 efectivo mientras el .pkg estaba en legacy la dejaba
     * huérfana y el .pkg se reinstalaba sin licencia.
     */
    fun importWorkBin(uri: Uri) {
        val pending = _pendingPkgLicense.value ?: return
        if (!beginImport()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _installProgress.value = InstallProgress(null, "Guardando work.bin…")
                // .pkg real (puede vivir en el ux0 antiguo): sin él no
                // hay dónde escribir la licencia.
                val pkgReal = storedPkgFileFor(pending.kind, pending.titleId, pending.dlcId)
                if (pkgReal == null) {
                    withContext(Dispatchers.Main) {
                        _alert.value = AlertInfo(
                            "No se encontró el .pkg guardado (${pending.titleId}): vuelve a importar el .pkg y luego su work.bin.",
                        )
                    }
                    return@launch
                }
                val licenseDest = library.findPkgWorkBinFile(pkgReal)
                val result = library.importWorkBin(uri, licenseDest, pending.contentId)
                // P1-1: con la licencia ya guardada, el DLC/update se
                // instala en el acto (antes el mensaje prometía una
                // instalación en JUGAR que no existe).
                var installed: Boolean? = null
                if (result.isSuccess && pending.kind != com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME) {
                    val title = TitleId.fromString(pending.titleId)
                    val dlcId = pending.dlcId ?: java.io.File(pending.storedPath).nameWithoutExtension
                    installed = if (title != null) {
                        installStoredPkg(pending.kind, title, dlcId).fold(
                            onSuccess = {
                                LauncherLog.append(context, "import", "DLC/update instalado en el núcleo tras guardar la licencia: $dlcId (${title.id}).")
                                true
                            },
                            onFailure = { e ->
                                LauncherLog.append(context, "import", "No se pudo instalar el DLC/update $dlcId tras guardar la licencia: ${e.message} (el .pkg queda guardado).")
                                false
                            },
                        )
                    } else false
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess {
                        _pendingPkgLicense.value = null
                        val message = if (pending.kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME) {
                            "Licencia guardada: el .pkg ya tiene su work.bin.\n\nPulsa JUGAR: el motor integrado instalará el .pkg y arrancará el juego."
                        } else if (installed == true) {
                            "Licencia guardada y DLC/update instalado en el núcleo."
                        } else {
                            "Licencia guardada: el .pkg ya tiene su work.bin.\n\nLa instalación no se pudo hacer ahora: vuelve a importar el .pkg para reintentarla."
                        }
                        _alert.value = AlertInfo(message, null)
                    }.onFailure { e ->
                        _alert.value = AlertInfo("Ese archivo no es un work.bin válido: ${e.message}")
                    }
                }
            } finally {
                // endImport() ya refresca el estado (y reconstruye el
                // pendiente desde disco si sigue faltando otra licencia).
                endImport()
            }
        }
    }

    /**
     * Valida que haya .pkg + licencia y pide CONFIRMACIÓN antes de copiar
     * nada a Descargas/ChildOfLight/. La copia solo ocurre si el usuario
     * la confirma en el diálogo (confirmDeliverPkgToDownloads): sin
     * confirmación, la carpeta Descargas/ChildOfLight/ no se crea nunca.
     */
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
                // P1-7: el pendiente lleva el Content ID REAL del .pkg
                // (del header, vía pendingLicenseFor) y la ruta real del
                // .pkg (efectivo o antiguo). Antes, DLC/update llevaban
                // contentId = "": un work.bin binario válido se
                // rechazaba siempre como "de otro juego". Si el header
                // no se puede leer, el Content ID es null (sin
                // expectativa), nunca "".
                val pending = pendingLicenseFor(first)
                withContext(Dispatchers.Main) {
                    if (pending != null) _pendingPkgLicense.value = pending
                    _alert.value = AlertInfo(
                        "Falta la licencia (work.bin) del ${first.kind.label} ${first.titleId.id}: sin ella no se puede instalar.\n\n" +
                            "Elige el work.bin en Ajustes (tarjeta «Falta la licencia»).",
                    )
                }
                return@launch
            }
            // La copia a Descargas NUNCA es automática: el usuario la
            // confirma en el diálogo (qué se copia, cuánto ocupa y por
            // qué). Sin confirmación, la carpeta Descargas/ChildOfLight/
            // no se crea nunca.
            val files = library.storedPkgFilesForDelivery()
            val total = files.sumOf { it.length() }
            withContext(Dispatchers.Main) {
                _alert.value = AlertInfo(
                    downloadsCopyConfirmText(files.map { it.name }, total),
                    AlertAction.CONFIRM_DOWNLOADS_COPY,
                )
            }
        }
    }

    /**
     * El usuario confirmó la copia a Descargas en el diálogo: copia el
     * .pkg + work.bin a Descargas/ChildOfLight/, abre la app externa y
     * muestra la guía de instalación. Es el ÚNICO camino que crea la
     * carpeta Descargas/ChildOfLight/; con el núcleo embebido listo, el
     * juego se instala desde el almacenamiento interno y este paso
     * desaparece.
     */
    fun confirmDeliverPkgToDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
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
                            "Los archivos se copiaron a Descargas/ChildOfLight/, pero la app externa no está instalada.\n\n" +
                                "Instálala y luego vuelve a pulsar «Copiar a la app externa».",
                            AlertAction.INSTALL_VITA3K,
                        )
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    _alert.value = AlertInfo("No se pudo entregar el .pkg a la app externa: ${e.message}")
                }
            }
            refreshStatus()
        }
    }

    /** Guía paso a paso para instalar el .pkg dentro de Vita3K (una sola vez). */
    private fun pkgInstallGuideText(copied: List<String>): String {
        val files = copied.joinToString("\n") { "• $it" }
        return "Archivos copiados a Descargas/ChildOfLight/:\n$files\n\n" +
            "La app externa está abierta. Instala el juego ahí una sola vez:\n\n" +
            "1. En la app externa: File → Install Package (.pkg).\n" +
            "2. Elige el .pkg de Descargas/ChildOfLight/.\n" +
            "3. Pulsa «Select work.bin» y elige el work.bin de la misma carpeta.\n" +
            "4. Espera a que termine la instalación y vuelve aquí.\n\n" +
            "Cuando lo hayas instalado, pulsa «Ya lo instalé en la app externa»."
    }

    /** El usuario confirma que instaló el .pkg en Vita3K (honesto: el launcher no puede comprobarlo). */
    fun confirmPkgInstalledInVita3k() {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(current.copy(pkgInstalledInVita3k = true))
            _message.value = "Anotado: el juego quedó instalado en la app externa. Pulsa JUGAR y ábrelo en su biblioteca."
            LauncherLog.append(context, "pkg", "Usuario confirmó instalación del .pkg en la app externa.")
        }
    }

    // --- Turnip ---

    /**
     * Instala en el núcleo embebido el zip Turnip ya guardado por el
     * launcher (<filesDir>/vita3k/drivers/turnip/<stem>.zip). Devuelve el
     * nombre del driver instalado (stem del zip) o "" si no hay núcleo
     * o la instalación falló.
     *
     * Verificado contra upstream (pin 3e43bc80, native_drivers.cpp):
     * installCustomDriver extrae el zip en <filesDir>/driver/<stem>/
     * y devuelve el stem; si ese driver ya está instalado, rechaza
     * reinstalar ("already exists") y devuelve "". En ese caso el
     * nombre ya instalado es el correcto y se devuelve tal cual.
     * Best-effort y silencioso si no hay núcleo (Etapa 1): el zip
     * queda guardado y se instalará al lanzar el juego con el núcleo
     * (Vita3KLauncher.ensureTurnipDriverInstalled).
     */
    private fun installDriverIntoCore(): String {
        if (!Vita3kCoreBridge.available) return ""
        val zip = turnipRepo.installedDriverFile() ?: return ""
        val expectedName = zip.name.substringBeforeLast(".")
        val installed = Vita3kCoreBridge.installCustomDriver(zip.absolutePath)
        if (installed.isNotEmpty()) {
            LauncherLog.append(context, "driver", "Turnip instalado en el núcleo: $installed")
            return installed
        }
        if (expectedName.isNotEmpty() && Vita3kCoreBridge.getInstalledCustomDrivers().contains(expectedName)) {
            LauncherLog.append(context, "driver", "Turnip ya estaba instalado en el núcleo: $expectedName")
            return expectedName
        }
        // Otro fallo (zip corrupto, sin meta/.so válido...): un intento
        // tras borrar el registro viejo, por si quedó a medias.
        if (expectedName.isNotEmpty() && Vita3kCoreBridge.removeCustomDriver(expectedName)) {
            val retried = Vita3kCoreBridge.installCustomDriver(zip.absolutePath)
            if (retried.isNotEmpty()) {
                LauncherLog.append(context, "driver", "Turnip reinstalado en el núcleo: $retried")
                return retried
            }
        }
        LauncherLog.append(context, "driver", "No se pudo instalar Turnip en el núcleo (zip ${zip.name}); el núcleo usará el driver del sistema.")
        return ""
    }

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
                // El nombre que va a custom-driver-name es el del driver
                // instalado en el núcleo; si no hay núcleo aún, el stem
                // del zip (mismo valor que devuelve installCustomDriver).
                val coreName = installDriverIntoCore()
                val driverName = coreName.ifEmpty { savedName.substringBeforeLast(".") }
                if (gpu?.vendor == GpuVendor.ADRENO) {
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = true,
                            turnipVersion = savedName,
                            customDriverName = driverName,
                            gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP,
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        _message.value = if (Vita3kCoreBridge.available && coreName.isEmpty()) {
                            "Turnip guardado ($savedName), pero el núcleo no pudo instalarlo: se usará el driver del sistema hasta reinstalarlo o relanzar el juego."
                        } else {
                            "Turnip instalado: $savedName. El núcleo limpiará la caché de shaders al arrancar con el nuevo driver."
                        }
                    }
                } else {
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = true,
                            turnipVersion = savedName,
                            customDriverName = driverName,
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

    /**
     * Instala el driver Turnip incluido en assets, SOLO en Adreno y
     * SOLO si no hay ya un driver instalado (manual o incluido).
     * Best-effort y silencioso cuando no hay nada que hacer: si no
     * hay zip en assets (estado actual: sin binario), si la GPU no
     * es Adreno, si el modelo no tiene variante incluida (8xx,
     * 710/720, A32) o si la validación falla, NO se muestra ningún
     * error y se sigue con el driver del sistema. Solo se avisa
     * cuando la instalación tuvo éxito. Nunca bloquea el arranque ni
     * el lanzamiento del juego; nunca toca el juego ni los saves.
     * Se llama tras detectar la GPU (primer arranque y re-detección)
     * y en init si la GPU ya estaba guardada.
     */
    fun ensureBundledTurnipDriver() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val gpu = settingsRepo.gpuInfoFlow.first()
                if (gpu?.vendor != GpuVendor.ADRENO) return@runCatching
                val current = settingsRepo.settingsFlow.first()
                if (current.turnipInstalled) return@runCatching
                if (turnipRepo.installedDriverFile() != null) return@runCatching
                // Sin variante incluida para este modelo Adreno, no se
                // hace nada: el driver del sistema sigue mandando.
                if (BundledTurnipDriver.selectVariant(gpu.renderer) == null) return@runCatching
                val result = turnipRepo.installBundledAsset()
                result.onSuccess { installed ->
                    val coreName = installDriverIntoCore()
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = true,
                            turnipVersion = installed.savedName,
                            customDriverName = coreName.ifEmpty { installed.savedName.substringBeforeLast(".") },
                            gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP,
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        _message.value = "Driver incluido instalado: ${installed.savedName}. Si ves glitches, vuelve al driver del sistema en Ajustes → Driver GPU."
                    }
                }.onFailure { e ->
                    LauncherLog.append(context, "driver", "Driver incluido no instalado: ${e.message}")
                }
            }.onFailure { e ->
                LauncherLog.append(context, "driver", "No se pudo revisar el driver incluido: ${e.message}")
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
            if (!tryBeginLaunch()) return@launch
            try {
            // P3 (crash ~15:06 sin stack trace): entrada de JUGAR en
            // el log con el estado que decide el lanzamiento, para que
            // el próximo reporte muestre hasta dónde llegó el intento.
            val playStatus = _status.value
            LauncherLog.append(context, "launch", formatLaunchAttempt(playStatus.titleId?.id, Vita3kCoreBridge.available, playStatus.firmwareInstalled))
            if (!Vita3kCoreBridge.available) {
                Vita3kCoreBridge.lastLoadError?.let {
                    LauncherLog.append(context, "launch", "Núcleo ausente: $it")
                }
            }
            val settings = settingsRepo.settingsFlow.first()
            val gpu = settingsRepo.gpuInfoFlow.first()
            // 32 bits: el núcleo embebido necesita 64 bits (arm64). No se
            // bloquea nada aquí (la decisión lo dirá con su motivo), pero
            // el usuario debe saber por qué nunca se activa el núcleo.
            // La GPU se pasa al lanzador para el fallback Vulkan->OpenGL
            // del config.yml (solo lo lee el núcleo embebido).
            if (!DeviceCompat.is64BitDevice()) {
                withContext(Dispatchers.Main) {
                    _message.value = "Este teléfono es de 32 bits: el núcleo embebido necesita 64 bits."
                }
            }
            // config.yml ANTES del firmware: instalar el firmware
            // inicializa el núcleo (EmbeddedPkgInstaller) y el núcleo
            // lee config.yml al iniciar; si se escribiera después, el
            // juego arrancaría con los ajustes del lanzamiento anterior.
            // Best-effort: launch() lo vuelve a escribir igual.
            runCatching {
                Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu)
            }.onFailure {
                LauncherLog.append(context, "config", "No se pudo escribir config.yml antes del firmware: ${it.message}")
            }
            // Firmware: si hay PUP guardado y núcleo presente, instalarlo
            // ahora (best-effort, nunca bloquea el lanzamiento).
            ensureFirmwareForEmbedded()
            // Instalación del .pkg en el núcleo (al jugar con .pkg
            // guardado): el % real del núcleo llega a installProgress
            // y se muestra en Import; al terminar se limpia.
            val outcome = launcher.launch(
                library, settings, sessionManager, gpu,
                onInstallProgress = { percent, status ->
                    _installProgress.value = InstallProgress(percent, status)
                },
            )
            _installProgress.value = null
            LauncherLog.append(context, "launch", "JUGAR resultado: ${outcome::class.simpleName}")
            // P0-1 / P1-12: si el resultado es un bloqueo o fallo por
            // licencia, el pendiente queda listo ANTES del diálogo para
            // que Importar ofrezca "Elegir work.bin".
            prepareLicensePendingForOutcome(outcome)
            // P2-A7: la vía externa marca external=true: al volver al
            // launcher sin salir del juego, notePlayEnded solo hace
            // checkpoint en vez de cerrar la medición.
            val launchedExternal = outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternal ||
                outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning
            if (launchedExternal || outcome is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded) {
                sessionManager.notePlayStarted(external = launchedExternal)
            }
            withContext(Dispatchers.Main) {
                // Con el juego solo en .pkg, JUGAR abre la app externa y
                // el juego se abre en SU biblioteca (si ya se instaló ahí);
                // si no, el diálogo ofrece entregarlo ("Instalar en la app
                // externa", con confirmación de la copia a Descargas).
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
                                    "App externa abierta.\n\n" +
                                        "Abre Child of Light en su biblioteca (ya lo instalaste ahí).\n\n" +
                                        "Los ajustes del launcher (config.yml) no los lee la app externa: configúralos dentro de ella.",
                                )
                            } else {
                                AlertInfo(
                                    "App externa abierta, pero el juego aún no está instalado DENTRO de la app externa.\n\n" +
                                        "El .pkg guardado en el launcher no lo ve la app externa: hay que instalarlo ahí una sola vez.",
                                    AlertAction.DELIVER_PKG_TO_VITA3K,
                                )
                            }
                        } else {
                            AlertInfo(
                                "App externa abierta.\n\n" +
                                    "IMPORTANTE: el juego que instalaste en el launcher vive en su almacenamiento privado y la app externa no lo ve. " +
                                    "Para jugar, importa tu copia también dentro de la app externa (su biblioteca).\n\n" +
                                    "Los ajustes del launcher (config.yml) tampoco los lee la app externa: configúralos dentro de ella.",
                            )
                        }
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning ->
                        _alert.value = if (pkgOnly) {
                            AlertInfo(
                                Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL + "\n\n" +
                                    if (settings.pkgInstalledInVita3k) {
                                        "Abre Child of Light en la biblioteca de la app externa."
                                    } else {
                                        "El .pkg aún no está instalado dentro de la app externa."
                                    },
                                if (settings.pkgInstalledInVita3k) null else AlertAction.DELIVER_PKG_TO_VITA3K,
                            )
                        } else {
                            AlertInfo(
                                Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL + "\n\n" +
                                    "El juego que instalaste en el launcher vive en su almacenamiento privado y la app externa no lo ve: " +
                                    "importa tu copia también dentro de la app externa (su biblioteca).",
                            )
                        }
                    is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded ->
                        // Sin Vulkan, el config.yml ya cayó a OpenGL solo;
                        // se avisa aquí porque es el momento en que importa.
                        _message.value = "Núcleo embebido: juego lanzado (${outcome.configPath})." +
                            if (gpu?.vulkanSupported == false) " Sin Vulkan en este teléfono: se usa OpenGL, puede ir más lento o con fallos gráficos." else ""
                    is Vita3KLauncher.LaunchOutcome.EmbeddedCorePresent ->
                        _message.value = "Núcleo embebido detectado, pero falta el punto de entrada JNI."
                    is Vita3KLauncher.LaunchOutcome.EmbeddedFailed -> {
                        // El motor integrado falló al cargar o al arrancar:
                        // diálogo con el motivo exacto y la acción que lo
                        // resuelve (nunca salto automático a la app externa).
                        // P1-12: "instalado pero sin licencia" -> ir a
                        // importar a REEMPLAZAR el work.bin, no reinstalar.
                        // P1-5(e): conflicto de dos instalaciones ->
                        // resolverlo borrando la antigua, no reintentar
                        // (reintentar repite el mismo Conflict siempre).
                        val action = when {
                            outcome.reason.contains("no pudo copiar su licencia") -> AlertAction.GO_IMPORT
                            outcome.reason.contains("no se mueve nada para no mezclar dos instalaciones") -> AlertAction.RESOLVE_LEGACY_CONFLICT
                            else -> AlertAction.RETRY_LAUNCH
                        }
                        val text = if (action == AlertAction.RESOLVE_LEGACY_CONFLICT) {
                            outcome.reason + "\n\n«Borrar la antigua y jugar» borra la instalación antigua (su juego se pierde; los DLCs, updates y saves que falten en el motor integrado se mueven antes a él) y se queda solo con la del motor integrado."
                        } else {
                            outcome.reason
                        }
                        _alert.value = AlertInfo(text, action)
                    }
                    is Vita3KLauncher.LaunchOutcome.Blocked -> {
                        // El diálogo ofrece la acción que resuelve el bloqueo:
                        // ir a importar el firmware, reintentar el motor,
                        // instalar la app externa, o ir a importar el juego.
                        // P0-1: el bloqueo por falta de licencia lleva a
                        // Importar, donde la tarjeta «Falta la licencia»
                        // ofrece elegir el work.bin (el pendiente ya se
                        // reconstruyó antes del diálogo).
                        val action = when {
                            outcome.reason.contains("Falta el firmware") -> AlertAction.GO_IMPORT
                            outcome.reason.contains("Falta la licencia (work.bin)") -> AlertAction.GO_IMPORT
                            outcome.reason.contains("motor integrado") -> AlertAction.RETRY_LAUNCH
                            outcome.reason.contains("app externa no está instalada") -> AlertAction.INSTALL_VITA3K
                            outcome.reason.contains("El juego no está instalado") -> AlertAction.GO_IMPORT
                            else -> null
                        }
                        _alert.value = AlertInfo(outcome.reason, action)
                    }
                }
            }
            refreshStatus()
            } finally {
                endLaunch()
            }
        }
    }

    /**
     * P1-5(e): resuelve el conflicto de dos instalaciones (ux0 antiguo
     * + vita/ux0 con juegos distintos): conserva la del motor integrado
     * y borra la antigua. Antes se fusiona el contenido NO-juego del
     * antiguo que falte en el destino (DLCs, updates, saves, .pkg de
     * DLC/update y licencias, vía la fusión ya implementada en
     * EmbeddedCoreLayout), para no perderlo. El juego antiguo en sí
     * se pierde: el diálogo de JUGAR lo dice antes de pulsar el botón.
     * Guardas: si no hay instalación del motor integrado, NO se borra
     * nada (la antigua sería la única copia); si la fusión o el
     * borrado fallan, tampoco se borra nada y se avisa. Tras resolver,
     * se reintenta JUGAR solo. No toca EmbeddedCoreLayout: usa solo lo
     * que ya existe (legacyUx0Dir, embeddedUx0Dir,
     * mergeMissingNonGameContent).
     */
    fun resolveLegacyConflictKeepEmbedded() {
        viewModelScope.launch(Dispatchers.IO) {
            val legacy = EmbeddedCoreLayout.legacyUx0Dir(library.vitaRoot)
            val dest = EmbeddedCoreLayout.embeddedUx0Dir(library.vitaRoot)
            if (!legacy.isDirectory) {
                withContext(Dispatchers.Main) { _message.value = "No hay instalación antigua que borrar." }
                return@launch
            }
            if (!dest.isDirectory) {
                withContext(Dispatchers.Main) {
                    _message.value = "No hay instalación del motor integrado: no se borra la antigua, es la única copia del juego."
                }
                return@launch
            }
            if (!EmbeddedCoreLayout.mergeMissingNonGameContent(legacy, dest)) {
                withContext(Dispatchers.Main) {
                    _message.value = "No se pudo mover el contenido antiguo (DLCs, saves) al motor integrado: no se borró nada."
                }
                return@launch
            }
            val deleted = runCatching { legacy.deleteRecursively() }.getOrDefault(false) && !legacy.exists()
            if (!deleted) {
                withContext(Dispatchers.Main) {
                    _message.value = "No se pudo borrar la instalación antigua: no se borró nada. Repórtalo."
                }
                return@launch
            }
            LauncherLog.append(context, "launch", "Conflicto de instalaciones resuelto: ux0 antiguo borrado, se conserva vita/ux0.")
            refreshStatus()
            withContext(Dispatchers.Main) {
                _message.value = "Instalación antigua borrada (DLCs, updates y saves se movieron antes al motor integrado). Se reintenta JUGAR."
            }
            play()
        }
    }

    /**
     * Al volver del juego (MainActivity.onResume): cierra la medición
     * de tiempo de la sesión activa y guarda el snapshot de lo jugado.
     * No hace nada si no hay una partida en curso registrada. No
     * refresca el estado: onResume ya llamó a refreshStatus() justo
     * antes, y el segundo escaneo completo del juego (~1,14 GB) en
     * cada vuelta era trabajo duplicado.
     *
     * P2-A7 (vía externa): NO se cierra la medición aunque la app
     * externa siga en curso: play() y startBenchmark() ya pasan
     * external=true a notePlayStarted, y SessionManager.notePlayEnded
     * hace checkpoint (tiempo + snapshot) y la medición SIGUE en ese
     * caso; solo la vía embebida se cierra aquí. Una comprobación
     * best-effort con ActivityManager de si org.vita3k.emulator sigue
     * en primer plano NO es viable y por eso no se hace:
     * getRunningAppProcesses() solo devuelve el propio proceso desde
     * Android 5.0, la única alternativa (UsageStatsManager) exige el
     * permiso especial de estadísticas de uso, y en el momento en que
     * se llama aquí (MainActivity.onResume) el launcher mismo es la
     * app en primer plano por definición. Ver el comentario de
     * MainActivity.onResume, que documenta lo mismo.
     */
    fun noteReturnedFromGame() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.notePlayEnded()
            // Prueba de 30 s: si el juego midió el FPS automáticamente
            // (EmbeddedGameActivity muestrea getFps cada segundo y lo
            // guarda al salir), se registra solo; si no hay medición,
            // queda el registro manual desde Diagnóstico.
            val pending = _pendingBenchmark.value ?: return@launch
            // P2-A2: un pendiente de hace más de 15 min está caducado:
            // se descarta sin registrar nada (el FPS automático que
            // hubiera se consume y se tira, para que no lo herede una
            // prueba posterior).
            if (isBenchmarkPendingStale(pending.startedAtMillis)) {
                _pendingBenchmark.compareAndSet(pending, null)
                settingsRepo.takeAutoBenchmarkFps()
                LauncherLog.append(context, "benchmark", "Prueba pendiente caducada (>15 min): descartada sin registrar.")
                return@launch
            }
            val autoFps = settingsRepo.takeAutoBenchmarkFps() ?: return@launch
            if (!autoFps.isFinite() || autoFps < 1f || autoFps > 240f) return@launch
            completeBenchmark(autoFps, BenchmarkSource.AUTO_MEASURED, pending)
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

    /** Prueba de 30 s: lanza el juego con el preset actual; el núcleo mide el FPS solo mientras juegas y al volver se registra automáticamente. Si no hay medición (app externa), queda pendiente registrar el FPS visto en el overlay. */
    fun startBenchmark() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!tryBeginLaunch()) return@launch
            try {
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
                note = "Prueba de 30 s con preset ${settings.activeProfile.label}. El FPS se mide solo mientras juegas; si no hay medición al volver, anota el FPS del overlay.",
            )
            _pendingBenchmark.value = run
            // config.yml antes del firmware, igual que en play(): la
            // instalación del firmware inicializa el núcleo.
            runCatching {
                Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu)
            }
            // Firmware: igual que en play(), instalar el PUP guardado
            // en el núcleo si está presente (best-effort, no bloquea).
            ensureFirmwareForEmbedded()
            val outcome = launcher.launch(library, settings, sessionManager, gpu, benchmark = true)
            // P0-1 / P1-12: igual que en play(): si el resultado es un
            // bloqueo o fallo por licencia, el pendiente queda listo
            // para que Importar ofrezca "Elegir work.bin".
            prepareLicensePendingForOutcome(outcome)
            // P2-A7: igual que en play(): la vía externa marca
            // external=true (checkpoint en vez de cerrar la medición).
            val launchedExternal = outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternal ||
                outcome is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning
            if (launchedExternal || outcome is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded) {
                sessionManager.notePlayStarted(external = launchedExternal)
            }
            withContext(Dispatchers.Main) {
                _message.value = when (outcome) {
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternal ->
                        "Prueba de 30 s en marcha. Juega 30 segundos, mira el FPS del overlay y al volver regístralo aquí."
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning ->
                        "Prueba de 30 s en marcha. ${Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL}"
                    is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded ->
                        "Prueba de 30 s en marcha (núcleo embebido). El FPS se mide solo mientras juegas; al volver se registra automáticamente." +
                            if (gpu?.vulkanSupported == false) " Sin Vulkan en este teléfono: se usa OpenGL, puede ir más lento." else ""
                    is Vita3KLauncher.LaunchOutcome.Blocked -> {
                        _pendingBenchmark.value = null
                        outcome.reason
                    }
                    is Vita3KLauncher.LaunchOutcome.EmbeddedFailed -> {
                        _pendingBenchmark.value = null
                        outcome.reason
                    }
                    is Vita3KLauncher.LaunchOutcome.EmbeddedCorePresent -> {
                        // M32: la prueba nunca empezó (falta el JNI); sin
                        // limpiar el pendiente, recordBenchmarkFps registraría
                        // un FPS de una partida que no ocurrió.
                        _pendingBenchmark.value = null
                        "Núcleo embebido detectado, falta el punto de entrada JNI."
                    }
                }
            }
            } finally {
                endLaunch()
            }
        }
    }

    /**
     * Registra el FPS medio visto en el overlay. Si es el preset G99
     * Calidad 2x y baja de 28 fps, vuelve a 1x automáticamente (si el
     * ajuste está activado). La medición manual es el respaldo cuando
     * el juego no midió el FPS solo (p. ej. jugaste en la app
     * externa): con el núcleo embebido, la prueba se registra sola
     * al volver (ver noteReturnedFromGame).
     */
    fun recordBenchmarkFps(fps: Float) {
        val pending = _pendingBenchmark.value ?: return
        // P2-A2: un pendiente de hace más de 15 min está caducado: se
        // descarta y se avisa, en vez de registrar el FPS contra aquel
        // perfil/run viejo.
        if (isBenchmarkPendingStale(pending.startedAtMillis)) {
            _pendingBenchmark.compareAndSet(pending, null)
            _message.value = "La prueba de 30 s caducó (más de 15 min sin registrarse): vuelve a lanzarla para medir de nuevo."
            return
        }
        // M25: un FPS inválido (negativo, NaN, absurdo) no se guarda ni se
        // usa para decidir el auto-revert; el pendiente se conserva para
        // poder reintentarlo.
        if (!fps.isFinite() || fps < 1f || fps > 240f) {
            _message.value = "FPS no válido: $fps. Anota el FPS del overlay (entre 1 y 240)."
            return
        }
        viewModelScope.launch { completeBenchmark(fps, BenchmarkSource.MANUAL_OVERLAY, pending) }
    }

    /**
     * Cierra una prueba de 30 s (manual o medida por el núcleo):
     * guarda el historial, limpia el pendiente y aplica el
     * auto-revert a 1x si toca. `source` distingue cómo se midió.
     *
     * P2-A2: el consumo del pendiente es ATÓMICO con compareAndSet
     * sobre el StateFlow: la vía automática (noteReturnedFromGame, al
     * volver) y la manual (recordBenchmarkFps) pueden correr a la
     * vez, y solo la primera registra la prueba; la segunda ve que el
     * pendiente ya no es el suyo y no hace nada. Sin Mutex: el
     * StateFlow ya es concurrente y el compareAndSet es la primitiva
     * adecuada para "reclamar" un valor de un solo uso; el resto del
     * trabajo (guardar historial, auto-revert) ya no depende del
     * pendiente, que viaja capturado en el parámetro.
     */
    private suspend fun completeBenchmark(fps: Float, source: BenchmarkSource, pending: BenchmarkRun) {
        if (!_pendingBenchmark.compareAndSet(pending, null)) return
        // Defensa: si el pendiente caducó entre la lectura y el
        // reclamo, no se registra nada (ya está consumido/limpiado).
        if (isBenchmarkPendingStale(pending.startedAtMillis)) {
            LauncherLog.append(context, "benchmark", "Prueba caducada (>15 min) al completarla: descartada sin registrar.")
            return
        }
        val settings = settingsRepo.settingsFlow.first()
        // M25: el auto-revert se decide con el perfil ACTUAL, no con el
        // de cuando empezó la prueba (el usuario pudo cambiarlo).
        val revert = shouldAutoRevertTo1x(fps, settings.activeProfile, settings.autoRevertTo1xIfBelow28Fps)
        val completed = pending.copy(fpsAvg = fps, source = source, autoReverted = revert)
        settingsRepo.recordBenchmark(completed)
        val how = if (source == BenchmarkSource.AUTO_MEASURED) "medido automáticamente por el juego" else "registrado a mano"
        if (revert) {
            settingsRepo.applyPreset(GpuProfileId.G99_RECOMMENDED, current = settings)
            _message.value = "FPS medio: $fps ($how). Menos de 28 fps con Calidad 2x: vuelto a 1x automáticamente (preset G99 Recomendado)."
        } else {
            _message.value = "Prueba registrada: $fps fps medios ($how) con ${pending.profile.label}."
        }
        LauncherLog.append(context, "benchmark", "Prueba ${pending.profile.name}: $fps fps ($source), autoRevert=$revert")
    }

    fun dismissBenchmark() { _pendingBenchmark.value = null }

    // --- Logros ---

    fun resetTrophies() {
        viewModelScope.launch {
            achievementsRepo.resetAll()
            clearJustUnlocked()
            // R2-P2-30: el contador unlockedTrophies del índice de
            // sesiones se actualiza ya (antes quedaba viejo hasta el
            // próximo snapshot).
            sessionManager.refreshActiveTrophyCount()
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

    /**
     * Lee un archivo de texto elegido por el usuario con tope
     * (auditoría RAM ≤ 4 GB, 2026-11-07): readText() sin límite sobre
     * un URI elegido a mano cargaría el archivo entero en RAM si el
     * usuario eligiera por error un archivo grande. Estos archivos
     * (progreso de logros, perfil de control) pesan KBs; 64 KB sobran.
     */
    private fun readTextCapped(uri: Uri, maxChars: Int = 64 * 1024): String {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val buf = CharArray(8192)
            val sb = StringBuilder()
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (sb.length + n > maxChars) {
                    throw IllegalArgumentException(
                        "El archivo es demasiado grande (más de ${maxChars / 1024} KB). Elige el archivo exportado correcto.",
                    )
                }
                sb.append(buf, 0, n)
            }
            return sb.toString()
        } ?: return ""
    }

    fun importAchievements(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = readTextCapped(uri)
                val result = achievementsRepo.importText(text)
                // R2-P2-30: actualizar el contador unlockedTrophies del
                // índice de sesiones.
                sessionManager.refreshActiveTrophyCount()
                // R2-P2-34: el total real tras importar se lee de
                // progressFlow (no de addedOrKept, que es solo el nº de
                // entradas válidas del archivo).
                val progress = achievementsRepo.progressFlow.first()
                val total = progress.count { it.unlocked }
                withContext(Dispatchers.Main) {
                    _message.value = "Logros importados: ahora tienes $total/${progress.size} " +
                        "(${result.newlyUnlocked} nuevos, ${result.ignoredInvalid} inválidas ignoradas)."
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
            // B6: el borrado es atómico en el repositorio (leer +
            // filtrar + guardar bajo el mismo mutex); antes se hacía
            // aquí en tres pasos y dos borrados rápidos podían
            // pisarse entre sí.
            val deleted = touchRepo.deleteControl(controlId)
            if (!deleted) return@launch
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
                // B6: el id llevaba solo currentTimeMillis(); dos toques
                // en el mismo milisegundo daban el mismo id y borrar o
                // editar uno afectaba a los dos. Se añade nanoTime,
                // igual que los ids de perfil.
                id = "btn_custom_$n-${System.currentTimeMillis()}-${System.nanoTime() % 100000}",
                label = "B$n",
                kind = "button",
                vitaButton = com.danielsnagustin.childoflight.launcher.data.VitaButton.CROSS,
                xFraction = 0.5f, yFraction = 0.5f, sizeDp = 64, opacity = 0.8f,
            )
            touchRepo.addControl(control)
            _message.value = "Botón nuevo añadido en el centro. Muévelo y remapealo."
        }
    }

    /**
     * P1-13 (ronda 3): repone el stick o la zona Igniculus en el perfil
     * activo si faltan, con sus valores por defecto. Antes, borrar el
     * stick o la zona era irreversible salvo restablecer todo el layout
     * y perder los perfiles y posiciones: addTouchControl() solo creaba
     * botones. Ahora la recuperación es un toque: el editor muestra el
     * botón solo cuando falta ese kind.
     */
    fun restoreMissingTouchControl(kind: String) {
        viewModelScope.launch {
            val layout = touchRepo.layoutFlow.first()
            if (layout.any { it.kind == kind }) {
                _message.value = "Ya existe en este perfil."
                return@launch
            }
            val defaults = com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout()
            val control = defaults.firstOrNull { it.kind == kind }
            if (control == null) {
                _message.value = "No hay un control por defecto para ese tipo."
                return@launch
            }
            touchRepo.addControl(control)
            val what = if (kind == "stick") "Stick" else "Zona Igniculus"
            _message.value = "$what añadido con sus valores por defecto. Muévelo y ajústalo."
            LauncherLog.append(context, "touch", "$what repuesto en el perfil activo (P1-13)")
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
                val text = readTextCapped(uri)
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

    // --- Restauración por categoría (Ola 3, Etapa 9b) ---
    // Restablece SOLO esa categoría (ver CategoryReset.kt). Almacenamiento
    // no tiene ajustes: son archivos, y el mensaje lo dice en vez de
    // fingir que se restableció algo. El firmware ya no es una categoría
    // de Ajustes: vive en Importar (juego y DLCs).
    fun resetCategory(category: SettingsCategory) {
        viewModelScope.launch {
            if (!categoryHasResettableSettings(category)) {
                _message.value = "Almacenamiento no tiene ajustes que restablecer: son archivos (copias en Descargas, juego, DLCs), no ajustes."
                return@launch
            }
            val current = settingsRepo.settingsFlow.first()
            val currentUi = uiRepo.uiFlow.first()
            // Modo seguro: si está ON, restablecer Diagnóstico sin restaurar
            // la copia previa perdería los ajustes anteriores al Modo seguro
            // (quedarían los valores forzados 1x/SDL como permanentes y la
            // copia huérfana). Se restaura primero (por campo, igual que al
            // apagar el Modo seguro: R2-P1-08/09) y el reseteo se aplica
            // encima de los ajustes restaurados.
            val base = if (category == SettingsCategory.DIAGNOSTICS && current.safeMode) {
                val snapshot = readSettingsSnapshot(safeModeSnapshotFile())
                deleteSettingsSnapshot(safeModeSnapshotFile())
                snapshot?.let { restoreSafeModeSnapshot(it, current) } ?: current
            } else {
                current
            }
            val reset = resetCategorySettings(base, category)
            // P1-8: en Mali (o sin Turnip instalado) el reseteo de GPU
            // con el perfil "Calidad / Turnip" activo podía dejar
            // gpuDriver=TURNIP sin forma de revertirlo desde la UI
            // (Mali no muestra los chips de driver). La guarda vuelve
            // al driver del sistema; también repara un TURNIP huérfano
            // previo aunque se restablezca otra categoría.
            val gpu = settingsRepo.gpuInfoFlow.first()
            val guarded = guardTurnipDriver(reset, gpu?.vendor)
            settingsRepo.save(guarded)
            uiRepo.save(resetCategoryUiPrefs(currentUi, category))
            val turnipNote = if (guarded.gpuDriver != reset.gpuDriver) {
                " Turnip no está disponible en este teléfono: se usa el driver del sistema."
            } else {
                ""
            }
            _message.value = if (category == SettingsCategory.PERSONALIZATION) {
                "Personalización restablecida a los valores por defecto."
            } else {
                "Categoría ${category.title} restablecida a los valores del preset ${current.activeProfile.label}.$turnipNote"
            }
        }
    }

    // --- Copia de seguridad de ajustes (Ola 3, Etapa 9a) ---
    // JSON por SAF (ver SettingsBackup.kt). Al importar se aplican los
    // mismos clamps que al guardar; un archivo inválido no aplica nada.

    fun exportSettingsBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = settingsRepo.settingsFlow.first()
                val currentUi = uiRepo.uiFlow.first()
                val text = encodeSettingsBackup(current, currentUi)
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                withContext(Dispatchers.Main) { _message.value = "Copia de seguridad exportada." }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo exportar la copia: ${e.message}" }
            }
        }
    }

    fun importSettingsBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // R2-P2-22: lectura HASTA EOF (readTextCapped lee en bucle
            // hasta agotar el stream), acotada a 256 KB: antes se hacía
            // un solo read() de 256 KB y un backup mayor quedaba
            // truncado en silencio. Si el archivo supera el tope,
            // readTextCapped lanza y no se aplica nada.
            val text = try {
                readTextCapped(uri, 262_144)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo leer el archivo de copia de seguridad: ${e.message}" }
                return@launch
            }
            if (text.isBlank()) {
                withContext(Dispatchers.Main) { _message.value = "No se pudo leer el archivo de copia de seguridad." }
                return@launch
            }
            val current = settingsRepo.settingsFlow.first()
            val currentUi = uiRepo.uiFlow.first()
            val decoded = decodeSettingsBackup(text, current, currentUi)
            if (decoded.isFailure) {
                withContext(Dispatchers.Main) {
                    _message.value = decoded.exceptionOrNull()?.message ?: "Archivo de copia de seguridad inválido. No se aplicó nada."
                }
                return@launch
            }
            val (settings, ui) = decoded.getOrThrow()
            // P1-8: una copia de otro teléfono (o de un estado huérfano)
            // puede traer gpuDriver=TURNIP; en este teléfono no tendría
            // forma de revertirse desde la UI.
            val gpu = settingsRepo.gpuInfoFlow.first()
            val guarded = guardTurnipDriver(settings, gpu?.vendor)
            settingsRepo.save(guarded)
            uiRepo.save(ui)
            withContext(Dispatchers.Main) {
                _message.value = "Copia de seguridad importada: ajustes y personalización restaurados." +
                    if (guarded.gpuDriver != settings.gpuDriver) {
                        " Turnip no está disponible en este teléfono: se usa el driver del sistema."
                    } else {
                        ""
                    }
            }
        }
    }

    // --- Borrar no usados ---

    fun deleteDlc(dlcId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Título fresco de disco, no el estado de la UI (que puede ir
            // desactualizado si cambió el juego): con el estado obsoleto
            // el borrado no hacía nada en silencio, sin avisar al usuario.
            val title = freshTitleId()
            if (title == null) {
                withContext(Dispatchers.Main) { _message.value = "No hay juego instalado: no hay DLC que borrar." }
                return@launch
            }
            // P1-5: el resultado dice qué se borró de verdad (.pkg
            // guardado, contenido instalado, o ambos, en cualquiera de
            // los dos ux0) y si algo falló. Antes el mensaje decía
            // "borrado" aunque la carpeta extraída siguiera ahí.
            val result = library.deleteDlc(title, dlcId)
            withContext(Dispatchers.Main) {
                _message.value = when {
                    result.failed ->
                        "No se pudo borrar del todo el DLC $dlcId. Revisa el reporte de errores para el motivo exacto."
                    result.ok -> {
                        val parts = listOfNotNull(
                            "su .pkg guardado".takeIf { result.deletedStoredPkg },
                            "su contenido instalado".takeIf { result.deletedExtracted },
                        )
                        "DLC $dlcId borrado: se eliminó ${parts.joinToString(" y ")}."
                    }
                    else -> "No se encontró el DLC $dlcId: no había nada que borrar."
                }
            }
            refreshStatus()
        }
    }

    fun deleteTurnipDriver() {
        viewModelScope.launch(Dispatchers.IO) {
            val hadFile = turnipRepo.deleteDriver()
            val current = settingsRepo.settingsFlow.first()
            // Borra también el driver instalado dentro del núcleo, si lo
            // había: si no, custom-driver-name quedaría apuntando a un
            // driver que ya no existe (el núcleo caería al driver del
            // sistema, pero el registro huérfano quedaría en el núcleo).
            if (current.customDriverName.isNotBlank() && Vita3kCoreBridge.available) {
                val removed = Vita3kCoreBridge.removeCustomDriver(current.customDriverName)
                LauncherLog.append(context, "driver", "Driver Turnip borrado del núcleo (${current.customDriverName}): $removed")
            }
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
                        "El juego está como .pkg: los saves viven dentro de la app externa, no en el launcher."
                    } else {
                        "No hay juego instalado: no hay saves que borrar."
                    }
                }
                return@launch
            }
            val result = library.deleteSavesOnly(title)
            // H6: la copia guardada de la sesión activa solo se borra si
            // realmente había saves vivos que borrar. Antes se borraba
            // siempre, incluso cuando el mensaje decía "No había saves".
            if (result.deleted && !result.failed) sessionManager.clearActiveSessionSavedata()
            withContext(Dispatchers.Main) {
                _message.value = when {
                    result.failed -> "No se pudieron borrar los saves. Revisa el reporte de errores para el motivo exacto."
                    result.deleted -> "Saves borrados (también de la sesión activa). El juego y los DLCs siguen instalados."
                    else -> "No había saves que borrar."
                }
            }
            refreshStatus()
        }
    }

    fun hideGpuPreset(profile: GpuProfileId) {
        hideGpuPresets(setOf(profile))
    }

    /**
     * P1-17: oculta varios presets en una sola escritura atómica (ver
     * UiPreferencesRepository.hideGpuPresets). El lote de limpieza lo
     * usa para que no se pierda ninguno; el preset activo nunca se
     * oculta.
     */
    fun hideGpuPresets(profiles: Set<GpuProfileId>) {
        viewModelScope.launch {
            val current = settingsRepo.settingsFlow.first()
            val toHide = profiles.filter { it != current.activeProfile }
            if (toHide.isEmpty()) {
                _message.value = "No se puede ocultar el preset activo. Cambia primero a otro preset."
                return@launch
            }
            uiRepo.hideGpuPresets(toHide.toSet())
            val skipped = profiles.size - toHide.size
            _message.value = if (skipped > 0) {
                "Presets ocultos: ${toHide.joinToString(", ") { it.label }}. El activo no se puede ocultar."
            } else if (toHide.size == 1) {
                "Preset ${toHide.single().label} oculto de la lista."
            } else {
                "${toHide.size} presets ocultos de la lista."
            }
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
                        "El juego está como .pkg: los saves viven dentro de la app externa, no en el launcher."
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
                        "El juego está como .pkg: los saves viven dentro de la app externa, no en el launcher."
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
                    // R2-P2-31: importSaves REEMPLAZA (el agente de datos
                    // borra la viva antes de copiar; la sesión ya está
                    // respaldada): el mensaje lo dice, no es una fusión.
                    onSuccess = { "Saves importados: $it archivos. Los saves actuales fueron reemplazados por los importados." },
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

    fun refreshBatteryOptimizationState() {
        viewModelScope.launch {
            // Se lee el estado REAL (PowerManager) al volver del diálogo
            // del sistema, lo conceda el usuario o no. No se persiste
            // nada: el campo guardado no lo leía nadie y el valor vivo
            // es el del sistema (ronda de verificación 2026-11-08).
            _batteryOptDisabled.value =
                runCatching { launcher.isBatteryOptimizationDisabled() }.getOrDefault(false)
        }
    }

    fun buildBugReportText(settings: LauncherSettings, gpu: GpuInfo?): String {
        val status = _status.value
        // Ola 3, Etapa 10: el log se lee una vez y se reutiliza para el
        // log completo y para los errores recientes.
        val recentLog = LauncherLog.readLast(context, 60)
        val raw = buildString {
            appendLine("=== Reporte de bug — Child of Light Launcher ===")
            appendLine("Fecha: ${java.util.Date()}")
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0.2.4"
            appendLine("App: Child of Light Launcher v$versionName")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            // Modelo: solo en el informe de diagnóstico y en Ajustes →
            // Diagnóstico (ver SettingsDiagnosticsScreen).
            appendLine("Modelo: ${Build.MODEL}")
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
            appendLine("Entregado a la app externa: ${settings.pkgDeliveredToVita3k} | Instalado en la app externa (confirmado por el usuario): ${settings.pkgInstalledInVita3k}")
            appendLine("DLCs: ${status.dlcCount} | Update: ${status.updateInstalled} | Firmware: ${status.firmwareInstalled}")
            appendLine("Saves: ${status.saves.size} archivos")
            appendLine()
            appendLine("--- Núcleo ---")
            appendLine("Núcleo embebido: ${if (embeddedCorePresent()) "presente" else "NO presente"}")
            appendLine("App externa: ${externalVita3kVersion() ?: "no instalada"}")
            appendLine()
            appendLine("--- Ajustes activos ---")
            appendLine("Perfil: ${settings.activeProfile.label}")
            appendLine("Backend: ${settings.backend} | Resolución: ${settings.resolutionLabel} | Filtro: ${settings.screenFilter}")
            appendLine("Aniso: ${settings.anisotropicFiltering}x | VSync: ${settings.vSync} | Async: ${settings.asyncPipelineCompilation}")
            appendLine("High Accuracy: ${settings.highAccuracy} | Surface Sync: ${settings.surfaceSyncEnabled} | Memory Mapping: ${settings.memoryMapping}")
            appendLine("Audio: ${settings.audioBackend} | Volumen: ${settings.audioVolume} | NGS: ${settings.ngsEnable}")
            appendLine("CPU opt (JIT): ${settings.cpuOpt} | FPS hack: ${settings.fpsHack}")
            appendLine("Modo seguro: ${settings.safeMode}")
            appendLine("Driver: ${settings.gpuDriver} | Turnip: ${settings.turnipVersion} (instalado: ${settings.turnipInstalled})")
            appendLine()
            appendLine("--- config.yml generado ---")
            // P1-14: igual que en refreshConfigPreview: generate()
            // espera vitaFsPath, no vitaRoot (el pref-path del reporte
            // mostraba el valor ANTIGUO del bug -3).
            appendLine(
                Vita3kConfigGenerator.generate(
                    settings,
                    Vita3kConfigGenerator.vitaFsPathFor(library.vitaRoot).absolutePath,
                    gpu,
                ),
            )
            appendLine("--- Errores recientes del log ---")
            val recentErrors = extractRecentErrorLines(recentLog)
            if (recentErrors.isEmpty()) {
                appendLine("Sin errores recientes.")
            } else {
                recentErrors.forEach { appendLine(it) }
            }
            appendLine("--- Log del launcher (últimas líneas) ---")
            appendLine(recentLog)
            // Fase 2 (2026-11-09): la cola del log NATIVO del núcleo.
            // El log del launcher no distingue si el juego sigue
            // cargando (H1) o si el núcleo no presenta nada (H3); el
            // log nativo sí ("Game started", errores del renderer,
            // avisos de superficie/swapchain). El archivo vive en
            // <filesDir>/vita3k/vita3k.log, junto a launcher.log; el
            // núcleo lo escribe siempre (pin util/src/logging.cpp).
            // El .replace() de privacidad del final también cubre
            // esta sección (las rutas del directorio privado).
            appendLine("--- Log del núcleo (vita3k.log, últimas líneas) ---")
            appendLine(LauncherLog.readNativeLogTail(File(library.vitaRoot, "vita3k.log")))
        }
        // Privacidad: el nombre del paquete no sale en el reporte
        // (ni en la cabecera ni en las rutas del log/config): se
        // sustituye por un nombre neutro; las rutas siguen siendo
        // legibles para depurar.
        return raw.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher")
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
