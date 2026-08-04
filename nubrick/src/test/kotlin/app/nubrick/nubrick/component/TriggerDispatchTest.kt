package app.nubrick.nubrick.component

import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.data.user.NubrickUser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.mockito.stubbing.Answer

class TriggerDispatchTest {
    @Test
    fun dispatchSwallowsExceptionsFromHandleNubrickEvent() {
        val handled = CountDownLatch(1)
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
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        // Must not rethrow — dispatch is a public crash boundary.
        holder.dispatch(NubrickEvent("test"))

        assertTrue(handled.await(1, TimeUnit.SECONDS))
    }
}
