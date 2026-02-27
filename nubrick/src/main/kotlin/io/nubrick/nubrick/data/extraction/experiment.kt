package io.nubrick.nubrick.data.extraction

import io.nubrick.nubrick.data.user.UserProperty
import io.nubrick.nubrick.data.user.getCurrentDate
import io.nubrick.nubrick.schema.ConditionOperator
import io.nubrick.nubrick.schema.ExperimentCondition
import io.nubrick.nubrick.schema.ExperimentConfig
import io.nubrick.nubrick.schema.ExperimentConfigs
import io.nubrick.nubrick.schema.ExperimentFrequency
import io.nubrick.nubrick.schema.ExperimentKind
import io.nubrick.nubrick.schema.ExperimentVariant
import io.nubrick.nubrick.schema.UserEventFrequencyCondition

internal fun extractComponentId(variant: ExperimentVariant): String? {
    val configs = variant.configs ?: return null
    if (configs.isEmpty()) return null
    val id = configs.firstOrNull()
    return id?.value
}

internal fun extractExperimentVariant(config: ExperimentConfig, normalizedUserRnd: Double): ExperimentVariant? {
    val baseline = config.baseline ?: return null
    val variants = config.variants ?: return baseline
    if (variants.isEmpty()) return baseline

    val baselineWeight = baseline.weight ?: 1
    val weights: MutableList<Int> = mutableListOf(baselineWeight)
    variants.forEach {
        val weight = it.weight ?: 1
        weights.add(weight)
    }
    val weightSum = weights.sum()

    // here is calculation of the picking from the probability.
    // X is selected when p_X(x) >= F_X(x)
    // where F_X(x) := Integral p_X(t)dt, the definition of cumulative distribution function
    var cumulativeDistributionValue: Double = 0.0
    var selectedVariantIndex: Int = 0

    for ((index, weight) in weights.withIndex()) {
        val probability: Double = weight.toDouble() / weightSum.toDouble()
        cumulativeDistributionValue += probability
        if (cumulativeDistributionValue >= normalizedUserRnd) {
            selectedVariantIndex = index
            break
        }
    }

    if (selectedVariantIndex == 0) return baseline
    if (variants.count() > selectedVariantIndex - 1) {
        return variants[selectedVariantIndex - 1]
    }
    return null
}

internal fun extractExperimentConfig(
    configs: ExperimentConfigs,
    kinds: List<ExperimentKind>,
    properties: (seed: Int?) -> List<UserProperty>,
    isNotInFrequency: (experimentId: String, frequency: ExperimentFrequency?) -> Boolean,
    isMatchedToUserEventFrequencyConditions: (conditions: List<UserEventFrequencyCondition>?) -> Boolean,
): ExperimentConfig? {
    val configs = configs.configs ?: return null
    if (configs.isEmpty()) return null
    val currentDate = getCurrentDate()

    // Filter configs that match the requested kinds, are within their time window, and match all conditions
    val matched = configs.filter { config ->
        val configKind = config.kind ?: return@filter false
        if (!kinds.contains(configKind)) return@filter false

        val startedAt = config.startedAt
        if (startedAt != null) {
            if (currentDate.isBefore(startedAt)) {
                return@filter false
            }
        }
        val endedAt = config.endedAt
        if (endedAt != null) {
            if (currentDate.isAfter(endedAt)) {
                return@filter false
            }
        }
        val experimentId = config.id ?: ""
        if (!isNotInFrequency(experimentId, config.frequency)) {
            return@filter false
        }
        if (!isMatchedToUserEventFrequencyConditions(config.eventFrequencyConditions)) {
            return@filter false
        }
        if (!isInDistributionTarget(
                distribution = config.distribution,
                properties = properties(config.seed),
            )
        ) {
            return@filter false
        }
        true
    }
    // Pick the highest-priority config. If tied, prefer the latest start date.
    // Configs without a priority are ranked lowest; without a start date, earliest.
    return matched.maxWithOrNull(
        compareBy<ExperimentConfig> { it.priority ?: Int.MIN_VALUE }
            .thenComparing { a, b ->
                val aDate = a.startedAt
                val bDate = b.startedAt
                when {
                    aDate == null && bDate == null -> 0
                    aDate == null -> -1
                    bDate == null -> 1
                    else -> aDate.compareTo(bDate)
                }
            }
    )
}

internal fun isInDistributionTarget(distribution: List<ExperimentCondition>?, properties: List<UserProperty>): Boolean {
    if (distribution == null) return true
    if (distribution.isEmpty()) return true
    val props = properties.associateBy { property -> property.name }
    val foundNotMatched = distribution.firstOrNull { condition ->
        val propKey = condition.property ?: return@firstOrNull true
        val conditionValue = condition.value ?: return@firstOrNull true
        val op = condition.operator ?: return@firstOrNull true
        val prop = props[propKey] ?: return@firstOrNull true
        !comparePropWithConditionValue(
            prop = prop,
            asType = condition.asType,
            value = conditionValue,
            op = ConditionOperator.valueOf(op)
        )
    }
    return foundNotMatched == null
}
