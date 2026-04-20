package io.nubrick.nubrick.data

import io.nubrick.nubrick.schema.ApiHttpRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkTest {
    @Test
    fun `get request retries server errors and returns success`() {
        val (result, requestCount) = withLocalServer(
            response(500),
            response(502),
            response(200, "ok")
        ) { endpoint ->
            getRequest(endpoint)
        }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
        assertEquals(3, requestCount)
    }

    @Test
    fun `get request does not retry not found`() {
        val (result, requestCount) = withLocalServer(response(404)) { endpoint ->
            getRequest(endpoint)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotFoundException)
        assertEquals(1, requestCount)
    }

    @Test
    fun `get request does not retry client errors`() {
        val (result, requestCount) = withLocalServer(response(400, "bad request")) { endpoint ->
            getRequest(endpoint)
        }

        val error = result.exceptionOrNull()
        assertTrue(result.isFailure)
        assertTrue(error is HttpException)
        assertEquals(400, (error as HttpException).statusCode)
        assertEquals(1, requestCount)
    }

    @Test
    fun `post request does not retry server errors`() {
        val (result, requestCount) = withLocalServer(response(500)) { endpoint ->
            postRequest(endpoint, "{}")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HttpException)
        assertEquals(1, requestCount)
    }

    @Test
    fun `oversized successful response returns failure`() {
        val connection = object : HttpURLConnection(URL("http://127.0.0.1/test")) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = HTTP_OK
            override fun getInputStream(): InputStream = RepeatingInputStream(5 * 1024 * 1024 + 1)
        }

        val result = connectAndGetResponse(connection)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `create http url connection rejects unsupported schemes`() {
        val result = createHttpUrlConnection("file:///tmp/test.json")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `custom http request returns failure for invalid json response`() {
        val (result, requestCount) = withLocalServer(response(200, "{")) { endpoint ->
            runBlocking {
                HttpRequestRepositoryImpl().request(ApiHttpRequest(url = endpoint))
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, requestCount)
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
    }
}

private class RepeatingInputStream(private var remaining: Int) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        remaining--
        return 'x'.code
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = minOf(length, remaining)
        buffer.fill('x'.code.toByte(), offset, offset + count)
        remaining -= count
        return count
    }
}
