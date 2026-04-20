package app.nubrick.nubrick.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VariableUnitTest {
    @Test
    fun buildJsonElement_shouldPreserveBooleanValues() {
        val actual = buildJsonElement(mapOf(
            "enabled" to true,
            "count" to 12L,
        ))

        assertEquals(
            JsonObject(mapOf(
                "enabled" to JsonPrimitive(true),
                "count" to JsonPrimitive(12L),
            )),
            actual
        )
    }
}
