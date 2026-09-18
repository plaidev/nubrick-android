package app.nubrick.nubrick.component

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleObserverRegistrationTest {
    @Test
    fun attachRegistersOnlyOnceAcrossOverlayRecreation() {
        val lifecycle = RecordingLifecycle()
        val registration = LifecycleObserverRegistration(object : LifecycleObserver {})

        registration.attach(lifecycle)
        registration.attach(lifecycle)

        assertEquals(1, lifecycle.addedObserverCount)
    }

    @Test
    fun detachRemovesTheRegisteredObserverOnce() {
        val lifecycle = RecordingLifecycle()
        val registration = LifecycleObserverRegistration(object : LifecycleObserver {})

        registration.attach(lifecycle)
        registration.detach()
        registration.detach()

        assertEquals(1, lifecycle.removedObserverCount)
    }

    private class RecordingLifecycle : Lifecycle() {
        var addedObserverCount = 0
            private set
        var removedObserverCount = 0
            private set

        override fun addObserver(observer: LifecycleObserver) {
            addedObserverCount += 1
        }

        override fun removeObserver(observer: LifecycleObserver) {
            removedObserverCount += 1
        }

        override val currentState: State = State.INITIALIZED
    }
}
