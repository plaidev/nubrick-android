package app.nubrick.nubrick.data

import app.nubrick.nubrick.data.user.DATETIME_OFFSET
import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkTest {
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
    fun `memory cache hit returns immediately and revalidates in background`() {
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())

        val (body, requestCount) = withLocalServer(
            response(200, "old"),
            response(200, "new"),
        ) { endpoint, _ ->
            runBlocking {
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())

                var updated = false
                repeat(50) {
                    delay(50)
                    if (memory.get(endpoint).getOrNull()?.data == "new") {
                        updated = true
                        return@repeat
                    }
                }
                assertTrue(updated)
                memory.get(endpoint).getOrNull()?.data
            }
        }

        assertEquals("new", body)
        assertEquals(2, requestCount)
    }

    @Test
    fun `expired retention cap fetches from network before returning`() {
        val memory = CacheStore(retentionSeconds = 30)
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())

        val (body, requestCount) = withLocalServer(
            response(200, "old"),
            response(200, "fresh"),
        ) { endpoint, _ ->
            runBlocking {
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())
                DATETIME_OFFSET = 31_000
                val second = repo.getWithCache(endpoint)
                assertEquals("fresh", second.getOrNull())
                second.getOrNull()
            }
        }

        assertEquals("fresh", body)
        assertEquals(2, requestCount)
        DATETIME_OFFSET = 0
    }

    @Test
    fun `revalidate 404 deletes memory cache entry`() {
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())

        val (result, requestCount) = withLocalServer(
            response(200, "old"),
            response(404),
        ) { endpoint, _ ->
            runBlocking {
                assertTrue(repo.getWithCache(endpoint).isSuccess)
                assertEquals("old", memory.get(endpoint).getOrNull()?.data)

                assertEquals("old", repo.getWithCache(endpoint).getOrNull())

                var invalidated = false
                repeat(50) {
                    delay(50)
                    if (memory.get(endpoint).isFailure) {
                        invalidated = true
                        return@repeat
                    }
                }
                assertTrue(invalidated)

                val after = repo.getWithCache(endpoint)
                assertTrue(after.isFailure)
                assertTrue(after.exceptionOrNull() is NotFoundException)
                endpoint
            }
        }
        assertTrue(requestCount >= 2)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `revalidate 404 does not remove a newer cache entry`() {
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val revalidationStarted = CountDownLatch(1)
        val releaseRevalidation = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val server = executor.submit {
            serverSocket.use { socket ->
                repeat(2) { responseIndex ->
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) {
                            // Drain headers before writing the response.
                        }
                        if (responseIndex == 1) {
                            revalidationStarted.countDown()
                            releaseRevalidation.await(5, TimeUnit.SECONDS)
                        }
                        client.getOutputStream().use { output ->
                            output.write(
                                if (responseIndex == 0) response(200, "old").toByteArray()
                                else response(404).toByteArray()
                            )
                            output.flush()
                        }
                    }
                }
            }
        }

        try {
            val endpoint = "http://127.0.0.1:${serverSocket.localPort}/test"
            runBlocking {
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())
                assertTrue(revalidationStarted.await(5, TimeUnit.SECONDS))

                memory.set(endpoint, "new")
                releaseRevalidation.countDown()

                repeat(50) {
                    delay(50)
                    if (memory.get(endpoint).getOrNull()?.data == "new") return@repeat
                }
                assertEquals("new", memory.get(endpoint).getOrNull()?.data)
            }
            server.get(5, TimeUnit.SECONDS)
        } finally {
            releaseRevalidation.countDown()
            serverSocket.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `revalidate success does not overwrite a newer cache entry`() {
        val memory = CacheStore()
        val revalidationStarted = CountDownLatch(1)
        val releaseRevalidation = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                revalidationStarted.countDown()
                releaseRevalidation.await(5, TimeUnit.SECONDS)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("stale".toResponseBody())
                    .build()
            }
            .build()
        val repo = NetworkRepository(CoroutineScope(Dispatchers.IO), memory, client)
        val endpoint = "http://127.0.0.1/test"

        try {
            memory.set(endpoint, "old")
            runBlocking {
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())
                assertTrue(revalidationStarted.await(5, TimeUnit.SECONDS))

                memory.set(endpoint, "new")
                releaseRevalidation.countDown()

                repeat(50) {
                    delay(50)
                    if (memory.get(endpoint).getOrNull()?.data == "new") return@repeat
                }
                assertEquals("new", memory.get(endpoint).getOrNull()?.data)
            }
        } finally {
            releaseRevalidation.countDown()
        }
    }

    @Test
    fun `newer cold-cache request wins when responses finish out of order`() {
        val memory = CacheStore()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = if (requestCount.getAndIncrement() == 0) {
                    firstRequestStarted.countDown()
                    releaseFirstRequest.await(5, TimeUnit.SECONDS)
                    "old"
                } else {
                    "new"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()
        val repo = NetworkRepository(CoroutineScope(Dispatchers.IO), memory, client)
        val endpoint = "http://127.0.0.1/test"

        try {
            runBlocking {
                val first = async(Dispatchers.IO) { repo.getWithCache(endpoint) }
                assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))

                val second = async(Dispatchers.IO) { repo.getWithCache(endpoint) }
                assertEquals("new", second.await().getOrNull())
                releaseFirstRequest.countDown()
                assertEquals("old", first.await().getOrNull())

                assertEquals("new", memory.get(endpoint).getOrNull()?.data)
            }
        } finally {
            releaseFirstRequest.countDown()
        }
    }

    @Test
    fun `revalidate network failure keeps previously fetched body`() {
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())

        // Initial GET + revalidate with MAX_RETRIES=2 → 3 attempts (1s + 2s delays).
        val expectedRequests = 1 + 3
        val (kept, requestCount) = withLocalServer(
            response(200, "old"),
            response(500),
            response(500),
            response(500),
        ) { endpoint, served ->
            runBlocking {
                assertTrue(repo.getWithCache(endpoint).isSuccess)
                assertEquals("old", repo.getWithCache(endpoint).getOrNull())

                var refreshFinished = false
                repeat(200) {
                    delay(50)
                    if (served.get() >= expectedRequests) {
                        refreshFinished = true
                        return@repeat
                    }
                }
                assertTrue("expected $expectedRequests server hits, got ${served.get()}", refreshFinished)
                delay(100)

                assertEquals("old", memory.get(endpoint).getOrNull()?.data)
                memory.get(endpoint).getOrNull()?.data
            }
        }

        assertEquals("old", kept)
        assertTrue("requestCount=$requestCount", requestCount >= expectedRequests)
    }

    @Test
    fun `revalidate coalesces in-flight requests`() {
        val memory = CacheStore()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = NetworkRepository(scope, memory, OkHttpClient())

        val slowOk = "HTTP/1.1 200 OK\r\n" +
            "Content-Length: 3\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "new"

        val (body, count) = withLocalServer(
            response(200, "old"),
            slowOk,
            slowOk,
            slowOk,
        ) { endpoint, _ ->
            runBlocking {
                assertTrue(repo.getWithCache(endpoint).isSuccess)
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
        // 1 initial + 1 coalesced revalidate (not 5).
        assertTrue("requestCount=$count", count <= 3)
        assertTrue(body.isNotEmpty())
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
    }
}
