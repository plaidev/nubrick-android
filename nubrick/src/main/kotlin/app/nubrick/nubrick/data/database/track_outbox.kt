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
    val byteCount: Int,
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

    suspend fun insert(
        eventId: String,
        payload: String,
        eventType: String,
        createdAt: Long,
        userId: String,
        meta: String,
    ): Int? = withDatabase {
        val byteCount = payload.toByteArray(Charsets.UTF_8).size
        if (byteCount > limits.maxEventBytes) return@withDatabase null

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
            if (eventType == CRASH_EVENT_TYPE) 0 else countNormalEvents()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun nextCrash(): PendingTrackEvent? = withDatabase {
        query(
            selection = "${TrackOutboxTable.Columns.EventType} = ?",
            selectionArgs = arrayOf(CRASH_EVENT_TYPE),
            limit = 1,
        ).firstOrNull()
    }

    suspend fun nextNormalBatch(maxEvents: Int, maxPayloadBytes: Int): List<PendingTrackEvent> = withDatabase {
        val entries = query(
            selection = "${TrackOutboxTable.Columns.EventType} != ?",
            selectionArgs = arrayOf(CRASH_EVENT_TYPE),
            limit = maxEvents,
        )
        val first = entries.firstOrNull() ?: return@withDatabase emptyList()
        val batch = mutableListOf<PendingTrackEvent>()
        var payloadBytes = 0
        for (entry in entries) {
            if (entry.userId != first.userId || entry.meta != first.meta) break
            if (batch.isNotEmpty() && payloadBytes + entry.byteCount > maxPayloadBytes) break
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
        count("1", null, null) > 0
    }

    private suspend fun <T> withDatabase(block: () -> T): T =
        withContext(Dispatchers.IO) {
            synchronized(this@TrackOutbox) {
                block()
            }
        }

    private fun countNormalEvents(): Int = count(
        "${TrackOutboxTable.Columns.EventType} != ?",
        arrayOf(CRASH_EVENT_TYPE),
        null,
    )

    private fun count(selection: String, selectionArgs: Array<String>?, defaultValue: Int? = 0): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM ${TrackOutboxTable.Name} WHERE $selection",
            selectionArgs,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else defaultValue ?: 0
        }
    }

    private fun query(selection: String, selectionArgs: Array<String>, limit: Int): List<PendingTrackEvent> {
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
            selection,
            selectionArgs,
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
                        byteCount = cursor.getInt(3),
                        userId = cursor.getString(4),
                        meta = cursor.getString(5),
                    ))
                }
            }
        }
    }

    private fun enforceLimits() {
        var totalCount = count("1", null)
        var totalBytes = totalPendingBytes()
        var evicted = false

        while (totalCount > limits.maxEventCount || totalBytes > limits.maxQueueBytes) {
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

    private fun totalPendingBytes(): Int = db.rawQuery(
        "SELECT COALESCE(SUM(${TrackOutboxTable.Columns.ByteCount}), 0) FROM ${TrackOutboxTable.Name}",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    private fun oldestPendingEvent(): Pair<Long, Int>? = db.query(
        TrackOutboxTable.Name,
        arrayOf(BaseColumns._ID, TrackOutboxTable.Columns.ByteCount),
        null,
        null,
        null,
        null,
        "${BaseColumns._ID} ASC",
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getInt(1) else null
    }

    private companion object {
        const val CRASH_EVENT_TYPE = "crash"
    }
}
