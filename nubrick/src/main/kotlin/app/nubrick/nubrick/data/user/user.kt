package app.nubrick.nubrick.data.user

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.nubrick.nubrick.VERSION
import app.nubrick.nubrick.schema.BuiltinUserProperty
import app.nubrick.nubrick.schema.UserPropertyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random


internal fun getNubrickUserSharedPreferences(context: Context): SharedPreferences? {
    return context.getSharedPreferences(
        context.packageName + ".nubrik.io.user",
        Context.MODE_PRIVATE
    )
}

internal const val USER_SEED_MAX = 100000000
internal const val USER_SEED_KEY = "NATIVEBRIK_USER_SEED"

@Volatile
internal var DATETIME_OFFSET: Long = 0

internal fun getCurrentDate(): ZonedDateTime {
    val currentMillis = ZonedDateTime.now().toInstant().toEpochMilli()
    return ZonedDateTime.ofInstant(
        Instant.ofEpochMilli(currentMillis + DATETIME_OFFSET),
        ZoneId.systemDefault()
    )
}

internal fun syncDateFromHttpDateHeader(t0: Long, t1: Long, serverDateHeader: String?) {
    if (serverDateHeader == null) return
    val serverTime = try {
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("GMT")
        formatter.parse(serverDateHeader)?.time ?: return
    } catch (e: Exception) {
        return
    }

    val networkDelay = (t1 - t0) / 2
    val estimatedServerTime = serverTime + networkDelay

    DATETIME_OFFSET = estimatedServerTime - t1
}

internal fun getToday(): ZonedDateTime {
    val now = getCurrentDate()
    return now.truncatedTo(ChronoUnit.DAYS)
}

internal fun formatISO8601(time: ZonedDateTime): String {
    return time.format(DateTimeFormatter.ISO_INSTANT)
}

private fun formatUserPropertyValue(value: Any): String {
    return when (value) {
        is ZonedDateTime -> value.format(DateTimeFormatter.ISO_INSTANT)
        is OffsetDateTime -> value.toInstant().toString()
        is Instant -> value.toString()
        is LocalDateTime -> value.atZone(ZoneId.systemDefault()).toInstant().toString()
        is LocalDate -> value.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        is Date -> value.toInstant().toString()
        is Calendar -> value.toInstant().toString()
        else -> value.toString()
    }
}

internal data class UserProperty(
    val name: String,
    val value: String,
    val type: UserPropertyType,
)

private const val USER_CUSTOM_PROPERTY_KEY_PREFIX = "NATIVEBRIK_CUSTOM_"

internal data class NubrickUserState(
    val properties: Map<String, String> = emptyMap(),
    val customProperties: Map<String, String> = emptyMap(),
) {
    val id: String
        get() = properties[BuiltinUserProperty.userId.toString()] ?: ""

    val templateProperties: Map<String, String>
        get() = customProperties + (BuiltinUserProperty.userId.toString() to id)
}

class NubrickUser {
    private val _state = MutableStateFlow(NubrickUserState())
    internal val state: StateFlow<NubrickUserState> = _state.asStateFlow()
    internal val preferences: SharedPreferences?
    @Volatile
    private var lastBootTime: ZonedDateTime = getCurrentDate()
    internal var packageName: String? = null
    internal var appVersion: String? = null

    val id: String
        get() {
            return this.state.value.id
        }

    val retention: Int
        get() {
            return (this.state.value.properties[BuiltinUserProperty.retentionPeriod.toString()] ?: "0").toInt()
        }

