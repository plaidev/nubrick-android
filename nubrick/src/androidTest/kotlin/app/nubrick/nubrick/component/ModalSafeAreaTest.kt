package app.nubrick.nubrick.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.nubrick.nubrick.component.renderer.Page
import app.nubrick.nubrick.schema.UIPageBlock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModalSafeAreaTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val insets = mutableStateOf(WindowInsets(left = 12, top = 24, right = 18, bottom = 32))
    private val respectSafeArea = mutableStateOf(true)

    @Test
    fun allEdgesAreInsetInsideTheBackgroundAndAuthoredPadding() {
        render()
        assertContentBox(left = 17, top = 89, right = 217, bottom = 263)
    }

    @Test
    fun insetChangesUpdateTheLayout() {
        render()
        composeRule.runOnIdle {
            insets.value = WindowInsets(left = 30, top = 0, right = 10, bottom = 16)
        }
        assertContentBox(left = 35, top = 65, right = 225, bottom = 279)
    }

    @Test
    fun disablingSafeAreaRestoresOnlyAuthoredPadding() {
        render()
        composeRule.runOnIdle { respectSafeArea.value = false }
        assertContentBox(left = 5, top = 5, right = 235, bottom = 295)
    }

    @Test
    fun hiddenNavigationDoesNotReserveHeaderSpace() {
        render(showNavigation = false)
        assertContentBox(left = 17, top = 29, right = 217, bottom = 263)
    }

    @Test
    fun parentConsumedInsetsAreNotAddedAgain() {
        // Material's sheet consumes its top offset and keyboard padding before its content.
        render(consumedInsets = WindowInsets(top = 100, bottom = 32))
        assertContentBox(left = 17, top = 65, right = 217, bottom = 295)
    }

    @Test
    fun lastScrollableItemCanBeReachedAboveTheBottomInset() {
        render(
            overflow = "SCROLL",
            children = """
                { "__typename": "UIFlexContainerBlock", "data": {
                  "frame": { "height": 400, "width": 0 }
                } },
                { "__typename": "UITextBlock", "data": { "value": "Last item" } }
            """.trimIndent(),
        )
        composeRule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 1000f)
        }
        val viewport = composeRule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val lastItem = composeRule.onNodeWithText("Last item").fetchSemanticsNode().boundsInRoot
        assertTrue(lastItem.height > 0)
        assertEquals(viewport.bottom - 37, lastItem.bottom, 1f)
        assertTrue(lastItem.top >= viewport.top + 89)
    }

    @Test
    fun unexpectedNonFlexModalRootStillRendersWithoutInsets() {
        render(renderAs = """
            { "__typename": "UITextBlock", "data": { "value": "Fallback content" } }
        """.trimIndent())
        composeRule.onNodeWithText("Fallback content").assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val text = composeRule.onNodeWithText("Fallback content").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.left, text.left, 1f)
        assertEquals(viewport.top, text.top, 1f)
    }

    @Test
    fun nonModalPageDoesNotApplyModalInsets() {
        render(isModal = false)
        assertContentBox(left = 5, top = 5, right = 235, bottom = 295)
    }

    private fun render(
        isModal: Boolean = true,
        showNavigation: Boolean = true,
        consumedInsets: WindowInsets = WindowInsets(0),
        overflow: String = "VISIBLE",
        children: String = """
            { "__typename": "UIFlexContainerBlock", "data": {
              "frame": { "width": 0, "height": 0,
                "background": { "__typename": "Color", "red": 0, "green": 0, "blue": 1, "alpha": 1 }
              }
            } }
        """.trimIndent(),
        renderAs: String = """
            { "__typename": "UIFlexContainerBlock", "data": {
              "direction": "COLUMN", "alignItems": "START", "justifyContent": "START",
              "overflow": "$overflow",
              "frame": { "width": 0, "height": 0,
                "paddingLeft": 5, "paddingTop": 5, "paddingRight": 5, "paddingBottom": 5,
                "background": { "__typename": "Color", "red": 1, "green": 0, "blue": 0, "alpha": 1 }
              },
              "children": [ $children ]
            } }
        """.trimIndent(),
    ) {
        composeRule.setContent {
            RendererSmokeHarness {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(240.dp, 300.dp).testTag("viewport").consumeWindowInsets(consumedInsets)) {
                        val page = requireNotNull(UIPageBlock.decode(Json.parseToJsonElement("""
                            { "data": { "kind": "MODAL", "modalRespectSafeArea": ${respectSafeArea.value},
                              "modalNavigationBackButton": { "visible": $showNavigation },
                              "renderAs": $renderAs
                            } }
                        """.trimIndent())))
                        Page(page, isModal = isModal, safeAreaInsets = insets.value)
                    }
                }
            }
        }
    }

    private fun assertContentBox(left: Int, top: Int, right: Int, bottom: Int) {
        val pixels = composeRule.onNodeWithTag("viewport").captureToImage().toPixelMap()
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        assertEquals(Color.Blue, pixels[left, centerY])
        assertEquals(Color.Red, pixels[left - 1, centerY])
        assertEquals(Color.Blue, pixels[right - 1, centerY])
        assertEquals(Color.Red, pixels[right, centerY])
        assertEquals(Color.Blue, pixels[centerX, top])
        assertEquals(Color.Red, pixels[centerX, top - 1])
        assertEquals(Color.Blue, pixels[centerX, bottom - 1])
        assertEquals(Color.Red, pixels[centerX, bottom])
        assertEquals(Color.Red, pixels[0, 0])
        assertEquals(Color.Red, pixels[239, 299])
    }
}
