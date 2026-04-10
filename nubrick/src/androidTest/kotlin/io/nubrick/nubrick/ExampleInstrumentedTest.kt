package io.nubrick.nubrick

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Before
    fun setup() {
        NubrickSDK.resetForTest()
        clearUserPreferences()
    }

    @After
    fun teardown() {
        NubrickSDK.resetForTest()
        clearUserPreferences()
    }

    private fun clearUserPreferences() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        appContext
            .getSharedPreferences("${appContext.packageName}.nubrik.io.user", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.nubrick.nubrick.test", appContext.packageName)
    }

    @Test
    fun initializeAndReinitialize_doNotThrow() {
        NubrickSDK.resetForTest()
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        NubrickSDK.initialize(
            context = appContext,
            config = Config(projectId = "test-project-id")
        )
        NubrickSDK.initialize(
            context = appContext,
            config = Config(projectId = "another-project-id")
        )

        assertTrue(true)
    }

    @Test
    fun userApis_uninitialized_returnNullAndDoNotThrow() {
        NubrickSDK.resetForTest()

        assertNull(NubrickSDK.getUserId())
        assertNull(NubrickSDK.getUserProperty("plan"))
        assertTrue(NubrickSDK.getUserProperties().isEmpty())

        NubrickSDK.setUserId("user-before-init")
        NubrickSDK.setUserProperty("plan", "pro")
        NubrickSDK.setUserProperties(mapOf("tier" to "gold"))

        assertNull(NubrickSDK.getUserId())
        assertNull(NubrickSDK.getUserProperty("plan"))
        assertTrue(NubrickSDK.getUserProperties().isEmpty())
    }

    @Test
    fun userApis_initialized_roundtrip() {
        NubrickSDK.resetForTest()
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        NubrickSDK.initialize(
            context = appContext,
            config = Config(projectId = "test-project-id")
        )

        NubrickSDK.setUserId("user-123")
        NubrickSDK.setUserProperty("plan", "pro")
        NubrickSDK.setUserProperty("age", 20)
        NubrickSDK.setUserProperty("isMember", true)

        assertEquals("user-123", NubrickSDK.getUserId())
        assertEquals("pro", NubrickSDK.getUserProperty("plan"))
        assertEquals("20", NubrickSDK.getUserProperty("age"))
        assertEquals("true", NubrickSDK.getUserProperty("isMember"))
    }

    @Test
    fun userPluralApis_initialized_roundtrip() {
        NubrickSDK.resetForTest()
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        NubrickSDK.initialize(
            context = appContext,
            config = Config(projectId = "test-project-id")
        )

        NubrickSDK.setUserProperties(
            mapOf(
                "userId" to "user-456",
                "plan" to "basic",
                "age" to 30,
                "isMember" to false
            )
        )

        val props = NubrickSDK.getUserProperties()
        assertEquals("user-456", NubrickSDK.getUserId())
        assertEquals("basic", props["plan"])
        assertEquals("30", props["age"])
        assertEquals("false", props["isMember"])
        assertEquals("user-456", props["userId"])
    }
}
