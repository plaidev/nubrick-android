package app.nubrick.nubrick.data

import app.nubrick.nubrick.Config
import app.nubrick.nubrick.data.database.DatabaseRepository
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.schema.ExperimentConfig
import app.nubrick.nubrick.schema.ExperimentConfigs
import app.nubrick.nubrick.schema.ExperimentFrequency
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.ExperimentVariant
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UITextBlock
import app.nubrick.nubrick.schema.UserEventFrequencyCondition
import app.nubrick.nubrick.schema.VariantConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        val container = newContainer(
            componentRepository = componentRepository,
            experimentRepository = experimentRepository,
        )

        val fetched = container.fetchEmbedding("requested-exp").getOrThrow()

        assertEquals("resolved-exp", fetched.experimentId)
        assertEquals("resolved-var", fetched.variantId)
        assertEquals(block.data, fetched.root)
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

    private fun newContainer(
        componentRepository: ComponentRepository = FakeComponentRepository(),
        experimentRepository: ExperimentRepository = FakeExperimentRepository(),
        trackRepository: FakeTrackRepository = FakeTrackRepository(),
        experimentId: String? = null,
        variantId: String? = null,
    ): ContainerImpl {
        return ContainerImpl(
            config = Config(projectId = "project-123"),
            user = fakeUser(),
            componentRepository = componentRepository,
            experimentRepository = experimentRepository,
            trackRepository = trackRepository,
            httpRequestRepository = FakeHttpRequestRepository(),
            databaseRepository = FakeDatabaseRepository(),
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
) : ExperimentRepository {
    override suspend fun fetchExperimentConfigs(id: String): Result<ExperimentConfigs> {
        return Result.success(experimentConfigs)
    }

    override suspend fun fetchTriggerExperimentConfigs(name: String): Result<ExperimentConfigs> {
        return Result.success(triggerConfigs)
    }
}

private data class SurveyResponseCall(
    val experimentId: String,
    val variantId: String,
    val responseData: String,
)

private class FakeTrackRepository : TrackRepository {
    val surveyResponses = mutableListOf<SurveyResponseCall>()

    override fun trackExperimentEvent(event: TrackExperimentEvent) = Unit
    override fun trackEvent(event: TrackUserEvent) = Unit

    override fun sendSurveyResponse(experimentId: String, variantId: String, responseData: String) {
        surveyResponses.add(SurveyResponseCall(experimentId, variantId, responseData))
    }

    override fun storeNativeCrash(throwable: Throwable) = Unit
    override fun sendFlutterCrash(crashEvent: TrackCrashEvent) = Unit
}

private class FakeHttpRequestRepository : HttpRequestRepository {
    override suspend fun request(req: CompiledHttpRequest) = Result.failure<JsonObject>(NotFoundException())
}

private class FakeDatabaseRepository : DatabaseRepository {
    override fun appendUserEvent(name: String) = Unit
    override fun appendExperimentHistory(experimentId: String) = Unit
    override fun isNotInFrequency(experimentId: String, frequency: ExperimentFrequency?) = true
    override fun isMatchedToUserEventFrequencyCondition(condition: UserEventFrequencyCondition?) = true
}
