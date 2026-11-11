// runtime_bridge.cpp — puente JNI del arranque del núcleo (Etapa 2).
//
// Funciones JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// (init/loadGame/getFps/isInitialized/destroySession + prepareFrontend).
// Reenvían a upstream; sin árbol Vita3K compilan como stubs
// NOT_IMPLEMENTED (LAUNCHER_HAS_VITA3K no definido).
//
// FLUJO REAL (ver core/EmbeddedGameActivity.kt):
// 1. prepareFrontend(): SDL_SetMainReady + metadatos + cámara. Es una
//    copia de prepare_frontend_runtime() de upstream
//    (native_bootstrap.cpp) con NUESTROS metadatos (paquete del
//    launcher). Idempotente: SDL_WasInit guarda la segunda llamada.
// 2. init(storagePath): reenvía a NativeLib.init de upstream
//    (initialize_session + AppSessionController). Idempotente en
//    upstream (si ya está inicializado devuelve true).
// 3. El juego NO arranca aquí: lo arranca el hilo SDL de
//    EmbeddedGameActivity, que ejecuta SDL_main con argv {"-r", titleId}
//    (vita3k/android/jni/main_android.cpp). loadGame() SOLO valida
//    que la sesión existe y que el Title ID está instalado; si
//    devuelve OK, la Activity puede arrancar el hilo SDL con
//    confianza. Es la validación honesta, no un arranque fingido.
// 4. getFps(): lee EmuEnvState::fps (vita3k/emuenv/include/emuenv/state.h).
//    -1.0f si no hay sesión (sin partida en curso).
// 5. isInitialized(): NativeLib.isInitialized de upstream (hay sesión).
// 6. destroySession(): apagado REAL (P1-1): si no hay sesión activa,
//    destruye el controlador y el emuenv para que el próximo init()
//    re-lea config.yml. La antigua shutdown() solo pedía
//    SDL_EVENT_QUIT y no liberaba nada (P2-B6): se eliminó; pedir el
//    cierre limpio de la partida es requestAppQuit (session_bridge).

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "LauncherBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Códigos de Vita3kCoreBridge: 0 = OK, -1 = NOT_IMPLEMENTED.
static constexpr jint kOk = 0;
static constexpr jint kNotImplemented = -1;
// Errores del núcleo (otros negativos, ver Vita3kCoreBridge.kt):
// loadGame devuelve -2 si no hay sesión y -3 si el Title ID no está
// instalado (el .kt lo documenta así); init fallido también es -2
// (otra función, mismo "no se puede continuar").
static constexpr jint kInitFailed = -2;
static constexpr jint kNotInitialized = -2;
static constexpr jint kTitleNotInstalled = -3;
// destroySession con partida en curso/lanzándose/parándose: no se
// destruyó nada (ver Vita3kCoreBridge.SESSION_ACTIVE).
static constexpr jint kSessionActive = -4;

#ifdef LAUNCHER_HAS_VITA3K
#include "launcher_bridge.h"
#include "android/jni/android_state.h"

#include <emuenv/state.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h> // SDL_SetMainReady (no viene en SDL.h)

#include <exception>
#include <optional>
#include <string>

