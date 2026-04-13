package io.nubrick.nubrick

const val VERSION = BuildConfig.VERSION_NAME

internal data class Endpoint(
    val cdn: String,
    val track: String,
)

internal object SdkConstants {
    val endpoint = Endpoint(
        cdn = "https://cdn.nativebrik.com",
        track = "https://track.nativebrik.com/track/v1",
    )
}