    internal constructor(context: Context, seed: Int? = null) {
        this.preferences = getNubrickUserSharedPreferences(context)

        // userId := uuid by default
        val userIdKey = BuiltinUserProperty.userId.toString()
        val userId: String = this.preferences?.getString(userIdKey, null) ?: UUID.randomUUID().toString()
        this.preferences?.edit()?.putString(userIdKey, userId)?.apply()
        this.setBaseProperty(userIdKey, userId)

        // USER_SEED_KEY := n in [0,USER_SEED_MAX)
        val rand = if (seed != null) Random(seed) else Random
        val userSeed: Int = this.preferences?.getInt(USER_SEED_KEY, rand.nextInt(0, USER_SEED_MAX)) ?: rand.nextInt(0, USER_SEED_MAX)
        this.preferences?.edit()?.putInt(USER_SEED_KEY, userSeed)?.apply()
        this.setBaseProperty(USER_SEED_KEY, userSeed.toString())

        val languageCode = Locale.getDefault().language
        this.setBaseProperty(BuiltinUserProperty.languageCode.toString(), languageCode)

        val regionCode = Locale.getDefault().country.toString()
        this.setBaseProperty(BuiltinUserProperty.regionCode.toString(), regionCode)

        val firstBootTimeKey = BuiltinUserProperty.firstBootTime.toString()
        val firstBootTime: String = this.preferences?.getString(firstBootTimeKey, null) ?: formatISO8601(
            getCurrentDate()
        )
        this.preferences?.edit()?.putString(firstBootTimeKey, firstBootTime)?.apply()
        this.setBaseProperty(firstBootTimeKey, firstBootTime)

        this.setBaseProperty(BuiltinUserProperty.sdkVersion.toString(), VERSION)

        this.setBaseProperty(BuiltinUserProperty.osName.toString(), "Android")
        this.setBaseProperty(BuiltinUserProperty.osVersion.toString(), Build.VERSION.SDK_INT.toString())

        try {
            val packageName = context.packageName
            this.packageName = packageName
            this.setBaseProperty(BuiltinUserProperty.appId.toString(), packageName)
            val appVersion = context.packageManager.getPackageInfo(packageName, 0).versionName
            this.setBaseProperty(BuiltinUserProperty.appVersion.toString(), appVersion ?: "0.0.0")
            this.appVersion = appVersion
        } catch (_: Exception) {
            this.setBaseProperty(BuiltinUserProperty.appVersion.toString(), "0.0.0")
        }

        this.preferences?.all?.forEach { (key, value) ->
            if (key.startsWith(USER_CUSTOM_PROPERTY_KEY_PREFIX)) {
                val propKey = key.removePrefix(USER_CUSTOM_PROPERTY_KEY_PREFIX)
                this.setCustomProperty(propKey, value.toString())
            }
        }

        this.comeBack()
    }

    fun setUserId(id: String) {
        val userIdKey = BuiltinUserProperty.userId.toString()
        this.setBaseProperty(userIdKey, id)
        this.preferences?.edit()?.putString(userIdKey, id)?.apply()
    }

    fun setProperty(key: String, value: Any) {
        if (key == BuiltinUserProperty.userId.toString()) {
            this.setUserId(value.toString())
            return
        }
        val strValue = formatUserPropertyValue(value)
        this.setCustomProperty(key, strValue)
        this.preferences?.edit()?.putString(USER_CUSTOM_PROPERTY_KEY_PREFIX + key, strValue)?.apply()
    }

    fun getProperty(key: String): String? {
        if (key == BuiltinUserProperty.userId.toString()) {
            return this.id.ifEmpty { null }
        }
        return this.state.value.customProperties[key]
    }

    fun setProperties(props: Map<String, Any>) {
        val userIdKey = BuiltinUserProperty.userId.toString()
        val userId = props[userIdKey]
        if (userId != null) {
            this.setUserId(userId.toString())
        }

        val customEntries = props.filterKeys { it != userIdKey }
            .mapValues { (_, value) -> formatUserPropertyValue(value) }
        if (customEntries.isNotEmpty()) {
            this._state.update { state ->
                state.copy(customProperties = state.customProperties + customEntries)
            }
            val editor = this.preferences?.edit()
            customEntries.forEach { (key, value) ->
                editor?.putString(USER_CUSTOM_PROPERTY_KEY_PREFIX + key, value)
            }
            editor?.apply()
        }
    }

    fun getProperties(): Map<String, String> {
        return this.state.value.templateProperties
    }

    fun comeBack() {
        val now = getCurrentDate()
        val lastBootTime = getCurrentDate()
        this.setBaseProperty(BuiltinUserProperty.lastBootTime.toString(), formatISO8601(lastBootTime))
        this.lastBootTime = lastBootTime

        val retentionPeriodKey = BuiltinUserProperty.retentionPeriod.toString()
        val retentionTimestamp = this.preferences?.getLong(retentionPeriodKey, now.toEpochSecond()) ?: now.toEpochSecond()
        val retentionPeriodCountKey = "retentionPeriodCount"
        val retentionCount = this.preferences?.getInt(retentionPeriodCountKey, 0) ?: 0
        this.setBaseProperty(retentionPeriodKey, retentionCount.toString())

        // 1 day is equal to 86400 seconds
        val lastDaysSince0 = retentionTimestamp / (86400)
        val daysSince0 = now.toEpochSecond() / (86400)
        if (lastDaysSince0 == daysSince0 - 1) {
            // count up retention. because user is returned in 1 day
            val countedUp = retentionCount + 1
            this.preferences?.edit()
                ?.putLong(retentionPeriodKey, now.toEpochSecond())
                ?.putInt(retentionPeriodCountKey, countedUp)
                ?.apply()
            this.setBaseProperty(retentionPeriodKey, countedUp.toString())
        } else if (lastDaysSince0 == daysSince0) {
            // save the initial count
            this.preferences?.edit()
                ?.putLong(retentionPeriodKey, retentionTimestamp)
                ?.putInt(retentionPeriodCountKey, retentionCount)
                ?.apply()
        } else if (lastDaysSince0 < daysSince0 - 1) {
            // reset retention. because user won't be returned in 1 day
            val reset = 0
            this.preferences?.edit()
                ?.putLong(retentionPeriodKey, now.toEpochSecond())
                ?.putInt(retentionPeriodCountKey, reset)
                ?.apply()
            this.setBaseProperty(retentionPeriodKey, reset.toString())
        }
    }

