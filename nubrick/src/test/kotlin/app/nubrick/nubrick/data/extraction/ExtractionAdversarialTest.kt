package app.nubrick.nubrick.data.extraction

import app.nubrick.nubrick.data.user.UserProperty
import app.nubrick.nubrick.schema.ConditionOperator
import app.nubrick.nubrick.schema.ExperimentCondition
import app.nubrick.nubrick.schema.ExperimentConfig
import app.nubrick.nubrick.schema.ExperimentConfigs
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.ExperimentVariant
import app.nubrick.nubrick.schema.UserPropertyType
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ExtractionAdversarialTest {
    private fun fixture(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "Missing fixture: fixtures/$name"
        }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun decodeConfigs(name: String): ExperimentConfigs? {
        val element = Json.decodeFromString<JsonElement>(fixture(name))
        return ExperimentConfigs.decode(element)
    }

    private fun extractConfig(configs: ExperimentConfigs): ExperimentConfig? {
        return extractExperimentConfig(
            configs = configs,
            kinds = listOf(ExperimentKind.POPUP, ExperimentKind.EMBED),
            properties = { emptyList() },
            isNotInFrequency = { _, _ -> true },
            isMatchedToUserEventFrequencyConditions = { _ -> true },
        )
    }

    @Test
    fun emptyConfigsFixtureExtractsNullWithoutThrowing() {
        val configs = try {
            decodeConfigs("experiment_configs_empty.json")
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        assertNotNull(configs)
        val extracted = try {
            extractConfig(configs!!)
        } catch (e: Throwable) {
            fail("extract threw: $e")
            return
        }
        assertNull(extracted)
    }

    @Test
    fun nullConfigsFixtureExtractsNullWithoutThrowing() {
        val configs = try {
            decodeConfigs("experiment_configs_null_configs.json")
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        assertNotNull(configs)
        val extracted = try {
            extractConfig(configs!!)
        } catch (e: Throwable) {
            fail("extract threw: $e")
            return
        }
        assertNull(extracted)
    }

    @Test
    fun partialConfigsFixtureExtractsNullWithoutThrowing() {
        val configs = try {
            decodeConfigs("experiment_configs_partial.json")
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        assertNotNull(configs)
        val extracted = try {
            extractConfig(configs!!)
        } catch (e: Throwable) {
            fail("extract threw: $e")
            return
        }
        assertNull(extracted)
    }

    @Test
    fun zeroWeightsVariantDoesNotThrow() {
        val configs = try {
            decodeConfigs("experiment_configs_zero_weights.json")
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        val config = try {
            extractConfig(configs!!)
        } catch (e: Throwable) {
            fail("extractConfig threw: $e")
            return
        }
        assertNotNull(config)
        val variant = try {
            extractExperimentVariant(config!!, normalizedUserRnd = 0.5)
        } catch (e: Throwable) {
            fail("extractExperimentVariant threw: $e")
            return
        }
        // Soft: either baseline or null is acceptable; must not crash.
        if (variant != null) {
            assertEquals("baseline", variant.id)
        }
    }

    @Test
    fun missingBaselineReturnsNull() {
        val variant = try {
            extractExperimentVariant(
                ExperimentConfig(id = "1", kind = ExperimentKind.POPUP, baseline = null),
                normalizedUserRnd = 0.5,
            )
        } catch (e: Throwable) {
            fail("extract threw: $e")
            return
        }
        assertNull(variant)
    }

    @Test
    fun emptyVariantsReturnsBaseline() {
        val variant = try {
            extractExperimentVariant(
                ExperimentConfig(
                    id = "1",
                    kind = ExperimentKind.POPUP,
                    baseline = ExperimentVariant(id = "baseline", weight = 1),
                    variants = emptyList(),
                ),
                normalizedUserRnd = 0.5,
            )
        } catch (e: Throwable) {
            fail("extract threw: $e")
            return
        }
        assertEquals("baseline", variant?.id)
    }

    @Test
    fun extractComponentIdNullOrEmptyConfigsReturnsNull() {
        assertNull(extractComponentId(ExperimentVariant(configs = null)))
        assertNull(extractComponentId(ExperimentVariant(configs = emptyList())))
    }

    @Test
    fun invalidRegexDistributionDoesNotThrow() {
        val properties = listOf(
            UserProperty(name = "name", value = "Nubrick", type = UserPropertyType.STRING),
        )
        val distribution = listOf(
            ExperimentCondition(
                property = "name",
                operator = ConditionOperator.Regex.name,
                value = "[", // invalid regex
            ),
        )
        val matched = try {
            isInDistributionTarget(distribution, properties)
        } catch (e: Throwable) {
            fail("isInDistributionTarget threw: $e")
            return
        }
        assertEquals(false, matched)
    }
}
