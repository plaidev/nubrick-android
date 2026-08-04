package app.nubrick.nubrick.schema

import app.nubrick.nubrick.data.FailedToDecodeException
import app.nubrick.nubrick.data.decodeJsonElementOrFailure
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PayloadResilienceTest {
    private fun fixture(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "Missing fixture: fixtures/$name"
        }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun decodeUiBlock(name: String): UIBlock? {
        val element = Json.decodeFromString<JsonElement>(fixture(name))
        return UIBlock.decode(element)
    }

    @Test
    fun validFlexDecodes() {
        val block = decodeUiBlock("uiblock_valid_flex.json")
        val flex = block as? UIBlock.UnionUIFlexContainerBlock
            ?: return fail("Expected flex container")
        assertEquals("1", flex.data.id)
        assertEquals(16, flex.data.data?.gap)
        assertEquals(1, flex.data.data?.children?.size)
    }

    @Test
    fun unknownTypenameReturnsNullWithoutThrowing() {
        val block = try {
            decodeUiBlock("uiblock_unknown_typename.json")
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        assertNull(block)
    }

    @Test
    fun partialNullsDoNotThrow() {
        val block = try {
            decodeUiBlock("uiblock_partial_nulls.json")
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        val flex = block as? UIBlock.UnionUIFlexContainerBlock
            ?: return fail("Expected flex container even with null fields")
        assertNull(flex.data.id)
        assertNull(flex.data.data)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val block = try {
            decodeUiBlock("uiblock_unknown_fields.json")
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        val flex = block as? UIBlock.UnionUIFlexContainerBlock
            ?: return fail("Expected flex container")
        assertEquals("1", flex.data.id)
        assertEquals("Hello World", (flex.data.data?.children?.get(0) as? UIBlock.UnionUITextBlock)?.data?.data?.value)
    }

    @Test
    fun partialExperimentConfigsDoNotThrow() {
        val configs = try {
            val element = Json.decodeFromString<JsonElement>(fixture("experiment_configs_partial.json"))
            ExperimentConfigs.decode(element)
        } catch (e: Throwable) {
            fail("decode threw: $e")
            return
        }
        assertNotNull(configs)
        assertEquals(1, configs?.configs?.size)
        assertNull(configs?.configs?.get(0)?.id)
    }

    @Test
    fun invalidJsonIsContainedAsFailedToDecode() {
        val result = decodeJsonElementOrFailure(fixture("invalid.json"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FailedToDecodeException)
    }
}
