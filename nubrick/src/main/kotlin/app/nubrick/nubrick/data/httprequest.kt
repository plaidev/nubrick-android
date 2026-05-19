package app.nubrick.nubrick.data

import app.nubrick.nubrick.schema.ApiHttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

internal interface HttpRequestRepository {
    suspend fun request(req: ApiHttpRequest): Result<JsonElement>
}

internal class HttpRequestRepositoryImpl(
    private val client: OkHttpClient,
) : HttpRequestRepository {
    override suspend fun request(req: ApiHttpRequest): Result<JsonElement> {
        val response: String = sendHttpRequest(req, client).getOrElse {
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
