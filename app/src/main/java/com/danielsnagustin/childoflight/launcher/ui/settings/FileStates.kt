package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.model.GameStatus
import com.danielsnagustin.childoflight.launcher.ui.ColTurquoise
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.SectionTitle
import com.danielsnagustin.childoflight.launcher.ui.cardTextColor
import com.danielsnagustin.childoflight.launcher.ui.isDarkUi
import com.danielsnagustin.childoflight.launcher.ui.mutedTextColor
import com.danielsnagustin.childoflight.launcher.ui.LocalUiPreferences

/**
 * Estado de archivos (Ola 3, Etapa 7): los 6 estados, todos leídos del
 * disco (GameStatus de scanStatus + FirmwareStatus), sin inventar nada.
 *
 * La regla es una sola y se repite en cada fila: GUARDADO no es
 * INSTALADO. Un .pkg guardado no es un juego instalado; un PUP
 * guardado no es firmware instalado. El estado (4), juego instalado
 * en la app externa, es una CONFIRMACIÓN HONESTA del usuario y se
 * etiqueta así: el launcher no puede leer la biblioteca de la app
 * externa para comprobarlo.
 *
 * Reutiliza los estados que ya muestran ImportScreen y General; no
 * duplica lógica de escaneo: solo lee GameStatus y FirmwareStatus.
 */
@Composable
fun FileStateSection(
    status: GameStatus,
    firmwareStatus: FirmwareStatus,
    pkgInstalledInVita3k: Boolean,
) {
    // R4-P1-14: textos segun el tema (en Claro, blanco sobre fondo claro no se lee).
    val dark = isDarkUi(LocalUiPreferences.current)
    SectionTitle("Estado de archivos")
    // 1. Firmware importado = PUP guardado.
    Text(
        "Firmware importado: " + when (firmwareStatus) {
            is FirmwareStatus.Ready -> "PUP guardado e instalado en el núcleo (${firmwareStatus.source})"
            is FirmwareStatus.Saved -> "PSVUPDAT.PUP guardado, pendiente de instalar en el núcleo"
            FirmwareStatus.Missing -> "Sin PUP guardado"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = cardTextColor(dark),
    )
    // 2. Firmware instalado EN EL NÚCLEO = Ready (vs0 extraído o marcador con versión).
    Text(
        "Firmware instalado en el núcleo: " + when (firmwareStatus) {
            is FirmwareStatus.Ready -> "Sí (${firmwareStatus.source})"
            is FirmwareStatus.Saved -> "No: solo guardado, no instalado"
            FirmwareStatus.Missing -> "No"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (firmwareStatus is FirmwareStatus.Ready) ColTurquoise else cardTextColor(dark),
    )
    // 3. Juego importado = extraído (eboot.bin en ux0/app/) o .pkg guardado.
    Text(
        "Juego importado: " + when {
            status.gameExtracted -> "Extraído (${status.extractedTitleId?.id ?: "—"} · ${DownloadsCleanup.formatBytes(status.gameSizeBytes)})"
            status.pkgTitleId != null -> ".pkg guardado (${status.pkgTitleId.id} · ${DownloadsCleanup.formatBytes(status.pkgSizeBytes)}): guardado, no instalado"
            else -> "No importado"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = cardTextColor(dark),
    )
    // 4. Juego instalado en la app externa: confirmación honesta del usuario.
    Text(
        "Juego instalado en la app externa: " + if (pkgInstalledInVita3k) {
            "Sí, confirmado por ti; el launcher no puede leer la biblioteca de la app externa"
        } else {
            "No confirmado"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = cardTextColor(dark),
    )
    // 5. DLCs: id + tamaño + si es .pkg guardado o carpeta extraída.
    Text(
        "DLCs (${status.dlcCount}):",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = cardTextColor(dark),
    )
    if (status.dlcs.isEmpty()) {
        Text("Sin DLCs.", style = MaterialTheme.typography.bodyMedium, color = mutedTextColor(dark))
    } else {
        status.dlcs.forEach { dlc ->
            Text(
                "• ${dlc.dlcId} — ${DownloadsCleanup.formatBytes(dlc.sizeBytes)}" +
                    if (dlc.isPkg) " (.pkg guardado, no instalado)" else " (extraído)",
                style = MaterialTheme.typography.bodyMedium,
                color = cardTextColor(dark),
            )
        }
    }
    // 6. Actualizaciones.
    Text(
        "Actualización: " + if (status.updateInstalled) "Instalada" else "Sin actualización instalada",
        style = MaterialTheme.typography.bodyMedium,
        color = cardTextColor(dark),
    )
    Spacer(modifier = Modifier.height(4.dp))
    NoteText("Guardado no es instalado: un .pkg guardado o un PUP guardado no cuentan como instalados hasta que el núcleo o la app externa los instala de verdad.")
}
