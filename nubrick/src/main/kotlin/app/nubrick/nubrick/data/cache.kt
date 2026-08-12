package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.getCurrentDate
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private const val CACHE_TIME_SECONDS = 10 * 60L // 10 minutes
private const val STALE_TIME_SECONDS = 1 * 60L  // 1 minute

internal class CacheStore {
    private val cache = ConcurrentHashMap<String, CacheObject>()

    fun get(key: String): Result<CacheObject> {
        val cached = cache[key] ?: return Result.failure(NotFoundException())

        val now = getCurrentDate()
        val diff = now.toEpochSecond() - cached.timestamp.toEpochSecond()
        if (diff > CACHE_TIME_SECONDS) {
            // Only remove the entry we observed to avoid wiping a concurrent set().
            cache.remove(key, cached)
            return Result.failure(NotFoundException())
        }
        return Result.success(cached)
    }

    /** Returns the entry without applying TTL expiry/removal. */
    fun getIfPresent(key: String): CacheObject? = cache[key]

    fun set(key: String, value: String): Result<Unit> {
        val now = getCurrentDate()
        val cacheObject = CacheObject(
            data = value,
            timestamp = now,
        )
        cache[key] = cacheObject
        return Result.success(Unit)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    /**
     * Removes [key] only when the stored value is still [expected].
     * Returns true when the entry was removed.
     */
    fun remove(key: String, expected: CacheObject): Boolean {
        return cache.remove(key, expected)
    }

    fun removeByPrefix(prefix: String) {
        cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }
}

internal data class CacheObject(
    val data: String,
    internal val timestamp: ZonedDateTime,
) {
    fun isStale(): Boolean {
        val now = getCurrentDate()
        val diff = now.toEpochSecond() - timestamp.toEpochSecond()
        return diff > STALE_TIME_SECONDS
    }
}
