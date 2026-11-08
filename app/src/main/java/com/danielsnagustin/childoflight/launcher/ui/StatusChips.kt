package com.danielsnagustin.childoflight.launcher.ui

import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.model.GameStatus

/**
 * Indicadores de estado de la Home (Fase 2, Ola 1).
 *
 * Lógica PURA (sin Compose, sin Android): dado el estado real del
 * disco (GameStatus) y del firmware, dice qué nivel tiene cada
 * indicador. Testeable en JVM (HomeStatusChipsTest). La UI solo
 * traduce el nivel a color y el texto a idioma.
 *
 * Niveles:
 * - OK: está instalado / listo.
 * - PENDING: está a medias (juego solo en .pkg sin instalar en el
 *   núcleo; PUP guardado sin instalar en el núcleo).
 * - MISSING: no hay nada.
 * No hay nivel de error: GameStatus / FirmwareStatus no expresan
 * errores, así que no se inventa un estado de error.
 *
 * Detalles reales (nada inventado):
 * - Juego: Title ID. DLCs: cantidad. Update: sin detalle (el
 *   escaneo no registra versión de update; es solo un booleano).
 * - Firmware: Ready → la versión instalada (p. ej. "3.74");
 *   Saved → "importado" (PUP guardado, pendiente de instalar en el
 *   núcleo). Así se distingue importado de instalado. Los DLCs
 *   tampoco tienen versión en los datos (DlcInfo no lleva versión).
 */
enum class StatusLevel {
    OK,
    PENDING,
    MISSING,
}

enum class HomeStatusKey {
    GAME,
    DLCS,
    UPDATE,
    FIRMWARE,
}

/** Un indicador: qué es, en qué nivel está y el detalle real (Title ID, cantidad, versión). */
data class StatusChipData(
    val key: HomeStatusKey,
    val level: StatusLevel,
    val detail: String = "",
)

fun homeStatusChips(status: GameStatus, firmware: FirmwareStatus): List<StatusChipData> {
    val gameLevel = when {
        status.gameExtracted -> StatusLevel.OK
        status.pkgInstalled -> StatusLevel.PENDING
        else -> StatusLevel.MISSING
    }
    val firmwareLevel = when (firmware) {
        is FirmwareStatus.Ready -> StatusLevel.OK
        is FirmwareStatus.Saved -> StatusLevel.PENDING
        is FirmwareStatus.Missing -> StatusLevel.MISSING
    }
    return listOf(
        StatusChipData(
            key = HomeStatusKey.GAME,
            level = gameLevel,
            detail = status.titleId?.id ?: "",
        ),
        StatusChipData(
            key = HomeStatusKey.DLCS,
            level = if (status.dlcs.isNotEmpty()) StatusLevel.OK else StatusLevel.MISSING,
            detail = if (status.dlcCount > 0) status.dlcCount.toString() else "",
        ),
        StatusChipData(
            key = HomeStatusKey.UPDATE,
            level = if (status.updateInstalled) StatusLevel.OK else StatusLevel.MISSING,
        ),
        StatusChipData(
            key = HomeStatusKey.FIRMWARE,
            level = firmwareLevel,
            // Ready: la versión instalada (firmwareName = "3.74", o
            // vacío si no hay marcador con versión). Saved: "importado"
            // — el PUP está guardado pero NO instalado en el núcleo;
            // no se muestra el texto largo "PSVUPDAT.PUP (guardado, no
            // instalado)" en el chip.
            detail = when (firmware) {
                is FirmwareStatus.Ready -> status.firmwareName ?: ""
                is FirmwareStatus.Saved -> "importado"
                is FirmwareStatus.Missing -> ""
            },
        ),
    )
}
