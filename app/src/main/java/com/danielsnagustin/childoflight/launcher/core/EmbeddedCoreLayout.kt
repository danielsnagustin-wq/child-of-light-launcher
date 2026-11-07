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
        legacyUx0Dir(storageRoot).isDirectory && !isMigrated(storageRoot)

    /**
     * ux0 efectivo para TODO el launcher (juego, DLCs, updates, saves,
     * trofeos, .pkg): `vita/ux0` si ya se migró al layout del núcleo,
     * si no el `ux0` antiguo de Etapa 1. Los repositorios deben usar
     * este dir, no construir `File(vitaRoot, "ux0/...")` a mano, o
     * "perderían" el juego tras la primera migración.
     */
    fun resolveUx0Dir(storageRoot: File): File =
        if (isMigrated(storageRoot)) embeddedUx0Dir(storageRoot) else legacyUx0Dir(storageRoot)

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
     */
    fun migrateToEmbeddedLayout(storageRoot: File): MigrationResult {
        val legacy = legacyUx0Dir(storageRoot)
        val dest = embeddedUx0Dir(storageRoot)
        // Conflicto PRIMERO: si existen los dos, no se sabe cuál es el
        // bueno y tratarlo como "ya migrado" escondería datos. No se
        // toca nada y se reporta para resolverlo a mano.
        if (legacy.isDirectory && dest.exists()) {
            return MigrationResult.Conflict(
                "Ya existe ${dest.name} en ${dest.parent} y también el ux0 antiguo; " +
                    "no se mueve nada para no mezclar dos instalaciones.",
            )
        }
        if (isMigrated(storageRoot)) return MigrationResult.AlreadyMigrated
        if (!legacy.isDirectory) return MigrationResult.NothingToMigrate
        val vita = vitaDir(storageRoot)
        if (!vita.exists() && !vita.mkdirs()) {
            return MigrationResult.Conflict("No se pudo crear la carpeta ${vita.absolutePath}.")
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
            MigrationResult.Conflict("No se pudo copiar ux0: ${e.message}")
        }
    }

    private fun countFiles(dir: File): Int {
        var n = 0
        dir.walkTopDown().forEach { if (it.isFile) n++ }
        return n
    }
}
