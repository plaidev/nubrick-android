package app.nubrick.nubrick.data.user

import android.content.Context
import android.content.SharedPreferences
import app.nubrick.nubrick.schema.BuiltinUserProperty
import app.nubrick.nubrick.schema.UserPropertyType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class UtilsUnitTest {
    @After
    fun tearDown() {
        DATETIME_OFFSET = 0
    }

    @Test
    fun testSyncDateFromHttpDateHeader_shouldWork() {
        DATETIME_OFFSET = 0
        val now = System.currentTimeMillis()
        val tomorrow = Date(now + (24 * 60 * 60 * 1000))
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val formattedDate = formatter.format(tomorrow)

        syncDateFromHttpDateHeader(now, now, formattedDate)
        val offset = DATETIME_OFFSET
        val diff = abs(offset - 24 * 60 * 60 * 1000)

        assertTrue("time offset should be around 24 hours", diff < 5000)
    }

    @Test
    fun testGetCurrentDate() {
        DATETIME_OFFSET = 24 * 60 * 60 * 1000
        val deviceCurrent = System.currentTimeMillis()
        val syncedCurrent = getCurrentDate().toInstant().toEpochMilli()
        val diff = (syncedCurrent - deviceCurrent) / 1000

        assertTrue("(diff - 24 hours) should be around 2 sec", abs(diff - 24 * 60 * 60) < 2)
    }

    @Test
    fun toUserProperties_shouldCalculateLocalMinuteAsMinutesSinceMidnight() {
        val target = ZonedDateTime.now()
            .withHour(12)
            .withMinute(34)
            .withSecond(30)
            .withNano(0)
        DATETIME_OFFSET = target.toInstant().toEpochMilli() - System.currentTimeMillis()
        val user = NubrickUser(context = mockContext(), seed = 0)

        val localMinute = user.toUserProperties().first {
            it.name == BuiltinUserProperty.localMinute.toString()
        }

        assertEquals((12 * 60 + 34).toString(), localMinute.value)
    }

    @Test
    fun getProperties_shouldNotAddUserIdToCustomProperties() {
        val user = NubrickUser(context = mockContext(), seed = 0)

        user.getProperties()

        val userIdProperties = user.toUserProperties().filter {
            it.name == BuiltinUserProperty.userId.toString()
        }
        assertEquals(1, userIdProperties.size)
    }

    @Test
    fun setProperty_shouldStoreDateAsIsoInstant() {
        val user = NubrickUser(context = mockContext(), seed = 0)

        user.setProperty("date", Date(1317826080000L))

        assertEquals("2011-10-05T14:48:00Z", user.getProperty("date"))
        assertEquals(
            UserPropertyType.STRING,
            user.toUserProperties().first { it.name == "date" }.type
        )
    }

    @Test
    fun setProperty_shouldStoreInstantAsIsoInstant() {
        val user = NubrickUser(context = mockContext(), seed = 0)

        user.setProperty("instant", Instant.ofEpochSecond(1317826080L))

        assertEquals("2011-10-05T14:48:00Z", user.getProperty("instant"))
        assertEquals(
            UserPropertyType.STRING,
            user.toUserProperties().first { it.name == "instant" }.type
        )
    }

    @Test
    fun setProperty_shouldStoreLocalDateTimeAsIsoInstant() {
        val user = NubrickUser(context = mockContext(), seed = 0)
        val localDateTime = LocalDateTime.of(2011, 10, 5, 14, 48)

        user.setProperty("localDateTime", localDateTime)

        assertEquals(
            localDateTime.atZone(ZoneId.systemDefault()).toInstant().toString(),
            user.getProperty("localDateTime")
        )
        assertEquals(
            UserPropertyType.STRING,
            user.toUserProperties().first { it.name == "localDateTime" }.type
        )
    }

    @Test
    fun setProperty_shouldStoreLocalDateAsIsoInstant() {
        val user = NubrickUser(context = mockContext(), seed = 0)
        val localDate = LocalDate.of(2011, 10, 5)

        user.setProperty("localDate", localDate)

        assertEquals(
            localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
            user.getProperty("localDate")
        )
        assertEquals(
            UserPropertyType.STRING,
            user.toUserProperties().first { it.name == "localDate" }.type
        )
    }

    @Test
    fun setProperty_shouldKeepCustomPropertyTypesAsString() {
        val user = NubrickUser(context = mockContext(), seed = 0)

        user.setProperty("int", 123)
        user.setProperty("double", 12.3)
        user.setProperty("bool", true)
        user.setProperty("string", "123")

        val props = user.toUserProperties().associateBy { it.name }
        assertEquals(UserPropertyType.STRING, props["int"]?.type)
        assertEquals(UserPropertyType.STRING, props["double"]?.type)
        assertEquals(UserPropertyType.STRING, props["bool"]?.type)
        assertEquals(UserPropertyType.STRING, props["string"]?.type)
    }

    @Test
    fun toUserProperties_shouldDefaultOldCustomPropertiesToString() {
        val user = NubrickUser(
            context = mockContext(mapOf("NATIVEBRIK_CUSTOM_date" to "2011-10-05T14:48:00Z")),
            seed = 0
        )

        val date = user.toUserProperties().first { it.name == "date" }
        assertEquals(UserPropertyType.STRING, date.type)
    }

    @Test
    fun retention_shouldReturnZeroWhenPropertyIsNotAnInt() {
        val user = NubrickUser(context = mockContext(), seed = 0)
        val setBaseProperty = NubrickUser::class.java.getDeclaredMethod(
            "setBaseProperty",
            String::class.java,
            String::class.java,
        )
        setBaseProperty.isAccessible = true
        setBaseProperty.invoke(user, BuiltinUserProperty.retentionPeriod.toString(), "not-a-number")

        assertEquals(0, user.retention)
    }

    @Test
    fun getNormalizedUserRnd_shouldBeDeterministicForSameSeeds() {
        val user = NubrickUser(context = mockContext(), seed = 0)

        val first = user.getNormalizedUserRnd(42)
        val second = user.getNormalizedUserRnd(42)

        assertEquals(first, second, 0.0)
    }

    @Test
    fun getNormalizedUserRnd_shouldStayInHalfOpenUnitInterval() {
        val user = NubrickUser(context = mockContext(), seed = 0)

        repeat(50) { experimentSeed ->
            val rnd = user.getNormalizedUserRnd(experimentSeed)
            assertTrue("rnd=$rnd seed=$experimentSeed", rnd >= 0.0 && rnd < 1.0)
        }
    }

    @Test
    fun getNormalizedUserRnd_shouldVaryByExperimentSeed() {
        val user = NubrickUser(context = mockContext(), seed = 0)

        val a = user.getNormalizedUserRnd(1)
        val b = user.getNormalizedUserRnd(2)

        assertNotEquals(a, b)
    }

    @Test
    fun comeBack_shouldCountUpOnNextUtcDay_keepSameDay_andResetOnGap() {
        val store = mutableMapOf<String, Any>()
        setSyncedInstant(Instant.parse("2024-01-10T12:00:00Z"))
        val user = NubrickUser(context = mockContext(store = store), seed = 0)
        assertEquals(0, user.retention)

        setSyncedInstant(Instant.parse("2024-01-11T12:00:00Z"))
        user.comeBack()
        assertEquals(1, user.retention)

        setSyncedInstant(Instant.parse("2024-01-11T18:00:00Z"))
        user.comeBack()
        assertEquals(1, user.retention)

        setSyncedInstant(Instant.parse("2024-01-13T12:00:00Z"))
        user.comeBack()
        assertEquals(0, user.retention)
    }

    @Test
    fun userId_shouldPersistAcrossInstances() {
        val store = mutableMapOf<String, Any>()
        val first = NubrickUser(context = mockContext(store = store), seed = 0)
        val userId = first.id

        val second = NubrickUser(context = mockContext(store = store), seed = 0)
        assertEquals(userId, second.id)
    }

    @Test
    fun setUserId_shouldUpdateAndPersist() {
        val store = mutableMapOf<String, Any>()
        val user = NubrickUser(context = mockContext(store = store), seed = 0)

        user.setUserId("custom-user")
        assertEquals("custom-user", user.id)
        assertEquals("custom-user", user.getProperty(BuiltinUserProperty.userId.toString()))

        val reloaded = NubrickUser(context = mockContext(store = store), seed = 0)
        assertEquals("custom-user", reloaded.id)
    }

    @Test
    fun setProperties_shouldPersistCustomValuesAndUserId() {
        val store = mutableMapOf<String, Any>()
        val user = NubrickUser(context = mockContext(store = store), seed = 0)

        user.setProperties(
            mapOf(
                BuiltinUserProperty.userId.toString() to "from-map",
                "plan" to "pro",
            )
        )

        assertEquals("from-map", user.id)
        assertEquals("pro", user.getProperty("plan"))

        val reloaded = NubrickUser(context = mockContext(store = store), seed = 0)
        assertEquals("from-map", reloaded.id)
        assertEquals("pro", reloaded.getProperty("plan"))
    }

    @Test
    fun userSeed_shouldPersistAndKeepRndStableAcrossInstances() {
        val store = mutableMapOf<String, Any>()
        val first = NubrickUser(context = mockContext(store = store), seed = 7)
        val rnd = first.getNormalizedUserRnd(99)

        val second = NubrickUser(context = mockContext(store = store), seed = 12345)
        assertEquals(rnd, second.getNormalizedUserRnd(99), 0.0)
    }

    private fun setSyncedInstant(instant: Instant) {
        DATETIME_OFFSET = instant.toEpochMilli() - System.currentTimeMillis()
    }

    private fun mockContext(
        allPreferences: Map<String, Any> = emptyMap(),
        store: MutableMap<String, Any>? = null,
    ): Context {
        val backing = store ?: mutableMapOf()
        backing.putAll(allPreferences)

        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)

        `when`(context.packageName).thenReturn("app.nubrick.test")
        `when`(context.getSharedPreferences("app.nubrick.test.nubrik.io.user", Context.MODE_PRIVATE))
            .thenReturn(preferences)
        `when`(preferences.getString(anyString(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<String?>(1)
            backing[key] as? String ?: default
        }
        `when`(preferences.getInt(anyString(), anyInt())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<Int>(1)
            backing[key] as? Int ?: default
        }
        `when`(preferences.getLong(anyString(), anyLong())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<Long>(1)
            when (val value = backing[key]) {
                is Long -> value
                is Int -> value.toLong()
                else -> default
            }
        }
        `when`(preferences.all).thenReturn(backing.toMap())
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenAnswer { invocation ->
            backing[invocation.getArgument(0)] = invocation.getArgument<String>(1)
            editor
        }
        `when`(editor.putInt(anyString(), anyInt())).thenAnswer { invocation ->
            backing[invocation.getArgument(0)] = invocation.getArgument<Int>(1)
            editor
        }
        `when`(editor.putLong(anyString(), anyLong())).thenAnswer { invocation ->
            backing[invocation.getArgument(0)] = invocation.getArgument<Long>(1)
            editor
        }
        `when`(editor.apply()).then { }

        return context
    }
}
