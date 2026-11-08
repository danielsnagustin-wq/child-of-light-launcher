package com.danielsnagustin.childoflight.launcher.data

import com.danielsnagustin.childoflight.launcher.model.TitleId
import java.io.File

/**
 * Búsqueda de la carpeta del juego dentro de un árbol ya extraído
 * (zip/.vpk) o copiado. Pura (solo java.io.File): testeable sin Android
 * en ImportTreeSearchTest.
 *
 * Por qué existe: muchos paquetes EU traen el juego ANIDADO
 * ("Child of Light/PCSB00598/eboot.bin" o "PCSB00598 (EU)/...") y la
 * validación antigua exigía "PCSB00598/eboot.bin" en la raíz del zip:
 * el juego europeo válido se rechazaba con "no contiene eboot.bin" y
 * el usuario veía que "no lo reconoce". Ahora se busca la carpeta del
 * Title ID en el árbol (hasta 3 niveles) y se valida eboot.bin dentro
 * de ella, sin distinguir mayúsculas (EBOOT.BIN también vale).
 */
object ImportTreeSearch {

    /**
     * Busca una carpeta llamada PCSE00480 o PCSB00598 dentro de [root]
     * (hasta 3 niveles de profundidad). Devuelve el Title ID y la
     * carpeta encontrada, o null si no hay ninguna.
     */
    fun findTitleDir(root: File, depth: Int = 0): Pair<TitleId, File>? =
        findTitleDirs(root, depth).firstOrNull()

    /**
     * TODAS las carpetas de Title ID dentro de [root] (hasta 3 niveles).
     * Un .zip puede traer las dos regiones (US + EU): devolver solo la
     * primera instalaba una en silencio y escondía la otra (P2-C3).
     * El llamador decide: rechazar el paquete multi-región o instalar
     * ambas e informar. Sin duplicados por Title ID (si la misma región
     * aparece dos veces, gana la primera).
     */
    fun findTitleDirs(root: File, depth: Int = 0): List<Pair<TitleId, File>> {
        if (depth > 3) return emptyList()
        val children = root.listFiles() ?: return emptyList()
        val found = mutableListOf<Pair<TitleId, File>>()
        for (child in children) {
            if (!child.isDirectory) continue
            TitleId.fromString(child.name)?.let { found.add(it to child) }
        }
        for (child in children) {
            if (!child.isDirectory) continue
            found.addAll(findTitleDirs(child, depth + 1))
        }
        return found.distinctBy { it.first }
    }

    /**
     * Title ID leído de sce_sys/param.sfo dentro de la carpeta del juego
     * (por contenido, sin distinguir mayúsculas en los nombres). Null si
     * no hay SFO o no trae un Title ID válido.
     */
    fun sfoTitleId(titleDir: File): TitleId? {
        val sceSys = findChildIgnoreCase(titleDir, "sce_sys")?.takeIf { it.isDirectory } ?: return null
        val sfo = findChildIgnoreCase(sceSys, "param.sfo")?.takeIf { it.isFile } ?: return null
        val info = ParamSfo.parse(sfo).getOrNull() ?: return null
        return TitleId.fromString(info.titleId)
    }

    /**
     * Contrasta el SFO con el nombre de la carpeta (P2-C2): si existe
     * sce_sys/param.sfo, su TITLE_ID manda sobre el nombre. Si no
     * cuadra (carpeta PCSB00598 con el eboot/SFO de otro juego o de la
     * otra región), se rechaza: instalarlo cruzaría DLCs y saves por
     * región. Sin SFO, el nombre de la carpeta sigue valiendo.
     */
    fun validateTitleDirSfo(titleDir: File): Result<TitleId> {
        val folderTitle = TitleId.fromString(titleDir.name)
            ?: return Result.failure(
                IllegalArgumentException(
                    "La carpeta \"${titleDir.name}\" no es un Title ID de Child of Light.",
                ),
            )
        val sceSys = findChildIgnoreCase(titleDir, "sce_sys")?.takeIf { it.isDirectory }
        val sfo = sceSys?.let { findChildIgnoreCase(it, "param.sfo")?.takeIf { f -> f.isFile } }
        val rawTitle = sfo?.let { ParamSfo.parse(it).getOrNull()?.titleId?.trim() }
        if (!rawTitle.isNullOrEmpty()) {
            val sfoTitle = TitleId.fromString(rawTitle)
            if (sfoTitle == null) {
                return Result.failure(
                    IllegalArgumentException(
                        "El juego dentro de la carpeta ${folderTitle.id} no es Child of Light: " +
                            "su param.sfo dice $rawTitle. El contenido manda sobre el nombre de la carpeta.",
                    ),
                )
            }
            if (sfoTitle != folderTitle) {
                return Result.failure(
                    IllegalArgumentException(
                        "El juego dentro de la carpeta ${folderTitle.id} no es Child of Light " +
                            "de esa región: su param.sfo dice ${sfoTitle.id}. El contenido manda " +
                            "sobre el nombre de la carpeta.",
                    ),
                )
            }
        }
        return Result.success(folderTitle)
    }

    /** Busca un hijo (archivo o carpeta) por nombre sin distinguir mayúsculas. */
    fun findChildIgnoreCase(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** eboot.bin dentro de la carpeta del juego, sin distinguir mayúsculas. */
    fun findEboot(titleDir: File): File? =
        findChildIgnoreCase(titleDir, "eboot.bin")?.takeIf { it.isFile }

    /**
     * Juego PLANO: eboot.bin en la raíz del árbol (sin carpeta de Title
     * ID) con sce_sys/param.sfo al lado. El Title ID se lee del SFO por
     * contenido, nunca del nombre de ninguna carpeta: hay paquetes que
     * traen el juego descomprimido sin la carpeta PCSE00480/PCSB00598.
     * Devuelve el Title ID y la raíz, o null si no es un juego plano válido.
     */
    fun findFlatGame(root: File): Pair<TitleId, File>? {
        if (findEboot(root) == null) return null
        val sceSys = findChildIgnoreCase(root, "sce_sys")?.takeIf { it.isDirectory } ?: return null
        val sfo = findChildIgnoreCase(sceSys, "param.sfo")?.takeIf { it.isFile } ?: return null
        val info = ParamSfo.parse(sfo).getOrNull() ?: return null
        val title = TitleId.fromString(info.titleId) ?: return null
        return title to root
    }

    /**
     * Carpeta addcont/ dentro de la carpeta del juego: primero hija
     * directa (estructura normal <TITLE>/addcont/<DLC>/), y si no, un
     * nivel más abajo (paquetes con una carpeta intermedia).
     */
    fun findAddcontDir(titleDir: File): File? {
        findChildIgnoreCase(titleDir, "addcont")
            ?.takeIf { it.isDirectory }
            ?.let { return it }
        val children = titleDir.listFiles() ?: return null
        for (child in children) {
            if (!child.isDirectory) continue
            findChildIgnoreCase(child, "addcont")
                ?.takeIf { it.isDirectory }
                ?.let { return it }
        }
        return null
    }

    /** Nombres de las carpetas de nivel superior (para el mensaje de rechazo). */
    fun topLevelDirNames(root: File): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()
}
