package app.nubrick.nubrick.component

import androidx.test.ext.junit.runners.AndroidJUnit4
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

        val holder = TriggerStateHolder(
            container = container,
            user = mock(NubrickUser::class.java),
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
}
