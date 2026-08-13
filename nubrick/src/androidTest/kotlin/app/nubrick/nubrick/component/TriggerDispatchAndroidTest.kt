package app.nubrick.nubrick.component

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.data.user.NubrickUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.mockito.stubbing.Answer

@RunWith(AndroidJUnit4::class)
class TriggerDispatchAndroidTest {
    @Test
    fun dispatchDoesNotReportUnhandledCoroutineExceptions() {
        val handled = CountDownLatch(1)
        val unhandled = AtomicReference<Throwable?>(null)
        val exceptionLatch = CountDownLatch(1)
        val container = mock(
            Container::class.java,
            withSettings().defaultAnswer(Answer { invocation ->
                if (invocation.method.name == "handleNubrickEvent") {
                    handled.countDown()
                    throw RuntimeException("boom")
                }
                null
            }),
        )
        // NubrickUser is a final Kotlin class; Mockito cannot mock it on device.
        // This path never touches user after handleNubrickEvent throws.
        val user = NubrickUser(InstrumentationRegistry.getInstrumentation().targetContext)

        val holder = TriggerStateHolder(
            container = container,
            user = user,
            scope = CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Unconfined +
                    CoroutineExceptionHandler { _, throwable ->
                        unhandled.set(throwable)
                        exceptionLatch.countDown()
                    },
            ),
        )

        holder.dispatch(NubrickEvent("test"))

        assertTrue(handled.await(1, TimeUnit.SECONDS))
        // Give the coroutine a moment; containment means the handler must never fire.
        exceptionLatch.await(200, TimeUnit.MILLISECONDS)
        assertNull(unhandled.get())
    }

    @Test
    fun dispatchCallsHandleNubrickEventOnMainThread() {
        val handled = CountDownLatch(1)
        val dispatchThread = AtomicReference<Thread>()
        val container = mock(
            Container::class.java,
            withSettings().defaultAnswer(Answer { invocation ->
                when (invocation.method.name) {
                    "handleNubrickEvent" -> {
                        dispatchThread.set(Thread.currentThread())
                        handled.countDown()
                        null
                    }
                    // Avoid NPE after the callback: production continues to fetchTriggerContent.
                    "fetchTriggerContent" -> Result.failure<Any>(UnsupportedOperationException("unused"))
                    else -> null
                }
            }),
        )
        val user = NubrickUser(InstrumentationRegistry.getInstrumentation().targetContext)
        val holder = TriggerStateHolder(
            container = container,
            user = user,
            // Match production: NubrickRuntime uses Dispatchers.IO.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        holder.dispatch(NubrickEvent("test"))

        assertTrue(handled.await(2, TimeUnit.SECONDS))
        assertEquals(Looper.getMainLooper().thread, dispatchThread.get())
    }
}
