package app.nubrick.nubrick

const val VERSION = BuildConfig.VERSION_NAME

internal data class Endpoint(
    val cdn: String,
    val track: String,
    val surveyResponses: String,
)

internal object SdkConstants {
    private const val trackBaseUrl = "https://track.nativebrik.com"
    private const val trackEndpoint = "/track/v1"
    private const val surveyResponsesEndpoint = "/track/v1/survey-responses"

    val endpoint = Endpoint(
        cdn = "https://cdn.nativebrik.com",
        track = "$trackBaseUrl$trackEndpoint",
        surveyResponses = "$trackBaseUrl$surveyResponsesEndpoint",
    )
}
