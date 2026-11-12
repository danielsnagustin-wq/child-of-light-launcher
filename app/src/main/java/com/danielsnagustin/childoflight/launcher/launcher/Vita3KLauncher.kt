package com.danielsnagustin.childoflight.launcher.launcher

import android.app.ActivityManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameActivity
import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameLauncher
import com.danielsnagustin.childoflight.launcher.core.EmbeddedPkgInstaller
import com.danielsnagustin.childoflight.launcher.core.InstalledWithoutLicenseException
import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.data.LARGE_FILE_COPY_BUFFER_BYTES
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.ImportTreeSearch
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.PkgHeaderParser
import com.danielsnagustin.childoflight.launcher.data.PkgRetryState
import com.danielsnagustin.childoflight.launcher.data.SessionManager
import com.danielsnagustin.childoflight.launcher.data.formatLoadGameResult
import com.danielsnagustin.childoflight.launcher.data.TurnipDriverRepository
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.PkgKind
import org.libsdl.app.SDLActivity
import com.danielsnagustin.childoflight.launcher.model.TitleId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Lanzador del juego.
 *
 * Flujo real (sesiones + firmware simplificado):
 * 1. Verifica prerrequisitos: SOLO el juego instalado. El firmware
 *    NO bloquea el primer uso: el launcher solo pide juego + DLCs.
 * 2. Activa la sesión correcta (SessionManager): snapshot de la
 *    sesión actual y restore de la activa en la savedata viva. Si
 *    falla, NO se lanza (mejor no lanzar con la savedata equivocada).
 *    SOLO si el juego está EXTRAÍDO: con un .pkg no hay savedata en
 *    el launcher (con el núcleo embebido, los saves vivirán en
 *    vita/ux0 una vez instalado el juego) y no hay sesión que activar.
 * 3. Si lo pide el ajuste, cierra apps en segundo plano (best-effort).
 * 4. Escribe config.yml con los ajustes actuales.
 * 5. Núcleo embebido: el motor integrado es el camino
 *    PRINCIPAL. Si el .so existe, JUGAR abre SIEMPRE el juego dentro
 *    del launcher (EmbeddedGameActivity, SDL): la decisión es pura
 *    (EmbeddedGameLauncher.decide) y aquí solo se ejecuta. Con juego
 *    solo en .pkg, primero se instala el .pkg con el propio núcleo
 *    (EmbeddedPkgInstaller) y luego se arranca. Si el intento
 *    embebido falla (migración, init, loadGame o apertura de la
 *    Activity), NO hay salto a la app externa: se devuelve
 *    EmbeddedFailed con el motivo exacto y el launcher muestra el
 *    diálogo (Reintentar / Reportar). Sin firmware local, decide()
 *    Firmware); sin núcleo, solo se usa la vía externa si el usuario
 *    activó la opción avanzada en Ajustes (allowExternalVita3k).
 * 6. Vía externa (solo opción avanzada): lanza la app externa
 *    org.vita3k.emulator si está instalada, con un Intent MAIN
 *    normal. No se inventa un deep-link para arrancar un Title ID
 *    concreto: no existe (P2-42, verificado en el pin 3e43bc80:
 *    android/jni/main_android.cpp:66-76 toma el Title ID de argv
 *    ("-r", de Emulator.getArguments()), que un Intent externo no
 *    puede inyectar; el pin no trae ningún intent-filter ni acción
 *    para lanzar un juego).
 *    Solo se llega aquí cuando decide() devolvió UseExternal, es
 *    decir, sin núcleo embebido y con la opción avanzada activada.
 *    Si falta firmware LOCAL, solo se AVISA con una frase y se lanza
 *    igual: la app externa gestiona su propio firmware al arrancar
 *    (tiene su propio instalador) y el launcher no puede ver su
 *    almacenamiento privado. Nunca se le pide al usuario que busque
 *    firmware por internet.
 * 7. Si ni siquiera está la app externa (con la opción avanzada
 *    activada), devuelve el error con instrucciones claras en vez
 *    de fallar en silencio.
 *
 * .pkg + app externa (deliverPkgFiles): el launcher NO puede
 * desencriptar un .pkg ni leer la biblioteca de la app externa. La
 * única vía honesta es copiar el .pkg + work.bin a
 * Descargas/ChildOfLight/ (carpeta compartida que la app externa sí
 * lee), abrir la app externa y guiar al usuario para instalarlo ahí
 * una sola vez (File > Install Package (.pkg) > elegir el .pkg >
 * "Select work.bin" > elegir el work.bin). La app externa no expone
 * ningún intent público documentado para instalar un .pkg: no se
 * inventa ninguno. La copia NUNCA es automática: el ViewModel pide
 * confirmación antes (qué se copia, cuánto ocupa y por qué) y la
 * carpeta solo se crea si el usuario la confirma; las copias se
 * pueden borrar desde Ajustes → Almacenamiento. Con el núcleo
 * embebido listo, el juego se instala desde el almacenamiento
 * interno y este paso desaparece.
 */
class Vita3KLauncher(private val context: Context) {

    /**
     * R4-P0-8: último GameStatus escaneado por checkPrerequisites().
     * Un solo lanzamiento a la vez (la guarda anti-doble-JUGAR del
     * ViewModel lo garantiza), así que no hay carrera: play() lo lee
     * tras launch() para reutilizar ese escaneo en vez de pedir otro
     * refreshStatus() completo cuando el lanzamiento no instaló nada.
     * En memoria: tras reiniciar el proceso es null y play() hace el
     * refresco normal.
     */
    @Volatile
    var lastScannedStatus: GameStatus? = null
        private set

    /**
     * Ronda 0.2.64 (H-2 tiempo de carga): ruta del .pkg bloqueado por
     * el tope de reinstalación (ver PkgRetryState): ya se intentó
     * reinstalar una vez y loadGame siguió devolviendo -3. El
     * ViewModel lo lee tras un EmbeddedFailed para ofrecer el botón
     * "Borrar ese .pkg" (AlertAction.DELETE_BLOCKED_PKG) en vez de
     * reintentar (que reinstalaría 1,14 GB otra vez). null = ningún
     * .pkg bloqueado en este proceso. El tope en sí es persistente
     * (pkg-retry-state.txt en vitaRoot); este campo es solo el
     * puntero al .pkg del lanzamiento actual.
     */
    @Volatile
    var lastBlockedPkgPath: String? = null
        private set

    /** Title ID del .pkg bloqueado (para borrarlo con el gancho existente). */
    @Volatile
    var lastBlockedPkgTitleId: String? = null
        private set

    /**
     * Log de instrumentación T1/T2 (ronda 0.2.64, H-4): añade
     * "(Xms desde JUGAR)" cuando se conoce el t0 del toque de JUGAR
     * (launchStartedAtMillis > 0, elapsedRealtime). Con 0 (llamador
     * sin cronómetro) solo registra el mensaje. Mismo patrón que
     * refreshStatusOnce (duraciones solo si > 500 ms), pero aquí el
     * umbral es 0: cada hito del arranque interesa para el ~2 min.
     */
    private fun logTiming(tag: String, launchStartedAtMillis: Long, message: String) {
        val suffix = if (launchStartedAtMillis > 0) {
            " (${SystemClock.elapsedRealtime() - launchStartedAtMillis}ms desde JUGAR)"
        } else {
            ""
        }
        LauncherLog.append(context, tag, "$message$suffix")
    }

    /** Estado persistente del tope de reinstalación por .pkg. */
    private fun pkgRetryStateFile(vitaRoot: File) = File(vitaRoot, "pkg-retry-state.txt")

    private fun readPkgRetryState(vitaRoot: File): PkgRetryState {
        return runCatching {
            val file = pkgRetryStateFile(vitaRoot)
            if (!file.exists()) PkgRetryState() else PkgRetryState.decode(file.readText())
        }.getOrDefault(PkgRetryState())
    }

    private fun writePkgRetryState(vitaRoot: File, state: PkgRetryState) {
        runCatching { pkgRetryStateFile(vitaRoot).writeText(state.encode()) }
    }

    /**
     * Borra el .pkg bloqueado por el tope de reinstalación (ronda
     * 0.2.64, H-2). Usa el gancho existente
     * deleteStoredGamePkgAnyState (borra el .pkg del juego EN
     * CUALQUIER ESTADO) cuando es el .pkg del juego; si no hay
     * titleId, borra el archivo por su ruta. Limpia también la
     * entrada de reintentos y los punteros, para que la próxima
     * importación parta de cero. Devuelve true si se borró algo.
     * Lo llama el ViewModel desde el botón "Borrar ese .pkg".
     */
    fun deleteBlockedPkg(): Boolean {
        val path = lastBlockedPkgPath ?: return false
        val titleId = lastBlockedPkgTitleId
        val deleted = if (titleId != null) {
            runCatching { GameLibraryRepository(context).deleteStoredGamePkgAnyState(titleId) }.getOrDefault(false)
        } else {
            runCatching { File(path).delete() }.getOrDefault(false)
        }
        if (deleted) {
            val vitaRoot = GameLibraryRepository(context).vitaRoot
            val key = titleId?.let { PkgRetryState.gameKey(it) }
            if (key != null) writePkgRetryState(vitaRoot, readPkgRetryState(vitaRoot).recordSuccess(key))
            lastBlockedPkgPath = null
            lastBlockedPkgTitleId = null
            LauncherLog.append(context, "pkg", ".pkg bloqueado borrado a mano: $path")
        } else {
            LauncherLog.append(context, "pkg", "No se pudo borrar el .pkg bloqueado: $path")
        }
        return deleted
    }

