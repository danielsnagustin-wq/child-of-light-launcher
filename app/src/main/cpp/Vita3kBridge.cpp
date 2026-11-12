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

// El stub no incluye launcher_bridge.h (su target no tiene ese
// include dir): helpers locales con la misma semántica (P2-B5).
// Limpian la excepción pendiente para no devolver nunca con una
// excepción activa; nullptr solo si la JVM no puede crear ni el
// valor vacío (OOM).
static inline void stub_clear_pending_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

static inline jstring stub_empty_string(JNIEnv *env) {
    jstring result = env->NewStringUTF("");
    if (result == nullptr) {
        stub_clear_pending_exception(env);
    }
    return result;
}

static inline jobjectArray stub_empty_string_array(JNIEnv *env) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        stub_clear_pending_exception(env);
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(0, string_class, nullptr);
    if (result == nullptr) {
        stub_clear_pending_exception(env);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInit(
    JNIEnv *env, jobject /* thiz */, jstring storagePath) {
    // TODO-ETAPA2: llamar a initialize_session() con storagePath y crear AppSessionController.
    // P2-B3: GetStringUTFChars(nullptr) es UB y un null por OOM deja
    // una excepción pendiente; se comprueba antes y se limpia.
    // El stub no puede validar nada: el error es NOT_IMPLEMENTED
    // (-1), no -3 (kTitleNotInstalled es del puente real).
    if (storagePath == nullptr) {
        LOGW("nativeInit STUB (TODO-ETAPA2): storagePath nulo");
        return kNotImplemented;
    }
    const char *path = env->GetStringUTFChars(storagePath, nullptr);
    if (path == nullptr) {
        stub_clear_pending_exception(env);
        LOGW("nativeInit STUB (TODO-ETAPA2): no se pudo leer storagePath");
        return kNotImplemented;
    }
    LOGW("nativeInit STUB (TODO-ETAPA2): núcleo no embebido, storagePath=%s", path);
    env->ReleaseStringUTFChars(storagePath, path);
    return kNotImplemented;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeLoadGame(
    JNIEnv *env, jobject /* thiz */, jstring titleId) {
    // TODO-ETAPA2: arrancar el juego como SDL_main con argv {"-r", titleId}
    // y AppLaunchRequest{.app_path = titleId} vía sdl_frontend::run().
    // P2-B3: igual que nativeInit (null-check + limpiar OOM); el
    // error del stub es NOT_IMPLEMENTED (-1), no -3.
    if (titleId == nullptr) {
        LOGW("nativeLoadGame STUB (TODO-ETAPA2): titleId nulo");
        return kNotImplemented;
    }
    const char *id = env->GetStringUTFChars(titleId, nullptr);
    if (id == nullptr) {
        stub_clear_pending_exception(env);
        LOGW("nativeLoadGame STUB (TODO-ETAPA2): no se pudo leer el titleId");
        return kNotImplemented;
    }
    LOGW("nativeLoadGame STUB (TODO-ETAPA2): núcleo no embebido, titleId=%s", id);
    env->ReleaseStringUTFChars(titleId, id);
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

// P2-B12 (stub): sin núcleo no hay sesión inicializada.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeIsInitialized(
    JNIEnv * /* env */, jobject /* thiz */) {
    return JNI_FALSE;
}

// P1-1 / P2-B6 (stub): sin núcleo no hay sesión que destruir.
// La antigua nativeShutdown (solo pedía SDL_EVENT_QUIT sin liberar
// nada) se eliminó del contrato: pedir el cierre limpio es
// requestAppQuit y el apagado real es destroySession.
extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeDestroySession(
    JNIEnv * /* env */, jobject /* thiz */) {
    LOGW("nativeDestroySession STUB (TODO-ETAPA2): núcleo no embebido");
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
    return stub_empty_string(env);
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
    return stub_empty_string(env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeConvertRifToZrif(
    JNIEnv *env, jobject /* thiz */, jstring /* licensePath */) {
    LOGW("nativeConvertRifToZrif STUB (TODO-ETAPA2): núcleo no embebido");
    return stub_empty_string(env);
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
    return stub_empty_string(env);
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

// --- Overlay táctil: stubs sin núcleo (mismo contrato que el
// adaptador real en app/src/main/cpp/launcher/src/
// input_overlay_bridge.cpp). Sin núcleo no hay mando virtual SDL:
// todo devuelve false y el overlay no se monta.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayAttach(
    JNIEnv * /* env */, jobject /* thiz */) {
    LOGW("nativeOverlayAttach STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayDetach(
    JNIEnv * /* env */, jobject /* thiz */) {
    LOGW("nativeOverlayDetach STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayIsAttached(
    JNIEnv * /* env */, jobject /* thiz */) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlaySetButton(
    JNIEnv * /* env */, jobject /* thiz */, jint /* sdlButton */, jboolean /* pressed */) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlaySetAxis(
    JNIEnv * /* env */, jobject /* thiz */, jint /* sdlAxis */, jshort /* value */) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlaySetTouchState(
    JNIEnv * /* env */, jobject /* thiz */, jboolean /* isBack */) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeOverlayGamepadCount(
    JNIEnv * /* env */, jobject /* thiz */) {
    LOGW("nativeOverlayGamepadCount STUB: núcleo no embebido");
    return -1;
}

// --- Etapa 2 (fundación): drivers GPU personalizados ---
// Mismo contrato que driver_bridge.cpp: sin núcleo, "" / vacío / false.

extern "C" JNIEXPORT jstring JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeInstallCustomDriver(
    JNIEnv *env, jobject /* thiz */, jstring /* zipPath */) {
    LOGW("nativeInstallCustomDriver STUB (TODO-ETAPA2): núcleo no embebido");
    return stub_empty_string(env);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetInstalledCustomDrivers(
    JNIEnv *env, jobject /* thiz */) {
    return stub_empty_string_array(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeRemoveCustomDriver(
    JNIEnv * /* env */, jobject /* thiz */, jstring /* driverName */) {
    LOGW("nativeRemoveCustomDriver STUB (TODO-ETAPA2): núcleo no embebido");
    return JNI_FALSE;
}

// --- Ronda 0.2.64 (P1-3 Mali): máscara de mapeo de memoria ---
// Mismo contrato que mapping_bridge.cpp: sin núcleo, 0 = desconocida
// (la UI no filtra nada).

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetSupportedMemoryMappingMask(
    JNIEnv * /* env */, jobject /* thiz */, jstring /* customDriverName */) {
    LOGW("nativeGetSupportedMemoryMappingMask STUB: núcleo no embebido");
    return 0;
}
