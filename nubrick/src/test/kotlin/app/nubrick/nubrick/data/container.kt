package app.nubrick.nubrick.data

import app.nubrick.nubrick.Config
import app.nubrick.nubrick.data.database.DatabaseRepository
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.schema.ApiHttpHeader
import app.nubrick.nubrick.schema.ApiHttpRequest
import app.nubrick.nubrick.schema.ApiHttpRequestMethod
import app.nubrick.nubrick.schema.ExperimentConfig
import app.nubrick.nubrick.schema.ExperimentConfigs
import app.nubrick.nubrick.schema.ExperimentFrequency
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.ExperimentVariant
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UITextBlock
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import app.nubrick.nubrick.schema.VariantConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ContainerSurveyResponseTest {
    @Test
    fun `sendSurveyResponse sends current form data with container experiment context`() {
        val trackRepository = FakeTrackRepository()
        val container = newContainer(
            trackRepository = trackRepository,
            experimentId = "exp-123",
            variantId = "var-456",
        )

        container.setFormValue("name", FormValue.Str("Ada"))
        container.setFormValue("accepted", FormValue.Bool(true))
        container.setFormValue("choices", FormValue.StrList(listOf("a", "b")))
        container.sendSurveyResponse()

        assertEquals(1, trackRepository.surveyResponses.size)
        val sent = trackRepository.surveyResponses.single()
        assertEquals("exp-123", sent.experimentId)
        assertEquals("var-456", sent.variantId)

        val responseData = Json.decodeFromString<JsonObject>(sent.responseData)
        assertEquals("\"Ada\"", responseData["name"].toString())
        assertEquals("true", responseData["accepted"].toString())
        assertEquals("[\"a\",\"b\"]", responseData["choices"].toString())
    }

    @Test
    fun `sendSurveyResponse skips when experiment context is missing`() {
        val trackRepository = FakeTrackRepository()
        val container = newContainer(trackRepository = trackRepository)

        container.setFormValue("name", FormValue.Str("Ada"))
        container.sendSurveyResponse()

        assertTrue(trackRepository.surveyResponses.isEmpty())
    }

    @Test
    fun `handleAction sends survey response when requested`() {
        val trackRepository = FakeTrackRepository()
        val container = newContainer(
            trackRepository = trackRepository,
            experimentId = "exp-123",
            variantId = "var-456",
        )

        container.setFormValue("name", FormValue.Str("Ada"))
        container.handleAction(UIBlockAction(
            eventName = "submit",
            submitSurveyResponse = true,
        ))

        assertEquals(1, trackRepository.surveyResponses.size)
        val sent = trackRepository.surveyResponses.single()
        assertEquals("exp-123", sent.experimentId)
        assertEquals("var-456", sent.variantId)
        assertEquals("\"Ada\"", Json.decodeFromString<JsonObject>(sent.responseData)["name"].toString())
    }

    @Test
    fun `makeContainer sets experiment context and starts with independent form state`() {
        val container = newContainer(experimentId = "root-exp", variantId = "root-var")
        container.setFormValue("name", FormValue.Str("Ada"))

        val child = container.makeContainer(experimentId = "child-exp", variantId = "child-var")

        assertEquals("child-exp", child.experimentId)
        assertEquals("child-var", child.variantId)
        assertTrue(child.getFormValues().isEmpty())
        assertEquals("\"Ada\"", container.getFormValues()["name"].toString())
    }

    @Test
    fun `fetchEmbedding returns resolved experiment and variant context`() = runBlocking {
        val block = UIBlock.UnionUIRootBlock(UIRootBlock(id = "root"))
        val componentRepository = FakeComponentRepository(
            mapOf(("resolved-exp" to "component-1") to block)
        )
        val experimentRepository = FakeExperimentRepository(
            experimentConfigs = ExperimentConfigs(configs = listOf(
                ExperimentConfig(
                    id = "resolved-exp",
                    kind = ExperimentKind.EMBED,
                    baseline = ExperimentVariant(
                        id = "resolved-var",
                        configs = listOf(VariantConfig(value = "component-1")),
                    ),
                )
            ))
        )
        val databaseRepository = FakeDatabaseRepository()
        val container = newContainer(
            componentRepository = componentRepository,
            experimentRepository = experimentRepository,
            databaseRepository = databaseRepository,
        )

        val callerThread = Thread.currentThread()
        val fetched = container.fetchEmbedding("requested-exp").getOrThrow()

        assertEquals("resolved-exp", fetched.experimentId)
        assertEquals("resolved-var", fetched.variantId)
        assertEquals(block.data, fetched.root)
        assertTrue(databaseRepository.frequencyCheckThreads.single() !== callerThread)
    }

    @Test
    fun `fetchEmbedding with component id returns requested experiment and container variant context`() = runBlocking {
        val block = UIBlock.UnionUIRootBlock(UIRootBlock(id = "root"))
        val container = newContainer(
            componentRepository = FakeComponentRepository(
                mapOf(("requested-exp" to "component-1") to block)
            ),
            variantId = "selected-var",
        )

        val fetched = container.fetchEmbedding(
            experimentId = "requested-exp",
            componentId = "component-1",
        ).getOrThrow()

        assertEquals("requested-exp", fetched.experimentId)
        assertEquals("selected-var", fetched.variantId)
        assertEquals(block.data, fetched.root)
    }

    @Test
    fun `fetchEmbedding returns not found when component is not root block`() = runBlocking {
        val container = newContainer(
            componentRepository = FakeComponentRepository(
                mapOf(("requested-exp" to "component-1") to UIBlock.UnionUITextBlock(UITextBlock()))
            ),
        )

        val result = container.fetchEmbedding(
            experimentId = "requested-exp",
            componentId = "component-1",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotFoundException)
    }

    @Test
    fun `fetchEmbedding tracks experiment and appends history on success`() = runBlocking {
        val trackRepository = FakeTrackRepository()
        val databaseRepository = FakeDatabaseRepository()
        val container = newContainer(
            componentRepository = FakeComponentRepository(
                mapOf(("resolved-exp" to "component-1") to UIBlock.UnionUIRootBlock(UIRootBlock(id = "root")))
            ),
            experimentRepository = FakeExperimentRepository(
                experimentConfigs = ExperimentConfigs(configs = listOf(embedConfig()))
            ),
            trackRepository = trackRepository,
            databaseRepository = databaseRepository,
        )

        container.fetchEmbedding("requested-exp").getOrThrow()

        assertEquals(1, trackRepository.experimentEvents.size)
        assertEquals("resolved-exp", trackRepository.experimentEvents.single().experimentId)
        assertEquals("resolved-var", trackRepository.experimentEvents.single().variantId)
        assertEquals(listOf("resolved-exp"), databaseRepository.experimentHistories)
        assertTrue(databaseRepository.userEvents.isEmpty())
    }

    @Test
    fun `fetchEmbedding records track and history even when component fetch fails`() = runBlocking {
        val trackRepository = FakeTrackRepository()
        val databaseRepository = FakeDatabaseRepository()
        val container = newContainer(
            componentRepository = FakeComponentRepository(),
            experimentRepository = FakeExperimentRepository(
                experimentConfigs = ExperimentConfigs(configs = listOf(embedConfig()))
            ),
            trackRepository = trackRepository,
            databaseRepository = databaseRepository,
        )

        val result = container.fetchEmbedding("requested-exp")

        assertTrue(result.isFailure)
        assertEquals(1, trackRepository.experimentEvents.size)
        assertEquals("resolved-exp", trackRepository.experimentEvents.single().experimentId)
        assertEquals(listOf("resolved-exp"), databaseRepository.experimentHistories)
    }

    @Test
    fun `fetchEmbedding skips track and history when frequency rejects experiment`() = runBlocking {
        val trackRepository = FakeTrackRepository()
        val databaseRepository = FakeDatabaseRepository(notInFrequency = false)
        val container = newContainer(
            componentRepository = FakeComponentRepository(
                mapOf(("resolved-exp" to "component-1") to UIBlock.UnionUIRootBlock(UIRootBlock(id = "root")))
            ),
            experimentRepository = FakeExperimentRepository(
                experimentConfigs = ExperimentConfigs(configs = listOf(
                    embedConfig(frequency = ExperimentFrequency(period = 1))
                ))
            ),
            trackRepository = trackRepository,
            databaseRepository = databaseRepository,
        )

        val result = container.fetchEmbedding("requested-exp")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NotFoundException)
        assertTrue(trackRepository.experimentEvents.isEmpty())
        assertTrue(databaseRepository.experimentHistories.isEmpty())
        assertEquals(1, databaseRepository.frequencyChecks.size)
        assertEquals("resolved-exp", databaseRepository.frequencyChecks.single().first)
    }

    @Test
    fun `fetchTriggerContent includes selected variant context`() = runBlocking {
        val block = UIBlock.UnionUIRootBlock(UIRootBlock(id = "root"))
        val componentRepository = FakeComponentRepository(
            mapOf(("trigger-exp" to "component-1") to block)
        )
        val experimentRepository = FakeExperimentRepository(
            triggerConfigs = ExperimentConfigs(configs = listOf(
                ExperimentConfig(
                    id = "trigger-exp",
                    kind = ExperimentKind.POPUP,
                    baseline = ExperimentVariant(
                        id = "trigger-var",
                        configs = listOf(VariantConfig(value = "component-1")),
                    ),
                )
            ))
        )
        val container = newContainer(
            componentRepository = componentRepository,
            experimentRepository = experimentRepository,
        )

        val (content, kind) = container.fetchTriggerContent("open", listOf(ExperimentKind.POPUP)).getOrThrow()

        assertEquals(ExperimentKind.POPUP, kind)
        assertEquals("trigger-exp", content.experimentId)
        assertEquals("trigger-var", content.variantId)
        assertEquals(block.data, content.root)
    }

    @Test
    fun `fetchTriggerContent tracks user event and appends popup history`() = runBlocking {
        val trackRepository = FakeTrackRepository()
        val databaseRepository = FakeDatabaseRepository()
        val container = newContainer(
            componentRepository = FakeComponentRepository(
                mapOf(("trigger-exp" to "component-1") to UIBlock.UnionUIRootBlock(UIRootBlock(id = "root")))
            ),
            experimentRepository = FakeExperimentRepository(
                triggerConfigs = ExperimentConfigs(configs = listOf(popupConfig()))
            ),
            trackRepository = trackRepository,
            databaseRepository = databaseRepository,
        )

        container.fetchTriggerContent("open", listOf(ExperimentKind.POPUP)).getOrThrow()

        assertEquals(listOf("open"), trackRepository.userEvents.map { it.name })
        assertEquals(listOf("open"), databaseRepository.userEvents)
        assertEquals(1, trackRepository.experimentEvents.size)
        assertEquals("trigger-exp", trackRepository.experimentEvents.single().experimentId)
        assertEquals(listOf("trigger-exp"), databaseRepository.experimentHistories)
    }

    @Test
    fun `fetchTriggerContent skips experiment history for TOOLTIP`() = runBlocking {
        val trackRepository = FakeTrackRepository()
        val databaseRepository = FakeDatabaseRepository()
        val container = newContainer(
            componentRepository = FakeComponentRepository(
                mapOf(("tooltip-exp" to "component-1") to UIBlock.UnionUIRootBlock(UIRootBlock(id = "root")))
            ),
            experimentRepository = FakeExperimentRepository(
                triggerConfigs = ExperimentConfigs(configs = listOf(
                    ExperimentConfig(
                        id = "tooltip-exp",
                        kind = ExperimentKind.TOOLTIP,
                        baseline = ExperimentVariant(
                            id = "tooltip-var",
                            configs = listOf(VariantConfig(value = "component-1")),
                        ),
                    )
                ))
            ),
            trackRepository = trackRepository,
            databaseRepository = databaseRepository,
        )

        val (_, kind) = container.fetchTriggerContent(
            "open",
            listOf(ExperimentKind.TOOLTIP),
        ).getOrThrow()

        assertEquals(ExperimentKind.TOOLTIP, kind)
        assertEquals(1, trackRepository.experimentEvents.size)
        assertTrue(databaseRepository.experimentHistories.isEmpty())
        assertEquals(listOf("open"), databaseRepository.userEvents)
    }

    @Test
    fun `fetchTriggerContent records user event even when config fetch fails`() = runBlocking {
        val trackRepository = FakeTrackRepository()
        val databaseRepository = FakeDatabaseRepository()
        val container = newContainer(
            experimentRepository = FakeExperimentRepository(
                triggerResult = Result.failure(NotFoundException()),
            ),
            trackRepository = trackRepository,
            databaseRepository = databaseRepository,
        )

        val result = container.fetchTriggerContent("open", listOf(ExperimentKind.POPUP))

        assertTrue(result.isFailure)
        assertEquals(listOf("open"), trackRepository.userEvents.map { it.name })
        assertEquals(listOf("open"), databaseRepository.userEvents)
        assertTrue(trackRepository.experimentEvents.isEmpty())
        assertTrue(databaseRepository.experimentHistories.isEmpty())
    }

    @Test
    fun `fetchRemoteConfig tracks experiment and appends history`() = runBlocking {
        val trackRepository = FakeTrackRepository()
        val databaseRepository = FakeDatabaseRepository()
        val variant = ExperimentVariant(
            id = "config-var",
            configs = listOf(VariantConfig(key = "flag", value = "on")),
        )
        val container = newContainer(
            experimentRepository = FakeExperimentRepository(
                experimentConfigs = ExperimentConfigs(configs = listOf(
                    ExperimentConfig(
                        id = "config-exp",
                        kind = ExperimentKind.CONFIG,
                        baseline = variant,
                    )
                ))
            ),
            trackRepository = trackRepository,
            databaseRepository = databaseRepository,
        )

        val fetched = container.fetchRemoteConfig("config-exp").getOrThrow()

        assertEquals("config-var", fetched.id)
        assertEquals(1, trackRepository.experimentEvents.size)
        assertEquals("config-exp", trackRepository.experimentEvents.single().experimentId)
        assertEquals("config-var", trackRepository.experimentEvents.single().variantId)
        assertEquals(listOf("config-exp"), databaseRepository.experimentHistories)
    }

    @Test
    fun `compileHttpRequest compiles url headers and body templates`() {
        val container = newContainer()
        val variable = JsonObject(mapOf(
            "user" to JsonObject(mapOf("id" to JsonPrimitive("ada"))),
            "token" to JsonPrimitive("secret"),
        ))

        val compiled = container.compileHttpRequest(
            ApiHttpRequest(
                url = "https://example.com/users/{{ user.id }}",
                method = ApiHttpRequestMethod.POST,
                headers = listOf(
                    ApiHttpHeader(name = "X-Token", value = "{{ token }}"),
                ),
                body = "{\"id\":\"{{ user.id }}\"}",
            ),
            variable,
        )

        assertEquals("https://example.com/users/ada", compiled.url)
        assertEquals(ApiHttpRequestMethod.POST, compiled.method)
        assertEquals(listOf(CompiledHttpHeader(name = "X-Token", value = "secret")), compiled.headers)
        assertEquals("{\"id\":\"ada\"}", compiled.body)
    }

    @Test
    fun `sendCompiledHttpRequest delegates to http request repository`() = runBlocking {
        val httpRequestRepository = FakeHttpRequestRepository(
            response = Result.success(JsonObject(mapOf("ok" to JsonPrimitive(true)))),
        )
        val container = newContainer(httpRequestRepository = httpRequestRepository)
        val req = CompiledHttpRequest(
            url = "https://example.com",
            method = ApiHttpRequestMethod.GET,
            headers = emptyList(),
            body = null,
        )

        val result = container.sendCompiledHttpRequest(req).getOrThrow()

        assertEquals(listOf(req), httpRequestRepository.requests)
        assertEquals(JsonObject(mapOf("ok" to JsonPrimitive(true))), result)
    }

    @Test
    fun `appendExperimentHistory forwards to database repository`() = runBlocking {
        val databaseRepository = FakeDatabaseRepository()
        val container = newContainer(databaseRepository = databaseRepository)

        container.appendExperimentHistory("exp-1")

        assertEquals(listOf("exp-1"), databaseRepository.experimentHistories)
    }

    private fun embedConfig(
        frequency: ExperimentFrequency? = null,
    ): ExperimentConfig {
        return ExperimentConfig(
            id = "resolved-exp",
            kind = ExperimentKind.EMBED,
            baseline = ExperimentVariant(
                id = "resolved-var",
                configs = listOf(VariantConfig(value = "component-1")),
            ),
            frequency = frequency,
        )
    }

    private fun popupConfig(): ExperimentConfig {
        return ExperimentConfig(
            id = "trigger-exp",
            kind = ExperimentKind.POPUP,
            baseline = ExperimentVariant(
                id = "trigger-var",
                configs = listOf(VariantConfig(value = "component-1")),
            ),
        )
    }

    private fun newContainer(
        componentRepository: ComponentRepository = FakeComponentRepository(),
        experimentRepository: ExperimentRepository = FakeExperimentRepository(),
        trackRepository: FakeTrackRepository = FakeTrackRepository(),
        httpRequestRepository: FakeHttpRequestRepository = FakeHttpRequestRepository(),
        databaseRepository: FakeDatabaseRepository = FakeDatabaseRepository(),
        experimentId: String? = null,
        variantId: String? = null,
    ): ContainerImpl {
        return ContainerImpl(
            config = Config(projectId = "project-123"),
            user = fakeUser(),
            componentRepository = componentRepository,
            experimentRepository = experimentRepository,
            trackRepository = trackRepository,
            httpRequestRepository = httpRequestRepository,
            databaseRepository = databaseRepository,
            experimentId = experimentId,
            variantId = variantId,
        )
    }

    private fun fakeUser(): NubrickUser {
        val user = Mockito.mock(NubrickUser::class.java)
        Mockito.`when`(user.toUserProperties(Mockito.any())).thenReturn(emptyList())
        Mockito.`when`(user.getNormalizedUserRnd(Mockito.any())).thenReturn(0.0)
        return user
    }
}

