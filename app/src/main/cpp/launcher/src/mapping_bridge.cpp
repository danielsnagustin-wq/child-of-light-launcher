// mapping_bridge.cpp — puente JNI de la máscara de mapeo de memoria
// y del estado del driver personalizado (ronda 0.2.64, P1-3 Mali;
// ronda 0.2.65, R-P1-12 y J-P2-8).
//
// Funciones JNI de com.danielsnagustin.childoflight.launcher.core.Vita3kCoreBridge
// para preguntar al núcleo qué métodos de mapeo de memoria soporta el
// driver y si el driver personalizado pedido cargó de verdad.
// Reenvían a native_config.cpp de upstream (pin 3e43bc80,
// getSupportedMemoryMappingMask:804, getCustomDriverSupportInfo:816);
// sin árbol Vita3K compilan como stubs (0 = desconocida / null) y el
// llamador no filtra nada ni afirma nada sobre el driver.
//
// POR QUÉ EXISTE ESTE PUENTE (verificado contra upstream, pin
// 3e43bc80): el método de mapeo pedido en config.yml
// (memory-mapping-method) solo se honra si su bit está en la máscara
// soportada (vulkan/renderer.cpp:1019-1022); si no, el núcleo vuelve
// a Double Buffer EN SILENCIO (renderer.cpp:750). En Mali, Native
// Buffer no siempre está soportado y Page Table depende del driver:
// sin la máscara, la UI ofrecía 5 métodos sin saber cuáles existen
// y el usuario no veía por qué su elección no tenía efecto. Y con un
// Turnip obsoleto, resolve_vk_get_instance_proc_addr cae al loader
// del sistema (android_driver.cpp:479-491) y la máscara devuelta es
// la del sistema: sin getCustomDriverSupportInfo el juego rendía con
// el sistema mientras la UI creía Turnip activo.
//
// CACHÉ (J-P2-8): la ruta del driver personalizado re-enumeraba
// Vulkan con una VkInstance nueva por llamada. Las dos funciones de
// este archivo comparten UNA sola consulta a upstream
// (getCustomDriverSupportInfo, que enumera una vez y devuelve
// máscara + estado de carga; la máscara que devuelve es la misma
// que getSupportedMemoryMappingMask para el mismo nombre: con ""
// ambas usan get_default_supported_memory_mapping_mask(get_emuenv()),
// y con un nombre ambas toman el primer mapping_method_masks de
// enumerate_vulkan_devices). El resultado se cachea por nombre de
// driver pedido y se invalida cuando el nombre cambia. Si se
// reinstala un zip con el MISMO nombre pero otro contenido, la caché
// queda obsoleta hasta que cambie el nombre: el nombre es la clave
// que el núcleo mismo usa (custom-driver-name), y no hay señal de
// "mismo nombre, binario distinto" sin preguntar de nuevo.
//
// Robustez JNI: igual que driver_bridge.cpp — si upstream deja una
// excepción pendiente se limpia y se devuelve 0 (desconocida) o null;
// una excepción C++ sin capturar cruzando JNI aborta el proceso, así
// que se captura y se devuelve el valor de error. El argumento es el
// nombre del driver Turnip instalado ("" = driver del sistema); null
// desde Kotlin no puede llegar (String no nulo), pero se protege igual.

#include <jni.h>
#include <android/log.h>

#include <exception>
#include <mutex>
#include <string>

#define LOG_TAG "LauncherBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// launcher_bridge.h se incluye siempre (no solo con núcleo): el
// helper de limpiar excepciones pendientes se usa también en la
// rama stub.
#include "launcher_bridge.h"

