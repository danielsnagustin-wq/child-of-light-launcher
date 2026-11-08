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
import android.provider.MediaStore
import android.provider.Settings
import com.danielsnagustin.childoflight.launcher.core.EmbeddedCoreLayout
import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameActivity
import com.danielsnagustin.childoflight.launcher.core.EmbeddedGameLauncher
import com.danielsnagustin.childoflight.launcher.core.EmbeddedPkgInstaller
import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.data.LARGE_FILE_COPY_BUFFER_BYTES
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.SessionManager
import com.danielsnagustin.childoflight.launcher.data.TurnipDriverRepository
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.GpuDriver
import com.danielsnagustin.childoflight.launcher.model.GpuInfo
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Lanzador del juego.
 *
 * Flujo real (Etapa 1 + sesiones + firmware simplificado):
 * 1. Verifica prerrequisitos: SOLO el juego instalado. El firmware
 *    NO bloquea el primer uso: el launcher solo pide juego + DLCs.
 * 2. Activa la sesión correcta (SessionManager): snapshot de la
 *    sesión actual y restore de la activa en la savedata viva. Si
 *    falla, NO se lanza (mejor no lanzar con la savedata equivocada).
 *    SOLO si el juego está EXTRAÍDO: con un .pkg no hay savedata en
 *    el launcher (vive en Vita3K) y no hay sesión que activar.
 * 3. Si lo pide el ajuste, cierra apps en segundo plano (best-effort).
 * 4. Escribe config.yml con los ajustes actuales.
 * 5. Núcleo embebido (Etapa 2): el motor integrado es el camino
 *    PRINCIPAL. Si el .so existe, JUGAR abre SIEMPRE el juego dentro
 *    del launcher (EmbeddedGameActivity, SDL): la decisión es pura
 *    (EmbeddedGameLauncher.decide) y aquí solo se ejecuta. Con juego
 *    solo en .pkg, primero se instala el .pkg con el propio núcleo
 *    (EmbeddedPkgInstaller) y luego se arranca. Si el intento
 *    embebido falla (migración, init, loadGame o apertura de la
 *    Activity), NO hay salto a la app externa: se devuelve
 *    EmbeddedFailed con el motivo exacto y el launcher muestra el
 *    diálogo (Reintentar / Reportar). Sin firmware local, decide()
 *    bloquea con la frase de firmware (Ajustes → Avanzado →
 *    Firmware); sin núcleo, solo se usa la vía externa si el usuario
 *    activó la opción avanzada en Ajustes (allowExternalVita3k).
 * 6. Vía externa (solo opción avanzada): lanza la app externa
 *    org.vita3k.emulator si está instalada, con un Intent MAIN
 *    normal. No se inventa un deep-link para arrancar un Title ID
 *    concreto: no hay uno documentado verificado (TODO-VERIFY).
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

    companion object {
        const val VITA3K_PACKAGE = "org.vita3k.emulator"
        const val EMBEDDED_LIB_NAME = "Vita3K" // OUTPUT_NAME de upstream (target CMake "vita3k", archivo libVita3K.so)

        /**
         * Una frase, sin pedir que se busque nada por internet.
         * Para el lanzamiento externo (la app externa gestiona su firmware).
         */
        const val FIRMWARE_WARNING_EXTERNAL =
            "Falta el firmware de Vita en el launcher: la app externa lo gestiona al arrancar, " +
                "o puedes importarlo en Ajustes → Avanzado → Firmware (opción avanzada)."
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

    data class Prerequisites(
        val gameInstalled: Boolean,
        val titleId: String?,
        /** true si el juego está EXTRAÍDO (eboot.bin). false = solo .pkg. */
        val gameExtracted: Boolean,
        val firmwareInstalled: Boolean,
        val firmwareStatus: FirmwareStatus,
        val missing: List<String>,
    ) {
        /** Solo el juego es obligatorio. El firmware es aviso, no bloqueo. */
        val ready: Boolean get() = gameInstalled
        /** El juego es solo un .pkg guardado: no hay saves/sesiones en el launcher. */
        val pkgOnly: Boolean get() = gameInstalled && !gameExtracted
    }

    fun checkPrerequisites(library: GameLibraryRepository): Prerequisites {
        val status = library.scanStatus()
        val firmwareStatus = FirmwareResolver.resolve(library.vitaRoot)
        val missing = mutableListOf<String>()
        if (!status.gameInstalled) {
            missing.add("El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).")
        }
        return Prerequisites(
            status.gameInstalled,
            status.titleId?.id,
            status.gameExtracted,
            firmwareStatus.ready,
            firmwareStatus,
            missing,
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
     */
    suspend fun launch(
        library: GameLibraryRepository,
        settings: LauncherSettings,
        sessionManager: SessionManager? = null,
        gpu: GpuInfo? = null,
    ): LaunchOutcome {
        // BUG (auditoría): writeConfig (file.writeText) y checkPrerequisites
        // pueden lanzar IOException y tumbar la corrutina del ViewModel en
        // play()/startBenchmark(). El lanzamiento nunca debe lanzar.
        return try {
            launchImpl(library, settings, sessionManager, gpu)
        } catch (e: Exception) {
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
    ): LaunchOutcome {
        val pre = checkPrerequisites(library)
        if (!pre.ready) {
            return LaunchOutcome.Blocked(pre.missing.joinToString("\n"))
        }
        // Sesión correcta ANTES de tocar nada: si falla, no se lanza.
        // Con un .pkg NO hay sesión que activar: los saves viven en
        // Vita3K, no en el launcher (no hay savedata que respaldar).
        if (sessionManager != null && !pre.pkgOnly) {
            val activated = sessionManager.activateForLaunch()
            if (activated.isFailure) {
                return LaunchOutcome.Blocked(
                    activated.exceptionOrNull()?.message ?: "No se pudo activar la sesión.",
                )
            }
        } else if (pre.pkgOnly) {
            LauncherLog.append(context, "launch", "Juego solo en .pkg: sin sesiones (los saves viven en la app externa).")
        }
        if (settings.closeBackgroundApps) {
            val killed = closeBackgroundApps()
            LauncherLog.append(context, "launch", "Apps en segundo plano cerradas (best-effort): $killed procesos")
        }
        val configFile = Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot, gpu)
        // Turnip: el config.yml ya dice custom-driver-name, pero el
        // nombre solo vale si el driver está instalado en el núcleo
        // (<filesDir>/driver/<nombre>/). Si el zip se instaló cuando
        // aún no había núcleo (Etapa 1) o el registro se perdió, se
        // instala ahora, antes de arrancar el juego. Best-effort: si
        // falla, el núcleo usa el driver del sistema y el juego se
        // lanza igual (nunca bloquea el lanzamiento).
        ensureTurnipDriverInstalled(settings)
        val titleId = pre.titleId ?: return LaunchOutcome.Blocked("No se pudo determinar el Title ID instalado.")

        // 5. Núcleo embebido (Etapa 2): el motor integrado es el camino
        //    principal. La decisión es pura (EmbeddedGameLauncher.decide);
        //    aquí solo se ejecuta. Si el intento embebido falla, se
        //    devuelve EmbeddedFailed con el motivo exacto: NUNCA se
        //    salta a la app externa por detrás. La vía externa solo se
        //    alcanza cuando decide() devolvió UseExternal (sin núcleo y
        //    con la opción avanzada activada en Ajustes).
        val status = library.scanStatus()
        when (val decision = EmbeddedGameLauncher.decide(status, Vita3kCoreBridge.available, pre.firmwareStatus.ready, settings.allowExternalVita3k)) {
            is EmbeddedGameLauncher.Decision.UseEmbedded -> {
                return when (val attempt = tryLaunchEmbedded(library, titleId, configFile.absolutePath)) {
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
                val installed = embeddedTitle?.let { installPkgWithEmbeddedCore(library, it) }
                    ?: Result.failure(IllegalStateException("Title ID no válido: ${decision.titleId}"))
                if (installed.isFailure) {
                    val reason = installed.exceptionOrNull()?.message
                        ?: "No se pudo instalar el .pkg en el núcleo embebido."
                    LauncherLog.append(context, "launch", "Instalación .pkg embebida falló: $reason")
                    return LaunchOutcome.EmbeddedFailed("No se pudo instalar el juego en el motor integrado: $reason")
                }
                return when (val attempt = tryLaunchEmbedded(library, titleId, configFile.absolutePath)) {
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
                    "Desactívala en Ajustes → Avanzado → Motor, o instala la app externa desde su página oficial de releases. " +
                    "El config.yml ya quedó escrito en:\n${configFile.absolutePath}",
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
     * Intenta el lanzamiento embebido: migra el layout una vez
     * (ux0 -> vita/ux0), inicializa el núcleo, valida el Title ID y
     * abre EmbeddedGameActivity (el hilo SDL arranca el juego).
     * Devuelve Ok(LaunchedEmbedded) si la Activity se abrió, o
     * Failed(motivo exacto) si algo falló. Nunca lanza excepciones
     * ni salta a la app externa: el motivo llega al diálogo
     * (Reintentar / Reportar).
     */
    private fun tryLaunchEmbedded(
        library: GameLibraryRepository,
        titleId: String,
        configPath: String,
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
                }
                else -> { /* AlreadyMigrated / NothingToMigrate: nada que hacer */ }
            }
            val initRc = Vita3kCoreBridge.init(library.vitaRoot.absolutePath)
            if (initRc != Vita3kCoreBridge.OK) {
                return EmbeddedAttempt.Failed(
                    "El motor integrado no se pudo iniciar (código $initRc). Vuelve a intentarlo; si sigue igual, repórtalo.",
                )
            }
            // La lista de apps del núcleo es la del momento de init: si
            // el .pkg se instaló en este mismo lanzamiento (o en otro
            // anterior del mismo proceso, con init ya hecho), loadGame
            // diría -3 (no instalado) aunque el juego ya esté dentro.
            // Re-escanear antes de validar es lo que hace la UI de
            // upstream tras instalar; sin núcleo es no-op.
            Vita3kCoreBridge.refreshAppsList()
            val loadRc = Vita3kCoreBridge.loadGame(titleId)
            if (loadRc != Vita3kCoreBridge.OK) {
                val detail = when (loadRc) {
                    -2 -> "el motor integrado no encontró la sesión activa"
                    -3 -> "el motor integrado no encontró el juego instalado ($titleId)"
                    else -> "el motor integrado devolvió el código $loadRc al validar el juego"
                }
                return EmbeddedAttempt.Failed(
                    "El motor integrado no pudo arrancar el juego: $detail. Vuelve a intentarlo; si sigue igual, repórtalo.",
                )
            }
            val intent = EmbeddedGameActivity.createLaunchIntent(context, titleId, "Child of Light")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            LauncherLog.append(context, "launch", "Núcleo embebido lanzó $titleId (EmbeddedGameActivity)")
            EmbeddedAttempt.Ok(LaunchOutcome.LaunchedEmbedded(configPath))
        } catch (e: Exception) {
            LauncherLog.append(context, "launch", "No se pudo abrir EmbeddedGameActivity: ${e.message}")
            EmbeddedAttempt.Failed("No se pudo abrir la pantalla del juego: ${e.message ?: "error desconocido"}")
        }
    }

    /**
     * Instala el .pkg del juego con el propio núcleo embebido
     * (EmbeddedPkgInstaller), y después el update y los DLCs
     * guardados en .pkg: sin ellos el usuario jugaría solo el juego
     * base. El progreso detallado aún no se muestra en UI (TODO:
     * cablear el progreso a la pantalla de JUGAR); por ahora solo se
     * registra inicio y fin en el log. Devuelve Result: un fallo del
     * juego base bloquea el lanzamiento con el motivo exacto; un
     * fallo del update/DLC no bloquea el lanzamiento del juego base,
     * pero queda registrado (no silencioso).
     */
    private suspend fun installPkgWithEmbeddedCore(
        library: GameLibraryRepository,
        titleId: TitleId,
    ): Result<Unit> {
        LauncherLog.append(context, "pkg", "Instalando .pkg en el núcleo embebido: ${titleId.id}")
        val installer = EmbeddedPkgInstaller(library)
        val result = installer.installGamePkg(titleId) { _, _ -> /* progreso: TODO mostrar en UI */ }
        result.onFailure {
            LauncherLog.append(context, "pkg", "Instalación .pkg embebida falló: ${it.message}")
            return Result.failure(it)
        }
        if (library.pkgPatchFile(titleId.id).isFile) {
            installer.installUpdatePkg(titleId) { _, _ -> }
                .onSuccess { LauncherLog.append(context, "pkg", "Update embebido instalado: ${titleId.id}") }
                .onFailure { LauncherLog.append(context, "pkg", "Instalación del update embebido falló: ${it.message}") }
        }
        library.pkgDlcDir().listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".pkg") }
            ?.forEach { pkg ->
                val dlcId = pkg.nameWithoutExtension
                installer.installDlcPkg(dlcId) { _, _ -> }
                    .onSuccess { LauncherLog.append(context, "pkg", "DLC embebido instalado: $dlcId") }
                    .onFailure { LauncherLog.append(context, "pkg", "Instalación del DLC $dlcId embebido falló: ${it.message}") }
            }
        return Result.success(Unit)
    }

    /** ¿Existe la librería nativa del núcleo embebido? En Etapa 1, no. Se cachea: loadLibrary no debe intentarse en cada recomposición. */
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
