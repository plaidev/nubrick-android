package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.getCurrentDate
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

internal const val DEFAULT_CACHE_RETENTION_SECONDS = 24 * 60 * 60L // 1 day
private const val MAX_CACHE_ENTRY_COUNT = 128
private const val MAX_CACHE_BYTES = 4 * 1024 * 1024

internal class CacheStore(
    private val retentionSeconds: Long = DEFAULT_CACHE_RETENTION_SECONDS,
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

    fun set(key: String, value: String): Unit = synchronized(lock) {
        setLocked(key, value)
    }

    /**
     * Replaces [key] only when the stored value is still [expected].
     * Returns false if another request has already updated or removed the entry.
     */
    fun replace(key: String, expected: CacheObject, value: String): Boolean = synchronized(lock) {
        if (cache[key] != expected) return@synchronized false
        setLocked(key, value)
        true
    }

    /** Caller must hold [lock]. */
    private fun setLocked(key: String, value: String) {
        val now = getCurrentDate()
        removeExpiredEntries(now)
        val byteCount = value.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount > byteBudget) return
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
            if (!entry.value.isWithinRetention(retentionSeconds, now)) {
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
    fun isWithinRetention(
        retentionSeconds: Long,
        now: ZonedDateTime = getCurrentDate(),
    ): Boolean {
        if (retentionSeconds <= 0L) return false
        val diff = now.toEpochSecond() - timestamp.toEpochSecond()
        return diff <= retentionSeconds
    }
}
