@file:OptIn(ExperimentalEventPropertiesApi::class)

package app.nubrick.nubrick

import android.util.Log
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Collections
import java.util.Date
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Canonical, immutable property values returned to the host app. */
@ExperimentalEventPropertiesApi
sealed class EventPropertyValue {
    data class Integer(val value: Long) : EventPropertyValue()
    data class Float(val value: Double) : EventPropertyValue()
    data class String(val value: kotlin.String) : EventPropertyValue()
    data class Boolean(val value: kotlin.Boolean) : EventPropertyValue()
    data class Timestamp(val value: Instant) : EventPropertyValue()
}

private fun EventPropertyValue.encode(): JsonObject = buildJsonObject {
    val (type, value) = when (val property = this@encode) {
        is EventPropertyValue.Integer -> "integer" to JsonPrimitive(property.value)
        is EventPropertyValue.Float -> "float" to JsonPrimitive(property.value)
        is EventPropertyValue.String -> "string" to JsonPrimitive(property.value)
        is EventPropertyValue.Boolean -> "boolean" to JsonPrimitive(property.value)
        is EventPropertyValue.Timestamp -> "timestamp" to JsonPrimitive(property.value.toString())
    }
    put("type", type)
    put("value", value)
}

internal fun encodeEventProperties(properties: Map<String, EventPropertyValue>): JsonObject? =
    if (properties.isEmpty()) null else JsonObject(properties.mapValues { (_, value) -> value.encode() })

/** Validate and copy native inputs into immutable canonical values. */
internal fun normalizeEventProperties(input: Map<String, Any?>): Map<String, EventPropertyValue> {
    val values = linkedMapOf<String, EventPropertyValue>()
    var omittedProperty = false
    for ((key, rawValue) in input) {
        val property = normalize(rawValue)
        if (property == null) {
            omittedProperty = true
            continue
        }
        values[key] = property
    }
    if (omittedProperty) {
        logWarning("Omitted unsupported or lossy event properties")
    }
    return if (values.isEmpty()) emptyMap() else Collections.unmodifiableMap(values)
}

private fun normalize(value: Any?): EventPropertyValue? = try {
    normalizeValue(value)
} catch (_: ArithmeticException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun normalizeValue(value: Any?): EventPropertyValue? = when (value) {
    is EventPropertyValue.Integer -> value
    is EventPropertyValue.Float -> floatProperty(value.value)
    is EventPropertyValue.String -> value
    is EventPropertyValue.Boolean -> value
    is EventPropertyValue.Timestamp -> value
    is String -> EventPropertyValue.String(value)
    is Boolean -> EventPropertyValue.Boolean(value)
    is Byte, is Short, is Int, is Long -> EventPropertyValue.Integer((value as Number).toLong())
    is UByte -> EventPropertyValue.Integer(value.toLong())
    is UShort -> EventPropertyValue.Integer(value.toLong())
    is UInt -> EventPropertyValue.Integer(value.toLong())
    is ULong -> value.takeIf { it <= Long.MAX_VALUE.toULong() }?.let { EventPropertyValue.Integer(it.toLong()) }
    is Float -> floatProperty(value.toDouble())
    is Double -> floatProperty(value)
    is Instant -> EventPropertyValue.Timestamp(value)
    is ZonedDateTime -> EventPropertyValue.Timestamp(value.toInstant())
    is OffsetDateTime -> EventPropertyValue.Timestamp(value.toInstant())
    // SQL date types inherit Date but are not supported event property inputs.
    is java.sql.Timestamp, is java.sql.Date, is java.sql.Time -> null
    is Date -> EventPropertyValue.Timestamp(Instant.ofEpochMilli(value.time))
    is Calendar -> EventPropertyValue.Timestamp(Instant.ofEpochMilli(value.timeInMillis))
    else -> null
}

private fun floatProperty(value: Double): EventPropertyValue? =
    value.takeIf { it.isFinite() }?.let(EventPropertyValue::Float)

private fun logWarning(message: String) {
    runCatching { Log.w("NubrickSDK", message) }
}
