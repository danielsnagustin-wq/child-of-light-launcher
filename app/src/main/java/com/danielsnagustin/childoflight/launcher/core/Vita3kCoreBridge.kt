package com.danielsnagustin.childoflight.launcher.core

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
 *   firmas (init/loadGame/getFps/shutdown) son el contrato que ese
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
     * Inicializa el núcleo con la ruta de almacenamiento (pref-path).
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

    /** Apaga el núcleo y libera la sesión. */
    fun shutdown(): Int {
        if (!available) return NOT_IMPLEMENTED
        return try {
            nativeShutdown()
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

    /** Pulsa/suelta un botón SDL del mando virtual (ver TouchOverlayMapping). */
    fun overlaySetButton(sdlButton: Int, pressed: Boolean): Boolean {
        if (!available) return false
        return try {
            nativeOverlaySetButton(sdlButton, pressed)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Mueve un eje SDL del mando virtual (-32767..32767). */
    fun overlaySetAxis(sdlAxis: Int, value: Short): Boolean {
        if (!available) return false
        return try {
            nativeOverlaySetAxis(sdlAxis, value)
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Táctil frontal (false, defecto) o trasero (true) de Vita. */
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

    private external fun nativeInit(storagePath: String): Int
    private external fun nativeLoadGame(titleId: String): Int
    private external fun nativeGetFps(): Float
    private external fun nativeShutdown(): Int
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
    private external fun nativeOverlaySetButton(sdlButton: Int, pressed: Boolean): Boolean
    private external fun nativeOverlaySetAxis(sdlAxis: Int, value: Short): Boolean
    private external fun nativeOverlaySetTouchState(isBack: Boolean): Boolean
}
