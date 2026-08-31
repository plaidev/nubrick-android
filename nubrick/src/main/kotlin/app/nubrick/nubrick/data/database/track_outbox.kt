package app.nubrick.nubrick.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.provider.BaseColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PendingTrackEvent(
    val eventId: String,
    val payload: String,
    val eventType: String,
    val byteCount: Long,
    val userId: String,
    val meta: String,
)

internal data class TrackOutboxLimits(
    val maxEventCount: Int = 5_000,
    val maxQueueBytes: Int = 10 * 1024 * 1024,
    val maxEventBytes: Int = 500 * 1024,
)

private object TrackOutboxTable {
    const val Name = "track_outbox"

    object Columns : BaseColumns {
        const val EventId = "event_id"
        const val Payload = "payload"
        const val EventType = "event_type"
        const val ByteCount = "byte_count"
        const val CreatedAt = "created_at"
        const val UserId = "user_id"
        const val Meta = "meta"
    }
}

internal const val SQL_CREATE_TRACK_OUTBOX_TABLE = """
    CREATE TABLE IF NOT EXISTS ${TrackOutboxTable.Name} (
        ${BaseColumns._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
        ${TrackOutboxTable.Columns.EventId} TEXT NOT NULL UNIQUE,
        ${TrackOutboxTable.Columns.Payload} TEXT NOT NULL,
        ${TrackOutboxTable.Columns.EventType} TEXT NOT NULL,
        ${TrackOutboxTable.Columns.ByteCount} INTEGER NOT NULL,
        ${TrackOutboxTable.Columns.CreatedAt} INTEGER NOT NULL,
        ${TrackOutboxTable.Columns.UserId} TEXT NOT NULL,
        ${TrackOutboxTable.Columns.Meta} TEXT NOT NULL
    )
"""

/** Durable FIFO outbox for analytics events. Public methods hop to IO and are
 * serialized so persistence, capacity eviction, selection, and acknowledgement
 * cannot race each other. */
internal class TrackOutbox(
    private val databaseProvider: () -> SQLiteDatabase,
    private val limits: TrackOutboxLimits = TrackOutboxLimits(),
) {
    constructor(dbHelper: NubrickDbHelper) : this({ dbHelper.writableDatabase })

    private val db: SQLiteDatabase by lazy { databaseProvider() }

    suspend fun insertAndGetPendingCount(
        eventId: String,
        payload: String,
        eventType: String,
        createdAt: Long,
        userId: String,
        meta: String,
    ): Int? = withDatabase {
        val byteCount = payload.toByteArray(Charsets.UTF_8).size.toLong()
        if (byteCount > limits.maxEventBytes.toLong()) return@withDatabase null

        try {
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put(TrackOutboxTable.Columns.EventId, eventId)
                    put(TrackOutboxTable.Columns.Payload, payload)
                    put(TrackOutboxTable.Columns.EventType, eventType)
                    put(TrackOutboxTable.Columns.ByteCount, byteCount)
                    put(TrackOutboxTable.Columns.CreatedAt, createdAt)
                    put(TrackOutboxTable.Columns.UserId, userId)
                    put(TrackOutboxTable.Columns.Meta, meta)
                }
                try {
                    db.insertOrThrow(TrackOutboxTable.Name, null, values)
                    enforceLimits()
                } catch (_: SQLiteConstraintException) {
                    // Already persisted; treat as success so crash recovery can ack.
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            pendingEventCount()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the oldest events in FIFO order. Crash payloads remain isolated,
     * but do not overtake events that were queued earlier.
     */
    suspend fun nextBatch(maxEvents: Int, maxPayloadBytes: Int): List<PendingTrackEvent> = withDatabase {
        val entries = query(maxEvents)
        val first = entries.firstOrNull() ?: return@withDatabase emptyList()
        if (first.eventType == CRASH_EVENT_TYPE) return@withDatabase listOf(first)

        val batch = mutableListOf<PendingTrackEvent>()
        var payloadBytes = 0L
        for (entry in entries) {
            if (entry.eventType == CRASH_EVENT_TYPE) break
            if (entry.userId != first.userId || entry.meta != first.meta) break
            if (batch.isNotEmpty() && payloadBytes > maxPayloadBytes.toLong() - entry.byteCount) break
            batch += entry
            payloadBytes += entry.byteCount
        }
        batch
    }

    suspend fun remove(eventIds: List<String>) = withDatabase {
        if (eventIds.isEmpty()) return@withDatabase
        val placeholders = eventIds.joinToString(",") { "?" }
        db.delete(
            TrackOutboxTable.Name,
            "${TrackOutboxTable.Columns.EventId} IN ($placeholders)",
            eventIds.toTypedArray(),
        )
    }

    suspend fun hasPendingEvents(): Boolean = withDatabase {
        pendingEventCount() > 0
    }

    private suspend fun <T> withDatabase(block: () -> T): T =
        withContext(Dispatchers.IO) {
            synchronized(this@TrackOutbox) {
                block()
            }
        }

    private fun pendingEventCount(): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM ${TrackOutboxTable.Name}",
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun query(limit: Int): List<PendingTrackEvent> {
        return db.query(
            TrackOutboxTable.Name,
            arrayOf(
                TrackOutboxTable.Columns.EventId,
                TrackOutboxTable.Columns.Payload,
                TrackOutboxTable.Columns.EventType,
                TrackOutboxTable.Columns.ByteCount,
                TrackOutboxTable.Columns.UserId,
                TrackOutboxTable.Columns.Meta,
            ),
            null,
            null,
            null,
            null,
            "${BaseColumns._ID} ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingTrackEvent(
                        eventId = cursor.getString(0),
                        payload = cursor.getString(1),
                        eventType = cursor.getString(2),
                        byteCount = cursor.getLong(3).coerceAtLeast(0L),
                        userId = cursor.getString(4),
                        meta = cursor.getString(5),
                    ))
                }
            }
        }
    }

    private fun enforceLimits() {
        var totalCount = pendingEventCount()
        var totalBytes = totalPendingBytes()
        var evicted = false

        while (totalCount > limits.maxEventCount || totalBytes > limits.maxQueueBytes.toLong()) {
            val oldest = oldestPendingEvent() ?: break
            val (id, byteCount) = oldest
            db.delete(TrackOutboxTable.Name, "${BaseColumns._ID} = ?", arrayOf(id.toString()))
            totalCount -= 1
            totalBytes -= byteCount
            evicted = true
        }

        if (evicted) {
            Log.w("NubrickSDK", "Discarded oldest pending tracking events because the outbox limit was reached")
        }
    }

    private fun totalPendingBytes(): Long = db.rawQuery(
        "SELECT COALESCE(SUM(${TrackOutboxTable.Columns.ByteCount}), 0) FROM ${TrackOutboxTable.Name}",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
    }

    private fun oldestPendingEvent(): Pair<Long, Long>? = db.query(
        TrackOutboxTable.Name,
        arrayOf(BaseColumns._ID, TrackOutboxTable.Columns.ByteCount),
        null,
        null,
        null,
        null,
        "${BaseColumns._ID} ASC",
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getLong(1).coerceAtLeast(0L) else null
    }

    private companion object {
        const val CRASH_EVENT_TYPE = "crash"
    }
}
