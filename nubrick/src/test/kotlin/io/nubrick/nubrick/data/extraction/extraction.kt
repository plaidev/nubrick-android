package io.nubrick.nubrick.data.extraction

import io.nubrick.nubrick.data.user.UserProperty
import io.nubrick.nubrick.data.user.getCurrentDate
import io.nubrick.nubrick.schema.UserPropertyType
import io.nubrick.nubrick.schema.ConditionOperator
import io.nubrick.nubrick.schema.ExperimentCondition
import io.nubrick.nubrick.schema.ExperimentConfig
import io.nubrick.nubrick.schema.ExperimentConfigs
import io.nubrick.nubrick.schema.ExperimentKind
import io.nubrick.nubrick.schema.ExperimentVariant
import io.nubrick.nubrick.schema.VariantConfig
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
    fun extractExperimentConfig_shouldWork() {
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
    fun extractExperimentConfig_shouldFilterOnlyRunningConfig() {
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
    fun extractExperimentConfig_shouldSelectHighestPriority() {
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
    fun extractExperimentConfig_tiedPriorityShouldPreferLatestStartDate() {
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
    fun extractExperimentConfig_nilPriorityShouldBeRankedLowest() {
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
    fun extractExperimentConfig_shouldFilterByKind() {
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
}
