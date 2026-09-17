package app.nubrick.nubrick.data.database

import android.database.sqlite.SQLiteDatabase
import app.nubrick.nubrick.data.user.DATETIME_OFFSET
import app.nubrick.nubrick.data.user.formatISO8601
import app.nubrick.nubrick.data.user.getCurrentDate
import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.ExperimentFrequency
import app.nubrick.nubrick.schema.FrequencyUnit
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DatabaseRepositoryAndroidTest {
    private lateinit var db: SQLiteDatabase
    private lateinit var repository: DatabaseRepositoryImpl
    private var originalDateTimeOffset = 0L

    @Before
    fun setUp() {
        originalDateTimeOffset = DATETIME_OFFSET
        db = SQLiteDatabase.create(null)
        db.execSQL(SQL_CREATE_EXPERIMENT_HISTORY_TABLE)
        db.execSQL(SQL_CREATE_USER_EVENT_TABLE)
        db.execSQL(SQL_CREATE_TRACK_OUTBOX_TABLE)
        repository = DatabaseRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        DATETIME_OFFSET = originalDateTimeOffset
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
    fun dailyExperimentFrequencyAllowsDisplayOnTheNextLocalDay() = runBlocking {
        val originalOffset = DATETIME_OFFSET
        try {
            val displayedAt = getCurrentDate()
                .withHour(12)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
            setCurrentDate(displayedAt)
            repository.appendExperimentHistory("daily-experiment")

            val blockedOnSameDay = repository.isNotInFrequency(
                "daily-experiment",
                ExperimentFrequency(period = 1, unit = FrequencyUnit.DAY),
            )
            setCurrentDate(displayedAt.plusDays(1))
            val allowed = repository.isNotInFrequency(
                "daily-experiment",
                ExperimentFrequency(period = 1, unit = FrequencyUnit.DAY),
            )

            Assert.assertFalse(blockedOnSameDay)
            Assert.assertTrue(allowed)
        } finally {
            DATETIME_OFFSET = originalOffset
        }
    }

    @Test
    fun weeklyExperimentFrequencyAllowsDisplayInTheNextCalendarWeek() = runBlocking {
        val originalOffset = DATETIME_OFFSET
        try {
            val displayedAt = getCurrentDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
                .withHour(12)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
            setCurrentDate(displayedAt)
            repository.appendExperimentHistory("weekly-experiment")

            setCurrentDate(
                displayedAt.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).withHour(12)
            )
            val allowed = repository.isNotInFrequency(
                "weekly-experiment",
                ExperimentFrequency(period = 1, unit = FrequencyUnit.WEEK),
            )

            Assert.assertTrue(allowed)
        } finally {
            DATETIME_OFFSET = originalOffset
        }
    }

    @Test
    fun monthlyExperimentFrequencyAllowsDisplayInTheNextCalendarMonth() = runBlocking {
        val originalOffset = DATETIME_OFFSET
        try {
            val displayedAt = getCurrentDate()
                .withDayOfMonth(15)
                .withHour(12)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
            setCurrentDate(displayedAt)
            repository.appendExperimentHistory("monthly-experiment")

            setCurrentDate(displayedAt.plusMonths(1).withDayOfMonth(1).withHour(12))
            val allowed = repository.isNotInFrequency(
                "monthly-experiment",
                ExperimentFrequency(period = 1, unit = FrequencyUnit.MONTH),
            )

            Assert.assertTrue(allowed)
        } finally {
            DATETIME_OFFSET = originalOffset
        }
    }

    @Test
    fun nonPositiveFrequencyPeriodsDoNotRestrictDelivery() = runBlocking {
        val originalOffset = DATETIME_OFFSET
        try {
            val displayedAt = getCurrentDate()
                .withHour(12)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
            setCurrentDate(displayedAt)
            repository.appendExperimentHistory("hourly-experiment")

            listOf(0, -1).forEach { period ->
                val allowed = repository.isNotInFrequency(
                    "hourly-experiment",
                    ExperimentFrequency(period = period, unit = FrequencyUnit.HOUR),
                )

                Assert.assertTrue("period=$period", allowed)
            }

            val blockedWithNoPeriodOrUnit = repository.isNotInFrequency(
                "hourly-experiment",
                ExperimentFrequency(),
            )

            Assert.assertFalse(blockedWithNoPeriodOrUnit)
        } finally {
            DATETIME_OFFSET = originalOffset
        }
    }

    @Test
    fun missingPeriodBlocksDisplaysAcrossAllHistory() = runBlocking {
        val originalOffset = DATETIME_OFFSET
        try {
            val displayedAt = getCurrentDate()
            setCurrentDate(displayedAt)
            repository.appendExperimentHistory("once-only-experiment")

            setCurrentDate(displayedAt.plusYears(60))

            listOf(FrequencyUnit.MINUTE, FrequencyUnit.HOUR).forEach { unit ->
                val allowed = repository.isNotInFrequency(
                    "once-only-experiment",
                    ExperimentFrequency(unit = unit),
                )
                Assert.assertFalse("unit=$unit", allowed)
            }

            val allowedWithExplicitHourPeriod = repository.isNotInFrequency(
                "once-only-experiment",
                ExperimentFrequency(period = 1, unit = FrequencyUnit.HOUR),
            )
            Assert.assertTrue(allowedWithExplicitHourPeriod)
        } finally {
            DATETIME_OFFSET = originalOffset
        }
    }

    @Test
    fun missingLookbackPeriodCountsEventsAcrossAllHistory() = runBlocking {
        val originalOffset = DATETIME_OFFSET
        try {
            val recordedAt = getCurrentDate()
            setCurrentDate(recordedAt)
            repository.appendUserEvent("purchase")

            setCurrentDate(recordedAt.plusYears(60))

            listOf(FrequencyUnit.MINUTE, FrequencyUnit.HOUR).forEach { unit ->
                val matched = repository.isMatchedToUserEventFrequencyCondition(
                    UserEventFrequencyCondition(
                        eventName = "purchase",
                        unit = unit,
                        comparison = ConditionOperator.GreaterThanOrEqual,
                        threshold = 1,
                    )
                )
                Assert.assertTrue("unit=$unit", matched)
            }

            val explicitHourLookbackMisses = repository.isMatchedToUserEventFrequencyCondition(
                UserEventFrequencyCondition(
                    eventName = "purchase",
                    lookbackPeriod = 1,
                    unit = FrequencyUnit.HOUR,
                    comparison = ConditionOperator.GreaterThanOrEqual,
                    threshold = 1,
                )
            )
            Assert.assertFalse(explicitHourLookbackMisses)
        } finally {
            DATETIME_OFFSET = originalOffset
        }
    }

    @Test
    fun trackOutboxKeepsFifoRecordsUntilAcknowledged() = runBlocking {
        val outbox = TrackOutbox(databaseProvider = { db })
        Assert.assertNotNull(outbox.insertEvent("old", createdAt = 1))
        Assert.assertNotNull(outbox.insertEvent("new", createdAt = 2))

        val batch = outbox.nextBatch(maxEvents = 50, maxPayloadBytes = 512 * 1024)
        Assert.assertEquals(listOf("old", "new"), batch.map { it.eventId })

        outbox.remove(listOf("old"))
        Assert.assertEquals(listOf("new"), outbox.nextBatch(50, 512 * 1024).map { it.eventId })
    }

    @Test
    fun trackOutboxDoesNotLetCrashesOvertakeEarlierEvents() = runBlocking {
        val outbox = TrackOutbox(databaseProvider = { db })
        outbox.insertEvent("event-before", createdAt = 1)
        outbox.insertEvent("crash", createdAt = 2, eventType = "crash")
        outbox.insertEvent("event-after", createdAt = 3)

        Assert.assertEquals(listOf("event-before"), outbox.nextBatch(50, 512 * 1024).map { it.eventId })
        outbox.remove(listOf("event-before"))
        Assert.assertEquals(listOf("crash"), outbox.nextBatch(50, 512 * 1024).map { it.eventId })
        outbox.remove(listOf("crash"))
        Assert.assertEquals(listOf("event-after"), outbox.nextBatch(50, 512 * 1024).map { it.eventId })
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
            outbox.nextBatch(50, 512 * 1024).map { it.eventId },
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
            outbox.nextBatch(50, 512 * 1024).map { it.eventId },
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

        Assert.assertEquals(
            listOf("event-1", "event-2"),
            outbox.nextBatch(50, 512 * 1024).map { it.eventId },
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
            outbox.nextBatch(50, 512 * 1024).map { it.eventId },
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

        val stored = outbox.nextBatch(50, 512 * 1024).single()
        Assert.assertEquals("user-a", stored.userId)
        Assert.assertEquals("""{"appVersion":"1.0"}""", stored.meta)
    }

    @Test
    fun trackOutboxDoesNotMixUsersInTheSameBatch() = runBlocking {
        val outbox = TrackOutbox(databaseProvider = { db })
        outbox.insertEvent("a1", createdAt = 1, userId = "user-a")
        outbox.insertEvent("b1", createdAt = 2, userId = "user-b")
        outbox.insertEvent("a2", createdAt = 3, userId = "user-a")

        Assert.assertEquals(listOf("a1"), outbox.nextBatch(50, 512 * 1024).map { it.eventId })
        outbox.remove(listOf("a1"))
        Assert.assertEquals(listOf("b1"), outbox.nextBatch(50, 512 * 1024).map { it.eventId })
    }

    @Test
    fun missingEventFrequencyFieldsFailClosed() = runBlocking {
        val missingEventName = repository.isMatchedToUserEventFrequencyCondition(
            UserEventFrequencyCondition(
                unit = FrequencyUnit.DAY,
                comparison = ConditionOperator.Equal,
                threshold = 0,
            )
        )
        val missingThreshold = repository.isMatchedToUserEventFrequencyCondition(
            UserEventFrequencyCondition(
                eventName = "purchase",
                unit = FrequencyUnit.DAY,
                comparison = ConditionOperator.Equal,
            )
        )
        val missingComparison = repository.isMatchedToUserEventFrequencyCondition(
            UserEventFrequencyCondition(
                eventName = "purchase",
                unit = FrequencyUnit.DAY,
                threshold = 0,
            )
        )

        Assert.assertFalse(missingEventName)
        Assert.assertFalse(missingThreshold)
        Assert.assertFalse(missingComparison)
    }

    @Test
    fun unsupportedEventFrequencyComparisonsFailClosed() = runBlocking {
        listOf(
            ConditionOperator.Regex,
            ConditionOperator.In,
            ConditionOperator.NotIn,
            ConditionOperator.Between,
            ConditionOperator.UNKNOWN,
        ).forEach { comparison ->
            val matches = repository.isMatchedToUserEventFrequencyCondition(
                UserEventFrequencyCondition(
                    eventName = "purchase",
                    unit = FrequencyUnit.DAY,
                    comparison = comparison,
                    threshold = 0,
                )
            )
            Assert.assertFalse("comparison=$comparison", matches)
        }
    }

    @Test
    fun unknownFrequencyUnitFailsClosed() = runBlocking {
        val eventFrequencyMatches = repository.isMatchedToUserEventFrequencyCondition(
            UserEventFrequencyCondition(
                eventName = "purchase",
                unit = FrequencyUnit.UNKNOWN,
                comparison = ConditionOperator.Equal,
                threshold = 0,
            )
        )
        val experimentAllowed = repository.isNotInFrequency(
            "never-shown",
            ExperimentFrequency(period = 1, unit = FrequencyUnit.UNKNOWN),
        )

        Assert.assertFalse(eventFrequencyMatches)
        Assert.assertFalse(experimentAllowed)
    }

    @Test
    fun futureFrequencyHistoryIsIgnored() = runBlocking {
        val now = getCurrentDate()
        setCurrentDate(now.plusHours(1))
        repository.appendUserEvent("future-event")
        repository.appendExperimentHistory("future-experiment")
        setCurrentDate(now)

        Assert.assertFalse(repository.isMatchedToUserEventFrequencyCondition(eventCondition("future-event")))
        Assert.assertTrue(repository.isNotInFrequency("future-experiment", ExperimentFrequency()))
    }

    @Test
    fun lowerFrequencyBoundsAreInclusive() = runBlocking {
        val now = getCurrentDate().withHour(12).withMinute(0).withSecond(0).withNano(0)
        val eventBoundary = now.minusHours(2)
        val dayBoundary = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
        db.execSQL(
            "INSERT INTO event (name, timestamp) VALUES (?, ?)",
            arrayOf("boundary-event", formatISO8601(eventBoundary)),
        )
        db.execSQL(
            "INSERT INTO experiment_history (experiment_id, timestamp) VALUES (?, ?)",
            arrayOf("boundary-experiment", formatISO8601(dayBoundary)),
        )
        setCurrentDate(now)

        Assert.assertTrue(
            repository.isMatchedToUserEventFrequencyCondition(
                eventCondition("boundary-event", since = eventBoundary)
            )
        )
        Assert.assertFalse(
            repository.isNotInFrequency(
                "boundary-experiment",
                ExperimentFrequency(period = 1, unit = FrequencyUnit.DAY),
            )
        )
    }

    @Test
    fun databaseReadFailuresFailClosed() = runBlocking {
        db.close()

        Assert.assertFalse(
            repository.isMatchedToUserEventFrequencyCondition(
                eventCondition("event", threshold = 0, comparison = ConditionOperator.Equal)
            )
        )
        Assert.assertFalse(repository.isNotInFrequency("experiment", ExperimentFrequency()))
    }

    @Test
    fun databaseInsertFailuresAreReported() = runBlocking {
        db.execSQL(
            "CREATE TRIGGER fail_event_insert BEFORE INSERT ON event " +
                "BEGIN SELECT RAISE(FAIL, 'forced failure'); END"
        )
        db.execSQL(
            "CREATE TRIGGER fail_history_insert BEFORE INSERT ON experiment_history " +
                "BEGIN SELECT RAISE(FAIL, 'forced failure'); END"
        )

        Assert.assertFalse(repository.appendUserEvent("event"))
        Assert.assertFalse(repository.appendExperimentHistory("experiment"))
    }
}

private const val TEST_META = """{"platform":"android"}"""

private fun setCurrentDate(date: ZonedDateTime) {
    DATETIME_OFFSET = date.toInstant().toEpochMilli() - System.currentTimeMillis()
}

private fun eventCondition(
    name: String,
    threshold: Int = 1,
    comparison: ConditionOperator = ConditionOperator.GreaterThanOrEqual,
    since: ZonedDateTime? = null,
) = UserEventFrequencyCondition(
    eventName = name,
    unit = FrequencyUnit.HOUR,
    comparison = comparison,
    since = since,
    threshold = threshold,
)

private suspend fun TrackOutbox.insertEvent(
    eventId: String,
    createdAt: Long,
    eventType: String = "event",
    userId: String = "user-a",
    payload: String = "{\"eventUuid\":\"$eventId\"}",
    meta: String = TEST_META,
) = insertAndGetPendingCount(eventId, payload, eventType, createdAt, userId, meta)
