package com.danielsnagustin.childoflight.launcher.data

import android.app.ActivityManager
import android.content.Context

/**
 * Memoria física del teléfono (auditoría RAM ≤ 4 GB, 2026-11-07).
 *
 * El teléfono del usuario tiene 4 GB y el juego ya va muy bien en la
 * app externa: el launcher no debe pedir más que eso. La detección
 * existe para aplicar el perfil conservador por defecto en el primer
 * arranque (ver applyLowRamDefaults en LauncherSettings) sin tocar
 * los ajustes que el usuario ya personalizó.
 *
 * OJO: ActivityManager.isLowRamDevice() por sí solo NO detecta un
 * teléfono de 4 GB: ese flag es para dispositivos de gama baja
 * (~1 GB o menos, ro.config.low_ram). Por eso el criterio real es
 * la RAM total (MemoryInfo.totalMem) ≤ 4 GiB; el flag solo se usa
 * como vía adicional (si el sistema ya se declara low-RAM, también
 * se aplica el perfil conservador aunque totalMem no se pueda leer).
 *
 * Sin verificar en dispositivo: los valores de totalMem en este
 * teléfono concreto y el comportamiento real del perfil solo se
 * pueden confirmar en el teléfono (CI no tiene ActivityManager).
 */
object DeviceMemory {

    /** Umbral de "RAM baja": 4 GiB exactos (4 * 1024^3 bytes). */
    const val LOW_RAM_THRESHOLD_BYTES: Long = 4L * 1024L * 1024L * 1024L

    /** RAM total del teléfono en bytes, o null si no se pudo leer. */
    fun totalRamBytes(context: Context): Long? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** true si el teléfono tiene ≤ 4 GB de RAM total (o el sistema se declara low-RAM). */
    fun isLowRamDevice(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.isLowRamDevice) return true
            val total = totalRamBytes(context) ?: return false
            total <= LOW_RAM_THRESHOLD_BYTES
        } catch (_: Exception) {
            false
        }
    }
}
