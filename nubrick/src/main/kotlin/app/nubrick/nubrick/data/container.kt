package app.nubrick.nubrick.data

import app.nubrick.nubrick.Config
import app.nubrick.nubrick.Event
import app.nubrick.nubrick.EventProperty
import app.nubrick.nubrick.EventPropertyType
import app.nubrick.nubrick.FlutterBridgeApi
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.database.DatabaseRepository
import app.nubrick.nubrick.data.extraction.extractComponentId
import app.nubrick.nubrick.data.extraction.extractExperimentConfig
import app.nubrick.nubrick.data.extraction.extractExperimentVariant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.schema.ApiHttpRequest
import app.nubrick.nubrick.schema.ExperimentConfigs
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.ExperimentVariant
import app.nubrick.nubrick.schema.Property
import app.nubrick.nubrick.schema.PropertyType
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.template.compile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class ExtractedVariant(
    val experimentId: String,
    val kind: ExperimentKind,
    val variant: ExperimentVariant,
)

@FlutterBridgeApi
class ExperimentContent internal constructor(
    internal val experimentId: String,
    internal val variantId: String?,
    internal val root: UIRootBlock,
)

@FlutterBridgeApi
class NotFoundException : Exception("Not found")
internal class FailedToDecodeException : Exception("Failed to decode")
internal class SkipHttpRequestException : Exception("Skip http request")

internal interface Container {
    val experimentId: String?
    val variantId: String?

    fun makeContainer(): Container
    fun makeContainer(experimentId: String?, variantId: String?): Container
    fun close() {}

    fun handleEvent(it: Event) {}
    fun handleAction(action: UIBlockAction, onEvent: ((Event) -> Unit)? = null) {}

    @Composable
    fun rememberVariableForTemplate(
        data: JsonElement?,
        pageProperties: List<Property>?,
        arguments: Any?,
    ): JsonElement

    val formValuesFlow: StateFlow<Map<String, FormValue>>
    fun getFormValues(): Map<String, JsonElement>
    fun getFormValue(key: String): FormValue?
    fun setFormValue(key: String, value: FormValue)
    fun sendSurveyResponse()

    fun compileHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement,
    ): CompiledHttpRequest

    suspend fun sendCompiledHttpRequest(req: CompiledHttpRequest): Result<JsonElement>

    suspend fun sendHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement,
    ): Result<JsonElement>

    suspend fun fetchEmbedding(
        experimentId: String,
        componentId: String? = null,
    ): Result<ExperimentContent>
    suspend fun fetchTriggerContent(
        trigger: String,
        kinds: List<ExperimentKind>,
    ): Result<Pair<ExperimentContent, ExperimentKind>>
    suspend fun fetchRemoteConfig(experimentId: String): Result<ExperimentVariant>
    fun appendExperimentHistory(experimentId: String)

    fun storeNativeCrash(throwable: Throwable)
    fun sendFlutterCrash(crashEvent: TrackCrashEvent)
    fun handleNubrickEvent(it: NubrickEvent)
}

