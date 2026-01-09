package io.nubrick.nubrick.data

import android.os.Build
import io.nubrick.nubrick.Config
import io.nubrick.nubrick.FlutterBridgeApi
import io.nubrick.nubrick.VERSION
import io.nubrick.nubrick.data.user.NubrickUser
import io.nubrick.nubrick.data.user.formatISO8601
import io.nubrick.nubrick.data.user.getCurrentDate
import io.nubrick.nubrick.schema.TriggerEventNameDefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okio.withLock
import java.time.ZonedDateTime
import java.util.Timer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.fixedRateTimer

private val CRASH_RECORD_KEY = "CRASH_RECORD_KEY"

@Serializable
data class StackFrame(
    val fileName: String?,
    val className: String?,
    val methodName: String?,
    val lineNumber: Int?,
)

@Serializable
data class ExceptionRecord(
    val type: String?,
    val message: String?,
    val callStacks: List<StackFrame>?
)

data class TrackCrashEvent(
    val exceptions: List<ExceptionRecord>,
    val platform: String? = null,
    val flutterSdkVersion: String? = null,
    val severity: CrashSeverity = CrashSeverity.ERROR,
) {
    internal fun encode(): JsonObject {
        val map = mutableMapOf(
            "typename" to JsonPrimitive("crash"),
            "exceptions" to Json.encodeToJsonElement(this.exceptions),
            "severity" to JsonPrimitive(severity.name.lowercase()),
        )
        if (platform != null) {
            map["platform"] = JsonPrimitive(platform)
        }
        if (flutterSdkVersion != null) {
            map["flutterSdkVersion"] = JsonPrimitive(flutterSdkVersion)
        }
        return JsonObject(map)
    }
}

/**
 * Severity level for crash/error reporting.
 */
enum class CrashSeverity {
    DEBUG, INFO, WARNING, ERROR, FATAL;

    /** Returns true if this severity level should be counted as an error (ERROR or FATAL). */
    val isErrorLevel: Boolean get() = this == ERROR || this == FATAL

    companion object {
        /** Parses a string into a CrashSeverity, defaulting to ERROR for null, empty, or invalid values. */
        fun from(value: String?): CrashSeverity =
            if (value.isNullOrEmpty()) ERROR
            else entries.find { it.name.equals(value, ignoreCase = true) } ?: ERROR
    }
}

internal data class TrackUserEvent(
    val name: String,
    val timestamp: ZonedDateTime = getCurrentDate(),
) {
    fun encode(): JsonObject {
        return JsonObject(mapOf(
            "typename" to JsonPrimitive("event"),
            "name" to JsonPrimitive(this.name),
            "timestamp" to JsonPrimitive(formatISO8601(this.timestamp)),
        ))
    }
}

internal data class TrackExperimentEvent(
    val experimentId: String,
    val variantId: String,
    val timestamp: ZonedDateTime = getCurrentDate(),
) {
    fun encode(): JsonObject {
        return JsonObject(mapOf(
            "typename" to JsonPrimitive("experiment"),
            "experimentId" to JsonPrimitive(this.experimentId),
            "variantId" to JsonPrimitive(this.variantId),
            "timestamp" to JsonPrimitive(formatISO8601(this.timestamp)),
        ))
    }
}

internal sealed class TrackEvent {
    class UserEvent(val event: TrackUserEvent) : TrackEvent()
    class ExperimentEvent(val event: TrackExperimentEvent) : TrackEvent()
    class CrashEvent(val event: TrackCrashEvent) : TrackEvent()

    fun encode(): JsonObject {
        return when (this) {
            is UserEvent -> this.event.encode()
            is ExperimentEvent -> this.event.encode()
            is CrashEvent -> this.event.encode()
        }
    }
}

internal data class TrackEventMeta(
    val appId: String?,
    val appVersion: String?,
    val osName: String?,
    val osVersion: String?,
    val sdkVersion: String?,
    val platform: String? = "android"
) {
    fun encode(): JsonObject {
        return JsonObject(mapOf(
            "appId" to JsonPrimitive(this.appId),
            "appVersion" to JsonPrimitive(this.appVersion),
            "osName" to JsonPrimitive(this.osName),
            "osVersion" to JsonPrimitive(this.osVersion),
            "sdkVersion" to JsonPrimitive(this.sdkVersion),
            "platform" to JsonPrimitive(this.platform),
        ))
    }
}

internal data class TrackRequest(
    val projectId: String,
    val userId: String,
    val events: List<TrackEvent>,
    val meta: TrackEventMeta,
    val timestamp: ZonedDateTime = getCurrentDate(),
) {
    fun encode(): JsonObject {
        val events = this.events.map { it.encode() }
        return JsonObject(mapOf(
            "projectId" to JsonPrimitive(projectId),
            "userId" to JsonPrimitive(userId),
            "timestamp" to JsonPrimitive(formatISO8601(timestamp)),
            "events" to JsonArray(events),
            "meta" to meta.encode(),
        ))
    }
}

internal interface TrackRepository {
    fun trackExperimentEvent(event: TrackExperimentEvent)
    fun trackEvent(event: TrackUserEvent)

    fun storeNativeCrash(throwable: Throwable)
    fun sendFlutterCrash(crashEvent: TrackCrashEvent)
}

