package io.nubrick.nubrick.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.nubrick.nubrick.Config
import io.nubrick.nubrick.Event
import io.nubrick.nubrick.NubrickEvent
import io.nubrick.nubrick.data.database.DatabaseRepository
import io.nubrick.nubrick.data.database.DatabaseRepositoryImpl
import io.nubrick.nubrick.data.extraction.extractComponentId
import io.nubrick.nubrick.data.extraction.extractExperimentConfig
import io.nubrick.nubrick.data.extraction.extractExperimentVariant
import io.nubrick.nubrick.data.user.NubrickUser
import io.nubrick.nubrick.schema.ApiHttpHeader
import io.nubrick.nubrick.schema.ApiHttpRequest
import io.nubrick.nubrick.schema.ExperimentConfigs
import io.nubrick.nubrick.schema.ExperimentKind
import io.nubrick.nubrick.schema.ExperimentVariant
import io.nubrick.nubrick.schema.Property
import io.nubrick.nubrick.schema.UIBlock
import io.nubrick.nubrick.template.compile
import kotlinx.serialization.json.JsonElement

internal data class ExtractedVariant(
    val experimentId: String,
    val kind: ExperimentKind,
    val variant: ExperimentVariant,
)

class NotFoundException : Exception("Not found")
class FailedToDecodeException : Exception("Failed to decode")
class SkipHttpRequestException : Exception("Skip http request")

internal interface Container {
    fun initWith(arguments: Any?): Container

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
    suspend fun fetchTriggerContent(trigger: String, kinds: List<ExperimentKind>): Result<Pair<ExperimentKind, UIBlock>>
    suspend fun fetchRemoteConfig(experimentId: String): Result<ExperimentVariant>

    fun storeNativeCrash(throwable: Throwable)
    fun sendFlutterCrash(crashEvent: TrackCrashEvent)
    fun handleNubrickEvent(it: NubrickEvent)
}

internal class ContainerImpl(
    private val config: Config,
    private val user: NubrickUser,
    private val db: SQLiteDatabase,
    private val arguments: Any? = null,
    private val formRepository: FormRepository? = null,
    private val cache: CacheStore,
    private val context: Context,
) : Container {
    private val componentRepository: ComponentRepository by lazy {
        ComponentRepositoryImpl(config, cache)
    }
    private val experimentRepository: ExperimentRepository by lazy {
        ExperimentRepositoryImpl(config, cache)
    }
    private val trackRepository: TrackRepository by lazy {
        TrackRepositoryImpl(config, user)
    }
    private val httpRequestRepository: HttpRequestRepository by lazy {
        HttpRequestRepositoryImpl()
    }
    private val databaseRepository: DatabaseRepository by lazy {
        DatabaseRepositoryImpl(db)
    }

    override fun initWith(arguments: Any?): Container {
        return ContainerImpl(
            config = this.config,
            user = this.user,
            db = this.db,
            arguments = arguments,
            formRepository = FormRepositoryImpl(),
            cache = cache,
            context = this.context,
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
    ): Result<JsonElement> {
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
        return this.httpRequestRepository.request(compiledReq)
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
        this.databaseRepository.appendExperimentHistory(extracted.experimentId)
        val componentId = extractComponentId(extracted.variant) ?: return Result.failure(NotFoundException())
        val component =
            this.componentRepository.fetchComponent(extracted.experimentId, componentId).getOrElse {
                return Result.failure(it)
            }
        return Result.success(component)
    }

    override suspend fun fetchTriggerContent(trigger: String, kinds: List<ExperimentKind>): Result<Pair<ExperimentKind, UIBlock>> {
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
        this.databaseRepository.appendExperimentHistory(extracted.experimentId)
        val componentId = extractComponentId(extracted.variant) ?: return Result.failure(NotFoundException())
        val component =
            this.componentRepository.fetchComponent(extracted.experimentId, componentId).getOrElse {
                return Result.failure(it)
            }
        return Result.success(extracted.kind to component)
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
}
