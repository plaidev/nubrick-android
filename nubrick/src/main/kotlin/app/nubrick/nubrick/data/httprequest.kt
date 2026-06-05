package app.nubrick.nubrick.data

import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

internal data class CompiledHttpHeader(
    val name: String,
    val value: String,
)

internal data class CompiledHttpRequest(
    val url: String?,
    val method: ApiHttpRequestMethod?,
    val headers: List<CompiledHttpHeader>,
    val body: String?,
)

internal interface HttpRequestRepository {
    suspend fun request(req: CompiledHttpRequest): Result<JsonElement>
}

internal class HttpRequestRepositoryImpl(
    private val client: OkHttpClient,
) : HttpRequestRepository {
    override suspend fun request(req: CompiledHttpRequest): Result<JsonElement> {
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
