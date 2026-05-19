package app.nubrick.nubrick.data.database

import android.database.sqlite.SQLiteDatabase
import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.FrequencyUnit
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import org.junit.Assert
import org.junit.After
import org.junit.Before
import org.junit.Test

class DatabaseRepositoryAndroidTest {
    private lateinit var db: SQLiteDatabase
    private lateinit var repository: DatabaseRepositoryImpl

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL(SQL_CREATE_EXPERIMENT_HISTORY_TABLE)
        db.execSQL(SQL_CREATE_USER_EVENT_TABLE)
        repository = DatabaseRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun eventFrequencyConditionCountsEventsInSameBucket() {
        repository.appendUserEvent("purchase")
        repository.appendUserEvent("purchase")

        val matched = repository.isMatchedToUserEventFrequencyCondition(
            UserEventFrequencyCondition(
                eventName = "purchase",
                lookbackPeriod = 1,
                unit = FrequencyUnit.DAY,
                comparison = ConditionOperator.GreaterThanOrEqual,
                threshold = 2,
            )
        )

        Assert.assertTrue(matched)
    }
}
