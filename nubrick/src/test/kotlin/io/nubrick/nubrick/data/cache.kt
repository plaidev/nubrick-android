package io.nubrick.nubrick.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZonedDateTime

class CacheStoreTest {
    private lateinit var cacheStore: CacheStore

    @Before
    fun setup() {
        // Override getCurrentDate for testing
        io.nubrick.nubrick.data.user.DATETIME_OFFSET = 0
        cacheStore = CacheStore()
    }

    @Test
    fun `test basic set and get operations`() {
        // Given
        val key = "test-key"
        val value = "test-value"

        // When
        val setResult = cacheStore.set(key, value)
        val getResult = cacheStore.get(key)

        // Then
        assertTrue(setResult.isSuccess)
        assertTrue(getResult.isSuccess)
        assertEquals(value, getResult.getOrNull()?.data)
    }

    @Test
    fun `test get non-existent key returns failure`() {
        // When
        val result = cacheStore.get("non-existent-key")

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotFoundException)
    }

    @Test
    fun `test cache object staleness`() {
        // Given - stale time is 1 minute (60s), so 90s ago is stale
        val now = ZonedDateTime.now()
        val staleTimestamp = now.minusSeconds(90)
        val cacheObject = CacheObject(
            data = "test-data",
            timestamp = staleTimestamp,
        )

        // Then
        assertTrue(cacheObject.isStale())
    }

    @Test
    fun `test cache object freshness`() {
        // Given - stale time is 1 minute (60s), so 30s ago is fresh
        val now = ZonedDateTime.now()
        val freshTimestamp = now.minusSeconds(30)
        val cacheObject = CacheObject(
            data = "test-data",
            timestamp = freshTimestamp,
        )

        // Then
        assertFalse(cacheObject.isStale())
    }

    @Test
    fun `test expired cache returns failure`() {
        // Given - cache time is 10 minutes (600s)
        val key = "test-key"
        val value = "test-value"
        cacheStore = CacheStore()

        // When
        val setResult = cacheStore.set(key, value)
        assertTrue("Set operation should succeed", setResult.isSuccess)

        // Simulate time passing beyond cache time (10 minutes = 600000ms)
        io.nubrick.nubrick.data.user.DATETIME_OFFSET = 601000

        val getResult = cacheStore.get(key)

        // Then
        assertTrue("Cache should be expired", getResult.isFailure)
        assertTrue("Should throw NotFoundException", getResult.exceptionOrNull() is NotFoundException)
    }
}