internal class ContainerImpl(
    private val config: Config,
    private val user: NubrickUser,
    private val componentRepository: ComponentRepository,
    private val experimentRepository: ExperimentRepository,
    private val trackRepository: TrackRepository,
    private val httpRequestRepository: HttpRequestRepository,
    private val databaseRepository: DatabaseRepository,
    private val formRepository: FormRepository = FormRepositoryImpl(),
    override val experimentId: String? = null,
    override val variantId: String? = null,
) : Container {
    override fun makeContainer(): Container {
        return makeContainer(experimentId = this.experimentId, variantId = this.variantId)
    }

    override fun makeContainer(experimentId: String?, variantId: String?): Container {
        return ContainerImpl(
            config = this.config,
            user = this.user,
            componentRepository = this.componentRepository,
            experimentRepository = this.experimentRepository,
            trackRepository = this.trackRepository,
            httpRequestRepository = this.httpRequestRepository,
            databaseRepository = this.databaseRepository,
            experimentId = experimentId,
            variantId = variantId,
        )
    }

    override fun handleEvent(it: Event) {
        this.config.onEvent?.let { it1 -> it1(it) }
    }

    override fun handleAction(action: UIBlockAction, onEvent: ((Event) -> Unit)?) {
        if (action.submitSurveyResponse == true) {
            sendSurveyResponse()
        }

        val event = action.toEvent()
        onEvent?.invoke(event)
        handleEvent(event)
    }

    @Composable
    override fun rememberVariableForTemplate(
        data: JsonElement?,
        pageProperties: List<Property>?,
        arguments: Any?,
    ): JsonElement {
        val userState by user.state.collectAsStateWithLifecycle()
        val userProperties = userState.templateProperties
        val formValues by formValuesFlow.collectAsStateWithLifecycle()
        val formData = formValues.toFormData()
        return remember(this, data, pageProperties, userProperties, formData, arguments) {
            createVariableForTemplate(
                data = data,
                pageProperties = pageProperties,
                formData = formData,
                userProperties = userProperties,
                arguments = arguments,
                projectId = config.projectId,
            )
        }
    }

    override val formValuesFlow: StateFlow<Map<String, FormValue>> =
        formRepository.formValues

    override fun getFormValues(): Map<String, JsonElement> {
        return this.formRepository.getFormData()
    }

    override fun getFormValue(key: String): FormValue? {
        return this.formRepository.getValue(key)
    }

    override fun setFormValue(key: String, value: FormValue) {
        this.formRepository.setValue(key, value)
    }

    override suspend fun sendHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement,
    ): Result<JsonElement> {
        return sendCompiledHttpRequest(compileHttpRequest(req, variable))
    }

    override fun compileHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement,
    ): CompiledHttpRequest {
        return CompiledHttpRequest(
            url = req.url?.let { compile(it, variable) },
            method = req.method,
            headers = req.headers?.map {
                CompiledHttpHeader(
                    name = compile(it.name ?: "", variable),
                    value = compile(it.value ?: "", variable),
                )
            } ?: emptyList(),
            body = req.body?.let { compile(it, variable) },
        )
    }

    override suspend fun sendCompiledHttpRequest(req: CompiledHttpRequest): Result<JsonElement> = withContext(Dispatchers.IO) {
        httpRequestRepository.request(req)
    }

    override suspend fun fetchEmbedding(
        experimentId: String,
        componentId: String?,
    ): Result<ExperimentContent> {
        if (componentId != null) {
            val component =
                this.componentRepository.fetchComponent(experimentId, componentId).getOrElse {
                    return Result.failure(it)
                }
            return rootContent(
                experimentId = experimentId,
                variantId = this.variantId,
                block = component,
            )
        }

        val configs = this.experimentRepository.fetchExperimentConfigs(experimentId).getOrElse {
            return Result.failure(it)
        }
        val extracted = this.extractVariant(configs = configs, listOf(ExperimentKind.EMBED))
            .getOrElse {
                return Result.failure(it)
            }
        val selectedVariantId = extracted.variant.id ?: return Result.failure(NotFoundException())
        this.trackRepository.trackExperimentEvent(
            TrackExperimentEvent(
                experimentId = extracted.experimentId,
                variantId = selectedVariantId
            )
        )
        // Tooltip is a Flutter-only flow. Persist tooltip history only after
        // Flutter confirms the tooltip actually started rendering.
        if (extracted.kind != ExperimentKind.TOOLTIP) {
            this.databaseRepository.appendExperimentHistory(extracted.experimentId)
        }
        val componentId = extractComponentId(extracted.variant) ?: return Result.failure(NotFoundException())
        val component =
            this.componentRepository.fetchComponent(extracted.experimentId, componentId).getOrElse {
                return Result.failure(it)
            }
        return rootContent(
            experimentId = extracted.experimentId,
            variantId = selectedVariantId,
            block = component,
        )
    }

    override suspend fun fetchTriggerContent(
        trigger: String,
        kinds: List<ExperimentKind>,
    ): Result<Pair<ExperimentContent, ExperimentKind>> {
        // send the user track event and save it to database
        this.trackRepository.trackEvent(TrackUserEvent(trigger))
        this.databaseRepository.appendUserEvent(trigger)

        // fetch config from cdn
        val configs = this.experimentRepository.fetchTriggerExperimentConfigs(trigger).getOrElse {
            return Result.failure(it)
        }

        // select the best matching config for the specified kinds
        val extracted = this.extractVariant(configs = configs, kinds)
            .getOrElse {
                return Result.failure(it)
            }
        val variantId = extracted.variant.id ?: return Result.failure(NotFoundException())
        this.trackRepository.trackExperimentEvent(
            TrackExperimentEvent(
                experimentId = extracted.experimentId,
                variantId = variantId
            )
        )
        // Tooltip is a Flutter-only flow. Persist tooltip history only after
        // Flutter confirms the tooltip actually started rendering.
        if (extracted.kind != ExperimentKind.TOOLTIP) {
            this.databaseRepository.appendExperimentHistory(extracted.experimentId)
        }
        val componentId = extractComponentId(extracted.variant) ?: return Result.failure(NotFoundException())
        val component =
            this.componentRepository.fetchComponent(extracted.experimentId, componentId).getOrElse {
                return Result.failure(it)
            }
        return rootContent(
            experimentId = extracted.experimentId,
            variantId = variantId,
            block = component,
        ).map { content ->
            content to extracted.kind
        }
    }

    override suspend fun fetchRemoteConfig(experimentId: String): Result<ExperimentVariant> {
        val configs = this.experimentRepository.fetchExperimentConfigs(experimentId).getOrElse {
            return Result.failure(it)
        }
        val extracted = this.extractVariant(configs = configs, listOf(ExperimentKind.CONFIG))
            .getOrElse {
                return Result.failure(it)
            }
        val variantId = extracted.variant.id ?: return Result.failure(NotFoundException())
        this.trackRepository.trackExperimentEvent(
            TrackExperimentEvent(
                experimentId = extracted.experimentId,
                variantId = variantId
            )
        )
        this.databaseRepository.appendExperimentHistory(extracted.experimentId)
        return Result.success(extracted.variant)
    }

    override fun appendExperimentHistory(experimentId: String) {
        this.databaseRepository.appendExperimentHistory(experimentId)
    }

    override fun sendSurveyResponse() {
        val experimentId = this.experimentId ?: return
        val variantId = this.variantId ?: return
        val responseData = JsonObject(this.formRepository.getFormData()).toString()
        this.trackRepository.sendSurveyResponse(
            experimentId = experimentId,
            variantId = variantId,
            responseData = responseData,
        )
    }

    private fun rootContent(
        experimentId: String,
        variantId: String?,
        block: UIBlock,
    ): Result<ExperimentContent> {
        return when (block) {
            is UIBlock.UnionUIRootBlock -> Result.success(ExperimentContent(
                experimentId = experimentId,
                variantId = variantId,
                root = block.data,
            ))
            else -> Result.failure(NotFoundException())
        }
    }

    private fun extractVariant(
        configs: ExperimentConfigs,
        kinds: List<ExperimentKind>,
    ): Result<ExtractedVariant> {
        val config = extractExperimentConfig(
            configs = configs,
            kinds = kinds,
            properties = { seed -> this.user.toUserProperties(seed) },
            isNotInFrequency = { experimentId, frequency ->
                this.databaseRepository.isNotInFrequency(experimentId, frequency)
            },
            isMatchedToUserEventFrequencyConditions = { conditions ->
                val _conditions = conditions ?: return@extractExperimentConfig true
                _conditions.all { condition ->
                    this.databaseRepository.isMatchedToUserEventFrequencyCondition(condition)
                }
            }
        ) ?: return Result.failure(NotFoundException())
        val experimentId = config.id ?: return Result.failure(NotFoundException())
        val kind = config.kind ?: return Result.failure(NotFoundException())

        val normalizedUserRnd = this.user.getNormalizedUserRnd(config.seed)
        val variant = extractExperimentVariant(
            config = config,
            normalizedUserRnd = normalizedUserRnd
        ) ?: return Result.failure(NotFoundException())

        return Result.success(ExtractedVariant(experimentId, kind, variant))
    }

    override fun storeNativeCrash(throwable: Throwable) {
        this.trackRepository.storeNativeCrash(throwable)
    }

    override fun sendFlutterCrash(crashEvent: TrackCrashEvent) {
        this.trackRepository.sendFlutterCrash(crashEvent)
    }

    override fun handleNubrickEvent(it: NubrickEvent) {
        this.config.onDispatch?.let { handle ->
            handle(it)
        }
    }

    override fun close() {
        trackRepository.close()
    }
}

private fun UIBlockAction.toEvent(): Event {
    return Event(
        name = eventName,
        deepLink = deepLink,
        payload = payload?.map { p ->
            EventProperty(
                name = p.name ?: "",
                value = p.value ?: "",
                type = when (p.ptype) {
                    PropertyType.INTEGER -> EventPropertyType.INTEGER
                    PropertyType.STRING -> EventPropertyType.STRING
                    PropertyType.TIMESTAMPZ -> EventPropertyType.TIMESTAMPZ
                    else -> EventPropertyType.UNKNOWN
                }
            )
        }
    )
}