private class FakeComponentRepository(
    private val components: Map<Pair<String, String>, UIBlock> = emptyMap(),
) : ComponentRepository {
    override suspend fun fetchComponent(experimentId: String, id: String): Result<UIBlock> {
        return components[experimentId to id]?.let { Result.success(it) }
            ?: Result.failure(NotFoundException())
    }
}

private class FakeExperimentRepository(
    private val experimentConfigs: ExperimentConfigs = ExperimentConfigs(configs = emptyList()),
    private val triggerConfigs: ExperimentConfigs = ExperimentConfigs(configs = emptyList()),
    private val triggerResult: Result<ExperimentConfigs>? = null,
) : ExperimentRepository {
    override suspend fun fetchExperimentConfigs(id: String): Result<ExperimentConfigs> {
        return Result.success(experimentConfigs)
    }

    override suspend fun fetchTriggerExperimentConfigs(name: String): Result<ExperimentConfigs> {
        return triggerResult ?: Result.success(triggerConfigs)
    }
}

private data class SurveyResponseCall(
    val experimentId: String,
    val variantId: String,
    val responseData: String,
)

private class FakeTrackRepository : TrackRepository {
    val surveyResponses = mutableListOf<SurveyResponseCall>()
    val experimentEvents = mutableListOf<TrackExperimentEvent>()
    val userEvents = mutableListOf<TrackUserEvent>()

