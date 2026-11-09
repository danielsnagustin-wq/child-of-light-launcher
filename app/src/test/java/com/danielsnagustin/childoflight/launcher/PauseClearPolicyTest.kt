package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.core.PauseClearOutcome
import com.danielsnagustin.childoflight.launcher.core.decidePauseClearAttempt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Política de reintento del clear de pausa de fondo (R4-P0-4).
 * La verificación (isAppPaused) manda sobre el Boolean del set:
 * el objetivo es "el juego ya no está pausado", no "el set devolvió
 * true".
 */
class PauseClearPolicyTest {

    @Test
    fun clearAcceptedAndUnpausedIsCleared() {
        assertEquals(
            PauseClearOutcome.CLEARED,
            decidePauseClearAttempt(setOk = true, stillPaused = false, attempt = 1, maxAttempts = 3),
        )
    }

    @Test
    fun alreadyUnpausedIsClearedEvenIfSetWasRejected() {
        assertEquals(
            PauseClearOutcome.CLEARED,
            decidePauseClearAttempt(setOk = false, stillPaused = false, attempt = 1, maxAttempts = 3),
        )
    }

    @Test
    fun clearAcceptedButStillPausedIsOtherReason() {
        assertEquals(
            PauseClearOutcome.STILL_PAUSED_OTHER_REASON,
            decidePauseClearAttempt(setOk = true, stillPaused = true, attempt = 1, maxAttempts = 3),
        )
    }

    @Test
    fun clearRejectedRetriesBeforeLastAttempt() {
        assertEquals(
            PauseClearOutcome.CLEAR_REJECTED_RETRY,
            decidePauseClearAttempt(setOk = false, stillPaused = true, attempt = 1, maxAttempts = 3),
        )
        assertEquals(
            PauseClearOutcome.CLEAR_REJECTED_RETRY,
            decidePauseClearAttempt(setOk = false, stillPaused = true, attempt = 2, maxAttempts = 3),
        )
    }

    @Test
    fun clearRejectedOnLastAttemptIsFailed() {
        assertEquals(
            PauseClearOutcome.CLEAR_REJECTED_FAILED,
            decidePauseClearAttempt(setOk = false, stillPaused = true, attempt = 3, maxAttempts = 3),
        )
    }
}
