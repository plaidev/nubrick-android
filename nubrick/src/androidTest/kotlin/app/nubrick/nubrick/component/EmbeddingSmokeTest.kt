package app.nubrick.nubrick.component

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.data.ExperimentContent
import app.nubrick.nubrick.data.NotFoundException
import app.nubrick.nubrick.schema.UIRootBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun embeddingShowsFailedState() {
        composeRule.setContent {
            Embedding(
                container = FakeContainer(Result.failure(RuntimeException("boom"))),
                experimentId = "exp",
                content = { state ->
                    Text(
                        text = when (state) {
                            is EmbeddingLoadingState.Loading -> "loading"
                            is EmbeddingLoadingState.Failed -> "failed"
                            is EmbeddingLoadingState.NotFound -> "notfound"
                            is EmbeddingLoadingState.Completed -> "completed"
                        }
                    )
                },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("failed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("failed").assertExists()
    }

    @Test
    fun embeddingShowsNotFoundState() {
        composeRule.setContent {
            Embedding(
                container = FakeContainer(Result.failure(NotFoundException())),
                experimentId = "exp",
                content = { state ->
                    Text(
                        text = when (state) {
                            is EmbeddingLoadingState.Loading -> "loading"
                            is EmbeddingLoadingState.Failed -> "failed"
                            is EmbeddingLoadingState.NotFound -> "notfound"
                            is EmbeddingLoadingState.Completed -> "completed"
                        }
                    )
                },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("notfound").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("notfound").assertExists()
    }

    @Test
    fun embeddingShowsCompletedState() {
        val content = ExperimentContent(
            experimentId = "exp",
            variantId = "variant",
            root = UIRootBlock(id = "root", data = null),
        )
        composeRule.setContent {
            Embedding(
                container = FakeContainer(Result.success(content)),
                experimentId = "exp",
                content = { state ->
                    Text(
                        text = when (state) {
                            is EmbeddingLoadingState.Loading -> "loading"
                            is EmbeddingLoadingState.Failed -> "failed"
                            is EmbeddingLoadingState.NotFound -> "notfound"
                            is EmbeddingLoadingState.Completed -> "completed"
                        }
                    )
                },
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("completed").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("completed").assertExists()
    }
}
