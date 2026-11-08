package com.danielsnagustin.childoflight.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.data.DownloadsCleanup
import com.danielsnagustin.childoflight.launcher.data.FirmwareResolver
import com.danielsnagustin.childoflight.launcher.data.FirmwareStatus
import com.danielsnagustin.childoflight.launcher.model.AppLanguage

/**
 * Pantalla de Importación: juego principal, DLCs, updates y
 * firmware, todo en un solo lugar.
 *
 * Diseño (2026-11-08, petición del usuario: "no se entiende con
 * tanto en esta parte"): cada tarjeta muestra SOLO su estado
 * (✓ Guardado / ✓ Instalado / Ausente) + un dato corto + UNA
 * acción como mucho. Nada de párrafos explicativos: lo ya subido
 * no vuelve a pedir subir, solo queda como guardado con un
 * "Reemplazar" pequeño. Los botones grandes de Importar solo
 * aparecen cuando el elemento falta.
 *
 * Estados REALES desde GameStatus (disco): nada ficticio. La
 * licencia pendiente (work.bin) y la copia a la app externa viven
 * aquí solo cuando hacen falta: la copia externa es una opción
 * avanzada y solo se muestra si el núcleo embebido NO está
 * presente (con núcleo, el .pkg se instala solo al pulsar JUGAR).
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
    // Firmware: mismo flujo del ViewModel.
    val firmwareStatus by vm.firmwareStatusFlow.collectAsState()
    val firmwareInstalling by vm.firmwareInstallingFlow.collectAsState()
    val embeddedCore by vm.embeddedCoreFlow.collectAsState()
    var showDeleteFirmwareConfirm by rememberSaveable { mutableStateOf(false) }
    val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importFirmware(uri)
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
                                progress = { (progress.percent ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            PulsingText(
                                progress?.message ?: strings.importingLabel,
                                ColAccent,
                                MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No cierres la app mientras dura.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ColMutedText,
                        )
                    }
                }
            }
            // --- Juego principal: estado + un dato + una acción ---
            item {
                SectionTitle(strings.mainGame)
                PremiumCard {
                    if (status.gameExtracted) {
                        Text(
                            "✓ ${strings.installed} · ${status.extractedTitleId?.id ?: status.titleId?.id ?: "—"} · ${DownloadsCleanup.formatBytes(status.gameSizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        if (status.pkgInstalled) {
                            Text(
                                "Licencia (work.bin): ${if (status.pkgLicensePresent) "guardada ✓" else "falta ✗"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (status.pkgLicensePresent) ColTurquoise else Color.White,
                            )
                        }
                        TextButton(onClick = { pickGame.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                    } else if (status.pkgInstalled) {
                        Text(
                            "✓ Guardado · ${status.pkgTitleId?.id ?: "—"} · ${DownloadsCleanup.formatBytes(status.pkgSizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            "Se instala solo al pulsar JUGAR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ColMutedText,
                        )
                        Text(
                            "Licencia (work.bin): ${if (status.pkgLicensePresent) "guardada ✓" else "falta ✗"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status.pkgLicensePresent) ColTurquoise else Color.White,
                        )
                        TextButton(onClick = { pickGame.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                    } else {
                        Text(
                            strings.absent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Importar juego (.zip / .vpk / .pkg)", onClick = { pickGame.launch("*/*") }, enabled = !importing)
                        TextButton(onClick = { pickGameFolder.launch(null) }, enabled = !importing) { Text("o importar carpeta") }
                    }
                    // Copia a la app externa: solo opción avanzada y
                    // solo si el núcleo NO está presente. Con núcleo,
                    // el .pkg se instala solo al pulsar JUGAR.
                    if (status.pkgInstalled && embeddedCore != true) {
                        if (s?.pkgDeliveredToVita3k == true) {
                            Text("✓ Copiado a Descargas/ChildOfLight/", style = MaterialTheme.typography.bodyMedium, color = ColTurquoise)
                        }
                        if (s?.pkgInstalledInVita3k == true) {
                            Text("✓ Instalado en la app externa", style = MaterialTheme.typography.bodyMedium, color = ColTurquoise)
                        }
                        TextButton(onClick = { vm.deliverPkgToVita3k() }, enabled = !importing && status.pkgLicensePresent) { Text("Copiar a la app externa") }
                        if (s?.pkgDeliveredToVita3k == true && s?.pkgInstalledInVita3k != true) {
                            TextButton(onClick = { vm.confirmPkgInstalledInVita3k() }) { Text("Ya lo instalé en la app externa") }
                        }
                    }
                }
            }
            // --- DLC ---
            item {
                SectionTitle(strings.dlcSection)
                PremiumCard {
                    if (status.dlcs.isEmpty()) {
                        Text(
                            strings.absent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Importar DLC (.zip / .pkg)", onClick = { pickDlc.launch("*/*") }, enabled = !importing)
                        TextButton(onClick = { pickDlcFolder.launch(null) }, enabled = !importing) { Text("o importar carpeta") }
                    } else {
                        status.dlcs.forEach { dlc ->
                            Text(
                                "✓ ${dlc.dlcId} · ${DownloadsCleanup.formatBytes(dlc.sizeBytes)}" + if (dlc.isPkg) " (.pkg)" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                        TextButton(onClick = { pickDlc.launch("*/*") }, enabled = !importing) { Text("Añadir otro DLC") }
                    }
                }
            }
            // --- Actualizaciones ---
            item {
                SectionTitle(strings.updatesSection)
                PremiumCard {
                    if (status.updateInstalled) {
                        Text(
                            "✓ ${strings.installed}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            strings.absent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Importar update (.zip / .vpk / .pkg)", onClick = { pickUpdate.launch("*/*") }, enabled = !importing)
                    }
                }
            }
            // --- Firmware ---
            item {
                SectionTitle("Firmware")
                PremiumCard {
                    when (firmwareStatus) {
                        is FirmwareStatus.Ready -> {
                            Text(
                                "✓ ${FirmwareResolver.label(firmwareStatus)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { pickFirmware.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                                TextButton(onClick = { showDeleteFirmwareConfirm = true }, enabled = !importing) { Text("Borrar firmware") }
                            }
                        }
                        is FirmwareStatus.Saved -> {
                            Text(
                                "✓ ${FirmwareResolver.label(firmwareStatus)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            if (firmwareInstalling) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "Instalando firmware…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ColMutedText,
                                )
                            } else if (embeddedCore == true) {
                                Spacer(modifier = Modifier.height(8.dp))
                                SecondaryButton("Instalar en el núcleo embebido", onClick = { vm.installSavedFirmwareInCore() }, enabled = !importing)
                                TextButton(onClick = { pickFirmware.launch("*/*") }, enabled = !importing) { Text("Reemplazar") }
                            } else {
                                Text(
                                    "La app externa usa su propio firmware.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ColMutedText,
                                )
                            }
                        }
                        FirmwareStatus.Missing -> {
                            Text(
                                strings.absent,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                            Text(
                                "Tu copia del firmware, solo en tu teléfono.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ColMutedText,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SecondaryButton("Importar firmware (.PUP, tu copia)", onClick = { pickFirmware.launch("*/*") }, enabled = !importing)
                        }
                    }
                }
            }
            // --- Falta la licencia del .pkg ---
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
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton("Elegir work.bin", onClick = { pickWorkBin.launch("*/*") }, enabled = !importing)
                    }
                }
            }
            item {
                BackButton(onBack)
            }
        }
        if (showDeleteFirmwareConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteFirmwareConfirm = false },
                title = { Text("¿Borrar el firmware?") },
                text = { Text("Se borra el PUP guardado y el firmware instalado en el núcleo. El juego, los DLCs y los saves no se tocan. Para jugar con el núcleo embebido habrá que importar el PUP de nuevo.") },
                confirmButton = {
                    TextButton(onClick = { vm.deleteFirmware(); showDeleteFirmwareConfirm = false }) { Text("Borrar") }
                },
                dismissButton = { TextButton(onClick = { showDeleteFirmwareConfirm = false }) { Text("Cancelar") } },
            )
        }
    }
}