    override suspend fun trackExperimentEvent(event: TrackExperimentEvent) {
        experimentEvents.add(event)
    }

    override suspend fun trackEvent(event: TrackUserEvent) {
        userEvents.add(event)
    }

    override fun sendSurveyResponse(experimentId: String, variantId: String, responseData: String) {
        surveyResponses.add(SurveyResponseCall(experimentId, variantId, responseData))
    }

    override fun storeNativeCrash(throwable: Throwable) = Unit
    override fun sendFlutterCrash(crashEvent: TrackCrashEvent) = Unit
}

private class FakeHttpRequestRepository(
    private val response: Result<kotlinx.serialization.json.JsonElement> = Result.failure(NotFoundException()),
) : HttpRequestRepository {
    val requests = mutableListOf<CompiledHttpRequest>()

    override suspend fun request(req: CompiledHttpRequest): Result<kotlinx.serialization.json.JsonElement> {
        requests.add(req)
        return response
    }
}

private class FakeDatabaseRepository(
    private val notInFrequency: Boolean = true,
    private val matchedEventFrequency: Boolean = true,
) : DatabaseRepository {
    val userEvents = mutableListOf<String>()
    val experimentHistories = mutableListOf<String>()
    val frequencyChecks = mutableListOf<Pair<String, ExperimentFrequency?>>()
    val frequencyCheckThreads = mutableListOf<Thread>()

    override suspend fun appendUserEvent(name: String) {
        userEvents.add(name)
    }

    override suspend fun appendExperimentHistory(experimentId: String) {
        experimentHistories.add(experimentId)
    }

    override suspend fun isNotInFrequency(experimentId: String, frequency: ExperimentFrequency?): Boolean {
        frequencyChecks.add(experimentId to frequency)
        frequencyCheckThreads.add(Thread.currentThread())
        return notInFrequency
    }

    override suspend fun isMatchedToUserEventFrequencyCondition(condition: UserEventFrequencyCondition?): Boolean {
        return matchedEventFrequency
    }
}
