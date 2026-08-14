package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.syncDateFromHttpDateHeader
import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal const val CONNECT_TIMEOUT = 10 * 1000
internal const val READ_TIMEOUT = 5 * 1000
private const val HTTP_NOT_FOUND = 404
private const val MAX_RETRIES = 2
private val RETRY_DELAYS = longArrayOf(1000, 2000)
private const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024
private const val MAX_ERROR_BODY_SIZE = 4 * 1024
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal class HttpException(val statusCode: Int, body: String?) :
    Exception("HTTP $statusCode" + if (body.isNullOrBlank()) "" else ": $body")

internal class NetworkRepository(
    private val scope: CoroutineScope,
    private val cache: CacheStore,
    private val client: OkHttpClient,
) {
    private val refreshing = ConcurrentHashMap<String, AtomicBoolean>()

    suspend fun getWithCache(endpoint: String, syncDateTime: Boolean = false): Result<String> {
        val cached = cache.get(endpoint).getOrElse {
            val result = getRequest(endpoint, syncDateTime, client).getOrElse { error ->
                return Result.failure(error)
            }
            cache.set(endpoint, result)
            return Result.success(result)
        }
        if (cached.isStale()) {
            scheduleStaleRefresh(endpoint, syncDateTime)
        }
        return Result.success(cached.data)
    }

    /**
     * Drops the in-memory entry and, when present, the matching OkHttp disk-cache entry.
     * Used for definitive absences (404), not transient network failures.
     */
    fun invalidate(endpoint: String) {
        cache.remove(endpoint)
        evictHttpCache(endpoint)
    }

    private fun scheduleStaleRefresh(endpoint: String, syncDateTime: Boolean) {
        val gate = refreshing.computeIfAbsent(endpoint) { AtomicBoolean(false) }
        if (!gate.compareAndSet(false, true)) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                getRequest(endpoint, syncDateTime, client, forceNetwork = true).fold(
                    onSuccess = { body ->
                        cache.set(endpoint, body)
                    },
                    onFailure = { error ->
                        // Offline / timeouts / 5xx: keep the body we last fetched successfully.
                        // 404: experiment/component is gone — drop caches.
                        if (error is NotFoundException) {
                            invalidate(endpoint)
                        }
                    },
                )
            } finally {
                refreshing.remove(endpoint, gate)
            }
        }
    }

    private fun evictHttpCache(endpoint: String) {
        val httpCache = client.cache ?: return
        try {
            val iterator = httpCache.urls()
            while (iterator.hasNext()) {
                if (iterator.next() == endpoint) {
                    iterator.remove()
                }
            }
        } catch (_: Exception) {
            // Best-effort eviction; memory invalidate already happened.
        }
    }
}

private fun readStream(stream: InputStream, maxSize: Int = MAX_RESPONSE_SIZE): String {
    return stream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalRead = 0
        var count: Int
        while (input.read(buffer).also { count = it } != -1) {
            if (totalRead + count > maxSize) {
                throw IOException("Response body exceeded max size of $maxSize bytes")
            }
            output.write(buffer, 0, count)
            totalRead += count
        }
        output.toString(Charsets.UTF_8.name())
    }
}

private fun readErrorBody(body: ResponseBody?): String? {
    return try {
        body?.byteStream()?.let { readStream(it, MAX_ERROR_BODY_SIZE) }
    } catch (_: Exception) {
        null
    }
}

private fun isRetryable(e: Throwable): Boolean {
    return e is SocketTimeoutException || (e is HttpException && e.statusCode >= 500)
}

/** Outbox retries only help for transient failures. 4xx other than 408/429 will
 * not succeed on a later attempt, so those records should be dropped. */
internal fun isRetryableTrackingFailure(error: Throwable): Boolean {
    return when (error) {
        is SocketTimeoutException -> true
        is HttpException -> error.statusCode >= 500 || error.statusCode == 408 || error.statusCode == 429
        is NotFoundException -> false
        is IOException -> true
        else -> false
    }
}

