package app.nubrick.nubrick.data

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackEventChannelTest {

    @Test
    fun `test channel drops events when full`() {
        val channel = Channel<String>(capacity = 3)

        // Fill the channel
        assertTrue(channel.trySend("event1").isSuccess)
        assertTrue(channel.trySend("event2").isSuccess)
        assertTrue(channel.trySend("event3").isSuccess)

        // Next send should fail (channel full)
        assertFalse(channel.trySend("event4").isSuccess)

        channel.close()
    }

    @Test
    fun `test channel accepts events after consuming`() {
        val channel = Channel<String>(capacity = 2)

        assertTrue(channel.trySend("event1").isSuccess)
        assertTrue(channel.trySend("event2").isSuccess)
        assertFalse(channel.trySend("event3").isSuccess)

        // Consume one event
        val result = channel.tryReceive()
        assertTrue(result.isSuccess)
        assertEquals("event1", result.getOrNull())

        // Now we can send again
        assertTrue(channel.trySend("event3").isSuccess)

        channel.close()
    }
}

class TrackEventEncodingTest {

    @Test
    fun `test user event encoding contains typename`() {
        val event = TrackUserEvent(name = "test_event")
        val encoded = event.encode()

        assertEquals("event", encoded["typename"]?.toString()?.trim('"'))
        assertEquals("test_event", encoded["name"]?.toString()?.trim('"'))
    }

    @Test
    fun `test experiment event encoding contains all fields`() {
        val event = TrackExperimentEvent(
            experimentId = "exp-123",
            variantId = "var-456"
        )
        val encoded = event.encode()

        assertEquals("experiment", encoded["typename"]?.toString()?.trim('"'))
        assertEquals("exp-123", encoded["experimentId"]?.toString()?.trim('"'))
        assertEquals("var-456", encoded["variantId"]?.toString()?.trim('"'))
    }

    @Test
    fun `test crash severity parsing`() {
        assertEquals(CrashSeverity.ERROR, CrashSeverity.from(null))
        assertEquals(CrashSeverity.ERROR, CrashSeverity.from(""))
        assertEquals(CrashSeverity.ERROR, CrashSeverity.from("invalid"))
        assertEquals(CrashSeverity.DEBUG, CrashSeverity.from("debug"))
        assertEquals(CrashSeverity.DEBUG, CrashSeverity.from("DEBUG"))
        assertEquals(CrashSeverity.FATAL, CrashSeverity.from("fatal"))
    }

    @Test
    fun `test crash severity isErrorLevel`() {
        assertFalse(CrashSeverity.DEBUG.isErrorLevel)
        assertFalse(CrashSeverity.INFO.isErrorLevel)
        assertFalse(CrashSeverity.WARNING.isErrorLevel)
        assertTrue(CrashSeverity.ERROR.isErrorLevel)
        assertTrue(CrashSeverity.FATAL.isErrorLevel)
    }
}
