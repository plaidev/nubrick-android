package app.nubrick.nubrick.data

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.nubrick.nubrick.Config
import app.nubrick.nubrick.FlutterBridgeApi
import app.nubrick.nubrick.SdkConstants
import app.nubrick.nubrick.VERSION
import app.nubrick.nubrick.data.database.TrackOutbox
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.data.user.formatISO8601
import app.nubrick.nubrick.data.user.getCurrentDate
import app.nubrick.nubrick.schema.TriggerEventNameDefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    val timestamp: ZonedDateTime = getCurrentDate(),
) {
    internal fun encode(): JsonObject {
        val map = mutableMapOf(
            "typename" to JsonPrimitive("crash"),
            "exceptions" to Json.encodeToJsonElement(this.exceptions),
            "severity" to JsonPrimitive(severity.name.lowercase()),
            "timestamp" to JsonPrimitive(formatISO8601(this.timestamp)),
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

@Serializable
internal data class StoredNativeCrash(
    val exceptions: List<ExceptionRecord>,
    val timestamp: String,
)

internal fun encodeStoredNativeCrash(
    exceptions: List<ExceptionRecord>,
    timestamp: ZonedDateTime,
): String = Json.encodeToString(StoredNativeCrash(exceptions, formatISO8601(timestamp)))

internal fun decodeStoredNativeCrash(data: String): TrackCrashEvent? {
    if (data.isEmpty()) return null
    try {
        val stored = Json.decodeFromString<StoredNativeCrash>(data)
        val timestamp = try {
            Instant.parse(stored.timestamp).atZone(ZoneOffset.UTC)
        } catch (_: Exception) {
            getCurrentDate()
        }
        return TrackCrashEvent(exceptions = stored.exceptions, timestamp = timestamp)
    } catch (_: Exception) {
        return try {
            TrackCrashEvent(
                exceptions = Json.decodeFromString<List<ExceptionRecord>>(data),
                timestamp = getCurrentDate(),
            )
        } catch (_: Exception) {
            null
        }
    }
}

internal fun crashTrackingEvents(crashEvent: TrackCrashEvent): List<TrackEvent> {
    val events = mutableListOf<TrackEvent>()
    val causedByNubrick = crashEvent.exceptions.any { exception ->
        exception.callStacks.orEmpty().any { frame ->
            val className = frame.className.orEmpty()
            className.contains("app.nubrick.nubrick") ||
                className.contains("app.nubrick.flutter.nubrick_flutter") ||
                className.contains("package:nubrick_flutter")
        }
    }
    val crashId = "${formatISO8601(crashEvent.timestamp)}|${crashEvent.exceptions.firstOrNull()?.type}|${crashEvent.exceptions.firstOrNull()?.message}"

    if (crashEvent.severity.isErrorLevel) {
        events += TrackEvent.UserEvent(
            TrackUserEvent(
                name = TriggerEventNameDefs.N_ERROR_RECORD.name,
                timestamp = crashEvent.timestamp,
            ),
            eventUuid = crashDerivedEventUuid(crashId, "error_record"),
        )
    }

    if (causedByNubrick) {
        if (crashEvent.severity.isErrorLevel) {
            events += TrackEvent.UserEvent(
                TrackUserEvent(
                    name = TriggerEventNameDefs.N_ERROR_IN_SDK_RECORD.name,
                    timestamp = crashEvent.timestamp,
                ),
                eventUuid = crashDerivedEventUuid(crashId, "error_in_sdk"),
            )
        }
        events += TrackEvent.CrashEvent(
            crashEvent,
            eventUuid = crashDerivedEventUuid(crashId, "crash"),
        )
    }
    return events
}

private fun crashDerivedEventUuid(crashId: String, kind: String): String =
    UUID.nameUUIDFromBytes("$crashId|$kind".toByteArray(Charsets.UTF_8)).toString()

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

internal sealed class TrackEvent(val eventUuid: String = UUID.randomUUID().toString()) {
    class UserEvent(val event: TrackUserEvent, eventUuid: String = UUID.randomUUID().toString()) : TrackEvent(eventUuid)
    class ExperimentEvent(val event: TrackExperimentEvent, eventUuid: String = UUID.randomUUID().toString()) : TrackEvent(eventUuid)
    class CrashEvent(val event: TrackCrashEvent, eventUuid: String = UUID.randomUUID().toString()) : TrackEvent(eventUuid)

    val eventType: String
        get() = when (this) {
            is UserEvent -> "event"
            is ExperimentEvent -> "experiment"
            is CrashEvent -> "crash"
        }

    fun encode(): JsonObject {
        val encoded = when (this) {
            is UserEvent -> this.event.encode()
            is ExperimentEvent -> this.event.encode()
            is CrashEvent -> this.event.encode()
        }
        return JsonObject(encoded + ("eventUuid" to JsonPrimitive(eventUuid)))
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

internal fun decodeTrackEventMeta(encoded: JsonObject): TrackEventMeta {
    fun string(key: String): String? {
        val value = encoded[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.content
    }
    return TrackEventMeta(
        appId = string("appId"),
        appVersion = string("appVersion"),
        osName = string("osName"),
        osVersion = string("osVersion"),
        sdkVersion = string("sdkVersion"),
        platform = string("platform"),
    )
}

internal data class TrackRequest(
    val projectId: String,
    val userId: String,
    val events: List<JsonObject>,
    val meta: TrackEventMeta,
    val timestamp: ZonedDateTime = getCurrentDate(),
) {
    fun encode(): JsonObject {
        return JsonObject(mapOf(
            "projectId" to JsonPrimitive(projectId),
            "userId" to JsonPrimitive(userId),
            "timestamp" to JsonPrimitive(formatISO8601(timestamp)),
            "events" to JsonArray(events),
            "meta" to meta.encode(),
        ))
    }
}

internal data class SurveyResponseRequest(
    val projectId: String,
    val experimentId: String,
    val variantId: String,
    val userId: String,
    val responseData: String,
    val meta: TrackEventMeta,
    val timestamp: ZonedDateTime = getCurrentDate(),
) {
    fun encode(): JsonObject {
        return JsonObject(mapOf(
            "timestamp" to JsonPrimitive(formatISO8601(timestamp)),
            "projectId" to JsonPrimitive(projectId),
            "experimentId" to JsonPrimitive(experimentId),
            "variantId" to JsonPrimitive(variantId),
            "userId" to JsonPrimitive(userId),
            "response_data" to JsonPrimitive(responseData),
            "meta" to meta.encode(),
        ))
    }
}

private fun postOnMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
        return
    }
    Handler(Looper.getMainLooper()).post(block)
}

internal fun nextScheduledFlushDelayMs(retryAfterMs: Long?, pendingFollowUpMs: Long?): Long? {
    return listOfNotNull(retryAfterMs, pendingFollowUpMs).minOrNull()
}

internal interface TrackRepository {
    suspend fun trackExperimentEvent(event: TrackExperimentEvent)
    suspend fun trackEvent(event: TrackUserEvent)
    fun sendSurveyResponse(experimentId: String, variantId: String, responseData: String)

    fun storeNativeCrash(throwable: Throwable)
    fun sendFlutterCrash(crashEvent: TrackCrashEvent)
    fun close() {}
}

internal class TrackRepositoryImpl(
    private val config: Config,
    private val user: NubrickUser,
    private val scope: CoroutineScope,
    private val outbox: TrackOutbox,
    private val client: OkHttpClient,
) : TrackRepository {
    private val maxBatchSize = 50
    private val maxBatchPayloadBytes = 512 * 1024
    private val maxBatchEventPayloadBytes = 500 * 1024
    private val flushIntervalMs = 10_000L
    private val scheduleLock = Any()
    private var scheduledJob: Job? = null
    private var scheduledJobId = 0L
    private var scheduledDelayMs = Long.MAX_VALUE
    private var isSending = false
    private var pendingDelayMs: Long? = null
    private var retryDelayMs = flushIntervalMs
    private var closed = false
    private val trackingClient = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val processLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START, Lifecycle.Event.ON_STOP -> requestFlush(0)
            else -> Unit
        }
    }

    init {
        postOnMainThread {
            val shouldAdd = synchronized(scheduleLock) { !closed }
            if (shouldAdd) {
                withProcessLifecycle { it.addObserver(processLifecycleObserver) }
            }
        }
        requestFlush(0)
        scope.launch { sendStoredCrash() }
    }

    private suspend fun enqueue(event: TrackEvent): Boolean = withContext(Dispatchers.IO) {
        val payload = Json.encodeToString(event.encode())
        val meta = Json.encodeToString(currentMeta().encode())
        val normalEventCount = outbox.insert(
            eventId = event.eventUuid,
            payload = payload,
            eventType = event.eventType,
            createdAt = System.currentTimeMillis(),
            userId = user.id,
            meta = meta,
        )
        if (normalEventCount == null) {
            Log.w("NubrickSDK", "Event dropped because it could not be persisted or was oversized")
            return@withContext false
        }
        requestFlush(if (event.eventType == "crash" || normalEventCount >= maxBatchSize) 0 else flushIntervalMs)
        true
    }

    private fun requestFlush(delayMs: Long) {
        val jobId: Long
        synchronized(scheduleLock) {
            if (closed) return
            if (isSending) {
                pendingDelayMs = minOf(pendingDelayMs ?: delayMs, delayMs)
                return
            }
            if (scheduledJob?.isActive == true) {
                if (delayMs >= scheduledDelayMs) return
                scheduledJob?.cancel()
            }
            scheduledDelayMs = delayMs
            jobId = ++scheduledJobId
            scheduledJob = scope.launch {
                if (delayMs > 0) delay(delayMs)
                runScheduledFlush(jobId)
            }
        }
    }

    private suspend fun runScheduledFlush(jobId: Long) {
        synchronized(scheduleLock) {
            if (closed || jobId != scheduledJobId || isSending) return
            scheduledJob = null
            scheduledDelayMs = Long.MAX_VALUE
            isSending = true
            pendingDelayMs = null
        }

        var retryAfterMs: Long? = null
        var pendingFollowUpMs: Long? = null
        try {
            while (outbox.hasPendingEvents()) {
                if (sendNextBatch()) {
                    retryDelayMs = flushIntervalMs
                    continue
                }
                retryAfterMs = nextRetryDelayMs()
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NubrickSDK", "Could not drain the tracking outbox", e)
            retryAfterMs = nextRetryDelayMs()
        } finally {
            synchronized(scheduleLock) {
                isSending = false
                pendingFollowUpMs = pendingDelayMs
                pendingDelayMs = null
            }
        }

        nextScheduledFlushDelayMs(retryAfterMs, pendingFollowUpMs)?.let(::requestFlush)
    }

    private fun nextRetryDelayMs(): Long {
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5 * 60 * 1000L)
        return delay
    }

    private fun currentMeta(): TrackEventMeta {
        return TrackEventMeta(
            appId = user.packageName,
            appVersion = user.appVersion,
            osVersion = Build.VERSION.SDK_INT.toString(),
            osName = "Android",
            sdkVersion = VERSION
        )
    }

    private suspend fun sendNextBatch(): Boolean {
        val pending = outbox.nextCrash()?.let { listOf(it) }
            ?: outbox.nextNormalBatch(maxBatchSize, maxBatchEventPayloadBytes)
        if (pending.isEmpty()) return true

        val validPending = pending.mapNotNull { entry ->
            val event = runCatching { Json.parseToJsonElement(entry.payload).jsonObject }.getOrNull()
            val meta = runCatching {
                decodeTrackEventMeta(Json.parseToJsonElement(entry.meta).jsonObject)
            }.getOrNull()
            if (event == null || meta == null) {
                outbox.remove(listOf(entry.eventId))
                Log.w("NubrickSDK", "Dropped corrupt tracking event from the outbox")
                null
            } else {
                Triple(entry, event, meta)
            }
        }
        if (validPending.isEmpty()) return true

        var batch = validPending
        while (batch.isNotEmpty()) {
            val request = TrackRequest(
                projectId = config.projectId,
                userId = batch.first().first.userId,
                events = batch.map { it.second },
                meta = batch.first().third,
            )
            val body = Json.encodeToString(request.encode())
            if (body.toByteArray(Charsets.UTF_8).size > maxBatchPayloadBytes) {
                if (batch.size == 1) {
                    outbox.remove(listOf(batch.single().first.eventId))
                    Log.w("NubrickSDK", "Dropped oversized tracking event")
                    return true
                }
                batch = batch.dropLast(1)
                continue
            }

            val result = postTrackingRequest(SdkConstants.endpoint.track, body, trackingClient)
            if (result.isSuccess) {
                outbox.remove(batch.map { it.first.eventId })
                return true
            }
            val error = result.exceptionOrNull()
            if (error == null || isRetryableTrackingFailure(error)) return false
            outbox.remove(batch.map { it.first.eventId })
            Log.w("NubrickSDK", "Dropped tracking events after a non-retryable response")
            return true
        }
        return true
    }

    override suspend fun trackEvent(event: TrackUserEvent) {
        enqueue(TrackEvent.UserEvent(event))
    }

    override suspend fun trackExperimentEvent(event: TrackExperimentEvent) {
        enqueue(TrackEvent.ExperimentEvent(event))
    }

    override fun sendSurveyResponse(experimentId: String, variantId: String, responseData: String) {
        if (experimentId.isEmpty() || variantId.isEmpty()) {
            return
        }
        scope.launch {
            val request = SurveyResponseRequest(
                projectId = config.projectId,
                experimentId = experimentId,
                variantId = variantId,
                userId = user.id,
                responseData = responseData,
                meta = currentMeta(),
            )
            val body = Json.encodeToString(request.encode())
            postRequest(SdkConstants.endpoint.surveyResponses, body, client).onFailure {
                Log.w("NubrickSDK", "Dropped survey response after send failure")
            }
        }
    }

    private suspend fun sendCrashToBackend(crashEvent: TrackCrashEvent): Boolean {
        var persisted = true
        for (event in crashTrackingEvents(crashEvent)) {
            if (!enqueue(event)) persisted = false
        }
        return persisted
    }

    private suspend fun sendStoredCrash() {
        val data = user.preferences?.getString(CRASH_RECORD_KEY, "") ?: ""
        if (data.isEmpty()) return

        val stored = decodeStoredNativeCrash(data)
        if (stored == null) {
            user.preferences?.edit()?.remove(CRASH_RECORD_KEY)?.commit()
            return
        }

        if (sendCrashToBackend(stored.copy(platform = "android"))) {
            user.preferences?.edit()?.remove(CRASH_RECORD_KEY)?.commit()
        }
    }

    override fun storeNativeCrash(throwable: Throwable) {
        var counter = 0
        var currentException: Throwable? = throwable
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
            counter++
        }

        val data = encodeStoredNativeCrash(exceptionsList, getCurrentDate())
        user.preferences?.edit()?.putString(CRASH_RECORD_KEY, data)?.commit()
    }

    override fun sendFlutterCrash(crashEvent: TrackCrashEvent) {
        scope.launch { sendCrashToBackend(crashEvent) }
    }

    override fun close() {
        synchronized(scheduleLock) {
            closed = true
            pendingDelayMs = null
            scheduledJob?.cancel()
            scheduledJob = null
        }
        postOnMainThread {
            withProcessLifecycle { it.removeObserver(processLifecycleObserver) }
        }
    }

    private fun withProcessLifecycle(block: (Lifecycle) -> Unit) {
        runCatching {
            block(ProcessLifecycleOwner.get().lifecycle)
        }.onFailure { error ->
            Log.w("NubrickSDK", "Could not update process lifecycle observer for tracking flushes", error)
        }
    }
}
