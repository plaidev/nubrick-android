package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.DATETIME_OFFSET
import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        ) { endpoint, _ ->
            runBlocking { getRequest(endpoint, client = client) }
        }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        assertEquals(3, requestCount)
    }

    @Test
    fun `get request does not retry not found`() {
        val (result, requestCount) = withLocalServer(response(404)) { endpoint, _ ->
            runBlocking { getRequest(endpoint, client = client) }
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotFoundException)
        assertEquals(1, requestCount)
    }

    @Test
    fun `get request does not retry client errors`() {
        val (result, requestCount) = withLocalServer(response(400, "bad request")) { endpoint, _ ->
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
        ) { endpoint, _ ->
            runBlocking { postRequest(endpoint, "{}", client) }
        }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        assertEquals(3, requestCount)
    }

    @Test
    fun `oversized successful response returns failure`() {
        val oversizedBody = "x".repeat(5 * 1024 * 1024 + 1)
        val (result, requestCount) = withLocalServer(response(200, oversizedBody)) { endpoint, _ ->
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
        val (result, requestCount) = withLocalServer(response(200, "{")) { endpoint, _ ->
            runBlocking {
                HttpRequestRepositoryImpl(client).request(
                    CompiledHttpRequest(
                        url = endpoint,
                        method = null,
                        headers = emptyList(),
                        body = null,
                    )
                )
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, requestCount)
    }

    @Test
    fun `custom http request rejects unknown method before sending`() {
        val result = sendHttpRequest(
            CompiledHttpRequest(
                url = "http://127.0.0.1/test",
                method = ApiHttpRequestMethod.UNKNOWN,
                headers = emptyList(),
                body = null,
            ),
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
            val (result, requestCount) = withLocalServer(cacheableResponse(200, "ok")) { endpoint, _ ->
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
            ) { endpoint, _ ->
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

    @Test
    fun `stale refresh 404 invalidates memory and okhttp cache`() {
        val diskCache = Cache(temporaryFolder.newFolder("okhttp-cache-404"), 10L * 1024 * 1024)
        val client = OkHttpClient.Builder().cache(diskCache).build()
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, client)

        try {
            val (result, requestCount) = withLocalServer(
                cacheableResponse(200, "old"),
                response(404),
            ) { endpoint, _ ->
                runBlocking {
                    assertTrue(repo.getWithCache(endpoint).isSuccess)
                    assertEquals("old", memory.get(endpoint).getOrNull()?.data)

                    // Make memory entry stale and trigger background refresh.
                    DATETIME_OFFSET = 61_000
                    assertEquals("old", repo.getWithCache(endpoint).getOrNull())

                    // Wait for background refresh (404) to finish.
                    var invalidated = false
                    repeat(50) {
                        delay(50)
                        if (memory.get(endpoint).isFailure) {
                            invalidated = true
                            return@repeat
                        }
                    }
                    assertTrue(invalidated)

                    // New CacheStore forces OkHttp path; 404 must not resurrect old body.
                    val secondRepo = NetworkRepository(scope, CacheStore(), client)
                    val after = secondRepo.getWithCache(endpoint)
                    assertTrue(after.isFailure)
                    assertTrue(after.exceptionOrNull() is NotFoundException)
                    endpoint
                }
            }
            assertTrue(requestCount >= 2)
            assertTrue(result.isNotEmpty())
        } finally {
            DATETIME_OFFSET = 0
            diskCache.close()
        }
    }

    @Test
    fun `stale refresh network failure keeps previously fetched body`() {
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())

        // Initial GET + stale refresh with MAX_RETRIES=2 → 3 attempts (1s + 2s delays).
        val expectedRequests = 1 + 3
        val (kept, requestCount) = withLocalServer(
            cacheableResponse(200, "old"),
            response(500),
            response(500),
            response(500),
        ) { endpoint, served ->
            runBlocking {
                assertTrue(repo.getWithCache(endpoint).isSuccess)
                DATETIME_OFFSET = 61_000
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())

                // Wait until all refresh attempts hit the server (not just "still cached while in flight").
                var refreshFinished = false
                repeat(200) {
                    delay(50)
                    if (served.get() >= expectedRequests) {
                        refreshFinished = true
                        return@repeat
                    }
                }
                assertTrue("expected $expectedRequests server hits, got ${served.get()}", refreshFinished)
                // Allow the refresh coroutine to apply the final failure handling.
                delay(100)

                assertEquals("old", memory.get(endpoint).getOrNull()?.data)
                memory.get(endpoint).getOrNull()?.data
            }
        }

        assertEquals("old", kept)
        assertTrue("requestCount=$requestCount", requestCount >= expectedRequests)
        DATETIME_OFFSET = 0
    }

    @Test
    fun `stale refresh coalesces in-flight requests`() {
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())

        val slowOk = "HTTP/1.1 200 OK\r\n" +
            "Cache-Control: public, max-age=600\r\n" +
            "Content-Length: 3\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "new"

        val (body, count) = withLocalServer(
            cacheableResponse(200, "old"),
            slowOk,
            slowOk,
            slowOk,
        ) { endpoint, _ ->
            runBlocking {
                assertTrue(repo.getWithCache(endpoint).isSuccess)
                DATETIME_OFFSET = 61_000
                // Burst stale reads — should schedule a single refresh.
                repeat(5) {
                    assertEquals("old", repo.getWithCache(endpoint).getOrNull())
                }
                var updated = false
                repeat(50) {
                    delay(50)
                    if (memory.get(endpoint).getOrNull()?.data == "new") {
                        updated = true
                        return@repeat
                    }
                }
                assertTrue(updated)
                endpoint
            }
        }
        // 1 initial + 1 coalesced refresh (not 5).
        assertTrue("requestCount=$count", count <= 3)
        assertTrue(body.isNotEmpty())
        DATETIME_OFFSET = 0
    }

    companion object {
        private fun <T> withLocalServer(
            vararg responses: String,
            request: (endpoint: String, requestCount: AtomicInteger) -> T
        ): Pair<T, Int> {
            val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            // Retry delays are 1s + 2s; keep accept open across gaps between attempts.
            serverSocket.soTimeout = 10_000
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

            val result = request("http://127.0.0.1:${serverSocket.localPort}/test", requestCount)
            server.get(15, TimeUnit.SECONDS)
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