internal suspend fun getRequest(
    endpoint: String,
    syncDateTime: Boolean = false,
    client: OkHttpClient,
    forceNetwork: Boolean = false,
): Result<String> = requestWithRetry {
    try {
        val t0 = System.currentTimeMillis()
        val builder = Request.Builder()
            .url(endpoint)
            .get()
        if (forceNetwork) {
            // Stale refresh must observe origin 404/updates; do not satisfy from disk cache alone.
            builder.cacheControl(CacheControl.FORCE_NETWORK)
        }
        executeRequest(client, builder.build(), syncDateTime, t0)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }
}

private suspend fun requestWithRetry(
    request: () -> Result<String>
): Result<String> {
    var lastResult: Result<String> = Result.failure(IOException("No attempts made"))
    for (attempt in 0..MAX_RETRIES) {
        if (attempt > 0) delay(RETRY_DELAYS[attempt - 1])
        lastResult = request()
        if (lastResult.isSuccess) return lastResult
        val error = lastResult.exceptionOrNull() ?: break
        if (!isRetryable(error)) break
    }
    return lastResult
}

internal suspend fun postRequest(endpoint: String, data: String, client: OkHttpClient): Result<String> {
    val request = try {
        Request.Builder()
            .url(endpoint)
            .post(data.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    } catch (e: IllegalArgumentException) {
        return Result.failure(e)
    }
    return requestWithRetry { executeRequest(client, request) }
}

/** Tracking owns retry scheduling in its durable outbox, so each call makes
 * one request and leaves unconfirmed records on disk for a later attempt. */
internal suspend fun postTrackingRequest(endpoint: String, data: String, client: OkHttpClient): Result<String> {
    val request = try {
        Request.Builder()
            .url(endpoint)
            .post(data.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    } catch (e: IllegalArgumentException) {
        return Result.failure(e)
    }
    return executeRequest(client, request)
}

internal fun sendHttpRequest(req: CompiledHttpRequest, client: OkHttpClient): Result<String> {
    val url = req.url ?: return Result.failure(SkipHttpRequestException())
    val method = req.method ?: ApiHttpRequestMethod.GET
    if (method == ApiHttpRequestMethod.UNKNOWN) {
        return Result.failure(IllegalArgumentException("Unsupported HTTP method: UNKNOWN"))
    }
    val request = try {
        val builder = Request.Builder().url(url)

        req.headers.forEach { header ->
            builder.header(header.name, header.value)
        }

        val body = if (method != ApiHttpRequestMethod.GET &&
            method != ApiHttpRequestMethod.HEAD &&
            method != ApiHttpRequestMethod.TRACE
        ) {
            (req.body ?: "").toRequestBody(JSON_MEDIA_TYPE)
        } else {
            null
        }
        builder.method(method.toString(), body).build()
    } catch (e: IllegalArgumentException) {
        return Result.failure(e)
    }

    return executeRequest(client, request)
}

private fun executeRequest(
    client: OkHttpClient,
    request: Request,
    syncDateTime: Boolean = false,
    t0: Long = System.currentTimeMillis(),
): Result<String> {
    try {
        client.newCall(request).execute().use { response ->
            if (syncDateTime && response.networkResponse != null) {
                syncDateFromHttpDateHeader(t0, System.currentTimeMillis(), response.header("Date"))
            }

            return when {
                response.code in 200..299 -> {
                    Result.success(response.body?.byteStream()?.let(::readStream) ?: "")
                }
                response.code == HTTP_NOT_FOUND -> Result.failure(NotFoundException())
                else -> Result.failure(HttpException(response.code, readErrorBody(response.body)))
            }
        }
    } catch (e: IOException) {
        return Result.failure(e)
    } catch (e: IllegalArgumentException) {
        return Result.failure(e)
    }
}
