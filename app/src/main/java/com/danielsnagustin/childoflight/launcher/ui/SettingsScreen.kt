package com.danielsnagustin.childoflight.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.ui.settings.CategoryRow
import com.danielsnagustin.childoflight.launcher.ui.settings.SearchResultRow
import com.danielsnagustin.childoflight.launcher.ui.settings.SettingsCategory
import com.danielsnagustin.childoflight.launcher.ui.settings.searchSettings

/**
 * Hub de Ajustes (Fase 2, Ola 2, Etapa 1). La antigua pantalla única
 * se dividió en 9 categorías, cada una con su pantalla propia
 * (ui/settings/): nada se perdió, todo tiene su categoría (ver el
 * inventario en el manifiesto de la Ola 2).
 *
 * Buscador: encuentra ajustes por nombre o palabra clave y lleva a su
 * categoría. La consulta sobrevive a rotación (rememberSaveable) y la
 * lista de resultados sustituye a las categorías mientras se escribe.
 * Atrás: categoría -> hub -> Home (ver MainActivity).
 */
@Composable
fun SettingsScreen(vm: LauncherViewModel, onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // R2-P2-21a / R2-P2-28: la búsqueda oculta "Instalar Turnip" cuando
    // la GPU detectada no es Adreno.
    val gpu by vm.gpuFlow.collectAsState(initial = null)
    val results = searchSettings(query, gpuVendor = gpu?.vendor)
    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush()),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
        ) {
            item {
                ScreenHeader("Configuración", "Todo lo del launcher, en un solo lugar.")
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar ajuste…") },
                    placeholder = { Text("p. ej. FSR, audio, 60 fps…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (query.isBlank()) {
                SettingsCategory.entries.forEach { category ->
                    item {
                        CategoryRow(category) { onNavigate(category.screen) }
                    }
                }
            } else if (results.isEmpty()) {
                item {
                    Text(
                        "Sin resultados para «$query». Prueba con otra palabra (p. ej. audio, FSR, firmware).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColMutedText,
                    )
                }
            } else {
                results.forEach { result ->
                    item {
                        SearchResultRow(result) { onNavigate(result.destination ?: result.category.screen) }
                    }
                }
            }
            item {
                BackButton(onBack)
            }
        }
    }
}
