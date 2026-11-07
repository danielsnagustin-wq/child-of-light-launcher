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
import com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.SessionManager
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings
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
 * 5. Núcleo embebido (Etapa 2, esqueleto): si Vita3kCoreBridge está
 *    disponible, se intenta init + loadGame(titleId). Sin .so, el
 *    puente devuelve NOT_IMPLEMENTED y se sigue con el fallback.
 *    El núcleo embebido SÍ necesita firmware local: sin él, se
 *    bloquea con UNA frase (no hay otra fuente de firmware dentro
 *    de la app). Con un .pkg no se intenta el núcleo embebido: no
 *    hay juego extraído que cargar.
 * 6. Fallback honesto: lanza la app externa org.vita3k.emulator
 *    (Vita3K oficial de Android) si está instalada, con un Intent
 *    MAIN normal. No se inventa un deep-link para arrancar un Title
 *    ID concreto: no hay uno documentado verificado (TODO-VERIFY).
 *    Si falta firmware LOCAL, solo se AVISA con una frase y se lanza
 *    igual: Vita3K externo gestiona su propio firmware al arrancar
 *    (tiene su propio instalador) y el launcher no puede ver su
 *    almacenamiento privado. Nunca se le pide al usuario que busque
 *    firmware por internet.
 * 7. Si ni siquiera está la app externa, devuelve el error con
 *    instrucciones claras en vez de fallar en silencio.
 *
 * .pkg + Vita3K externo (deliverPkgFiles): el launcher NO puede
 * desencriptar un .pkg ni leer la biblioteca de Vita3K. La única vía
 * honesta es copiar el .pkg + work.bin a Descargas/ChildOfLight/
 * (carpeta compartida que Vita3K sí lee), abrir Vita3K y guiar al
 * usuario para instalarlo ahí una sola vez (File > Install Package
 * (.pkg) > elegir el .pkg > "Select work.bin" > elegir el work.bin).
 * Vita3K Android no expone ningún intent público documentado para
 * instalar un .pkg: no se inventa ninguno.
 */
class Vita3KLauncher(private val context: Context) {

    companion object {
        const val VITA3K_PACKAGE = "org.vita3k.emulator"
        const val EMBEDDED_LIB_NAME = "vita3k"

        /**
         * Una frase, sin pedir que se busque nada por internet.
         * Para el lanzamiento externo (Vita3K gestiona su firmware).
         */
        const val FIRMWARE_WARNING_EXTERNAL =
            "Falta el firmware de Vita en el launcher: Vita3K lo gestiona al arrancar, " +
                "o puedes importarlo en Ajustes → Diagnóstico (opción avanzada)."

        /** Una frase. Para el núcleo embebido (sin otra fuente de firmware). */
        const val FIRMWARE_BLOCKED_EMBEDDED =
            "Falta el firmware de Vita: el núcleo embebido no puede arrancar el juego sin él " +
                "(Ajustes → Diagnóstico → Firmware, opción avanzada)."
    }

