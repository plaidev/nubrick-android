package app.nubrick.nubrick

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InitializeContainmentAndroidTest {
    @Before
    fun setup() {
        NubrickSDK.resetForTest()
    }

    @After
    fun teardown() {
        NubrickSDK.resetForTest()
    }

    @Test
    fun initializeFailureDoesNotThrowAndLeavesSdkUninitialized() {
        val realContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val failingContext = object : ContextWrapper(realContext) {
            override fun getApplicationContext(): Context {
                throw RuntimeException("boom")
            }
        }

        // Must not crash the host process.
        NubrickSDK.initialize(
            context = failingContext,
            config = Config(projectId = "test-project-id"),
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

        NubrickSDK.initialize(
            context = failingContext,
            config = Config(projectId = "test-project-id"),
        )
        assertNull(NubrickSDK.getUserId())

        NubrickSDK.initialize(
            context = realContext,
            config = Config(projectId = "test-project-id"),
        )
        NubrickSDK.setUserId("user-after-retry")
        assertEquals("user-after-retry", NubrickSDK.getUserId())
    }
}
