// session_bridge.cpp — puente JNI de sesión (Etapa 2).
//
// Funciones JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// para consultar y controlar la partida en curso. Reenvían a
// native_session.cpp / native_bootstrap.cpp de upstream; sin árbol
// Vita3K compilan como stubs (false / "" / 0), y el llamador actúa
// como si no hubiera partida.
//
// Los usa EmbeddedGameActivity (pausa con el botón guía, cierre
// limpio al salir) y, en el futuro, la medición automática de FPS
// (getFps vive en runtime_bridge.cpp).
//
// Robustez JNI: si upstream deja una excepción pendiente, se limpia
// y se devuelve el valor de "no hay partida" (false / "" / 0): el
// llamador Kotlin solo captura UnsatisfiedLinkError, así que una
// excepción pendiente sería un crash en vez de un estado controlado.
// getRunningAppTitle casi nunca devuelve null (Kotlin lo trata como
// String no nulo): solo si la JVM no puede crear ni la cadena vacía
// (OOM), caso en el que el helper limpia la excepción y devuelve
// null en vez de devolver con una excepción pendiente (P2-B5).

#include <jni.h>
#include <android/log.h>

#include <exception>

#define LOG_TAG "LauncherBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// launcher_bridge.h se incluye siempre (no solo con núcleo): los
// helpers de cadena vacía (P2-B5) se usan también en las ramas stub.
#include "launcher_bridge.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsAppRunning(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_isAppRunning(env, nullptr);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRequestAppQuit(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_requestAppQuit(env, nullptr);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    LOGW("nativeRequestAppQuit STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsAppPaused(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_isAppPaused(env, nullptr);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetRunningAppTitle(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    jstring title = Java_org_vita3k_emulator_NativeLib_getRunningAppTitle(env, nullptr);
    // P2-B1: evaluar el clear ANTES del null (el || cortocircuitaba
    // y el NewStringUTF("") corría con la excepción pendiente,
    // devolviendo null a un String no nulo de Kotlin).
    const bool hadException = launcher_clear_pending_exception(env);
    if (title == nullptr || hadException) {
        return launcher_new_empty_string(env);
    }
    return title;
#else
    return launcher_new_empty_string(env);
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetFirmwareInstallStateMask(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    const jint mask = Java_org_vita3k_emulator_NativeLib_getFirmwareInstallStateMask(env, nullptr);
    if (launcher_clear_pending_exception(env)) {
        return 0;
    }
    return mask;
#else
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRefreshAppsList(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    // P4: scan_apps usa fs::directory_iterator sin error_code (pin
    // 3e43bc80, apps_list.cpp collect_app_cache_sources): un error de
    // E/S lanza std::filesystem::filesystem_error, y una excepción C++
    // sin capturar cruzando JNI aborta el proceso (SIGABRT) sin
    // diálogo ni stack trace. Se captura: el llamador valida con
    // loadGame y muestra el error en vez de cerrarse.
    try {
        Java_org_vita3k_emulator_NativeLib_refreshAppsList(env, nullptr);
    } catch (const std::exception &e) {
        LOGE("nativeRefreshAppsList: excepción C++ capturada: %s", e.what());
    } catch (...) {
        LOGE("nativeRefreshAppsList: excepción C++ desconocida capturada");
    }
    launcher_clear_pending_exception(env);
#else
    LOGW("nativeRefreshAppsList STUB: núcleo no embebido");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeSetPauseReasonEnabled(
    JNIEnv *env, jobject /* thiz */, jint reasonMask, jboolean enabled) {
#ifdef LAUNCHER_HAS_VITA3K
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_setPauseReasonEnabled(env, nullptr, reasonMask, enabled);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    LOGW("nativeSetPauseReasonEnabled STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeSetInputIntercepted(
    JNIEnv *env, jobject /* thiz */, jboolean intercepted) {
#ifdef LAUNCHER_HAS_VITA3K
    const jboolean ok = Java_org_vita3k_emulator_NativeLib_setInputIntercepted(env, nullptr, intercepted);
    if (launcher_clear_pending_exception(env)) {
        return JNI_FALSE;
    }
    return ok;
#else
    LOGW("nativeSetInputIntercepted STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}
