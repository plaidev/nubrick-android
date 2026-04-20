package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.syncDateFromHttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

internal const val CONNECT_TIMEOUT = 10 * 1000
internal const val READ_TIMEOUT = 5 * 1000
private const val MAX_RETRIES = 2
private val RETRY_DELAYS = longArrayOf(1000, 2000)
private const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024
private const val MAX_ERROR_BODY_SIZE = 4 * 1024

internal class HttpException(val statusCode: Int, body: String?) :
    Exception("HTTP $statusCode" + if (body.isNullOrBlank()) "" else ": $body")

internal class NetworkRepository(
    private val scope: CoroutineScope,
    private val cache: CacheStore,
) {
    fun getWithCache(endpoint: String, syncDateTime: Boolean = false): Result<String> {
        val cached = cache.get(endpoint).getOrElse {
            val result = getRequest(endpoint, syncDateTime).getOrElse { error ->
                return Result.failure(error)
            }
            cache.set(endpoint, result).getOrNull()
            return Result.success(result)
        }
        if (cached.isStale()) {
            scope.launch(Dispatchers.IO) {
                val result = getRequest(endpoint, syncDateTime).getOrNull() ?: return@launch
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

private fun readErrorBody(connection: HttpURLConnection): String? {
    return try {
        connection.errorStream?.let { readStream(it, MAX_ERROR_BODY_SIZE) }
    } catch (_: Exception) {
        null
    }
}

private fun isRetryable(e: Throwable): Boolean {
    return e is SocketTimeoutException || (e is HttpException && e.statusCode >= 500)
}

internal fun getRequest(endpoint: String, syncDateTime: Boolean = false): Result<String> {
    var lastResult: Result<String> = Result.failure(IOException("No attempts made"))
    for (attempt in 0..MAX_RETRIES) {
        if (attempt > 0) Thread.sleep(RETRY_DELAYS[attempt - 1])
        lastResult = getRequestOnce(endpoint, syncDateTime)
        if (lastResult.isSuccess) return lastResult
        val error = lastResult.exceptionOrNull() ?: break
        if (!isRetryable(error)) break
    }
    return lastResult
}

private fun getRequestOnce(endpoint: String, syncDateTime: Boolean): Result<String> {
    var connection: HttpURLConnection? = null
    try {
        val t0 = System.currentTimeMillis()
        val url = URL(endpoint)
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.requestMethod = "GET"
        connection.doOutput = false
        connection.doInput = true
        connection.useCaches = true
        connection.connect()
        val responseCode = connection.responseCode

        if (syncDateTime) {
            syncDateFromHttpResponse(t0, connection)
        }

        if (responseCode == HttpURLConnection.HTTP_OK) {
            return Result.success(readStream(connection.inputStream))
        } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return Result.failure(NotFoundException())
        } else {
            return Result.failure(HttpException(responseCode, readErrorBody(connection)))
        }
    } catch (e: IOException) {
        return Result.failure(e)
    } finally {
        connection?.disconnect()
    }
}

internal fun postRequest(endpoint: String, data: String): Result<String> {
    var connection: HttpURLConnection? = null
    try {
        val url = URL(endpoint)
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.doInput = true
        connection.useCaches = false
        connection.setRequestProperty("Content-Type", "application/json")

        val bodyData = data.toByteArray()
        connection.setRequestProperty("Content-Length", bodyData.size.toString())
        connection.outputStream.use { outputStream ->
            outputStream.write(bodyData)
            outputStream.flush()
        }

        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return Result.success(readStream(connection.inputStream))
        } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return Result.failure(NotFoundException())
        } else {
            return Result.failure(HttpException(responseCode, readErrorBody(connection)))
        }

    } catch (e: IOException) {
        return Result.failure(e)
    } finally {
        connection?.disconnect()
    }
}

internal fun createHttpUrlConnection(endpoint: String): Result<HttpURLConnection> {
    try {
        val parsed = URL(endpoint)
        if (parsed.protocol != "https" && parsed.protocol != "http") {
            return Result.failure(IOException("Unsupported URL scheme: ${parsed.protocol}"))
        }
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        return Result.success(connection)
    } catch (e: Exception) {
        return Result.failure(e)
    }
}

internal fun setBody(connection: HttpURLConnection, body: String) {
    connection.doOutput = true
    connection.useCaches = false
    connection.setRequestProperty("Content-Type", "application/json")

    val bodyData = body.toByteArray()
    connection.setRequestProperty("Content-Length", bodyData.size.toString())
    connection.outputStream.use { outputStream ->
        outputStream.write(bodyData)
        outputStream.flush()
    }
}

internal fun connectAndGetResponse(connection: HttpURLConnection): Result<String> {
    try {
        connection.connect()
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return Result.success(readStream(connection.inputStream))
        } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return Result.failure(NotFoundException())
        } else {
            return Result.failure(HttpException(responseCode, readErrorBody(connection)))
        }
    } catch (e: IOException) {
        return Result.failure(e)
    } finally {
        connection.disconnect()
    }
}
