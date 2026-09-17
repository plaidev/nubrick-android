package app.nubrick.nubrick.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.provider.BaseColumns
import app.nubrick.nubrick.data.user.formatISO8601
import app.nubrick.nubrick.data.user.getCurrentDate
import java.time.ZonedDateTime

private object ExperimentHistoryTable {
    const val Name: String = "experiment_history"
    object Columns: BaseColumns {
        const val ExperimentId = "experiment_id"
        const val Timestamp = "timestamp"
    }
}

internal const val SQL_CREATE_EXPERIMENT_HISTORY_TABLE = """
    CREATE TABLE ${ExperimentHistoryTable.Name} (
        ${BaseColumns._ID} INTEGER PRIMARY KEY,
        ${ExperimentHistoryTable.Columns.ExperimentId} TEXT,
        ${ExperimentHistoryTable.Columns.Timestamp} DATETIME
    )
"""

internal class ExperimentHistory(private val db: SQLiteDatabase) {
    fun append(
        experimentId: String
    ): Long {
        val values = ContentValues().apply {
            put(ExperimentHistoryTable.Columns.ExperimentId, experimentId)
            put(ExperimentHistoryTable.Columns.Timestamp, formatISO8601(getCurrentDate()))
        }
        return db.insert(ExperimentHistoryTable.Name, null, values)
    }

    fun count(
        experimentId: String,
        after: ZonedDateTime?,
        now: ZonedDateTime,
    ): Long {
        val timestamp = ExperimentHistoryTable.Columns.Timestamp
        val projection = arrayOf("count($timestamp) AS count")
        val selection: String
        val selectionArgs: Array<String>
        if (after == null) {
            selection = """
                ${ExperimentHistoryTable.Columns.ExperimentId} = ?
                AND julianday($timestamp) <= julianday(?)
            """.trimIndent()
            selectionArgs = arrayOf(experimentId, formatISO8601(now))
        } else {
            selection = """
                ${ExperimentHistoryTable.Columns.ExperimentId} = ?
                AND julianday($timestamp) >= julianday(?)
                AND julianday($timestamp) <= julianday(?)
            """.trimIndent()
            selectionArgs = arrayOf(experimentId, formatISO8601(after), formatISO8601(now))
        }
        return db.query(
            ExperimentHistoryTable.Name,
            projection,
            selection,
            selectionArgs,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToNext()) return@use 0L
            cursor.getLong(cursor.getColumnIndexOrThrow("count"))
        }
    }
}
