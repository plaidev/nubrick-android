package app.nubrick.nubrick.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.nubrick.nubrick.data.extraction.compareInteger
import app.nubrick.nubrick.data.user.getToday
import app.nubrick.nubrick.data.user.getCurrentDate
import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.ExperimentFrequency
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import app.nubrick.nubrick.schema.FrequencyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface DatabaseRepository {
    suspend fun appendUserEvent(name: String)
    suspend fun appendExperimentHistory(experimentId: String)
    suspend fun isNotInFrequency(experimentId: String, frequency: ExperimentFrequency?): Boolean
    suspend fun isMatchedToUserEventFrequencyCondition(condition: UserEventFrequencyCondition?): Boolean
    suspend fun close() {}
}

private const val DATABASE_NAME = "Nativebrik.sdk.db"
private const val DATABASE_VERSION = 2
internal class NubrickDbHelper(context: Context): SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        try {
            db.execSQL(SQL_CREATE_EXPERIMENT_HISTORY_TABLE)
            db.execSQL(SQL_CREATE_USER_EVENT_TABLE)
            db.execSQL(SQL_CREATE_TRACK_OUTBOX_TABLE)
        } catch (_: Exception) {
            throw Exception("Nubrick SDK couldn't create a sqlite database.")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            if (oldVersion < 2) {
                db.execSQL(SQL_CREATE_TRACK_OUTBOX_TABLE)
            }
        } catch (_: Exception) {
            throw Exception("Nubrick SDK couldn't create a sqlite database.")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }
}

internal class DatabaseRepositoryImpl private constructor(
    private val databaseProvider: () -> SQLiteDatabase,
    private val closeDatabase: () -> Unit,
): DatabaseRepository {
    constructor(db: SQLiteDatabase) : this(databaseProvider = { db }, closeDatabase = {})
    constructor(dbHelper: NubrickDbHelper) : this(
        databaseProvider = { dbHelper.writableDatabase },
        closeDatabase = { dbHelper.close() },
    )

    // These are first accessed from withDatabase(), so opening the database remains on IO.
    private val db: SQLiteDatabase by lazy { databaseProvider() }
    private val history: ExperimentHistory by lazy { ExperimentHistory(db) }
    private val userEvent: UserEvent by lazy { UserEvent(db) }

    override suspend fun appendUserEvent(name: String) {
        withDatabase {
            userEvent.append(name)
        }
    }

    override suspend fun appendExperimentHistory(experimentId: String) {
        withDatabase {
            history.append(experimentId)
        }
    }

    override suspend fun isNotInFrequency(experimentId: String, frequency: ExperimentFrequency?): Boolean = withDatabase {
        if (frequency == null) return@withDatabase true

        val period = frequency.period ?: (365 * 50)
        val unit = frequency.unit ?: FrequencyUnit.DAY

        // For minute/hour we base calculation on current date-time, otherwise on today (truncated day).
        val baseDate = when (unit) {
            FrequencyUnit.MINUTE, FrequencyUnit.HOUR -> getCurrentDate()
            else -> getToday()
        }

        val after = unit.subtract(period, baseDate)
        val count = history.countAfter(experimentId, after)
        count.toInt() == 0
    }

    override suspend fun isMatchedToUserEventFrequencyCondition(condition: UserEventFrequencyCondition?): Boolean = withDatabase {
        if (condition == null) return@withDatabase true
        val eventName = condition.eventName ?: return@withDatabase true
        val threshold = condition.threshold ?: return@withDatabase true
        val unit = condition.unit ?: FrequencyUnit.DAY
        val comparison = condition.comparison ?: ConditionOperator.Equal
        val counts = userEvent.counts(
            name = eventName,
            unit = unit,
            lookbackPeriod = condition.lookbackPeriod,
            since = condition.since
        )
        val total = counts.values.sum()
        compareInteger(total, listOf(threshold), comparison)
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        closeDatabase()
    }

    private suspend fun <T> withDatabase(block: () -> T): T =
        withContext(Dispatchers.IO) {
            block()
        }
}
