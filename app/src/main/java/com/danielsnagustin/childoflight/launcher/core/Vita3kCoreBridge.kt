package com.danielsnagustin.childoflight.launcher.core

import android.content.Context
import android.util.Log
import java.io.File
import org.vita3k.emulator.data.NativeTrophyCollection

/**
 * Puente JNI con el núcleo Vita3K embebido (Etapa 2).
 *
 * ESTADO: ESQUELETO, NO FUNCIONAL. En Etapa 1 no hay .so: `available`
 * es false y todas las llamadas devuelven NOT_IMPLEMENTED sin llamar
 * a código nativo. Vita3KLauncher solo lo usa si `available` es true,
 * y si no, sigue con el fallback a Vita3K externo. Nada de esto se
 * activa sin el .so, así que no rompe la compilación de Etapa 1.
 *
 * Por qué existe ya: el diseño del puente está fijado por lo verificado
 * en el código de Vita3K (master, 2026-11-07, ver docs/etapa2/ETAPA2-PLAN.md):
 * - Upstream NO expone un `loadGame(titleId)` público. El arranque real
 *   es SDL_main con argv {"-r", "<TITLE_ID>"} (vita3k/android/jni/
 *   main_android.cpp) y el bootstrap es NativeLib.init(storagePath)
 *   (native_bootstrap.cpp). Las funciones JNI de upstream están ligadas
 *   a la clase org.vita3k.emulator.NativeLib: nuestro paquete NO puede
 *   llamarlas tal cual.
 * - Por eso el puente real de Etapa 2 será código NUEVO (capa adaptadora
 *   estilo EmuCoreV) compilado DENTRO de libVita3K.so junto al árbol
 *   Vita3K vendorizado, con funciones JNI en ESTE paquete. Estas
 *   firmas (init/loadGame/getFps/destroySession/isInitialized) son el contrato que ese
 *   adaptador deberá cumplir; los stubs de Vita3kBridge.cpp ya las
 *   implementan devolviendo NOT_IMPLEMENTED.
 *
 * Códigos de retorno: 0 = OK, NOT_IMPLEMENTED (-1) = stub sin núcleo,
 * cualquier otro negativo = error del núcleo (se documentará al
 * implementar el adaptador real).
 *
 * Carga: se intenta "Vita3K" primero (el .so real de Etapa 2: target CMake
 * "vita3k", OUTPUT_NAME "Vita3K", archivo libVita3K.so) y luego "vita3k-bridge" (el stub local de
 * app/src/main/cpp, solo para desarrollo). Cargar dos veces es seguro:
 * System.loadLibrary cachea por classloader.
 */
object Vita3kCoreBridge {

    const val OK = 0
    const val NOT_IMPLEMENTED = -1

    /**
     * destroySession() con partida en curso, lanzándose o parándose:
     * no se destruyó nada. El llamador debe esperar a que el juego
     * termine de verdad y reintentar, o reiniciar el proceso.
     */
    const val SESSION_ACTIVE = -4

    /**
     * Bits de motivo de pausa (iguales que NativeLib de upstream).
     * PAUSE_REASON_USER = el usuario pausó (botón guía / menú).
     */
    const val PAUSE_REASON_USER = 1
    const val PAUSE_REASON_MENU = 2
    const val PAUSE_REASON_BACKGROUND = 4

    /**
     * Callback de progreso de instalación. El nativo lo llama por
     * duck-typing: busca el método onProgress(int, String) en el
     * objeto (ver ScopedJniCallback en upstream). Por eso el método
     * se llama EXACTAMENTE onProgress y tiene ESTA firma.
     */
    fun interface EmbeddedInstallCallback {
        fun onProgress(percent: Int, status: String)
    }

    /** ¿Hay algún .so del núcleo cargable? En Etapa 1, no. */
    val available: Boolean by lazy {
        loadLibrary("Vita3K") || loadLibrary("vita3k-bridge")
    }

