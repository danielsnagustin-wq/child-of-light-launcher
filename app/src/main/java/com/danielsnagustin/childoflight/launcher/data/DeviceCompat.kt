package com.danielsnagustin.childoflight.launcher.data

import android.os.Build

/**
 * Compatibilidad del dispositivo: arquitectura (64/32 bits).
 *
 * Por qué existe: el núcleo embebido solo se compila para arm64-v8a
 * (ver app/build.gradle.kts: abiFilters). En un teléfono de 32 bits
 * System.loadLibrary("vita3k") falla y el launcher ya cae al fallback
 * externo sin crashear (Vita3kCoreBridge captura UnsatisfiedLinkError),
 * pero el usuario no sabía POR QUÉ nunca se activaba el núcleo. Ahora
 * la pantalla de diagnóstico lo dice claro, en español.
 *
 * La RAM la cubre DeviceMemory (auditoría ≤ 4 GB del mismo día): no se
 * duplica aquí; la pantalla de diagnóstico lee de ambos.
 *
 * Las funciones puras (con listas como parámetro) son testeables en JVM;
 * las que leen Build van protegidas con runCatching para no tumbar
 * nunca al launcher si Android devuelve algo raro.
 */
object DeviceCompat {

    /** Puro/testeable: true si hay algún ABI de 64 bits entre los soportados. */
    fun is64BitDevice(supported64BitAbis: List<String>, supportedAbis: List<String>): Boolean =
        supported64BitAbis.isNotEmpty() ||
            supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }

    /** Lectura real de Build.SUPPORTED_64_BIT_ABIS / SUPPORTED_ABIS. */
    fun is64BitDevice(): Boolean = runCatching {
        is64BitDevice(
            Build.SUPPORTED_64_BIT_ABIS?.toList() ?: emptyList(),
            Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
        )
    }.getOrDefault(false)

    /** Etiqueta corta: "64 bits (arm64-v8a)" o "32 bits (armeabi-v7a)". */
    fun architectureLabel(): String = runCatching {
        val first = Build.SUPPORTED_ABIS?.firstOrNull() ?: "desconocida"
        if (is64BitDevice()) "64 bits ($first)" else "32 bits ($first)"
    }.getOrDefault("desconocida")
}
