package app.nubrick.nubrick

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InitializeContainmentAndroidTest {
    @Before
    fun setup() {
        resetSdk()
    }

    @After
    fun teardown() {
        resetSdk()
    }

    private fun resetSdk() = runBlocking {
        NubrickSDK.resetForTest()
    }

    @Test
    fun initializeFailureReturnsFalseAndLeavesSdkUninitialized() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val failingContext = object : ContextWrapper(realContext) {
            override fun getApplicationContext(): Context {
                throw RuntimeException("boom")
            }
        }

        assertFalse(
            NubrickSDK.initialize(
                context = failingContext,
                config = Config(projectId = "test-project-id"),
            )
        )

        assertNull(NubrickSDK.getUserId())
        NubrickSDK.setUserId("should-be-ignored")
        assertNull(NubrickSDK.getUserId())
    }

    @Test
    fun initializeCanSucceedAfterPreviousFailure() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val failingContext = object : ContextWrapper(realContext) {
            override fun getApplicationContext(): Context {
                throw RuntimeException("boom")
            }
        }

        assertFalse(
            NubrickSDK.initialize(
                context = failingContext,
                config = Config(projectId = "test-project-id"),
            )
        )
        assertNull(NubrickSDK.getUserId())

        assertTrue(
            NubrickSDK.initialize(
                context = realContext,
                config = Config(projectId = "test-project-id"),
            )
        )
        NubrickSDK.setUserId("user-after-retry")
        assertEquals("user-after-retry", NubrickSDK.getUserId())
    }

    @Test
    fun initializeAndResetSucceedOffTheMainThread() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val executor = Executors.newSingleThreadExecutor()
        try {
            val initialized = executor.submit<Boolean> {
                NubrickSDK.initialize(
                    context = realContext,
                    config = Config(projectId = "test-project-id"),
                )
            }.get(5, TimeUnit.SECONDS)
            assertTrue(initialized)

            executor.submit {
                runBlocking { NubrickSDK.resetForTest() }
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun initializeReturnsTrueWhenAlreadyInitialized() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        assertTrue(
            NubrickSDK.initialize(
                context = realContext,
                config = Config(projectId = "test-project-id"),
            )
        )
        assertTrue(
            NubrickSDK.initialize(
                context = realContext,
                config = Config(projectId = "another-project-id"),
            )
        )
    }
}
