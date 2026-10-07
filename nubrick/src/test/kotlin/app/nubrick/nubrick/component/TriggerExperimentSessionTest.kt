package app.nubrick.nubrick.component

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TriggerExperimentSessionTest {
    @Test
    fun tooltipAndPopupShareSlotAndStaleCleanupCannotReleaseNewOwner() {
        val session = TriggerExperimentSession()
        assertTrue(session.start("tooltip"))
        assertFalse(session.start("popup"))
        session.finish("other")
        assertTrue(session.isActive())
        session.finish("tooltip")
        assertTrue(session.start("popup"))
        session.finish("tooltip")
        assertFalse(session.start("next-tooltip"))
        session.finish("popup")
        assertTrue(session.start("next-tooltip"))
    }

    @Test
    fun endingWaitsForRecordingAndRejectsDuplicateOrLateRecording() {
        val session = TriggerExperimentSession()
        assertTrue(session.start("tooltip"))
        assertTrue(session.beginRecording("tooltip"))
        assertFalse(session.beginRecording("tooltip"))
        session.finish("tooltip")
        assertTrue(session.isActive())
        assertFalse(session.start("popup"))
        session.finishRecording("tooltip")
        assertFalse(session.isActive())
        assertFalse(session.beginRecording("tooltip"))
        assertTrue(session.start("popup"))
        session.finishRecording("tooltip")
        assertTrue(session.isActive())
    }

    @Test
    fun recordingCompletionKeepsUndismissedTooltipActive() {
        val session = TriggerExperimentSession()
        assertTrue(session.start("tooltip"))
        assertTrue(session.beginRecording("tooltip"))
        session.finishRecording("tooltip")
        assertTrue(session.isActive())
        assertFalse(session.beginRecording("tooltip"))
        session.finish("tooltip")
        assertFalse(session.isActive())
    }

    @Test
    fun concurrentStartsHaveExactlyOneWinner() {
        val session = TriggerExperimentSession()
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val threads = (1..20).map { id ->
            Thread {
                start.await()
                if (session.start("session-$id")) winners.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }

    @Test
    fun recordingStateIsSharedRegardlessOfExperimentType() {
        val session = TriggerExperimentSession()
        repeat(2) {
            assertTrue(session.start("session"))
            assertTrue(session.beginRecording("session"))
            assertFalse(session.beginRecording("session"))
            session.finishRecording("session")
            assertTrue(session.isActive())
            assertFalse(session.beginRecording("session"))
            session.finish("session")
            assertFalse(session.isActive())
            assertFalse(session.beginRecording("session"))
        }
    }

}
