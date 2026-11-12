package com.danielsnagustin.childoflight.launcher.data

/**
 * Tope de reinstalación por .pkg (ronda 0.2.64, H-2 tiempo de carga,
 * P0 condicional). Puro y testeable en JVM (PkgRetryStateTest).
 *
 * PROBLEMA: si loadGame devuelve -3 con el mismo .pkg, el lanzador
 * reinstalaba el .pkg de 1,14 GB UNA VEZ POR LANZAMIENTO, en CADA
 * JUGAR, mientras el error persistiera. El reintento por lanzamiento
 * ya existía (shouldReinstallAndRetryLoadGame); faltaba el tope
 * ENTRE lanzamientos: una vez que un .pkg ya se reinstaló y siguió
 * sin validar, no se reinstala más hasta que el ARCHIVO cambie
 * (reimportado) o el usuario lo borre a propósito. Lo mismo vale
 * para los .pkg de update/DLC en installStoredAddonPkgs.
 *
 * ESTADO: una entrada por .pkg, con la identidad del archivo (nombre,
 * tamaño en bytes, última modificación) + cuántas reinstalaciones
 * fallidas lleva + cuándo fue la última. Se guarda en
 * `pkg-retry-state.txt` bajo vitaRoot (una línea por entrada,
 * campos separados por '|'): el formato línea a línea se eligió a
 * propósito — org.json es stub en los tests unitarios JVM (ver
 * SettingsBackup.kt) y DataStore no es el sitio para un estado que
 * el lanzador lee en el camino caliente de JUGAR. Las líneas
 * malformadas se saltan al leer.
 *
 * REGLA: la primera reinstalación fallida ya bloquea (failures >= 1):
 * una reinstalación que no cambia el resultado no merece una segunda
 * copia de 1,14 GB. Si el archivo cambia (otro tamaño o mtime), la
 * entrada ya no corresponde y el tope se resetea solo. Un loadGame
 * con éxito limpia la entrada del juego.
 *
 * Ronda 0.2.65 (X-P2-2): nombre, tamaño y mtime NO bastan para
 * identificar el archivo: reimportar un .pkg DISTINTO con el mismo
 * nombre, mismo tamaño y mismo mtime heredaba el bloqueo del viejo
 * (un .pkg nuevo y bueno quedaba bloqueado por el fallo del viejo).
 * La identidad incluye ahora un hash parcial del contenido (SHA-256
 * de 3 ventanas de 64 KB: inicio, mitad y final; un .pkg de 1,14 GB
 * no se lee entero para esto). Dos archivos con el mismo nombre,
 * tamaño y mtime pero distinto contenido tienen distinto hash y el
 * tope no se hereda. Las entradas viejas (sin hash) siguen valiendo
 * con la identidad clásica hasta que se reescriben.
 */
data class PkgRetryEntry(
    val key: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val failedReinstalls: Int,
    val lastFailedAtMillis: Long,
    /** Hash parcial SHA-256 del contenido (X-P2-2); "" = entrada vieja, sin hash. */
    val partialHashHex: String = "",
)

data class PkgRetryState(val entries: Map<String, PkgRetryEntry> = emptyMap()) {