namespace {

// Resultado cacheado de la enumeración Vulkan para un nombre de
// driver pedido. Protegido por mutex: JNI puede llamarse desde
// varios hilos de Kotlin (UI / IO).
std::mutex g_driver_support_mutex;
std::string g_cached_driver_name;
jint g_cached_mask = 0;
jint g_cached_loaded = 0;
bool g_cache_valid = false;

// Consulta a upstream (una sola enumeración) y cachea el resultado
// bajo el nombre pedido. Devuelve false si no se pudo preguntar
// (upstream lanzó o dejó excepción pendiente): el llamador devuelve
// el valor de error. Con caché válida para el mismo nombre no toca
// upstream en absoluto.
bool query_driver_support_cached(JNIEnv *env, const std::string &driver_name, jint &mask_out, jint &loaded_out) {
    {
        std::lock_guard<std::mutex> lock(g_driver_support_mutex);
        if (g_cache_valid && g_cached_driver_name == driver_name) {
            mask_out = g_cached_mask;
            loaded_out = g_cached_loaded;
            return true;
        }
    }
    jintArray info = nullptr;
    try {
        jstring name_arg = env->NewStringUTF(driver_name.c_str());
        if (name_arg == nullptr) {
            launcher_clear_pending_exception(env);
            LOGE("query_driver_support_cached: no se pudo crear el nombre del driver");
            return false;
        }
        info = Java_org_vita3k_emulator_NativeLib_getCustomDriverSupportInfo(env, nullptr, name_arg);
        env->DeleteLocalRef(name_arg);
    } catch (const std::exception &e) {
        LOGE("query_driver_support_cached: excepción C++ capturada: %s", e.what());
        launcher_clear_pending_exception(env);
        return false;
    } catch (...) {
        LOGE("query_driver_support_cached: excepción C++ desconocida capturada");
        launcher_clear_pending_exception(env);
        return false;
    }
    if (launcher_clear_pending_exception(env) || info == nullptr) {
        return false;
    }
    jsize length = env->GetArrayLength(info);
    if (length < 2) {
        env->DeleteLocalRef(info);
        LOGE("query_driver_support_cached: upstream devolvió un array de %d elementos", static_cast<int>(length));
        return false;
    }
    jint values[2] = {0, 0};
    env->GetIntArrayRegion(info, 0, 2, values);
    env->DeleteLocalRef(info);
    if (launcher_clear_pending_exception(env)) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(g_driver_support_mutex);
        g_cached_driver_name = driver_name;
        g_cached_mask = values[0];
        g_cached_loaded = values[1];
        g_cache_valid = true;
    }
    mask_out = values[0];
    loaded_out = values[1];
    return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetSupportedMemoryMappingMask(
    JNIEnv *env, jobject /* thiz */, jstring customDriverName) {
#ifdef LAUNCHER_HAS_VITA3K
    if (customDriverName == nullptr) {
        LOGW("nativeGetSupportedMemoryMappingMask: nombre de driver nulo");
        return 0;
    }
    const char *utf_chars = env->GetStringUTFChars(customDriverName, nullptr);
    if (utf_chars == nullptr) {
        launcher_clear_pending_exception(env);
        LOGW("nativeGetSupportedMemoryMappingMask: no se pudo leer el nombre del driver");
        return 0;
    }
    const std::string driver_name(utf_chars);
    env->ReleaseStringUTFChars(customDriverName, utf_chars);
    jint mask = 0;
    jint loaded = 0;
    if (!query_driver_support_cached(env, driver_name, mask, loaded)) {
        return 0;
    }
    return mask;
#else
    LOGW("nativeGetSupportedMemoryMappingMask STUB: núcleo no embebido");
    return 0;
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_danielsnagustin_childoflight_launcher_core_Vita3kCoreBridge_nativeGetCustomDriverSupportInfo(
    JNIEnv *env, jobject /* thiz */, jstring customDriverName) {
#ifdef LAUNCHER_HAS_VITA3K
    if (customDriverName == nullptr) {
        LOGW("nativeGetCustomDriverSupportInfo: nombre de driver nulo");
        return nullptr;
    }
    const char *utf_chars = env->GetStringUTFChars(customDriverName, nullptr);
    if (utf_chars == nullptr) {
        launcher_clear_pending_exception(env);
        LOGW("nativeGetCustomDriverSupportInfo: no se pudo leer el nombre del driver");
        return nullptr;
    }
    const std::string driver_name(utf_chars);
    env->ReleaseStringUTFChars(customDriverName, utf_chars);
    jint mask = 0;
    jint loaded = 0;
    if (!query_driver_support_cached(env, driver_name, mask, loaded)) {
        return nullptr;
    }
    jint values[2] = {mask, loaded};
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        launcher_clear_pending_exception(env);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    if (launcher_clear_pending_exception(env)) {
        return nullptr;
    }
    return result;
#else
    LOGW("nativeGetCustomDriverSupportInfo STUB: núcleo no embebido");
    return nullptr;
#endif
}
