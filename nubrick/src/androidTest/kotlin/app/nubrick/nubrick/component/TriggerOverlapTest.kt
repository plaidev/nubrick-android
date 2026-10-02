package app.nubrick.nubrick.component

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.ExperimentContent
import app.nubrick.nubrick.data.NotFoundException
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.UIRootBlock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TriggerOverlapTest {
    private class Fixture {
        val job = SupervisorJob()
        private val starts = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val responses = ConcurrentHashMap<String, CompletableDeferred<Result<Pair<ExperimentContent, ExperimentKind>>>>()
        fun response(trigger: String) = responses.getOrPut(trigger) { CompletableDeferred() }
        suspend fun awaitFetch(trigger: String) = withTimeout(3000) {
            starts.getOrPut(trigger) { CompletableDeferred() }.await()
        }
        val fetched = CopyOnWriteArrayList<String>()
        val recorded = CopyOnWriteArrayList<String>()
        val shown = CopyOnWriteArrayList<String>()
        val callbacks = CopyOnWriteArrayList<String>()
        val displayRecordBlocker = AtomicReference<CompletableDeferred<Unit>?>(null)
        val container = object : FakeContainer(Result.failure(NotFoundException())) {
            override fun handleNubrickEvent(it: NubrickEvent) { callbacks.add(it.name) }
            override suspend fun fetchTriggerContent(trigger: String, kinds: List<ExperimentKind>): Result<Pair<ExperimentContent, ExperimentKind>> {
                fetched.add(trigger)
                starts.getOrPut(trigger) { CompletableDeferred() }.complete(Unit)
                return response(trigger).await()
            }
            override suspend fun recordTriggerEvent(name: String, sourceExperimentId: String?): Boolean {
                recorded.add(name)
                return true
            }
            override suspend fun recordDisplayedTriggerContent(experimentId: String, variantId: String) {
                shown.add(experimentId)
                displayRecordBlocker.get()?.await()
            }
        }
        val holder = TriggerStateHolder(container,
            NubrickUser(InstrumentationRegistry.getInstrumentation().targetContext),
            CoroutineScope(job + Dispatchers.IO))
        suspend fun drain() { withTimeout(3000) { job.children.toList().joinAll() } }
        fun content(id: String = "experiment") = ExperimentContent(experimentId = id, variantId = "variant", root = UIRootBlock(id = id))
        suspend fun awaitModal(id: String) = withTimeout(3000) {
            while (!withContext(Dispatchers.Main) { holder.modalContents.any { it.experimentId == id } }) delay(10)
        }
    }

    @Test
    fun slowFetchDoesNotBlockAnotherExperimentFromStarting() = runBlocking {
        val f = Fixture()
        try {
            f.holder.dispatch(NubrickEvent("slow"))
            f.awaitFetch("slow")
            f.holder.dispatch(NubrickEvent("fast"))
            f.awaitFetch("fast")
            f.response("fast").complete(Result.success(f.content("fast") to ExperimentKind.POPUP))
            f.awaitModal("fast")
            // A still-loading experiment can fail without affecting the displayed winner.
            f.response("slow").complete(Result.failure(NotFoundException()))
            f.drain()
            assertEquals(listOf("slow", "fast"), f.fetched)
            assertEquals(listOf("slow", "fast"), f.recorded)
            withContext(Dispatchers.Main) {
                assertEquals("fast", f.holder.modalContents.single().experimentId)
            }
        } finally { f.job.cancelAndJoin() }
    }

    @Test
    fun competingCompletedFetchesPresentOnlyOneAndNeverReplayTheLoser() = runBlocking {
        val f = Fixture()
        try {
            f.holder.dispatch(NubrickEvent("one"))
            f.holder.dispatch(NubrickEvent("two"))
            f.awaitFetch("one")
            f.awaitFetch("two")
            f.response("one").complete(Result.success(f.content("one") to ExperimentKind.POPUP))
            f.response("two").complete(Result.success(f.content("two") to ExperimentKind.POPUP))
            f.drain()
            assertEquals(setOf("one", "two"), f.fetched.toSet())
            assertTrue(f.shown.isEmpty())
            withContext(Dispatchers.Main) {
                val winner = f.holder.modalContents.single()
                f.holder.handleShown(winner)
                f.holder.handleDismiss(winner.root)
                assertTrue(f.holder.modalContents.isEmpty())
            }
            f.drain()
            assertEquals(1, f.shown.size)
            assertEquals(2, f.fetched.size)
            withContext(Dispatchers.Main) { assertTrue(f.holder.modalContents.isEmpty()) }
        } finally { f.job.cancelAndJoin() }
    }

    @Test
    fun popupOwnsReservationUntilDismissAndRecordsDisplayOnlyOnce() = runBlocking {
        val f = Fixture()
        try {
            val content = f.content()
            f.response("first").complete(Result.success(content to ExperimentKind.POPUP))
            f.holder.dispatch(NubrickEvent("first"))
            f.drain()
            assertTrue(f.shown.isEmpty())
            f.holder.dispatch(NubrickEvent("overlap"))
            f.drain()
            assertEquals(listOf("first"), f.fetched)
            withContext(Dispatchers.Main) {
                f.holder.handleShown(content)
                f.holder.handleShown(content)
            }
            f.drain()
            assertEquals(listOf("experiment"), f.shown)
            withContext(Dispatchers.Main) { f.holder.handleDismiss(content.root) }
            f.response("fresh").complete(Result.failure(NotFoundException()))
            f.holder.dispatch(NubrickEvent("fresh"))
            f.drain()
            assertEquals(listOf("first", "fresh"), f.fetched)
        } finally { f.job.cancelAndJoin() }
    }

    @Test
    fun dismissalKeepsReservationUntilDisplayHistoryIsRecorded() = runBlocking {
        val f = Fixture()
        try {
            val blocker = CompletableDeferred<Unit>()
            f.displayRecordBlocker.set(blocker)
            val content = f.content()
            f.response("first").complete(Result.success(content to ExperimentKind.POPUP))
            f.holder.dispatch(NubrickEvent("first"))
            f.awaitModal("experiment")
            withContext(Dispatchers.Main) {
                f.holder.handleShown(content)
                f.holder.handleDismiss(content.root)
            }
            withTimeout(3000) {
                while (f.shown.isEmpty()) delay(10)
            }

            f.holder.dispatch(NubrickEvent("overlap"))
            withTimeout(3000) {
                while (!f.recorded.contains("overlap")) delay(10)
            }
            assertEquals(listOf("first"), f.fetched)

            blocker.complete(Unit)
            f.drain()
            f.response("fresh").complete(Result.failure(NotFoundException()))
            f.holder.dispatch(NubrickEvent("fresh"))
            f.drain()
            assertEquals(listOf("first", "fresh"), f.fetched)
        } finally { f.job.cancelAndJoin() }
    }

    @Test
    fun cancelledFetchDoesNotBlockFreshTrigger() = runBlocking {
        val f = Fixture()
        try {
            f.holder.dispatch(NubrickEvent("first"))
            f.awaitFetch("first")
            f.job.children.toList().forEach { it.cancelAndJoin() }
            f.response("fresh").complete(Result.failure(NotFoundException()))
            f.holder.dispatch(NubrickEvent("fresh"))
            f.drain()
            assertEquals(listOf("first", "fresh"), f.fetched)
        } finally { f.job.cancelAndJoin() }
    }

    @Test
    fun overlappingPredefinedBatchRecordsEveryEventWithoutFetching() = runBlocking {
        val f = Fixture()
        try {
            f.response("first").complete(Result.success(f.content() to ExperimentKind.POPUP))
            f.holder.dispatch(NubrickEvent("first"))
            f.drain()
            val dispatch = TriggerStateHolder::class.java.getDeclaredMethod("dispatchPredefinedEvents", List::class.java)
            dispatch.isAccessible = true
            dispatch.invoke(f.holder, listOf(NubrickEvent("boot"), NubrickEvent("return")))
            f.drain()
            assertEquals(listOf("first"), f.fetched)
            assertEquals(listOf("first", "boot", "return"), f.recorded)
            assertTrue(f.callbacks.containsAll(listOf("boot", "return")))
        } finally { f.job.cancelAndJoin() }
    }
}
