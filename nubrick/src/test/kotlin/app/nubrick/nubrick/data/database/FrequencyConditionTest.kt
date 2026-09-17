package app.nubrick.nubrick.data.database

import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.FrequencyUnit
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyConditionTest {
    @Test
    fun supportedComparisonsAreTheSingleThresholdOperators() {
        assertTrue(isSupportedUserEventFrequencyComparison(ConditionOperator.Equal))
        assertTrue(isSupportedUserEventFrequencyComparison(ConditionOperator.NotEqual))
        assertTrue(isSupportedUserEventFrequencyComparison(ConditionOperator.GreaterThan))
        assertTrue(isSupportedUserEventFrequencyComparison(ConditionOperator.GreaterThanOrEqual))
        assertTrue(isSupportedUserEventFrequencyComparison(ConditionOperator.LessThan))
        assertTrue(isSupportedUserEventFrequencyComparison(ConditionOperator.LessThanOrEqual))
    }

    @Test
    fun unsupportedComparisonsAreRejected() {
        assertFalse(isSupportedUserEventFrequencyComparison(ConditionOperator.Regex))
        assertFalse(isSupportedUserEventFrequencyComparison(ConditionOperator.In))
        assertFalse(isSupportedUserEventFrequencyComparison(ConditionOperator.NotIn))
        assertFalse(isSupportedUserEventFrequencyComparison(ConditionOperator.Between))
        assertFalse(isSupportedUserEventFrequencyComparison(ConditionOperator.UNKNOWN))
    }

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