    // n in [0,1)
    internal fun getNormalizedUserRnd(seed: Int?): Double {
        val userSeedStr: String = this.state.value.properties[USER_SEED_KEY] ?: "0"
        val userSeed: Int = userSeedStr.toIntOrNull() ?: 0
        return Random((seed ?: 0) + userSeed).nextDouble()
    }

    internal fun toUserProperties(seed: Int? = 0): List<UserProperty> {
        val now = getCurrentDate()
        val props: MutableList<UserProperty> = mutableListOf()

        val bootingTime = now.toEpochSecond() - this.lastBootTime.toEpochSecond()
        props.addAll(listOf(
            UserProperty(
                name = BuiltinUserProperty.currentTime.toString(),
                value = formatISO8601(now),
                type = UserPropertyType.TIMESTAMPZ,
            ),
            UserProperty(
                name = BuiltinUserProperty.bootingTime.toString(),
                value = bootingTime.toString(),
                type = UserPropertyType.INTEGER
            )
        ))

        props.addAll(listOf(
            UserProperty(
                name = BuiltinUserProperty.localYear.toString(),
                value = now.year.toString(),
                type = UserPropertyType.INTEGER,
            ),
            UserProperty(
                name = BuiltinUserProperty.localMonth.toString(),
                value = now.month.value.toString(),
                type = UserPropertyType.INTEGER
            ),
            UserProperty(
                name = BuiltinUserProperty.localDay.toString(),
                value = now.dayOfMonth.toString(),
                type = UserPropertyType.INTEGER,
            ),
            UserProperty(
                name = BuiltinUserProperty.localHour.toString(),
                value = now.hour.toString(),
                type = UserPropertyType.INTEGER,
            ),
            UserProperty(
                name = BuiltinUserProperty.localMinute.toString(),
                value = (now.hour * 60 + now.minute).toString(),
                type = UserPropertyType.INTEGER,
            ),
            UserProperty(
                name = BuiltinUserProperty.localSecond.toString(),
                value = (now.hour * 60 * 60 + now.minute * 60 + now.second).toString(),
                type = UserPropertyType.INTEGER,
            ),
            UserProperty(
                name = BuiltinUserProperty.localWeekday.toString(),
                value = now.dayOfWeek.toString(),
                type = UserPropertyType.STRING,
            )
        ))

        this.state.value.properties.forEach { (key, value) ->
            if (key == BuiltinUserProperty.userRnd.toString()) {
                // not to use userRnd prop. use USER_SEED_KEY instead.
                return@forEach
            } else if (key == USER_SEED_KEY) {
                // add userRnd when it's USER_SEED_KEY
                val prop = UserProperty(
                    name = BuiltinUserProperty.userRnd.toString(),
                    value = this.getNormalizedUserRnd(seed = seed).toString(),
                    type = UserPropertyType.DOUBLE
                )
                props.add(prop)
            } else {
                props.add(UserProperty(
                    name = key,
                    value = value,
                    type = when (key) {
                        BuiltinUserProperty.userId.toString() -> UserPropertyType.STRING
                        BuiltinUserProperty.firstBootTime.toString() -> UserPropertyType.TIMESTAMPZ
                        BuiltinUserProperty.lastBootTime.toString() -> UserPropertyType.TIMESTAMPZ
                        BuiltinUserProperty.retentionPeriod.toString() -> UserPropertyType.INTEGER
                        BuiltinUserProperty.osName.toString() -> UserPropertyType.STRING
                        BuiltinUserProperty.osVersion.toString() -> UserPropertyType.SEMVER
                        BuiltinUserProperty.sdkVersion.toString() -> UserPropertyType.SEMVER
                        BuiltinUserProperty.appVersion.toString() -> UserPropertyType.SEMVER
                        else -> UserPropertyType.STRING
                    }
                ))
            }
        }

        this.state.value.customProperties.forEach { (key, value) ->
            props.add(UserProperty(
                name = key,
                value = value,
                type = UserPropertyType.STRING
            ))
        }

        return props
    }

    private fun setBaseProperty(key: String, value: String) {
        this._state.update { state ->
            state.copy(properties = state.properties + (key to value))
        }
    }

    private fun setCustomProperty(key: String, value: String) {
        this._state.update { state ->
            state.copy(customProperties = state.customProperties + (key to value))
        }
    }
}
