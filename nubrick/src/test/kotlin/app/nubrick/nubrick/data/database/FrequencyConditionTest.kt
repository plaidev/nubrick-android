package app.nubrick.nubrick.data.database

import app.nubrick.nubrick.schema.FrequencyUnit
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FrequencyConditionTest {
    @Test
    fun subtractClampsNegativePeriodsToTheReferenceDate() {
        val now = ZonedDateTime.of(2026, 9, 17, 12, 0, 0, 0, ZoneOffset.UTC)
        assertEquals(now, FrequencyUnit.DAY.subtract(-7, now))
        assertEquals(now, FrequencyUnit.MONTH.subtract(Int.MIN_VALUE, now))
    }

    @Test
    fun subtractDoesNotThrowForLargeMonthValues() {
        val now = ZonedDateTime.of(2026, 9, 17, 12, 0, 0, 0, ZoneOffset.UTC)
        val result = FrequencyUnit.MONTH.subtract(Int.MAX_VALUE, now)
        assertFalse(result.isAfter(now))
    }
}
