package com.danielsnagustin.childoflight.launcher.data

import java.io.File

/**
 * Tamaño de un directorio con caché por (ruta, lastModified).
 *
 * scanStatus() se llama en init, en cada onResume y al volver del juego, y
 * antes recorría TODO el juego (~1,14 GB) y cada DLC en cada llamada. La
 * caché evita el recorrido cuando el directorio no cambió: la marca es el
 * lastModified del directorio, que Android actualiza al crear o borrar
 * hijos directos (instalar/desinstalar cambia hijos; jugar no toca el
 * árbol del juego). El contenido (qué hay instalado) se sigue leyendo
 * siempre: la caché solo guarda el tamaño en bytes.
 */
class DirSizeCache {
    // Ronda 0.2.55 P2-9: la clave es (ruta, mtime del directorio,
    // bytes agregados de los archivos de PRIMER nivel). Antes solo el
    // mtime: si el núcleo escribía DENTRO de un archivo existente
    // (contenido que crece sin crear/borrar entradas), el tamaño
    // cacheado quedaba obsoleto. El agregado de primer nivel es
    // barato (listFiles sin recursión) y caza ese crecimiento. Un
    // cambio anidado (dentro de una subcarpeta) sigue sin verse
    // hasta invalidate(): misma limitación documentada que antes.
    private val cache = mutableMapOf<String, Triple<Long, Long, Long>>()

    /** Tamaño total de los archivos bajo [dir] (0 si no existe). */
    fun sizeOf(dir: File): Long {
        val key = dir.absolutePath
        val stamp = dir.lastModified()
        val topLevelBytes = if (dir.isDirectory) {
            dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        } else {
            0L
        }
        synchronized(cache) {
            val cached = cache[key]
            if (cached != null && cached.first == stamp && cached.second == topLevelBytes) return cached.third
        }
        val size = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        synchronized(cache) { cache[key] = Triple(stamp, topLevelBytes, size) }
        return size
    }

    /** Olvida la entrada de [dir] (p. ej. tras una importación). */
    fun invalidate(dir: File) {
        synchronized(cache) { cache.remove(dir.absolutePath) }
    }
}
