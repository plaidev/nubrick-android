package io.nubrick.nubrick.data

import io.nubrick.nubrick.schema.ApiHttpRequest
import io.nubrick.nubrick.schema.ApiHttpRequestMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal interface HttpRequestRepository {
    suspend fun request(req: ApiHttpRequest): Result<JsonElement>
}

internal class HttpRequestRepositoryImpl : HttpRequestRepository {
    override suspend fun request(req: ApiHttpRequest): Result<JsonElement> {
        val url = req.url ?: return Result.failure(SkipHttpRequestException())
        val connection = createHttpUrlConnection(url).getOrElse {
            return Result.failure(it)
        }
        val method = req.method ?: ApiHttpRequestMethod.GET
        connection.requestMethod = method.toString()
        connection.doInput = true

        req.headers?.forEach { header ->
            val name = header.name ?: return@forEach
            connection.setRequestProperty(name, header.value ?: "")
        }

        if (method != ApiHttpRequestMethod.GET && method != ApiHttpRequestMethod.TRACE) run {
            val body = req.body ?: ""
            setBody(connection, body)
        }

        val response: String = connectAndGetResponse(connection).getOrElse {
            return Result.failure(it)
        }
        val json = try {
            Json.decodeFromString<JsonElement>(response)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return Result.success(json)
    }
}
