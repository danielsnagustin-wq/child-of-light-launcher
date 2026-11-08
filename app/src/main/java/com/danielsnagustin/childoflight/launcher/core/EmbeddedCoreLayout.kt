package com.danielsnagustin.childoflight.launcher.core

import android.content.Context
import java.io.File

/**
 * Layout del almacenamiento para el núcleo embebido (Etapa 2).
 *
 * DESAJUSTE VERIFICADO contra Vita3K upstream (master 3e43bc80,
 * vita3k/android/jni/native_bootstrap.cpp, initialize_session):
 * el núcleo espera `<storage>/vita/ux0/...` y `config.yml` en
 * `<storage>/config.yml` (vita_fs = storage_path / "vita",
 * config path = storage_path). La Etapa 1 guardaba
 * `<filesDir>/vita3k/ux0/...` (sin la carpeta `vita/` intermedia).
 *
 * Por eso, al activar el núcleo embebido hay que migrar UNA vez:
 * `vita3k/ux0` -> `vita3k/vita/ux0`. La config.yml NO se mueve
 * (sigue en la raíz del storage, que es donde el núcleo la lee) y
 * tampoco se mueven `firmware/` ni `drivers/` (son del launcher,
 * no del núcleo). La migración es idempotente y no borra nada si
 * no puede mover: en caso de conflicto no toca nada y lo reporta.
 *
 * Todo es java.io.File puro (sin Context salvo storageRoot) para
 * poder probarlo en tests unitarios de la JVM.
 */
object EmbeddedCoreLayout {

    /** Raíz de almacenamiento del launcher: <filesDir>/vita3k (igual que Etapa 1). */
    fun storageRoot(context: Context): File = File(context.filesDir, "vita3k")

    /** <storage>/vita — el vita_fs del núcleo. */
    fun vitaDir(storageRoot: File): File = File(storageRoot, "vita")

    /** <storage>/vita/ux0 — donde el núcleo busca app/addcont/patch/savedata. */
    fun embeddedUx0Dir(storageRoot: File): File = File(vitaDir(storageRoot), "ux0")

    /** <storage>/ux0 — layout antiguo de Etapa 1. */
    fun legacyUx0Dir(storageRoot: File): File = File(storageRoot, "ux0")

    /** true si el layout ya es el del núcleo embebido. */
    fun isMigrated(storageRoot: File): Boolean = embeddedUx0Dir(storageRoot).isDirectory

    /** true si hay layout antiguo que migrar (y aún no está migrado). */
    fun needsMigration(storageRoot: File): Boolean =
        legacyUx0Dir(storageRoot).isDirectory &&
            (!isMigrated(storageRoot) || isEmptyDir(embeddedUx0Dir(storageRoot)))

    /** true si el directorio no existe o no contiene ningún archivo (carpetas vacías no cuentan). */
    private fun isEmptyDir(dir: File): Boolean =
        !dir.isDirectory || countFiles(dir) == 0

    /**
     * ux0 efectivo para TODO el launcher (juego, DLCs, updates, saves,
     * trofeos, .pkg): `vita/ux0` si ya se migró al layout del núcleo,
     * si no el `ux0` antiguo de Etapa 1. Los repositorios deben usar
     * este dir, no construir `File(vitaRoot, "ux0/...")` a mano, o
     * "perderían" el juego tras la primera migración.
     *
     * Excepción (2026-11-07): si `vita/ux0` existe pero está VACÍO y
     * el `ux0` antiguo tiene contenido, el juego vive en el antiguo:
     * el núcleo puede haber creado `vita/ux0` vacío al inicializarse
     * antes de migrar. Devolver el embebido vacío haría que el juego
     * "desapareciera" (scanStatus no lo vería) y la migración se
     * quedaría en Conflicto para siempre. En ese caso se usa el
     * antiguo y la migración (que limpia el destino vacío) lo
     * recoloca al lanzar.
     *
     * Ampliación (2026-11-08): el embebido existe y NO está vacío,
     * pero el juego importado vive en el antiguo y el embebido no lo
     * tiene (el núcleo creó `vita/ux0` con otro contenido antes de
     * migrar). En ese caso también se usa el antiguo: la resolución
     * nunca debe "perder" un juego ya importado, o JUGAR diría que
     * no está instalado aunque la Home lo muestre guardado. La
     * migración recoloca el juego al lanzar, cuando decide() sí se
     * alcanza.
     */
    fun resolveUx0Dir(storageRoot: File): File {
        val embedded = embeddedUx0Dir(storageRoot)
        val legacy = legacyUx0Dir(storageRoot)
        if (embedded.isDirectory && legacy.isDirectory &&
            isEmptyDir(embedded) && !isEmptyDir(legacy)
        ) {
            return legacy
        }
        // El embebido no está vacío pero no tiene el juego que sí vive
        // en el antiguo: devolver el embebido lo haría "desaparecer"
        // (scanStatus no lo vería y JUGAR diría que no está instalado).
        if (embedded.isDirectory && legacy.isDirectory) {
            val legacyGames = gameTitles(legacy)
            if (legacyGames.isNotEmpty() && !gameTitles(embedded).containsAll(legacyGames)) {
                return legacy
            }
        }
        return if (isMigrated(storageRoot)) embedded else legacy
    }

