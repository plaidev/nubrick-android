package app.nubrick.nubrick.data.database

import android.database.sqlite.SQLiteDatabase
import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.FrequencyUnit
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import org.junit.Assert
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DatabaseRepositoryAndroidTest {
    private lateinit var db: SQLiteDatabase
    private lateinit var repository: DatabaseRepositoryImpl

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL(SQL_CREATE_EXPERIMENT_HISTORY_TABLE)
        db.execSQL(SQL_CREATE_USER_EVENT_TABLE)
        db.execSQL(SQL_CREATE_TRACK_OUTBOX_TABLE)
        repository = DatabaseRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun eventFrequencyConditionCountsEventsInSameBucket() = runBlocking {
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

    @Test
    fun trackOutboxKeepsFifoRecordsUntilAcknowledged() = runBlocking {
        val outbox = TrackOutbox(databaseProvider = { db })
        Assert.assertNotNull(outbox.insertEvent("old", createdAt = 1))
        Assert.assertNotNull(outbox.insertEvent("new", createdAt = 2))

        val batch = outbox.nextNormalBatch(maxEvents = 50, maxPayloadBytes = 512 * 1024)
        Assert.assertEquals(listOf("old", "new"), batch.map { it.eventId })

        outbox.remove(listOf("old"))
        Assert.assertEquals(listOf("new"), outbox.nextNormalBatch(50, 512 * 1024).map { it.eventId })
    }

    @Test
    fun trackOutboxEvictsOnlyTheOldestEventAtCountLimit() = runBlocking {
        val outbox = TrackOutbox(
            databaseProvider = { db },
            limits = TrackOutboxLimits(maxEventCount = 2, maxQueueBytes = 1_000_000),
        )

        outbox.insertEvent("first", createdAt = 1)
        outbox.insertEvent("second", createdAt = 2)
        outbox.insertEvent("third", createdAt = 3)

        Assert.assertEquals(
            listOf("second", "third"),
            outbox.nextNormalBatch(50, 512 * 1024).map { it.eventId },
        )
    }

    @Test
    fun trackOutboxEvictsOnlyTheOldestEventAtByteLimit() = runBlocking {
        val firstPayload = "{\"eventUuid\":\"first\"}"
        val secondPayload = "{\"eventUuid\":\"second\"}"
        val outbox = TrackOutbox(
            databaseProvider = { db },
            limits = TrackOutboxLimits(
                maxEventCount = 10,
                maxQueueBytes = maxOf(firstPayload.length, secondPayload.length),
            ),
        )

        outbox.insertEvent("first", createdAt = 1, payload = firstPayload)
        outbox.insertEvent("second", createdAt = 2, payload = secondPayload)

        Assert.assertEquals(
            listOf("second"),
            outbox.nextNormalBatch(50, 512 * 1024).map { it.eventId },
        )
    }

    @Test
    fun trackOutboxEvictsOldestEventRegardlessOfType() = runBlocking {
        val outbox = TrackOutbox(
            databaseProvider = { db },
            limits = TrackOutboxLimits(maxEventCount = 2, maxQueueBytes = 1_000_000),
        )

        outbox.insertEvent("crash-1", createdAt = 1, eventType = "crash")
        outbox.insertEvent("event-1", createdAt = 2)
        outbox.insertEvent("event-2", createdAt = 3)

        Assert.assertNull(outbox.nextCrash())
        Assert.assertEquals(
            listOf("event-1", "event-2"),
            outbox.nextNormalBatch(50, 512 * 1024).map { it.eventId },
        )
    }

    @Test
    fun trackOutboxInsertOfDuplicateEventIdIsIdempotent() = runBlocking {
        val outbox = TrackOutbox(databaseProvider = { db })
        val payload = "{\"eventUuid\":\"same\"}"

        Assert.assertNotNull(outbox.insertEvent("same", createdAt = 1, payload = payload))
        Assert.assertNotNull(outbox.insertEvent("same", createdAt = 2, payload = payload))
        Assert.assertEquals(
            listOf("same"),
            outbox.nextNormalBatch(50, 512 * 1024).map { it.eventId },
        )
    }

    @Test
    fun trackOutboxCreateTableIsIdempotent() {
        db.execSQL(SQL_CREATE_TRACK_OUTBOX_TABLE)
    }

    @Test
    fun trackOutboxKeepsUserIdentityWithTheEvent() = runBlocking {
        val outbox = TrackOutbox(databaseProvider = { db })
        outbox.insertEvent("event-a", createdAt = 1, userId = "user-a", meta = """{"appVersion":"1.0"}""")

        val stored = outbox.nextNormalBatch(50, 512 * 1024).single()
        Assert.assertEquals("user-a", stored.userId)
        Assert.assertEquals("""{"appVersion":"1.0"}""", stored.meta)
    }

    @Test
    fun trackOutboxDoesNotMixUsersInTheSameBatch() = runBlocking {
        val outbox = TrackOutbox(databaseProvider = { db })
        outbox.insertEvent("a1", createdAt = 1, userId = "user-a")
        outbox.insertEvent("b1", createdAt = 2, userId = "user-b")
        outbox.insertEvent("a2", createdAt = 3, userId = "user-a")

        Assert.assertEquals(listOf("a1"), outbox.nextNormalBatch(50, 512 * 1024).map { it.eventId })
        outbox.remove(listOf("a1"))
        Assert.assertEquals(listOf("b1"), outbox.nextNormalBatch(50, 512 * 1024).map { it.eventId })
    }
}

private const val TEST_META = """{"platform":"android"}"""

private suspend fun TrackOutbox.insertEvent(
    eventId: String,
    createdAt: Long,
    eventType: String = "event",
    userId: String = "user-a",
    payload: String = "{\"eventUuid\":\"$eventId\"}",
    meta: String = TEST_META,
) = insert(eventId, payload, eventType, createdAt, userId, meta)
