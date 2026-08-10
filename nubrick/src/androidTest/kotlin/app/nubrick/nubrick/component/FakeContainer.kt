package app.nubrick.nubrick.component

import androidx.compose.runtime.Composable
import app.nubrick.nubrick.Event
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.CompiledHttpRequest
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.data.ExperimentContent
import app.nubrick.nubrick.data.FormValue
import app.nubrick.nubrick.data.TrackCrashEvent
import app.nubrick.nubrick.schema.ApiHttpRequest
import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.ExperimentVariant
import app.nubrick.nubrick.schema.Property
import app.nubrick.nubrick.schema.UIBlockAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Minimal [Container] for Compose Embedding smoke tests.
 * Only [fetchEmbedding] is meaningful; other members are stubs.
 */
internal class FakeContainer(
    private val embeddingResult: Result<ExperimentContent>,
) : Container {
    override val experimentId: String? = null
    override val variantId: String? = null

    override fun makeContainer(): Container = this

    override fun makeContainer(experimentId: String?, variantId: String?): Container = this

    @Composable
    override fun rememberVariableForTemplate(
        data: JsonElement?,
        pageProperties: List<Property>?,
        arguments: Any?,
    ): JsonElement = JsonNull

    override val formValuesFlow: StateFlow<Map<String, FormValue>> =
        MutableStateFlow(emptyMap())

    override fun getFormValues(): Map<String, JsonElement> = emptyMap()

    override fun getFormValue(key: String): FormValue? = null

    override fun setFormValue(key: String, value: FormValue) {}

    override fun sendSurveyResponse() {}

    override fun compileHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement,
    ): CompiledHttpRequest = CompiledHttpRequest(
        url = null,
        method = ApiHttpRequestMethod.UNKNOWN,
        headers = emptyList(),
        body = null,
    )

    override suspend fun sendCompiledHttpRequest(req: CompiledHttpRequest): Result<JsonElement> =
        Result.failure(UnsupportedOperationException("not used in smoke test"))

    override suspend fun sendHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement,
    ): Result<JsonElement> =
        Result.failure(UnsupportedOperationException("not used in smoke test"))

    override suspend fun fetchEmbedding(
        experimentId: String,
        componentId: String?,
    ): Result<ExperimentContent> = embeddingResult

    override suspend fun fetchTriggerContent(
        trigger: String,
        kinds: List<ExperimentKind>,
    ): Result<Pair<ExperimentContent, ExperimentKind>> =
        Result.failure(UnsupportedOperationException("not used in smoke test"))

    override suspend fun fetchRemoteConfig(experimentId: String): Result<ExperimentVariant> =
        Result.failure(UnsupportedOperationException("not used in smoke test"))

    override suspend fun appendExperimentHistory(experimentId: String) {}

    override fun storeNativeCrash(throwable: Throwable) {}

    override fun sendFlutterCrash(crashEvent: TrackCrashEvent) {}

    override fun handleNubrickEvent(it: NubrickEvent) {}

    override fun handleEvent(it: Event) {}

    override fun handleAction(action: UIBlockAction, onEvent: ((Event) -> Unit)?) {}
}
