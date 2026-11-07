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
    fun findTitleDir(root: File, depth: Int = 0): Pair<TitleId, File>? {
        if (depth > 3) return null
        val children = root.listFiles() ?: return null
        for (child in children) {
            if (!child.isDirectory) continue
            TitleId.fromString(child.name)?.let { return it to child }
        }
        for (child in children) {
            if (!child.isDirectory) continue
            findTitleDir(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** Busca un hijo (archivo o carpeta) por nombre sin distinguir mayúsculas. */
    fun findChildIgnoreCase(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** eboot.bin dentro de la carpeta del juego, sin distinguir mayúsculas. */
    fun findEboot(titleDir: File): File? =
        findChildIgnoreCase(titleDir, "eboot.bin")?.takeIf { it.isFile }

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
