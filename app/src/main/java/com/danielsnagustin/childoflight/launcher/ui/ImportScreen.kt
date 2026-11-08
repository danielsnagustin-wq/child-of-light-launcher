package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.model.AppLanguage

/**
 * Pantalla de Importación (Fase 2, Ola 1): juego principal, DLCs y
 * updates, todo en un solo lugar. Antes vivía en Ajustes; se muda
 * aquí tal cual (mismos pickers, mismas funciones del ViewModel,
 * mismo resultado detallado), y la Home apunta aquí.
 *
 * Estados REALES desde GameStatus (disco): nada ficticio. Cada
 * apartado muestra lo que hay instalado (Title ID, tamaño, lista
 * de DLCs con id + tamaño, update sí/no) y el estado vacío útil
 * cuando no hay nada.
 *
 * Progreso (honesto):
 * - Copia del archivo (sin hook de progreso en copyUriAtomic):
 *   estado indeterminado ("Importando… <nombre>") + resultado.
 *   Nunca se inventa un %.
 * - Instalación en el núcleo (firmware o .pkg al jugar): % real
 *   del núcleo por installProgress (percent + mensaje del núcleo).
 */
@Composable
fun ImportScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val settings by vm.settingsFlow.collectAsState(initial = null)
    val uiPrefs by vm.uiFlow.collectAsState(initial = null)
    val strings = stringsFor(uiPrefs?.language ?: AppLanguage.SPANISH)
    val status by vm.status.collectAsState()
    val importing by vm.importing.collectAsState()
    val installProgress by vm.installProgress.collectAsState()
    val pendingLicense by vm.pendingPkgLicense.collectAsState()

    val pickGame = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importGame(uri)
    }
    val pickDlc = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importDlc(uri)
    }
    val pickUpdate = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUpdate(uri)
    }
    val pickGameFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importGameFolder(uri)
    }
    val pickDlcFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importDlcFolder(uri)
    }
    val pickWorkBin = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importWorkBin(uri)
    }

    val s = settings
    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush()),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
        ) {
            item {
                ScreenHeader(strings.importTitle, strings.importSubtitle)
            }
            // --- Progreso: copia indeterminada, instalación en núcleo con % real ---
            if (importing || installProgress != null) {
                item {
                    PremiumCard {
                        val progress = installProgress
                        if (progress?.percent != null) {
                            Text(
                                "${strings.installingLabel} ${progress.percent}% — ${progress.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = (progress.percent ?: 0) / 100f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            PulsingText(
                                progress?.message ?: strings.importingLabel,
                                ColGold,
                                MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        NoteText("No cierres la app mientras dura. Un juego completo puede tardar varios minutos.")
                    }
                }
            }
            // --- Juego principal ---
            item {
                SectionTitle(strings.mainGame)
                PremiumCard {
                    Text(
                        "Juego y DLCs: solo se aceptan PCSE00480 (US) y PCSB00598 (EU).",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${strings.state}: ${status.installedLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    if (status.gameExtracted) {
                        Text(
                            "Title ID: ${status.extractedTitleId?.id ?: "—"} · Tamaño: ${DownloadsCleanup.formatBytes(status.gameSizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ColMutedText,
                        )
                    } else if (status.pkgInstalled) {
                        Text(
                            "Guardado como .pkg (${status.pkgTitleId?.id ?: "—"} · ${DownloadsCleanup.formatBytes(status.pkgSizeBytes)}): se instala en el motor integrado al pulsar JUGAR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ColMutedText,
                        )
                    } else {
                        Text(
                            strings.pendingImport,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = ColGold,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SecondaryButton("Importar juego (.zip / .vpk / .pkg)", onClick = { pickGame.launch("*/*") }, enabled = !importing)
                    NoteText("Elige el paquete COMPLETO del juego: .zip/.vpk con eboot.bin dentro, o .pkg. El nombre del archivo da igual: el juego se valida por su contenido. Un eboot.bin suelto no basta.")
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton("Importar carpeta del juego", onClick = { pickGameFolder.launch(null) }, enabled = !importing)
                    NoteText("Elige la carpeta PCSE00480 o PCSB00598 que contiene eboot.bin dentro.")
                }
            }
            // --- DLC ---
            item {
                SectionTitle(strings.dlcSection)
                PremiumCard {
                    if (status.dlcs.isEmpty()) {
                        Text(
                            strings.noDlcsYet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColMutedText,
                        )
                    } else {
                        status.dlcs.forEach { dlc ->
                            Text(
                                "• ${dlc.dlcId} — ${DownloadsCleanup.formatBytes(dlc.sizeBytes)}" + if (dlc.isPkg) " (.pkg)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SecondaryButton("Importar DLC (.zip / .pkg)", onClick = { pickDlc.launch("*/*") }, enabled = !importing)
                    NoteText("Elige el .zip del DLC (con la ruta addcont/ dentro) o el .pkg del DLC.")
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton("Importar carpeta de DLC", onClick = { pickDlcFolder.launch(null) }, enabled = !importing)
                    NoteText("Elige la carpeta del juego que contiene addcont/ dentro.")
                }
            }
            // --- Actualizaciones ---
            item {
                SectionTitle(strings.updatesSection)
                PremiumCard {
                    Text(
                        "${strings.update}: ${if (status.updateInstalled) strings.installed else strings.noUpdateYet}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SecondaryButton("Importar update (.zip / .vpk / .pkg)", onClick = { pickUpdate.launch("*/*") }, enabled = !importing)
                    NoteText("Elige el .zip, .vpk o .pkg del update.")
                }
            }
            // --- .pkg: licencia pendiente y entrega a la app externa (mudado desde Ajustes, tal cual) ---
            item {
                pendingLicense?.let { pending ->
                    SectionTitle("Falta la licencia")
                    PremiumCard {
                        Text(
                            "Falta el work.bin del ${pending.kind.label} (${pending.titleId}).",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        NoteText("Sin la licencia no se puede instalar el .pkg. Es el archivo work.bin de 512 B que acompaña al .pkg (a veces se descarga como «work (5).bin»).")
                        Spacer(modifier = Modifier.height(12.dp))
                        SecondaryButton("Elegir work.bin", onClick = { pickWorkBin.launch("*/*") }, enabled = !importing)
                    }
                }
                if (status.pkgInstalled) {
                    SectionTitle("Juego en .pkg")
                    PremiumCard {
                        Text(
                            "Juego (.pkg): ${status.pkgTitleId?.id ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text("Content ID: ${status.pkgContentId ?: "—"}", style = MaterialTheme.typography.bodySmall, color = ColMutedText)
                        Text(
                            "Licencia (work.bin): ${if (status.pkgLicensePresent) "guardada ✓" else "falta ✗"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status.pkgLicensePresent) ColSuccessGreen else Color.White,
                        )
                        if (s?.pkgDeliveredToVita3k == true) {
                            Text("✓ Copiado a Descargas/ChildOfLight/", style = MaterialTheme.typography.bodyMedium, color = ColSuccessGreen)
                        }
                        if (s?.pkgInstalledInVita3k == true) {
                            Text("✓ Instalado en la app externa (confirmado por ti)", style = MaterialTheme.typography.bodyMedium, color = ColSuccessGreen)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        SecondaryButton("Instalar en la app externa", onClick = { vm.deliverPkgToVita3k() }, enabled = !importing && status.pkgLicensePresent)
                        if (!status.pkgLicensePresent) {
                            NoteText("Falta el work.bin: elige la licencia arriba para poder instalarlo.")
                        }
                        if (s?.pkgDeliveredToVita3k == true && s?.pkgInstalledInVita3k != true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SecondaryButton("Ya lo instalé en la app externa", onClick = { vm.confirmPkgInstalledInVita3k() })
                        }
                        NoteText("El launcher no puede instalar un .pkg por sí mismo: lo instala la app externa. «Instalar en la app externa» te pide confirmar antes de copiar el .pkg y su licencia a Descargas/ChildOfLight/ (la app externa no puede leer el almacenamiento interno del launcher) y abre la app externa con la guía. Cuando el núcleo embebido esté listo, no hará falta copiar nada.")
                    }
                }
            }
            item {
                BackButton(onBack)
            }
        }
    }
}
