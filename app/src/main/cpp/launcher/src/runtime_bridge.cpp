// runtime_bridge.cpp — puente JNI del arranque del núcleo (Etapa 2).
//
// Funciones JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// (las 4 originales + prepareFrontend). Reenvían a upstream; sin árbol
// Vita3K compilan como stubs NOT_IMPLEMENTED (LAUNCHER_HAS_VITA3K
// no definido).
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
// 5. shutdown(): reenvía a requestAppQuit (SDL_EVENT_QUIT).

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "LauncherBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Códigos de Vita3kCoreBridge: 0 = OK, -1 = NOT_IMPLEMENTED.
static constexpr jint kOk = 0;
static constexpr jint kNotImplemented = -1;
// Errores del núcleo (otros negativos, ver Vita3kCoreBridge).
static constexpr jint kInitFailed = -2;
static constexpr jint kNotInitialized = -3;
static constexpr jint kTitleNotInstalled = -4;

#ifdef LAUNCHER_HAS_VITA3K
#include "launcher_bridge.h"
#include "android/jni/android_state.h"

#include <emuenv/state.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h> // SDL_SetMainReady (no viene en SDL.h)

#include <optional>
#include <string>

// Copia de prepare_frontend_runtime() de upstream con nuestros metadatos.
// SDL_SetMainReady debe llamarse antes de que SDL inicie (lo hace
// EmbeddedGameActivity antes de super.onCreate()).
static bool prepare_frontend_runtime() {
    SDL_SetMainReady();

    SDL_SetAppMetadata("Child of Light Launcher", "0.1.0",
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
    // prepareFrontend primero (idempotente): SDL_SetMainReady debe
    // preceder a cualquier SDL_Init del hilo SDL.
    if (!prepare_frontend_runtime()) {
        LOGE("nativeInit: prepareFrontend falló");
        return kInitFailed;
    }
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_init(env, nullptr, storagePath);
    if (ok != JNI_TRUE) {
        LOGE("nativeInit: NativeLib.init devolvió false");
        return kInitFailed;
    }
    LOGI("nativeInit: núcleo inicializado");
    return kOk;
#else
    const char *path = env->GetStringUTFChars(storagePath, nullptr);
    LOGW("nativeInit STUB (TODO-ETAPA2): núcleo no embebido, storagePath=%s",
         path != nullptr ? path : "(null)");
    if (path != nullptr) env->ReleaseStringUTFChars(storagePath, path);
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
    const char *id = env->GetStringUTFChars(titleId, nullptr);
    const std::string title = id != nullptr ? id : "";
    if (id != nullptr) env->ReleaseStringUTFChars(titleId, id);
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
    const char *id = env->GetStringUTFChars(titleId, nullptr);
    LOGW("nativeLoadGame STUB (TODO-ETAPA2): núcleo no embebido, titleId=%s",
         id != nullptr ? id : "(null)");
    if (id != nullptr) env->ReleaseStringUTFChars(titleId, id);
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
    return static_cast<jfloat>(emuenv->fps);
#else
    LOGI("nativeGetFps STUB (TODO-ETAPA2): sin núcleo, -1.0");
    return -1.0f;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeShutdown(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_requestAppQuit(env, nullptr);
    return ok == JNI_TRUE ? kOk : kNotInitialized;
#else
    LOGW("nativeShutdown STUB (TODO-ETAPA2): núcleo no embebido");
    return kNotImplemented;
#endif
}