    /**
     * Ronda 0.2.55 P1-6: instante (monótono) del último escaneo de
     * checkPrerequisites(). Con menos de 2 s (misma ventana que la
     * guarda P1-9 del ViewModel), el escaneo se reutiliza en vez de
     * lanzar otro scanStatus() completo: un JUGAR justo después de
     * una prueba de 30 s (o dos JUGAR seguidos) ya no escanea dos
     * veces. decide() solo lee instalado/extraído/.pkg/licencia de
     * ese estado, y en 2 s el disco no cambia por ninguna vía de la
     * app sin pasar por un refresco. Si el estado reutilizado es el
     * del lanzamiento anterior, play() lo detecta (misma referencia)
     * y conserva su _status, que ya es fresco.
     */
    @Volatile
    private var lastScannedAtMillis: Long = 0L

    companion object {
        const val VITA3K_PACKAGE = "org.vita3k.emulator"
        const val EMBEDDED_LIB_NAME = "Vita3K" // OUTPUT_NAME de upstream (target CMake "vita3k", archivo libVita3K.so)

        /**
         * Una frase, sin pedir que se busque nada por internet.
         * Para el lanzamiento externo (la app externa gestiona su firmware).
         */
        const val FIRMWARE_WARNING_EXTERNAL =
            "Falta el firmware de Vita en el launcher: la app externa lo gestiona al arrancar, " +
                "o puedes importarlo en Importar → Firmware."

        /**
         * Para el lanzamiento embebido bloqueado por falta de firmware:
         * el motor integrado no puede arrancar sin firmware. La usa
         * EmbeddedGameLauncher.decide() como motivo del Blocked, y el
         * ViewModel la reconoce por "Falta el firmware" para ofrecer
         * ir a importar (AlertAction.GO_IMPORT).
         */
        const val FIRMWARE_BLOCKED_EMBEDDED =
            "Falta el firmware de Vita: el motor integrado no puede arrancar el juego sin él " +
                "(Importar → Firmware)."

        /**
         * P2-A4a: el último arranque embebido que falló DENTRO de
         * EmbeddedGameActivity.onCreate (después de que launch() ya
         * devolvió LaunchedEmbedded y el ViewModel dijera "juego
         * lanzado"). La Activity lo anota con noteEmbeddedLaunchFailed
         * y MainActivity.onResume lo consume con
         * takeEmbeddedLaunchFailure para corregir el mensaje al volver.
         * Es una bandera en memoria (un solo lanzamiento a la vez):
         * tryLaunchEmbedded la limpia antes de abrir la Activity.
         */
        @Volatile
        private var lastEmbeddedLaunchFailure: String? = null

        /** Anota que el juego no llegó a arrancar dentro de la Activity (P2-A4a). */
        fun noteEmbeddedLaunchFailed(reason: String) {
            lastEmbeddedLaunchFailure = reason
        }

        /**
         * Devuelve y consume el fallo anotado por
         * noteEmbeddedLaunchFailed (null si el juego arrancó bien o si
         * ya se consumió). MainActivity.onResume lo llama al volver.
         */
        fun takeEmbeddedLaunchFailure(): String? {
            val reason = lastEmbeddedLaunchFailure
            lastEmbeddedLaunchFailure = null
            return reason
        }

        /**
         * P2-A4b: texto del fallo de init() según el código. El -1
         * (NOT_IMPLEMENTED) no es "un error más": significa que no hay
         * núcleo en esta APK (cargó el stub o nada) y mostrar solo
         * "código -1" no dice nada. Se registra qué biblioteca falló
         * al cargar (lastLoadError) en el mensaje y en el log.
         */
        internal fun initFailureMessage(rc: Int, lastLoadError: String?): String {
            return if (rc == Vita3kCoreBridge.NOT_IMPLEMENTED) {
                "El motor integrado no está presente en esta APK: el núcleo no se pudo cargar" +
                    (lastLoadError?.let { " ($it)" } ?: "") +
                    ". Instala el artefacto con núcleo (native) o repórtalo."
            } else {
                "El motor integrado no se pudo iniciar (código $rc). Vuelve a intentarlo; si sigue igual, repórtalo."
            }
        }

        /**
         * Código de loadGame cuando el núcleo no encuentra el juego
         * instalado (Vita3kCoreBridge.loadGame: -3; -2 es sin sesión,
         * -1 es sin núcleo). Solo el -3 lo arregla una reinstalación.
         */
        const val LOAD_GAME_NOT_INSTALLED = -3

        /**
         * R6-P0: ¿reinstalar el .pkg guardado y reintentar loadGame
         * UNA vez? Pura (testeable en JVM). Solo el -3 ("no
         * instalado") lo arregla una reinstalación: con -1 (sin
         * núcleo) o -2 (sin sesión) instalar no cambia nada, y sin
         * .pkg guardado reintentar es repetir lo mismo. [alreadyRetried]
         * acota a un solo reintento por lanzamiento: un .pkg que no
         * es el juego (p. ej. un update guardado como juego) no
         * entra en bucle, falla una vez y el motivo explica cómo
         * salir de él (ver tryLaunchEmbedded).
         */
        internal fun shouldReinstallAndRetryLoadGame(
            loadRc: Int,
            storedPkgPresent: Boolean,
            alreadyRetried: Boolean,
        ): Boolean {
            return loadRc == LOAD_GAME_NOT_INSTALLED && storedPkgPresent && !alreadyRetried
        }

        /**
         * R6-P2-07: ¿los artefactos que afirma un escaneo cacheado
         * siguen en disco? Barato (un listado de carpeta por
         * artefacto): si el juego extraído o el .pkg guardado
         * desaparecieron entre medias, el escaneo miente y hay que
         * re-escanear. No es testeable en JVM (necesita el
         * repositorio, que necesita Context): solo-teléfono.
         */
        internal fun cachedArtifactsExist(library: GameLibraryRepository, status: GameStatus): Boolean {
            val extractedId = status.extractedTitleId?.id
            if (extractedId != null && ImportTreeSearch.findEboot(library.appDir(extractedId)) == null) return false
            val pkgId = status.pkgTitleId?.id
            if (pkgId != null && library.findPkgGameFile(pkgId) == null) return false
            return true
        }
    }

    sealed class LaunchOutcome {
        /** config.yml escrito; se lanzó la app externa de Vita3K. */
        data class LaunchedExternal(val configPath: String) : LaunchOutcome()
        /** Se lanzó Vita3K externo sin firmware local (Vita3K lo gestiona). */
        data class LaunchedExternalFirmwareWarning(val configPath: String) : LaunchOutcome()
        /** El núcleo embebido arrancó el juego. */
        data class LaunchedEmbedded(val configPath: String) : LaunchOutcome()
        /** El núcleo embebido existe. Sin punto de entrada JNI aún. Reservado: launchImpl ya no lo devuelve (el fallback externo cubre ese caso mejor); el ViewModel lo sigue manejando por si vuelve a usarse. */
        data class EmbeddedCorePresent(val configPath: String) : LaunchOutcome()
        /**
         * El intento embebido falló (migración, init, loadGame o
         * apertura de la Activity). reason es el motivo exacto para
         * el diálogo (Reintentar / Reportar). Nunca se salta a la
         * app externa por detrás.
         */
        data class EmbeddedFailed(val reason: String) : LaunchOutcome()
        data class Blocked(val reason: String) : LaunchOutcome()
    }

    /** Resultado del intento embebido: éxito o motivo exacto del fallo. */
    sealed class EmbeddedAttempt {
        data class Ok(val outcome: LaunchOutcome) : EmbeddedAttempt()
        data class Failed(val reason: String) : EmbeddedAttempt()
    }

    /**
     * P1-12: si la excepción es InstalledWithoutLicenseException, el
     * .pkg YA está instalado en el núcleo y solo falló el registro de
     * la licencia. El fallo NO es "no se pudo instalar": se informa
     * "instalado pero sin licencia" y se ofrece reemplazar el
     * work.bin (el mensaje de la excepción ya lo dice). El llamador
     * NO debe reintentar la instalación completa: un reintento de
     * JUGAR ya no reinstala (decide() pasa a UseEmbedded, el juego
     * está extraído) y el motivo llega al diálogo con Reintentar /
     * Reportar. Null si no es este caso (fallo genérico).
     */
    private fun installedWithoutLicenseFailure(exception: Throwable?): LaunchOutcome.EmbeddedFailed? {
        val withoutLicense = exception as? InstalledWithoutLicenseException ?: return null
        return LaunchOutcome.EmbeddedFailed(
            "El juego quedó instalado en el motor integrado pero sin licencia: ${withoutLicense.message}",
        )
    }

