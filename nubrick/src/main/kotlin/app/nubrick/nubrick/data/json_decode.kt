package app.nubrick.nubrick.data

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Parses a CDN/network response body into [JsonElement].
 * Invalid JSON becomes [FailedToDecodeException] instead of throwing into the caller coroutine.
 */
internal fun decodeJsonElementOrFailure(response: String): Result<JsonElement> {
    return try {
        Result.success(Json.decodeFromString(response))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Result.failure(FailedToDecodeException())
    }
}
