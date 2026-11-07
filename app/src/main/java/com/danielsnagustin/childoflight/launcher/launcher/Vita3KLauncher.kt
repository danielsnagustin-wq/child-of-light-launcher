package com.danielsnagustin.childoflight.launcher.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.danielsnagustin.childoflight.launcher.data.GameLibraryRepository
import com.danielsnagustin.childoflight.launcher.data.LauncherLog
import com.danielsnagustin.childoflight.launcher.data.Vita3kConfigGenerator
import com.danielsnagustin.childoflight.launcher.model.LauncherSettings

/**
 * Lanzador del juego.
 *
 * Flujo real en Etapa 1:
 * 1. Verifica prerrequisitos: juego instalado + firmware guardado.
 * 2. Si el ajuste lo pide, cierra apps en segundo plano (best-effort).
 * 3. Escribe config.yml con los ajustes actuales.
 * 4. Intenta el núcleo embebido: comprueba si existe la librería
 *    nativa "vita3k" (System.loadLibrary). En Etapa 1 NO existe:
 *    embeber el .so de Vita3K es la Etapa 2, y aquí NO se finge
 *    que el juego ya corre sin núcleo.
 * 5. Fallback honesto: lanza la app externa org.vita3k.emulator
 *    (Vita3K oficial de Android) si está instalada, con un Intent
 *    MAIN normal. No se inventa un deep-link para arrancar un Title
 *    ID concreto: no hay uno documentado que se haya verificado en
 *    Etapa 1 (TODO-VERIFY en Etapa 2).
 * 6. Si ni siquiera está la app externa, devuelve el error con
 *    instrucciones claras en vez de fallar en silencio.
 */
class Vita3KLauncher(private val context: Context) {

    companion object {
        const val VITA3K_PACKAGE = "org.vita3k.emulator"
        const val EMBEDDED_LIB_NAME = "vita3k"
    }

    sealed class LaunchOutcome {
        /** config.yml escrito; se lanzó la app externa de Vita3K. */
        data class LaunchedExternal(val configPath: String) : LaunchOutcome()
        /** El núcleo embebido existe (Etapa 2). Sin punto de entrada JNI aún. */
        data class EmbeddedCorePresent(val configPath: String) : LaunchOutcome()
        data class Blocked(val reason: String) : LaunchOutcome()
    }

    data class Prerequisites(
        val gameInstalled: Boolean,
        val titleId: String?,
        val firmwareInstalled: Boolean,
        val missing: List<String>,
    ) {
        val ready: Boolean get() = missing.isEmpty()
    }

    fun checkPrerequisites(library: GameLibraryRepository): Prerequisites {
        val status = library.scanStatus()
        val missing = mutableListOf<String>()
        if (!status.gameInstalled) {
            missing.add("El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).")
        }
        if (!status.firmwareInstalled) {
            missing.add("El firmware de Vita no está guardado. Importa el PSVUPDAT.PUP de tu Vita.")
        }
        return Prerequisites(status.gameInstalled, status.titleId?.id, status.firmwareInstalled, missing)
    }

    fun launch(library: GameLibraryRepository, settings: LauncherSettings): LaunchOutcome {
        val pre = checkPrerequisites(library)
        if (!pre.ready) {
            return LaunchOutcome.Blocked(pre.missing.joinToString("\n"))
        }
        if (settings.closeBackgroundApps) {
            val killed = closeBackgroundApps()
            LauncherLog.append(context, "launch", "Apps en segundo plano cerradas (best-effort): $killed procesos")
        }
        val configFile = Vita3kConfigGenerator.writeConfig(context, settings, library.vitaRoot)
        if (embeddedCorePresent()) {
            // Etapa 2: aquí irá la llamada JNI al núcleo embebido.
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
            LauncherLog.append(context, "launch", "Vita3K externo lanzado; config en ${configFile.absolutePath}")
            LaunchOutcome.LaunchedExternal(configFile.absolutePath)
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
