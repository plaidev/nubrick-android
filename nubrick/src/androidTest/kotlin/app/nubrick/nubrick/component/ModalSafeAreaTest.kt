package app.nubrick.nubrick.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.nubrick.nubrick.component.renderer.Page
import app.nubrick.nubrick.component.renderer.modalSafeAreaPadding
import app.nubrick.nubrick.schema.UIPageBlock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

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
    fun fixedTopInsetDoesNotChangeWithParentConsumption() {
        render(consumedInsets = WindowInsets(top = 100), fixedTopInset = 7.dp)
        assertContentBox(left = 17, top = 72, right = 217, bottom = 263)
    }

    @Test
    fun freezingTheAppliedTopInsetKeepsPositionAndChildConsumption() {
        val parentConsumedTop = mutableStateOf(17)
        val fixedTopInset = mutableStateOf<Dp?>(null)
        var childConsumedTop = -1
        composeRule.setContent {
            val density = LocalDensity.current
            Box(
                Modifier
                    .size(240.dp, 300.dp)
                    .consumeWindowInsets(WindowInsets(top = parentConsumedTop.value))
                    .modalSafeAreaPadding(
                        insets = WindowInsets(top = 24),
                        nonTopSides = WindowInsetsSides.Horizontal,
                        fixedTopInset = fixedTopInset.value,
                    )
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .testTag("content")
                        .onConsumedWindowInsetsChanged {
                            childConsumedTop = it.getTop(density)
                        }
                )
            }
        }

        val initialTop = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top
        composeRule.runOnIdle { assertEquals(24, childConsumedTop) }
        composeRule.runOnIdle {
            fixedTopInset.value = with(composeRule.density) { 7.toDp() }
        }
        val frozenTop = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top
        assertEquals(initialTop, frozenTop, 0f)
        composeRule.runOnIdle { assertEquals(24, childConsumedTop) }

        composeRule.runOnIdle { parentConsumedTop.value = 10 }
        val overscrolledTop = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top
        assertEquals(initialTop, overscrolledTop, 0f)
        composeRule.runOnIdle { assertEquals(24, childConsumedTop) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun measuredExpandedAnchorMatchesMaterialSheetAnchor() {
        lateinit var sheetState: SheetState
        var predictedExpandedTopPx = -1
        composeRule.setContent {
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {},
                sheetState = sheetState,
                dragHandle = {},
                contentWindowInsets = { WindowInsets(0) },
            ) {
                BoxWithConstraints {
                    val density = LocalDensity.current
                    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
                    val contentHeight = LocalConfiguration.current.screenHeightDp.dp - statusTop
                    val sheetHeightPx = with(density) { contentHeight.roundToPx() }
                        .coerceIn(0, constraints.maxHeight)
                    predictedExpandedTopPx = constraints.maxHeight - sheetHeightPx
                    Box(Modifier.height(contentHeight))
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(SheetValue.Expanded, sheetState.currentValue)
            assertEquals(predictedExpandedTopPx, sheetState.requireOffset().roundToInt())
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun fullScreenSheetExpandsToTheTopOfItsLayout() {
        lateinit var sheetState: SheetState
        lateinit var dialogInsets: WindowInsets
        lateinit var dialogDensity: Density
        var availableHeightPx = -1
        var capturedSafeTopPx = -1
        composeRule.setContent {
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {},
                sheetState = sheetState,
                dragHandle = {},
                contentWindowInsets = { WindowInsets(0) },
            ) {
                BoxWithConstraints {
                    availableHeightPx = constraints.maxHeight
                    dialogDensity = LocalDensity.current
                    dialogInsets = WindowInsets.safeDrawing
                    capturedSafeTopPx = remember(sheetState) {
                        dialogInsets.getTop(dialogDensity)
                    }
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(availableHeightPx > 0)
            assertEquals(SheetValue.Expanded, sheetState.currentValue)
            assertEquals(0, sheetState.requireOffset().roundToInt())
            assertEquals(dialogInsets.getTop(dialogDensity), capturedSafeTopPx)
        }
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
    fun nonFlexModalRootIsCenteredWithoutInsets() {
        render(renderAs = """
            { "__typename": "UITextBlock", "data": { "value": "Fallback content" } }
        """.trimIndent())
        composeRule.onNodeWithText("Fallback content").assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val text = composeRule.onNodeWithText("Fallback content").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.x, text.center.x, 1f)
        assertEquals(viewport.center.y, text.center.y, 1f)
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
        fixedTopInset: Dp? = null,
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
                        Page(
                            page,
                            isModal = isModal,
                            safeAreaInsets = insets.value,
                            fixedTopInset = fixedTopInset,
                        )
                    }
                }
            }
        }
    }

    private fun assertContentBox(left: Int, top: Int, right: Int, bottom: Int) {
        val pixels = composeRule.onNodeWithTag("viewport").captureToImage().toPixelMap()
        fun assertPixelColor(expected: Color, x: Int, y: Int) {
            val actual = pixels[x, y]
            // Allow one 8-bit color level of screenshot rendering variation at boundaries.
            // Keep the sampled positions exact so a one-pixel layout shift still fails.
            val tolerance = 1f / 255f
            assertEquals("Red at ($x, $y)", expected.red, actual.red, tolerance)
            assertEquals("Green at ($x, $y)", expected.green, actual.green, tolerance)
            assertEquals("Blue at ($x, $y)", expected.blue, actual.blue, tolerance)
            assertEquals("Alpha at ($x, $y)", expected.alpha, actual.alpha, 0f)
        }

        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        assertPixelColor(Color.Blue, left, centerY)
        assertPixelColor(Color.Red, left - 1, centerY)
        assertPixelColor(Color.Blue, right - 1, centerY)
        assertPixelColor(Color.Red, right, centerY)
        assertPixelColor(Color.Blue, centerX, top)
        assertPixelColor(Color.Red, centerX, top - 1)
        assertPixelColor(Color.Blue, centerX, bottom - 1)
        assertPixelColor(Color.Red, centerX, bottom)
        assertPixelColor(Color.Red, 0, 0)
        assertPixelColor(Color.Red, 239, 299)
    }
}
