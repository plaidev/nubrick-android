package app.nubrick.nubrick.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import app.nubrick.nubrick.data.extraction.compareInteger
import app.nubrick.nubrick.data.user.getToday
import app.nubrick.nubrick.data.user.getCurrentDate
import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.ExperimentFrequency
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import app.nubrick.nubrick.schema.FrequencyUnit
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface DatabaseRepository {
    suspend fun appendUserEvent(name: String): Boolean
    suspend fun appendExperimentHistory(experimentId: String): Boolean
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

    override suspend fun appendUserEvent(name: String): Boolean = withDatabase {
        userEvent.append(name) != -1L
    }

    override suspend fun appendExperimentHistory(experimentId: String): Boolean = withDatabase {
        history.append(experimentId) != -1L
    }

    override suspend fun isNotInFrequency(experimentId: String, frequency: ExperimentFrequency?): Boolean = withDatabase {
        if (frequency == null) return@withDatabase true

        val unit = frequency.unit ?: FrequencyUnit.DAY

        // A missing period means "only once", so include the experiment's
        // complete display history instead of approximating it with a cutoff.
        val after = frequency.period?.let { period ->
            if (period <= 0) return@withDatabase true

            // Minute/hour frequencies are rolling windows. Longer units are calendar periods.
            val baseDate = when (unit) {
                FrequencyUnit.MINUTE, FrequencyUnit.HOUR -> getCurrentDate()
                FrequencyUnit.DAY, FrequencyUnit.UNKNOWN -> getToday()
                FrequencyUnit.WEEK -> getToday().with(DayOfWeek.MONDAY)
                FrequencyUnit.MONTH -> getToday().withDayOfMonth(1)
            }

            // The current calendar unit is included in the frequency interval.
            val unitsToSubtract = when (unit) {
                FrequencyUnit.DAY, FrequencyUnit.WEEK, FrequencyUnit.MONTH, FrequencyUnit.UNKNOWN ->
                    (period - 1).coerceAtLeast(0)
                else -> period
            }
            unit.subtract(unitsToSubtract, baseDate)
        }
        val count = try {
            history.count(experimentId, after, getCurrentDate())
        } catch (error: Exception) {
            Log.e("NubrickSDK", "Couldn't read experiment frequency history", error)
            return@withDatabase false
        }
        count == 0L
    }

    override suspend fun isMatchedToUserEventFrequencyCondition(condition: UserEventFrequencyCondition?): Boolean = withDatabase {
        if (condition == null) return@withDatabase true
        val eventName = condition.eventName ?: return@withDatabase true
        val threshold = condition.threshold ?: return@withDatabase true
        val unit = condition.unit ?: FrequencyUnit.DAY
        val comparison = condition.comparison ?: ConditionOperator.Equal
        val counts = try {
            userEvent.counts(
                name = eventName,
                unit = unit,
                lookbackPeriod = condition.lookbackPeriod,
                since = condition.since
            )
        } catch (error: Exception) {
            Log.e("NubrickSDK", "Couldn't read user event frequency history", error)
            return@withDatabase false
        }
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
