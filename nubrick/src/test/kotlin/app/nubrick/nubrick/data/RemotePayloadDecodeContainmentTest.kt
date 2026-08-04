package app.nubrick.nubrick.data

import app.nubrick.nubrick.Config
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RemotePayloadDecodeContainmentTest {
    @Test
    fun fetchComponentContainsInvalidJsonAsFailedToDecode() = runBlocking {
        val network = mock(NetworkRepository::class.java)
        `when`(network.getWithCache(anyString(), anyBoolean()))
            .thenReturn(Result.success("{ this is not valid json"))

        val repository = ComponentRepositoryImpl(
            config = Config(projectId = "test"),
            networkRepository = network,
        )

        val result = repository.fetchComponent(experimentId = "exp", id = "comp")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FailedToDecodeException)
    }

    @Test
    fun fetchExperimentConfigsContainsInvalidJsonAsFailedToDecode() = runBlocking {
        val network = mock(NetworkRepository::class.java)
        `when`(network.getWithCache(anyString(), anyBoolean()))
            .thenReturn(Result.success("{ this is not valid json"))

        val repository = ExperimentRepositoryImpl(
            config = Config(projectId = "test"),
            networkRepository = network,
        )

        val result = repository.fetchExperimentConfigs("exp")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FailedToDecodeException)
    }
}
