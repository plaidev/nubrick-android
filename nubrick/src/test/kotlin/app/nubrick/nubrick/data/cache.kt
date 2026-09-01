package app.nubrick.nubrick.data

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
        app.nubrick.nubrick.data.user.DATETIME_OFFSET = 0
        cacheStore = CacheStore()
    }

    @Test
    fun `test basic set and get operations`() {
        val key = "test-key"
        val value = "test-value"

        val setResult = cacheStore.set(key, value)
        val getResult = cacheStore.get(key)

        assertTrue(setResult.isSuccess)
        assertTrue(getResult.isSuccess)
        assertEquals(value, getResult.getOrNull()?.data)
    }

    @Test
    fun `test get non-existent key returns failure`() {
        val result = cacheStore.get("non-existent-key")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotFoundException)
    }

    @Test
    fun `entry within retention is served`() {
        val now = ZonedDateTime.now()
        val cacheObject = CacheObject(
            data = "test-data",
            timestamp = now.minusSeconds(30),
        )

        assertTrue(cacheObject.isWithinRetention(DEFAULT_CACHE_RETENTION_SECONDS))
    }

    @Test
    fun `entry older than retention is not served`() {
        val now = ZonedDateTime.now()
        val cacheObject = CacheObject(
            data = "test-data",
            timestamp = now.minusSeconds(DEFAULT_CACHE_RETENTION_SECONDS + 1),
        )

        assertFalse(cacheObject.isWithinRetention(DEFAULT_CACHE_RETENTION_SECONDS))
    }

    @Test
    fun `zero retention never serves cached entries`() {
        val cacheObject = CacheObject(
            data = "test-data",
            timestamp = ZonedDateTime.now(),
        )

        assertFalse(cacheObject.isWithinRetention(0))
    }

    @Test
    fun `expired cache returns failure`() {
        val key = "test-key"
        val value = "test-value"
        cacheStore = CacheStore()

        val setResult = cacheStore.set(key, value)
        assertTrue("Set operation should succeed", setResult.isSuccess)

        app.nubrick.nubrick.data.user.DATETIME_OFFSET = (DEFAULT_CACHE_RETENTION_SECONDS + 1) * 1000

        val getResult = cacheStore.get(key)

        assertTrue("Cache should be expired", getResult.isFailure)
        assertTrue("Should throw NotFoundException", getResult.exceptionOrNull() is NotFoundException)
    }

    @Test
    fun `custom retention cap expires earlier`() {
        cacheStore = CacheStore(retentionSeconds = 30)
        cacheStore.set("k", "v")

        app.nubrick.nubrick.data.user.DATETIME_OFFSET = 31_000

        assertTrue(cacheStore.get("k").isFailure)
    }

    @Test
    fun `custom retention cap still serves within window`() {
        cacheStore = CacheStore(retentionSeconds = 30)
        cacheStore.set("k", "v")

        app.nubrick.nubrick.data.user.DATETIME_OFFSET = 29_000

        assertEquals("v", cacheStore.get("k").getOrNull()?.data)
    }

    @Test
    fun `conditional remove does not wipe a newer entry`() {
        val key = "test-key"
        cacheStore.set(key, "old")
        val staleSnapshot = cacheStore.get(key).getOrThrow()

        cacheStore.set(key, "new")
        assertFalse(cacheStore.remove(key, staleSnapshot))
        assertEquals("new", cacheStore.get(key).getOrThrow().data)
    }

    @Test
    fun `cache evicts the oldest entry when its byte budget is exceeded`() {
        cacheStore = CacheStore(maxBytes = 3)
        cacheStore.set("first", "abc")
        cacheStore.set("second", "d")

        assertTrue(cacheStore.get("first").isFailure)
        assertEquals("d", cacheStore.get("second").getOrNull()?.data)
    }

    @Test
    fun `cache keeps an existing entry when its replacement exceeds the byte budget`() {
        cacheStore = CacheStore(maxBytes = 3)
        cacheStore.set("key", "old")
        cacheStore.set("key", "oversized")

        assertEquals("old", cacheStore.get("key").getOrNull()?.data)
    }
}