    /**
     * ¿Está el archivo .so del núcleo en la instalación, SIN cargarlo?
     * Solo comprueba que existe (directorio de libs nativas y, como
     * respaldo, las entradas del APK). No ejecuta código nativo: un .so
     * roto no puede tumbar la app aquí.
     *
     * Uso: la Home/Importar/Diagnóstico solo necesitan saber si el
     * núcleo está presente, no cargarlo. Cargar 42 MB de .so solo para
     * mostrar la pantalla principal es un desperdicio y, si sus
     * inicializadores estáticos fallan, matan el proceso sin stack
     * trace Java antes del primer frame (crash al abrir de los runs
     * #80/#82, que el arreglo de DataStore no resolvió). La carga real
     * sigue siendo [available] y solo se toca al lanzar el juego (JUGAR)
     * o en diagnósticos GPU explícitos.
     */
    fun isLibraryFilePresent(context: Context): Boolean {
        return runCatching {
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            if (libDir.isDirectory) {
                val names = libDir.list() ?: emptyArray()
                if (names.any { it == "libVita3K.so" || it == "libvita3k-bridge.so" }) return true
            }
            // Respaldo (extractNativeLibs=false): el .so vive dentro del APK.
            val apk = File(context.applicationInfo.sourceDir)
            if (apk.isFile) {
                java.util.zip.ZipFile(apk).use { zip ->
                    if (zip.getEntry("lib/arm64-v8a/libVita3K.so") != null) return true
                    if (zip.getEntry("lib/arm64-v8a/libvita3k-bridge.so") != null) return true
                }
            }
            false
        }.getOrDefault(false)
    }

    /**
     * Último error al cargar la librería nativa (null si cargó bien o
     * si aún no se intentó). Diagnóstico: antes se tragaba en silencio
     * y un .so ausente o roto solo se veía como available=false.
     */
    var lastLoadError: String? = null
        private set