// Copia de prepare_frontend_runtime() de upstream con nuestros metadatos.
// SDL_SetMainReady debe llamarse antes de que SDL inicie (lo hace
// EmbeddedGameActivity antes de super.onCreate()).
static bool prepare_frontend_runtime() {
    SDL_SetMainReady();

    // R5-A3-4: la versión es un literal manual: subirla con cada
    // release (0.2.63). Mejora futura: generarla desde
    // BuildConfig.VERSION_NAME pasándola por JNI a prepareFrontend.
    SDL_SetAppMetadata("Child of Light Launcher", "0.2.63",
                       "com.danielsnagustin.childoflight.launcher");

    if (SDL_WasInit(SDL_INIT_CAMERA) & SDL_INIT_CAMERA)
        return true;

    if (!SDL_InitSubSystem(SDL_INIT_CAMERA)) {
        LOGE("prepareFrontend: no se pudo iniciar el subsistema de cámara SDL: %s", SDL_GetError());
        return false;
    }

    return true;
}
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativePrepareFrontend(
    JNIEnv * /* env */, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    return prepare_frontend_runtime() ? JNI_TRUE : JNI_FALSE;
#else
    LOGW("nativePrepareFrontend STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInit(
    JNIEnv *env, jobject /* thiz */, jstring storagePath) {
#ifdef LAUNCHER_HAS_VITA3K
    if (storagePath == nullptr) {
        LOGE("nativeInit: storagePath nulo");
        return kInitFailed;
    }
    // REA 0.2.1 oficial: SDL_ANDROID_ALLOW_RECREATE_ACTIVITY=1 se
    // pone desde Kotlin (Vita3KLauncher.tryLaunchEmbedded, vía
    // SDLActivity.nativeSetenv), no aquí: SDL_setenv no está
    // declarado en los headers SDL de esta build (run #138).
    // prepareFrontend primero (idempotente): SDL_SetMainReady debe
    // preceder a cualquier SDL_Init del hilo SDL.
    // P2-B4: best-effort, NO fatal. prepare_frontend_runtime() solo
    // hace SDL_SetMainReady + metadatos + cámara (pin 3e43bc80,
    // native_bootstrap.cpp:141-155); lo imprescindible (SetMainReady
    // y metadatos) ya corrió antes del paso de cámara, y el núcleo no
    // necesita la cámara para jugar. Si la cámara SDL no inicia en
    // este dispositivo, el juego debe arrancar igualmente. El fallo
    // fatal se mantiene solo en nativePrepareFrontend.
    if (!prepare_frontend_runtime()) {
        LOGW("nativeInit: prepareFrontend falló (cámara SDL no disponible); se sigue sin ella");
    }
    // P4: una excepción C++ sin capturar cruzando JNI aborta el
    // proceso (SIGABRT); initialize_session ya captura std::exception
    // por dentro (pin 3e43bc80), pero este try/catch cubre cualquier
    // otra vía y convierte el fallo en código de error.
    jboolean initOk = JNI_FALSE;
    try {
        initOk = Java_org_vita3k_emulator_NativeLib_init(env, nullptr, storagePath);
    } catch (const std::exception &e) {
        LOGE("nativeInit: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return kInitFailed;
    } catch (...) {
        LOGE("nativeInit: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return kInitFailed;
    }
    if (launcher_clear_pending_exception(env) || initOk != JNI_TRUE) {
        LOGE("nativeInit: NativeLib.init devolvió false o lanzó excepción");
        return kInitFailed;
    }
    LOGI("nativeInit: núcleo inicializado");
    return kOk;
#else
    // P2-B3: mismo null-check que en la rama real (el stub tampoco
    // puede llamar a GetStringUTFChars sobre null).
    if (storagePath == nullptr) {
        LOGW("nativeInit STUB (TODO-ETAPA2): storagePath nulo");
        return kNotImplemented;
    }
    const char *path = env->GetStringUTFChars(storagePath, nullptr);
    if (path == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeInit STUB (TODO-ETAPA2): no se pudo leer storagePath");
        return kNotImplemented;
    }
    LOGW("nativeInit STUB (TODO-ETAPA2): núcleo no embebido, storagePath=%s", path);
    env->ReleaseStringUTFChars(storagePath, path);
    return kNotImplemented;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeLoadGame(
    JNIEnv *env, jobject /* thiz */, jstring titleId) {
#ifdef LAUNCHER_HAS_VITA3K
    EmuEnvState *emuenv = get_emuenv();
    if (emuenv == nullptr) {
        LOGE("nativeLoadGame: sesión no inicializada (llamar a init primero)");
        return kNotInitialized;
    }
    // P2-B3: GetStringUTFChars(nullptr) es UB (upstream
    // jstring_to_string tampoco comprueba, android_state.cpp:47-51),
    // y un null por OOM deja OutOfMemoryError pendiente: se comprueba
    // antes y se limpia, devolviendo -3 (el llamador Kotlin lo trata
    // como "juego no instalado", no como crash).
    if (titleId == nullptr) {
        LOGE("nativeLoadGame: titleId nulo");
        return kTitleNotInstalled;
    }
    const char *id = env->GetStringUTFChars(titleId, nullptr);
    if (id == nullptr) {
        launcher_clear_pending_exception(env);
        LOGE("nativeLoadGame: no se pudo leer el titleId (sin memoria)");
        return kTitleNotInstalled;
    }
    const std::string title = id;
    env->ReleaseStringUTFChars(titleId, id);
    if (title.empty()) {
        return kTitleNotInstalled;
    }
    // find_app_by_title_id está declarado en android_state.h.
    const auto app = find_app_by_title_id(title);
    if (!app.has_value()) {
        LOGE("nativeLoadGame: %s no está instalado en el núcleo", title.c_str());
        return kTitleNotInstalled;
    }
    // OK: la sesión existe y el juego está instalado. El arranque
    // real lo hace el hilo SDL de EmbeddedGameActivity (SDL_main
    // con argv {"-r", titleId}).
    LOGI("nativeLoadGame: %s validado, listo para arrancar en la Activity SDL", title.c_str());
    return kOk;
#else
    // P2-B3: mismo null-check que en la rama real. El stub no puede
    // validar nada: el error es NOT_IMPLEMENTED (-1), no -3
    // (kTitleNotInstalled es del puente real).
    if (titleId == nullptr) {
        LOGW("nativeLoadGame STUB (TODO-ETAPA2): titleId nulo");
        return kNotImplemented;
    }
    const char *id = env->GetStringUTFChars(titleId, nullptr);
    if (id == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeLoadGame STUB (TODO-ETAPA2): no se pudo leer el titleId");
        return kNotImplemented;
    }
    LOGW("nativeLoadGame STUB (TODO-ETAPA2): núcleo no embebido, titleId=%s", id);
    env->ReleaseStringUTFChars(titleId, id);
    return kNotImplemented;
#endif
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetFps(
    JNIEnv * /* env */, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    EmuEnvState *emuenv = get_emuenv();
    if (emuenv == nullptr) {
        return -1.0f;
    }
    // P2-B7: data race formal, benigno en ARM64, documentado y NO
    // tocado. EmuEnvState::fps es uint32_t plano (pin 3e43bc80,
    // emuenv/state.h:146), escrito por update_runtime_metrics
    // (app.cpp:314) y leído aquí desde el hilo UI; upstream no expone
    // ningún accesorio atómico ni getter de FPS en el pin, así que no
    // hay lectura "correcta" que usar. En ARM64 la lectura alineada
    // de 32 bits es atómica a nivel hardware: lo peor que puede pasar
    // es leer el valor del segundo anterior. No convertir a atómico
    // desde el adaptador: fps es un campo de upstream y cambiar su
    // tipo exigiría tocar el árbol Vita3K.
    return static_cast<jfloat>(emuenv->fps);
#else
    LOGI("nativeGetFps STUB (TODO-ETAPA2): sin núcleo, -1.0");
    return -1.0f;
#endif
}

// P2-B12: expone NativeLib.isInitialized de upstream
// (native_bootstrap.cpp:202-205: get_emuenv() != nullptr). Solo dice
// si la sesión existe; NO dice si hay partida en curso (eso es
// isAppRunning). La oleada 2 lo usa en EmbeddedGameActivity.onCreate
// para verificar el init en vez de inicializar en el hilo UI.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsInitialized(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    // P4: una excepción C++ sin capturar cruzando JNI aborta el
    // proceso (SIGABRT); isInitialized es trivial en upstream, pero
    // el patrón del puente convierte cualquier fallo en false.
    jboolean ok = JNI_FALSE;
    try {
        ok = Java_org_vita3k_emulator_NativeLib_isInitialized(env, nullptr);
    } catch (const std::exception &e) {
        LOGE("nativeIsInitialized: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeIsInitialized: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return JNI_FALSE;
    }
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    return JNI_FALSE;
#endif
}

// P1-1: apagado REAL de la sesión del núcleo (lo que la antigua
// nativeShutdown nunca hizo: solo pedía SDL_EVENT_QUIT y la sesión
// vivía hasta la muerte del proceso, así que el segundo init() era
// no-op y los ajustes nuevos de config.yml no se aplicaban).
//
// Qué hace: si no hay sesión activa (fase Idle del
// AppSessionController), destruye el controlador y luego el emuenv
// (en ese orden: el controlador guarda una referencia EmuEnvState&,
// session_controller.h:81). El próximo init() vuelve a ejecutar
// initialize_session y re-lee config.yml de disco: los ajustes
// cambiados entre partidas SÍ se aplican.
//
// Qué NO hace: nunca inicia el apagado de una partida en curso.
// Si la fase no es Idle (Launching / Running / Stopping) devuelve
// kSessionActive (-4) SIN destruir nada: el llamador debe esperar a
// que el juego termine de verdad (requestAppQuit + cierre de la
// Activity) y reintentar, o reiniciar el proceso del launcher.
// Tampoco toca SDL ni el subsistema de cámara: prepareFrontend es
// idempotente y se vuelve a llamar en el próximo init().
//
// Cuándo llamarlo (contrato para la oleada 2, ver
// Vita3kCoreBridge.destroySession): solo desde el hilo principal
// (UI), solo cuando no hay partida en curso: desde
// EmbeddedGameActivity.onDestroy() DESPUÉS de super.onDestroy()
// (la SDLActivity vendorizada ya envió QUIT al hilo SDL y esperó
// hasta 1 s a que termine, SDLActivity.java:687-721) o al inicio de
// tryLaunchEmbedded() cuando isAppRunning() es false. Si devuelve
// SESSION_ACTIVE repetidamente, la vía segura es reiniciar el
// proceso entre sesiones, no forzar la destrucción.
//
// Base verificada en el pin 3e43bc80: upstream NO expone ninguna
// función JNI de destrucción de sesión; la destrucción existe solo
// como reset() de los unique_ptr de AndroidSessionState
// (android_state.h:33-36, android_state.cpp:32-34) y el destructor
// por defecto de EmuEnvState (emuenv.cpp:111). stop() del controlador
// (session_controller.cpp:196-...) ya desmontó runtime y renderer
// cuando la fase llegó a Idle; reset_session_tracking() (:280-290)
// deja la fase en Idle. Riesgo residual NO verificable en el pin
// parcial (sin sdl-frontend): entre que la fase llega a Idle y que
// el hilo SDL termina del todo, el hilo podría seguir tocando el
// emuenv; por eso el llamador debe hacerlo tras super.onDestroy()
// (join del hilo SDL) y el primer test en dispositivo debe ser:
// cambiar un ajuste, JUGAR, salir, cambiar otro ajuste, JUGAR y
// comprobar que el segundo aplica sin crash. Si la re-inicialización
// en proceso diera problemas (p. ej. logging::init, fuera del pin),
// el fallback es el reinicio de proceso.
extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeDestroySession(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    AndroidSessionState &session = android_session_state();
    app::AppSessionController *controller = session.app_session_controller.get();
    if (controller != nullptr && controller->has_active_session()) {
        LOGW("nativeDestroySession: sesión activa (lanzando/jugando/parando); no se destruye nada");
        return kSessionActive;
    }
    const bool hadSession = controller != nullptr || session.emuenv != nullptr;
    if (!hadSession) {
        return kNotInitialized;
    }
    // P4: los destructores son noexcept por defecto, pero el patrón
    // del puente convierte cualquier excepción C++ en código de error
    // en vez de dejarla cruzar JNI (SIGABRT).
    try {
        // El controlador ANTES que el emuenv: guarda EmuEnvState&.
        session.app_session_controller.reset();
        session.emuenv.reset();
    } catch (const std::exception &e) {
        LOGE("nativeDestroySession: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return kNotInitialized;
    } catch (...) {
        LOGE("nativeDestroySession: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return kNotInitialized;
    }
    if (launcher_clear_pending_exception(env)) {
        return kNotInitialized;
    }
    LOGI("nativeDestroySession: sesión destruida; el próximo init() re-leerá config.yml");
    return kOk;
#else
    LOGW("nativeDestroySession STUB (TODO-ETAPA2): núcleo no embebido");
    return kNotImplemented;
#endif
}