internal class TrackRepositoryImpl: TrackRepository {
    private val queueLock: ReentrantLock = ReentrantLock()
    private val config: Config
    private val user: NubrickUser
    private var timer: Timer? = null
    private val maxBatchSize: Int = 50
    private val maxQueueSize: Int = 300
    private var buffer: MutableList<TrackEvent> = mutableListOf()

    internal constructor(config: Config, user: NubrickUser) {
        this.config = config
        this.user = user

        this.sendStoredCrash()
    }

    override fun trackEvent(event: TrackUserEvent) {
        this.enqueue(TrackEvent.UserEvent(event))
    }

    override fun trackExperimentEvent(event: TrackExperimentEvent) {
        this.enqueue(TrackEvent.ExperimentEvent(event))
    }

    private fun enqueue(event: TrackEvent) {
        this.queueLock.withLock {
            if (this.timer == null) {
                val self = this
                CoroutineScope(Dispatchers.Main).launch {
                    self.timer?.cancel()
                    self.timer = fixedRateTimer(initialDelay = 0, period = 4000) {
                        CoroutineScope(Dispatchers.IO).launch {
                            self.sendAndFlush()
                        }
                    }
                }
            }
            if (this.buffer.size >= this.maxBatchSize) {
                val self = this
                CoroutineScope(Dispatchers.IO).launch {
                    self.sendAndFlush()
                }
            }
            this.buffer.add(event)
            if (buffer.size >= this.maxQueueSize) {
                this.buffer.drop(this.maxQueueSize - this.buffer.size)
            }
        }
    }

    private fun sendAndFlush() {
        val tempBuffer = this.buffer
        if (tempBuffer.isEmpty()) return
        this.buffer = mutableListOf()
        val meta = TrackEventMeta(
            appId = this.user.packageName,
            appVersion = this.user.appVersion,
            osVersion = Build.VERSION.SDK_INT.toString(),
            osName = "Android",
            sdkVersion = VERSION
        )
        val request = TrackRequest(
            projectId = config.projectId,
            userId = user.id,
            events = tempBuffer,
            meta = meta,
        )
        val body = Json.encodeToString(request.encode())
        this.timer?.cancel()
        this.timer = null
        postRequest(this.config.endpoint.track, body).onFailure {
            this.buffer.addAll(tempBuffer)
        }
    }

    private fun sendCrashToBackend(crashEvent: TrackCrashEvent) {
        val causedByNubrick = crashEvent.exceptions.any { exception ->
            exception.callStacks.orEmpty().any { frame ->
                frame.className?.contains("io.nubrick.nubrick") ?: false ||
                frame.className?.contains("package:nativebrik_bridge") ?: false
            }
        }

        // Only send error tracking events for error or fatal severity
        if (crashEvent.severity.isErrorLevel) {
            this.buffer.add(TrackEvent.UserEvent(TrackUserEvent(
                name = TriggerEventNameDefs.N_ERROR_RECORD.name
            )))
        }

        if (causedByNubrick) {
            if (crashEvent.severity.isErrorLevel) {
                buffer.add(TrackEvent.UserEvent(TrackUserEvent(
                    name = TriggerEventNameDefs.N_ERROR_IN_SDK_RECORD.name
                )))
            }
            buffer.add(TrackEvent.CrashEvent(crashEvent))
        }
        val self = this
        CoroutineScope(Dispatchers.IO).launch {
            self.sendAndFlush()
        }
    }

    private fun sendStoredCrash() {
        val data = this.queueLock.withLock {
            val data = this.user.preferences?.getString(CRASH_RECORD_KEY, "") ?: ""
            if (data.isNotEmpty()) {
                this.user.preferences?.edit()?.remove(CRASH_RECORD_KEY)?.commit()
            }
            data
        }
        if (data.isEmpty()) {
            return
        }

        val exceptionsList : List<ExceptionRecord>
        try {
            exceptionsList = Json.decodeFromString<List<ExceptionRecord>>(data)
        }
        catch (e: Exception) {
            return // in case there was exception in json decoding we stop here
        }

        sendCrashToBackend(TrackCrashEvent(exceptions = exceptionsList))
    }

    override fun storeNativeCrash(throwable: Throwable) {
        // Loop on chained exceptions (up to 20 times max)
        var counter = 0
        var currentException : Throwable? = throwable
        val exceptionsList = mutableListOf<ExceptionRecord>()

        while (currentException != null && counter < 20) {
            val stackFrames = currentException.stackTrace
            exceptionsList.add(ExceptionRecord(
                type = currentException::class.simpleName,
                message = currentException.message,
                callStacks = stackFrames.map {
                    StackFrame(
                        fileName = it.fileName,
                        className = it.className,
                        methodName = it.methodName,
                        lineNumber = if (it.lineNumber >= 0) it.lineNumber else null
                    )
                }
            ))
            currentException = currentException.cause
            counter ++
        }

        val data = Json.encodeToString(exceptionsList)
        this.user.preferences?.edit()?.putString(CRASH_RECORD_KEY, data)?.commit()
    }

    override fun sendFlutterCrash(crashEvent: TrackCrashEvent) {
        sendCrashToBackend(crashEvent)
    }
}