    private fun loadLibrary(name: String): Boolean {
        return try {
            System.loadLibrary(name)
            true
        } catch (e: UnsatisfiedLinkError) {
            lastLoadError = "System.loadLibrary($name): ${e.message}"
            false
        } catch (e: Exception) {
            lastLoadError = "System.loadLibrary($name): ${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /**
     * Inicializa el núcleo con la ruta de almacenamiento.
     * Equivale a NativeLib.init(storagePath) de upstream: crea
     * config/log/cache/patch/cheats, lee config.yml y enumera GPUs.
     */
    fun init(storagePath: String): Int {
        if (!available) return NOT_IMPLEMENTED
        return try {
            nativeInit(storagePath)
        } catch (_: UnsatisfiedLinkError) {
            NOT_IMPLEMENTED
        }
    }

    /**
     * Valida que el juego con el Title ID dado se puede arrancar con el
     * núcleo embebido (sesión inicializada + Title ID instalado).
     * NO arranca el juego: el arranque real lo hace el hilo SDL de
     * EmbeddedGameActivity (SDL_main con argv {"-r", titleId}).
     * Devuelve OK si la validación pasa; -2 si no hay sesión, -3 si el
     * juego no está instalado en el núcleo.
     */
    fun loadGame(titleId: String): Int {
        if (!available) return NOT_IMPLEMENTED
        return try {
            nativeLoadGame(titleId)
        } catch (_: UnsatisfiedLinkError) {
            NOT_IMPLEMENTED
        }
    }

    /** FPS actual del núcleo (-1.0f si no hay partida en curso). */
    fun getFps(): Float {
        if (!available) return -1.0f
        return try {
            nativeGetFps()
        } catch (_: UnsatisfiedLinkError) {
            -1.0f
        }
    }

    /**
     * true si la sesión del núcleo existe (NativeLib.isInitialized
     * de upstream: hay emuenv). NO dice si hay partida en curso
     * (eso es isAppRunning). La oleada 2 lo usa en
     * EmbeddedGameActivity.onCreate para verificar el init en vez de
     * inicializar en el hilo UI (P2-B12).
     */
    fun isInitialized(): Boolean {
        if (!available) return false
        return try {
            nativeIsInitialized()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Apagado REAL de la sesión del núcleo (P1-1): destruye el
     * controlador de sesión y el emuenv. El próximo init() vuelve a
     * leer config.yml de disco, así que los ajustes cambiados entre
     * dos partidas SÍ se aplican. Sin esto, el segundo init() era
     * no-op en upstream y el juego arrancaba con los ajustes de la
     * primera sesión.
     *
     * CONTRATO (oleada 2):
     * - Devuelve OK (0) si la sesión se destruyó; NOT_IMPLEMENTED
     *   (-1) sin núcleo; NOT_INITIALIZED (-2) si no había sesión
     *   que destruir; SESSION_ACTIVE (-4) si hay una partida en
     *   curso, lanzándose o parándose: NO se destruyó nada.
     * - Llamar solo desde el hilo principal (UI) y solo cuando no
     *   hay partida en curso: desde EmbeddedGameActivity.onDestroy()
     *   DESPUÉS de super.onDestroy() (la SDLActivity ya envió QUIT
     *   al hilo SDL y esperó hasta 1 s a que termine) o al inicio
     *   de tryLaunchEmbedded() cuando isAppRunning() es false.
     *   Nunca llamar mientras el juego está en curso o lanzándose.
     * - Si devuelve SESSION_ACTIVE repetidamente, la vía segura es
     *   reiniciar el proceso del launcher entre sesiones, no forzar
     *   la destrucción.
     * - NO es "pedir el cierre limpio": eso es requestAppQuit().
     *   La antigua shutdown() solo hacía eso mismo (duplicaba
     *   requestAppQuit, con 0 llamadores) y se eliminó (P2-B6).
     */
    fun destroySession(): Int {
        if (!available) return NOT_IMPLEMENTED
        return try {
            nativeDestroySession()
        } catch (_: UnsatisfiedLinkError) {
            NOT_IMPLEMENTED
        }
    }

    // --- Etapa 2 (fundación): prepareFrontend + instalación + sesión ---
    // Todas siguen el mismo contrato: sin .so devuelven el valor de
    // "no implementado" sin llamar a nativo; con .so reenvían a las
    // funciones JNI de upstream (ver app/src/main/cpp/launcher/).
    // La instalación (.pkg/.zip/PUP) es lo que permite que el launcher
    // "solo acepte el juego": sin núcleo, el instalador devuelve
    // failure y el llamador usa la vía de Etapa 1 (Vita3K externo).

    /**
     * Prepara el frontend SDL (SDL_SetMainReady + metadatos + cámara).
     * Upstream lo llama desde MainActivity ANTES de arrancar Emulator;
     * EmbeddedGameActivity lo llama antes de super.onCreate().
     */
    fun prepareFrontend(): Boolean {
        if (!available) return false
        return try {
            nativePrepareFrontend()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Instala un .pkg con su zRIF ("" = sin licencia). true si se instaló. */
    fun installPkg(pkgPath: String, zrif: String, callback: EmbeddedInstallCallback): Boolean {
        if (!available) return false
        return try {
            nativeInstallPkg(pkgPath, zrif, callback)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Instala un .zip/.vpk. forceReinstall = reinstalar sin preguntar si ya existe. */
    fun installArchive(archivePath: String, callback: EmbeddedInstallCallback, forceReinstall: Boolean): Boolean {
        if (!available) return false
        return try {
            nativeInstallArchive(archivePath, callback, forceReinstall)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Instala el firmware PUP. Devuelve la versión ("3.74") o "" si falló. */
    fun installFirmware(pupPath: String, callback: EmbeddedInstallCallback): String {
        if (!available) return ""
        return try {
            nativeInstallFirmware(pupPath, callback)
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
    }

    /** Copia una licencia .rif/.bin (work.bin) a su sitio dentro del núcleo. */
    fun copyLicense(licensePath: String): Boolean {
        if (!available) return false
        return try {
            nativeCopyLicense(licensePath)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Crea e instala una licencia a partir de un zRIF. */
    fun createLicense(zrif: String): Boolean {
        if (!available) return false
        return try {
            nativeCreateLicense(zrif)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Busca el zRIF de un .pkg entre las licencias ya instaladas ("" si no hay). */
    fun findPkgZrif(pkgPath: String): String {
        if (!available) return ""
        return try {
            nativeFindPkgZrif(pkgPath)
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
    }

    /** Convierte un .bin/.rif (work.bin) a zRIF ("" si no se pudo). */
    fun convertRifToZrif(licensePath: String): String {
        if (!available) return ""
        return try {
            nativeConvertRifToZrif(licensePath)
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
    }

    /** true si hay una partida en curso en el núcleo. */
    fun isAppRunning(): Boolean {
        if (!available) return false
        return try {
            nativeIsAppRunning()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Pide el cierre limpio de la partida en curso. */
    fun requestAppQuit(): Boolean {
        if (!available) return false
        return try {
            nativeRequestAppQuit()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** true si la partida está en pausa. */
    fun isAppPaused(): Boolean {
        if (!available) return false
        return try {
            nativeIsAppPaused()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Title ID de la partida en curso ("" si no hay). */
    fun getRunningAppTitle(): String {
        if (!available) return ""
        return try {
            nativeGetRunningAppTitle()
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
    }

    /** Máscara de estado del firmware instalado (bits de upstream). */
    fun getFirmwareInstallStateMask(): Int {
        if (!available) return 0
        return try {
            nativeGetFirmwareInstallStateMask()
        } catch (_: UnsatisfiedLinkError) {
            0
        }
    }

    /** Re-escanea la lista de apps instaladas en el núcleo. */
    fun refreshAppsList() {
        if (!available) return
        try {
            nativeRefreshAppsList()
        } catch (_: UnsatisfiedLinkError) {
            // Sin núcleo: no hay nada que refrescar.
        }
    }

    /** Activa o quita un motivo de pausa (PAUSE_REASON_*). */
    fun setPauseReasonEnabled(reasonMask: Int, enabled: Boolean): Boolean {
        if (!available) return false
        return try {
            nativeSetPauseReasonEnabled(reasonMask, enabled)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Bloquea o libera la entrada del juego sin cambiar la pausa. */
    fun setInputIntercepted(intercepted: Boolean): Boolean {
        if (!available) return false
        return try {
            nativeSetInputIntercepted(intercepted)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    // --- Overlay táctil (Etapa 2): mando virtual SDL de upstream ---
    // Reenvían a vita3k/android/jni/input_overlay.cpp del pin
    // 3e43bc80, que crea un joystick virtual SDL ("Vita3K Virtual
    // Controller") y traduce botones/ejes a entrada del juego
    // (ctrl.cpp). La UI del overlay es nuestra (GamepadOverlayView,
    // perfil activo del launcher): el núcleo solo recibe eventos.
    // Sin .so, todo devuelve false sin llamar a nativo; con .so, el
    // adaptador (launcher/src/input_overlay_bridge.cpp) llama a las
    // funciones JNI de upstream con el mismo nombre.
    // attach solo funciona con la partida en curso (SDL gamepad
    // iniciado + sesión activa): llamarlo antes no crea nada, por eso
    // EmbeddedGameActivity lo llama al arrancar el juego
    // (setCurrentGameId) y confirma con overlayIsAttached().

    /** Crea el mando virtual SDL del overlay (idempotente en upstream: primero lo suelta). */
    fun overlayAttach(): Boolean {
        if (!available) return false
        return try {
            nativeOverlayAttach()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Destruye el mando virtual SDL del overlay. */
    fun overlayDetach(): Boolean {
        if (!available) return false
        return try {
            nativeOverlayDetach()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** true si el mando virtual "Vita3K Virtual Controller" existe en SDL. */
    fun overlayIsAttached(): Boolean {
        if (!available) return false
        return try {
            nativeOverlayIsAttached()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Número de gamepads que ve SDL en este momento (R4-P0-3: readback
     * real para el reporte de bug). -1 = sin dato: sin núcleo, sin
     * SDL gamepad iniciado, o la función nativa aún no existe en el
     * puente C++ (el C++ usa SDL_GetGamepads, igual que
     * overlay_is_attached() en input_overlay_bridge.cpp). Un 0 con el
     * juego en curso significa que SDL no ve ningún mando; si el
     * mando virtual del overlay existe se comprueba aparte con
     * overlayIsAttached().
     */
    fun overlayGamepadCount(): Int {
        if (!available) return -1
        return try {
            nativeOverlayGamepadCount()
        } catch (_: UnsatisfiedLinkError) {
            -1
        }
    }

    /**
     * Pulsa/suelta un botón SDL del mando virtual (ver TouchOverlayMapping).
     * Devuelve false si el mando virtual no existe en SDL (P2-B11):
     * en ese caso el botón no llegó al juego y hay que reintentar el
     * attach (overlayAttach + overlayIsAttached) antes de seguir.
     */
    fun overlaySetButton(sdlButton: Int, pressed: Boolean): Boolean {
        if (!available) return false
        return try {
            nativeOverlaySetButton(sdlButton, pressed)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Mueve un eje SDL del mando virtual (-32767..32767).
     * Devuelve false si el mando virtual no existe en SDL (P2-B11):
     * en ese caso el eje no llegó al juego y hay que reintentar el
     * attach (overlayAttach + overlayIsAttached) antes de seguir.
     */
    fun overlaySetAxis(sdlAxis: Int, value: Short): Boolean {
        if (!available) return false
        return try {
            nativeOverlaySetAxis(sdlAxis, value)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Táctil frontal (false, defecto) o trasero (true) de Vita.
     *
     * R4-P2-16 (verificado-sin-cambio): el oficial solo lo llama al
     * pulsar su botón de cambio táctil (legacyId 1024); el launcher
     * lo fuerza a false (frontal) tras cada attach/rebind del mando
     * virtual. Equivalente en la práctica: el launcher no expone
     * táctil trasero y Child of Light usa el frontal (Igniculus). El
     * valor por defecto del flag no se pudo verificar: el módulo
     * touch/ no está en el pin (touch/functions.h ausente; solo la
     * referencia en input_overlay.cpp:117).
     */
    fun overlaySetTouchState(isBack: Boolean): Boolean {
        if (!available) return false
        return try {
            nativeOverlaySetTouchState(isBack)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    // --- Drivers GPU personalizados (Turnip, solo Adreno) ---
    // Verificado contra upstream (pin 3e43bc80, native_drivers.cpp):
    // installCustomDriver extrae el zip en <filesDir>/driver/<stem>/
    // y devuelve ese stem como nombre del driver ("" si falló o si ya
    // existe). Ese nombre es el valor exacto de `custom-driver-name`
    // en config.yml. Sin esta llamada, el config.yml nombraba un
    // driver que el núcleo no tenía instalado y caía al driver del
    // sistema en silencio.

    /**
     * Instala un zip Turnip en el núcleo. Devuelve el nombre del
     * driver instalado (stem del zip, p. ej. "Turnip_v26.0.0_R8_Gmem")
     * o "" si no hay núcleo o falló la instalación.
     */
    fun installCustomDriver(zipPath: String): String {
        if (!available) return ""
        return try {
            nativeInstallCustomDriver(zipPath)
        } catch (_: UnsatisfiedLinkError) {
            ""
        }
    }

    /** Nombres de los drivers personalizados instalados en el núcleo. */
    fun getInstalledCustomDrivers(): Array<String> {
        if (!available) return emptyArray()
        return try {
            nativeGetInstalledCustomDrivers()
        } catch (_: UnsatisfiedLinkError) {
            emptyArray()
        }
    }

    /** Borra un driver personalizado del núcleo. true si se borró. */
    fun removeCustomDriver(driverName: String): Boolean {
        if (!available) return false
        return try {
            nativeRemoveCustomDriver(driverName)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    // --- Trofeos (Ronda 0.2.57, puente JNI de trofeos) ---
    // Solo lectura: el juego desbloquea, el launcher solo observa.
    // Reenvía a Java_org_vita3k_emulator_NativeLib_getTrophyCollections
    // de upstream (native_trophies.cpp:206-207, compilado dentro de
    // libVita3K.so: pin CMakeLists.txt:167) vía trophy_bridge.cpp.
    // Exige sesión viva: getTrophyCollections devuelve vacío si no hay
    // emuenv (native_trophies.cpp:224-226), y destroySession lo
    // destruye (runtime_bridge.cpp: session.emuenv.reset()). Por eso
    // el único llamador lee ANTES de destroySession
    // (EmbeddedGameActivity.onDestroy) y la sincronización consume ese
    // resultado al volver (LauncherViewModel.syncTrophiesFromGame).
    // Sin .so devuelve vacío; nunca lanza: cualquier fallo nativo o
    // JNI se convierte en vacío con log, para que un puente roto no
    // tumbe nunca la vuelta a Home.
    fun getTrophyCollections(): Array<NativeTrophyCollection> {
        if (!available) return emptyArray()
        return try {
            nativeGetTrophyCollections()
        } catch (_: UnsatisfiedLinkError) {
            emptyArray()
        } catch (e: Exception) {
            Log.w("ChildOfLight", "getTrophyCollections falló, se devuelve vacío: ${e.message}")
            emptyArray()
        }
    }

    private external fun nativeInit(storagePath: String): Int
    private external fun nativeLoadGame(titleId: String): Int
    private external fun nativeGetFps(): Float
    private external fun nativeIsInitialized(): Boolean
    private external fun nativeDestroySession(): Int
    private external fun nativePrepareFrontend(): Boolean
    private external fun nativeInstallPkg(pkgPath: String, zrif: String, callback: EmbeddedInstallCallback): Boolean
    private external fun nativeInstallArchive(archivePath: String, callback: EmbeddedInstallCallback, forceReinstall: Boolean): Boolean
    private external fun nativeInstallFirmware(pupPath: String, callback: EmbeddedInstallCallback): String
    private external fun nativeCopyLicense(licensePath: String): Boolean
    private external fun nativeCreateLicense(zrif: String): Boolean
    private external fun nativeFindPkgZrif(pkgPath: String): String
    private external fun nativeConvertRifToZrif(licensePath: String): String
    private external fun nativeIsAppRunning(): Boolean
    private external fun nativeRequestAppQuit(): Boolean
    private external fun nativeIsAppPaused(): Boolean
    private external fun nativeGetRunningAppTitle(): String
    private external fun nativeGetFirmwareInstallStateMask(): Int
    private external fun nativeRefreshAppsList()
    private external fun nativeSetPauseReasonEnabled(reasonMask: Int, enabled: Boolean): Boolean
    private external fun nativeSetInputIntercepted(intercepted: Boolean): Boolean
    private external fun nativeInstallCustomDriver(zipPath: String): String
    private external fun nativeGetInstalledCustomDrivers(): Array<String>
    private external fun nativeRemoveCustomDriver(driverName: String): Boolean
    private external fun nativeOverlayAttach(): Boolean
    private external fun nativeOverlayDetach(): Boolean
    private external fun nativeOverlayIsAttached(): Boolean
    private external fun nativeOverlayGamepadCount(): Int
    private external fun nativeOverlaySetButton(sdlButton: Int, pressed: Boolean): Boolean
    private external fun nativeOverlaySetAxis(sdlAxis: Int, value: Short): Boolean
    private external fun nativeOverlaySetTouchState(isBack: Boolean): Boolean
    private external fun nativeGetTrophyCollections(): Array<NativeTrophyCollection>
}
