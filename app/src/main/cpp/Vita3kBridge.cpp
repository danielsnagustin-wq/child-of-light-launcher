// Vita3kBridge.cpp — puente JNI de Etapa 2 (ESQUELETO, no funcional aún)
//
// TODO-ETAPA2: todas las funciones devuelven NOT_IMPLEMENTED (-1) de forma
// explícita. NO fingen que el núcleo funciona. Cuando el árbol Vita3K
// vendorizado exista (app/src/main/cpp/vita3k, ver docs/etapa2/ETAPA2-PLAN.md),
// estas funciones se reemplazarán por la capa adaptadora real que llame a:
//   - initialize_session() / NativeLib.init(storagePath)  (vita3k/android/jni/native_bootstrap.cpp)
//   - SDL_main con argv {"-r", titleId} / AppLaunchRequest (vita3k/android/jni/main_android.cpp)
//   - requestAppQuit / isAppRunning                        (vita3k/android/jni/native_session.cpp)
//
// Los nombres JNI deben coincidir EXACTAMENTE con la clase Kotlin:
//   com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// (las funciones son `private external` dentro de un `object`, así que JNI
// las ve como métodos estáticos de la clase con el nombre dado).
//
// El stub compila a libvita3k-bridge.so y permite probar la carga con
// System.loadLibrary("vita3k-bridge") sin núcleo real. En la app de Etapa 1
// no se carga nunca: Vita3kCoreBridge.available es false sin .so.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "Vita3kBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// -1 = NOT_IMPLEMENTED (mismo valor que Vita3kCoreBridge.NOT_IMPLEMENTED).
static constexpr jint kNotImplemented = -1;

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInit(
    JNIEnv *env, jobject /* thiz */, jstring storagePath) {
    // TODO-ETAPA2: llamar a initialize_session() con storagePath y crear AppSessionController.
    const char *path = env->GetStringUTFChars(storagePath, nullptr);
    LOGW("nativeInit STUB (TODO-ETAPA2): núcleo no embebido, storagePath=%s",
         path != nullptr ? path : "(null)");
    if (path != nullptr) env->ReleaseStringUTFChars(storagePath, path);
    return kNotImplemented;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeLoadGame(
    JNIEnv *env, jobject /* thiz */, jstring titleId) {
    // TODO-ETAPA2: arrancar el juego como SDL_main con argv {"-r", titleId}
    // y AppLaunchRequest{.app_path = titleId} vía sdl_frontend::run().
    const char *id = env->GetStringUTFChars(titleId, nullptr);
    LOGW("nativeLoadGame STUB (TODO-ETAPA2): núcleo no embebido, titleId=%s",
         id != nullptr ? id : "(null)");
    if (id != nullptr) env->ReleaseStringUTFChars(titleId, id);
    return kNotImplemented;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetFps(
    JNIEnv * /* env */, jobject /* thiz */) {
    // TODO-ETAPA2: devolver el FPS real del núcleo (performance overlay).
    // -1.0f = sin partida en curso / sin núcleo.
    LOGI("nativeGetFps STUB (TODO-ETAPA2): sin núcleo, -1.0");
    return -1.0f;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeShutdown(
    JNIEnv * /* env */, jobject /* thiz */) {
    // TODO-ETAPA2: pedir SDL_EVENT_QUIT y destruir la sesión (requestAppQuit).
    LOGW("nativeShutdown STUB (TODO-ETAPA2): núcleo no embebido");
    return kNotImplemented;
}

// --- Etapa 2 (fundación): instalación + sesión + prepareFrontend ---
// Mismo contrato que el adaptador real (app/src/main/cpp/launcher/):
// sin núcleo, todos devuelven el valor de "no implementado" sin llamar
// a nativo. El stub existe para que libvita3k-bridge.so implemente el
// contrato JNI completo aunque el árbol Vita3K no esté presente.

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativePrepareFrontend(
    JNIEnv * /* env */, jobject /* thiz */) {
    LOGW("nativePrepareFrontend STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallPkg(
    JNIEnv * /* env */, jobject /* thiz */, jstring /* pkgPath */, jstring /* zrif */, jobject /* callback */) {
    LOGW("nativeInstallPkg STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallArchive(
    JNIEnv * /* env */, jobject /* thiz */, jstring /* archivePath */, jobject /* callback */, jboolean /* forceReinstall */) {
    LOGW("nativeInstallArchive STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallFirmware(
    JNIEnv *env, jobject /* thiz */, jstring /* pupPath */, jobject /* callback */) {
    LOGW("nativeInstallFirmware STUB (TODO-ETAPA2): núcleo no embebido");
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeCopyLicense(
    JNIEnv * /* env */, jobject /* thiz */, jstring /* licensePath */) {
    LOGW("nativeCopyLicense STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeCreateLicense(
    JNIEnv * /* env */, jobject /* thiz */, jstring /* zrif */) {
    LOGW("nativeCreateLicense STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeFindPkgZrif(
    JNIEnv *env, jobject /* thiz */, jstring /* pkgPath */) {
    LOGW("nativeFindPkgZrif STUB (TODO-ETAPA2): núcleo no embebido");
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeConvertRifToZrif(
    JNIEnv *env, jobject /* thiz */, jstring /* licensePath */) {
    LOGW("nativeConvertRifToZrif STUB (TODO-ETAPA2): núcleo no embebido");
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsAppRunning(
    JNIEnv * /* env */, jobject /* thiz */) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRequestAppQuit(
    JNIEnv * /* env */, jobject /* thiz */) {
    LOGW("nativeRequestAppQuit STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsAppPaused(
    JNIEnv * /* env */, jobject /* thiz */) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetRunningAppTitle(
    JNIEnv *env, jobject /* thiz */) {
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetFirmwareInstallStateMask(
    JNIEnv * /* env */, jobject /* thiz */) {
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRefreshAppsList(
    JNIEnv * /* env */, jobject /* thiz */) {
    LOGW("nativeRefreshAppsList STUB (TODO-ETAPA2): núcleo no embebido");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeSetPauseReasonEnabled(
    JNIEnv * /* env */, jobject /* thiz */, jint /* reasonMask */, jboolean /* enabled */) {
    LOGW("nativeSetPauseReasonEnabled STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeSetInputIntercepted(
    JNIEnv * /* env */, jobject /* thiz */, jboolean /* intercepted */) {
    LOGW("nativeSetInputIntercepted STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}
