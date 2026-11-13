package com.danielsnagustin.childoflight.launcher.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.danielsnagustin.childoflight.launcher.R
import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import com.danielsnagustin.childoflight.launcher.core.EmbeddedPkgInstaller
import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.core.OverlayInputStats
import com.danielsnagustin.childoflight.launcher.core.FrameProfileStats
import com.danielsnagustin.childoflight.launcher.core.TouchStats
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
import com.danielsnagustin.childoflight.launcher.data.mapNativeTrophies
import com.danielsnagustin.childoflight.launcher.data.SessionManager
import com.danielsnagustin.childoflight.launcher.data.SettingsRepository
import com.danielsnagustin.childoflight.launcher.data.TouchLayoutRepository
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreReader
import com.danielsnagustin.childoflight.launcher.data.TrophyStoreScan
import com.danielsnagustin.childoflight.launcher.data.TrophySyncPending
import com.danielsnagustin.childoflight.launcher.data.TrophyTrpReader
import com.danielsnagustin.childoflight.launcher.data.TrpReadResult
import org.vita3k.emulator.data.NativeTrophyCollection
import com.danielsnagustin.childoflight.launcher.data.TurnipDriverRepository
import com.danielsnagustin.childoflight.launcher.data.UiPreferencesRepository
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.data.RawgCache
import com.danielsnagustin.childoflight.launcher.online.RawgClient
import com.danielsnagustin.childoflight.launcher.online.RawgGameDetails
import java.net.HttpURLConnection
import java.net.URL
import com.danielsnagustin.childoflight.launcher.data.backupJsonHasSafeModeEnabled
import com.danielsnagustin.childoflight.launcher.data.decodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.data.encodeSettingsBackup
import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.BenchmarkRun
import com.danielsnagustin.childoflight.launcher.model.BenchmarkSource
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuProfileId
import com.danielsnagustin.childoflight.launcher.model.GpuVendor
import com.danielsnagustin.childoflight.launcher.model.ImportResult
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.TrophyProgress
import com.danielsnagustin.childoflight.launcher.model.TrophyPopupQueue
import com.danielsnagustin.childoflight.launcher.model.TrophyUnlockAnnouncer
import com.danielsnagustin.childoflight.launcher.model.TitleId
import com.danielsnagustin.childoflight.launcher.model.UiPreferences
import com.danielsnagustin.childoflight.launcher.model.applySafeMode
import com.danielsnagustin.childoflight.launcher.ui.settings.audioFixTarget
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.categoryHasResettableSettings
import com.danielsnagustin.childoflight.launcher.ui.settings.extractCpuLogLines
import com.danielsnagustin.childoflight.launcher.ui.settings.extractRecentErrorLines
import com.danielsnagustin.childoflight.launcher.ui.settings.guardTurnipDriver
import com.danielsnagustin.childoflight.launcher.ui.settings.resetCategorySettings
import com.danielsnagustin.childoflight.launcher.ui.settings.resetCategoryUiPrefs
import com.danielsnagustin.childoflight.launcher.ui.settings.turnipKeptNote
import com.danielsnagustin.childoflight.launcher.model.defaultProfileFor
import com.danielsnagustin.childoflight.launcher.model.effectiveSettings
import com.danielsnagustin.childoflight.launcher.model.presetSettings
import com.danielsnagustin.childoflight.launcher.model.resolveBackend
import com.danielsnagustin.childoflight.launcher.model.shouldAutoRevertTo1x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.danielsnagustin.childoflight.launcher.data.TouchControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Acción que puede ofrecer el diálogo de aviso importante. */
enum class AlertAction { GO_IMPORT, DELIVER_PKG_TO_VITA3K, CONFIRM_PKG_INSTALLED, REPLACE_DUPLICATE, CONFIRM_DOWNLOADS_COPY, RETRY_LAUNCH, RESOLVE_LEGACY_CONFLICT, DELETE_BLOCKED_PKG }

/** Caducidad de una prueba de 30 s pendiente (P2-A2): pasado este tiempo sin registrarse, se descarta. */
internal const val BENCHMARK_PENDING_MAX_AGE_MILLIS = 15L * 60 * 1000

/**
 * R4-P1-9: ventana de recencia de refreshStatus(): si el último
 * escaneo COMPLETADO terminó hace menos de esto, otro refresco no
 * aporta nada nuevo y se salta. Al volver de una partida larga el
 * último escaneo es viejo y el refresco ocurre igual.
 */
internal const val REFRESH_RECENCY_MILLIS = 2000L

/**
 * R4-P1-12: cuánto dura la caché de versión externa y batería en
 * refreshStatusOnce(): 5 minutos. Las lecturas explícitas (pantalla
 * de Diagnóstico, reporte de bug, diálogo de batería) siguen leyendo
 * en vivo y refrescan la caché.
 */
internal const val EXTERNAL_INFO_CACHE_TTL_MILLIS = 5L * 60 * 1000

/**
 * Pura (JVM): true si un refresco puede saltarse porque otro escaneo
 * acaba de terminar. No se salta nunca si hay un refresco pendiente:
 * ese pendiente se marcó mientras corría un escaneo anterior y puede
 * ser la única señal de que el disco cambió durante ese escaneo (por
 * ejemplo, una importación que terminó a mitad del escaneo). Tampoco
 * se salta si nunca se completó un escaneo (0) ni si el reloj va hacia
 * atrás.
 */
internal fun shouldSkipRecentRefresh(
    lastCompletedAtMillis: Long,
    nowMillis: Long,
    refreshPending: Boolean,
): Boolean =
    !refreshPending && lastCompletedAtMillis > 0L &&
        nowMillis >= lastCompletedAtMillis &&
        nowMillis - lastCompletedAtMillis < REFRESH_RECENCY_MILLIS

/**
 * Pura (JVM): Title IDs cuyos tamaños instalados debe olvidar
 * refreshStorageState() antes de re-escanear. El estado solo muestra
 * un título (el extraído si lo hay, si no el del .pkg), pero el .pkg
 * guardado puede ser de la otra región que el extraído (M15: dos
 * regiones instaladas) y sus directorios también pueden tener
 * tamaños cacheados obsoletos; devolver los tres sin duplicar cubre
 * ambos casos sin coste.
 */
internal fun storageInvalidationTitleIds(status: GameStatus): List<String> =
    listOfNotNull(status.titleId?.id, status.extractedTitleId?.id, status.pkgTitleId?.id).distinct()

/**
 * Pura (JVM): true si una lectura cacheada de versión externa o
 * batería sigue fresca. 0 (nunca leída) nunca es fresca.
 */
internal fun isExternalInfoCacheFresh(cachedAtMillis: Long, nowMillis: Long): Boolean =
    cachedAtMillis > 0L && nowMillis >= cachedAtMillis &&
        nowMillis - cachedAtMillis < EXTERNAL_INFO_CACHE_TTL_MILLIS

/**
 * Pura (JVM): true si una prueba empezada en [startedAtMillis] ya caducó
 * a las [nowMillis]. Una prueba vieja no debe registrar un FPS medido
 * días después contra aquel perfil/run (P2-A2).
 */
