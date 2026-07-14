package app.nubrick.nubrick.component.provider.event

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDispatcherTest {
    @Test
    fun `required fields treat missing and empty values as invalid`() {
        assertTrue(requiredFieldsAreInvalid(listOf("answer"), emptyMap()))
        assertTrue(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to JsonPrimitive(""))))
        assertTrue(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to JsonArray(emptyList()))))
        assertTrue(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to JsonNull)))
    }

    @Test
    fun `required fields treat present values as valid`() {
        assertFalse(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to JsonPrimitive("yes"))))
        assertFalse(requiredFieldsAreInvalid(
            listOf("answer"),
            mapOf("answer" to JsonArray(listOf(JsonPrimitive("yes")))),
        ))
        assertFalse(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to JsonPrimitive(false))))
        assertFalse(requiredFieldsAreInvalid(null, emptyMap()))
        assertFalse(requiredFieldsAreInvalid(emptyList(), emptyMap()))
    }

}
