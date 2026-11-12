#pragma once

#include <jni.h>

// launcher_bridge.h — reenvíos a las funciones JNI de Vita3K upstream.
//
// La capa adaptadora NO reimplementa el núcleo: llama a las funciones
// JNI que upstream ya compila dentro de libVita3K.so
// (vita3k/android/jni/native_bootstrap.cpp, native_install.cpp,
// native_session.cpp, native_drivers.cpp). Esas funciones están ligadas a la clase
// org.vita3k.emulator.NativeLib y nuestro paquete no puede llamarlas
// desde Kotlin tal cual; pero desde C++ son funciones `extern "C"`
// normales dentro del mismo .so, así que el adaptador las declara
// aquí y las llama directamente.
//
// Verificado contra Vita3K master 3e43bc803a04c775737a3474e0d54f3e10ca61bf
// (2026-11-07): las 21 funciones de bootstrap/install/session existen
// con estas firmas exactas y el jclass es un parámetro sin nombre
// (ignorado) en todas; el adaptador pasa nullptr. Además, las 3 de
// native_drivers.cpp (installCustomDriver y compañía) existen con
// estas firmas: sin ellas el driver Turnip nunca se instalaba en el
// núcleo (el config.yml solo nombra el driver; ver driver_bridge.cpp).
// Si upstream cambia una firma, hay que fijar el commit
// (docs/etapa2/ETAPA2-ESTADO.md) o actualizar estas declaraciones.
//
// NOTA: upstream IGNORA el parámetro jclass en todas estas funciones
// (usan solo env + los argumentos); el adaptador pasa nullptr.
// Algunas están declaradas para uso futuro (isInitialized):
// declararlas no cuesta nada y documenta la superficie JNI
// disponible de upstream.
//
// Si una llamada a upstream dejó una excepción JNI pendiente, la
// limpia y devuelve true. El puente usa códigos de error, no
// excepciones: el llamador Kotlin solo captura UnsatisfiedLinkError,
// así que una excepción pendiente aquí llegaría como crash a
// EmbeddedGameActivity / EmbeddedPkgInstaller en vez de como
// código de error. Upstream normalmente devuelve false/"" sin
// lanzar, pero si lanza, el puente lo convierte en error.
// (P2-B2: #pragma once e #include <jni.h> van ANTES del helper: el
// header es autocontenido y ya no depende de que cada .cpp incluya
// <jni.h> primero.)
static inline bool launcher_clear_pending_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

// Cadena Java vacía nueva, o nullptr SOLO si la JVM no pudo crearla
// (OOM): en ese caso limpia la excepción pendiente para no devolver
// con una excepción activa (P2-B5: un NewStringUTF("") sin comprobar
// devolvía null con la excepción pendiente, y Kotlin recibía null en
// un String no nulo). El llamador Kotlin trata "" como fallo;
// nullptr solo ocurre sin memoria, donde el proceso está a punto de
// morir igualmente. NUNCA se devuelve con una excepción pendiente.
static inline jstring launcher_new_empty_string(JNIEnv *env) {
    jstring result = env->NewStringUTF("");
    if (result == nullptr) {
        launcher_clear_pending_exception(env);
    }
    return result;
}

