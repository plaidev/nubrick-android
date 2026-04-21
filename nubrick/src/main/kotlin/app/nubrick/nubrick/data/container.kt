package app.nubrick.nubrick.data

import app.nubrick.nubrick.Config
import app.nubrick.nubrick.Event
import app.nubrick.nubrick.FlutterBridgeApi
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.database.DatabaseRepository
import app.nubrick.nubrick.data.extraction.extractComponentId
import app.nubrick.nubrick.data.extraction.extractExperimentConfig
import app.nubrick.nubrick.data.extraction.extractExperimentVariant
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.schema.ApiHttpHeader
import app.nubrick.nubrick.schema.ApiHttpRequest
import app.nubrick.nubrick.schema.ExperimentConfigs
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.ExperimentVariant
import app.nubrick.nubrick.schema.Property
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.template.compile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

internal data class ExtractedVariant(
    val experimentId: String,
    val kind: ExperimentKind,
    val variant: ExperimentVariant,
)

internal data class TriggerContent(
    val experimentId: String,
    val kind: ExperimentKind,
    val block: UIBlock,
)

@FlutterBridgeApi
class NotFoundException : Exception("Not found")
internal class FailedToDecodeException : Exception("Failed to decode")
internal class SkipHttpRequestException : Exception("Skip http request")

internal interface Container {
    fun initWith(arguments: Any?): Container
    fun close() {}

    fun handleEvent(it: Event) {}

    fun createVariableForTemplate(
        data: JsonElement? = null,
        properties: List<Property>? = null
    ): JsonElement

    fun getFormValues(): Map<String, JsonElement>
    fun getFormValue(key: String): FormValue?
    fun setFormValue(key: String, value: FormValue)
    fun addFormValueListener(listener: FormValueListener)
    fun removeFormValueListener(listener: FormValueListener)

    suspend fun sendHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement? = null
    ): Result<JsonElement>

    suspend fun fetchEmbedding(experimentId: String, componentId: String? = null): Result<UIBlock>
    suspend fun fetchTriggerContent(trigger: String, kinds: List<ExperimentKind>): Result<TriggerContent>
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
    private val arguments: Any? = null,
    private val formRepository: FormRepository? = null,
) : Container {

    override fun initWith(arguments: Any?): Container {
        return ContainerImpl(
            config = this.config,
            user = this.user,
            componentRepository = this.componentRepository,
            experimentRepository = this.experimentRepository,
            trackRepository = this.trackRepository,
            httpRequestRepository = this.httpRequestRepository,
            databaseRepository = this.databaseRepository,
            arguments = arguments,
            formRepository = FormRepositoryImpl(),
        )
    }


    override fun handleEvent(it: Event) {
        this.config.onEvent?.let { it1 -> it1(it) }
    }

    override fun createVariableForTemplate(
        data: JsonElement?,
        properties: List<Property>?
    ): JsonElement {
        return createVariableForTemplate(
            user = this.user,
            data = data,
            properties = properties,
            form = formRepository?.getFormData(),
            arguments = arguments,
            projectId = config.projectId,
        )
    }

    override fun getFormValues(): Map<String, JsonElement> {
        return this.formRepository?.getFormData() ?: emptyMap()
    }

    override fun getFormValue(key: String): FormValue? {
        return this.formRepository?.getValue(key)
    }

    override fun setFormValue(key: String, value: FormValue) {
        this.formRepository?.setValue(key, value)
    }

    override fun addFormValueListener(listener: FormValueListener) {
        this.formRepository?.addListener(listener)
    }

    override fun removeFormValueListener(listener: FormValueListener) {
        this.formRepository?.removeListener(listener)
    }

    override suspend fun sendHttpRequest(
        req: ApiHttpRequest,
        variable: JsonElement?
    ): Result<JsonElement> = withContext(Dispatchers.IO) {
        val mergedVariable = mergeJsonElements(variable, createVariableForTemplate())
        val compiledReq = ApiHttpRequest(
            url = req.url?.let { compile(it, mergedVariable) },
            method = req.method,
            headers = req.headers?.map {
                ApiHttpHeader(
                    compile(it.name ?: "", mergedVariable),
                    compile(it.value ?: "", mergedVariable)
                )
            },
            body = req.body?.let { compile(it, mergedVariable) },
        )
        httpRequestRepository.request(compiledReq)
    }

    override suspend fun fetchEmbedding(
        experimentId: String,
        componentId: String?
    ): Result<UIBlock> {
        if (componentId != null) {
            val component =
                this.componentRepository.fetchComponent(experimentId, componentId).getOrElse {
                    return Result.failure(it)
                }
            return Result.success(component)
        }

        val configs = this.experimentRepository.fetchExperimentConfigs(experimentId).getOrElse {
            return Result.failure(it)
        }
        val extracted = this.extractVariant(configs = configs, listOf(ExperimentKind.EMBED))
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
        return Result.success(component)
    }

    override suspend fun fetchTriggerContent(trigger: String, kinds: List<ExperimentKind>): Result<TriggerContent> {
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
        return Result.success(
            TriggerContent(
                experimentId = extracted.experimentId,
                kind = extracted.kind,
                block = component,
            )
        )
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