    sealed class LaunchOutcome {
        /** config.yml escrito; se lanzó la app externa de Vita3K. */
        data class LaunchedExternal(val configPath: String) : LaunchOutcome()
        /** Se lanzó Vita3K externo sin firmware local (Vita3K lo gestiona). */
        data class LaunchedExternalFirmwareWarning(val configPath: String) : LaunchOutcome()
        /** El núcleo embebido arrancó el juego (Etapa 2, aún no funcional). */
        data class LaunchedEmbedded(val configPath: String) : LaunchOutcome()
        /** El núcleo embebido existe. Sin punto de entrada JNI aún. */
        data class EmbeddedCorePresent(val configPath: String) : LaunchOutcome()
        data class Blocked(val reason: String) : LaunchOutcome()
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
     * (carpeta compartida que Vita3K sí puede leer) para instalarlos
     * ahí una sola vez. Vita3K Android no expone ningún intent público
     * documentado para instalar un .pkg: por eso se copia + se abre
     * Vita3K + se muestra la guía paso a paso. No se inventa un
     * deep-link. Devuelve las rutas públicas copiadas.
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
                FileInputStream(file).use { it.copyTo(out) }
            } ?: throw IllegalStateException("No se pudo escribir $fileName en Descargas.")
            "Descargas/ChildOfLight/$fileName"
        } else {
            // API 28: carpeta pública legacy (permiso WRITE_EXTERNAL_STORAGE maxSdkVersion 28).
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ChildOfLight")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, fileName)
            FileInputStream(file).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
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

    suspend fun launch(
        library: GameLibraryRepository,
        settings: LauncherSettings,
        sessionManager: SessionManager? = null,
    ): LaunchOutcome {
        // BUG (auditoría): writeConfig (file.writeText) y checkPrerequisites
        // pueden lanzar IOException y tumbar la corrutina del ViewModel en
        // play()/startBenchmark(). El lanzamiento nunca debe lanzar.
        return try {
            launchImpl(library, settings, sessionManager)
        } catch (e: Exception) {
            LaunchOutcome.Blocked("No se pudo lanzar: ${e.message}")
        }
    }

    private suspend fun launchImpl(
        library: GameLibraryRepository,
        settings: LauncherSettings,
        sessionManager: SessionManager? = null,
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
            LauncherLog.append(context, "launch", "Juego solo en .pkg: sin sesiones (los saves viven en Vita3K).")
        }
        if (settings.closeBackgroundApps) {
            val killed = closeBackgroundApps()
            LauncherLog.append(context, "launch", "Apps en segundo plano cerradas (best-effort): $killed procesos")
        }
        val configFile = Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot)
        val titleId = pre.titleId ?: return LaunchOutcome.Blocked("No se pudo determinar el Title ID instalado.")

        // 5. Núcleo embebido (Etapa 2): puente JNI si el .so existe.
        // H3: si el intento embebido ya falló (init/loadGame devolvieron
        // error), NO se devuelve EmbeddedCorePresent: ese estado es un
        // callejón sin salida y nunca probaba Vita3K externo. Se salta
        // directo al intent externo.
        var embeddedAttemptFailed = false
        // Con un .pkg no se intenta el núcleo embebido: no hay juego
        // extraído que cargar (loadGame necesitaría eboot.bin).
        if (Vita3kCoreBridge.available && !pre.pkgOnly) {
            if (!pre.firmwareStatus.ready) {
                return LaunchOutcome.Blocked(FIRMWARE_BLOCKED_EMBEDDED)
            }
            val initRc = Vita3kCoreBridge.init(library.vitaRoot.absolutePath)
            if (initRc == Vita3kCoreBridge.OK) {
                val loadRc = Vita3kCoreBridge.loadGame(titleId)
                if (loadRc == Vita3kCoreBridge.OK) {
                    LauncherLog.append(context, "launch", "Núcleo embebido lanzó $titleId")
                    return LaunchOutcome.LaunchedEmbedded(configFile.absolutePath)
                }
                LauncherLog.append(context, "launch", "Puente embebido: loadGame devolvió $loadRc; fallback a externo")
                embeddedAttemptFailed = true
            } else {
                LauncherLog.append(context, "launch", "Puente embebido: init devolvió $initRc; fallback a externo")
                embeddedAttemptFailed = true
            }
        }
        if (embeddedCorePresent() && !embeddedAttemptFailed && !pre.pkgOnly) {
            // .so presente pero sin puente JNI funcional (Etapa 2 en curso).
            LauncherLog.append(context, "launch", "Núcleo embebido detectado; falta el punto de entrada JNI (Etapa 2).")
            return LaunchOutcome.EmbeddedCorePresent(configFile.absolutePath)
        }
        val intent = context.packageManager.getLaunchIntentForPackage(VITA3K_PACKAGE)
            ?: return LaunchOutcome.Blocked(
                "Núcleo embebido no presente (Etapa 2) y Vita3K no está instalado.\n\n" +
                    "Instala Vita3K para Android (org.vita3k.emulator) desde su GitHub oficial " +
                    "para jugar mientras llega la Etapa 2. El config.yml ya quedó escrito en:\n${configFile.absolutePath}",
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (!pre.firmwareStatus.ready) {
                LauncherLog.append(context, "launch", "Vita3K externo lanzado sin firmware local (aviso mostrado)")
                LaunchOutcome.LaunchedExternalFirmwareWarning(configFile.absolutePath)
            } else {
                LauncherLog.append(context, "launch", "Vita3K externo lanzado; config en ${configFile.absolutePath}")
                LaunchOutcome.LaunchedExternal(configFile.absolutePath)
            }
        } catch (e: Exception) {
            LaunchOutcome.Blocked("No se pudo abrir Vita3K: ${e.message}")
        }
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