// Array vacío de String, o nullptr si no se pudo crear (OOM o clase
// java/lang/String no encontrada, que en la práctica solo ocurre sin
// memoria). Misma regla que launcher_new_empty_string: sin excepción
// pendiente al devolver.
static inline jobjectArray launcher_new_empty_string_array(JNIEnv *env) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        launcher_clear_pending_exception(env);
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(0, string_class, nullptr);
    if (result == nullptr) {
        launcher_clear_pending_exception(env);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

extern "C" {

// --- native_bootstrap.cpp ---
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_prepareFrontend(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_init(JNIEnv *env, jclass clazz, jstring storage_path);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isInitialized(JNIEnv *env, jclass clazz);
JNIEXPORT void JNICALL
Java_org_vita3k_emulator_NativeLib_refreshAppsList(JNIEnv *env, jclass clazz);
JNIEXPORT jint JNICALL
Java_org_vita3k_emulator_NativeLib_getFirmwareInstallStateMask(JNIEnv *env, jclass clazz);

// --- native_install.cpp ---
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_installFirmware(JNIEnv *env, jclass clazz, jstring path, jobject callback);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_installPkg(JNIEnv *env, jclass clazz, jstring path, jstring zrif, jobject callback);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_installArchive(JNIEnv *env, jclass clazz, jstring path, jobject callback, jboolean force_reinstall);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_copyLicense(JNIEnv *env, jclass clazz, jstring path);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_createLicense(JNIEnv *env, jclass clazz, jstring zrif);
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_findPkgZrif(JNIEnv *env, jclass clazz, jstring pkg_path);
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_convertRifToZrif(JNIEnv *env, jclass clazz, jstring path);

// --- native_session.cpp ---
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_setInputIntercepted(JNIEnv *env, jclass clazz, jboolean intercepted);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_setPauseReasonEnabled(JNIEnv *env, jclass clazz, jint reason_mask, jboolean enabled);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isAppRunning(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_requestAppQuit(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_isAppPaused(JNIEnv *env, jclass clazz);
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_getRunningAppTitle(JNIEnv *env, jclass clazz);
// R5-A3-7: isImeActive/submitIme/dismissIme NO se declaran aquí:
// existen en upstream (native_session.cpp:151-176) pero ningún
// código del launcher las llama (verificado por grep en
// app/src/main/java y app/src/main/cpp). Si el IME hace falta algún
// día, re-añadirlas con estas firmas exactas: jboolean (JNIEnv *,
// jclass), sin argumentos.

// --- native_trophies.cpp ---
// Solo lectura: devuelve las colecciones de trofeos del TRP
// (CollectionSnapshot por npComId). Verificado contra el pin
// (native_trophies.cpp:206-207): una sola función, sin parámetros,
// ligada a NativeLib; el adaptador pasa nullptr como jclass, igual
// que con las demás. Se compila dentro de libVita3K.so (pin
// CMakeLists.txt:167), así que la llamada directa enlaza.
// Devuelve vacío si no hay emuenv (sesión destruida): el llamador
// lee ANTES de destroySession (ver trophy_bridge.cpp).
JNIEXPORT jobjectArray JNICALL
Java_org_vita3k_emulator_NativeLib_getTrophyCollections(JNIEnv *env, jclass clazz);

// --- native_drivers.cpp ---
// Instala un driver GPU personalizado (zip Turnip): upstream extrae el
// zip en <filesDir>/driver/<stem-del-zip>/ y devuelve ese stem como
// nombre del driver ("" si falló o si ya existe). Ese nombre es el
// valor exacto que espera `custom-driver-name` en config.yml.
JNIEXPORT jstring JNICALL
Java_org_vita3k_emulator_NativeLib_installCustomDriver(JNIEnv *env, jclass clazz, jstring path);
JNIEXPORT jobjectArray JNICALL
Java_org_vita3k_emulator_NativeLib_getInstalledCustomDrivers(JNIEnv *env, jclass clazz);
JNIEXPORT jboolean JNICALL
Java_org_vita3k_emulator_NativeLib_removeCustomDriver(JNIEnv *env, jclass clazz, jstring driver_name);

// --- native_config.cpp ---
// Máscara de métodos de mapeo de memoria soportados por el driver
// (ronda 0.2.64, P1-3 Mali; native_config.cpp:804 del pin 3e43bc80).
// [custom_driver_name] es el nombre del driver Turnip instalado ("" =
// driver del sistema). Devuelve la máscara de bits de MappingMethod
// (renderer/include/renderer/state.h:51): Disabled=1<<0,
// DoubleBuffer=1<<1, ExernalHost=1<<2, PageTable=1<<3,
// NativeBuffer=1<<4. Con el driver del sistema y sin sesión, enumera
// los dispositivos Vulkan y devuelve la máscara del primero; si no
// hay ninguno, devuelve 1<<0 (solo Disabled). Si el método pedido no
// está en la máscara, el núcleo vuelve a Double Buffer en silencio
// (vulkan/renderer.cpp:1019-1022): por eso el launcher muestra el
// método efectivo, no solo el pedido (ver MemoryMapping en el modelo).
JNIEXPORT jint JNICALL
Java_org_vita3k_emulator_NativeLib_getSupportedMemoryMappingMask(JNIEnv *env, jclass clazz, jstring custom_driver_name);

// --- input_overlay.cpp (overlay táctil: mando virtual SDL) ---
// Verificado contra Vita3K pin 3e43bc80 (vita3k/android/jni/
// input_overlay.cpp): el overlay de upstream es un joystick virtual
// SDL ("Vita3K Virtual Controller", SDL_JOYSTICK_TYPE_GAMEPAD) que el
// núcleo lee como un mando más. attach/detach crean/destruyen ese
// joystick (attach es idempotente: primero lo suelta); setButton con
// button < 0 manda L2/R2 como eje (SDL_SetJoystickVirtualAxis con
// -button); setTouchState cambia táctil frontal/trasero de Vita.
// La UI del overlay en el launcher es propia (GamepadOverlayView):
// estas funciones solo inyectan botones/ejes. Se llaman con thiz
// nullptr, igual que las demás (upstream ignora el objeto).
JNIEXPORT void JNICALL
Java_org_vita3k_emulator_overlay_InputOverlay_attachController(JNIEnv *env, jobject thiz);
JNIEXPORT void JNICALL
Java_org_vita3k_emulator_overlay_InputOverlay_detachController(JNIEnv *env, jobject thiz);
JNIEXPORT void JNICALL
Java_org_vita3k_emulator_overlay_InputOverlay_setAxis(JNIEnv *env, jobject thiz, jint axis, jshort value);
JNIEXPORT void JNICALL
Java_org_vita3k_emulator_overlay_InputOverlay_setButton(JNIEnv *env, jobject thiz, jint button, jboolean value);
JNIEXPORT void JNICALL
Java_org_vita3k_emulator_overlay_InputOverlay_setTouchState(JNIEnv *env, jobject thiz, jboolean is_back);

} // extern "C"
