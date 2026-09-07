package app.nubrick.nubrick.component.provider.event

import app.nubrick.nubrick.data.FormValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDispatcherTest {
    @Test
    fun `required fields treat missing and empty values as invalid`() {
        assertTrue(requiredFieldsAreInvalid(listOf("answer"), emptyMap()))
        assertTrue(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to FormValue.Str(""))))
        assertTrue(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to FormValue.StrList(emptyList()))))
        assertTrue(requiredFieldsAreInvalid(listOf("tos"), mapOf("tos" to FormValue.Bool(false))))
    }

    @Test
    fun `required fields treat present values as valid`() {
        assertFalse(requiredFieldsAreInvalid(listOf("answer"), mapOf("answer" to FormValue.Str("yes"))))
        assertFalse(requiredFieldsAreInvalid(
            listOf("answer"),
            mapOf("answer" to FormValue.StrList(listOf("yes"))),
        ))
        assertFalse(requiredFieldsAreInvalid(listOf("tos"), mapOf("tos" to FormValue.Bool(true))))
        assertFalse(requiredFieldsAreInvalid(null, emptyMap()))
        assertFalse(requiredFieldsAreInvalid(emptyList(), emptyMap()))
    }

    @Test
    fun `required text fields treat regex mismatches as invalid`() {
        val email = """^\S+@\S+$"""
        assertTrue(
            requiredFieldsAreInvalid(
                listOf("email"),
                mapOf("email" to FormValue.Str("not-an-email", regex = email)),
            ),
        )
        assertFalse(
            requiredFieldsAreInvalid(
                listOf("email"),
                mapOf("email" to FormValue.Str("user@example.com", regex = email)),
            ),
        )
        assertFalse(
            requiredFieldsAreInvalid(
                listOf("email"),
                mapOf("email" to FormValue.Str("not-an-email")),
            ),
        )
    }
}