    /**
     * Title IDs con juego en un ux0: .pkg de juego en pkg/ (por nombre
     * de archivo, `<TITLE>.pkg`) y juego extraído en app/<TITLE>/ con
     * eboot.bin (el nombre se compara sin distinguir mayúsculas, como
     * los dumps que lo traen como EBOOT.BIN). Los .pkg de DLC
     * (pkg/dlc/) y de update (pkg/patch/) no cuentan: no son el juego.
     */
    private fun gameTitles(ux0Dir: File): Set<String> {
        val titles = mutableSetOf<String>()
        File(ux0Dir, "pkg").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pkg", ignoreCase = true) }
            ?.forEach { titles.add(it.nameWithoutExtension) }
        File(ux0Dir, "app").listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { appDir ->
                val hasEboot = appDir.listFiles()
                    ?.any { it.isFile && it.name.equals("eboot.bin", ignoreCase = true) } == true
                if (hasEboot) titles.add(appDir.name)
            }
        return titles
    }

    sealed class MigrationResult {
        /** Se movió ux0 -> vita/ux0. filesMoved = nº de archivos movidos. */
        data class Migrated(val filesMoved: Int) : MigrationResult()
        /** Ya estaba migrado: no se hizo nada. */
        data object AlreadyMigrated : MigrationResult()
        /** No había nada que migrar (ni ux0 antiguo ni vita/ux0). */
        data object NothingToMigrate : MigrationResult()
        /** No se pudo migrar: NO se tocó nada. reason explica por qué. */
        data class Conflict(val reason: String) : MigrationResult()
    }

    /**
     * Migra el layout de Etapa 1 al del núcleo embebido (una sola vez).
     * Intenta renameTo (misma partición, instantáneo); si falla, copia
     * recursiva y borra el origen solo si la copia fue completa.
     *
     * Conflicto: si existen los dos ux0 y el antiguo tiene un juego
     * que el embebido NO tiene, no se sabe cuál es la instalación
     * buena: no se mueve nada y se reporta para resolverlo a mano.
     *
     * Excepción (2026-11-08): si el destino embebido ya contiene el
     * juego y el antiguo no tiene ningún juego que el embebido no
     * tenga, el layout embebido ya es el bueno: no hay nada que mover
     * y se devuelve AlreadyMigrated para que el lanzamiento continúe.
     * Devolver Conflict en ese caso bloqueaba JUGAR para siempre
     * aunque el juego ya estuviera instalado en vita/ux0 (el escaneo
     * lo veía, instalado=true). El ux0 antiguo se deja intacto: lo
     * ignora resolveUx0Dir porque el embebido tiene los juegos.
     */
    fun migrateToEmbeddedLayout(storageRoot: File): MigrationResult {
        val legacy = legacyUx0Dir(storageRoot)
        val dest = embeddedUx0Dir(storageRoot)
        // Conflicto PRIMERO: si existen los dos, no se sabe cuál es el
        // bueno y tratarlo como "ya migrado" escondería datos. No se
        // toca nada y se reporta para resolverlo a mano.
        // Excepción (2026-11-07): destino VACÍO (sin archivos, solo
        // carpetas vacías): lo creó el núcleo al inicializarse antes de
        // migrar. No hay nada que perder: se quita y se procede con el
        // rename. Sin esto, el juego del layout antiguo quedaba huérfano
        // para siempre (Conflicto en cada lanzamiento).
        if (legacy.isDirectory && dest.exists()) {
            if (dest.isDirectory && countFiles(dest) == 0) {
                dest.deleteRecursively()
            } else if (dest.isDirectory && gameTitles(legacy).all { it in gameTitles(dest) }) {
                // El embebido ya tiene el juego (o el antiguo no tiene
                // ningún juego): nada que mover, el lanzamiento sigue.
                return MigrationResult.AlreadyMigrated
            } else {
                return MigrationResult.Conflict(
                    "Ya existe una instalación en el motor integrado y también la antigua; " +
                        "no se mueve nada para no mezclar dos instalaciones.",
                )
            }
        }
        if (isMigrated(storageRoot)) return MigrationResult.AlreadyMigrated
        if (!legacy.isDirectory) return MigrationResult.NothingToMigrate
        val vita = vitaDir(storageRoot)
        if (!vita.exists() && !vita.mkdirs()) {
            return MigrationResult.Conflict("No se pudo crear la carpeta del motor integrado.")
        }
        val count = countFiles(legacy)
        if (legacy.renameTo(dest)) return MigrationResult.Migrated(count)
        // renameTo falló (p. ej. distinto punto de montaje): copiar + borrar.
        return try {
            legacy.copyRecursively(dest, overwrite = false)
            if (!dest.isDirectory || countFiles(dest) != count) {
                dest.deleteRecursively()
                return MigrationResult.Conflict("La copia quedó incompleta; no se borró el original.")
            }
            legacy.deleteRecursively()
            MigrationResult.Migrated(count)
        } catch (e: Exception) {
            dest.deleteRecursively()
            MigrationResult.Conflict("No se pudo copiar la instalación antigua al motor integrado.")
        }
    }

    private fun countFiles(dir: File): Int {
        var n = 0
        dir.walkTopDown().forEach { if (it.isFile) n++ }
        return n
    }
}
