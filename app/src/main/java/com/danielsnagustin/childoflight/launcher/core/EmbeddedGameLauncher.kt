package com.danielsnagustin.childoflight.launcher.core

import com.danielsnagustin.childoflight.launcher.launcher.Vita3KLauncher
import com.danielsnagustin.childoflight.launcher.model.GameStatus

/**
 * Decisión de por dónde lanzar el juego (Etapa 2: motor embebido
 * como camino principal).
 *
 * Función PURA (sin Android ni I/O) para poder probarla en unit tests.
 * La usa Vita3KLauncher antes de tocar nada; el lanzamiento real
 * (Activity SDL embebida o intent externo) vive en Vita3KLauncher.
 *
 * Reglas (el motor integrado es el camino normal; la app externa
 * nunca es un respaldo automático):
 * - Sin juego instalado -> Blocked (falta el juego).
 * - Sin Title ID -> Blocked (no se sabe qué arrancar).
 * - Núcleo embebido disponible + juego EXTRAÍDO + firmware local
 *   -> UseEmbedded: el juego arranca dentro del launcher.
 * - Núcleo disponible + juego solo en .pkg + firmware local
 *   -> UseEmbeddedPkg: primero se instala el .pkg con el propio
 *   núcleo (EmbeddedPkgInstaller) y luego se arranca embebido.
 *   SOLO si el .pkg tiene su licencia (work.bin) o la licencia ya
 *   está instalada en el núcleo (R6-P1-07): sin ninguna de las dos el
 *   núcleo instalaría un juego que no arranca y nadie avisaría
 *   (P0-1). Sin licencia en ningún sitio -> Blocked con "Falta la
 *   licencia (work.bin)…" para que la UI ofrezca el selector de
 *   work.bin (PendingPkgLicense, oleada 2). decide() es pura y lee
 *   licenseInstalledInCore del GameStatus (lo calcula scanStatus
 *   desde ux0/license/<TITLE>/<CONTENT_ID>.rif): si la licencia ya
 *   está instalada dentro del núcleo pero el work.bin guardado se
 *   perdió, decide() permite instalar igual, porque
 *   installPkgFile resuelve la licencia vía findPkgZrif. La
 *   reinstalación del eboot (que no pasa por decide()) ya usaba esa
 *   vía; el camino principal ahora también.
 * - Núcleo disponible pero SIN firmware local -> Blocked: el núcleo
 *   no puede arrancar sin firmware y ya no se salta a la app externa
 *   por detrás. El motivo dice dónde importarlo (Importar →
 *   Firmware).
 * - Sin núcleo embebido -> Blocked, SALVO que el usuario haya
 *   activado la opción avanzada en Ajustes (allowExternal): solo
 *   entonces UseExternal. La opción avanzada no cambia nada cuando
 *   el núcleo SÍ está presente: con núcleo, JUGAR siempre usa el
 *   motor integrado (o bloquea con el motivo exacto si algo falta).
 */
object EmbeddedGameLauncher {

    /**
     * Motivo del Blocked cuando el juego solo está en .pkg y falta su
     * licencia (work.bin) (P0-1). El ViewModel lo reconoce por
     * "Falta la licencia (work.bin)" para ofrecer el selector de
     * work.bin (PendingPkgLicense, oleada 2), igual que reconoce
     * "Falta el firmware" para ir a importar.
     */
    const val LICENSE_BLOCKED_EMBEDDED =
        "Falta la licencia (work.bin) del juego: sin ella el motor integrado no puede instalar el .pkg. " +
            "Elige el work.bin en Importar (tarjeta «Falta la licencia»)."

    sealed class Decision {
        /** Arrancar el juego extraído con el núcleo embebido. */
        data class UseEmbedded(val titleId: String) : Decision()
        /** Instalar el .pkg con el núcleo y luego arrancar embebido. */
        data class UseEmbeddedPkg(val titleId: String) : Decision()
        /**
         * Lanzar la app externa. Solo llega aquí si no hay núcleo
         * embebido Y el usuario activó la opción avanzada en Ajustes.
         * reason es solo para el log.
         */
        data class UseExternal(val reason: String) : Decision()
        /** No lanzar. reason es el mensaje para el usuario. */
        data class Blocked(val reason: String) : Decision()
    }

    fun decide(
        status: GameStatus,
        coreAvailable: Boolean,
        firmwareReady: Boolean,
        allowExternal: Boolean = false,
    ): Decision {
        val titleId = status.titleId?.id
            ?: return Decision.Blocked("No se pudo determinar el Title ID instalado.")
        if (!status.gameInstalled) {
            return Decision.Blocked(
                "El juego no está instalado. Importa tu copia de Child of Light (PCSE00480 o PCSB00598).",
            )
        }
        if (coreAvailable) {
            if (!firmwareReady) {
                return Decision.Blocked(Vita3KLauncher.FIRMWARE_BLOCKED_EMBEDDED)
            }
            return when {
                status.gameExtracted -> Decision.UseEmbedded(titleId)
                // P0-1: el .pkg sin licencia no se instala: el núcleo
                // dejaría un juego que no arranca sin avisar a nadie.
                // Solo el camino .pkg se bloquea: un juego extraído ya
                // tiene su licencia registrada en el núcleo y no la
                // necesita (por eso va primero).
                // R6-P1-07: la licencia instalada EN EL NÚCLEO también
                // vale: installPkgFile la reutiliza vía findPkgZrif
                // cuando no hay work.bin guardado, así que bloquear
                // aquí pedía un work.bin que ya no hace falta.
                status.pkgInstalled && !status.pkgLicensePresent && !status.licenseInstalledInCore ->
                    Decision.Blocked(LICENSE_BLOCKED_EMBEDDED)
                status.pkgInstalled -> Decision.UseEmbeddedPkg(titleId)
                // P2-A6: hoy inalcanzable por el invariante de
                // scanStatus (gameInstalled = extractedTitle != null
                // || pkgTitle != null, GameLibraryRepository.kt:677),
                // salvo un GameStatus construido a mano. El mensaje ya
                // no dice "no está instalado" (contradictorio: el
                // estado dice que sí lo está): describe el estado real.
                else -> Decision.Blocked(
                    "El juego está marcado como instalado pero no hay copia extraída ni .pkg guardado: " +
                        "vuelve a importar tu copia de Child of Light.",
                )
            }
        }
        // Sin núcleo embebido: la app externa solo se usa si el usuario
        // lo permitió explícitamente en Ajustes → General → Motor. Sin ese
        // permiso, se bloquea con el motivo exacto (reintentar /
        // reportar en el diálogo) en vez de saltar por fuera.
        if (allowExternal) {
            return Decision.UseExternal("Núcleo embebido no presente; opción avanzada activada.")
        }
        return Decision.Blocked(
            "El motor integrado no está disponible en esta versión: parece que instalaste la versión pequeña, sin núcleo. " +
                "Instala la APK del archivo grande «child-of-light-launcher-apks-native» (unos 66 MB, con el motor integrado) y reintenta.",
        )
    }
}
