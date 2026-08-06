package app.nubrick.nubrick.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.data.NotFoundException
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UIRootBlockData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rootWithNullDataDoesNotCrash() {
        composeRule.setContent {
            Root(
                container = FakeContainer(Result.failure(NotFoundException())),
                root = UIRootBlock(id = "root", data = null),
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rootWithEmptyPagesDoesNotCrash() {
        composeRule.setContent {
            Root(
                container = FakeContainer(Result.failure(NotFoundException())),
                root = UIRootBlock(
                    id = "root",
                    data = UIRootBlockData(pages = emptyList()),
                ),
            )
        }
        composeRule.waitForIdle()
    }
}
