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
 *   estilo EmuCoreV) compilado DENTRO de libvita3k.so junto al árbol
 *   Vita3K vendorizado, con funciones JNI en ESTE paquete. Estas
 *   firmas (init/loadGame/getFps/shutdown) son el contrato que ese
 *   adaptador deberá cumplir; los stubs de Vita3kBridge.cpp ya las
 *   implementan devolviendo NOT_IMPLEMENTED.
 *
 * Códigos de retorno: 0 = OK, NOT_IMPLEMENTED (-1) = stub sin núcleo,
 * cualquier otro negativo = error del núcleo (se documentará al
 * implementar el adaptador real).
 *
 * Carga: se intenta "vita3k" primero (el .so real de Etapa 2, cuyo
 * target CMake se llama así) y luego "vita3k-bridge" (el stub local de
 * app/src/main/cpp, solo para desarrollo). Cargar dos veces es seguro:
 * System.loadLibrary cachea por classloader.
 */
object Vita3kCoreBridge {

    const val OK = 0
    const val NOT_IMPLEMENTED = -1

    /** ¿Hay algún .so del núcleo cargable? En Etapa 1, no. */
    val available: Boolean by lazy {
        loadLibrary("vita3k") || loadLibrary("vita3k-bridge")
    }

    private fun loadLibrary(name: String): Boolean {
        return try {
            System.loadLibrary(name)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Exception) {
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
     * Arranca el juego con el Title ID dado. Equivale al argv
     * {"-r", titleId} de SDL_main + AppLaunchRequest{app_path=titleId}.
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

    private external fun nativeInit(storagePath: String): Int
    private external fun nativeLoadGame(titleId: String): Int
    private external fun nativeGetFps(): Float
    private external fun nativeShutdown(): Int
}
