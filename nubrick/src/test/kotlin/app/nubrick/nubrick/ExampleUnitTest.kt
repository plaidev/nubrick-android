package app.nubrick.nubrick

import app.nubrick.nubrick.remoteconfig.RemoteConfigResult
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `createConfig bridges Java-friendly listeners`() {
        var receivedEvent: Event? = null
        var receivedDispatch: NubrickEvent? = null
        val config = NubrickSDK.createConfig(
            projectId = "project-id",
            onEventListener = NubrickGlobalEventListener { receivedEvent = it },
            onDispatchListener = NubrickDispatchListener { receivedDispatch = it },
            trackCrashes = false,
        )

        val event = Event(name = "completed", deepLink = null, payload = null)
        val dispatch = NubrickEvent(name = "open-popup")
        config.onEvent?.invoke(event)
        config.onDispatch?.invoke(dispatch)

        assertEquals(event, receivedEvent)
        assertEquals(dispatch, receivedDispatch)
        assertFalse(config.trackCrashes)

        assertFalse(NubrickSDK.createConfig("project-id", false).trackCrashes)
    }

    @Test
    fun `remote config result exposes failures without Kotlin Result`() {
        val error = IllegalStateException("Unavailable")
        val result = RemoteConfigResult(error = error)

        assertFalse(result.isSuccess)
        assertNull(result.value)
        assertEquals(error, result.error)
    }
}
