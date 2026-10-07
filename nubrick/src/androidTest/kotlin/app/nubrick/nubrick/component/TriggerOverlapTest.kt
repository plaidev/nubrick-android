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
    private class Fixture(onTooltip: ((String, String, String?, String) -> Unit)? = null) {
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
            CoroutineScope(job + Dispatchers.IO),
            onTooltip = onTooltip)
        suspend fun drain() { withTimeout(3000) { job.children.toList().joinAll() } }
        fun content(id: String = "experiment") = ExperimentContent(experimentId = id, variantId = "variant", root = UIRootBlock(id = id))
        suspend fun awaitModal(id: String) = withTimeout(3000) {
            while (!withContext(Dispatchers.Main) { holder.modalContents.any { it.experimentId == id } }) delay(10)
        }
    }

    @Test
    fun tooltipBlocksPopupAndOtherTooltipsUntilRecordingAndDismissalFinish() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.holder.startExperiment("tooltip"))
            assertFalse(f.holder.startExperiment("other-tooltip"))
            f.holder.dispatch(NubrickEvent("blocked"))
            f.drain()
            assertEquals(listOf("blocked"), f.recorded)
            assertTrue(f.fetched.isEmpty())
            val recording = CompletableDeferred<Unit>()
            f.displayRecordBlocker.set(recording)
            f.holder.recordDisplay("tooltip", "tooltip-experiment", "variant")
            f.holder.finishExperiment("tooltip")
            assertFalse(f.holder.startExperiment("next"))
            recording.complete(Unit)
            f.drain()
            assertEquals(listOf("tooltip-experiment"), f.shown)
            f.response("popup").complete(Result.success(f.content() to ExperimentKind.POPUP))
            f.holder.dispatch(NubrickEvent("popup"))
            f.drain()
            assertFalse(f.holder.startExperiment("next"))
            withContext(Dispatchers.Main) {
                f.holder.finishExperiment("tooltip")
                assertEquals(1, f.holder.modalContents.size)
                f.holder.handleDismiss(f.holder.modalContents.single())
            }
            assertTrue(f.holder.startExperiment("next"))
            f.holder.finishExperiment("next")
        } finally { f.job.cancelAndJoin() }
    }

    @Test
    fun recordingFailureReleasesDismissedTooltip() = runBlocking {
        val f = Fixture()
        try {
            val blocker = CompletableDeferred<Unit>()
            f.displayRecordBlocker.set(blocker)
            assertTrue(f.holder.startExperiment("tooltip"))
            f.holder.recordDisplay("tooltip", "experiment", "variant")
            f.holder.finishExperiment("tooltip")
            blocker.completeExceptionally(IllegalStateException("storage failed"))
            f.drain()
            assertTrue(f.holder.startExperiment("next"))
        } finally { f.job.cancelAndJoin() }
    }

    @Test
    fun tooltipClaimDiscardsPopupFetchThatCompletesLate() = runBlocking {
        val f = Fixture()
        try {
            f.holder.dispatch(NubrickEvent("slow"))
            f.awaitFetch("slow")
            assertTrue(f.holder.startExperiment("tooltip"))
            f.response("slow").complete(Result.success(f.content() to ExperimentKind.POPUP))
            f.drain()
            withContext(Dispatchers.Main) { assertTrue(f.holder.modalContents.isEmpty()) }
            assertTrue(f.shown.isEmpty())
            f.holder.finishExperiment("tooltip")
            assertTrue(f.holder.startExperiment("next"))
        } finally { f.job.cancelAndJoin() }
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
                f.holder.handleDismiss(winner)
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
            val presented = withContext(Dispatchers.Main) { f.holder.modalContents.single() }
            withContext(Dispatchers.Main) {
                f.holder.handleShown(presented)
                f.holder.handleShown(presented)
            }
            f.drain()
            assertEquals(listOf("experiment"), f.shown)
            withContext(Dispatchers.Main) { f.holder.handleDismiss(presented) }
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
                val presented = f.holder.modalContents.single()
                f.holder.handleShown(presented)
                f.holder.handleDismiss(presented)
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
    @Test
    fun staleDismissalCannotFinishAnotherSessionWithTheSameRoot() = runBlocking {
        val fixture = Fixture()
        try {
            val content = fixture.content()
            fixture.response("first").complete(Result.success(content to ExperimentKind.POPUP))
            fixture.holder.dispatch(NubrickEvent("first"))
            fixture.drain()
            val first = fixture.holder.modalContents.single()
            assertEquals("experiment", first.root.id)
            withContext(Dispatchers.Main) { fixture.holder.handleDismiss(first) }
            fixture.response("second").complete(Result.success(content to ExperimentKind.POPUP))
            fixture.holder.dispatch(NubrickEvent("second"))
            fixture.drain()
            val second = fixture.holder.modalContents.single()
            assertSame(first.root, second.root)
            assertNotEquals(first.sessionId, second.sessionId)
            withContext(Dispatchers.Main) { fixture.holder.handleDismiss(first) }
            fixture.holder.finishExperiment(first.sessionId!!)
            assertSame(second, fixture.holder.modalContents.single())
            assertTrue(fixture.holder.ownsExperiment(second.sessionId!!))
        } finally { fixture.job.cancelAndJoin() }
    }

    @Test
    fun nativeDispatchReservesTooltipBeforeNotifyingFlutter() = runBlocking {
        var sessionId: String? = null
        lateinit var fixture: Fixture
        fixture = Fixture(onTooltip = { data, _, _, id ->
            sessionId = id
            assertEquals("experiment", org.json.JSONObject(data).getString("id"))
            assertTrue(fixture.holder.ownsExperiment(sessionId!!))
            assertFalse(fixture.holder.startExperiment("overlap"))
        })
        try {
            fixture.response("tooltip").complete(Result.success(fixture.content() to ExperimentKind.TOOLTIP))
            fixture.holder.dispatch(NubrickEvent("tooltip"))
            fixture.drain()
            assertNotNull(sessionId)
            assertNotEquals("experiment", sessionId)
            fixture.holder.dispatch(NubrickEvent("blocked"))
            fixture.drain()
            assertEquals(listOf("tooltip"), fixture.fetched)
            fixture.holder.finishExperiment(sessionId!!)
            assertTrue(fixture.holder.startExperiment("next"))
        } finally { fixture.job.cancelAndJoin() }
    }

}