    data class Prerequisites(
        val gameInstalled: Boolean,
        val titleId: String?,
        /** true si el juego está EXTRAÍDO (eboot.bin). false = solo .pkg. */
        val gameExtracted: Boolean,
        val firmwareInstalled: Boolean,
        val firmwareStatus: FirmwareStatus,
        /**
         * R4-P0-8: el GameStatus completo que se escaneó para decidir
         * esto. launchImpl() lo reutiliza para decide() en vez de
         * hacer un segundo scanStatus() en el mismo lanzamiento.
         * Default vacío por compatibilidad con quien lo construya a mano.
         */
        val status: GameStatus = GameStatus(),
        val missing: List<String>,
    ) {
        /** Solo el juego es obligatorio. El firmware es aviso, no bloqueo. */
        val ready: Boolean get() = gameInstalled
        /** El juego es solo un .pkg guardado: no hay saves/sesiones en el launcher. */
        val pkgOnly: Boolean get() = gameInstalled && !gameExtracted
    }

    fun checkPrerequisites(library: GameLibraryRepository): Prerequisites {
        // Ronda 0.2.55 P1-6: reutilizar el escaneo si tiene menos de
        // 2 s (misma ventana que la guarda P1-9). Si no, escanear de
        // disco y guardar el instante: JUGAR necesita el estado
        // correcto, y un escaneo viejo no lo es.
        // R6-P2-07: el escaneo cacheado puede mentir si el disco cambió
        // (se borró el .pkg o el juego entre medias): antes de
        // reutilizarlo, verificar barato (File.exists / un listado)
        // que los artefactos que el estado afirma sigan existiendo.
        // Si falta alguno, re-escanear.
        val nowMillis = SystemClock.elapsedRealtime()
        val cachedStatus = lastScannedStatus
        val status = if (cachedStatus != null && nowMillis - lastScannedAtMillis < 2000L && cachedArtifactsExist(library, cachedStatus)) {
            cachedStatus
        } else {
            library.scanStatus().also {
                lastScannedStatus = it
                lastScannedAtMillis = nowMillis
            }
        }
        val firmwareStatus = FirmwareResolver.resolve(library.vitaRoot)
        val missing = mutableListOf<String>()
        if (!status.gameInstalled) {
            missing.add("El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).")
        }
        // Diagnóstico (2026-11-08): sin esta línea, un bloqueo aquí era
        // indiagnosable (el diálogo solo muestra el motivo, no dónde se
        // buscó el juego). Se registra el motivo y la ruta ux0 resuelta.
        if (missing.isNotEmpty()) {
            LauncherLog.append(context, "launch", "Prerrequisitos no cumplidos: ${missing.joinToString(" ")} (ux0=${library.ux0Dir.absolutePath})")
        }
        return Prerequisites(
            gameInstalled = status.gameInstalled,
            titleId = status.titleId?.id,
            gameExtracted = status.gameExtracted,
            firmwareInstalled = firmwareStatus.ready,
            firmwareStatus = firmwareStatus,
            status = status,
            missing = missing,
        )
    }

