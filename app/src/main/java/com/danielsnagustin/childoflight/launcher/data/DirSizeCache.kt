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
    private val cache = mutableMapOf<String, Pair<Long, Long>>()

    /** Tamaño total de los archivos bajo [dir] (0 si no existe). */
    fun sizeOf(dir: File): Long {
        val key = dir.absolutePath
        val stamp = dir.lastModified()
        synchronized(cache) {
            val cached = cache[key]
            if (cached != null && cached.first == stamp) return cached.second
        }
        val size = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        synchronized(cache) { cache[key] = stamp to size }
        return size
    }

    /** Olvida la entrada de [dir] (p. ej. tras una importación). */
    fun invalidate(dir: File) {
        synchronized(cache) { cache.remove(dir.absolutePath) }
    }
}
