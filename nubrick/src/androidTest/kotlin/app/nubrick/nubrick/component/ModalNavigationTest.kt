package app.nubrick.nubrick.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.component.bridge.UIBlockActionBridge
import app.nubrick.nubrick.data.NotFoundException
import app.nubrick.nubrick.schema.ModalScreenSize
import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.TriggerSetting
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIPageBlockData
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UIRootBlockData
import app.nubrick.nubrick.schema.UITextBlock
import app.nubrick.nubrick.schema.UITextBlockData
import app.nubrick.nubrick.schema.UITextInputBlock
import app.nubrick.nubrick.schema.UITextInputBlockData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModalNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displayCallbackFiresAfterPresentationAndNotForInternalNavigation() {
        var shown = 0
        render(
            backAction = UIBlockAction(destinationPageId = "B"),
            onShown = { shown++ },
        )
        composeRule.runOnIdle { assertEquals(1, shown) }
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithText("Page B").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, shown) }
    }

    @Test
    fun nonPresentingTriggerRootCompletesWithoutDisplay() {
        var shown = 0
        var dismissed = 0
        composeRule.setContent {
            Root(
                container = FakeContainer(Result.failure(NotFoundException())),
                root = UIRootBlock(id = "empty"),
                embeddingVisibility = false,
                onShown = { shown++ },
                onDismiss = { dismissed++ },
            )
        }
        composeRule.runOnIdle {
            assertEquals(0, shown)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun triggerRootCompletesWhenNavigationLeavesPresentedModal() {
        val bridge = UIBlockActionBridge()
        var dismissed = 0
        render(
            modalPages = listOf(
                page("A"),
                UIPageBlock(
                    id = "B",
                    data = UIPageBlockData(kind = PageKind.COMPONENT),
                ),
            ),
            eventBridge = bridge,
            embeddingVisibility = false,
            onDismiss = { dismissed++ },
        )

        composeRule.onNodeWithText("Page A").assertIsDisplayed()
        runBlocking { bridge.dispatch("""{"destinationPageId":"B"}""") }
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun firstPageCloseRunsConfiguredBackAction() {
        var dismissed = 0
        render(
            backAction = UIBlockAction(destinationPageId = "B"),
            onDismiss = { dismissed++ },
        )

        composeRule.onNodeWithText("Page A").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithText("Page B").assertIsDisplayed()
        composeRule.onNodeWithText("Page A").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test
    fun firstPageCloseWithoutActionDismisses() {
        var dismissed = 0
        render(onDismiss = { dismissed++ })

        composeRule.onNodeWithText("Page A").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithText("Page A").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun closeDismissesExpandedResizableSheetInOneTap() {
        var dismissed = 0
        render(
            modalPages = listOf(page("A", screenSize = ModalScreenSize.UNKNOWN)),
            onDismiss = { dismissed++ },
        )
        expandSheet()

        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onNodeWithText("Page A").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    private fun expandSheet() {
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            .performSemanticsAction(SemanticsActions.Expand) { it() }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse)).assertExists()
    }

    @Test
    fun replacingPageDuringExitDoesNotReuseInputState() {
        openDuringExit("C")
    }

    @Test
    fun reopeningSamePageDuringExitStartsWithFreshInputState() {
        openDuringExit("B")
    }

    private fun openDuringExit(destination: String) {
        val bridge = UIBlockActionBridge()
        render(
            modalPages = listOf(page("A"), inputPage("B"), inputPage("C")),
            eventBridge = bridge,
        )
        runBlocking { bridge.dispatch("""{"destinationPageId":"B"}""") }
        composeRule.onNodeWithText("Initial B").performTextReplacement("Edited B")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.mainClock.advanceTimeBy(32)
        // B is still composed while its exit animation is running.
        composeRule.onNodeWithText("Edited B").assertExists()
        runBlocking { bridge.dispatch("""{"destinationPageId":"$destination"}""") }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.onNodeWithText("Initial $destination").assertExists()

        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText("Initial $destination").assertIsDisplayed()
        composeRule.onNodeWithText("Edited B").assertDoesNotExist()
    }

    private fun inputPage(id: String) = UIPageBlock(
        id = id,
        data = UIPageBlockData(
            kind = PageKind.MODAL,
            modalScreenSize = ModalScreenSize.LARGE,
            renderAs = UIBlock.UnionUITextInputBlock(
                UITextInputBlock(data = UITextInputBlockData(value = "Initial $id")),
            ),
        ),
    )

    private fun render(
        backAction: UIBlockAction? = null,
        onDismiss: () -> Unit = {},
        onShown: () -> Unit = {},
        modalPages: List<UIPageBlock> = listOf(page("A", backAction), page("B")),
        eventBridge: UIBlockActionBridge? = null,
        embeddingVisibility: Boolean = true,
    ) {
        val root = UIRootBlock(
            id = "root",
            data = UIRootBlockData(
                pages = listOf(
                    UIPageBlock(
                        id = "trigger",
                        data = UIPageBlockData(
                            kind = PageKind.TRIGGER,
                            triggerSetting = TriggerSetting(
                                onTrigger = UIBlockAction(destinationPageId = "A"),
                            ),
                        ),
                    ),
                ) + modalPages,
            ),
        )
        composeRule.setContent {
            Root(
                container = FakeContainer(Result.failure(NotFoundException())),
                root = root,
                embeddingVisibility = embeddingVisibility,
                onDismiss = { onDismiss() },
                onShown = onShown,
                eventBridge = eventBridge,
            )
        }
        composeRule.waitForIdle()
    }

    private fun page(
        id: String,
        backAction: UIBlockAction? = null,
        screenSize: ModalScreenSize = ModalScreenSize.LARGE,
    ) = UIPageBlock(
        id = id,
        data = UIPageBlockData(
            kind = PageKind.MODAL,
            modalScreenSize = screenSize,
            triggerSetting = backAction?.let { TriggerSetting(onTrigger = it) },
            renderAs = UIBlock.UnionUITextBlock(
                UITextBlock(data = UITextBlockData(value = "Page $id")),
            ),
        ),
    )
}
