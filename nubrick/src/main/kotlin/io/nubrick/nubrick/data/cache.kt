package io.nubrick.nubrick.data

import io.nubrick.nubrick.data.user.getCurrentDate
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
            cache.remove(key)
            return Result.failure(NotFoundException())
        }
        return Result.success(cached)
    }

    fun set(key: String, value: String): Result<Unit> {
        val now = getCurrentDate()
        val cacheObject = CacheObject(
            data = value,
            timestamp = now,
        )
        cache[key] = cacheObject
        return Result.success(Unit)
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