    /** Clave del .pkg del juego (un juego por launcher). */
    companion object {
        fun gameKey(titleId: String): String = "game:$titleId"

        /** Clave de un .pkg de update/DLC guardado (por archivo). */
        fun addonKey(titleId: String, fileName: String): String = "addon:$titleId:$fileName"

        private const val MAX_FAILURES_BEFORE_BLOCK = 1

        /** Ventana del hash parcial (X-P2-2): 64 KB por muestra. */
        private const val PARTIAL_HASH_WINDOW_BYTES = 64 * 1024L

        /**
         * Hash parcial del contenido de un .pkg (X-P2-2): SHA-256 de
         * 3 ventanas de 64 KB (inicio, mitad y final del archivo).
         * Lee como mucho 192 KB aunque el .pkg pese 1,14 GB. "" si el
         * archivo no existe, está vacío o no se puede leer: en ese
         * caso la identidad vuelve a ser solo nombre, tamaño y mtime
         * (como antes). Determinista: el mismo contenido da siempre
         * el mismo hash; dos contenidos distintos con igual nombre,
         * tamaño y mtime casi nunca coinciden.
         */
        fun partialFileHash(pkgFile: java.io.File): String {
            return runCatching {
                val size = pkgFile.length()
                if (size <= 0L) return ""
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                java.io.RandomAccessFile(pkgFile, "r").use { raf ->
                    val window = PARTIAL_HASH_WINDOW_BYTES
                    if (size <= window) {
                        val all = ByteArray(size.toInt())
                        raf.readFully(all)
                        digest.update(all)
                    } else {
                        val buf = ByteArray(window.toInt())
                        val starts = listOf(0L, size / 2 - window / 2, size - window)
                        for (start in starts) {
                            raf.seek(start.coerceAtLeast(0L))
                            raf.readFully(buf)
                            digest.update(buf)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrDefault("")
        }

        /** Decodifica el estado desde el texto del archivo (líneas malformadas se saltan). */
        fun decode(text: String): PkgRetryState {
            val entries = mutableMapOf<String, PkgRetryEntry>()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split("|")
                // 6 campos = formato viejo (sin hash, X-P2-2); 7 = con hash.
                if (parts.size != 6 && parts.size != 7) continue
                val failures = parts[4].toIntOrNull() ?: continue
                val size = parts[2].toLongOrNull() ?: continue
                val mtime = parts[3].toLongOrNull() ?: continue
                val lastFailed = parts[5].toLongOrNull() ?: continue
                val entry = PkgRetryEntry(
                    key = parts[0],
                    fileName = parts[1],
                    sizeBytes = size,
                    lastModifiedMillis = mtime,
                    failedReinstalls = failures,
                    lastFailedAtMillis = lastFailed,
                    partialHashHex = if (parts.size == 7) parts[6] else "",
                )
                entries[entry.key] = entry
            }
            return PkgRetryState(entries)
        }
    }

    /** Codifica el estado a texto (una línea por entrada). */
    fun encode(): String = entries.values.joinToString("\n") { e ->
        listOf(e.key, e.fileName, e.sizeBytes, e.lastModifiedMillis, e.failedReinstalls, e.lastFailedAtMillis, e.partialHashHex)
            .joinToString("|")
    }

    /** true si este .pkg ya se intentó reinstalar y falló, SIN que el archivo haya cambiado. */
    fun isBlocked(key: String, pkgFile: java.io.File): Boolean {
        val entry = entries[key] ?: return false
        if (!sameFile(entry, pkgFile)) return false
        return entry.failedReinstalls >= MAX_FAILURES_BEFORE_BLOCK
    }

    /** true si se puede intentar la (re)instalación de este .pkg. */
    fun shouldAttemptReinstall(key: String, pkgFile: java.io.File): Boolean = !isBlocked(key, pkgFile)

    /** Registra una reinstalación fallida (crea o actualiza la entrada con la identidad actual). */
    fun recordFailedReinstall(key: String, pkgFile: java.io.File, nowMillis: Long): PkgRetryState {
        val previous = entries[key]
        val failures = if (previous != null && sameFile(previous, pkgFile)) previous.failedReinstalls + 1 else 1
        val entry = PkgRetryEntry(
            key = key,
            fileName = pkgFile.name,
            sizeBytes = pkgFile.length(),
            lastModifiedMillis = pkgFile.lastModified(),
            failedReinstalls = failures,
            lastFailedAtMillis = nowMillis,
            partialHashHex = partialFileHash(pkgFile),
        )
        return copy(entries = entries + (key to entry))
    }

    /** Limpia la entrada (éxito, o el usuario borró el .pkg). */
    fun recordSuccess(key: String): PkgRetryState = copy(entries = entries - key)

    /**
     * Limpia las entradas de un archivo por su nombre (ronda 0.2.65,
     * W-P1-5): al borrar a mano un addon bloqueado (sin Title ID del
     * juego) no hay clave game:/addon: a mano; se borran las entradas
     * que apuntan a ese nombre de archivo para que una reimportación
     * futura parta de cero.
     */
    fun clearEntriesForFile(fileName: String): PkgRetryState =
        copy(entries = entries.filterValues { it.fileName != fileName })

    private fun sameFile(entry: PkgRetryEntry, pkgFile: java.io.File): Boolean {
        if (entry.fileName != pkgFile.name) return false
        if (entry.sizeBytes != pkgFile.length()) return false
        if (entry.lastModifiedMillis != pkgFile.lastModified()) return false
        // X-P2-2: con hash, el contenido manda: mismo nombre, tamaño
        // y mtime pero distinto contenido NO es el mismo archivo.
        // Entradas viejas sin hash ("") usan la identidad clásica.
        if (entry.partialHashHex.isEmpty()) return true
        return entry.partialHashHex == partialFileHash(pkgFile)
    }
}
