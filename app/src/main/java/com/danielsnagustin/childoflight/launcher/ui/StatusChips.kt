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
            detail = status.firmwareName ?: "",
        ),
    )
}
