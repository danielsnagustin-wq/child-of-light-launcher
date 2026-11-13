package com.danielsnagustin.childoflight.launcher.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formato de fecha/hora compartido por las pantallas (Ronda 0.2.70,
 * Área 6, 6-P2-2): antes cada fila de Logros y cada tarjeta de sesión
 * construía un SimpleDateFormat nuevo en cada recomposición (parseo
 * del patrón + Calendar + Locale por fila). DateTimeFormatter es
 * inmutable y seguro para hilos: se crea una sola vez y se reutiliza.
 * Mismo patrón visible que antes: "dd/MM/yyyy HH:mm" en la zona
 * horaria del teléfono. Si el valor no se puede formatear, "—".
 */
private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.getDefault())

internal fun formatDateTime(millis: Long): String {
    return try {
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
    } catch (_: Exception) {
        "—"
    }
}
