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

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "LauncherBridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifdef LAUNCHER_HAS_VITA3K
#include "launcher_bridge.h"
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsAppRunning(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_isAppRunning(env, nullptr);
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRequestAppQuit(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_requestAppQuit(env, nullptr);
#else
    LOGW("nativeRequestAppQuit STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsAppPaused(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_isAppPaused(env, nullptr);
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetRunningAppTitle(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_getRunningAppTitle(env, nullptr);
#else
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetFirmwareInstallStateMask(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_getFirmwareInstallStateMask(env, nullptr);
#else
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRefreshAppsList(
    JNIEnv *env, jobject /* thiz */) {
#ifdef LAUNCHER_HAS_VITA3K
    Java_org_vita3k_emulator_NativeLib_refreshAppsList(env, nullptr);
#else
    LOGW("nativeRefreshAppsList STUB: núcleo no embebido");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeSetPauseReasonEnabled(
    JNIEnv *env, jobject /* thiz */, jint reasonMask, jboolean enabled) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_setPauseReasonEnabled(env, nullptr, reasonMask, enabled);
#else
    LOGW("nativeSetPauseReasonEnabled STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeSetInputIntercepted(
    JNIEnv *env, jobject /* thiz */, jboolean intercepted) {
#ifdef LAUNCHER_HAS_VITA3K
    return Java_org_vita3k_emulator_NativeLib_setInputIntercepted(env, nullptr, intercepted);
#else
    LOGW("nativeSetInputIntercepted STUB: núcleo no embebido");
    return JNI_FALSE;
#endif
}