    /**
     * Copia los .pkg + work.bin guardados a Descargas/ChildOfLight/
     * (carpeta compartida que la app externa sí puede leer) para
     * instalarlos ahí una sola vez. La app externa no expone ningún
     * intent público documentado para instalar un .pkg: por eso se
     * copia + se abre la app externa + se muestra la guía paso a paso.
     * No se inventa un deep-link. Solo se llama después de que el
     * usuario confirme la copia en el diálogo del ViewModel: es el
     * ÚNICO camino que crea Descargas/ChildOfLight/. Devuelve las
     * rutas públicas copiadas.
     */
    fun deliverPkgFiles(library: GameLibraryRepository): Result<List<String>> {
        return try {
            val files = library.storedPkgFilesForDelivery()
            if (files.isEmpty()) {
                return Result.failure(
                    IllegalStateException("No hay ningún .pkg guardado para entregar. Importa primero el .pkg del juego."),
                )
            }
            val copied = files.map { copyToDownloads(it) }
            LauncherLog.append(context, "pkg", "Entregados a Descargas/ChildOfLight/: ${copied.joinToString()}")
            Result.success(copied)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun copyToDownloads(file: File): String {
        val fileName = file.name
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore (API 29+): sin permiso de almacenamiento.
            val resolver = context.contentResolver
            // Si ya existe un archivo con ese nombre en la carpeta, se
            // borra primero para no acumular copias viejas del .pkg.
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(fileName, "Download/ChildOfLight/"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/ChildOfLight/")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No se pudo crear $fileName en Descargas.")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES) }
            } ?: throw IllegalStateException("No se pudo escribir $fileName en Descargas.")
            "Descargas/ChildOfLight/$fileName"
        } else {
            // API 28: carpeta pública legacy (permiso WRITE_EXTERNAL_STORAGE maxSdkVersion 28).
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ChildOfLight")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, fileName)
            FileInputStream(file).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out, LARGE_FILE_COPY_BUFFER_BYTES) }
            }
            dest.absolutePath
        }
    }

    /** Abre Vita3K externo (su biblioteca). false si no está instalado. */
    fun openVita3k(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(VITA3K_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * gpu = GPU detectada (opcional): solo se usa para el config.yml
     * (fallback Vulkan -> OpenGL si el teléfono no tiene Vulkan, ver
     * Vita3kConfigGenerator). La vía externa no lee el config.yml del
     * launcher, así que ahí el parámetro no cambia nada.
     * onInstallProgress (opcional): % real del núcleo al instalar el
     * .pkg guardado (juego, update, DLCs). Solo se usa para mostrarlo
     * en UI; sin él, la instalación es exactamente igual que antes.
     * benchmark (opcional): true = prueba de 30 s; la Activity del
     * juego muestrea el FPS del núcleo y lo guarda al salir, para
     * registrar la prueba automáticamente al volver.
     * configAlreadyWritten (opcional, R4-P0-7): true = el llamador ya
     * escribió config.yml en este JUGAR (play/startBenchmark lo hacen
     * antes del firmware); launchImpl NO lo reescribe. false por
     * defecto: cualquier otro camino sigue escribiéndolo aquí.
     * prerequisites (opcional, R4-P0-8): prerrequisitos ya comprobados
     * por el llamador (startBenchmark); launchImpl reutiliza su
     * GameStatus en vez de re-escanear. null por defecto: se comprueba
     * aquí como antes.
     * launchStartedAtMillis (opcional, ronda 0.2.64 H-4): t0 del toque
     * de JUGAR (SystemClock.elapsedRealtime, monotónico). 0 = sin
     * medir: los hitos del arranque se registran sin ms. play() y
     * startBenchmark() lo pasan; los hitos (init, lista de apps,
     * loadGame, Activity) se registran con "(Xms desde JUGAR)".
     */
    suspend fun launch(
        library: GameLibraryRepository,
        settings: LauncherSettings,
        sessionManager: SessionManager? = null,
        gpu: GpuInfo? = null,
        onInstallProgress: EmbeddedPkgInstaller.ProgressListener? = null,
        benchmark: Boolean = false,
        configAlreadyWritten: Boolean = false,
        prerequisites: Prerequisites? = null,
        launchStartedAtMillis: Long = 0L,
    ): LaunchOutcome {
        // BUG (auditoría): writeConfig (file.writeText) y checkPrerequisites
        // pueden lanzar IOException y tumbar la corrutina del ViewModel en
        // play()/startBenchmark(). El lanzamiento nunca debe lanzar.
        return try {
            launchImpl(library, settings, sessionManager, gpu, onInstallProgress, benchmark, configAlreadyWritten, prerequisites, launchStartedAtMillis)
        } catch (e: Throwable) {
            // Throwable (no solo Exception): en un teléfono de 4 GB, un
            // OutOfMemoryError durante la instalación del .pkg (1,14 GB)
            // escapaba de este catch y cerraba la app en vez de mostrar
            // el diálogo de bloqueo con el motivo.
            LauncherLog.append(context, "launch", "launch falló con ${e.javaClass.simpleName}: ${e.message}")
            LaunchOutcome.Blocked("No se pudo lanzar: ${e.message}")
        }
    }

    /**
     * Instala el driver Turnip en el núcleo embebido si los ajustes lo
     * piden y aún no está instalado. Verificado contra upstream (pin
     * 3e43bc80, native_drivers.cpp): `custom-driver-name` es el stem
     * del zip y el núcleo lo busca ya extraído en
     * <filesDir>/driver/<nombre>/; el zip guardado por el launcher en
     * <filesDir>/vita3k/drivers/turnip/ no basta por sí solo.
     * Best-effort y silencioso: sin núcleo, sin Turnip pedido o sin
     * zip guardado no hace nada; si la instalación falla, solo queda
     * en el log y el núcleo usará el driver del sistema. Nunca lanza
     * ni bloquea el lanzamiento del juego.
     */
    private fun ensureTurnipDriverInstalled(settings: LauncherSettings) {
        if (!Vita3kCoreBridge.available) return
        if (settings.gpuDriver != GpuDriver.TURNIP || !settings.turnipInstalled) return
        if (settings.customDriverName.isBlank()) return
        try {
            if (Vita3kCoreBridge.getInstalledCustomDrivers().contains(settings.customDriverName)) return
            val zip = TurnipDriverRepository(context).installedDriverFile()
            if (zip == null) {
                LauncherLog.append(context, "driver", "Turnip pedido (${settings.customDriverName}) pero no hay zip guardado; el núcleo usará el driver del sistema.")
                return
            }
            val name = Vita3kCoreBridge.installCustomDriver(zip.absolutePath)
            if (name.isEmpty()) {
                LauncherLog.append(context, "driver", "No se pudo instalar Turnip en el núcleo (${settings.customDriverName}); el núcleo usará el driver del sistema.")
            } else {
                LauncherLog.append(context, "driver", "Turnip instalado en el núcleo al lanzar: $name")
            }
        } catch (e: Exception) {
            LauncherLog.append(context, "driver", "No se pudo revisar Turnip en el núcleo: ${e.message}")
        }
    }

    private suspend fun launchImpl(
        library: GameLibraryRepository,
        settings: LauncherSettings,
        sessionManager: SessionManager? = null,
        gpu: GpuInfo? = null,
        onInstallProgress: EmbeddedPkgInstaller.ProgressListener? = null,
        benchmark: Boolean = false,
        configAlreadyWritten: Boolean = false,
        prerequisites: Prerequisites? = null,
        launchStartedAtMillis: Long = 0L,
    ): LaunchOutcome {
        // R4-P0-8: un solo escaneo del juego por lanzamiento. Si el
        // llamador ya comprobó los prerrequisitos (startBenchmark), se
        // reutiliza su GameStatus; si no, se comprueba aquí (play()).
        // El firmware SÍ se re-resuelve cuando viene de fuera: entre el
        // pre-chequeo y este launch() corre ensureFirmwareForEmbedded,
        // que pudo instalarlo, y decide() debe verlo listo. Es solo
        // una lectura del marcador, no un escaneo del juego.
        val checked = prerequisites ?: checkPrerequisites(library)
        logTiming("launch", launchStartedAtMillis, "Prerrequisitos comprobados (listo=${checked.ready})")
        val pre = if (prerequisites != null) {
            val freshFirmware = FirmwareResolver.resolve(library.vitaRoot)
            checked.copy(firmwareStatus = freshFirmware, firmwareInstalled = freshFirmware.ready)
        } else {
            checked
        }
        if (!pre.ready) {
            return LaunchOutcome.Blocked(pre.missing.joinToString("\n"))
        }
        // Sesión correcta ANTES de tocar nada: si falla, no se lanza.
        // Con un .pkg NO hay sesión que activar: el juego aún no está
        // extraído y no hay savedata que respaldar (con el núcleo
        // embebido, los saves vivirán en vita/ux0 una vez instalado).
        if (sessionManager != null && !pre.pkgOnly) {
            val activated = sessionManager.activateForLaunch()
            if (activated.isFailure) {
                return LaunchOutcome.Blocked(
                    activated.exceptionOrNull()?.message ?: "No se pudo activar la sesión.",
                )
            }
            logTiming("launch", launchStartedAtMillis, "Sesión activada para el lanzamiento")
        } else if (pre.pkgOnly) {
            LauncherLog.append(context, "launch", "Juego solo en .pkg: sin sesiones (aún no hay savedata que respaldar; con el núcleo embebido los saves vivirán en vita/ux0).")
        }
        if (settings.closeBackgroundApps) {
            val killed = closeBackgroundApps()
            LauncherLog.append(context, "launch", "Apps en segundo plano cerradas (best-effort): $killed procesos")
        }
        // R4-P0-7: config.yml se escribe UNA sola vez por JUGAR: el
        // llamador ya lo escribió antes del firmware y pasa
        // configAlreadyWritten=true. Con false (otros caminos) se
        // escribe aquí como antes. Además writeConfig es idempotente:
        // si el contenido es idéntico, no reescribe ni registra nada.
        val configFile = if (configAlreadyWritten) {
            File(library.vitaRoot, "config.yml")
        } else {
            Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu)
        }
        logTiming("launch", launchStartedAtMillis, "config.yml listo")
        // Turnip: el config.yml ya dice custom-driver-name, pero el
        // nombre solo vale si el driver está instalado en el núcleo
        // (<filesDir>/driver/<nombre>/). Si el zip se instaló cuando
        // aún no había núcleo o el registro se perdió, se
        // instala ahora, antes de arrancar el juego. Best-effort: si
        // falla, el núcleo usa el driver del sistema y el juego se
        // lanza igual (nunca bloquea el lanzamiento).
        ensureTurnipDriverInstalled(settings)
        val titleId = pre.titleId ?: return LaunchOutcome.Blocked("No se pudo determinar el Title ID instalado.")

        // 5. Núcleo embebido: el motor integrado es el camino
        //    principal. La decisión es pura (EmbeddedGameLauncher.decide);
        //    aquí solo se ejecuta. Si el intento embebido falla, se
        //    devuelve EmbeddedFailed con el motivo exacto: NUNCA se
        //    salta a la app externa por detrás. La vía externa solo se
        //    alcanza cuando decide() devolvió UseExternal (sin núcleo y
        //    con la opción avanzada activada en Ajustes).
        // R4-P0-8: SIN segundo escaneo: decide() reutiliza el GameStatus
        // que checkPrerequisites() ya escaneó al empezar este launch().
        // Nada cambió en disco entre medias para lo que decide() lee
        // (titleId, instalado, extraído, .pkg, licencia): la sesión se
        // activó (savedata, no el juego), el config es solo config y
        // Turnip es solo el driver; el juego se instala DESPUÉS de
        // decide(), nunca antes.
        when (val decision = EmbeddedGameLauncher.decide(pre.status, Vita3kCoreBridge.available, pre.firmwareStatus.ready, settings.allowExternalVita3k)) {
            is EmbeddedGameLauncher.Decision.UseEmbedded -> {
                // R6-P0: SIN disparo preventivo por ebootValidation
                // antes de loadGame. La validación estructural SELF
                // daba falso negativo con el eboot real instalado y
                // cada JUGAR reinstalaba el .pkg desde 0 (y el .pkg
                // nunca se borraba). Ahora manda loadGame: si dice
                // -3 y hay .pkg guardado, tryLaunchEmbedded
                // reinstala UNA vez y reintenta loadGame UNA vez.
                // La validación del eboot queda solo como diagnóstico
                // en el log (motivo + campos, para el próximo informe
                // del teléfono si el eboot real sigue fallando).
                runCatching {
                    LauncherLog.append(context, "pkg", "Eboot instalado en $titleId (solo diagnóstico, no bloquea JUGAR): ${library.ebootValidation(titleId).logLine()}")
                }
                // P0-1 (2026-11-09): el .pkg guardado ya NO se borra
                // aquí. Se borra después de que loadGame valide el
                // juego, en tryLaunchEmbedded: borrar antes de saber
                // si el juego arranca destruía la única vía de
                // recuperación si el eboot quedó truncado. El
                // work.bin se conserva siempre.
                // R5-A4-4: red de seguridad para update/DLC en .pkg
                // guardados pero no instalados (ver
                // installStoredAddonPkgs): con el juego ya extraído,
                // ningún camino los instalaba nunca.
                installStoredAddonPkgs(library, titleId, onInstallProgress)
                return when (val attempt = tryLaunchEmbedded(library, titleId, configFile.absolutePath, benchmark, onInstallProgress, launchStartedAtMillis)) {
                    is EmbeddedAttempt.Ok -> attempt.outcome
                    is EmbeddedAttempt.Failed -> {
                        LauncherLog.append(context, "launch", "Intento embebido falló: ${attempt.reason}")
                        LaunchOutcome.EmbeddedFailed(attempt.reason)
                    }
                }
            }
            is EmbeddedGameLauncher.Decision.UseEmbeddedPkg -> {
                // Instalar el .pkg con el propio núcleo y luego arrancar.
                // Si la instalación o el arranque fallan, se devuelve el
                // motivo exacto (EmbeddedFailed), sin vía externa.
                val embeddedTitle = TitleId.fromString(decision.titleId)
                val installed = embeddedTitle?.let { installPkgWithEmbeddedCore(library, it, onInstallProgress) }
                    ?: Result.failure(IllegalStateException("Title ID no válido: ${decision.titleId}"))
                if (installed.isFailure) {
                    // P1-12: instalado pero sin licencia (no es un fallo
                    // de instalación; no reinstalar, ofrecer work.bin).
                    installedWithoutLicenseFailure(installed.exceptionOrNull())?.let { return it }
                    val reason = installed.exceptionOrNull()?.message
                        ?: "No se pudo instalar el .pkg en el núcleo embebido."
                    LauncherLog.append(context, "launch", "Instalación .pkg embebida falló: $reason")
                    return LaunchOutcome.EmbeddedFailed("No se pudo instalar el juego en el motor integrado: $reason")
                }
                // P1-4: primera partida tras instalar el .pkg. Al inicio
                // de launchImpl la activación se saltó (pkgOnly: no había
                // savedata que respaldar), pero ahora el juego ya está
                // extraído y installedTitleId() ya lo ve: activar la
                // sesión aquí, antes de arrancar, para que esta primera
                // partida cuente el tiempo y haga snapshot. Si falla, se
                // bloquea con el motivo, igual que en la rama de juego
                // extraído.
                if (sessionManager != null) {
                    val activated = sessionManager.activateForLaunch()
                    if (activated.isFailure) {
                        return LaunchOutcome.Blocked(
                            activated.exceptionOrNull()?.message ?: "No se pudo activar la sesión.",
                        )
                    }
                }
                return when (val attempt = tryLaunchEmbedded(library, titleId, configFile.absolutePath, benchmark, onInstallProgress, launchStartedAtMillis)) {
                    is EmbeddedAttempt.Ok -> attempt.outcome
                    is EmbeddedAttempt.Failed -> {
                        LauncherLog.append(context, "launch", "Intento embebido falló tras instalar el .pkg: ${attempt.reason}")
                        LaunchOutcome.EmbeddedFailed(attempt.reason)
                    }
                }
            }
            is EmbeddedGameLauncher.Decision.Blocked -> {
                return LaunchOutcome.Blocked(decision.reason)
            }
            is EmbeddedGameLauncher.Decision.UseExternal -> {
                LauncherLog.append(context, "launch", "Vía externa (opción avanzada): ${decision.reason}")
            }
        }
        // Solo se llega aquí con UseExternal (sin núcleo embebido y
        // opción avanzada activada). Si la app externa no está
        // instalada, se bloquea con el motivo exacto.
        val intent = context.packageManager.getLaunchIntentForPackage(VITA3K_PACKAGE)
            ?: return LaunchOutcome.Blocked(
                "La opción avanzada está activada, pero la app externa no está instalada en este teléfono.\n\n" +
                    "Desactívala en Ajustes → General → Motor, o instala la app externa desde su página oficial de releases. " +
                    // R2-P1-02: la ruta absoluta del config.yml lleva el
                    // nombre del paquete (privacidad): solo la relativa.
                    "El config.yml ya quedó escrito en:\nvita3k/config.yml",
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (!pre.firmwareStatus.ready) {
                LauncherLog.append(context, "launch", "App externa lanzada sin firmware local (aviso mostrado)")
                LaunchOutcome.LaunchedExternalFirmwareWarning(configFile.absolutePath)
            } else {
                LauncherLog.append(context, "launch", "App externa lanzada; config en ${configFile.absolutePath}")
                LaunchOutcome.LaunchedExternal(configFile.absolutePath)
            }
        } catch (e: Exception) {
            LaunchOutcome.Blocked("No se pudo abrir la app externa: ${e.message}")
        }
    }

    /**
     * P1-1: apaga la sesión nativa anterior si ya no hay partida en
     * curso, para que el init() siguiente re-lea config.yml de disco
     * con los ajustes cambiados entre partidas. Contrato JNI: solo
     * desde el hilo principal (por eso el withContext(Main)) y solo
     * sin partida en curso (isAppRunning false). Si devuelve
     * SESSION_ACTIVE (-4), no se forzó nada: se registra y se sigue
     * con el lanzamiento (el init será no-op y los ajustes viejos
     * seguirán; hace falta matar el proceso para que entren). Sin
     * sesión (-2) o sin núcleo (-1) no hay nada que apagar.
     */
    private suspend fun destroyStaleSessionIfIdle() {
        if (!Vita3kCoreBridge.available || Vita3kCoreBridge.isAppRunning()) return
        val rc = withContext(Dispatchers.Main) {
            runCatching { Vita3kCoreBridge.destroySession() }.getOrDefault(Vita3kCoreBridge.NOT_IMPLEMENTED)
        }
        when (rc) {
            Vita3kCoreBridge.OK ->
                LauncherLog.append(context, "launch", "Sesión anterior del núcleo destruida; el próximo init re-lee config.yml")
            -2 ->
                LauncherLog.append(context, "launch", "Sin sesión anterior que destruir antes de init")
            Vita3kCoreBridge.SESSION_ACTIVE ->
                LauncherLog.append(context, "launch", "destroySession devolvió SESSION_ACTIVE (-4): la partida seguía activa o parándose; no se destruyó nada. Si los ajustes no se aplican, cierra la app por completo (matar el proceso) y vuelve a intentarlo.")
            else ->
                LauncherLog.append(context, "launch", "destroySession devolvió $rc antes de init; se sigue con el lanzamiento (si la sesión vieja sigue, los ajustes nuevos pueden no aplicarse hasta matar el proceso).")
        }
    }

    /**
     * Intenta el lanzamiento embebido: migra el layout una vez
     * (ux0 -> vita/ux0), inicializa el núcleo, valida el Title ID y
     * abre EmbeddedGameActivity (el hilo SDL arranca el juego).
     * Devuelve Ok(LaunchedEmbedded) si la Activity se abrió, o
     * Failed(motivo exacto) si algo falló. Nunca lanza excepciones
     * ni salta a la app externa: el motivo llega al diálogo
     * (Reintentar / Reportar).
     */
    private suspend fun tryLaunchEmbedded(
        library: GameLibraryRepository,
        titleId: String,
        configPath: String,
        benchmark: Boolean = false,
        onInstallProgress: EmbeddedPkgInstaller.ProgressListener? = null,
        launchStartedAtMillis: Long = 0L,
    ): EmbeddedAttempt {
        return try {
            when (val migration = EmbeddedCoreLayout.migrateToEmbeddedLayout(library.vitaRoot)) {
                is EmbeddedCoreLayout.MigrationResult.Conflict -> {
                    return EmbeddedAttempt.Failed(
                        "No se pudo preparar el juego para el motor integrado: ${migration.reason}",
                    )
                }
                is EmbeddedCoreLayout.MigrationResult.Migrated -> {
                    LauncherLog.append(context, "launch", "Layout migrado a vita/ux0 (${migration.filesMoved} archivos)")
                    // R5-A4-7: la migración mueve el juego de sitio:
                    // el tamaño cacheado de la ruta vieja ya no vale
                    // (la clave de la caché es la ruta absoluta).
                    library.invalidateInstalledSizes(titleId)
                }
                else -> { /* AlreadyMigrated / NothingToMigrate: nada que hacer */ }
            }
            // P1-1: si quedó una sesión vieja (p. ej. un init sin Activity
            // detrás, o una Activity que no llegó a destruirla), se apaga
            // aquí antes de init para que el nuevo init re-lea
            // config.yml con los ajustes actuales. Solo sin partida en
            // curso (isAppRunning false); destroySession corre en el hilo
            // principal, por contrato JNI.
            destroyStaleSessionIfIdle()
            val initRc = Vita3kCoreBridge.init(library.vitaRoot.absolutePath)
            if (initRc != Vita3kCoreBridge.OK) {
                // P2-A4b: el -1 no es "código -1" a secas: es "sin núcleo
                // en esta APK". Se registra qué biblioteca falló.
                if (initRc == Vita3kCoreBridge.NOT_IMPLEMENTED) {
                    LauncherLog.append(context, "launch", "init del núcleo devolvió -1 (sin núcleo en esta APK); última carga: ${Vita3kCoreBridge.lastLoadError}")
                } else {
                    LauncherLog.append(context, "launch", "init del núcleo falló (código $initRc)")
                }
                return EmbeddedAttempt.Failed(
                    initFailureMessage(initRc, Vita3kCoreBridge.lastLoadError),
                )
            }
            // Log de instrumentación (crash ~15:06 sin stack trace):
            // deja constancia de cada paso del arranque para que el
            // próximo reporte muestre hasta dónde llegó el intento.
            // Ronda 0.2.64 (H-4): con el t0 de JUGAR, cada hito lleva
            // sus ms ("init OK (Xms desde JUGAR)").
            logTiming("launch", launchStartedAtMillis, "Núcleo inicializado (init OK)")
            // La lista de apps del núcleo es la del momento de init: si
            // el .pkg se instaló en este mismo lanzamiento (o en otro
            // anterior del mismo proceso, con init ya hecho), loadGame
            // diría -3 (no instalado) aunque el juego ya esté dentro.
            // Re-escanear antes de validar es lo que hace la UI de
            // upstream tras instalar; sin núcleo es no-op.
            Vita3kCoreBridge.refreshAppsList()
            logTiming("launch", launchStartedAtMillis, "Lista de apps del núcleo re-escaneada; validando $titleId")
            var loadRc = Vita3kCoreBridge.loadGame(titleId)
            logTiming("launch", launchStartedAtMillis, formatLoadGameResult(titleId, loadRc))
            // R6-P0: loadGame manda. Si dice -3 (no instalado) y hay
            // .pkg guardado, reinstalar UNA vez y reintentar loadGame
            // UNA vez (shouldReinstallAndRetryLoadGame, puro). Es el
            // único reintento del lanzamiento: antes la reinstalación
            // la disparaba ebootValidation antes de loadGame en CADA
            // JUGAR, con su falso negativo del SELF real. Un .pkg
            // que no es el juego (un update guardado como juego) no
            // entra en bucle: falla tras el único reintento y el
            // motivo de abajo dice cómo salir de él.
            // Ronda 0.2.64 (H-2 tiempo de carga): TOPE ENTRE
            // LANZAMIENTOS. El while de abajo reinstala UNA vez por
            // lanzamiento, pero sin estado persistente lo hacía en CADA
            // JUGAR mientras loadGame siguiera devolviendo -3 (1,14 GB
            // por intento). Si este .pkg ya se reinstaló una vez y
            // siguió sin validar (mismo archivo: mismo nombre, tamaño
            // y mtime), NO se reinstala más: se marca bloqueado y el
            // -3 de abajo cae en el motivo con el botón "Borrar ese
            // .pkg". Un archivo distinto (reimportado) resetea el
            // tope solo; un loadGame con éxito limpia la entrada.
            val retryState = readPkgRetryState(library.vitaRoot)
            val storedPkgFile = library.findPkgGameFile(titleId)
            val pkgBlocked = storedPkgFile != null &&
                retryState.isBlocked(PkgRetryState.gameKey(titleId), storedPkgFile)
            if (pkgBlocked && storedPkgFile != null) {
                lastBlockedPkgPath = storedPkgFile.absolutePath
                lastBlockedPkgTitleId = titleId
                LauncherLog.append(context, "pkg", "loadGame -3 con .pkg ya reinstalado una vez (${storedPkgFile.name}): NO se reinstala más hasta que cambie el archivo o se borre.")
            }
            var loadRetried = false
            while (!pkgBlocked && shouldReinstallAndRetryLoadGame(loadRc, library.findPkgGameFile(titleId) != null, loadRetried)) {
                loadRetried = true
                val pkgFile = library.findPkgGameFile(titleId) ?: break
                LauncherLog.append(context, "pkg", "loadGame devolvió -3 con .pkg guardado: reinstalando $titleId una vez y reintentando.")
                val reinstalled = reinstallGamePkg(library, titleId, pkgFile, onInstallProgress)
                if (reinstalled.isFailure) {
                    // P1-12: instalado pero sin licencia (no es un
                    // fallo de instalación; no reinstalar, ofrecer
                    // work.bin). El motivo es el mismo que en
                    // launchImpl para este caso.
                    val withoutLicense = reinstalled.exceptionOrNull() as? InstalledWithoutLicenseException
                    if (withoutLicense != null) {
                        return EmbeddedAttempt.Failed(
                            "El juego quedó instalado en el motor integrado pero sin licencia: ${withoutLicense.message}",
                        )
                    }
                    val reason = reinstalled.exceptionOrNull()?.message ?: "No se pudo reinstalar el .pkg."
                    LauncherLog.append(context, "launch", "Reinstalación del .pkg falló: $reason")
                    return EmbeddedAttempt.Failed("No se pudo preparar el juego en el motor integrado: $reason")
                }
                loadRc = Vita3kCoreBridge.loadGame(titleId)
                logTiming("launch", launchStartedAtMillis, formatLoadGameResult(titleId, loadRc))
                // La reinstalación no cambió nada: el .pkg queda
                // bloqueado para los próximos JUGAR (ver arriba).
                if (loadRc != Vita3kCoreBridge.OK) {
                    writePkgRetryState(
                        library.vitaRoot,
                        readPkgRetryState(library.vitaRoot).recordFailedReinstall(
                            PkgRetryState.gameKey(titleId), pkgFile, System.currentTimeMillis(),
                        ),
                    )
                    lastBlockedPkgPath = pkgFile.absolutePath
                    lastBlockedPkgTitleId = titleId
                }
            }
            if (loadRc != Vita3kCoreBridge.OK) {
                val storedPkg = if (loadRc == LOAD_GAME_NOT_INSTALLED) library.findPkgGameFile(titleId) else null
                val detail = when (loadRc) {
                    -1 -> "el núcleo no está presente en esta APK (código -1)"
                    -2 -> "el motor integrado no encontró la sesión activa"
                    -3 -> "el motor integrado no encontró el juego instalado ($titleId)" +
                        if (lastBlockedPkgPath != null && storedPkg != null) {
                            // Ronda 0.2.64 (H-2): el .pkg ya se intentó
                            // reinstalar una vez y siguió sin validar:
                            // no se reinstala más hasta que cambie el
                            // archivo. El diálogo ofrece "Borrar ese
                            // .pkg" (un toque): borra el archivo y su
                            // entrada de reintentos; después se vuelve
                            // a importar el juego completo.
                            ". Ya se intentó reinstalar este .pkg una vez (${storedPkg.absolutePath}) y siguió sin validar: " +
                                "no se reinstala más hasta que cambie el archivo. " +
                                "Pulsa «Borrar ese .pkg» para borrarlo y vuelve a importar el juego completo (no un update)."
                        } else if (storedPkg != null) {
                            // R6-P1-02(b): el .pkg guardado se instaló
                            // en otro sitio (parece un update, no el
                            // juego: el núcleo lo instala por su SFO
                            // en patch/ y loadGame sigue sin ver el
                            // juego). Reinstalarlo otra vez no cambia
                            // nada: hay que borrarlo. La ruta exacta
                            // va en el mensaje y el diálogo ofrece
                            // «Borrar ese .pkg» (un toque, ronda
                            // 0.2.64 H-2).
                            ". El .pkg guardado (${storedPkg.absolutePath}) parece un update, no el juego: " +
                                "cada JUGAR lo reinstala igual sin que el juego arranque. " +
                                "Pulsa «Borrar ese .pkg» para borrarlo " +
                                "y vuelve a importar el juego completo (no el update)."
                        } else {
                            ""
                        }
                    else -> "el motor integrado devolvió el código $loadRc al validar el juego"
                }
                return EmbeddedAttempt.Failed(
                    "El motor integrado no pudo arrancar el juego: $detail. Vuelve a intentarlo; si sigue igual, repórtalo.",
                )
            }
            // R6-P0: el núcleo validó el juego (loadGame == 0): el
            // eboot es válido por definición y el .pkg guardado
            // (1,14 GB) ya es redundante y se borra solo, SIN la
            // puerta de ebootValidation (su falso negativo con el
            // SELF real era lo que dejaba el .pkg para siempre).
            // Es el ÚNICO punto donde se borra el .pkg del juego:
            // antes se borraba en el install, antes de saber si el
            // juego arranca. deleteStoredGamePkg registra siempre
            // su decisión en el log. El work.bin se conserva siempre.
            runCatching { library.deleteStoredGamePkg(titleId, requireValidEboot = false) }
            // Ronda 0.2.64 (H-2): loadGame validó el juego: la entrada
            // de reintentos de este .pkg se limpia (ya no hay nada que
            // bloquear) y el puntero al .pkg bloqueado se quita.
            runCatching {
                writePkgRetryState(library.vitaRoot, readPkgRetryState(library.vitaRoot).recordSuccess(PkgRetryState.gameKey(titleId)))
            }
            lastBlockedPkgPath = null
            lastBlockedPkgTitleId = null
            // Peso del juego (2026-11-09): el PUP guardado (133 MB)
            // también es redundante cuando el firmware ya está
            // instalado en el núcleo (hay marcador): solo hace falta
            // para instalar. Se borra en el mismo punto, tras validar
            // el juego. El marcador se conserva.
            runCatching {
                if (FirmwareResolver.deleteSavedPupIfInstalled(library.vitaRoot)) {
                    LauncherLog.append(context, "firmware", "PUP guardado borrado: firmware ya instalado en el núcleo")
                }
            }
            // REA 0.2.1 oficial (0.2.49): SDL_ANDROID_ALLOW_RECREATE_ACTIVITY=1
            // evita que SDL mate el proceso con System.exit(0) al recrear
            // la Activity en el mismo proceso (2º JUGAR). Se pone aquí,
            // con el .so ya cargado (init OK) y antes de crear la
            // Activity, que es cuando SDLActivity.onCreate lee la
            // variable. nativeSetenv es public static en SDLActivity
            // (vendorizada). Best-effort: si falla, el lanzamiento sigue.
            // (El intento en C++ con SDL_setenv no compila: el símbolo
            // no está declarado en los headers SDL de esta build.)
            runCatching {
                SDLActivity.nativeSetenv("SDL_ANDROID_ALLOW_RECREATE_ACTIVITY", "1")
            }
            val intent = EmbeddedGameActivity.createLaunchIntent(context, titleId, "Child of Light", benchmark, launchStartedAtMillis)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // P2-A4a: un fallo anterior ya consumido o no no debe
            // sobrevivir a este lanzamiento: si esta Activity falla su
            // init, ella misma anota el fallo nuevo.
            lastEmbeddedLaunchFailure = null
            context.startActivity(intent)
            logTiming("launch", launchStartedAtMillis, "Núcleo embebido lanzó $titleId (EmbeddedGameActivity)")
            EmbeddedAttempt.Ok(LaunchOutcome.LaunchedEmbedded(configPath))
        } catch (e: Throwable) {
            // Throwable (no solo Exception): un OutOfMemoryError aquí
            // cerraba la app en vez de mostrar el diálogo con el motivo.
            LauncherLog.append(context, "launch", "No se pudo abrir EmbeddedGameActivity (${e.javaClass.simpleName}): ${e.message}")
            EmbeddedAttempt.Failed("No se pudo abrir la pantalla del juego: ${e.message ?: "error desconocido"}")
        }
    }

    /**
     * Reinstala el .pkg del juego desde un archivo concreto (puede
     * vivir en el ux0 antiguo: findPkgGameFile lo busca en los dos).
     * R6-P0: es el reintento acotado de tryLaunchEmbedded cuando
     * loadGame dice -3 y hay .pkg guardado (UNA vez por lanzamiento):
     * ya NO es el disparo preventivo del UseEmbedded por
     * ebootValidation (su falso negativo reinstalaba en cada JUGAR).
     * installGamePkg no sirve aquí porque busca el .pkg solo en el
     * ux0 efectivo.
     */
    private suspend fun reinstallGamePkg(
        library: GameLibraryRepository,
        titleId: String,
        pkgFile: File,
        onInstallProgress: EmbeddedPkgInstaller.ProgressListener? = null,
    ): Result<Unit> {
        LauncherLog.append(context, "pkg", "Reinstalando .pkg en el núcleo embebido: $titleId")
        val installer = EmbeddedPkgInstaller(library)
        val progress: EmbeddedPkgInstaller.ProgressListener =
            onInstallProgress ?: EmbeddedPkgInstaller.ProgressListener { _, _ -> }
        // R6-P1-01: sin espacio no se empieza la reinstalación: el
        // .pkg ya guardado más su extracción son ~2,28 GB de pico
        // (mismo motivo que en installPkgWithEmbeddedCore). Antes se
        // exigía solo el .pkg y la extracción fallaba a mitad.
        val neededBytes = pkgFile.length() + GameLibraryRepository.PKG_EXTRACTION_MARGIN_BYTES
        if (!library.hasFreeSpace(library.ux0Dir, neededBytes)) {
            val reason = GameLibraryRepository.freeSpaceMessage(neededBytes, library.freeBytes(library.ux0Dir), pkgFile.length())
            LauncherLog.append(context, "pkg", "Reinstalación .pkg embebida bloqueada: $reason")
            return Result.failure(IllegalStateException(reason))
        }
        val result = installer.installPkgFile(pkgFile, library.findPkgWorkBinFile(pkgFile), progress)
        // R4-P2-7: el núcleo acaba de escribir en ux0/app/<titleId>:
        // el tamaño cacheado ya no vale.
        result.onSuccess { library.invalidateInstalledSizes(titleId) }
        result.onFailure {
            LauncherLog.append(context, "pkg", "Reinstalación .pkg embebida falló: ${it.message}")
        }
        // P0-1 (2026-11-09): el .pkg ya NO se borra aquí. Se borra
        // después de que loadGame valide el juego, en
        // tryLaunchEmbedded (al que llega el flujo tras reinstalar).
        // El work.bin se conserva siempre.
        return result
    }

    /**
     * R5-A4-4: red de seguridad para update/DLC en .pkg guardados
     * pero no instalados. Con el juego ya extraído (UseEmbedded),
     * ningún camino instalaba esos .pkg: ni JUGAR, ni el arranque,
     * ni el refresco. Escenarios reales: un update/DLC importado
     * cuando no había núcleo o falló su instalación (el .pkg quedó
     * guardado "para reintentarlo" sin ningún reintento), o un .pkg
     * de DLC/update en el ux0 antiguo tras la migración (la fusión
     * mueve los archivos pero no los instala). Se instala aquí, antes
     * de arrancar: primero el update y luego cada DLC en pkg/dlc/
     * (ambos ux0, sin duplicar, igual que installPkgWithEmbeddedCore).
     * Solo se instala lo que aún NO está instalado (extraído):
     * reinstalar en cada JUGAR lo que ya está dentro costaría minutos
     * por partida. Un fallo de un addon NO bloquea el lanzamiento
     * (log y seguir); un éxito invalida los tamaños. No se hace en
     * refreshStatusOnce: instalar es pesado y exige el núcleo
     * inicializado. Nunca lanza excepciones.
     */
    private suspend fun installStoredAddonPkgs(
        library: GameLibraryRepository,
        titleId: String,
        onInstallProgress: EmbeddedPkgInstaller.ProgressListener? = null,
    ) {
        if (!Vita3kCoreBridge.available) return
        if (!FirmwareResolver.resolve(library.vitaRoot).ready) return
        val title = TitleId.fromString(titleId) ?: return
        val installer = EmbeddedPkgInstaller(library)
        val progress: EmbeddedPkgInstaller.ProgressListener =
            onInstallProgress ?: EmbeddedPkgInstaller.ProgressListener { _, _ -> }
        // Update guardado, solo si no está ya instalado (extraído).
        // R6-P2-05: mismo criterio que scanStatus (isUpdateInstalled,
        // patch/ de AMBOS ux0), no solo el patch/ efectivo.
        val patchPkg = library.findPkgPatchFile(title.id)
        if (!library.isUpdateInstalled(title.id) && patchPkg != null) {
            // Ronda 0.2.64 (H-2): mismo tope que el .pkg del juego: un
            // update cuya clave nunca casa se reinstalaba en cada
            // JUGAR. Si ya falló una vez con el mismo archivo, se
            // salta (log) hasta que el archivo cambie o se borre.
            val updateKey = PkgRetryState.addonKey(title.id, patchPkg.name)
            val updateBlocked = readPkgRetryState(library.vitaRoot).isBlocked(updateKey, patchPkg)
            if (updateBlocked) {
                LauncherLog.append(context, "pkg", "Update guardado ${patchPkg.name} bloqueado por el tope de reinstalación (ya falló una vez con este archivo): no se reinstala.")
            } else {
                // R6-P2-01: sin espacio para este addon se salta con log,
                // sin bloquear el lanzamiento del juego base.
                val patchNeeded = GameLibraryRepository.pkgInstallNeededBytes(patchPkg.length())
                if (!library.hasFreeSpace(library.ux0Dir, patchNeeded)) {
                    LauncherLog.append(context, "pkg", "Update guardado no se instala antes de jugar: ${GameLibraryRepository.freeSpaceMessage(patchNeeded, library.freeBytes(library.ux0Dir), patchPkg.length())}")
                } else {
                    installer.installUpdatePkg(title, progress)
                        .onSuccess {
                            LauncherLog.append(context, "pkg", "Update guardado instalado antes de jugar: ${title.id}")
                            library.invalidateInstalledSizes(title.id)
                            // R6-P1-06: el .pkg de update ya no hace falta:
                            // se borra (con su guarda: solo si quedó instalado).
                            library.deleteStoredAddonPkg(patchPkg, PkgKind.UPDATE, title.id)
                            writePkgRetryState(library.vitaRoot, readPkgRetryState(library.vitaRoot).recordSuccess(updateKey))
                        }
                        .onFailure {
                            LauncherLog.append(context, "pkg", "Update guardado no se pudo instalar antes de jugar: ${it.message}")
                            writePkgRetryState(
                                library.vitaRoot,
                                readPkgRetryState(library.vitaRoot).recordFailedReinstall(updateKey, patchPkg, System.currentTimeMillis()),
                            )
                        }
                }
            }
        }
        // DLCs en .pkg en AMBOS ux0 (efectivo y antiguo), sin duplicar
        // el mismo DLC: el efectivo manda si está en los dos. Solo los
        // que no están ya instalados (extraídos): la coincidencia es
        // por clave de contenido (el nombre del archivo del .pkg puede
        // ser un hash; la carpeta en addcont/ lleva el nombre corto
        // del contenido, leído del header del .pkg).
        // R6-P2-05: las carpetas instaladas se leen de AMBOS ux0 (antes
        // solo del efectivo: un DLC instalado en el antiguo se
        // reinstalaba en cada JUGAR).
        val installedDlcKeys = listOf(library.ux0Dir, EmbeddedCoreLayout.legacyUx0Dir(library.vitaRoot))
            .flatMap { ux0 -> File(ux0, "addcont/${title.id}").listFiles().orEmpty().toList() }
            .filter { it.isDirectory }
            .map { GameLibraryRepository.dlcMergeKey(it.name) }
            .toSet()
        val dlcPkgs = linkedMapOf<String, File>()
        for (ux0 in listOf(library.ux0Dir, EmbeddedCoreLayout.legacyUx0Dir(library.vitaRoot))) {
            File(File(ux0, "pkg"), "dlc").listFiles()
                ?.filter { it.isFile && it.name.lowercase().endsWith(".pkg") }
                ?.forEach { pkg -> dlcPkgs.putIfAbsent(pkg.nameWithoutExtension, pkg) }
        }
        dlcPkgs.forEach { (dlcId, pkg) ->
            val contentKey = runCatching {
                pkg.inputStream().use { PkgHeaderParser.parse(it) }
            }.getOrNull()?.getOrNull()?.contentName?.let { GameLibraryRepository.dlcMergeKey(it) }
                ?: GameLibraryRepository.dlcMergeKey(dlcId)
            if (contentKey in installedDlcKeys) return@forEach
            // Ronda 0.2.64 (H-2): mismo tope que el update: un DLC
            // cuya clave nunca casa se reinstalaba en cada JUGAR.
            val dlcKey = PkgRetryState.addonKey(title.id, pkg.name)
            if (readPkgRetryState(library.vitaRoot).isBlocked(dlcKey, pkg)) {
                LauncherLog.append(context, "pkg", "DLC guardado $dlcId bloqueado por el tope de reinstalación (ya falló una vez con este archivo): no se reinstala.")
                return@forEach
            }
            // R6-P2-01: sin espacio para este addon se salta con log,
            // sin bloquear el lanzamiento del juego base.
            val dlcNeeded = GameLibraryRepository.pkgInstallNeededBytes(pkg.length())
            if (!library.hasFreeSpace(library.ux0Dir, dlcNeeded)) {
                LauncherLog.append(context, "pkg", "DLC guardado $dlcId no se instala antes de jugar: ${GameLibraryRepository.freeSpaceMessage(dlcNeeded, library.freeBytes(library.ux0Dir), pkg.length())}")
                return@forEach
            }
            installer.installPkgFile(pkg, library.findPkgWorkBinFile(pkg), progress)
                .onSuccess {
                    LauncherLog.append(context, "pkg", "DLC guardado instalado antes de jugar: $dlcId")
                    library.invalidateInstalledSizes(title.id)
                    // R6-P1-06: el .pkg de DLC ya no hace falta: se
                    // borra (con su guarda: solo si quedó instalado).
                    library.deleteStoredAddonPkg(pkg, PkgKind.DLC, title.id)
                    writePkgRetryState(library.vitaRoot, readPkgRetryState(library.vitaRoot).recordSuccess(dlcKey))
                }
                .onFailure {
                    LauncherLog.append(context, "pkg", "DLC guardado $dlcId no se pudo instalar antes de jugar: ${it.message}")
                    writePkgRetryState(
                        library.vitaRoot,
                        readPkgRetryState(library.vitaRoot).recordFailedReinstall(dlcKey, pkg, System.currentTimeMillis()),
                    )
                }
        }
    }

    /**
     * Instala el .pkg del juego con el propio núcleo embebido
     * (EmbeddedPkgInstaller), y después el update y los DLCs
     * guardados en .pkg: sin ellos el usuario jugaría solo el juego
     * base. El progreso real del núcleo llega por onInstallProgress
     * (null = no se muestra, como antes) y se registra inicio y fin
     * en el log. Devuelve Result: un fallo del juego base bloquea el
     * lanzamiento con el motivo exacto; un fallo del update/DLC no
     * bloquea el lanzamiento del juego base, pero queda registrado
     * (no silencioso).
     */
    private suspend fun installPkgWithEmbeddedCore(
        library: GameLibraryRepository,
        titleId: TitleId,
        onInstallProgress: EmbeddedPkgInstaller.ProgressListener? = null,
    ): Result<Unit> {
        LauncherLog.append(context, "pkg", "Instalando .pkg en el núcleo embebido: ${titleId.id}")
        val installer = EmbeddedPkgInstaller(library)
        val progress: EmbeddedPkgInstaller.ProgressListener =
            onInstallProgress ?: EmbeddedPkgInstaller.ProgressListener { _, _ -> }
        // P1-5: los .pkg pueden vivir en el ux0 ANTIGUO (la migración
        // AlreadyMigrated no mueve pkg/): se resuelven con los finders
        // duales (legacy + embebido) y se instala el archivo encontrado,
        // con su work.bin junto a él (findPkgWorkBinFile(pkgFile)). Las
        // rutas efectivas (pkgGameFile/pkgPatchFile/pkgDlcDir) solo ven
        // el ux0 efectivo y dejaban fuera el contenido del legacy.
        val gamePkg = library.findPkgGameFile(titleId.id)
            ?: return Result.failure(IllegalStateException("No está el archivo ${titleId.id}.pkg."))
        // R5-A4-1: sin espacio no se empieza la instalación en el
        // núcleo: el .pkg ya guardado más su extracción son ~2,28 GB
        // de pico y un fallo a mitad dejaría el juego a medias.
        // R6-P1-01: antes se exigía solo gamePkg.length() y la
        // extracción fallaba a mitad.
        val neededBytes = gamePkg.length() + GameLibraryRepository.PKG_EXTRACTION_MARGIN_BYTES
        if (!library.hasFreeSpace(library.ux0Dir, neededBytes)) {
            val reason = GameLibraryRepository.freeSpaceMessage(neededBytes, library.freeBytes(library.ux0Dir), gamePkg.length())
            LauncherLog.append(context, "pkg", "Instalación .pkg embebida bloqueada: $reason")
            return Result.failure(IllegalStateException(reason))
        }
        val result = installer.installPkgFile(gamePkg, library.findPkgWorkBinFile(gamePkg), progress)
        result.onFailure {
            LauncherLog.append(context, "pkg", "Instalación .pkg embebida falló: ${it.message}")
            return Result.failure(it)
        }
        // P0-1 (2026-11-09): el .pkg ya NO se borra aquí. Se borra
        // después de que loadGame valide el juego, en
        // tryLaunchEmbedded (al que llega el flujo tras instalar).
        // El work.bin se conserva siempre.
        val patchPkg = library.findPkgPatchFile(titleId.id)
        if (patchPkg != null) {
            installer.installPkgFile(patchPkg, library.findPkgWorkBinFile(patchPkg), progress)
                .onSuccess {
                    LauncherLog.append(context, "pkg", "Update embebido instalado: ${titleId.id}")
                    // R6-P1-06: el .pkg de update ya no hace falta: se
                    // borra (con su guarda: solo si quedó instalado).
                    library.deleteStoredAddonPkg(patchPkg, PkgKind.UPDATE, titleId.id)
                }
                .onFailure { LauncherLog.append(context, "pkg", "Instalación del update embebido falló: ${it.message}") }
        }
        // DLCs en .pkg en AMBOS ux0 (efectivo y antiguo), sin duplicar
        // el mismo DLC: el efectivo manda si está en los dos.
        val dlcPkgs = linkedMapOf<String, File>()
        for (ux0 in listOf(library.ux0Dir, EmbeddedCoreLayout.legacyUx0Dir(library.vitaRoot))) {
            File(File(ux0, "pkg"), "dlc").listFiles()
                ?.filter { it.isFile && it.name.lowercase().endsWith(".pkg") }
                ?.forEach { pkg -> dlcPkgs.putIfAbsent(pkg.nameWithoutExtension, pkg) }
        }
        dlcPkgs.forEach { (dlcId, pkg) ->
            installer.installPkgFile(pkg, library.findPkgWorkBinFile(pkg), progress)
                .onSuccess {
                    LauncherLog.append(context, "pkg", "DLC embebido instalado: $dlcId")
                    // R6-P1-06: el .pkg de DLC ya no hace falta: se
                    // borra (con su guarda: solo si quedó instalado).
                    library.deleteStoredAddonPkg(pkg, PkgKind.DLC, titleId.id)
                }
                .onFailure { LauncherLog.append(context, "pkg", "Instalación del DLC $dlcId embebido falló: ${it.message}") }
        }
        // R4-P2-7: el núcleo acaba de instalar juego + update + DLCs
        // en ux0: los tamaños cacheados ya no valen. Un fallo de
        // update/DLC no bloquea el lanzamiento, pero lo que sí se
        // instaló también invalida; forzar el re-escaneo es inocuo.
        library.invalidateInstalledSizes(titleId.id)
        return Result.success(Unit)
    }

    /** ¿Existe la librería nativa del núcleo embebido? Se cachea: loadLibrary no debe intentarse en cada recomposición. */
    private var embeddedCoreChecked: Boolean? = null

    fun embeddedCorePresent(): Boolean {
        embeddedCoreChecked?.let { return it }
        val present = try {
            System.loadLibrary(EMBEDDED_LIB_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Exception) {
            false
        }
        embeddedCoreChecked = present
        return present
    }

    /** Versión de Vita3K externo instalado, o null si no está instalado. */
    fun externalVita3kVersion(): String? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(VITA3K_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(VITA3K_PACKAGE, 0)
            }
            info.versionName ?: "instalado (versión desconocida)"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Cierra apps en segundo plano (best-effort).
     * Usa ActivityManager.killBackgroundProcesses, que requiere el
     * permiso KILL_BACKGROUND_PROCESSES. Solo puede cerrar procesos
     * en segundo plano, no apps en primer plano ni servicios del
     * sistema. Devuelve cuántos paquetes se intentó cerrar.
     */
    fun closeBackgroundApps(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val selfPackage = context.packageName
            val running = am.runningAppProcesses ?: return 0
            var count = 0
            for (proc in running) {
                val pkg = proc.pkgList.firstOrNull() ?: continue
                if (pkg == selfPackage) continue
                if (pkg.startsWith("com.android.") || pkg.startsWith("android.")) continue
                if (proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) continue
                am.killBackgroundProcesses(pkg)
                count++
            }
            count
        } catch (_: Exception) {
            0
        }
    }

    /** ¿La app está exenta de optimización de batería? */
    fun isBatteryOptimizationDisabled(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * ¿Hay un mando físico conectado? API real: InputDevice.getDeviceIds()
     * + SOURCE_GAMEPAD / SOURCE_JOYSTICK. Se usa para el interruptor
     * "Ocultar controles táctiles si hay mando conectado".
     */
    fun connectedGamepads(): List<String> {
        return try {
            android.view.InputDevice.getDeviceIds().toList().mapNotNull { id ->
                val device = android.view.InputDevice.getDevice(id) ?: return@mapNotNull null
                val sources = device.sources
                val isGamepad = (sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                    (sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                if (isGamepad) device.name else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isGamepadConnected(): Boolean = connectedGamepads().isNotEmpty()

    /** Intent para pedir la exención de optimización de batería (lo concede el usuario). */
    fun batteryOptimizationIntent(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