internal fun isBenchmarkPendingStale(startedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
    nowMillis - startedAtMillis > BENCHMARK_PENDING_MAX_AGE_MILLIS

/**
 * Ronda 0.2.65 (RG-P2-14): destino del auto-revert a 1x según la GPU
 * real, no siempre G99: en PowerVR/Xclipse/OTHER/UNKNOWN el revert
 * volvía a un preset de Mali en un teléfono que no es Mali. Es
 * defaultProfileFor: Adreno -> ADRENO_PERFORMANCE, G99 ->
 * G99_RECOMMENDED, resto -> MALI_PERFORMANCE (perfil seguro 1x, sin
 * etiqueta de otro hardware). Pura (JVM).
 */
internal fun autoRevertTargetProfile(gpu: GpuInfo?): GpuProfileId =
    gpu?.let { defaultProfileFor(it) } ?: GpuProfileId.MALI_PERFORMANCE

/**
 * Ronda 0.2.65 (T-P2-1): líneas de tiempos de carga (T1/T2) extraídas
 * del log del launcher para su sección propia en el informe de bug.
 * T1 se registra como "T1 (JUGAR" y T2 como "T2 ≈" en las líneas
 * [launch]; antes solo vivían en la cola de 60 líneas del informe y
 * podían caerse de ella. Se devuelven las últimas 10, en orden. Pura
 * (JVM).
 */
internal fun extractLoadTimingLines(logText: String): List<String> =
    logText.lines().filter { it.contains("T1 (JUGAR") || it.contains("T2 ≈") }.takeLast(10)

/**
 * Ronda 0.2.65 (T-P2-3): sanitiza los nombres de sesión (texto libre
 * del usuario, que puede contener su nombre real) en el texto del
 * informe de bug antes de exportarlo. Los nombres aparecen entre
 * comillas en las líneas de log ("Partida terminada: +X min en
 * "nombre""); se sustituyen por "sesión". Solo se tocan las formas
 * entrecomilladas exactas, para no tocar otras palabras del informe.
 * Pura (JVM).
 */
internal fun sanitizeSessionNamesInReport(text: String, sessionNames: List<String>): String {
    var out = text
    for (name in sessionNames) {
        val clean = name.trim()
        if (clean.isEmpty()) continue
        out = out.replace("\"$clean\"", "\"sesión\"")
    }
    return out
}

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
 *
 * P2-15: de los campos que el mecanismo sobrescribe, solo se revierten
 * los que el usuario NO tocó durante el modo seguro. Los valores forzados
 * son constantes: si el valor actual difiere del forzado, el usuario lo
 * cambió mientras el modo seguro estaba activo y se conserva; si sigue
 * siendo el forzado, se restaura el valor anterior (snapshot). Sin esto,
 * el mensaje "los cambios hechos mientras estaba activado se conservan"
 * mentía para esos campos.
 *
 * R5-A2-7 (ambigüedad documentada, sin arreglo de seguimiento): la
 * igualdad con el valor forzado NO distingue "no lo toqué" de "lo puse
 * a propósito en el valor forzado". Ejemplo real: antes del modo seguro
 * vSync=true y el modo seguro fuerza vSync=false; si el usuario pone
 * vSync=false DURANTE el modo seguro (lo quiere apagado), al desactivar
 * current sigue siendo el forzado y se restaura true del snapshot: ese
 * cambio deliberado se pierde. Solo se distingue registrando qué campos
 * se editaron mientras el modo seguro estaba ON, y eso no está
 * implementado: el mensaje de desactivación lo advierte. (Si el valor
 * pre-modo-seguro ya era igual al forzado, restaurar el snapshot no
 * cambia nada observable.)
 */
internal fun restoreSafeModeSnapshot(snapshot: LauncherSettings, current: LauncherSettings): LauncherSettings {
    val forced = applySafeMode(snapshot)
    fun <T> keepIfTouched(currentValue: T, forcedValue: T, snapshotValue: T): T =
        if (currentValue != forcedValue) currentValue else snapshotValue
    return current.copy(
        safeMode = false,
        // Solo los campos que applySafeMode sobrescribe (model/LauncherSettings.kt).
        resolutionMultiplier = keepIfTouched(current.resolutionMultiplier, forced.resolutionMultiplier, snapshot.resolutionMultiplier),
        screenFilter = keepIfTouched(current.screenFilter, forced.screenFilter, snapshot.screenFilter),
        anisotropicFiltering = keepIfTouched(current.anisotropicFiltering, forced.anisotropicFiltering, snapshot.anisotropicFiltering),
        vSync = keepIfTouched(current.vSync, forced.vSync, snapshot.vSync),
        asyncPipelineCompilation = keepIfTouched(current.asyncPipelineCompilation, forced.asyncPipelineCompilation, snapshot.asyncPipelineCompilation),
        highAccuracy = keepIfTouched(current.highAccuracy, forced.highAccuracy, snapshot.highAccuracy),
        surfaceSyncEnabled = keepIfTouched(current.surfaceSyncEnabled, forced.surfaceSyncEnabled, snapshot.surfaceSyncEnabled),
        audioBackend = keepIfTouched(current.audioBackend, forced.audioBackend, snapshot.audioBackend),
        ngsEnable = keepIfTouched(current.ngsEnable, forced.ngsEnable, snapshot.ngsEnable),
        shaderCache = keepIfTouched(current.shaderCache, forced.shaderCache, snapshot.shaderCache),
        // Ronda 0.2.70 (P1-3): restaurar también la opción de CPU optimisation.
        cpuOpt = keepIfTouched(current.cpuOpt, forced.cpuOpt, snapshot.cpuOpt),
        // P2-14: el modo seguro también apaga fps-hack; al salir, vuelve
        // el valor anterior (salvo que el usuario lo encendiera durante
        // el modo seguro, que se conserva).
        fpsHack = keepIfTouched(current.fpsHack, forced.fpsHack, snapshot.fpsHack),
        // R6-P1-09: el modo seguro también neutraliza estos 3
        // campos experimentales (ver applySafeMode); al salir,
        // vuelven los valores anteriores con la misma regla.
        hashlessTextureCache = keepIfTouched(current.hashlessTextureCache, forced.hashlessTextureCache, snapshot.hashlessTextureCache),
        // Ronda 0.2.66: el modo seguro también apaga el audio SDL
        // experimental (ver applySafeMode); al salir, vuelve el valor
        // anterior con la misma regla.
        expAudioSdl = keepIfTouched(current.expAudioSdl, forced.expAudioSdl, snapshot.expAudioSdl),
        memoryMapping = keepIfTouched(current.memoryMapping, forced.memoryMapping, snapshot.memoryMapping),
        fileLoadingDelay = keepIfTouched(current.fileLoadingDelay, forced.fileLoadingDelay, snapshot.fileLoadingDelay),
        // Ronda 0.2.64 (P1-4 Adreno): el modo seguro también neutraliza
        // Turnip (gpuDriver = SYSTEM en applySafeMode); al salir, vuelve
        // el driver anterior con la misma regla. El estado instalado
        // (turnipInstalled / customDriverName) no se toca nunca aquí.
        gpuDriver = keepIfTouched(current.gpuDriver, forced.gpuDriver, snapshot.gpuDriver),
    )
}

/**
 * R2-P1-09 (misma lógica para "Corregir audio" → Deshacer): solo se
 * revierten los 4 campos que audioFixTarget sobrescribe (audioBackend,
 * ngsEnable, shaderCache, expAudioSdl); el resto — incluidos los 5 campos que el
 * snapshot no guarda — se conserva como esté ahora.
 */
/**
 * Ronda 0.2.70 (P1-2): calcula los ajustes que deben exportarse cuando
 * el modo seguro está activado o cuando existe un snapshot pendiente de
 * “Corregir audio”.  
 *
 * - **Base**: si `current.safeMode` es `true` y `safeModeSnapshot` no es
 *   `null`, la base son los valores guardados en `safeModeSnapshot`; si no,
 *   la base es `current`. (Con el modo seguro ON los valores reales del
 *   usuario viven solo en el snapshot; en DataStore están los forzados.)
 *
 * - **Audio**: si `audioFixSnapshot` no es `null`: restaurar los 4 campos
 *   de audio desde el snapshot SOLO cuando el valor del snapshot difiere
 *   del valor forzado por `audioFixTarget`. Calcula `val fixed =
 *   audioFixTarget(audioFixSnapshot)` y para cada campo usa el valor del
 *   snapshot si difiere de `fixed`, si no conserva el de la base. Campos:
 *   `audioBackend`, `ngsEnable`, `shaderCache`, `expAudioSdl`. Razón: si el snapshot de
 *   audio se tomó con el modo seguro ON, sus 4 campos ya son los forzados
 *   y restaurarlos los reintroduciría; la base (snapshot del modo seguro)
 *   ya guarda el valor real. Si “Corregir audio” se aplicó ANTES del modo
 *   seguro, el snapshot trae los valores previos al arreglo y sí se
 *   restauran bien.
 *   (Fusión 0.2.70: el 4º campo, expAudioSdl, lo añadió el área 5 a
 *   audioFixTarget; sin él, exportar con "Corregir audio" pendiente
 *   filtraba el false forzado aunque el usuario lo tuviera ON.)
 *
 * - Devolver siempre el resultado con `safeMode = false`.
 */
internal fun settingsForExport(
    current: LauncherSettings,
    safeModeSnapshot: LauncherSettings?,
    audioFixSnapshot: LauncherSettings?,
): LauncherSettings {
    // Base según modo seguro.
    val base = if (current.safeMode && safeModeSnapshot != null) safeModeSnapshot else current

    // Si hay snapshot de audio, restaurar los campos sólo cuando difieren
    // del valor forzado por audioFixTarget.
    val withAudio = if (audioFixSnapshot != null) {
        val forced = audioFixTarget(audioFixSnapshot)
        base.copy(
            audioBackend = if (audioFixSnapshot.audioBackend != forced.audioBackend) audioFixSnapshot.audioBackend else base.audioBackend,
            ngsEnable = if (audioFixSnapshot.ngsEnable != forced.ngsEnable) audioFixSnapshot.ngsEnable else base.ngsEnable,
            shaderCache = if (audioFixSnapshot.shaderCache != forced.shaderCache) audioFixSnapshot.shaderCache else base.shaderCache,
            // Fusión 0.2.70: el 4º campo de audioFixTarget (área 5): sin
            // él, exportar con "Corregir audio" pendiente filtraba el
            // false forzado aunque el usuario lo tuviera ON.
            expAudioSdl = if (audioFixSnapshot.expAudioSdl != forced.expAudioSdl) audioFixSnapshot.expAudioSdl else base.expAudioSdl,
        )
    } else {
        base
    }

    // Siempre exportar sin modo seguro.
    return withAudio.copy(safeMode = false)
}

internal fun restoreAudioFixSnapshot(snapshot: LauncherSettings, current: LauncherSettings): LauncherSettings =
    current.copy(
        audioBackend = snapshot.audioBackend,
        ngsEnable = snapshot.ngsEnable,
        shaderCache = snapshot.shaderCache,
        expAudioSdl = snapshot.expAudioSdl,
    )

/**
 * R5-A4-2 (P0, privacidad): el nombre del paquete contiene el nombre
 * del usuario y NUNCA sale en ninguna pantalla visible. Centralizado
 * aquí: refreshConfigPreview() lo aplica al generar la vista previa
 * de config.yml (antes el diálogo de General mostraba el texto sin
 * sanitizar) y el reporte de bug aplica el mismo replace al final
 * de buildBugReportText. Las pantallas de Diagnóstico y GPU
 * sanitizan su propio log visible con este mismo replace.
 * Pura (JVM): R5PresetPreservationTest.
 */
internal fun sanitizeVisiblePath(text: String): String =
    text.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher")

/**
 * R5-A2-5: true si alguno de los campos preservados al aplicar un
 * preset (las 5 cachés/overlay de preserveManualCacheChoices más el
 * detalle y la posición del HUD) difiere entre los ajustes actuales
 * y el preset canónico: en ese caso el preset NO cambia ese campo
 * (se conserva el manual) y el mensaje al aplicar el preset lo dice.
 * Pura (JVM): R5PresetPreservationTest.
 */
internal fun preservedPresetFieldsDiffer(current: LauncherSettings, preset: LauncherSettings): Boolean =
    current.textureCache != preset.textureCache ||
        current.shaderCache != preset.shaderCache ||
        // Ronda 0.2.68 (load-p1): la carga rápida también se conserva
        // como valor manual al aplicar un preset.
        current.skipShaderPrecompile != preset.skipShaderPrecompile ||
        // Tercera pasada (N-1): la máscara también se conserva como
        // valor manual al aplicar un preset (preserveManualCacheChoices);
        // si difiere del preset, el aviso "Perfil modificado" debe verlo.
        current.disableMaskBit != preset.disableMaskBit ||
        current.showFpsOverlay != preset.showFpsOverlay ||
        current.hashlessTextureCache != preset.hashlessTextureCache ||
        current.showCompileShaders != preset.showCompileShaders ||
        // Ronda 0.2.66: el audio SDL experimental también sobrevive a
        // aplicar un preset (preserveManualCacheChoices).
        current.expAudioSdl != preset.expAudioSdl ||
        current.performanceOverlayDetail != preset.performanceOverlayDetail ||
        current.performanceOverlayPosition != preset.performanceOverlayPosition

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
    return "El juego, los DLCs, las actualizaciones y la licencia viven en el almacenamiento interno del launcher (privado, sin carpeta visible).\n\n" +
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
    // Cliente y caché para la ficha RAWG del juego
    private val rawgClient = RawgClient()
    private val rawgCache = RawgCache(context.filesDir, context.cacheDir)
    private val sessionManager = SessionManager(context, library, achievementsRepo, touchRepo)

    val settingsFlow = settingsRepo.settingsFlow
    val gpuFlow = settingsRepo.gpuInfoFlow
    val benchmarksFlow = settingsRepo.benchmarksFlow

    /**
     * Máscara de métodos de mapeo de memoria soportados por el driver
     * (ronda 0.2.64, P1-3 Mali; ver Vita3kCoreBridge
     * .getSupportedMemoryMappingMask). null = aún no preguntada (o sin
     * núcleo: sin núcleo no se puede preguntar); 0 = el núcleo no la
     * devolvió (desconocida: la UI no filtra nada). La preguntan
     * GpuDiagnosticsScreen al entrar y Ajustes → GPU la lee para la
     * nota de métodos no soportados. Se refresca al cambiar de driver
     * (Turnip cambia la máscara: se refresca en useSystemDriver e
     * installTurnip tras aplicar).
     */
    private val _supportedMappingMask = MutableStateFlow<Int?>(null)
    val supportedMappingMask: StateFlow<Int?> = _supportedMappingMask.asStateFlow()

    /**
     * true cuando se pidió Turnip pero el núcleo NO lo cargó (ronda
     * 0.2.65, C-P2-5 / R-P1-12): la máscara preguntada sería la del
     * driver del sistema y la UI debe decirlo ("Turnip pedido pero
     * no cargado, se usa el sistema"), no mostrar Turnip como activo.
     * Lo pone refreshMappingMask() con el segundo valor de
     * getCustomDriverSupportInfo (2 = pedido pero NO cargado).
     */
    private val _turnipRequestedButNotLoaded = MutableStateFlow(false)
    val turnipRequestedButNotLoaded: StateFlow<Boolean> = _turnipRequestedButNotLoaded.asStateFlow()

    /** Job del refresco de máscara en vuelo (ronda 0.2.65, X-P2-1). */
    private var refreshMappingMaskJob: Job? = null

    /**
     * Pregunta la máscara al núcleo (IO; sin núcleo deja null).
     * Ronda 0.2.65 (X-P2-1): el refresco en vuelo se cancela antes
     * de lanzar otro: dos refrescos concurrentes (entrar en GPU y
     * cambiar de driver a la vez) podían pisarse y dejar la máscara
     * del driver viejo.
     * Ronda 0.2.65 (C-P2-5): la máscara se pregunta con el driver
     * REALMENTE cargado, no con el guardado: getCustomDriverSupportInfo
     * (Vita3kCoreBridge, R-P1-12) devuelve [máscara, cargado] o null
     * sin dato; con cargado == 2 (pedido pero NO cargado) la máscara
     * es la del sistema y la UI lo dice. Sin dato se usa la máscara
     * clásica como antes.
     * Ronda 0.2.68 (P0, máscara-1 sin sesión): con driver del sistema
     * y sin sesión, getCustomDriverSupportInfo devuelve null (mismo
     * resguardo que la máscara clásica, G-P2-1): el 1 que devolvía el
     * pin no es la máscara del driver y degradaba Page Table a Double
     * Buffer en cada primer JUGAR. null aquí → máscara clásica → 0
     * (desconocida) → se emite el método pedido.
     */
    fun refreshMappingMask() {
        refreshMappingMaskJob?.cancel()
        refreshMappingMaskJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = settingsRepo.settingsFlow.first()
            val driverName = if (settings.gpuDriver == GpuDriver.TURNIP) settings.customDriverName else ""
            val supportInfo = Vita3kCoreBridge.getCustomDriverSupportInfo(driverName)
            val mask = supportInfo?.getOrNull(0) ?: Vita3kCoreBridge.getSupportedMemoryMappingMask(driverName)
            _turnipRequestedButNotLoaded.value = supportInfo?.getOrNull(1) == 2
            _supportedMappingMask.value = mask
            LauncherLog.append(context, "gpu", "Máscara de mapeo soportada: $mask (driver=${settings.gpuDriver})")
        }
    }

    /**
     * Borra el .pkg bloqueado por el tope de reinstalación (ronda
     * 0.2.64, H-2): el que ya se intentó reinstalar una vez y siguió
     * sin validar. Borra el archivo y su entrada de reintentos, para
     * que la próxima importación parta de cero. Lo llama el botón
     * "Borrar ese .pkg" del diálogo de JUGAR fallido.
     */
    fun deleteBlockedPkg() {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = launcher.deleteBlockedPkg()
            withContext(Dispatchers.Main) {
                _message.value = if (deleted) {
                    "El .pkg que no validaba se borró. Vuelve a importarlo en Importar para intentarlo de nuevo."
                } else {
                    "No se pudo borrar ese .pkg. Bórralo a mano en Importar → juego."
                }
            }
        }
    }
    val touchLayoutFlow = touchRepo.layoutFlow
    val profilesFlow = touchRepo.profilesFlow
    val activeProfileIdFlow = touchRepo.activeProfileIdFlow

    /**
     * 0.2.63 (G): último perfil táctil y layout vistos, para el
     * reporte de bug (perfil + fracciones del stick). Se actualizan
     * en init desde los flows del repositorio; buildBugReportText
     * los lee tal cual (pueden ser "sin dato" si el reporte se
     * exporta antes de que el primer valor llegue: el DataStore
     * emite al instante, así que en la práctica siempre hay dato).
     * Son la fuente de verdad guardada: si el stick se ve
     * desplazado en pantalla pero aquí dice (0.05, 0.675), el dato
     * está bien y la vista horneó márgenes de un tamaño viejo.
     */
    @Volatile
    private var lastTouchProfileId: String? = null

    @Volatile
    private var lastTouchLayout: List<TouchControl> = emptyList()
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
        // Ronda 0.2.55 P1-17: InputDevice.getDeviceIds() es IPC y se
        // llamaba desde LaunchedEffect (Main). Ahora en IO.
        viewModelScope.launch(Dispatchers.IO) {
            _gamepads.value = launcher.connectedGamepads()
        }
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
            // S11: no fiarse del _status guardado (puede ir
            // desactualizado tras importar o borrar): escanear fresco
            // de disco antes de decidir qué .pkg borrar. Ronda 0.2.55
            // P1-6: el escaneo pasa por scanStatusFresh() (mutex +
            // guarda de recencia P1-9), no directo a scanStatus().
            val status = runCatching { scanStatusFresh() }.getOrNull() ?: _status.value
            val extractedId = status.extractedTitleId?.id
            val freed = if (extractedId != null && status.pkgTitleId?.id == extractedId) {
                val size = status.pkgSizeBytes
                if (runCatching { library.deleteStoredGamePkg(extractedId) }.getOrDefault(false)) size else 0L
            } else 0L
            // R4-P1-10: el mensaje usa el status YA escaneado arriba
            // (fresco de disco) y solo se re-escanea si el borrado
            // cambió algo en disco (freed > 0): sin .pkg que borrar no
            // cambió nada y otro escaneo completo sería el segundo en
            // la misma función.
            if (freed > 0) refreshStatus()
            withContext(Dispatchers.Main) {
                _message.value = if (freed > 0) {
                    "Se borró el .pkg guardado (${DownloadsCleanup.formatBytes(freed)} liberados). El juego sigue instalado y la licencia no se tocó."
                } else {
                    "No había ningún .pkg guardado que borrar."
                }
            }
        }
    }

    /**
     * R6-P1-02(b): borra el .pkg de juego guardado EN CUALQUIER ESTADO
     * (sin exigir juego extraído ni eboot válido): el gancho es
     * library.deleteStoredGamePkgAnyState, para el .pkg "update
     * disfrazado de juego" que deja JUGAR en bucle. Solo se llama desde
     * la UI con confirmación (CleanupScreen): sin juego extraído, este
     * .pkg es la única copia del juego. El work.bin se conserva y no
     * hay juego extraído ni partidas que tocar (los saves solo existen
     * con el juego extraído).
     */
    fun deleteStoredGamePkgAnyState() {
        viewModelScope.launch(Dispatchers.IO) {
            // S11: no fiarse del _status guardado (puede ir
            // desactualizado tras importar o borrar): escanear fresco
            // de disco antes de decidir qué .pkg borrar, igual que
            // deleteInstalledGamePkgs.
            val status = runCatching { scanStatusFresh() }.getOrNull() ?: _status.value
            val pkgId = status.pkgTitleId?.id
            val freed = if (pkgId != null && !status.gameExtracted) {
                val size = status.pkgSizeBytes
                if (runCatching { library.deleteStoredGamePkgAnyState(pkgId) }.getOrDefault(false)) size else 0L
            } else 0L
            if (freed > 0) refreshStatus()
            withContext(Dispatchers.Main) {
                _message.value = if (freed > 0) {
                    "Se borró el .pkg guardado (${DownloadsCleanup.formatBytes(freed)} liberados). No había juego extraído: importa de nuevo tu copia del juego para jugar."
                } else {
                    "No había ningún .pkg guardado sin instalar que borrar."
                }
            }
        }
    }

    // P2-31: respaldos .bak huérfanos (su destino existe: la
    // instalación completó y el respaldo quedó atrás). Se muestran con
    // su tamaño en "Limpiar no usados" y se borran solo con
    // confirmación del usuario; un .bak cuyo destino falta no es
    // huérfano y nunca aparece aquí.
    private val _orphanBackups = MutableStateFlow<Pair<Int, Long>?>(null)
    val orphanBackupsFlow: StateFlow<Pair<Int, Long>?> = _orphanBackups.asStateFlow()

    fun refreshOrphanBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            _orphanBackups.value = runCatching { library.findOrphanBackups() }.getOrNull()
        }
    }

    fun deleteOrphanBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { library.deleteOrphanBackups() }.getOrNull()
            refreshOrphanBackups()
            refreshStatus()
            withContext(Dispatchers.Main) {
                _message.value = when {
                    result == null -> "No se pudieron revisar los respaldos .bak."
                    result.first == 0 -> "No había respaldos .bak huérfanos."
                    else -> "Respaldos .bak borrados: ${result.first} (${DownloadsCleanup.formatBytes(result.second)} liberados). El juego y los DLCs instalados no se tocaron."
                }
            }
        }
    }

    private val _status = MutableStateFlow(GameStatus())
    val status: StateFlow<GameStatus> = _status.asStateFlow()

    // Ronda 0.2.55 P0-1: true cuando el primer escaneo ya emitió un
    // GameStatus real. Antes Home nacía con GameStatus() vacío y
    // mostraba IMPORTAR hasta que terminaba el escaneo; ahora la
    // tarjeta muestra "Comprobando…" hasta que esto es true.
    private val _statusLoaded = MutableStateFlow(false)
    val statusLoaded: StateFlow<Boolean> = _statusLoaded.asStateFlow()

    // R6-P1-12: true si el último escaneo FALLÓ (disco, etc.): Home
    // muestra "No se pudo comprobar el estado" + Reintentar en vez
    // de mentir IMPORTAR con un GameStatus() vacío. Se limpia en el
    // primer escaneo con éxito.
    private val _statusError = MutableStateFlow(false)
    val statusError: StateFlow<Boolean> = _statusError.asStateFlow()

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

    /** Trofeo recién desbloqueado: la UI muestra popup + sonido.
     * BUG 2026-11-08: el popup nombraba el trofeo equivocado (15 en
     * vez de 17) porque dos desbloqueos en vuelo escribían este
     * StateFlow sin orden ni identidad de evento. El anuncio pasa por
     * [TrophyUnlockAnnouncer] (secuencia por pulsación + construcción
     * directa desde TROPHIES_20).
     * El marcado manual se eliminó (2026-11-08): los trofeos solo se
     * ganan jugando. El anunciador y el sonido quedan para la
     * sincronización automática con el TROPHY.TRP del núcleo.
     * R2-P2-11/R2-P2-37: el mutex de desbloqueo y la secuencia
     * (unlockTrophyMutex, unlockSeq) ya no tenían llamador y se
     * eliminaron; solo queda el anunciador.
     * R7-P1-06 (0.2.58): este hueco ya NO es el único sitio donde
     * viven los desbloqueos: es solo la CABEZA de la cola
     * [unlockPopupQueue]. Si una partida desbloquea varios trofeos,
     * se encolan todos y MainActivity muestra un popup tras otro (al
     * descartar uno aparece el siguiente); antes cada desbloqueo
     * sobrescribía este hueco y solo el último tenía popup. */
    private val _justUnlocked = MutableStateFlow<TrophyProgress?>(null)
    val justUnlocked: StateFlow<TrophyProgress?> = _justUnlocked.asStateFlow()
    private val unlockAnnouncer = TrophyUnlockAnnouncer()
    /** Cola de popups de trofeos (R7-P1-06): en orden numérico, uno tras otro. */
    private val unlockPopupQueue = TrophyPopupQueue()

    // Ronda 0.2.57 (puente JNI de trofeos): la sincronización al volver
    // del juego anuncia por aquí. Se declaran ANTES del init (misma
    // regla que _audioFixSnapshot: los usa syncTrophiesFromGame y una
    // corrutina del init podría correr antes de sus inicializadores).
    // - trophySyncSeq: fuente de la secuencia del anunciador: un
    //   AtomicLong que solo crece (incrementAndGet por anuncio). Nunca
    //   se reutiliza ni se resetea: un anuncio tardío con secuencia
    //   menor o igual se ignora y el popup no nombra otro trofeo.
    // - trophySyncMutex: serializa las sincronizaciones: dos vueltas
    //   seguidas a Home no pueden desbloquear/anunciar a la vez.
    private val trophySyncSeq = AtomicLong(0L)
    private val trophySyncMutex = Mutex()

    // Ronda 0.2.70, P1-5 (a): aviso de TrophySyncPending.store().
    // Se registra en init y se quita en onCleared.
    private val trophyPendingListener: (Array<NativeTrophyCollection>) -> Unit =
        { syncTrophiesFromGame(fromGame = true) }

    override fun onCleared() {
        TrophySyncPending.removeListener(trophyPendingListener)
        super.onCleared()
    }

    private val _trophyScan = MutableStateFlow<TrophyStoreScan?>(null)
    val trophyScan: StateFlow<TrophyStoreScan?> = _trophyScan.asStateFlow()

    // R2-S01: lectura del TROPHY.TRP real del juego instalado (diagnóstico
    // en Logros): nº de trofeos y nombres según el juego, no según la lista
    // del launcher. Null mientras no se ha leído o sin juego instalado.
    private val _trpRead = MutableStateFlow<TrpReadResult?>(null)
    val trpRead: StateFlow<TrpReadResult?> = _trpRead.asStateFlow()

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    // Ronda 0.2.55 P0-2: marca del archivo de log en la última lectura
    // (mtime + tamaño). refreshLog() solo re-lee si cambió: antes
    // re-leía el archivo entero en cada onResume. Se declara ANTES
    // del init porque refreshLog() corre desde el init.
    @Volatile
    private var lastLogFileStamp: Pair<Long, Long>? = null

    // Portada de Home decodificada UNA vez en el hilo IO: painterResource
    // decodifica en el hilo principal y lo repetía en cada vuelta a Home
    // (la composición se descarta al navegar). Null mientras carga: la
    // Home usa painterResource como respaldo transitorio.
    private val _coverBitmap = MutableStateFlow<ImageBitmap?>(null)
    val coverBitmap: StateFlow<ImageBitmap?> = _coverBitmap.asStateFlow()
    // Estado de la ficha RAWG
    private val _rawgDetails = MutableStateFlow<RawgGameDetails?>(null)
    val rawgDetails: StateFlow<RawgGameDetails?> = _rawgDetails.asStateFlow()
    private val _rawgCover = MutableStateFlow<ImageBitmap?>(null)
    val rawgCover: StateFlow<ImageBitmap?> = _rawgCover.asStateFlow()
    private val _rawgRefreshing = MutableStateFlow(false)
    val rawgRefreshing: StateFlow<Boolean> = _rawgRefreshing.asStateFlow()
    private val rawgRefreshGuard = AtomicBoolean(false)

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

    // MISMA REGLA (crash real 2026-11-09, 0.2.46): refreshStatus() se
    // llama desde el init y su corrutina toca refreshMutex y
    // refreshPending (P1-16). Vivían DESPUÉS del init: la corrutina
    // podía correr antes de que sus inicializadores se ejecutaran
    // (campo null -> NPE en tryLock) y la app moría al abrir.
    private val refreshMutex = Mutex()
    private val refreshPending = AtomicBoolean(false)

    // R4-P1-9: instante en que terminó el último escaneo completo.
    // Lo escribe la corrutina de refreshStatus() (hilo IO) y lo lee la
    // guarda de recencia: AtomicLong porque dos corrutinas IO pueden
    // llegar a la vez. Se declara ANTES del init por la misma regla de
    // arriba: refreshStatus() se llama desde el init.
    private val lastRefreshCompletedAtMillis = AtomicLong(0L)

    // R4-P1-12: caché de la versión externa y de la batería, para no
    // repetir el IPC (getPackageInfo / PowerManager) en cada refresco.
    // Solo las lee refreshStatusOnce() y las lecturas explícitas en
    // vivo; los Volatile bastan (en el peor caso se repite una lectura,
    // nunca se devuelve un valor incorrecto). También antes del init,
    // por la misma regla: refreshStatusOnce() corre desde el init.
    @Volatile
    private var externalVersionCache: String? = null
    @Volatile
    private var externalVersionCacheAtMillis: Long = 0L
    @Volatile
    private var externalVersionCacheKnown: Boolean = false
    @Volatile
    private var batteryOptCache: Boolean = false
    @Volatile
    private var batteryOptCacheAtMillis: Long = 0L
    @Volatile
    private var batteryOptCacheKnown: Boolean = false

    // R4-P1-12: la lista de Descargas solo se consulta en el refresco
    // la primera vez (sucia al arrancar, para que Almacenamiento no
    // nazca vacía). Después, cada refresco la salta: la entrega a
    // Descargas y el borrado de copias la re-listan directamente, y
    // Almacenamiento la pide al entrar con refreshStorageState().
    // Nada vuelve a marcarla sucia: el flag solo evita repetir la
    // query de MediaStore en cada refresco.
    @Volatile
    private var downloadsCopiesDirty: Boolean = true

    init {
        // Ronda 0.2.70, R-3 (tercera pasada): el pendiente de trofeos
        // se persiste a disco (TrophySyncPending): si el proceso muere
        // con el juego en primer plano (onDestroy no corre), el
        // próximo arranque reintenta la sync desde el archivo (take()
        // lo relee y MainActivity.onResume ya llama a
        // syncTrophiesFromGame). Se fija antes que nada: ningún
        // store() puede correr antes del init del ViewModel en este
        // proceso (el juego solo se lanza desde MainActivity, que crea
        // este ViewModel en onCreate).
        TrophySyncPending.initPersistence(context.filesDir)
        refreshStatus()
        refreshLog()
        loadCoverBitmap()
        // Cargar la ficha RAWG desde la caché (si existe)
        loadRawgCache()
        // Ronda 0.2.70, P1-5 (a): la captura de trofeos en
        // onDestroy del juego corre DESPUÉS del onResume que
        // consume el pendiente, así que el aviso de store() es el
        // que dispara la sync en esa vuelta (no solo onResume).
        // Se quita en onCleared; el oyente solo lanza la corrutina
        // (idempotente: un take() vacío no hace nada).
        TrophySyncPending.addListener(trophyPendingListener)
        viewModelScope.launch(Dispatchers.IO) { sessionManager.ensureDefaultSession() }
        // 0.2.63 (G): caché del perfil táctil activo + layout para
        // el reporte de bug (ver lastTouchProfileId). Best-effort:
        // si el DataStore falla, se quedan en "sin dato".
        viewModelScope.launch {
            runCatching {
                touchRepo.activeProfileIdFlow.collect { lastTouchProfileId = it }
            }
        }
        viewModelScope.launch {
            runCatching {
                touchRepo.layoutFlow.collect { lastTouchLayout = it }
            }
        }
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

    /**
     * R7-P1-06: cerrar el popup muestra el SIGUIENTE de la cola (si
     * la partida desbloqueó varios trofeos), no lo descarta todo. La
     * llama MainActivity al descartar el popup. Para vaciar la cola
     * entera (borrar el progreso) es clearUnlockQueue.
     */
    fun clearJustUnlocked() {
        unlockAnnouncer.clear()
        unlockPopupQueue.dismiss()
        _justUnlocked.value = unlockPopupQueue.current
    }

    /** R7-P1-06: vacía TODA la cola de popups de logros (al borrar el progreso): no queda ningún popup pendiente de trofeos que ya no existen. */
    fun clearUnlockQueue() {
        unlockAnnouncer.clear()
        unlockPopupQueue.clear()
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
    // (Las declaraciones viven ANTES del init: ver comentario de orden
    // de inicialización junto a _audioFixSnapshot.)

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!refreshMutex.tryLock()) {
                refreshPending.set(true)
                return@launch
            }
            try {
                // R4-P1-9: guarda de recencia EN EL MISMO camino del
                // mutex (no un segundo camino): con el candado ya en
                // la mano, ningún escaneo corre en paralelo y el
                // último completado es el dato fresco. Si terminó hace
                // <2 s y nadie pidió otro mientras corría, se salta el
                // escaneo: es el caso del arranque en frío (init +
                // onResume) y de volver del selector SAF (onResume +
                // endImport). El bucle de pendientes NO pasa por esta
                // guarda: un pendiente marcado durante un escaneo es la
                // señal de que el disco pudo cambiar a mitad del
                // escaneo y su re-escaneo siempre corre. Al volver de
                // una partida larga el último escaneo es viejo y el
                // refresco ocurre igual.
                if (shouldSkipRecentRefresh(
                        lastRefreshCompletedAtMillis.get(),
                        System.currentTimeMillis(),
                        refreshPending.get(),
                    )
                ) {
                    return@launch
                }
                do {
                    refreshPending.set(false)
                    refreshStatusOnce()
                    lastRefreshCompletedAtMillis.set(System.currentTimeMillis())
                } while (refreshPending.getAndSet(false))
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    /**
     * Ronda 0.2.55 P1-6: escaneo fresco SIN saltarse la guarda P1-9.
     * Para llamadores que necesitan el GameStatus como valor de
     * retorno (deleteInstalledGamePkgs): si el último escaneo
     * completado tiene menos de 2 s se reutiliza _status; si no, se
     * escanea una vez bajo el mutex de refreshStatus() (nunca dos
     * escaneos a la vez) y se actualiza la marca de recencia. Se
     * suspende esperando el mutex si otro escaneo corre: es IO, nunca
     * Main. No es refreshStatus(): ese es fire-and-forget y no
     * devuelve el estado.
     */
    private suspend fun scanStatusFresh(): GameStatus {
        refreshMutex.withLock {
            if (shouldSkipRecentRefresh(
                    lastRefreshCompletedAtMillis.get(),
                    System.currentTimeMillis(),
                    refreshPending.get(),
                )
            ) {
                return _status.value
            }
            refreshPending.set(false)
            refreshStatusOnce()
            lastRefreshCompletedAtMillis.set(System.currentTimeMillis())
            return _status.value
        }
    }

    // R4-P1-12: versión externa cacheada (getPackageInfo es IPC por
    // refresco). La caché dura EXTERNAL_INFO_CACHE_TTL_MILLIS; las
    // lecturas explícitas en vivo (Diagnóstico, reporte de bug) la
    // refrescan. Un null cacheado es válido (app externa no
    // instalada): por eso existe externalVersionCacheKnown.
    private fun cachedExternalVersion(): String? {
        val now = System.currentTimeMillis()
        if (externalVersionCacheKnown && isExternalInfoCacheFresh(externalVersionCacheAtMillis, now)) {
            return externalVersionCache
        }
        val fresh = runCatching { launcher.externalVita3kVersion() }.getOrNull()
        externalVersionCache = fresh
        externalVersionCacheAtMillis = now
        externalVersionCacheKnown = true
        return fresh
    }

    // R4-P1-12: exención de batería cacheada (PowerManager es IPC por
    // refresco). Misma TTL que la versión externa.
    private fun cachedBatteryOptDisabled(): Boolean {
        val now = System.currentTimeMillis()
        if (batteryOptCacheKnown && isExternalInfoCacheFresh(batteryOptCacheAtMillis, now)) {
            return batteryOptCache
        }
        val fresh = runCatching { launcher.isBatteryOptimizationDisabled() }.getOrDefault(false)
        batteryOptCache = fresh
        batteryOptCacheAtMillis = now
        batteryOptCacheKnown = true
        return fresh
    }

    /**
     * R5-A1-13: refresco completo al entrar a Almacenamiento. Invalidar
     * solo no bastaría: los tamaños viven en el flujo status y solo se
     * recalculan en scanStatus(), así que tras olvidar los tamaños
     * cacheados de los títulos conocidos se fuerza un escaneo nuevo.
     * - La guarda de recencia de refreshStatus() se salta a propósito:
     *   entrar a Almacenamiento es un pedido explícito de ver tamaños
     *   frescos, no spam de escaneos; marcar el pendiente antes de
     *   llamar a refreshStatus() es el mismo mecanismo que ya usa un
     *   refresco pedido mientras otro escaneo corría (y si un escaneo
     *   corre ahora mismo, el pendiente hace que se repita una vez al
     *   terminar, con la caché ya olvidada).
     * - La lista de Descargas se re-lee aquí mismo (antes la pedía la
     *   pantalla con refreshDownloadsCopiesNow()): un solo punto de
     *   entrada al entrar a Almacenamiento y una sola query de
     *   MediaStore (el flag se limpia antes de refreshStatus() para
     *   que refreshStatusOnce() no la repita).
     */
    fun refreshStorageState() {
        viewModelScope.launch(Dispatchers.IO) {
            storageInvalidationTitleIds(_status.value).forEach { library.invalidateInstalledSizes(it) }
            downloadsCopiesDirty = false
            refreshPending.set(true)
            refreshStatus()
            refreshDownloadsCopies()
        }
    }

    /** Un escaneo completo del estado (disco + firmware + TRP). Solo lo llama refreshStatus(), bajo el mutex. */
    private fun refreshStatusOnce() {
            // Ronda 0.2.55 (S1/S2): duración del refresco por fase,
            // una línea de log barata para confirmar en el teléfono
            // si el cuello es el firmware (vs0) o el escaneo.
            val refreshStartedMillis = SystemClock.elapsedRealtime()
            var firmwareMillis = 0L
            var scanMillis = 0L
            // R5-A4-1: limpiar .tmp huérfanos de importaciones fallidas
            // al inicio de cada refresco (no solo en la siguiente
            // importación): un .tmp de hasta 1,14 GB no debe quedarse
            // en disco entre importaciones.
            runCatching { library.cleanStaleImportLeftovers() }
            // P2-33: la migración del vs0 antiguo (vitaRoot/vs0) a la
            // ruta del núcleo (vita/vs0) es EXPLÍCITA y se hace aquí,
            // antes de leer el estado — resolve() es una lectura y ya
            // no mueve directorios como efecto secundario. Idempotente:
            // si no hay nada que migrar, devuelve false sin tocar nada.
            runCatching {
                if (FirmwareResolver.migrateLegacyVs0(library.vitaRoot)) {
                    LauncherLog.append(context, "status", "Firmware migrado de la ruta antigua (vitaRoot/vs0) a vita/vs0.")
                }
            }
            // R4-P1-12: el firmware se resuelve UNA sola vez por
            // refresco y se pasa a scanStatus(), que antes lo resolvía
            // otra vez por dentro. Si la resolución falla (nunca
            // debería: es lectura de archivos), scanStatus() resuelve
            // por su cuenta y _firmwareStatus conserva su valor.
            val firmwareResolveStartedMillis = SystemClock.elapsedRealtime()
            val firmware = runCatching { FirmwareResolver.resolve(library.vitaRoot) }.getOrNull()
            firmwareMillis = SystemClock.elapsedRealtime() - firmwareResolveStartedMillis
            // BUG (auditoría): scanStatus() hace I/O y puede lanzar; se llama
            // desde init/onResume sin protección. Si falla, se conserva el
            // estado anterior en vez de tumbar la corrutina.
            val scanStartedMillis = SystemClock.elapsedRealtime()
            runCatching { library.scanStatus(firmware) }
                .onSuccess {
                    _status.value = it
                    // R6-P1-12: "cargado" solo si el escaneo emitió un
                    // estado válido. En fallo, _statusLoaded se queda
                    // en false (Home sigue en "Comprobando…" o en el
                    // error con reintento) y _status conserva el
                    // estado anterior, no un vacío que miente IMPORTAR.
                    _statusLoaded.value = true
                    _statusError.value = false
                }
                .onFailure {
                    LauncherLog.append(context, "status", "No se pudo escanear el estado: ${it.message}")
                    _statusError.value = true
                }
            scanMillis = SystemClock.elapsedRealtime() - scanStartedMillis
            firmware?.let { _firmwareStatus.value = it }
            _externalVita3k.value = cachedExternalVersion()
            // Crash al abrir (#80/#82): NO cargar el .so aquí. `available`
            // ejecuta System.loadLibrary y un fallo nativo en sus
            // inicializadores mata el proceso sin stack trace Java antes
            // del primer frame. Solo se comprueba que el archivo existe;
            // la carga real es al lanzar el juego.
            _embeddedCore.value =
                runCatching { Vita3kCoreBridge.isLibraryFilePresent(context) }.getOrNull()
            _batteryOptDisabled.value = cachedBatteryOptDisabled()
            // R4-P1-12: la query de MediaStore de Descargas solo corre
            // en el primer refresco (downloadsCopiesDirty): la entrega
            // y el borrado de copias re-listan directamente, y
            // Almacenamiento la pide al entrar con refreshStorageState().
            // En cada refresco, no: el usuario casi nunca abre
            // Almacenamiento.
            if (downloadsCopiesDirty) {
                refreshDownloadsCopies()
                downloadsCopiesDirty = false
            }
            val title = _status.value.titleId?.id
            _trophyScan.value = if (title != null) {
                runCatching { TrophyStoreReader.scan(library.vitaRoot, title) }.getOrNull()
            } else null
            // R2-S01: el TRP del juego instalado, leído del .pkg ya
            // extraído (appDir del ux0 efectivo). Unreadable si no trae
            // TRP o el XML no se puede interpretar: nunca se inventa un
            // conteo; la lista de 20 no cambia sin esta prueba.
            // R5-A4-8: readTrpForDiagnosis reintenta con el TRP
            // instalado por el núcleo si el empaquetado es ilegible, y
            // un fallo del lector se convierte en Unreadable (antes el
            // getOrNull() hacía desaparecer la tarjeta en silencio).
            _trpRead.value = if (title != null) {
                runCatching { TrophyTrpReader.readTrpForDiagnosis(library.appDir(title)) }
                    .getOrElse { TrpReadResult.Unreadable("No se pudo leer el diagnóstico TRP.", it.message) }
            } else null
            // P1-11: el pendiente de licencia es solo en memoria y se
            // pierde al reiniciar; se reconstruye desde disco (lo que
            // falta de verdad, según storedPkgsMissingLicense) para que
            // la tarjeta "Falta la licencia" de Importar aparezca
            // también tras reiniciar, sin reimportar el .pkg.
            rebuildPendingLicenseFromDisk()
            // Ronda 0.2.55 (S1/S2): una línea por refresco con la
            // duración por fase. Es una sola línea (el resumen de
            // scanStatus sigue deduplicado aparte) y es lo que
            // permite medir en el teléfono si el refresco es lento.
            // R6-P2-18: la línea de duración solo se registra si el
            // refresco supera 500 ms: en el caso normal no aporta
            // nada y rotaba el log en cada refresco.
            val refreshMillis = SystemClock.elapsedRealtime() - refreshStartedMillis
            if (refreshMillis > 500) {
                LauncherLog.append(
                    context,
                    "status",
                    "Refresco de estado: $refreshMillis ms " +
                        "(firmware $firmwareMillis ms, escaneo $scanMillis ms).",
                )
            }
    }

    /**
     * R6-P1-12: reintento explícito tras un escaneo fallido (botón
     * Reintentar de Home). Marca el pendiente para que la guarda de
     * recencia no se salte el re-escaneo aunque el fallo fuera hace
     * menos de 2 s; el escaneo corre por el camino normal de
     * refreshStatus().
     */
    fun retryStatusCheck() {
        refreshPending.set(true)
        refreshStatus()
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
        // R6-P1-04: un .pkg de juego sin work.bin NO genera pendiente
        // de licencia cuando el juego está extraído y su licencia ya
        // está registrada en el núcleo: JUGAR funciona sin el work.bin
        // y la tarjeta "Falta la licencia" mentía pidiéndolo.
        val gameLicenseInCore = _status.value.gameExtracted && _status.value.licenseInstalledInCore
        val missing = runCatching { library.storedPkgsMissingLicense() }
            .getOrDefault(emptyList())
            .filterNot { it.kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME && gameLicenseInCore }
        val current = _pendingPkgLicense.value
        if (missing.isEmpty()) {
            if (current != null &&
                (storedPkgFileFor(current.kind, current.titleId, current.dlcId) == null ||
                    (current.kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME && gameLicenseInCore))
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
        // Ronda 0.2.55 P0-2: solo se re-lee si el archivo cambió
        // (mtime + tamaño); en cada onResume sin cambios no se toca
        // el disco. P1-13: tampoco se re-emite si el contenido es
        // idéntico, para no recomponer Diagnóstico/GPU sin motivo.
        viewModelScope.launch(Dispatchers.IO) {
            val file = LauncherLog.file(context)
            val stamp = file.lastModified() to file.length()
            if (stamp == lastLogFileStamp && _logText.value.isNotEmpty()) return@launch
            lastLogFileStamp = stamp
            val text = LauncherLog.readLast(context, 60)
            if (text != _logText.value) _logText.value = text
        }
    }

    private fun loadCoverBitmap() {
        viewModelScope.launch(Dispatchers.IO) {
            // Ronda 0.2.55 P1-12: la portada se muestra a ~1/3 de su
            // ancho real (1024x734): decodificarla entera (~3 MB de
            // bitmap) es trabajo de más en un G99. inSampleSize=2 la
            // deja en 512x367, de sobra para la tarjeta.
            _coverBitmap.value = runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeResource(context.resources, R.drawable.col_cover_clean, options)?.asImageBitmap()
            }.getOrNull()
        }
    }

    // Refrescar la ficha RAWG desde la API (opcionalmente forzando)
    fun refreshRawgDetails(title: String = "Child of Light", force: Boolean = false) {
        if (!force && _rawgDetails.value != null) return
        if (!rawgRefreshGuard.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _rawgRefreshing.value = true
                val apiKey = settingsRepo.settingsFlow.first().rawgApiKey
                if (apiKey.isBlank()) return@launch   // sin clave no se hace la petición

                val details = rawgClient.findGameDetails(title, apiKey) ?: run {
                    LauncherLog.append(context, "rawg", "No se pudo descargar la ficha del juego.")
                    return@launch
                }

                // Guardar y publicar los detalles
                rawgCache.saveDetails(title, details)
                _rawgDetails.value = details

                // Descargar y guardar la portada si está disponible
                details.backgroundImageUrl?.let { url ->
                    downloadBytes(url)?.let { bytes ->
                        rawgCache.saveCover(title, bytes)
                        val bitmap = runCatching {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                        }.getOrNull()
                        _rawgCover.value = bitmap
                    }
                }
            } finally {
                _rawgRefreshing.value = false
                rawgRefreshGuard.set(false)
            }
        }
    }

    // Helper privado para descargar bytes de una URL (sin registrar la URL ni la clave)
    private fun downloadBytes(urlStr: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "ChildOfLightLauncher")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // Cargar la ficha RAWG desde la caché (si está disponible)
    private fun loadRawgCache(title: String = "Child of Light") {
        viewModelScope.launch(Dispatchers.IO) {
            // Detalles
            _rawgDetails.value = rawgCache.loadDetails(title)
            // Portada
            rawgCache.loadCover(title)?.let { bytes ->
                val bitmap = runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                }.getOrNull()
                _rawgCover.value = bitmap
            }
        }
    }

    fun refreshConfigPreview(settings: LauncherSettings) {
        // La vista previa debe mostrar lo mismo que se escribirá al
        // lanzar (incluido el fallback Vulkan -> OpenGL): por eso se
        // lee la GPU detectada antes de generar.
        viewModelScope.launch(Dispatchers.IO) {
            val gpu = settingsRepo.gpuInfoFlow.first()
            // P1-14: generate() espera vitaFsPath, no vitaRoot: con la
            // raíz, la vista previa mostraba el pref-path ANTIGUO (el
            // del bug -3) mientras writeConfig escribía el bueno.
            // R5-A4-2 (P0): la vista previa se sanitiza AQUÍ, al
            // generarla, así ningún consumidor futuro vuelve a filtrar
            // el nombre del paquete en pantalla.
            _generatedConfig.value = sanitizeVisiblePath(
                Vita3kConfigGenerator.generate(
                    settings,
                    Vita3kConfigGenerator.vitaFsPathFor(library.vitaRoot).absolutePath,
                    gpu,
                    // Ronda 0.2.66 (G11-02): la vista previa muestra el
                    // mapeo EFECTIVO que se escribirá al lanzar, no el pedido.
                    _supportedMappingMask.value,
                ),
            )
        }
    }

    // --- GPU ---

    fun detectGpu() {
        viewModelScope.launch(Dispatchers.IO) {
            val gpu = GpuDetector.detect(context)
            val previousGpu = settingsRepo.gpuInfoFlow.first()
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
            // R7-P2-10 parcial (0.2.57): detectGpu llama directo a
            // settingsRepo.applyPreset, sin pasar por el applyPreset
            // del ViewModel que invalida el snapshot (R6-P2-10). En
            // el primer arranque el modo seguro no puede estar ON
            // (false por defecto, sin snapshot posible), pero la
            // re-detección manual desde Diagnóstico GPU sí puede
            // correr con el modo seguro ON: sin esto, al desactivarlo
            // resucitarían los valores viejos.
            if (appliedPreset && current.safeMode) deleteSettingsSnapshot(safeModeSnapshotFile())
            // Ronda 0.2.65 (RG-P2-11): la GPU guardada era el fallback
            // (OTHER/UNKNOWN) y ahora se detectó una real: si el preset
            // en vigor sigue siendo el del fallback (el usuario no
            // eligió otro a mano), se aplica el correcto. Sin esto, la
            // primera detección fallida bloqueaba el preset correcto
            // para siempre.
            val recoveredPreset = if (!appliedPreset) recoverFallbackPreset(previousGpu, gpu, current) else false
            // R4-P0-9: la variante conservadora es VISIBLE: el mensaje
            // dice que se aplicó el perfil conservador (1x) para
            // ahorrar memoria (antes decía además que la caché de
            // texturas quedó OFF; ronda 0.2.66, C-01: la variante
            // low-RAM ya no toca textureCache — en Vulkan OFF
            // re-subiría cada textura — y MALI_PERFORMANCE.textureCache
            // ahora es true, igual que G99; ver
            // isLowRamConservativeVariant).
            val lowRamNote = if (appliedPreset && lowRam) " Memoria baja (4 GB o menos): perfil conservador (1x) aplicado para ahorrar memoria." else ""
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
                        if (appliedPreset) "preset G99 Recomendado aplicado." else if (recoveredPreset) "preset G99 Recomendado aplicado (la detección anterior había fallado y dejó el preset genérico)." else "tus ajustes personalizados no se tocaron."
                } else {
                    "GPU detectada: ${gpu.shortLabel}. " +
                        if (appliedPreset) "Preset ${defaultProfileFor(gpu).label} aplicado." else if (recoveredPreset) "Preset ${defaultProfileFor(gpu).label} aplicado (la detección anterior había fallado y dejó el preset genérico)." else "Tus ajustes personalizados no se tocaron."
                }) + lowRamNote + vulkanWarning
            }
            LauncherLog.append(context, "gpu", "Detectada: ${gpu.renderer} | SoC ${gpu.socModel} | ${gpu.vulkanVersionLabel}")
            // En Adreno, instalar el driver incluido si lo trae la APK
            // (no-op mientras assets no tenga el zip; nunca en Mali).
            ensureBundledTurnipDriver()
        }
    }

    /**
     * Ronda 0.2.65 (RG-P2-11): si la GPU guardada era el fallback
     * (OTHER/UNKNOWN, p. ej. el primer probe EGL falló) y ahora se
     * detectó una GPU real de otro vendor, re-aplicar el preset
     * correcto — pero SOLO si el perfil activo sigue siendo el del
     * fallback (el usuario no eligió otro a mano: si eligió uno, solo
     * se actualiza la GPU guardada y sus ajustes no se tocan).
     * Devuelve true si se re-aplicó el preset.
     */
    private suspend fun recoverFallbackPreset(previousGpu: GpuInfo?, detectedGpu: GpuInfo, current: LauncherSettings): Boolean {
        if (previousGpu == null) return false
        if (previousGpu.vendor != GpuVendor.OTHER && previousGpu.vendor != GpuVendor.UNKNOWN) return false
        if (detectedGpu.vendor == previousGpu.vendor) return false
        if (current.activeProfile != defaultProfileFor(previousGpu)) return false
        val profile = defaultProfileFor(detectedGpu)
        val lowRam = DeviceMemory.isLowRamDevice(context)
        settingsRepo.applyPreset(profile, current = current, lowRam = lowRam)
        LauncherLog.append(context, "gpu", "GPU re-detectada: ${previousGpu.shortLabel} -> ${detectedGpu.shortLabel}; preset ${profile.label} aplicado (el anterior era el del fallback).")
        return true
    }

    /**
     * Ronda 0.2.65 (RG-P2-11): re-intentar la detección de GPU en
     * cada JUGAR mientras la guardada sea el fallback. Solo cuesta un
     * probe EGL mientras no haya una GPU real guardada; en cuanto la
     * hay, no se vuelve a preguntar. Devuelve la GPU vigente.
     */
    private suspend fun recheckGpuDetectionIfFallback(): GpuInfo? {
        val saved = settingsRepo.gpuInfoFlow.first()
        if (saved != null && saved.vendor != GpuVendor.OTHER && saved.vendor != GpuVendor.UNKNOWN) return saved
        val detected = runCatching { GpuDetector.detect(context) }.getOrNull() ?: return saved
        settingsRepo.saveGpuInfo(detected)
        val current = settingsRepo.settingsFlow.first()
        if (recoverFallbackPreset(saved, detected, current)) {
            withContext(Dispatchers.Main) {
                _message.value = "GPU detectada: ${detected.shortLabel}. Preset ${defaultProfileFor(detected).label} aplicado (la detección anterior había fallado y dejó el preset genérico)."
            }
        }
        return detected
    }

    // --- Ajustes ---

    fun saveSettings(settings: LauncherSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            // P1-18: si el guardado falla (disco lleno, archivo corrupto),
            // la app ya no muere (safeEdit lo registra y devuelve false),
            // pero el usuario debe saber que su cambio no quedó guardado.
            if (!settingsRepo.save(settings)) {
                _message.value = "No se pudieron guardar los ajustes (fallo al escribir). Inténtalo de nuevo."
            }
        }
    }

    fun applyPreset(profile: GpuProfileId) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsRepo.settingsFlow.first()
            // R6-P2-10: si el modo seguro está ON, su snapshot queda
            // invalidado al aplicar un preset: al desactivarlo después,
            // los campos donde el preset coincide con el valor forzado
            // se restaurarían a valores viejos (resucitados). Se borra
            // la copia; al desactivar, los valores del preset quedan
            // como están y el mensaje lo dice.
            if (current.safeMode) deleteSettingsSnapshot(safeModeSnapshotFile())
            // Ronda 0.2.64 (H-01): el preset Turnip en una GPU que no
            // es Adreno revierte el preset ENTERO a MALI_PERFORMANCE,
            // no solo el driver: el guard anterior solo corregía
            // gpuDriver a SYSTEM y el resto del preset Adreno (2x,
            // FXAA, anisotrópico 4x, Page Table) quedaba aplicado en
            // una GPU donde nunca se validó. profilesFor() ya no lista
            // Turnip fuera de Adreno; esto cubre el camino directo
            // (p. ej. un preset guardado antes del cambio).
            val gpu = settingsRepo.gpuInfoFlow.first()
            val effectiveProfile = if (profile == GpuProfileId.ADRENO_QUALITY_TURNIP && gpu?.vendor != GpuVendor.ADRENO) {
                GpuProfileId.MALI_PERFORMANCE
            } else {
                profile
            }
            settingsRepo.applyPreset(effectiveProfile, current = current)
            // R5-A2-2: la conservación del modo rendimiento y del
            // auto-revert vive ahora dentro de SettingsRepository
            // .applyPreset (la cubren también el auto-revert de la
            // prueba de 30 s y detectGpu); el bloque duplicado que
            // vivía aquí se eliminó.
            // R5-A2-5: si algún campo preservado (cachés y overlay)
            // difiere entre los ajustes actuales y el preset, el
            // preset NO lo cambió (se conservó el manual) y el
            // mensaje lo dice: la pantalla General muestra
            // "Perfil modificado" en ese caso.
            val preservedNote = if (preservedPresetFieldsDiffer(current, presetSettings(effectiveProfile))) {
                " Tus cambios manuales de cachés y overlay se conservaron (verás «Perfil modificado»)."
            } else {
                ""
            }
            if (effectiveProfile != profile) {
                _message.value = "Preset aplicado: ${effectiveProfile.label} (Turnip solo funciona en Adreno: en este teléfono se aplicó el perfil seguro en su lugar).$preservedNote"
                LauncherLog.append(context, "preset", "Preset aplicado: ${effectiveProfile.name} (pedido ${profile.name}, revertido entero: GPU no Adreno)")
                return@launch
            }
            // Adreno sin Turnip instalado: el preset Turnip dejaría
            // gpuDriver=TURNIP sin efecto y sin forma de revertirlo
            // desde la UI. Solo se corrige el driver (el resto del
            // preset Adreno sí es válido aquí).
            if (effectiveProfile == GpuProfileId.ADRENO_QUALITY_TURNIP) {
                val updated = settingsRepo.settingsFlow.first()
                if (!updated.turnipInstalled) {
                    settingsRepo.save(updated.copy(gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM))
                    _message.value = "Preset aplicado: ${profile.label}. Turnip no está instalado: se usa el driver del sistema.$preservedNote"
                    LauncherLog.append(context, "preset", "Preset aplicado: ${profile.name} (Turnip no instalado, driver Sistema)")
                    return@launch
                }
            }
            _message.value = "Preset aplicado: ${profile.label}.$preservedNote"
            LauncherLog.append(context, "preset", "Preset aplicado: ${profile.name}")
        }
    }

    fun resetSettings() {
        viewModelScope.launch(Dispatchers.IO) {
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
            // P1-8: el TURNIP activo solo se conserva si el
            // teléfono puede usarlo (Adreno con el driver
            // instalado). En Mali (o sin Turnip instalado) el
            // reseteo volvía a dejar un TURNIP huérfano sin
            // forma de revertirlo desde la UI.
            // Ronda 0.2.65 (G-P2-7): si el driver no se conserva, los
            // flags tampoco: antes se restauraban igual y quedaba el
            // fantasma "Turnip instalado" sin driver activo.
            val keepTurnip = current.gpuDriver == com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP &&
                gpu?.vendor == com.danielsnagustin.childoflight.launcher.model.GpuVendor.ADRENO &&
                current.turnipInstalled
            settingsRepo.save(
                reset.copy(
                    turnipInstalled = if (keepTurnip) current.turnipInstalled else false,
                    turnipVersion = if (keepTurnip) current.turnipVersion else "",
                    customDriverName = if (keepTurnip) current.customDriverName else "",
                    gpuDriver = if (keepTurnip) {
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
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsRepo.settingsFlow.first()
            if (enabled) {
                // R6-P2-10: si la copia de seguridad no se pudo
                // escribir (disco lleno), NO se sobrescriben los
                // ajustes: la promesa "tus ajustes anteriores quedan
                // guardados" sería falsa y al desactivar no habría
                // nada que restaurar.
                if (!writeSettingsSnapshot(safeModeSnapshotFile(), current)) {
                    _message.value = "No se pudo guardar la copia de tus ajustes: el modo seguro no se activó. Inténtalo de nuevo."
                    return@launch
                }
                settingsRepo.save(applySafeMode(current))
                _message.value = "Modo seguro activado: 1x, SDL, High Accuracy OFF y driver del sistema (un Turnip malo queda neutralizado; se puede reactivar al salir). Tus ajustes anteriores quedan guardados: al desactivarlo se restauran los valores que el modo seguro cambió."
            } else {
                val snapshot = readSettingsSnapshot(safeModeSnapshotFile())
                deleteSettingsSnapshot(safeModeSnapshotFile())
                if (snapshot != null) {
                    settingsRepo.save(restoreSafeModeSnapshot(snapshot, current))
                    _message.value = "Modo seguro desactivado. Se restauraron los valores anteriores al modo seguro; los cambios hechos mientras estaba activado se conservan, salvo si un ajuste quedó justo en el valor que el modo seguro forzaba: en ese caso no se puede saber si lo tocaste y vuelve al valor anterior."
                } else {
                    settingsRepo.save(current.copy(safeMode = false))
                    _message.value = "Modo seguro desactivado. No había ajustes guardados que restaurar: vuelve a aplicar tu preset si lo necesitas."
                }
            }
        }
    }

    /**
     * "Corregir audio": SDL + NGS ON + shaderCache ON (Ola 2, Etapa 4).
     * Antes de aplicar se guarda una copia de los ajustes actuales en
     * un archivo privado (col-audio-fix-snapshot.json): la pantalla de
     * Audio ofrece "Deshacer" para restaurarlos, también si la app se
     * cierra entre medias. El destino de los ajustes es audioFixTarget
     * (misma fuente que el diálogo de confirmación). No reinicia el
     * juego solo: hay que volver a lanzar el juego.
     *
     * Ronda 0.2.64 (H-1 tiempo de carga, P0): YA NO limpia la caché
     * de shaders. Antes borraba shader_cache + cache/shaders enteros
     * (33 archivos en el caso del usuario) y el siguiente arranque
     * recompilaba todo (~2 min): el backend de audio (SDL/Cubeb +
     * NGS) no tiene ninguna relación con la caché de shaders (se
     * indexa por hash de programa y versión de shader, no por
     * backend). La limpieza de caché sigue existiendo como botón
     * intencional en Ajustes → Rendimiento, con su aviso de que el
     * siguiente arranque será más lento.
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

    private fun writeSettingsSnapshot(file: java.io.File, settings: LauncherSettings): Boolean {
        return runCatching { file.writeText(encodeSettingsBackup(settings, UiPreferences())) }.isSuccess
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
            // R7-P2-02: mismo patrón que setSafeMode: si la copia de
            // seguridad no se pudo escribir (disco lleno), NO se aplica
            // el cambio: el diálogo ya prometió que se puede deshacer
            // y sin snapshot en disco el deshacer no existiría tras
            // reiniciar. Antes se descartaba el resultado y se aplicaba
            // igualmente, dejando solo la copia en memoria.
            if (!writeSettingsSnapshot(audioFixSnapshotFile(), current)) {
                _message.value = "No se pudo guardar la copia de tus ajustes: «Corregir audio» no se aplicó. Inténtalo de nuevo."
                return@launch
            }
            _audioFixSnapshot.value = current
            settingsRepo.save(audioFixTarget(current))
            withContext(Dispatchers.Main) {
                // Ronda 0.2.65 (A-P2-3): "caché de shaders" aquí es el
                // AJUSTE (activar su uso), no borrar archivos: la
                // caché guardada no se toca y su limpieza es un botón
                // aparte en Rendimiento. El texto viejo decía a la vez
                // "caché de shaders ON" y "No se toca la caché".
                _message.value = "Audio corregido: SDL + NGS ON + ajuste de caché de shaders ON + audio experimental OFF. No se borra la caché guardada: el siguiente arranque no se vuelve más lento por esto. Vuelve a lanzar el juego para aplicar el cambio."
            }
            LauncherLog.append(context, "audio", "Corregir audio: SDL + NGS ON + audio experimental OFF (sin tocar la caché de shaders)")
        }
    }

    /** Restaura los ajustes guardados antes de "Corregir audio" y borra el snapshot.
     * R2-P1-09: restauración por campo (restoreAudioFixSnapshot), igual
     * que el Modo seguro: solo vuelven los 4 campos de audio; el resto
     * — incluidos Turnip y entrega del .pkg, que el snapshot no guarda —
     * se conserva como esté ahora. */
    fun undoFixAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = readSettingsSnapshot(audioFixSnapshotFile()) ?: _audioFixSnapshot.value ?: return@launch
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(restoreAudioFixSnapshot(snapshot, current))
            deleteSettingsSnapshot(audioFixSnapshotFile())
            _audioFixSnapshot.value = null
            _message.value = "Audio restaurado a los valores anteriores a «Corregir audio» (backend, NGS, caché de shaders y audio experimental). Los demás ajustes no se tocaron."
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

    fun importGame(uri: Uri, force: Boolean = false) = importWith(uri, "GAME", { name -> library.importGame(uri, name, force) })
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
        // R4-P0-7: aquí ya NO se escribe config.yml al entrar: antes se
        // escribía ANTES de comprobar siquiera si había firmware que
        // instalar, y JUGAR acababa escribiéndolo 3 veces. La escritura
        // vive ahora justo antes de instalar (abajo), cuando de verdad
        // hay un PUP pendiente: installFirmware inicializa el núcleo y
        // el núcleo lee config.yml al iniciar (P1-15).
        if (!Vita3kCoreBridge.available) {
            return FirmwareCoreResult.NotInstalled(
                "Firmware guardado. El núcleo embebido aún no está en esta versión: " +
                    "el PUP queda guardado en el teléfono y se instalará solo cuando el núcleo esté disponible. " +
                    "Mientras tanto, la app externa usa su propio firmware.",
            )
        }
        when (val status = FirmwareResolver.resolve(library.vitaRoot)) {
            is FirmwareStatus.Ready -> {
                // R6-P1-03: "ya instalado" solo si NO hay un PUP
                // guardado pendiente de instalar (savedPupPendingInstall
                // es true cuando el PUP guardado difiere en tamaño del
                // instalado o el marcador no registra tamaño: PUP nuevo
                // u otra versión). Si hay PUP pendiente, se cae a la
                // instalación del PUP guardado: el botón "Instalar en
                // el núcleo embebido" existe justo para ese caso.
                if (!FirmwareResolver.savedPupPendingInstall(library.vitaRoot)) {
                    return FirmwareCoreResult.Done("Firmware ya instalado en el núcleo (${status.source}).")
                }
                // Si no: PUP pendiente; seguir e instalarlo.
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
        // R4-P0-7: config.yml se escribe SOLO aquí, cuando de verdad hay
        // un PUP pendiente de instalar (todos los retornos de arriba ya
        // pasaron). P1-15: installFirmware inicializa el núcleo y el
        // núcleo lee config.yml al iniciar; con un config.yml viejo
        // (pref-path pre-arreglo -3) la sesión quedaría envenenada
        // hasta matar el proceso. Best-effort: si no se puede escribir,
        // se sigue (el fallo queda en el log, como en play()); además
        // writeConfig es idempotente y no reescribe si el contenido es
        // idéntico al que play() ya escribió.
        runCatching {
            val settings = settingsRepo.settingsFlow.first()
            val gpu = settingsRepo.gpuInfoFlow.first()
            // Ronda 0.2.66 (G11-02): con máscara conocida se emite el
            // mapeo efectivo (igual que en play()).
            Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu, _supportedMappingMask.value)
        }.onFailure {
            LauncherLog.append(context, "config", "No se pudo escribir config.yml antes del firmware: ${it.message}")
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
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsRepo.settingsFlow.first()
            if (current.pkgDeliveredToVita3k || current.pkgInstalledInVita3k) {
                settingsRepo.save(current.copy(pkgDeliveredToVita3k = false, pkgInstalledInVita3k = false))
            }
        }
    }

    private fun importResultMessage(result: ImportResult): String = when (result) {
        is ImportResult.Success -> result.detail
        is ImportResult.DlcSuccess -> "DLC ${result.dlcId} instalado para ${result.titleId.id}."
        is ImportResult.UpdateSuccess -> "Actualización instalada para ${result.titleId.id}."
        is ImportResult.FirmwareSuccess -> "Firmware ${result.fileName} guardado."
        is ImportResult.PkgStored -> {
            val what = when (result.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME -> "El juego"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> "El DLC"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE -> "La actualización"
            }
            // El motor integrado instala el .pkg del JUEGO al pulsar
            // JUGAR (UseEmbeddedPkg). Para DLC/update, PkgStored ya
            // solo ocurre cuando la instalación en el acto falló (con
            // éxito se devuelve DlcSuccess/UpdateSuccess): el mensaje
            // NO promete una instalación en JUGAR que no existe (P1-1).
            val installNote = when (result.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME ->
                    "Pulsa JUGAR: el motor integrado instalará el .pkg y arrancará el juego."
                else -> "No se pudo instalar en el núcleo ahora mismo: el .pkg queda guardado. Comprueba que tienes instalada la APK con el motor integrado («child-of-light-launcher-apks-native») y vuelve a importarlo para reintentarlo. El motivo exacto queda en el reporte de bug."
            }
            "$what se guardó como .pkg (${result.titleId.id} · ${DownloadsCleanup.formatBytes(result.sizeBytes)}) en el almacenamiento interno del launcher.\n\n" +
                installNote
        }
        is ImportResult.PkgNeedsLicense -> {
            val what = when (result.kind) {
                com.danielsnagustin.childoflight.launcher.model.PkgKind.GAME -> "El juego"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC -> "El DLC"
                com.danielsnagustin.childoflight.launcher.model.PkgKind.UPDATE -> "La actualización"
            }
            "$what se guardó como .pkg (${result.titleId.id} · ${DownloadsCleanup.formatBytes(result.sizeBytes)}), " +
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
        val storedPath = when (result) {
            is ImportResult.PkgStored -> result.storedPath
            is ImportResult.PkgNeedsLicense -> result.storedPath
            else -> return result
        }
        _installProgress.value = InstallProgress(null, "Instalando en el núcleo…")
        val installed = installStoredPkg(kind, titleId, dlcId)
        return installed.fold(
            onSuccess = {
                LauncherLog.append(context, "import", "DLC/update instalado en el núcleo: $dlcId (${titleId.id}).")
                // R6-P1-06: el .pkg guardado ya no hace falta: se borra
                // (con su guarda: solo si el contenido quedó instalado).
                // El work.bin se conserva siempre. El .pkg del juego no
                // pasa por aquí (se borra tras loadGame == 0).
                runCatching {
                    library.deleteStoredAddonPkg(java.io.File(storedPath), kind, titleId.id)
                }
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
        val result = if (kind == com.danielsnagustin.childoflight.launcher.model.PkgKind.DLC) {
            installer.installDlcPkg(dlcId) { percent, status ->
                _installProgress.value = InstallProgress(percent, status)
            }
        } else {
            installer.installUpdatePkg(titleId) { percent, status ->
                _installProgress.value = InstallProgress(percent, status)
            }
        }
        // R4-P2-7: el núcleo acaba de escribir en ux0/addcont/ o
        // ux0/patch/: el tamaño cacheado ya no vale.
        return result.onSuccess { library.invalidateInstalledSizes(titleId.id) }
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
                        // Ronda 0.2.65 (U-P2-7): ya no hay botón para
                        // instalar la app externa (el enlace a releases
                        // se quitó): el diálogo es informativo, el texto
                        // ya dice qué hacer (instalarla y volver a copiar).
                        _alert.value = AlertInfo(
                            "Los archivos se copiaron a Descargas/ChildOfLight/, pero la app externa no está instalada.\n\n" +
                                "Instálala y luego vuelve a pulsar «Copiar a la app externa».",
                            null,
                        )
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    _alert.value = AlertInfo("No se pudo entregar el .pkg a la app externa: ${e.message}")
                }
            }
            // R4-P1-10: la entrega solo toca Descargas: basta
            // re-listar las copias. Un refreshStatus() completo aquí
            // re-escaneaba juego, firmware y TRP por una copia a
            // Descargas. La lista queda fresca para Almacenamiento.
            refreshDownloadsCopies()
            downloadsCopiesDirty = false
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
        viewModelScope.launch(Dispatchers.IO) {
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
            // Ronda 0.2.65 (RG-P2-8): instalar a mano es querer Turnip
            // otra vez: se quita el marcador de "no auto-instalar".
            runCatching { bundledTurnipOptOutFile().delete() }
            val name = fileNameOf(uri)
            val result = turnipRepo.installDriverZip(uri, name)
            val current = settingsRepo.settingsFlow.first()
            result.onSuccess { savedName ->
                // H7: Turnip solo funciona en Adreno/Snapdragon. En Mali
                // (G99) instalar el zip no activa nada: el driver del
                // sistema sigue siendo el que manda.
                val gpu = settingsRepo.gpuInfoFlow.first()
                // Ronda 0.2.64 (P2-6 Mali): el vendor se comprueba
                // ANTES de instalar nada. Antes installDriverIntoCore()
                // corría antes del if y copiaba el zip DENTRO del núcleo
                // también en Mali (inerte ahí), y la rama no-Adreno
                // persistía turnipInstalled/version/customDriverName:
                // estado muerto que la UI de Mali no podía revertir.
                // Ahora en no-Adreno no se persiste nada de Turnip y
                // el zip recién copiado se borra.
                if (gpu?.vendor != GpuVendor.ADRENO) {
                    turnipRepo.deleteDriver()
                    // Ronda 0.2.65 (G-P2-7): una copia importada de otro
                    // teléfono podía traer flags Turnip obsoletos
                    // (turnipInstalled/version/customDriverName) aunque
                    // el zip se acabara de borrar: se limpian los tres,
                    // no solo el driver activo.
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = false,
                            turnipVersion = "",
                            customDriverName = "",
                            gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM,
                        ),
                    )
                    withContext(Dispatchers.Main) {
                        _message.value = "Turnip no se instaló: tu GPU es ${gpu?.shortLabel ?: "Mali"} y Turnip solo funciona en Adreno/Snapdragon. Se sigue usando el driver del sistema."
                    }
                    LauncherLog.append(context, "driver", "Turnip no instalado (GPU no Adreno: ${gpu?.shortLabel}); zip borrado")
                    return@onSuccess
                }
                // El nombre que va a custom-driver-name es el del driver
                // instalado en el núcleo; si no hay núcleo aún, el stem
                // del zip (mismo valor que devuelve installCustomDriver).
                val coreName = installDriverIntoCore()
                val driverName = coreName.ifEmpty { savedName.substringBeforeLast(".") }
                if (gpu.vendor == GpuVendor.ADRENO) {
                    // Ronda 0.2.65 (W-P1-3): si el núcleo ESTÁ presente
                    // y no instaló el driver (coreName vacío), NO se
                    // persiste "Turnip instalado + activo": el zip queda
                    // guardado (se puede reintentar desde Ajustes → GPU)
                    // pero el driver del sistema sigue mandando. Sin
                    // núcleo aún (el motor integrado no está en esta instalación), el zip guardado sí se
                    // marca instalado: se instala al lanzar el juego.
                    val coreRejected = Vita3kCoreBridge.available && coreName.isEmpty()
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = !coreRejected,
                            turnipVersion = savedName,
                            customDriverName = driverName,
                            gpuDriver = if (coreRejected) {
                                com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM
                            } else {
                                com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP
                            },
                        ),
                    )
                    // Ronda 0.2.64 (P1-3 Mali): el nuevo driver cambia
                    // la máscara de mapeo soportada: se vuelve a preguntar.
                    refreshMappingMask()
                    withContext(Dispatchers.Main) {
                        _message.value = if (coreRejected) {
                            "Turnip guardado ($savedName), pero el núcleo no pudo instalarlo: se usará el driver del sistema hasta reinstalarlo o relanzar el juego."
                        } else {
                            // Ronda 0.2.65 (A-P2-2): quitada la afirmación
                            // no verificada de que el núcleo limpiará la
                            // caché de shaders al cambiar de driver (sin
                            // evidencia en el pin: el único borrado de
                            // caché es al borrar una app, no al cambiar
                            // de driver).
                            "Turnip instalado: $savedName. El cambio se aplica en el siguiente lanzamiento."
                        }
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
                // Ronda 0.2.65 (RG-P2-8): si el usuario borró Turnip a
                // mano (deleteTurnipDriver), NO se reinstala solo al
                // relanzar: el marcador lo recuerda. Sin él, borrar
                // duraba hasta el próximo arranque.
                if (bundledTurnipOptOutFile().exists()) return@runCatching
                // Sin variante incluida para este modelo Adreno, no se
                // hace nada: el driver del sistema sigue mandando.
                if (BundledTurnipDriver.selectVariant(gpu.renderer) == null) return@runCatching
                val result = turnipRepo.installBundledAsset()
                result.onSuccess { installed ->
                    val coreName = installDriverIntoCore()
                    // Ronda 0.2.65 (W-P1-3): igual que installTurnip:
                    // núcleo presente y rechazo -> no se persiste activo.
                    if (Vita3kCoreBridge.available && coreName.isEmpty()) {
                        LauncherLog.append(context, "driver", "Driver incluido guardado (${installed.savedName}) pero el núcleo no lo instaló: no se activa.")
                        return@onSuccess
                    }
                    // Ronda 0.2.65 (G-P2-9): el driver incluido se
                    // instala ENCIMA del preset por defecto (Rendimiento
                    // 1x en Adreno), sin cambiar de perfil. Aplicar el
                    // perfil Calidad/Turnip entero subiría el primer
                    // arranque a 2x + FXAA sin que nadie lo pidiera, y
                    // el auto-revert solo salta tras una prueba de 30 s
                    // manual. El "Perfil modificado" ya no salta por el
                    // driver solo: presetProfileFields no incluye
                    // gpuDriver (el driver es ortogonal al preset).
                    settingsRepo.save(
                        current.copy(
                            turnipInstalled = true,
                            turnipVersion = installed.savedName,
                            customDriverName = coreName.ifEmpty { installed.savedName.substringBeforeLast(".") },
                            gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.TURNIP,
                        ),
                    )
                    // Ronda 0.2.65 (RG-P2-7): el driver nuevo cambia la
                    // máscara de mapeo soportada: se vuelve a preguntar
                    // (el manual y el cambio a Sistema ya lo hacían).
                    refreshMappingMask()
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

    /**
     * Marcador persistente "no volver a instalar el Turnip incluido"
     * (ronda 0.2.65, RG-P2-8). Lo crea deleteTurnipDriver() cuando el
     * usuario borra el driver y lo respeta ensureBundledTurnipDriver();
     * installTurnip() lo borra (instalar a mano es querer Turnip otra
     * vez). Un archivo en filesDir, no un campo nuevo de ajustes: el
     * modelo de ajustes es de otra área de la ronda.
     */
    private fun bundledTurnipOptOutFile() = File(context.filesDir, "turnip-bundled-opt-out")

    fun useSystemDriver() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsRepo.settingsFlow.first()
            settingsRepo.save(current.copy(gpuDriver = com.danielsnagustin.childoflight.launcher.model.GpuDriver.SYSTEM))
            _message.value = "Driver del sistema activado."
            // Ronda 0.2.64 (P1-3 Mali): cambiar de driver cambia la
            // máscara de mapeo soportada: se vuelve a preguntar.
            refreshMappingMask()
        }
    }

    // --- Jugar / benchmark ---

    // Ronda 0.2.66 (AUD-P2-2): aviso visible cuando la sesión anterior
    // del núcleo no se pudo destruir (-4) y los ajustes cambiados
    // puede que no se apliquen en la partida que se acaba de lanzar.
    private val staleSessionWarningText =
        " La sesión anterior del núcleo no se pudo destruir: es posible que los ajustes cambiados no se apliquen en esta partida. Cierra la app por completo y vuelve a intentarlo."

    fun play() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!tryBeginLaunch()) return@launch
            try {
            // Ronda 0.2.64 (H-4 tiempo de carga, T1/T2): cronómetro del
            // camino JUGAR → título → primer frame. t0 es el toque de
            // JUGAR (elapsedRealtime, monotónico); se pasa al lanzador
            // y a la actividad del juego, que registran T1 (título) y
            // T2 (primer frame presentado) en el mismo reloj.
            val launchStartedAtMillis = SystemClock.elapsedRealtime()
            val playStatus = _status.value
            LauncherLog.append(context, "launch", "JUGAR t=0ms (titleId=${playStatus.titleId?.id ?: "?"})")
            // P3 (crash ~15:06 sin stack trace): entrada de JUGAR en
            // el log con el estado que decide el lanzamiento, para que
            // el próximo reporte muestre hasta dónde llegó el intento.
            LauncherLog.append(context, "launch", formatLaunchAttempt(playStatus.titleId?.id, Vita3kCoreBridge.available, playStatus.firmwareInstalled))
            if (!Vita3kCoreBridge.available) {
                Vita3kCoreBridge.lastLoadError?.let {
                    LauncherLog.append(context, "launch", "Núcleo ausente: $it")
                }
            }
            val settings = settingsRepo.settingsFlow.first()
            // Ronda 0.2.65 (RG-P2-11): si la GPU guardada es el
            // fallback de una detección fallida, se re-intenta aquí,
            // en cada JUGAR, y se aplica el preset correcto si el
            // activo sigue siendo el del fallback.
            val gpu = recheckGpuDetectionIfFallback()
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
            // R4-P0-7: config.yml se escribe UNA sola vez por JUGAR,
            // aquí, antes del firmware: instalar el firmware inicializa
            // el núcleo y el núcleo lee config.yml al iniciar; si se
            // escribiera después, el juego arrancaría con los ajustes
            // del lanzamiento anterior. launch() ya NO lo reescribe
            // (configAlreadyWritten) y el firmware solo lo reescribe si
            // de verdad instala un PUP y el contenido cambió
            // (writeConfig es idempotente). Si esta escritura falla,
            // configWritten queda false y launch() lo escribe como antes.
            var configWritten = false
            runCatching {
                // Ronda 0.2.66 (G11-02): con máscara conocida se emite
                // el mapeo efectivo, no el pedido.
                Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu, _supportedMappingMask.value)
                configWritten = true
            }.onFailure {
                LauncherLog.append(context, "config", "No se pudo escribir config.yml antes del firmware: ${it.message}")
            }
            // Firmware: si hay PUP guardado y núcleo presente, instalarlo
            // ahora (best-effort, nunca bloquea el lanzamiento).
            ensureFirmwareForEmbedded()
            // R4-P0-8: el escaneo anterior a este launch(), para saber
            // después si launchImpl escaneó de verdad en este JUGAR
            // (si checkPrerequisites lanzara, lastScannedStatus seguiría
            // siendo el de un lanzamiento anterior y NO se reutiliza).
            val scanBeforeLaunch = launcher.lastScannedStatus
            // Instalación del .pkg en el núcleo (al jugar con .pkg
            // guardado): el % real del núcleo llega a installProgress
            // y se muestra en Import; al terminar se limpia.
            val outcome = launcher.launch(
                library, settings, sessionManager, gpu,
                onInstallProgress = { percent, status ->
                    _installProgress.value = InstallProgress(percent, status)
                },
                configAlreadyWritten = configWritten,
                launchStartedAtMillis = launchStartedAtMillis,
                // Ronda 0.2.66 (G11-02): el config de repuesto de
                // launchImpl también resuelve el mapeo efectivo.
                memoryMappingMask = _supportedMappingMask.value,
            )
            _installProgress.value = null
            LauncherLog.append(context, "launch", "JUGAR resultado: ${outcome::class.simpleName} (${SystemClock.elapsedRealtime() - launchStartedAtMillis}ms desde JUGAR)")
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
                    is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded -> {
                        // Ronda 0.2.65 (W-P1-5): el juego se lanzó pero un
                        // update/DLC quedó bloqueado (el agente 1 deja
                        // lastBlockedPkgPath con su ruta al fallar la
                        // instalación): el usuario debe enterarse y tener
                        // el botón de borrado, no solo una línea de log.
                        // lastBlockedPkgPath solo puede venir de este
                        // lanzamiento (con el juego validado se limpia;
                        // con el juego bloqueado el resultado sería
                        // EmbeddedFailed, no LaunchedEmbedded).
                        if (launcher.lastBlockedPkgPath != null) {
                            _alert.value = AlertInfo(
                                "El juego se lanzó, pero un update o DLC no se pudo instalar y quedó bloqueado. Puedes borrar ese .pkg para no volver a intentarlo en cada JUGAR, o reimportarlo en Importar." +
                                    if (launcher.staleSessionWarning) "\n\nAviso:$staleSessionWarningText" else "",
                                AlertAction.DELETE_BLOCKED_PKG,
                            )
                        } else {
                            // Sin Vulkan, el config.yml ya cayó a OpenGL solo;
                            // se avisa aquí porque es el momento en que importa.
                            _message.value = "Núcleo embebido: juego lanzado (${outcome.configPath})." +
                                if (gpu?.vulkanSupported == false) " Sin Vulkan en este teléfono: se usa OpenGL, puede ir más lento o con fallos gráficos." else "" +
                                if (launcher.staleSessionWarning) staleSessionWarningText else ""
                        }
                    }
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
                            // Ronda 0.2.65 (A-P2-1): loadGame -3 SIN .pkg
                            // guardado: el motivo dice "vuelve a importar
                            // el juego completo en Importar" y Reintentar
                            // no sirve de nada (no hay nada que reinstalar).
                            outcome.reason.contains("vuelve a importar el juego completo en Importar") -> AlertAction.GO_IMPORT
                            outcome.reason.contains("no se mueve nada para no mezclar dos instalaciones") -> AlertAction.RESOLVE_LEGACY_CONFLICT
                            // Ronda 0.2.64 (H-2 tiempo de carga): el .pkg
                            // ya se intentó reinstalar una vez y siguió
                            // sin validar: el botón es "Borrar ese .pkg",
                            // no reintentar (reintentar reinstala 1,14 GB
                            // cada vez). launcher.lastBlockedPkgPath lo
                            // pone tryLaunchEmbedded al bloquear.
                            launcher.lastBlockedPkgPath != null -> AlertAction.DELETE_BLOCKED_PKG
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
                        // o ir a importar el juego. (Ronda 0.2.65, U-P2-7:
                        // "instalar la app externa" ya no es una acción: el
                        // enlace a releases se quitó; ese bloqueo queda como
                        // aviso sin botón.)
                        // P0-1: el bloqueo por falta de licencia lleva a
                        // Importar, donde la tarjeta «Falta la licencia»
                        // ofrece elegir el work.bin (el pendiente ya se
                        // reconstruyó antes del diálogo).
                        val action = when {
                            outcome.reason.contains("Falta el firmware") -> AlertAction.GO_IMPORT
                            outcome.reason.contains("Falta la licencia (work.bin)") -> AlertAction.GO_IMPORT
                            outcome.reason.contains("motor integrado") -> AlertAction.RETRY_LAUNCH
                            outcome.reason.contains("El juego no está instalado") -> AlertAction.GO_IMPORT
                            else -> null
                        }
                        _alert.value = AlertInfo(outcome.reason, action)
                    }
                }
            }
            // R4-P0-8: el refresco final solo re-escanea si el
            // lanzamiento pudo cambiar algo en disco (instalación del
            // .pkg, migración del layout o intento embebido fallido
            // tras instalar). Con Blocked, vía externa o núcleo sin
            // JNI no se instaló nada: se reutiliza el GameStatus que
            // launchImpl ya escaneó (launcher.lastScannedStatus) y
            // solo se re-lee el firmware (lectura barata del marcador),
            // que ensureFirmwareForEmbedded sí pudo instalar en este
            // JUGAR. La UI queda con el estado fresco sin otro escaneo.
            if (outcome is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded ||
                outcome is Vita3KLauncher.LaunchOutcome.EmbeddedFailed
            ) {
                refreshStatus()
            } else {
                // Solo se reutiliza si launchImpl escaneó EN ESTE JUGAR
                // (cada scanStatus construye un GameStatus nuevo: la
                // referencia cambia; si no cambió, el escaneo falló y
                // se conserva el _status que ya había, sin pisarlo con
                // un escaneo viejo de otro lanzamiento).
                val freshScan = launcher.lastScannedStatus
                if (freshScan != null && freshScan !== scanBeforeLaunch) {
                    _status.value = freshScan
                }
                runCatching { FirmwareResolver.resolve(library.vitaRoot) }
                    .onSuccess { _firmwareStatus.value = it }
            }
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
            // Ronda 0.2.55 P1-5: al volver del juego los saves pueden
            // haber cambiado (el núcleo escribe en ellos): se olvida
            // la caché de listSaves() para que el próximo escaneo los
            // re-liste frescos. En el resto de refrescos la caché manda.
            _status.value.titleId?.id?.let { library.invalidateSaves(it) }
            sessionManager.notePlayEnded()
            // Ronda 0.2.65 (W-P1-5): un addon que falló en la instalación
            // aplazada (tras volver del juego) no abre diálogo por sí
            // mismo: al volver, si quedó un .pkg bloqueado (el lanzador
            // deja lastBlockedPkgPath con su ruta y titleId null a
            // propósito: es el addon, no el juego), se ofrece aquí el
            // borrado. Si el usuario ya lo borró, el puntero es null y
            // no se muestra nada. El borrado por ruta + limpieza de
            // reintentos por nombre de archivo vive en
            // launcher.deleteBlockedPkg() (clearEntriesForFile).
            if (launcher.lastBlockedPkgPath != null) {
                withContext(Dispatchers.Main) {
                    _alert.value = AlertInfo(
                        "Un update o DLC no se pudo instalar y quedó bloqueado. Puedes borrar ese .pkg para no volver a intentarlo en cada JUGAR, o reimportarlo en Importar.",
                        AlertAction.DELETE_BLOCKED_PKG,
                    )
                }
            }
            // Prueba de 30 s: si el juego midió el FPS automáticamente
            // (EmbeddedGameActivity muestrea getFps cada segundo y lo
            // guarda al salir), se registra solo; si no hay medición,
            // queda el registro manual desde Diagnóstico.
            val pending = _pendingBenchmark.value
            // Ronda 0.2.65 (RG-P2-5): sin prueba pendiente, el FPS
            // muestreado igual se revisa para el auto-revert: una
            // prueba descartada a mitad de partida seguía muestreando
            // y su FPS se perdía sin revisar nunca.
            if (pending == null) {
                val sampledFps = settingsRepo.takeAutoBenchmarkFps()
                if (sampledFps != null && sampledFps.isFinite() && sampledFps in 1f..240f) {
                    maybeAutoRevertFromSampledFps(sampledFps)
                }
                return@launch
            }
            // P2-A2: un pendiente de hace más de 15 min está caducado:
            // se descarta sin registrar nada (el FPS automático que
            // hubiera se consume y se tira, para que no lo herede una
            // prueba posterior).
            if (isBenchmarkPendingStale(pending.startedAtMillis)) {
                _pendingBenchmark.compareAndSet(pending, null)
                val sampledFps = settingsRepo.takeAutoBenchmarkFps()
                if (sampledFps != null && sampledFps.isFinite() && sampledFps in 1f..240f) {
                    maybeAutoRevertFromSampledFps(sampledFps)
                }
                LauncherLog.append(context, "benchmark", "Prueba pendiente caducada (>15 min): descartada sin registrar.")
                return@launch
            }
            val autoFps = settingsRepo.takeAutoBenchmarkFps() ?: return@launch
            if (!autoFps.isFinite() || autoFps < 1f || autoFps > 240f) return@launch
            completeBenchmark(autoFps, BenchmarkSource.AUTO_MEASURED, pending)
        }
    }

    /**
     * Sincroniza los trofeos ganados en la última partida con el
     * progreso local (Ronda 0.2.57, puente JNI de trofeos). La llama
     * MainActivity.onResume al volver del juego, junto a
     * noteReturnedFromGame, solo cuando la Activity del juego ya no
     * sigue viva. Solo lectura del TROPHY.TRP real del núcleo: los
     * trofeos SOLO se ganan jugando; no hay marcado manual.
     *
     * Flujo: EmbeddedGameActivity capturó las colecciones nativas en
     * onDestroy ANTES de destroySession (la lectura JNI exige sesión
     * viva: sin emuenv devuelve vacío) y las dejó en
     * TrophySyncPending. Aquí se consumen (take vacía el hueco: una
     * segunda vuelta a Home no re-anuncia lo mismo), se mapean por
     * nombre en inglés exacto a números locales 1..20 y, por cada
     * trofeo ganado, achievementsRepo.unlock() guarda el desbloqueo
     * con el instante real del núcleo; solo si unlock() devuelve true
     * (desbloqueo nuevo) se anuncia el popup, con secuencia
     * estrictamente creciente. Un trofeo nativo sin correspondencia
     * se salta y queda en el log, nunca se inventa un mapeo.
     *
     * Nunca tumba la UI ni el regreso a Home: todo va en try/catch y
     * un fallo del puente solo deja el progreso local como estaba
     * (el diagnóstico TRP de Logros sigue disponible). Sin partida
     * embebida (vía externa, primer arranque) no hay nada pendiente
     * y vuelve sin hacer nada.
     *
     * Ronda 0.2.70, P1-5 (a): además de onResume, esta sync la
     * dispara el aviso de TrophySyncPending.store() (oyente
     * registrado en init): la captura en onDestroy corre después
     * del onResume que consume, así que sin el aviso el popup no
     * aparecía en esa vuelta. 7-P2-1: el take() vacío se registra
     * cuando se venía de una partida (fromGame), para distinguir
     * "sin pendiente" de "la captura aún no corrió"; un take con
     * colecciones pero 0 desbloqueos también queda en el log.
     * 7-P2-2: cada colección sincronizada registra su npComId y
     * las de otro juego se descartan si se conoce el esperado.
     *
     * Ronda 0.2.70, R-3 (tercera pasada): el pendiente también vive
     * en disco (TrophySyncPending lo persiste en store()): si el
     * proceso murió con el juego en primer plano, este mismo
     * take() lo relee en el próximo arranque y la sync desbloquea
     * lo ganado (unlock() es idempotente: reintentar no duplica ni
     * re-anuncia). El archivo solo se borra tras una sync con
     * éxito; si falla, queda para el próximo intento.
     *
     *
     * R7-P1-06: si en una partida se ganan varios trofeos nuevos, se
     * encolan en orden por número (unlockPopupQueue) y el popup
     * muestra uno tras otro: al descartar uno aparece el siguiente
     * hasta vaciar la cola. Ningún desbloqueo pierde su popup.
     */
    fun syncTrophiesFromGame(fromGame: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            trophySyncMutex.withLock {
                val collections = TrophySyncPending.take()
                if (collections == null) {
                    // 7-P2-1: solo se registra al volver de una
                    // partida; en un arranque normal no hay nada
                    // pendiente y no es noticia (evita spam en el log).
                    if (fromGame) {
                        LauncherLog.append(context, "trophies", "Sincronización de trofeos: sin colecciones pendientes al volver del juego (la captura aún no corrió o no hubo trofeos que capturar)")
                    }
                    return@withLock
                }
                runCatching {
                    val expectedNpComId = runCatching {
                        _status.value.titleId?.id?.let { TrophyStoreReader.resolveNpComId(library.vitaRoot, it) }
                    }.getOrNull()
                    for (collection in collections) {
                        LauncherLog.append(context, "trophies", "Colección de trofeos del núcleo: npComId=${collection.npComId.ifBlank { "<vacío>" }} totalCount=${collection.totalCount} desbloqueados=${collection.unlockedCount}")
                    }
                    val result = mapNativeTrophies(collections, expectedNpComId = expectedNpComId)
                    for (skippedCollection in result.skippedCollections) {
                        LauncherLog.append(context, "trophies", "Colección de trofeos descartada: npComId=$skippedCollection no es el del juego instalado (esperado $expectedNpComId)")
                    }
                    if (result.unlocked.isEmpty() && result.skippedUnknownNames.isEmpty() && result.skippedCollections.isEmpty()) {
                        LauncherLog.append(context, "trophies", "Sincronización de trofeos: ${collections.size} colecciones sin ningún trofeo ganado nuevo que mapear")
                    }
                    for (skipped in result.skippedUnknownNames) {
                        LauncherLog.append(context, "trophies", "Trofeo del núcleo sin correspondencia local, se salta: $skipped")
                    }
                    // Ronda 0.2.65 (W-P1-2): los saltos ya no quedan solo
                    // en el log: si el TROPCONF viene en otro idioma,
                    // los 20 pueden quedar en 0/20 sin que nadie lo vea.
                    // Aviso visible con el mecanismo de mensajes del VM.
                    if (result.skippedUnknownNames.isNotEmpty()) {
                        _message.value = "Algunos logros del juego no se reconocieron (${result.skippedUnknownNames.size}); el progreso puede estar incompleto."
                    }
                    for (entry in result.unlocked) {
                        if (achievementsRepo.unlock(entry.number, entry.unlockedAtMillis)) {
                            unlockAnnouncer.announce(entry.number, entry.unlockedAtMillis, trophySyncSeq.incrementAndGet())
                                ?.let {
                                    unlockPopupQueue.enqueue(it)
                                    _justUnlocked.value = unlockPopupQueue.current
                                }
                        }
                    }
                }.onFailure { e ->
                    LauncherLog.append(context, "trophies", "Sincronización de trofeos fallida (el progreso local no cambia): ${e.javaClass.simpleName}: ${e.message}")
                }.onSuccess {
                    // R-3 (tercera pasada): ack del pendiente
                    // persistido: solo tras desbloquear con éxito se
                    // borra el archivo. Si la sync falló, el archivo
                    // queda y el próximo arranque (o el rechequeo)
                    // reintenta. clearPersisted() borra solo si el
                    // archivo sigue siendo el consumido (huella): un
                    // store() nuevo entretanto no se pierde.
                    TrophySyncPending.clearPersisted()
                }
                // R7-P2-05: el contador "Logros: X/20" de la sesión
                // activa se recalcula DESPUÉS de desbloquear (antes
                // solo se refrescaba tras reset/import: tras una
                // partida con desbloqueos quedaba viejo hasta el
                // próximo snapshot). Best-effort, nunca tumba la sync.
                sessionManager.refreshActiveTrophyCount()
            }
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
            // R4-P1-10: sin refreshStatus(): crear una sesión no toca
            // nada que GameStatus lea (las sesiones viven en DataStore
            // y snapshots; los saves se leen de la savedata viva).
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
            // R4-P1-10: sin refreshStatus(), igual que createSession:
            // borrar una sesión no toca nada que GameStatus lea.
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
            // Ronda 0.2.64 (H-4 tiempo de carga, T1/T2): mismo t0 que
            // play(): el toque que inicia la prueba.
            val launchStartedAtMillis = SystemClock.elapsedRealtime()
            LauncherLog.append(context, "launch", "Prueba 30 s t=0ms (titleId=${_status.value.titleId?.id ?: "?"})")
            val settings = settingsRepo.settingsFlow.first()
            // Ronda 0.2.65 (RG-P2-11): igual que en play(): si la GPU
            // guardada es el fallback de una detección fallida, se
            // re-intenta en cada prueba.
            val gpu = recheckGpuDetectionIfFallback()
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
            // R4-P0-7: config.yml se escribe UNA sola vez por prueba,
            // aquí, antes del firmware, igual que en play(). launch()
            // ya NO lo reescribe (configAlreadyWritten); si esta
            // escritura falla, el flag queda false y launch() lo escribe.
            var configWritten = false
            runCatching {
                // Ronda 0.2.66 (G11-02): con máscara conocida se emite
                // el mapeo efectivo, no el pedido (igual que en play()).
                Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu, _supportedMappingMask.value)
                configWritten = true
            }
            // Firmware: igual que en play(), instalar el PUP guardado
            // en el núcleo si está presente (best-effort, no bloquea).
            ensureFirmwareForEmbedded()
            // R4-P0-8: el pre-chequeo de arriba se pasa a launch() para
            // que launchImpl reutilice su GameStatus en vez de
            // re-escanear; el firmware se re-resuelve dentro porque
            // ensureFirmwareForEmbedded pudo instalarlo entre medias.
            val outcome = launcher.launch(
                library, settings, sessionManager, gpu,
                benchmark = true,
                configAlreadyWritten = configWritten,
                prerequisites = pre,
                launchStartedAtMillis = launchStartedAtMillis,
                // Ronda 0.2.66 (G11-02): el config de repuesto de
                // launchImpl también resuelve el mapeo efectivo.
                memoryMappingMask = _supportedMappingMask.value,
            )
            LauncherLog.append(context, "launch", "Prueba 30 s resultado: ${outcome::class.simpleName} (${SystemClock.elapsedRealtime() - launchStartedAtMillis}ms desde el toque)")
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
                // Ronda 0.2.65 (W-P1-5): igual que en play(): un addon
                // bloqueado durante la prueba también avisa con el
                // botón de borrado al volver.
                if (outcome is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded && launcher.lastBlockedPkgPath != null) {
                    _alert.value = AlertInfo(
                        "La prueba está en marcha, pero un update o DLC no se pudo instalar y quedó bloqueado. Puedes borrar ese .pkg para no volver a intentarlo en cada JUGAR, o reimportarlo en Importar.",
                        AlertAction.DELETE_BLOCKED_PKG,
                    )
                }
                _message.value = when (outcome) {
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternal ->
                        "Prueba de 30 s en marcha. Juega 30 segundos, mira el FPS del overlay y al volver regístralo aquí."
                    is Vita3KLauncher.LaunchOutcome.LaunchedExternalFirmwareWarning ->
                        "Prueba de 30 s en marcha. ${Vita3KLauncher.FIRMWARE_WARNING_EXTERNAL}"
                    is Vita3KLauncher.LaunchOutcome.LaunchedEmbedded ->
                        "Prueba de 30 s en marcha (núcleo embebido). El FPS se mide solo mientras juegas; al volver se registra automáticamente." +
                            if (gpu?.vulkanSupported == false) " Sin Vulkan en este teléfono: se usa OpenGL, puede ir más lento." else "" +
                            // Ronda 0.2.66 (AUD-P2-2): aviso visible si la
                            // sesión anterior no se pudo destruir (-4).
                            if (launcher.staleSessionWarning) staleSessionWarningText else ""
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
     * Ronda 0.2.65 (RG-P2-5): el auto-revert a 1x solo existía dentro
     * de la prueba de 30 s (completeBenchmark): un muestreo sin prueba
     * pendiente (descartada a mitad de partida, pendiente caducado)
     * guardaba su FPS y nunca lo revisaba. Tras cada muestreo, si la
     * resolución efectiva es >= 2x y el FPS baja de 28, se vuelve a 1x
     * aunque no haya prueba que registrar. Misma regla que la prueba
     * (shouldAutoRevertTo1x por resolución efectiva) y mismo destino
     * por GPU (autoRevertTargetProfile). Devuelve true si revirtió.
     */
    private suspend fun maybeAutoRevertFromSampledFps(fps: Float): Boolean {
        val settings = settingsRepo.settingsFlow.first()
        val effectiveResolution = effectiveSettings(settings).resolutionMultiplier
        if (!shouldAutoRevertTo1x(fps, effectiveResolution, settings.autoRevertTo1xIfBelow28Fps)) return false
        if (settings.safeMode) deleteSettingsSnapshot(safeModeSnapshotFile())
        val gpu = settingsRepo.gpuInfoFlow.first()
        val targetProfile = autoRevertTargetProfile(gpu)
        settingsRepo.applyPreset(targetProfile, current = settings)
        _message.value = "FPS medio: $fps fps jugando. Menos de 28 fps con 2x: vuelto a 1x automáticamente (preset ${targetProfile.label})."
        LauncherLog.append(context, "benchmark", "Auto-revert a 1x fuera de prueba: $fps fps, preset ${targetProfile.name}")
        return true
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
        // Ronda 0.2.64 (P1-3 Adreno + H-05): la regla es por resolución
        // EFECTIVA (un 2x puesto a mano sobre un perfil 1x también
        // revierte) y el destino depende de la GPU: ADRENO_PERFORMANCE
        // en Adreno, G99_RECOMMENDED en G99/Mali. Antes siempre
        // G99_RECOMMENDED y solo para el perfil G99_QUALITY.
        val effectiveResolution = effectiveSettings(settings).resolutionMultiplier
        val revert = shouldAutoRevertTo1x(fps, effectiveResolution, settings.autoRevertTo1xIfBelow28Fps)
        val completed = pending.copy(fpsAvg = fps, source = source, autoReverted = revert)
        settingsRepo.recordBenchmark(completed)
        val how = if (source == BenchmarkSource.AUTO_MEASURED) "medido automáticamente por el juego" else "registrado a mano"
        if (revert) {
            // R6-P2-10: igual que al aplicar un preset a mano: con el
            // modo seguro ON, el snapshot queda invalidado o al
            // desactivarlo resucitarían los valores viejos.
            if (settings.safeMode) deleteSettingsSnapshot(safeModeSnapshotFile())
            val gpu = settingsRepo.gpuInfoFlow.first()
            // Ronda 0.2.65 (RG-P2-14): destino por GPU real, no siempre
            // G99 (en PowerVR/Xclipse volvía a un preset de Mali).
            val targetProfile = autoRevertTargetProfile(gpu)
            settingsRepo.applyPreset(targetProfile, current = settings)
            _message.value = "FPS medio: $fps ($how). Menos de 28 fps con 2x: vuelto a 1x automáticamente (preset ${targetProfile.label})."
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
            clearUnlockQueue()
            // R2-P2-30: el contador unlockedTrophies del índice de
            // sesiones se actualiza ya (antes quedaba viejo hasta el
            // próximo snapshot).
            sessionManager.refreshActiveTrophyCount()
            _message.value = "Progreso de logros borrado (local, no afecta a PSN porque nunca se sincroniza)."
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
                // R7-P2-01: un archivo sin firma válida (escrito a mano,
                // manipulado o respaldo viejo pre-0.2.58) se rechaza
                // entero: no se toca el progreso y se dice claro.
                if (result.rejectedInvalidSignature) {
                    withContext(Dispatchers.Main) {
                        _message.value = "Ese archivo no es un respaldo válido de logros: le falta la firma o fue modificado. Solo se pueden importar respaldos exportados por esta app."
                    }
                    return@launch
                }
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
        viewModelScope.launch(Dispatchers.IO) {
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
     * 0.2.68 segunda pasada: se repone con los valores de fábrica del
     * perfil ACTIVO, no siempre con los del default: una mano tiene
     * su propio stick (174 dp en (0.05, 0.70)) y su propia zona
     * (104 dp en (0.78, 0.80)); con los del default saldría un stick
     * de 177 dp en (0.035, 0.700) y una zona de 120 dp en (0.781,
     * 0.813) dentro de una mano. Un perfil personalizado no tiene
     * fábrica propia y usa el default, como antes.
     */
    fun restoreMissingTouchControl(kind: String) {
        viewModelScope.launch {
            val layout = touchRepo.layoutFlow.first()
            if (layout.any { it.kind == kind }) {
                _message.value = "Ya existe en este perfil."
                return@launch
            }
            val activeId = touchRepo.activeProfileIdFlow.first()
            val defaults = when (activeId) {
                "one-hand" -> com.danielsnagustin.childoflight.launcher.data.oneHandLayout()
                "external-gamepad" -> com.danielsnagustin.childoflight.launcher.data.externalGamepadLayout()
                else -> com.danielsnagustin.childoflight.launcher.data.defaultTouchLayout()
            }
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
            if (ok) {
                // P2-26: el id del perfil borrado quedaba para siempre en
                // touchOverlayHiddenProfileIds (la lista de perfiles
                // ocultos) y se acumulaba. Se purga al borrar.
                val current = settingsRepo.settingsFlow.first()
                if (id in current.touchOverlayHiddenProfileIds) {
                    settingsRepo.save(
                        current.copy(touchOverlayHiddenProfileIds = current.touchOverlayHiddenProfileIds - id),
                    )
                }
            }
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
        viewModelScope.launch(Dispatchers.IO) { uiRepo.save(prefs) }
    }

    /**
     * R4-P2-6: marca la intro de Home como ya vista EN ESTA INSTALACIÓN
     * (UiPreferences.homeIntroSeen, persistente en DataStore). La llama
     * HomeScreen al terminar su intro de ~900 ms (onIntroDone). Si ya
     * estaba marcada, no se escribe nada: la escritura DataStore solo
     * ocurre una vez por instalación.
     */
    fun markHomeIntroSeen() {
        viewModelScope.launch(Dispatchers.IO) {
            // R5-A1-4: escritura atómica en el repo (una sola clave
            // dentro de edit{}), sin read-modify-write en el VM: antes
            // se leía el snapshot entero y se guardaba, y un cambio de
            // personalización en el mismo instante se podía perder.
            uiRepo.markHomeIntroSeen()
        }
    }

    fun resetAllCustomization() {
        viewModelScope.launch(Dispatchers.IO) {
            // R5-A1-3: el reset total borraba homeIntroSeen y la intro
            // volvía a sonar. Se lee ANTES del reset y, si ya estaba
            // vista, se vuelve a marcar DESPUÉS (misma marca atómica
            // que markHomeIntroSeen()).
            // R6-P1-11: lo mismo vale para los presets ocultos con
            // "Limpiar no usados": resetAll() los borra y el reset
            // total los re-listaba sin aviso (el reset por categoría
            // ya los preserva a propósito: CategoryReset.kt). Se leen
            // ANTES y se reponen DESPUÉS.
            val beforeReset = runCatching { uiRepo.uiFlow.first() }.getOrNull()
            uiRepo.resetAll()
            if (beforeReset?.homeIntroSeen == true) uiRepo.markHomeIntroSeen()
            val hiddenPresets = beforeReset?.hiddenPresets ?: emptySet()
            if (hiddenPresets.isNotEmpty()) uiRepo.hideGpuPresets(hiddenPresets)
            touchRepo.reset()
            _message.value = "Personalización restablecida: tema, acento, fondo, texto, idioma, portada y controles. Los perfiles de control creados por ti se borraron; solo quedan los predefinidos."
        }
    }

    // --- Restauración por categoría (Ola 3, Etapa 9b) ---
    // Restablece SOLO esa categoría (ver CategoryReset.kt). Almacenamiento
    // no tiene ajustes: son archivos, y el mensaje lo dice en vez de
    // fingir que se restableció algo. El firmware ya no es una categoría
    // de Ajustes: vive en Importar (juego y DLCs).
    fun resetCategory(category: SettingsCategory) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!categoryHasResettableSettings(category)) {
                _message.value = when (category) {
                    SettingsCategory.STORAGE -> "Almacenamiento no tiene ajustes que restablecer: son archivos (copias en Descargas, juego, DLCs), no ajustes."
                    else -> "General no tiene ajustes de preset que restablecer: la sesión PSN y la app externa son interruptores tuyos, no valores del preset."
                }
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
            // R6-P2-10: igual que al aplicar un preset: restablecer
            // otra categoría con el modo seguro ON y conservar el
            // snapshot resucitaría los valores viejos al desactivarlo.
            // (Diagnóstico ya borra el snapshot arriba, al restaurar.)
            if (current.safeMode && category != SettingsCategory.DIAGNOSTICS) {
                deleteSettingsSnapshot(safeModeSnapshotFile())
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
                // R6-P2-12: "valores por defecto / del preset": el reset
                // pone valores del preset donde el preset los define y
                // defaults de LauncherSettings() donde no. Ronda 0.2.70
                // (11-P1-4): las elecciones manuales que aplicar un
                // preset conserva (turbo, audio, auto-revert, modo
                // rendimiento, controles, cerrar apps) también se
                // conservan aquí y NO se nombran como restablecidas.
                // Decir solo "valores del preset" era impreciso.
                // R6-P2-09: avisar si Turnip se conservó aunque el
                // preset diga Sistema.
                "Categoría ${category.title} restablecida a los valores por defecto / del preset ${current.activeProfile.label}.$turnipNote${turnipKeptNote(reset, presetSettings(current.activeProfile))}"
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
                // R5-A2-8: el modo seguro nunca se exporta: una copia
                // con safeMode=true guardaba los valores forzados
                // 1x/SDL como ajustes normales sin snapshot, y al
                // importarla los ajustes previos se perdían.
                // Ronda 0.2.70 (P1-2): settingsForExport exporta los
                // valores previos del snapshot del modo seguro (o los
                // previos a "Corregir audio" si está pendiente de
                // deshacer), siempre con safeMode=false; solo si no hay
                // snapshot se exportan los guardados.
                val safeSnapshot = if (current.safeMode) readSettingsSnapshot(safeModeSnapshotFile()) else null
                val audioSnapshot = readSettingsSnapshot(audioFixSnapshotFile())
                val text = encodeSettingsBackup(settingsForExport(current, safeSnapshot, audioSnapshot), currentUi)
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
            // R5-A2-8: una copia vieja puede traer safeMode=true (sin
            // snapshot que restaurar: los valores forzados quedarían
            // como permanentes al desactivarlo). Se importa como false
            // y el mensaje lo dice.
            val importedSafeMode = backupJsonHasSafeModeEnabled(text)
            val importedSettings = if (importedSafeMode) settings.copy(safeMode = false) else settings
            // P1-8: una copia de otro teléfono (o de un estado huérfano)
            // puede traer gpuDriver=TURNIP; en este teléfono no tendría
            // forma de revertirse desde la UI.
            val gpu = settingsRepo.gpuInfoFlow.first()
            val guarded = guardTurnipDriver(importedSettings, gpu?.vendor)
            settingsRepo.save(guarded)
            uiRepo.save(ui)
            withContext(Dispatchers.Main) {
                _message.value = "Copia de seguridad importada: ajustes y personalización restaurados." +
                    if (importedSafeMode) {
                        " La copia traía el modo seguro activado (copias antiguas): se importó desactivado para no perder tus ajustes anteriores."
                    } else {
                        ""
                    } +
                    if (guarded.gpuDriver != importedSettings.gpuDriver) {
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
            // Ronda 0.2.65 (RG-P2-8): borrar Turnip es una decisión del
            // usuario: el marcador evita que ensureBundledTurnipDriver
            // lo reinstale solo al relanzar.
            runCatching { bundledTurnipOptOutFile().writeText("Borrado por el usuario: no auto-instalar el Turnip incluido.\n") }
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

    /**
     * Title ID fresco (no el estado de la UI, que puede ir desactualizado).
     * R4-P0-8: lectura LIGERA, sin scanStatus() completo: el título
     * extraído sale de app/<TITLE>/eboot.bin y el del .pkg de su header
     * (Content ID). Son las mismas fuentes que usa scanStatus() para
     * titleId, sin tamaños, DLCs, saves ni MediaStore.
     */
    private fun freshTitleId(): String? {
        freshExtractedTitleId()?.let { return it }
        return freshPkgTitleId()
    }

    /**
     * Title ID del .pkg guardado, fresco: solo la parte .pkg de
     * freshTitleId() (header del .pkg, Content ID). R4-P1-11: los
     * llamadores que YA leyeron el título extraído usan esto para el
     * mensaje ".pkg" sin volver a leer el extraído: antes
     * freshTitleId() re-llamaba a freshExtractedTitleId() y se pagaba
     * la lectura ligera dos veces por cada exportar/importar/borrar
     * saves sin juego extraído.
     */
    private fun freshPkgTitleId(): String? {
        for (candidate in TitleId.entries) {
            val pkg = runCatching { library.findPkgGameFile(candidate.id) }.getOrNull() ?: continue
            val headerTitle = runCatching {
                pkg.inputStream().use { PkgHeaderParser.parse(it) }
            }.getOrNull()?.getOrNull()?.titleId
            return (headerTitle ?: candidate).id
        }
        return null
    }

    /** Title ID del juego EXTRAÍDO, fresco. null si el juego es solo .pkg. */
    private fun freshExtractedTitleId(): String? =
        runCatching { sessionManager.lightExtractedTitleId() }.getOrNull()

    fun exportSaves(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // Los saves del launcher solo existen con el juego extraído:
            // con un .pkg los saves viven dentro de Vita3K.
            val title = freshExtractedTitleId()
            if (title == null) {
                withContext(Dispatchers.Main) {
                    // R4-P1-11: el extraído ya se leyó arriba (null):
                    // solo falta la parte .pkg, sin re-leer el extraído.
                    _message.value = if (freshPkgTitleId() != null) {
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
                    // R4-P1-11: el extraído ya se leyó arriba (null):
                    // solo falta la parte .pkg, sin re-leer el extraído.
                    _message.value = if (freshPkgTitleId() != null) {
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
            // R9-P1-01: el borrado nuclear también resetea el progreso
            // global de logros. Antes sobrevivía y la "Partida 1" nueva
            // heredaba X/20 sin haber jugado (snapshotExtras copia el
            // progreso global vigente), y esos trofeos ya no se podían
            // volver a ganar porque syncTrophiesFromGame solo desbloquea.
            achievementsRepo.resetAll()
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

    // R4-P1-12: lecturas en VIVO (las piden explícitamente Diagnóstico
    // y el reporte de bug) que además refrescan la caché, para que el
    // próximo refreshStatus() no repita el IPC si sigue fresca.
    fun externalVita3kVersion(): String? {
        val fresh = runCatching { launcher.externalVita3kVersion() }.getOrNull()
        externalVersionCache = fresh
        externalVersionCacheAtMillis = System.currentTimeMillis()
        externalVersionCacheKnown = true
        return fresh
    }
    fun embeddedCorePresent(): Boolean = launcher.embeddedCorePresent()
    fun isBatteryOptimizationDisabled(): Boolean {
        val fresh = runCatching { launcher.isBatteryOptimizationDisabled() }.getOrDefault(false)
        batteryOptCache = fresh
        batteryOptCacheAtMillis = System.currentTimeMillis()
        batteryOptCacheKnown = true
        return fresh
    }
    fun batteryOptimizationIntent() = launcher.batteryOptimizationIntent()

    fun refreshBatteryOptimizationState() {
        viewModelScope.launch {
            // Se lee el estado REAL (PowerManager) al volver del diálogo
            // del sistema, lo conceda el usuario o no. No se persiste
            // nada: el campo guardado no lo leía nadie y el valor vivo
            // es el del sistema (ronda de verificación 2026-11-08).
            // R4-P1-12: la lectura en vivo refresca la caché, igual que
            // isBatteryOptimizationDisabled().
            val fresh = runCatching { launcher.isBatteryOptimizationDisabled() }.getOrDefault(false)
            batteryOptCache = fresh
            batteryOptCacheAtMillis = System.currentTimeMillis()
            batteryOptCacheKnown = true
            _batteryOptDisabled.value = fresh
        }
    }

    // R5-A4-3: la línea de licencia del reporte. Antes decía siempre
    // "Licencia work.bin: false" cuando el .pkg guardado ya se había
    // borrado tras un JUGAR con éxito (la licencia real ya está
    // registrada en el núcleo): el campo licenseInstalledInCore se
    // calcula en scanStatus() mirando ux0/license/<TITLE>/<CONTENT_ID>.rif.
    private fun licenseLine(status: GameStatus): String = when {
        status.gameExtracted && status.licenseInstalledInCore -> "Licencia: instalada en el núcleo"
        status.pkgInstalled -> "Licencia work.bin: ${status.pkgLicensePresent}"
        else -> "Licencia: sin dato"
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
            appendLine("Extraído: ${status.gameExtracted} | .pkg: ${status.pkgTitleId?.id ?: "-"} (${status.pkgContentId ?: "-"}) | ${licenseLine(status)}")
            appendLine("Entregado a la app externa: ${settings.pkgDeliveredToVita3k} | Instalado en la app externa (confirmado por el usuario): ${settings.pkgInstalledInVita3k}")
            appendLine("DLCs: ${status.dlcCount} | Update: ${status.updateInstalled} | Firmware: ${status.firmwareInstalled}")
            appendLine("Saves: ${status.saves.size} archivos")
            appendLine()
            appendLine("--- Núcleo ---")
            appendLine("Núcleo embebido: ${if (embeddedCorePresent()) "presente" else "NO presente"}")
            appendLine("App externa: ${externalVita3kVersion() ?: "no instalada"}")
            appendLine()
            appendLine("--- Mando virtual y pausa ---")
            // Leído al exportar el reporte (no durante la partida): el
            // nº de gamepads y la pausa son el estado en ese momento;
            // los contadores son acumulados del proceso (toda la
            // sesión de juego desde que se abrió la app).
            val gamepadCount = Vita3kCoreBridge.overlayGamepadCount()
            appendLine("Gamepads SDL: ${if (gamepadCount < 0) "sin dato" else "$gamepadCount"} | Mando virtual del juego: ${if (Vita3kCoreBridge.overlayIsAttached()) "presente" else "ausente"}")
            appendLine("Pausa: ${if (Vita3kCoreBridge.isAppPaused()) "PAUSADO" else "en marcha"}")
            val overlayStats = OverlayInputStats.snapshot()
            appendLine("Pulsaciones táctiles: botones ${overlayStats.buttonsOk} entregados / ${overlayStats.buttonsLost} perdidos | ejes ${overlayStats.axesOk} entregados / ${overlayStats.axesLost} perdidos")
            appendLine("Última pulsación: botón SDL ${overlayStats.lastButtonSdl} pressed=${overlayStats.lastButtonPressed} entregado=${overlayStats.lastButtonDelivered} | último eje: SDL ${overlayStats.lastAxisSdl} valor=${overlayStats.lastAxisValue} entregado=${overlayStats.lastAxisDelivered}")
            // 0.2.63 (D): táctil libre del juego (TouchStats).
            // lastEventMillis 0 = ningún toque todavía en este
            // proceso: un 0/0 con "ninguno todavía" no se puede
            // confundir con un reporte viejo (D-P2-2).
            val touchStats = TouchStats.snapshot()
            appendLine("Toques libres en el juego: ${touchStats.freeDownOk} DOWN entregados / ${touchStats.freeDownLost} perdidos | ${touchStats.freeUpOk} UP entregados | reenviados (2º dedo / zona Igniculus): ${touchStats.forwardedDownOk} DOWN entregados / ${touchStats.forwardedDownLost} perdidos | ${touchStats.forwardedUpOk} UP entregados")
            if (touchStats.lastEventMillis == TouchStats.NO_EVENT_MILLIS) {
                appendLine("Último toque: ninguno todavía en este proceso")
            } else {
                val ageMs = System.currentTimeMillis() - touchStats.lastEventMillis
                appendLine("Último toque: ${touchStats.lastAction} en (${touchStats.lastX}, ${touchStats.lastY}) entregado=${touchStats.lastDelivered} hace ${ageMs} ms")
            }
            // D-P1-2: el botón SDL 9 que cuentan las pulsaciones NO
            // es un botón del mando: es el brillo de Igniculus
            // (sendGlow envía SDL 9 + eje -4 al mantener la zona; en
            // lecturas v2 del juego el 9 es L1 colateral). El log lo
            // distingue ("brillo Igniculus pressed=...").
            appendLine("Nota: el botón SDL 9 de las pulsaciones es el brillo de Igniculus (no un botón del mando): ver log \"brillo Igniculus\".")
            // 0.2.63 (G): perfil táctil activo + fracciones del
            // stick guardadas (fuente de verdad). Si el stick se ve
            // desplazado en pantalla pero aquí dice (0.05, 0.675),
            // el dato está bien y la vista horneó márgenes de un
            // tamaño viejo; la línea "overlay construido" del logcat
            // dice con qué tamaño de vista se montó.
            val reportStick = lastTouchLayout.firstOrNull { it.kind == "stick" }
            appendLine("Perfil táctil: ${lastTouchProfileId ?: "sin dato"} | ${lastTouchLayout.size} controles | stick: ${reportStick?.let { "(${it.xFraction}, ${it.yFraction}) ${it.sizeDp} dp${it.sizeFraction?.let { f -> " (fracción $f)" } ?: ""}" } ?: "sin stick"}")
            appendLine()
            // R4-P2-9: "Ajustes activos" muestra los valores EFECTIVOS
            // (los que corre el juego y emite config.yml), no los
            // guardados: effectiveSettings() aplica el Modo seguro (1x,
            // SDL, High Accuracy OFF) y el paquete del modo 60 FPS
            // (1x, cachés ON), y resolveBackend() el fallback
            // Vulkan→OpenGL. Es el mismo patrón que la pantalla de
            // Diagnóstico ("Configuración efectiva",
            // SettingsDiagnosticsScreen). Antes, con 60 FPS ON el
            // reporte decía "VSync: false" mientras el config.yml del
            // mismo reporte emitía v-sync: true. La RAM total va aquí
            // (DeviceMemory, la misma fuente que Diagnóstico) para
            // cerrar el misterio de los 4 GB sin preguntar.
            // Ronda 0.2.64: el modo 60 FPS YA NO fuerza VSync ON (en
            // Vulkan elige el present mode desde la 0.2.66, G11-06):
            // la línea de abajo ya no lo dice.
            appendLine("--- Ajustes activos (efectivos) ---")
            val effective = effectiveSettings(settings)
            if (settings.safeMode) {
                appendLine("Modo seguro activo: los valores de abajo son los efectivos (1x, SDL, High Accuracy OFF, driver del sistema), no los guardados.")
            } else if (settings.fpsHack) {
                appendLine("Modo 60 FPS activo: los valores de abajo son los efectivos (fps-hack + 1x, cachés ON), no los guardados.")
            }
            appendLine("Perfil: ${effective.activeProfile.label}")
            appendLine("Backend: ${resolveBackend(effective, gpu).label} | Resolución: ${effective.resolutionLabel} | Filtro: ${effective.screenFilter}")
            appendLine("Aniso: ${effective.anisotropicFiltering}x | VSync: ${effective.vSync} | Async: ${effective.asyncPipelineCompilation}")
            appendLine("High Accuracy: ${effective.highAccuracy} | Surface Sync: ${effective.surfaceSyncEnabled} | Memory Mapping: ${effective.memoryMapping}")
            appendLine("Audio: ${effective.audioBackend} | Volumen: ${effective.audioVolume} | NGS: ${effective.ngsEnable} | Audio experimental (SDL y Cubeb): ${effective.expAudioSdl}")
            appendLine("CPU opt (JIT): ${effective.cpuOpt} | FPS hack: ${effective.fpsHack}")
            appendLine("Modo seguro: ${effective.safeMode}")
            appendLine("Driver: ${effective.gpuDriver} | Turnip: ${effective.turnipVersion} (instalado: ${effective.turnipInstalled})")
            appendLine("Caché de texturas: ${effective.textureCache} | Caché de shaders: ${effective.shaderCache} | Carga rápida (sin precompilar): ${effective.skipShaderPrecompile} | Caché sin hash: ${effective.hashlessTextureCache}")
            appendLine("Overlay de FPS: ${effective.showFpsOverlay} (detalle ${effective.performanceOverlayDetail.label}, posición ${effective.performanceOverlayPosition.label}) | Overlay táctil: ${effective.showTouchOverlayInGame}")
            appendLine("Compilar shaders: ${effective.showCompileShaders} | PSN: ${effective.psnSignedIn} | Retraso carga: ${effective.fileLoadingDelay} | Cerrar apps: ${effective.closeBackgroundApps}")
            val totalRamGb = DeviceMemory.totalRamBytes(context)?.let { it / 1_073_741_824.0 }
            appendLine("RAM total: ${totalRamGb?.let { "%.1f GB".format(it) } ?: "no detectada"}")
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
                    // Ronda 0.2.66 (G11-02): el reporte muestra el
                    // mapeo efectivo que se escribe al lanzar.
                    _supportedMappingMask.value,
                ),
            )
            appendLine("--- Tiempos de carga (T1/T2) ---")
            // Ronda 0.2.65 (T-P2-1): T1/T2 solo vivían en la cola de 60
            // líneas del log y podían caerse de ella. Sección propia:
            // se leen del archivo de log completo (hasta 200 líneas
            // tras rotar), no solo de la cola.
            val timingLines = runCatching {
                extractLoadTimingLines(LauncherLog.file(context).readText())
            }.getOrDefault(emptyList())
            if (timingLines.isEmpty()) {
                appendLine("Sin mediciones de carga todavía (juega una partida para medirlas).")
            } else {
                timingLines.forEach { appendLine(it) }
            }
            appendLine()
            // Ronda 0.2.66 (Fase L, INST-P0-2/INST-P0-3/INST-P1-1):
            // perfil de frames. Los agregados acumulan desde que se
            // activó el perfil en este proceso; el nº de muestras
            // dice si la sesión midió algo o no. La lectura honesta
            // vive en Snapshot.formatSummary(): ningún número de aquí
            // se llama "presentado" (sin pin no hay medida de frames
            // presentados) y un juego a 30 fps puede ser correcto.
            appendLine("--- Perfil de frames (Fase L) ---")
            appendLine("Perfil de frames: ${if (settings.frameProfile) "activado" else "desactivado"} (Ajustes → Diagnóstico)")
            val frameProfile = FrameProfileStats.snapshot()
            if (frameProfile.totalSamples == 0L) {
                appendLine("Sin muestras todavía (activa el perfil y juega una partida para medirlas).")
            } else {
                appendLine(frameProfile.formatSummary())
            }
            appendLine()
            // Ronda 0.2.66 (CPU-09, parte launcher): el diagnóstico
            // de CPU del launcher muestra las líneas de CPU del log
            // del núcleo (la única señal de CPU que existe hoy: el
            // pin registra "CPU Optimisation state: …" al arrancar)
            // y recomienda el overlay ON para la prueba. No hay
            // contadores JIT ni tiempos por hilo en el launcher, y
            // no se afirma si el build del núcleo habilita o no una
            // herramienta de profiling (corrección Codex 9: eso no
            // queda probado con las citas disponibles).
            appendLine("--- Diagnóstico de CPU ---")
            appendLine("CPU opt (JIT): ${effective.cpuOpt} (ver «Ajustes activos» arriba). El launcher no tiene contadores JIT ni tiempos de CPU por hilo: eso necesita instrumentación del núcleo.")
            val nativeLogTail = LauncherLog.readNativeLogTail(File(library.vitaRoot, "vita3k.log"))
            val cpuLines = extractCpuLogLines(nativeLogTail)
            if (cpuLines.isEmpty()) {
                appendLine("Sin líneas de CPU en la cola del log del núcleo (últimas 40 líneas).")
            } else {
                appendLine("Líneas de CPU del log del núcleo:")
                cpuLines.forEach { appendLine(it) }
            }
            appendLine("Para la prueba de lentitud, activa el overlay de FPS (Ajustes → Rendimiento → Mostrar overlay de FPS) y el perfil de frames (Ajustes → Diagnóstico).")
            appendLine()
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
            // Ronda 0.2.65 (W-P1-8): la cabecera ya no nombra el
            // archivo interno del núcleo (nombre genérico).
            appendLine("--- Log del núcleo (últimas líneas) ---")
            appendLine(nativeLogTail)
        }
        // Privacidad: el nombre del paquete no sale en el reporte
        // (ni en la cabecera ni en las rutas del log/config): se
        // sustituye por un nombre neutro; las rutas siguen siendo
        // legibles para depurar.
        // Ronda 0.2.65 (T-P2-3): los nombres de sesión son texto libre
        // del usuario (pueden contener su nombre real) y viajan en las
        // líneas de log del informe: se neutralizan antes de exportar
        // (el log en disco no cambia).
        val sessionNames = sessionManager.sessionsFlow.value.map { it.name }
        val sanitized = sanitizeSessionNamesInReport(raw, sessionNames)
        return sanitized.replace("com.danielsnagustin.childoflight.launcher", "child-of-light-launcher")
    }

    /**
     * Ronda 0.2.55 P1-4: exportar el reporte leyendo ajustes y GPU
     * aquí (IO), para que MainActivity no tenga que recoger
     * settingsFlow/gpuFlow en la raíz. Si los ajustes aún no han
     * emitido, first() espera a la primera emisión en vez de fallar
     * en silencio como antes.
     */
    fun exportBugReportCurrent(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = settingsRepo.settingsFlow.first()
            val gpu = settingsRepo.gpuInfoFlow.first()
            exportBugReport(uri, settings, gpu)
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
