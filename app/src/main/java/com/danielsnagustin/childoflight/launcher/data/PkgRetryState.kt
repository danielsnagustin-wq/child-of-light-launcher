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
 */
data class PkgRetryEntry(
    val key: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val failedReinstalls: Int,
    val lastFailedAtMillis: Long,
)

data class PkgRetryState(val entries: Map<String, PkgRetryEntry> = emptyMap()) {

    /** Clave del .pkg del juego (un juego por launcher). */
    companion object {
        fun gameKey(titleId: String): String = "game:$titleId"

        /** Clave de un .pkg de update/DLC guardado (por archivo). */
        fun addonKey(titleId: String, fileName: String): String = "addon:$titleId:$fileName"

        private const val MAX_FAILURES_BEFORE_BLOCK = 1

        /** Decodifica el estado desde el texto del archivo (líneas malformadas se saltan). */
        fun decode(text: String): PkgRetryState {
            val entries = mutableMapOf<String, PkgRetryEntry>()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split("|")
                if (parts.size != 6) continue
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
                )
                entries[entry.key] = entry
            }
            return PkgRetryState(entries)
        }
    }

    /** Codifica el estado a texto (una línea por entrada). */
    fun encode(): String = entries.values.joinToString("\n") { e ->
        listOf(e.key, e.fileName, e.sizeBytes, e.lastModifiedMillis, e.failedReinstalls, e.lastFailedAtMillis)
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
        )
        return copy(entries = entries + (key to entry))
    }

    /** Limpia la entrada (éxito, o el usuario borró el .pkg). */
    fun recordSuccess(key: String): PkgRetryState = copy(entries = entries - key)

    private fun sameFile(entry: PkgRetryEntry, pkgFile: java.io.File): Boolean =
        entry.fileName == pkgFile.name &&
            entry.sizeBytes == pkgFile.length() &&
            entry.lastModifiedMillis == pkgFile.lastModified()
}
