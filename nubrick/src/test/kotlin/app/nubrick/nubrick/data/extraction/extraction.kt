package app.nubrick.nubrick.data.extraction

import app.nubrick.nubrick.data.user.UserProperty
import app.nubrick.nubrick.data.user.getCurrentDate
import app.nubrick.nubrick.schema.UserPropertyType
import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.ExperimentCondition
import app.nubrick.nubrick.schema.ExperimentConfig
import app.nubrick.nubrick.schema.ExperimentConfigs
import app.nubrick.nubrick.schema.ExperimentFrequency
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.ExperimentVariant
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import app.nubrick.nubrick.schema.VariantConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

class ExtractionUnitTest {
    @Test
    fun extractComponentId_shouldWork() {
        Assert.assertEquals("hello", extractComponentId(ExperimentVariant(configs = listOf(
            VariantConfig(value = "hello")
        ))))
    }

    @Test
    fun extractExperimentVariant_shouldExtractVariantProbabilistically() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "1", weight = 1), // 0.25
            variants = listOf(
                ExperimentVariant(id = "2", weight = 1), // 0.5
                ExperimentVariant(id = "3", weight = 1), // 0.75
                ExperimentVariant(id = "4", weight = 1), // 1.0
            )
        )

        Assert.assertEquals("1", extractExperimentVariant(config, 0.24)?.id)
        Assert.assertEquals("2", extractExperimentVariant(config, 0.48)?.id)
        Assert.assertEquals("3", extractExperimentVariant(config, 0.74)?.id)
        Assert.assertEquals("4", extractExperimentVariant(config, 0.99)?.id)
    }

    @Test
    fun extractExperimentVariant_shouldWorkWithoutVariants() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "1", weight = 1), // 0.25
        )
        Assert.assertEquals("1", extractExperimentVariant(config, 0.24)?.id)
    }

    @Test
    fun extractExperimentConfig_shouldWork() = runBlocking {
        val properties: (seed: Int?) -> List<UserProperty> = {
            emptyList()
        }
        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig(
                    "1",
                    kind = ExperimentKind.POPUP,
                    distribution = emptyList()
                )
            )
        )
        Assert.assertEquals("1", extractExperimentConfig(configs, listOf(ExperimentKind.POPUP), properties, { _, _ -> true }, { _ -> true })?.id)
    }

    @Test
    fun extractExperimentConfig_shouldFilterOnlyRunningConfig() = runBlocking {
        val properties: (seed: Int?) -> List<UserProperty> = {
            emptyList()
        }
        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig(
                    "1",
                    kind = ExperimentKind.POPUP,
                    startedAt = getCurrentDate().plusDays(1),
                ),
                ExperimentConfig("2",
                    kind = ExperimentKind.POPUP,
                    startedAt = getCurrentDate().minusDays(1),
                    endedAt = getCurrentDate().minusDays(1)
                ),
                ExperimentConfig(
                    "running",
                    kind = ExperimentKind.POPUP,
                    startedAt = getCurrentDate().minusDays(1),
                    endedAt = getCurrentDate().plusDays(1),
                )
            )
        )
        Assert.assertEquals("running", extractExperimentConfig(configs, listOf(ExperimentKind.POPUP), properties, { _, _ -> true }, { _ -> true })?.id)
    }

    @Test
    fun isInDistributionTarget_shouldBeTrueWhenEmptyDistribution() {
        val properties: List<UserProperty> = emptyList()
        Assert.assertEquals(true, isInDistributionTarget(null, properties))
    }

    @Test
    fun isInDistributionTarget_shouldBeTrueWhenThereCorrectDistribution() {
        val properties: List<UserProperty> = listOf(
            UserProperty(
                name = "name",
                value = "Nubrick",
                type = UserPropertyType.STRING
            ),
            UserProperty(
                name = "id",
                value = "XXX",
                type = UserPropertyType.STRING
            )
        )
        val distribution: List<ExperimentCondition> = listOf(
            ExperimentCondition(
                property = "name",
                operator = ConditionOperator.Equal.name,
                value = "Nubrick"
            ),
            ExperimentCondition(
                property = "id",
                operator = ConditionOperator.Equal.name,
                value = "XXX"
            ),
        )
        Assert.assertEquals(true, isInDistributionTarget(distribution, properties))
    }

    @Test
    fun isInDistributionTarget_shouldBeFalseWhenThereIncorrectDistribution() {
        val properties: List<UserProperty> = listOf(
            UserProperty(
                name = "name",
                value = "Nubrick",
                type = UserPropertyType.STRING
            ),
            UserProperty(
                name = "id",
                value = "XXX",
                type = UserPropertyType.STRING
            )
        )
        val distribution: List<ExperimentCondition> = listOf(
            ExperimentCondition(
                property = "name",
                operator = ConditionOperator.Equal.name,
                value = "Nubrick"
            ),
            ExperimentCondition(
                property = "id",
                operator = ConditionOperator.Equal.name,
                value = "YYY"
            ),
        )
        Assert.assertEquals(false, isInDistributionTarget(distribution, properties))
    }

    @Test
    fun isInDistributionTarget_shouldBeFalseWhenOperatorIsInvalid() {
        val properties: List<UserProperty> = listOf(
            UserProperty(
                name = "name",
                value = "Nubrick",
                type = UserPropertyType.STRING
            )
        )
        val distribution: List<ExperimentCondition> = listOf(
            ExperimentCondition(
                property = "name",
                operator = "InvalidOperator",
                value = "Nubrick"
            )
        )

        Assert.assertEquals(false, isInDistributionTarget(distribution, properties))
    }

    @Test
    fun extractExperimentConfig_shouldSelectHighestPriority() = runBlocking {
        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig("low", kind = ExperimentKind.POPUP, priority = 1),
                ExperimentConfig("high", kind = ExperimentKind.POPUP, priority = 10),
                ExperimentConfig("mid", kind = ExperimentKind.POPUP, priority = 5),
            )
        )
        Assert.assertEquals("high", extractExperimentConfig(
            configs, listOf(ExperimentKind.POPUP), { _ -> emptyList() }, { _, _ -> true }, { _ -> true }
        )?.id)
    }

    @Test
    fun extractExperimentConfig_tiedPriorityShouldPreferLatestStartDate() = runBlocking {
        val now = getCurrentDate()
        val earlier = now.minusSeconds(2000)
        val later = now.minusSeconds(1000)

        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig("earlier", kind = ExperimentKind.POPUP, startedAt = earlier, priority = 5),
                ExperimentConfig("later", kind = ExperimentKind.POPUP, startedAt = later, priority = 5),
            )
        )
        Assert.assertEquals("later", extractExperimentConfig(
            configs, listOf(ExperimentKind.POPUP), { _ -> emptyList() }, { _, _ -> true }, { _ -> true }
        )?.id)
    }

    @Test
    fun extractExperimentConfig_nilPriorityShouldBeRankedLowest() = runBlocking {
        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig("no_priority", kind = ExperimentKind.POPUP),
                ExperimentConfig("has_priority", kind = ExperimentKind.POPUP, priority = 1),
            )
        )
        Assert.assertEquals("has_priority", extractExperimentConfig(
            configs, listOf(ExperimentKind.POPUP), { _ -> emptyList() }, { _, _ -> true }, { _ -> true }
        )?.id)
    }

    @Test
    fun extractExperimentConfig_shouldFilterByKind() = runBlocking {
        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig("popup", kind = ExperimentKind.POPUP),
                ExperimentConfig("tooltip", kind = ExperimentKind.TOOLTIP),
            )
        )

        val popupOnly = extractExperimentConfig(
            configs, listOf(ExperimentKind.POPUP), { _ -> emptyList() }, { _, _ -> true }, { _ -> true }
        )
        Assert.assertEquals("popup", popupOnly?.id)

        val tooltipOnly = extractExperimentConfig(
            configs, listOf(ExperimentKind.TOOLTIP), { _ -> emptyList() }, { _, _ -> true }, { _ -> true }
        )
        Assert.assertEquals("tooltip", tooltipOnly?.id)

        val both = extractExperimentConfig(
            configs, listOf(ExperimentKind.POPUP, ExperimentKind.TOOLTIP), { _ -> emptyList() }, { _, _ -> true }, { _ -> true }
        )
        Assert.assertNotNull(both)

        val configOnly = extractExperimentConfig(
            configs, listOf(ExperimentKind.CONFIG), { _ -> emptyList() }, { _, _ -> true }, { _ -> true }
        )
        Assert.assertNull(configOnly)
    }

    @Test
    fun extractExperimentConfig_shouldRespectIsNotInFrequency() = runBlocking {
        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig(
                    id = "in-frequency",
                    kind = ExperimentKind.POPUP,
                    frequency = ExperimentFrequency(period = 1),
                ),
                ExperimentConfig(
                    id = "allowed",
                    kind = ExperimentKind.POPUP,
                ),
            )
        )

        val selected = extractExperimentConfig(
            configs = configs,
            kinds = listOf(ExperimentKind.POPUP),
            properties = { emptyList() },
            isNotInFrequency = { experimentId, _ -> experimentId != "in-frequency" },
            isMatchedToUserEventFrequencyConditions = { true },
        )

        Assert.assertEquals("allowed", selected?.id)
    }

    @Test
    fun extractExperimentConfig_shouldRespectEventFrequencyConditions() = runBlocking {
        val blocked = listOf(
            UserEventFrequencyCondition(eventName = "purchase", threshold = 1)
        )
        val configs = ExperimentConfigs(
            configs = listOf(
                ExperimentConfig(
                    id = "needs-event",
                    kind = ExperimentKind.POPUP,
                    eventFrequencyConditions = blocked,
                ),
                ExperimentConfig(
                    id = "no-event-condition",
                    kind = ExperimentKind.POPUP,
                ),
            )
        )

        val selected = extractExperimentConfig(
            configs = configs,
            kinds = listOf(ExperimentKind.POPUP),
            properties = { emptyList() },
            isNotInFrequency = { _, _ -> true },
            isMatchedToUserEventFrequencyConditions = { conditions ->
                conditions.isNullOrEmpty()
            },
        )

        Assert.assertEquals("no-event-condition", selected?.id)
    }

    @Test
    fun isInDistributionTarget_shouldFailClosedWhenPropertyMissing() {
        val distribution = listOf(
            ExperimentCondition(
                property = "missing",
                operator = ConditionOperator.Equal.name,
                value = "x",
            )
        )
        Assert.assertFalse(isInDistributionTarget(distribution, emptyList()))
    }

    @Test
    fun isInDistributionTarget_shouldFailClosedWhenConditionFieldsAreNull() {
        val properties = listOf(
            UserProperty(name = "name", value = "Nubrick", type = UserPropertyType.STRING)
        )

        Assert.assertFalse(
            isInDistributionTarget(
                listOf(ExperimentCondition(property = null, operator = ConditionOperator.Equal.name, value = "Nubrick")),
                properties,
            )
        )
        Assert.assertFalse(
            isInDistributionTarget(
                listOf(ExperimentCondition(property = "name", operator = null, value = "Nubrick")),
                properties,
            )
        )
        Assert.assertFalse(
            isInDistributionTarget(
                listOf(ExperimentCondition(property = "name", operator = ConditionOperator.Equal.name, value = null)),
                properties,
            )
        )
    }

    @Test
    fun isInDistributionTarget_shouldFailClosedWhenOperatorIsUnknown() {
        val properties = listOf(
            UserProperty(name = "name", value = "Nubrick", type = UserPropertyType.STRING)
        )
        val distribution = listOf(
            ExperimentCondition(
                property = "name",
                operator = ConditionOperator.UNKNOWN.name,
                value = "Nubrick",
            )
        )
        Assert.assertFalse(isInDistributionTarget(distribution, properties))
    }

    @Test
    fun extractExperimentVariant_shouldSelectBaselineAtZeroRnd() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "baseline", weight = 1),
            variants = listOf(
                ExperimentVariant(id = "a", weight = 1),
                ExperimentVariant(id = "b", weight = 1),
            )
        )
        Assert.assertEquals("baseline", extractExperimentVariant(config, 0.0)?.id)
    }

    @Test
    fun extractExperimentVariant_shouldSelectLastVariantJustBelowOne() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "baseline", weight = 1),
            variants = listOf(
                ExperimentVariant(id = "a", weight = 1),
                ExperimentVariant(id = "b", weight = 1),
                ExperimentVariant(id = "c", weight = 1),
            )
        )
        // CDF ends at 1.0; rnd in [0, 1) near the top must still land in the last bucket.
        Assert.assertEquals("c", extractExperimentVariant(config, 0.999999999)?.id)
    }

    @Test
    fun extractExperimentVariant_shouldSelectLastVariantAtOne() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "baseline", weight = 1),
            variants = listOf(
                ExperimentVariant(id = "a", weight = 1),
                ExperimentVariant(id = "b", weight = 1),
            )
        )
        Assert.assertEquals("b", extractExperimentVariant(config, 1.0)?.id)
    }

    @Test
    fun extractExperimentVariant_shouldReturnNullWhenAllWeightsAreZero() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "baseline", weight = 0),
            variants = listOf(
                ExperimentVariant(id = "a", weight = 0),
                ExperimentVariant(id = "b", weight = 0),
            )
        )
        Assert.assertNull(extractExperimentVariant(config, 0.0))
        Assert.assertNull(extractExperimentVariant(config, 0.5))
        Assert.assertNull(extractExperimentVariant(config, 1.0))
    }

    @Test
    fun extractExperimentVariant_shouldReturnNullWhenWeightsAreNegativeAndSumToNonPositive() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "baseline", weight = -1),
            variants = listOf(
                ExperimentVariant(id = "a", weight = -2),
            )
        )
        Assert.assertNull(extractExperimentVariant(config, 0.5))
    }

    @Test
    fun extractExperimentVariant_shouldClampNegativeWeightsAndSelectPositiveOnes() {
        val config = ExperimentConfig(
            baseline = ExperimentVariant(id = "baseline", weight = -5),
            variants = listOf(
                ExperimentVariant(id = "a", weight = 1),
                ExperimentVariant(id = "b", weight = 1),
            )
        )
        // After clamping, weights are [0, 1, 1] → equal chance for a and b.
        Assert.assertEquals("a", extractExperimentVariant(config, 0.01)?.id)
        Assert.assertEquals("a", extractExperimentVariant(config, 0.49)?.id)
        Assert.assertEquals("b", extractExperimentVariant(config, 0.51)?.id)
        Assert.assertEquals("b", extractExperimentVariant(config, 0.99)?.id)
    }
}
