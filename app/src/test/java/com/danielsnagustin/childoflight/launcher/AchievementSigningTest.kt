package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.data.AchievementRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Firma del respaldo de logros (R7-P2-01, 0.2.58): el export va
 * firmado con HMAC-SHA256 y la clave por dispositivo; solo se aceptan
 * respaldos firmados por la app. Funciones puras del companion de
 * AchievementRepository (sin Android: no hay Robolectric en los tests
 * unitarios). La clave real en DataStore y el edit solo se verifican
 * en el teléfono.
 */
class AchievementSigningTest {

    // Clave de prueba (64 hex = 32 bytes), fija para que los tests
    // sean deterministas; la real se genera por dispositivo.
    private val testKey = "0123456789abcdef".repeat(4)
    private val otherKey = "fedcba9876543210".repeat(4)

    @Test
    fun signedRoundTripReturnsPayload() {
        val payload = "1|1000;4|2000;20|3000"
        val signed = AchievementRepository.signExport(payload, testKey)
        assertEquals(payload, AchievementRepository.verifySignedExport(signed, testKey))
    }

    @Test
    fun emptyPayloadRoundTrip() {
        // Exportar con 0 trofeos también va firmado (no es texto vacío).
        val signed = AchievementRepository.signExport("", testKey)
        assertEquals("", AchievementRepository.verifySignedExport(signed, testKey))
    }

    @Test
    fun unsignedTextIsRejected() {
        // Un .txt escrito a mano ("N|epochMillis") no tiene firma.
        assertNull(AchievementRepository.verifySignedExport("4|1700000000000", testKey))
        assertNull(AchievementRepository.verifySignedExport("1|1000;4|2000", testKey))
        assertNull(AchievementRepository.verifySignedExport("", testKey))
    }

    @Test
    fun tamperedPayloadIsRejected() {
        // Cambiar un trofeo del payload rompe el HMAC.
        val signed = AchievementRepository.signExport("4|1000;17|2000", testKey)
        val tampered = signed.replace("4|1000", "5|1000")
        assertNull(AchievementRepository.verifySignedExport(tampered, testKey))
    }

    @Test
    fun tamperedSignatureIsRejected() {
        // Cambiar un carácter de la firma rompe la verificación.
        val signed = AchievementRepository.signExport("4|1000", testKey)
        val last = signed.last()
        val replacement = if (last == '0') '1' else '0'
        val tampered = signed.dropLast(1) + replacement
        assertNull(AchievementRepository.verifySignedExport(tampered, testKey))
    }

    @Test
    fun wrongKeyIsRejected() {
        // Un respaldo firmado con otra clave (otro dispositivo) no vale.
        val signed = AchievementRepository.signExport("4|1000", testKey)
        assertNull(AchievementRepository.verifySignedExport(signed, otherKey))
    }

    @Test
    fun wrongHeaderIsRejected() {
        val signed = AchievementRepository.signExport("4|1000", testKey)
        val wrongHeader = signed.replace("COL-ACH-v1", "COL-ACH-v2")
        assertNull(AchievementRepository.verifySignedExport(wrongHeader, testKey))
        // Un texto con líneas de más (p. ej. salto final añadido) tampoco vale.
        assertNull(AchievementRepository.verifySignedExport("$signed\n", testKey))
    }
}
