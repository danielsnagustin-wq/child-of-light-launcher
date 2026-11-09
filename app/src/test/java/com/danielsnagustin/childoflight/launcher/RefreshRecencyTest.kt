package com.danielsnagustin.childoflight.launcher

import com.danielsnagustin.childoflight.launcher.ui.EXTERNAL_INFO_CACHE_TTL_MILLIS
import com.danielsnagustin.childoflight.launcher.ui.REFRESH_RECENCY_MILLIS
import com.danielsnagustin.childoflight.launcher.ui.isExternalInfoCacheFresh
import com.danielsnagustin.childoflight.launcher.ui.shouldSkipRecentRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-P1-9 + R4-P1-12: las reglas de recencia y de caché son puras
 * (shouldSkipRecentRefresh, isExternalInfoCacheFresh) para poder
 * probarlas en JVM: el ViewModel las aplica en refreshStatus() y en
 * refreshStatusOnce().
 */
class RefreshRecencyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun recencyWindowIsTwoSeconds() {
        assertEquals(2000L, REFRESH_RECENCY_MILLIS)
    }

    @Test
    fun skipWhenLastScanCompletedJustNow() {
        assertTrue(shouldSkipRecentRefresh(now, now, false))
        assertTrue(shouldSkipRecentRefresh(now - 1500, now, false))
        assertTrue(shouldSkipRecentRefresh(now - REFRESH_RECENCY_MILLIS + 1, now, false))
    }

    @Test
    fun noSkipWhenLastScanCompletedTwoSecondsAgoOrMore() {
        assertFalse(shouldSkipRecentRefresh(now - REFRESH_RECENCY_MILLIS, now, false))
        assertFalse(shouldSkipRecentRefresh(now - 5000, now, false))
        assertFalse(shouldSkipRecentRefresh(now - 60 * 60 * 1000, now, false))
    }

    @Test
    fun noSkipWhenRefreshPendingEvenIfRecent() {
        // Un pendiente marcado durante un escaneo puede ser la única
        // señal de que el disco cambió a mitad de ese escaneo (por
        // ejemplo, una importación que terminó mientras se escaneaba):
        // su re-escaneo siempre corre.
        assertFalse(shouldSkipRecentRefresh(now - 100, now, true))
        assertFalse(shouldSkipRecentRefresh(now, now, true))
    }

    @Test
    fun noSkipWhenNoScanEverCompleted() {
        assertFalse(shouldSkipRecentRefresh(0L, now, false))
    }

    @Test
    fun noSkipWhenClockGoesBackwards() {
        assertFalse(shouldSkipRecentRefresh(now + 1000, now, false))
    }

    @Test
    fun externalInfoCacheTtlIsFiveMinutes() {
        assertEquals(5L * 60 * 1000, EXTERNAL_INFO_CACHE_TTL_MILLIS)
    }

    @Test
    fun externalInfoCacheFreshWithinTtl() {
        assertTrue(isExternalInfoCacheFresh(now, now))
        assertTrue(isExternalInfoCacheFresh(now - 60 * 1000, now))
        assertTrue(isExternalInfoCacheFresh(now - 4 * 60 * 1000, now))
    }

    @Test
    fun externalInfoCacheStaleAfterTtl() {
        assertFalse(isExternalInfoCacheFresh(now - EXTERNAL_INFO_CACHE_TTL_MILLIS, now))
        assertFalse(isExternalInfoCacheFresh(now - 10 * 60 * 1000, now))
        assertFalse(isExternalInfoCacheFresh(now - 60 * 60 * 1000, now))
    }

    @Test
    fun externalInfoCacheNeverReadIsNotFresh() {
        assertFalse(isExternalInfoCacheFresh(0L, now))
    }

    @Test
    fun externalInfoCacheFutureTimestampIsNotFresh() {
        assertFalse(isExternalInfoCacheFresh(now + 1000, now))
    }
}
