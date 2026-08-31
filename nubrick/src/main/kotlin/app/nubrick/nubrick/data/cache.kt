package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.getCurrentDate
import java.time.ZonedDateTime
import java.nio.charset.StandardCharsets

private const val CACHE_TIME_SECONDS = 10 * 60L // 10 minutes
private const val STALE_TIME_SECONDS = 1 * 60L  // 1 minute
private const val MAX_CACHE_ENTRY_COUNT = 128
private const val MAX_CACHE_BYTES = 4 * 1024 * 1024

internal class CacheStore(
    maxEntryCount: Int = MAX_CACHE_ENTRY_COUNT,
    maxBytes: Int = MAX_CACHE_BYTES,
) {
    private val lock = Any()
    private val cache = LinkedHashMap<String, CacheObject>()
    private val entryLimit = maxEntryCount.coerceAtLeast(1)
    private val byteBudget = maxBytes.coerceAtLeast(0)
    private var totalBytes = 0

    fun get(key: String): Result<CacheObject> = synchronized(lock) {
        removeExpiredEntries(getCurrentDate())
        val cached = cache[key] ?: return@synchronized Result.failure(NotFoundException())

        Result.success(cached)
    }

    fun set(key: String, value: String): Result<Unit> = synchronized(lock) {
        val now = getCurrentDate()
        removeExpiredEntries(now)
        val byteCount = value.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount > byteBudget) return@synchronized Result.success(Unit)
        cache.remove(key)?.let { totalBytes -= it.byteCount }

        while (cache.size >= entryLimit || totalBytes > byteBudget - byteCount) {
            val oldestKey = cache.minByOrNull { it.value.timestamp }?.key ?: break
            totalBytes -= cache.remove(oldestKey)?.byteCount ?: 0
        }
        val cacheObject = CacheObject(
            data = value,
            timestamp = now,
            byteCount = byteCount,
        )
        cache[key] = cacheObject
        totalBytes += byteCount
        Result.success(Unit)
    }

    fun remove(key: String) = synchronized(lock) {
        cache.remove(key)?.let { totalBytes -= it.byteCount }
    }

    /**
     * Removes [key] only when the stored value is still [expected].
     * Returns true when the entry was removed.
     */
    fun remove(key: String, expected: CacheObject): Boolean = synchronized(lock) {
        if (cache[key] != expected) return@synchronized false
        totalBytes -= expected.byteCount
        cache.remove(key)
        true
    }

    private fun removeExpiredEntries(now: ZonedDateTime) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now.toEpochSecond() - entry.value.timestamp.toEpochSecond() > CACHE_TIME_SECONDS) {
                totalBytes -= entry.value.byteCount
                iterator.remove()
            }
        }
    }
}

internal data class CacheObject(
    val data: String,
    internal val timestamp: ZonedDateTime,
    internal val byteCount: Int = data.toByteArray(StandardCharsets.UTF_8).size,
) {
    fun isStale(): Boolean {
        val now = getCurrentDate()
        val diff = now.toEpochSecond() - timestamp.toEpochSecond()
        return diff > STALE_TIME_SECONDS
    }
}
