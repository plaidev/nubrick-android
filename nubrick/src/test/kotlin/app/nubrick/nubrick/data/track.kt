package app.nubrick.nubrick.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TrackEventEncodingTest {

    @Test
    fun `test user event encoding contains typename`() {
        val event = TrackUserEvent(name = "test_event")
        val encoded = TrackEvent.UserEvent(event, eventUuid = "event-uuid").encode()

        assertEquals("event", encoded["typename"]?.toString()?.trim('"'))
        assertEquals("test_event", encoded["name"]?.toString()?.trim('"'))
        assertEquals("event-uuid", encoded["eventUuid"]?.toString()?.trim('"'))
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

    @Test
    fun `test survey response request encoding contains tracking context`() {
        val request = SurveyResponseRequest(
            projectId = "project-123",
            experimentId = "exp-123",
            variantId = "var-456",
            userId = "user-789",
            responseData = """{"name":"Ada","accepted":true}""",
            meta = TrackEventMeta(
                appId = "app.id",
                appVersion = "1.2.3",
                osName = "Android",
                osVersion = "36",
                sdkVersion = "0.14.0",
            ),
            timestamp = ZonedDateTime.of(2026, 7, 9, 1, 2, 3, 0, ZoneOffset.UTC),
        )

        val encoded = request.encode()
        val meta = encoded["meta"] as kotlinx.serialization.json.JsonObject

        assertEquals("project-123", encoded["projectId"]?.jsonPrimitive?.content)
        assertEquals("exp-123", encoded["experimentId"]?.jsonPrimitive?.content)
        assertEquals("var-456", encoded["variantId"]?.jsonPrimitive?.content)
        assertEquals("user-789", encoded["userId"]?.jsonPrimitive?.content)
        assertEquals("""{"name":"Ada","accepted":true}""", encoded["response_data"]?.jsonPrimitive?.content)
        assertEquals("android", meta["platform"]?.jsonPrimitive?.content)
    }
}

class TrackingRetryPolicyTest {

    @Test
    fun `network and server failures are retryable`() {
        assertTrue(isRetryableTrackingFailure(IOException("offline")))
        assertTrue(isRetryableTrackingFailure(SocketTimeoutException("timeout")))
        assertTrue(isRetryableTrackingFailure(HttpException(500, "error")))
        assertTrue(isRetryableTrackingFailure(HttpException(503, null)))
        assertTrue(isRetryableTrackingFailure(HttpException(429, "slow down")))
        assertTrue(isRetryableTrackingFailure(HttpException(408, "timeout")))
    }

    @Test
    fun `client failures that retrying cannot fix are not retryable`() {
        assertFalse(isRetryableTrackingFailure(HttpException(400, "bad request")))
        assertFalse(isRetryableTrackingFailure(HttpException(401, "unauthorized")))
        assertFalse(isRetryableTrackingFailure(HttpException(413, "too large")))
        assertFalse(isRetryableTrackingFailure(HttpException(422, "invalid")))
        assertFalse(isRetryableTrackingFailure(NotFoundException()))
        assertFalse(isRetryableTrackingFailure(IllegalArgumentException("bad url")))
    }
}

class TrackingFlushScheduleTest {

    @Test
    fun `immediate pending flush wins over retry backoff`() {
        assertEquals(0L, nextScheduledFlushDelayMs(10_000L, 0L))
        assertEquals(0L, nextScheduledFlushDelayMs(5 * 60 * 1000L, 0L))
    }

    @Test
    fun `retry backoff is used when nothing requested a sooner flush`() {
        assertEquals(10_000L, nextScheduledFlushDelayMs(10_000L, null))
    }

    @Test
    fun `pending follow-up is used after a successful drain`() {
        assertEquals(0L, nextScheduledFlushDelayMs(null, 0L))
        assertEquals(10_000L, nextScheduledFlushDelayMs(null, 10_000L))
    }

    @Test
    fun `sooner pending follow-up wins when both delays are set`() {
        assertEquals(1_000L, nextScheduledFlushDelayMs(10_000L, 1_000L))
    }

    @Test
    fun `no follow-up is scheduled when neither delay is set`() {
        assertNull(nextScheduledFlushDelayMs(null, null))
    }
}

class StoredNativeCrashTest {

    @Test
    fun `stored crash round-trips exceptions and timestamp`() {
        val timestamp = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)
        val exceptions = listOf(
            ExceptionRecord(type = "NPE", message = "boom", callStacks = emptyList()),
        )

        val decoded = decodeStoredNativeCrash(encodeStoredNativeCrash(exceptions, timestamp))

        assertNotNull(decoded)
        assertEquals("NPE", decoded!!.exceptions.single().type)
        assertEquals("boom", decoded.exceptions.single().message)
        assertEquals(timestamp.toInstant(), decoded.timestamp.toInstant())
    }

    @Test
    fun `legacy crash json without timestamp still decodes exceptions`() {
        val exceptions = listOf(
            ExceptionRecord(type = "IllegalStateException", message = "old", callStacks = emptyList()),
        )

        val decoded = decodeStoredNativeCrash(Json.encodeToString(exceptions))

        assertNotNull(decoded)
        assertEquals("IllegalStateException", decoded!!.exceptions.single().type)
        assertEquals("old", decoded.exceptions.single().message)
    }

    @Test
    fun `corrupt crash json is rejected`() {
        assertNull(decodeStoredNativeCrash("{"))
        assertNull(decodeStoredNativeCrash(""))
    }
}

class CrashTrackingEventsTest {

    @Test
    fun `derived crash events reuse stable ids for the same crash`() {
        val crash = nubrickCrash(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC))

        assertEquals(
            crashTrackingEvents(crash).map { it.eventUuid },
            crashTrackingEvents(crash).map { it.eventUuid },
        )
    }

    @Test
    fun `derived crash event keeps the original crash timestamp`() {
        val timestamp = ZonedDateTime.of(2026, 8, 14, 6, 32, 0, 0, ZoneOffset.UTC)
        val encoded = crashTrackingEvents(nubrickCrash(timestamp))
            .filterIsInstance<TrackEvent.CrashEvent>()
            .single()
            .encode()

        assertEquals(
            timestamp.toInstant().toString(),
            encoded["timestamp"]?.jsonPrimitive?.content,
        )
    }

    private fun nubrickCrash(timestamp: ZonedDateTime) = TrackCrashEvent(
        exceptions = listOf(
            ExceptionRecord(
                type = "RuntimeException",
                message = "sdk crash",
                callStacks = listOf(
                    StackFrame(null, "app.nubrick.nubrick.Sdk", "init", 10),
                ),
            ),
        ),
        timestamp = timestamp,
    )
}

class TrackEventMetaTest {

    @Test
    fun `track event meta round-trips through json`() {
        val meta = TrackEventMeta(
            appId = "app.id",
            appVersion = "1.2.3",
            osName = "Android",
            osVersion = "36",
            sdkVersion = "0.16.4",
        )

        assertEquals(meta, decodeTrackEventMeta(meta.encode()))
    }
}
