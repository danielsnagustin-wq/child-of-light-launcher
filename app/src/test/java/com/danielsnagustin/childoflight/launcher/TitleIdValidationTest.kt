package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.model.TitleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleIdValidationTest {

    @Test
    fun acceptsBothChildOfLightTitleIds() {
        assertEquals(TitleId.US, TitleId.fromString("PCSE00480"))
        assertEquals(TitleId.EU, TitleId.fromString("PCSB00598"))
        assertTrue(TitleId.isSupported("PCSE00480"))
        assertTrue(TitleId.isSupported("PCSB00598"))
    }

    @Test
    fun acceptsLowercaseAndWhitespace() {
        assertEquals(TitleId.US, TitleId.fromString(" pcse00480 "))
        assertEquals(TitleId.EU, TitleId.fromString("pcsb00598"))
    }

    @Test
    fun rejectsOtherGames() {
        assertNull(TitleId.fromString("PCSF00576")) // Borderlands 2, no es Child of Light
        assertNull(TitleId.fromString("PCSA00152"))
        assertNull(TitleId.fromString(""))
        assertNull(TitleId.fromString(null))
        assertFalse(TitleId.isSupported("PCSE00481"))
    }

    @Test
    fun supportedSetHasExactlyTwoIds() {
        assertEquals(setOf("PCSE00480", "PCSB00598"), TitleId.SUPPORTED)
    }
}
