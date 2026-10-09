package app.nubrick.nubrick

import app.nubrick.nubrick.data.TrackUserEvent
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Date
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalEventPropertiesApi::class)
class EventPropertiesTest {
    @Test fun `native scalars encode without losing types or integer precision`() {
        val instant = Instant.parse("2026-09-29T01:02:03Z")
        val event = NubrickEvent("purchase", mapOf(
            "byte" to 2.toByte(), "short" to 3.toShort(), "int" to 1,
            "long" to 9007199254740993L, "float" to 1.25f, "double" to 12.5,
            "bool" to true, "zero" to 0, "one" to 1, "text" to "a\"b\n/🙂",
            "instant" to instant, "zoned" to instant.atZone(ZoneOffset.ofHours(9)),
            "offset" to OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(9)),
            "date" to Date.from(instant), "calendar" to Calendar.getInstance().apply { timeInMillis = instant.toEpochMilli() },
        ))
        val json = encodeEventProperties(event.properties)!!
        assertEquals("integer", json["long"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(9007199254740993L, json["long"]!!.jsonObject["value"]!!.jsonPrimitive.long)
        assertEquals("integer", json["int"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("float", json["double"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(12.5, json["double"]!!.jsonObject["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals("boolean", json["bool"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(true, json["bool"]!!.jsonObject["value"]!!.jsonPrimitive.boolean)
        assertEquals("0", json["zero"]!!.jsonObject["value"].toString())
        assertEquals("1", json["one"]!!.jsonObject["value"].toString())
        assertEquals("string", json["text"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("a\"b\n/🙂", json["text"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        for (key in listOf("instant", "zoned", "offset", "date", "calendar")) {
            assertEquals("timestamp", json[key]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(instant.toString(), json[key]!!.jsonObject["value"]!!.jsonPrimitive.content)
        }
        assertEquals(json, TrackUserEvent("purchase", properties = json).encode()["properties"])
    }

    @Test fun `invalid values are omitted without dropping event`() {
        val event = NubrickEvent("event", mapOf("array" to listOf(1), "object" to mapOf("a" to 1),
            "null" to null, "custom" to Any(), "ok" to false))
        assertEquals(mapOf("ok" to EventPropertyValue.Boolean(false)), event.properties)
        assertNull(encodeEventProperties(NubrickEvent("event", mapOf("bad" to null)).properties))
        assertFalse(TrackUserEvent("event").encode().containsKey("properties"))
    }

    @Test fun `properties are immutable snapshots`() {
        val input = mutableMapOf<String, Any?>("count" to 0)
        val event = NubrickEvent("e", input)
        input["count"] = 99
        assertEquals(EventPropertyValue.Integer(0), event.properties["count"])
        val date = Date(0)
        val withDate = NubrickEvent("e", mapOf("at" to date))
        date.time = 1000
        assertEquals(EventPropertyValue.Timestamp(Instant.EPOCH), withDate.properties["at"])
        assertThrows(UnsupportedOperationException::class.java) {
            (event.properties as MutableMap<String, EventPropertyValue>)["count"] = EventPropertyValue.Integer(99)
        }
        assertEquals(event.properties, event.copy(name = "copy").properties)
        assertEquals(NubrickEvent("e"), NubrickEvent("e", emptyMap()))
    }

    @Test fun `arbitrary precision inputs are unsupported`() {
        val invalid = mapOf(
            "integer" to BigInteger.ONE, "largeInteger" to BigInteger("9007199254740993"),
            "decimal" to BigDecimal("1.25"), "priceDecimal" to BigDecimal("19.99"), "zeroDecimal" to BigDecimal.ZERO,
            "binaryDecimal" to BigDecimal(0.1),
            "atomicInteger" to java.util.concurrent.atomic.AtomicInteger(1),
        )
        val event = NubrickEvent("e", invalid + ("ok" to 19.99))
        assertEquals(mapOf("ok" to EventPropertyValue.Float(19.99)), event.properties)
        assertEquals(setOf("ok"), encodeEventProperties(event.properties)!!.keys)
        assertNull(encodeEventProperties(NubrickEvent("e", invalid).properties))
    }

    @Test fun `numeric boundaries survive JSON round trip`() {
        val event = NubrickEvent("e", mapOf(
            "min" to Long.MIN_VALUE,
            "max" to Long.MAX_VALUE,
            "largestFloat" to Double.MAX_VALUE,
            "smallestFloat" to Double.MIN_VALUE,
            "zero" to 0.0,
        ))
        assertEquals(EventPropertyValue.Integer(Long.MIN_VALUE), event.properties["min"])
        assertEquals(EventPropertyValue.Integer(Long.MAX_VALUE), event.properties["max"])
        assertEquals(EventPropertyValue.Float(0.0), event.properties["zero"])
        val json = encodeEventProperties(event.properties)!!
        val reloaded = Json.parseToJsonElement(Json.encodeToString(JsonObject.serializer(), json)).jsonObject
        assertEquals(Long.MIN_VALUE, reloaded["min"]!!.jsonObject["value"]!!.jsonPrimitive.long)
        assertEquals(Long.MAX_VALUE, reloaded["max"]!!.jsonObject["value"]!!.jsonPrimitive.long)
        assertEquals(Double.MAX_VALUE, reloaded["largestFloat"]!!.jsonObject["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals(Double.MIN_VALUE, reloaded["smallestFloat"]!!.jsonObject["value"]!!.jsonPrimitive.double, 0.0)
        assertEquals(json, reloaded)
    }

    @Test fun `numbers outside Long and Double range are omitted`() {
        val invalid = mapOf(
            "integerOverflow" to Long.MAX_VALUE.toULong() + 1uL,
            "unsignedOverflow" to ULong.MAX_VALUE,
            "doubleOverflow" to Double.POSITIVE_INFINITY,
            "negativeDoubleOverflow" to Double.NEGATIVE_INFINITY,
            "nan" to Double.NaN,
            "floatInfinity" to Float.POSITIVE_INFINITY,
            "typedNaN" to EventPropertyValue.Float(Double.NaN),
            "typedInfinity" to EventPropertyValue.Float(Double.POSITIVE_INFINITY),
        )
        val event = NubrickEvent("e", invalid + ("ok" to 12.5))
        assertEquals(mapOf("ok" to EventPropertyValue.Float(12.5)), event.properties)
        assertEquals(setOf("ok"), encodeEventProperties(event.properties)!!.keys)
        assertNull(encodeEventProperties(NubrickEvent("e", invalid).properties))
    }

    @Test fun `invalid calendar is omitted without dropping event`() {
        val invalid = Calendar.getInstance().apply {
            clear()
            isLenient = false
            set(2026, Calendar.FEBRUARY, 30)
        }
        val event = NubrickEvent("e", mapOf("invalid" to invalid, "valid" to Date(0), "ok" to true))
        assertEquals("e", event.name)
        assertEquals(mapOf("valid" to EventPropertyValue.Timestamp(Instant.EPOCH), "ok" to EventPropertyValue.Boolean(true)), event.properties)
        val tracked = TrackUserEvent(event.name, properties = encodeEventProperties(event.properties)).encode()
        assertEquals("e", tracked["name"]!!.jsonPrimitive.content)
        assertEquals(setOf("valid", "ok"), tracked["properties"]!!.jsonObject.keys)
        assertNull(encodeEventProperties(NubrickEvent("e", mapOf("invalid" to invalid)).properties))
    }

    @Test fun `host properties are canonical across native numeric types`() {
        val event = NubrickEvent("e", mapOf(
            "byte" to 1.toByte(), "short" to 1.toShort(), "int" to 1, "long" to 1L,
            "ubyte" to 1.toUByte(), "ushort" to 1.toUShort(), "uint" to 1u, "ulong" to 1uL,
            "typed" to EventPropertyValue.Integer(1), "float" to 0.1f, "double" to 0.1f.toDouble(), "doubleTenth" to 0.1,
        ))
        for (key in listOf("byte", "short", "int", "long", "ubyte", "ushort", "uint", "ulong", "typed")) {
            assertEquals(EventPropertyValue.Integer(1), event.properties[key])
        }
        assertEquals(EventPropertyValue.Float(0.1f.toDouble()), event.properties["float"])
        assertEquals(event.properties["float"], event.properties["double"])
        assertEquals(EventPropertyValue.Float(0.1), event.properties["doubleTenth"])
        assertEquals(NubrickEvent("e", mapOf("n" to 1)), NubrickEvent("e", mapOf("n" to 1L)))
    }

    @Test fun `timestamp precision and native timestamp output are preserved`() {
        val instant = Instant.parse("2026-09-29T01:02:03.123456789Z")
        val event = NubrickEvent("e", mapOf(
            "at" to instant, "zoned" to instant.atZone(ZoneOffset.ofHours(9)),
            "offset" to instant.atOffset(ZoneOffset.ofHours(9)),
        ))
        val json = encodeEventProperties(event.properties)!!
        for (key in listOf("at", "zoned", "offset")) {
            assertEquals(EventPropertyValue.Timestamp(instant), event.properties[key])
            assertEquals(instant.toString(), json[key]!!.jsonObject["value"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `SQL date types are omitted without dropping supported properties`() {
        val event = NubrickEvent("e", mapOf(
            "sqlDate" to java.sql.Date(0), "sqlTime" to java.sql.Time(0),
            "sqlTimestamp" to java.sql.Timestamp(0).apply { nanos = 123456789 },
            "sqlMin" to java.sql.Timestamp(Long.MIN_VALUE),
            "sqlMax" to java.sql.Timestamp(Long.MAX_VALUE).apply { nanos = 999999999 },
            "date" to Date(0),
        ))
        assertEquals("e", event.name)
        assertEquals(mapOf("date" to EventPropertyValue.Timestamp(Instant.EPOCH)), event.properties)
        assertEquals(setOf("date"), encodeEventProperties(event.properties)!!.keys)
    }

    @Test fun `large properties remain in event snapshot`() {
        val largeString = "x".repeat(500 * 1024 + 1)
        val largeKey = "k".repeat(4 * 1024 + 1)
        val event = NubrickEvent("e", mapOf("value" to largeString, largeKey to true))
        assertEquals(mapOf("value" to EventPropertyValue.String(largeString), largeKey to EventPropertyValue.Boolean(true)), event.properties)

        val properties = (0 until 200).associate { "property_$it" to "x".repeat(32) }
        assertEquals(properties.mapValues { EventPropertyValue.String(it.value) }, NubrickEvent("e", properties).properties)
    }
}
