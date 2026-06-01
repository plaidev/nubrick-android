package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.syncDateFromHttpDateHeader
import app.nubrick.nubrick.schema.ApiHttpRequest
import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

internal const val CONNECT_TIMEOUT = 10 * 1000
internal const val READ_TIMEOUT = 5 * 1000
private const val HTTP_OK = 200
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
    suspend fun getWithCache(endpoint: String, syncDateTime: Boolean = false): Result<String> {
        val cached = cache.get(endpoint).getOrElse {
            val result = getRequest(endpoint, syncDateTime, client).getOrElse { error ->
                return Result.failure(error)
            }
            cache.set(endpoint, result).getOrNull()
            return Result.success(result)
        }
        if (cached.isStale()) {
            scope.launch(Dispatchers.IO) {
                val result = getRequest(endpoint, syncDateTime, client).getOrNull() ?: return@launch
                cache.set(endpoint, result).getOrNull()
            }
        }
        return Result.success(cached.data)
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

internal suspend fun getRequest(
    endpoint: String,
    syncDateTime: Boolean = false,
    client: OkHttpClient
): Result<String> = requestWithRetry {
    try {
        val t0 = System.currentTimeMillis()
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
        executeRequest(client, request, syncDateTime, t0)
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

internal fun sendHttpRequest(req: ApiHttpRequest, client: OkHttpClient): Result<String> {
    val url = req.url ?: return Result.failure(SkipHttpRequestException())
    val method = req.method ?: ApiHttpRequestMethod.GET
    if (method == ApiHttpRequestMethod.UNKNOWN) {
        return Result.failure(IllegalArgumentException("Unsupported HTTP method: UNKNOWN"))
    }
    val request = try {
        val builder = Request.Builder().url(url)

        req.headers?.forEach { header ->
            val name = header.name ?: return@forEach
            builder.header(name, header.value ?: "")
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

            return when (response.code) {
                HTTP_OK -> {
                    val body = response.body ?: return Result.failure(IOException("Empty response body"))
                    Result.success(readStream(body.byteStream()))
                }
                HTTP_NOT_FOUND -> Result.failure(NotFoundException())
                else -> Result.failure(HttpException(response.code, readErrorBody(response.body)))
            }
        }
    } catch (e: IOException) {
        return Result.failure(e)
    } catch (e: IllegalArgumentException) {
        return Result.failure(e)
    }
}
