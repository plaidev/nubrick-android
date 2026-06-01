package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.DATETIME_OFFSET
import app.nubrick.nubrick.schema.ApiHttpRequest
import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val client = OkHttpClient()

    @Test
    fun `get request retries server errors and returns success`() {
        val (result, requestCount) = withLocalServer(
            response(500),
            response(502),
            response(200, "ok")
        ) { endpoint ->
            runBlocking { getRequest(endpoint, client = client) }
        }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        assertEquals(3, requestCount)
    }

    @Test
    fun `get request does not retry not found`() {
        val (result, requestCount) = withLocalServer(response(404)) { endpoint ->
            runBlocking { getRequest(endpoint, client = client) }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotFoundException)
        assertEquals(1, requestCount)
    }

    @Test
    fun `get request does not retry client errors`() {
        val (result, requestCount) = withLocalServer(response(400, "bad request")) { endpoint ->
            runBlocking { getRequest(endpoint, client = client) }
        }

        val error = result.exceptionOrNull()
        assertTrue(result.isFailure)
        assertTrue(error is HttpException)
        assertEquals(400, (error as HttpException).statusCode)
        assertEquals(1, requestCount)
    }

    @Test
    fun `post request retries server errors and returns success`() {
        val (result, requestCount) = withLocalServer(
            response(500),
            response(502),
            response(200, "ok")
        ) { endpoint ->
            runBlocking { postRequest(endpoint, "{}", client) }
        }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        assertEquals(3, requestCount)
    }

    @Test
    fun `oversized successful response returns failure`() {
        val oversizedBody = "x".repeat(5 * 1024 * 1024 + 1)
        val (result, requestCount) = withLocalServer(response(200, oversizedBody)) { endpoint ->
            runBlocking { getRequest(endpoint, client = client) }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, requestCount)
    }

    @Test
    fun `get request rejects unsupported schemes`() {
        val result = runBlocking { getRequest("file:///tmp/test.json", client = client) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `custom http request returns failure for invalid json response`() {
        val (result, requestCount) = withLocalServer(response(200, "{")) { endpoint ->
            runBlocking {
                HttpRequestRepositoryImpl(client).request(ApiHttpRequest(url = endpoint))
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, requestCount)
    }

    @Test
    fun `custom http request rejects unknown method before sending`() {
        val result = sendHttpRequest(
            ApiHttpRequest(url = "http://127.0.0.1/test", method = ApiHttpRequestMethod.UNKNOWN),
            client
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `cached get request uses okhttp disk cache when memory cache is empty`() {
        val diskCache = Cache(temporaryFolder.newFolder("okhttp-cache"), 10L * 1024 * 1024)
        val client = OkHttpClient.Builder()
            .cache(diskCache)
            .build()
        val scope = CoroutineScope(Dispatchers.IO)

        try {
            val (result, requestCount) = withLocalServer(cacheableResponse(200, "ok")) { endpoint ->
                runBlocking {
                    val first = NetworkRepository(scope, CacheStore(), client).getWithCache(endpoint)
                    assertTrue(first.isSuccess)
                    assertEquals("ok", first.getOrNull())

                    NetworkRepository(scope, CacheStore(), client).getWithCache(endpoint)
                }
            }

            assertTrue(result.isSuccess)
            assertEquals("ok", result.getOrNull())
            assertEquals(1, requestCount)
        } finally {
            diskCache.close()
        }
    }

    @Test
    fun `cached get request does not sync date time from cached response`() {
        DATETIME_OFFSET = 0
        val diskCache = Cache(temporaryFolder.newFolder("okhttp-cache-date"), 10L * 1024 * 1024)
        val client = OkHttpClient.Builder()
            .cache(diskCache)
            .build()
        val scope = CoroutineScope(Dispatchers.IO)

        try {
            val (result, requestCount) = withLocalServer(
                cacheableResponse(200, "ok", dateHeader = "Tue, 19 May 2099 00:00:00 GMT")
            ) { endpoint ->
                runBlocking {
                    val first = NetworkRepository(scope, CacheStore(), client)
                        .getWithCache(endpoint, syncDateTime = true)
                    assertTrue(first.isSuccess)
                    assertTrue(DATETIME_OFFSET > 1000L)

                    DATETIME_OFFSET = 0

                    NetworkRepository(scope, CacheStore(), client)
                        .getWithCache(endpoint, syncDateTime = true)
                }
            }

            assertTrue(result.isSuccess)
            assertEquals(0L, DATETIME_OFFSET)
            assertEquals(1, requestCount)
        } finally {
            DATETIME_OFFSET = 0
            diskCache.close()
        }
    }

    companion object {
        private fun <T> withLocalServer(
            vararg responses: String,
            request: (String) -> T
        ): Pair<T, Int> {
            val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            serverSocket.soTimeout = 5000
            val requestCount = AtomicInteger(0)
            val executor = Executors.newSingleThreadExecutor()
            val server = executor.submit {
                serverSocket.use { socket ->
                    while (true) {
                        try {
                            socket.accept().use { client ->
                                val responseIndex = requestCount.getAndIncrement()
                                val reader = client.getInputStream().bufferedReader()
                                while (reader.readLine()?.isNotEmpty() == true) {
                                    // Drain headers before writing the response.
                                }
                                client.getOutputStream().use { output ->
                                    output.write(responses.getOrElse(responseIndex) { responses.last() }.toByteArray())
                                    output.flush()
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                    }
                }
            }

            val result = request("http://127.0.0.1:${serverSocket.localPort}/test")
            server.get(10, TimeUnit.SECONDS)
            executor.shutdownNow()
            return result to requestCount.get()
        }

        private fun response(statusCode: Int, body: String = ""): String {
            val reason = when (statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                502 -> "Bad Gateway"
                else -> "HTTP"
            }
            return "HTTP/1.1 $statusCode $reason\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
        }

        private fun cacheableResponse(statusCode: Int, body: String = "", dateHeader: String? = null): String {
            val reason = when (statusCode) {
                200 -> "OK"
                else -> "HTTP"
            }
            val date = dateHeader?.let { "Date: $it\r\n" } ?: ""
            return "HTTP/1.1 $statusCode $reason\r\n" +
                date +
                "Cache-Control: public, max-age=600\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
        }
    }
}
