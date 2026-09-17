package app.nubrick.nubrick.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.provider.BaseColumns
import app.nubrick.nubrick.data.user.formatISO8601
import app.nubrick.nubrick.data.user.getCurrentDate
import app.nubrick.nubrick.schema.DateTime
import app.nubrick.nubrick.schema.FrequencyUnit
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

private object UserEventTable {
    const val Name: String = "event"
    object Columns: BaseColumns {
        const val Name = "name"
        const val Timestamp = "timestamp"
    }
}

internal const val SQL_CREATE_USER_EVENT_TABLE = """
    CREATE TABLE ${UserEventTable.Name} (
        ${BaseColumns._ID} INTEGER PRIMARY KEY,
        ${UserEventTable.Columns.Name} TEXT,
        ${UserEventTable.Columns.Timestamp} DATETIME
    )
"""

internal class UserEvent(private val db: SQLiteDatabase) {
    fun append(
        name: String
    ): Long {
        val values = ContentValues().apply {
            put(UserEventTable.Columns.Name, name)
            put(UserEventTable.Columns.Timestamp, formatISO8601(getCurrentDate()))
        }
        return db.insert(UserEventTable.Name, null, values)
    }

    /**
     * Calculate the number of events aggregated by the given [unit].
     * The result is a map whose key is the bucket start ([ZonedDateTime]) and value is the
     * number of events that fall into that bucket.
     *
     * The effective lower bound is the later of (now − lookback) and [since].
     * Missing bounds are unbounded.
     *
     * @param name           Event name to aggregate.
     * @param unit           Time unit used as aggregation bucket and lookback unit.
     * @param lookbackPeriod Number of [unit]s to look back from now.
     * @param since          Lower-bound timestamp; events before this instant are excluded.
     */
    fun counts(
        name: String,
        unit: FrequencyUnit,
        lookbackPeriod: Int?,
        since: DateTime?
    ): Map<ZonedDateTime, Int> {
        val today = getCurrentDate()
        val startDate = lookbackPeriod?.let { unit.subtract(it.coerceAtLeast(0), today) }
        val lowerBound = listOfNotNull(startDate, since).maxOrNull()

        val timestamps = fetchTimestamps(name, lowerBound)

        // Aggregate counts per bucket
        val counts: MutableMap<ZonedDateTime, Int> = mutableMapOf()
        for (ts in timestamps) {
            val bucket = unit.bucketStart(ts)
            counts[bucket] = counts.getOrDefault(bucket, 0) + 1
        }
        return counts
    }

    /**
     * Fetch timestamps of events whose name matches and, when provided, occurred after [after].
     * Returned timestamps are converted to UTC ZonedDateTime.
     */
    private fun fetchTimestamps(name: String, after: ZonedDateTime?): List<ZonedDateTime> {
        val selection: String
        val selectionArgs: Array<String>
        if (after == null) {
            selection = "${UserEventTable.Columns.Name} = ?"
            selectionArgs = arrayOf(name)
        } else {
            selection = "${UserEventTable.Columns.Name} = ? AND ${UserEventTable.Columns.Timestamp} >= ?"
            selectionArgs = arrayOf(name, formatISO8601(after))
        }
        val cursor = this.db.query(
            UserEventTable.Name,
            arrayOf(UserEventTable.Columns.Timestamp),
            selection,
            selectionArgs,
            null,
            null,
            null,
        )

        val timeIdx = cursor.getColumnIndexOrThrow(UserEventTable.Columns.Timestamp)
        val list = mutableListOf<ZonedDateTime>()
        while (cursor.moveToNext()) {
            val tsStr = cursor.getString(timeIdx) ?: continue
            try {
                val instant = Instant.parse(tsStr)
                list.add(instant.atZone(ZoneOffset.UTC))
            } catch (_: Exception) {
            }
        }
        cursor.close()
        return list
    }
}

// -----------------------------------------------------------------------------
// FrequencyUnit helpers – implemented as extension functions to avoid polluting
// the original enum definition generated elsewhere.
// -----------------------------------------------------------------------------

internal fun FrequencyUnit.subtract(value: Int, from: ZonedDateTime): ZonedDateTime {
    val amount = value.coerceAtLeast(0)
    if (amount == 0) return from
    return try {
        when (this) {
            FrequencyUnit.MINUTE -> from.minusMinutes(amount.toLong())
            FrequencyUnit.HOUR -> from.minusHours(amount.toLong())
            FrequencyUnit.DAY -> from.minusDays(amount.toLong())
            FrequencyUnit.WEEK -> from.minusWeeks(amount.toLong())
            FrequencyUnit.MONTH -> from.minusMonths(amount.toLong())
            FrequencyUnit.UNKNOWN -> from
        }
    } catch (_: DateTimeException) {
        from
    }
}

internal fun FrequencyUnit.bucketStart(date: ZonedDateTime): ZonedDateTime = when (this) {
    FrequencyUnit.MINUTE -> date.truncatedTo(ChronoUnit.MINUTES)
    FrequencyUnit.HOUR -> date.truncatedTo(ChronoUnit.HOURS)
    FrequencyUnit.DAY -> date.truncatedTo(ChronoUnit.DAYS)
    FrequencyUnit.WEEK -> date.with(DayOfWeek.MONDAY).truncatedTo(ChronoUnit.DAYS)
    FrequencyUnit.MONTH -> date.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
    else -> date.truncatedTo(ChronoUnit.DAYS)
}
