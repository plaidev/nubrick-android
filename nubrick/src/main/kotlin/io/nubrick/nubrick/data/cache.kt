package io.nubrick.nubrick.data

import io.nubrick.nubrick.data.user.getCurrentDate
import java.time.ZonedDateTime
import java.util.concurrent.locks.ReentrantReadWriteLock

private const val CACHE_TIME_SECONDS = 10 * 60L // 10 minutes
private const val STALE_TIME_SECONDS = 1 * 60L  // 1 minute

internal class CacheStore {
    private val lock = ReentrantReadWriteLock()
    private val cache = mutableMapOf<String, CacheObject>()

    fun get(key: String): Result<CacheObject> {
        lock.readLock().lock()
        try {
            val cached = cache[key] ?: return Result.failure(NotFoundException())

            val now = getCurrentDate()
            val diff = now.toEpochSecond() - cached.timestamp.toEpochSecond()
            if (diff > CACHE_TIME_SECONDS) {
                cache.remove(key)
                return Result.failure(NotFoundException())
            }
            return Result.success(cached)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun set(key: String, value: String): Result<Unit> {
        lock.writeLock().lock()
        try {
            val now = getCurrentDate()
            val cacheObject = CacheObject(
                data = value,
                timestamp = now,
            )
            this.cache[key] = cacheObject
            return Result.success(Unit)
        } finally {
            lock.writeLock().unlock()
        }
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