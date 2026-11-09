package com.danielsnagustin.childoflight.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielsnagustin.childoflight.launcher.ui.BackButton
import com.danielsnagustin.childoflight.launcher.ui.ContentMaxWidth
import com.danielsnagustin.childoflight.launcher.ui.NoteText
import com.danielsnagustin.childoflight.launcher.ui.PremiumCard
import com.danielsnagustin.childoflight.launcher.ui.ScreenHeader
import com.danielsnagustin.childoflight.launcher.ui.cardTextColor
import com.danielsnagustin.childoflight.launcher.ui.isDarkUi
import com.danielsnagustin.childoflight.launcher.ui.mutedTextColor
import com.danielsnagustin.childoflight.launcher.ui.premiumBackgroundBrush
import com.danielsnagustin.childoflight.launcher.ui.rememberUiPreferences

/**
 * Armazón común de las 9 pantallas de categoría de Ajustes (Ola 2).
 * Mismo fondo, mismo ancho máximo y mismo botón Volver que la antigua
 * pantalla única: las categorías se sienten parte de Ajustes, no
 * pantallas sueltas. Los diálogos van en el hueco `dialogs` para que
 * no queden dentro de la lista con scroll.
 *
 * Ola 3, Etapa 9b: `onResetCategory` añade el botón "Restablecer esta
 * categoría" (con confirmación) al final de la pantalla. Restablece
 * SOLO esa categoría (ver CategoryReset.kt). Almacenamiento no
 * tiene ajustes que restablecer y no pasa el botón. Las pantallas
 * que no lo pasan no muestran el botón.
 */
@Composable
fun SettingsCategoryScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    dialogs: @Composable () -> Unit = {},
    onResetCategory: (() -> Unit)? = null,
    // R2-P2-26: etiqueta propia para el botón de restablecer, cuando
    // la pantalla tiene otro reset con ámbito distinto (ver
    // Personalización): por defecto, "Restablecer esta categoría".
    resetLabel: String? = null,
    content: LazyListScope.() -> Unit,
) {
    var showResetCategoryConfirm by rememberSaveable { mutableStateOf(false) }
    // R4-P1-14: fondo y textos según el tema. En claro, el fondo es el
    // tinte claro del fondo elegido (premiumBackgroundBrush con tema);
    // antes era siempre el AMOLED oscuro con textos blancos fijos.
    val uiPrefs = rememberUiPreferences()
    val dark = isDarkUi(uiPrefs)
    Box(
        modifier = Modifier.fillMaxSize().background(premiumBackgroundBrush(dark, uiPrefs?.homeBackground?.hex)),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
        ) {
            item {
                ScreenHeader(title, subtitle)
            }
            if (!dark) {
                item {
                    NoteText("Tema claro: el fondo elegido se aplica como tinte claro.")
                }
            }
            content()
            if (onResetCategory != null) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { showResetCategoryConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(resetLabel ?: "Restablecer esta categoría") }
                }
            }
            item {
                BackButton(onBack)
            }
        }
    }
    if (showResetCategoryConfirm && onResetCategory != null) {
        AlertDialog(
            onDismissRequest = { showResetCategoryConfirm = false },
            title = { Text("¿Restablecer esta categoría?") },
            text = { Text("Se restablecen solo los ajustes de esta categoría a los valores del preset del perfil activo (o a los defaults en Personalización). El resto de ajustes no se toca.") },
            confirmButton = {
                TextButton(onClick = { onResetCategory(); showResetCategoryConfirm = false }) { Text("Restablecer") }
            },
            dismissButton = { TextButton(onClick = { showResetCategoryConfirm = false }) { Text("Cancelar") } },
        )
    }
    dialogs()
}

/** Fila de categoría en el hub: tarjeta con título + subtítulo, toca para entrar. R4-P1-14: colores de texto según el tema. */
@Composable
fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    val dark = isDarkUi(rememberUiPreferences())
    PremiumCard(onClick = onClick) {
        Text(
            category.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = cardTextColor(dark),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            category.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor(dark),
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
}

/** Fila de resultado del buscador: título del ajuste + categoría a la que lleva (sin "En:" cuando el destino no es una categoría de ajustes, como Logros, que vive en el Inicio). R4-P1-14: colores de texto según el tema. */
@Composable
fun SearchResultRow(result: SearchableSetting, onClick: () -> Unit) {
    val dark = isDarkUi(rememberUiPreferences())
    PremiumCard(onClick = onClick) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                result.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = cardTextColor(dark),
            )
            if (result.destination == null) {
                Text(
                    "En: ${result.category.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor(dark),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
}
