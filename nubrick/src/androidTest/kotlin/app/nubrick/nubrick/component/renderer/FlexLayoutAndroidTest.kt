package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.nubrick.nubrick.component.provider.data.DataProvider
import app.nubrick.nubrick.component.provider.data.DataState
import app.nubrick.nubrick.component.provider.container.ContainerProvider
import app.nubrick.nubrick.component.provider.event.EventListenerProvider
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.schema.CollectionKind
import app.nubrick.nubrick.schema.UICollectionBlock
import app.nubrick.nubrick.schema.UICollectionBlockData
import app.nubrick.nubrick.schema.AlignItems
import app.nubrick.nubrick.schema.Color as SchemaColor
import app.nubrick.nubrick.schema.ColorValue
import app.nubrick.nubrick.schema.FlexDirection
import app.nubrick.nubrick.schema.FrameData
import app.nubrick.nubrick.schema.JustifyContent
import app.nubrick.nubrick.schema.Overflow
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIFlexContainerBlock
import app.nubrick.nubrick.schema.UIFlexContainerBlockData
import app.nubrick.nubrick.schema.UISelectInputBlock
import app.nubrick.nubrick.schema.UITextBlock
import app.nubrick.nubrick.schema.UITextBlockData
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import kotlin.math.roundToInt

class FlexLayoutAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val container = Mockito.mock(Container::class.java)

    @Test
    fun visibleOverflow_keepsFixedChildrenAtTheirDeclaredPositions() {
        render(
            flex(
                width = 100,
                height = 40,
                overflow = Overflow.VISIBLE,
                gap = 10,
                justifyContent = JustifyContent.START,
                children = listOf(text("first", 80, 20), text("second", 80, 20)),
            )
        )

        assertPosition("first", x = 0, y = 10)
        assertPosition("second", x = 90, y = 10)
    }

    @Test
    fun rowFillChildren_splitSpaceRemainingAfterFixedChildrenAndGaps() {
        render(
            flex(
                width = 240,
                height = 40,
                gap = 10,
                justifyContent = JustifyContent.START,
                alignItems = AlignItems.START,
                children = listOf(
                    text("fixed", 50, 20),
                    text("fill-one", 0, 20),
                    text("fill-two", 0, 20),
                ),
            )
        )

        assertPosition("fixed", x = 0, y = 0)
        assertPosition("fill-one", x = 60, y = 0)
        assertPosition("fill-two", x = 155, y = 0)
    }

    @Test
    fun columnFillChildren_splitSpaceRemainingAfterFixedChildrenAndGaps() {
        render(
            flex(
                width = 40,
                height = 240,
                direction = FlexDirection.COLUMN,
                gap = 10,
                justifyContent = JustifyContent.START,
                alignItems = AlignItems.START,
                children = listOf(
                    text("fixed", 20, 50),
                    text("fill-one", 20, 0),
                    text("fill-two", 20, 0),
                ),
            )
        )

        assertPosition("fixed", x = 0, y = 0)
        assertPosition("fill-one", x = 0, y = 60)
        assertPosition("fill-two", x = 0, y = 155)
    }

    @Test
    fun fillChildrenCanCollapseToZeroAfterCappedFixedChildrenExhaustTheParent() {
        render(
            flex(
                width = 100,
                height = 40,
                justifyContent = JustifyContent.START,
                alignItems = AlignItems.START,
                children = listOf(
                    text("fixed", 120, 20),
                    text("empty-fill", 0, 20),
                ),
            )
        )

        // BasicText has no layout height once its parent is zero-width, so
        // verify the rendered text node's main-axis collapse only. The frame
        // itself is intentionally not exposed in the semantics tree.
        val bounds = bounds("empty-fill")
        assertEquals(0f, (bounds.right - bounds.left).value, 0.5f)
    }

    @Test
    fun omittedDirectionDefaultsToRow() {
        render(
            flex(
                width = 120,
                height = 40,
                direction = null,
                gap = 10,
                justifyContent = JustifyContent.START,
                alignItems = AlignItems.START,
                children = listOf(text("first", 50, 20), text("second", 50, 20)),
            )
        )

        assertPosition("first", x = 0, y = 0)
        assertPosition("second", x = 60, y = 0)
    }

    @Test
    fun rtlScroll_startsAtTheFirstAuthoredChild() {
        val block = flex(
            width = 100,
            height = 40,
            overflow = Overflow.SCROLL,
            gap = 10,
            justifyContent = JustifyContent.START,
            alignItems = AlignItems.START,
            children = listOf(
                text("first", 80, 20, ComposeColor.Red.toSchemaColor()),
                text("second", 80, 20, ComposeColor.Green.toSchemaColor()),
            ),
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                content(block)
            }
        }

        assertEquals(ComposeColor.Red, pixelAt(x = 320, y = 1))
        assertEquals(ComposeColor.Green, pixelAt(x = 395, y = 1))
    }

    @Test
    fun columnEndAlignment_andEndJustification_applyOnTheirRespectiveAxes() {
        render(
            flex(
                width = 100,
                height = 200,
                direction = FlexDirection.COLUMN,
                justifyContent = JustifyContent.END,
                alignItems = AlignItems.END,
                gap = 10,
                children = listOf(text("top", 20, 30), text("bottom", 20, 30)),
            )
        )

        assertPosition("top", x = 80, y = 130)
        assertPosition("bottom", x = 80, y = 170)
    }

    @Test
    fun spaceBetween_preservesTheMinimumGapAndDistributesExtraSpace() {
        val block = flex(
            width = 301,
            height = 40,
            justifyContent = JustifyContent.SPACE_BETWEEN,
            gap = 10,
            alignItems = AlignItems.START,
            children = listOf(
                text("left", 50, 20, background = ComposeColor.Red.toSchemaColor()),
                text("middle", 50, 20, background = ComposeColor.Green.toSchemaColor()),
                text("right", 50, 20, background = ComposeColor.Blue.toSchemaColor()),
            ),
        )

        // BasicText's semantics bounds do not represent its parent flex item.
        // Check the frame backgrounds so this verifies the actual item offsets.
        composeRule.setContent {
            content(
                block,
                background = ComposeColor.Black,
            )
        }
        composeRule.waitForIdle()
        assertEquals(ComposeColor.Red, pixelAt(x = 25, y = 1))
        assertEquals(ComposeColor.Green, pixelAt(x = 150, y = 1))
        assertEquals(ComposeColor.Blue, pixelAt(x = 275, y = 1))
        assertEquals(ComposeColor.Blue, pixelAt(x = 300, y = 1))
    }

    @Test
    fun missingSelectData_doesNotDesynchronizeFillChildMeasurement() {
        render(
            flex(
                width = 100,
                height = 40,
                alignItems = AlignItems.START,
                children = listOf(
                    UIBlock.UnionUISelectInputBlock(UISelectInputBlock(data = null)),
                    text("fill", 0, 20),
                ),
            )
        )

        assertPosition("fill", x = 0, y = 0)
    }

    @Test
    fun scrollRows_keepTheDeclaredGapInsteadOfDiscardingItForSpaceBetween() {
        render(
            flex(
                width = 100,
                height = 40,
                overflow = Overflow.SCROLL,
                justifyContent = JustifyContent.SPACE_BETWEEN,
                gap = 10,
                alignItems = AlignItems.START,
                children = listOf(text("scroll-first", 50, 20), text("scroll-second", 50, 20)),
            )
        )

        assertPosition("scroll-first", x = 0, y = 0)
        assertPosition("scroll-second", x = 60, y = 0)
    }

    @Test
    fun scrollColumns_keepTheDeclaredGapInsteadOfDiscardingItForSpaceBetween() {
        render(
            flex(
                width = 40,
                height = 100,
                direction = FlexDirection.COLUMN,
                overflow = Overflow.SCROLL,
                justifyContent = JustifyContent.SPACE_BETWEEN,
                gap = 10,
                alignItems = AlignItems.START,
                children = listOf(text("scroll-top", 20, 50), text("scroll-bottom", 20, 50)),
            )
        )

        assertPosition("scroll-top", x = 0, y = 0)
        assertPosition("scroll-bottom", x = 0, y = 60)
    }

    @Test
    fun scrollRows_fillChildrenUseFreeViewportSpaceBeforeOverflowing() {
        val block = flex(
            width = 100,
            height = 40,
            overflow = Overflow.SCROLL,
            justifyContent = JustifyContent.START,
            alignItems = AlignItems.START,
            children = listOf(
                text("fixed", 50, 20, background = ComposeColor.Red.toSchemaColor()),
                text("fill", 0, 20, background = ComposeColor.Green.toSchemaColor()),
            ),
        )

        composeRule.setContent {
            content(block, background = ComposeColor.Black)
        }
        composeRule.waitForIdle()

        assertEquals(ComposeColor.Red, pixelAt(x = 25, y = 1))
        assertEquals(ComposeColor.Green, pixelAt(x = 75, y = 1))
    }

    @Test
    fun scrollRows_fillChildrenCollapseAfterFixedChildrenOverflowTheViewport() {
        render(
            flex(
                width = 100,
                height = 40,
                overflow = Overflow.SCROLL,
                justifyContent = JustifyContent.START,
                alignItems = AlignItems.START,
                children = listOf(
                    text("fixed", 120, 20),
                    text("empty-fill", 0, 20),
                ),
            )
        )

        val bounds = bounds("empty-fill")
        assertEquals(0f, (bounds.right - bounds.left).value, 0.5f)
    }

    @Test
    fun scrollRows_hugTheirContentUntilItOverflowsTheViewport() {
        val size = renderAndMeasureFlex(
            flex(
                width = null,
                height = 40,
                overflow = Overflow.SCROLL,
                justifyContent = JustifyContent.START,
                children = listOf(text("small", 20, 20)),
            )
        )

        assertEquals(20f, size.width, 0.5f)
    }

    @Test
    fun scrollColumns_hugTheirContentUntilItOverflowsTheViewport() {
        val size = renderAndMeasureFlex(
            flex(
                width = 40,
                height = null,
                direction = FlexDirection.COLUMN,
                overflow = Overflow.SCROLL,
                justifyContent = JustifyContent.START,
                children = listOf(text("small", 20, 20)),
            )
        )

        assertEquals(20f, size.height, 0.5f)
    }

    @Test
    fun hiddenOverflow_preservesGeometryForChildrenWithinTheFrame() {
        val children = listOf(text("first", 80, 20))
        val visible = flex(
            width = 100,
            height = 40,
            overflow = Overflow.VISIBLE,
            gap = 10,
            alignItems = AlignItems.START,
            children = children,
        )
        val hidden = flex(
            width = 100,
            height = 40,
            overflow = Overflow.HIDDEN,
            gap = 10,
            alignItems = AlignItems.START,
            children = children,
        )

        var displayedBlock by mutableStateOf(visible)
        composeRule.setContent {
            content(displayedBlock)
        }
        val visibleFirst = bounds("first")
        displayedBlock = hidden
        composeRule.waitForIdle()

        assertEquals(visibleFirst, bounds("first"))
    }

    @Test
    fun hiddenOverflow_clipsChildrenOutsideTheFrame() {
        val children = listOf(
            text("first", 80, 20),
            text(
                value = "second",
                width = 80,
                height = 20,
                background = ColorValue.UnionColor(
                    SchemaColor(red = 1f, green = 0f, blue = 0f, alpha = 1f)
                ),
            ),
        )
        val hidden = flex(
            width = 100,
            height = 40,
            overflow = Overflow.HIDDEN,
            gap = 10,
            alignItems = AlignItems.START,
            children = children,
        )

        composeRule.setContent {
            content(hidden, background = ComposeColor.Black)
        }
        composeRule.waitForIdle()
        assertEquals(ComposeColor.Red, pixelAt(x = 95, y = 10))
        assertEquals(ComposeColor.Black, pixelAt(x = 110, y = 10))
    }

    @Test
    fun omittedOverflow_defaultsToHidden() {
        val block = flex(
            width = 100,
            height = 40,
            gap = 10,
            alignItems = AlignItems.START,
            children = listOf(
                text("first", 80, 20),
                text(
                    value = "second",
                    width = 80,
                    height = 20,
                    background = ComposeColor.Red.toSchemaColor(),
                ),
            ),
        )

        composeRule.setContent {
            content(block, background = ComposeColor.Black)
        }
        composeRule.waitForIdle()
        assertEquals(ComposeColor.Black, pixelAt(x = 110, y = 10))
    }

    @Test
    fun unknownOverflow_defaultsToHidden() {
        val block = flex(
            width = 100,
            height = 40,
            overflow = Overflow.UNKNOWN,
            gap = 10,
            alignItems = AlignItems.START,
            children = listOf(
                text("first", 80, 20),
                text(
                    value = "second",
                    width = 80,
                    height = 20,
                    background = ComposeColor.Red.toSchemaColor(),
                ),
            ),
        )

        composeRule.setContent {
            content(block, background = ComposeColor.Black)
        }
        composeRule.waitForIdle()
        assertEquals(ComposeColor.Black, pixelAt(x = 110, y = 10))
    }

    @Test
    fun nestedFlexes_keepIndependentGapsAndAlignment() {
        val nested = UIBlock.UnionUIFlexContainerBlock(
            UIFlexContainerBlock(
                data = UIFlexContainerBlockData(
                    direction = FlexDirection.COLUMN,
                    justifyContent = JustifyContent.START,
                    alignItems = AlignItems.END,
                    gap = 5,
                    frame = FrameData(width = 80, height = 50),
                    children = listOf(text("nested-one", 20, 20), text("nested-two", 30, 20)),
                )
            )
        )
        render(
            flex(
                width = 200,
                height = 60,
                gap = 10,
                justifyContent = JustifyContent.START,
                alignItems = AlignItems.START,
                children = listOf(text("leading", 30, 20), nested),
            )
        )

        assertPosition("leading", x = 0, y = 0)
        assertPosition("nested-one", x = 100, y = 0)
        assertPosition("nested-two", x = 90, y = 25)
    }

    @Test
    fun scrollRow_supportsIntrinsicHeight() {
        composeRule.setContent {
            content(
                block = flex(
                    width = 100,
                    height = null,
                    overflow = Overflow.SCROLL,
                    children = listOf(text("intrinsic", 20, 20)),
                ),
                flexModifier = Modifier.height(IntrinsicSize.Min),
            )
        }
        assertPosition("intrinsic", x = 40, y = 0)
    }

    @Test
    fun scrollColumn_supportsIntrinsicWidth() {
        composeRule.setContent {
            content(
                block = flex(
                    width = null,
                    height = 100,
                    direction = FlexDirection.COLUMN,
                    overflow = Overflow.SCROLL,
                    children = listOf(text("intrinsic", 20, 20)),
                ),
                flexModifier = Modifier.width(IntrinsicSize.Max),
            )
        }
        assertPosition("intrinsic", x = 0, y = 40)
    }

    @Test
    fun scrollRow_extremeGapDoesNotOverflowTheMeasuredSize() {
        render(flex(
            width = 100,
            height = 40,
            overflow = Overflow.SCROLL,
            gap = Int.MAX_VALUE,
            justifyContent = JustifyContent.START,
            alignItems = AlignItems.START,
            children = listOf(text("first", 20, 20), text("last", 20, 20)),
        ))
        assertPosition("first", x = 0, y = 0)
    }

    @Test
    fun oversizedFrame_isConstrainedToItsParent() {
        val size = renderAndMeasureFlex(flex(
            width = 1_000_000,
            height = 1_000_000,
            children = listOf(text("small", 20, 20)),
        ))
        assertEquals(400f, size.width, 0.5f)
        assertEquals(400f, size.height, 0.5f)
    }

    @Test
    fun nestedScrollRows_withHugWidthRemainMeasurable() {
        render(flex(
            width = 100,
            height = 40,
            overflow = Overflow.SCROLL,
            justifyContent = JustifyContent.START,
            alignItems = AlignItems.START,
            children = listOf(UIBlock.UnionUIFlexContainerBlock(flex(
                width = null,
                height = 20,
                overflow = Overflow.SCROLL,
                justifyContent = JustifyContent.START,
                children = listOf(text("nested", 20, 20)),
            ))),
        ))
        assertPosition("nested", x = 0, y = 0)
    }

    @Test
    fun scrollColumn_extremeGapDoesNotOverflowTheMeasuredSize() {
        render(flex(
            width = 40,
            height = 100,
            direction = FlexDirection.COLUMN,
            overflow = Overflow.SCROLL,
            gap = Int.MAX_VALUE,
            justifyContent = JustifyContent.START,
            alignItems = AlignItems.START,
            children = listOf(text("first", 20, 20), text("last", 20, 20)),
        ))
        assertPosition("first", x = 0, y = 0)
    }

    @Test
    fun nestedScrollColumns_withHugHeightRemainMeasurable() {
        render(flex(
            width = 40,
            height = 100,
            direction = FlexDirection.COLUMN,
            overflow = Overflow.SCROLL,
            justifyContent = JustifyContent.START,
            alignItems = AlignItems.START,
            children = listOf(UIBlock.UnionUIFlexContainerBlock(flex(
                width = 20,
                height = null,
                direction = FlexDirection.COLUMN,
                overflow = Overflow.SCROLL,
                justifyContent = JustifyContent.START,
                children = listOf(text("nested", 20, 20)),
            ))),
        ))
        assertPosition("nested", x = 0, y = 0)
    }

    @Test
    fun emptyScrollFlex_withExtremeGapKeepsItsFrameSize() {
        val size = renderAndMeasureFlex(flex(
            width = 100,
            height = 40,
            overflow = Overflow.SCROLL,
            gap = Int.MAX_VALUE,
            children = emptyList(),
        ))
        assertEquals(100f, size.width, 0.5f)
        assertEquals(40f, size.height, 0.5f)
    }

    @Test
    fun scrollRow_nestedGridWithoutWidthCanScroll() {
        assertNestedCollectionCanScroll(CollectionKind.GRID, FlexDirection.ROW)
    }

    @Test
    fun scrollColumn_nestedGridWithoutHeightCanScroll() {
        assertNestedCollectionCanScroll(CollectionKind.GRID, FlexDirection.COLUMN)
    }

    @Test
    fun scrollRow_nestedCarouselWithoutWidthCanScroll() {
        assertNestedCollectionCanScroll(CollectionKind.CAROUSEL, FlexDirection.ROW)
    }

    @Test
    fun scrollColumn_nestedCarouselWithoutHeightCanScroll() {
        assertNestedCollectionCanScroll(CollectionKind.CAROUSEL, FlexDirection.COLUMN)
    }

    @Test
    fun scrollRow_nestedGridPreservesAnExplicitWidthLargerThanTheViewport() {
        assertNestedCollectionCanScroll(
            CollectionKind.GRID,
            FlexDirection.ROW,
            frame = FrameData(width = 120),
            expectedViewport = 120,
        )
    }

    @Test
    fun scrollColumn_nestedCarouselPreservesAnExplicitHeightLargerThanTheViewport() {
        assertNestedCollectionCanScroll(
            CollectionKind.CAROUSEL,
            FlexDirection.COLUMN,
            frame = FrameData(height = 120),
            expectedViewport = 120,
        )
    }

    @Test
    fun scrollRow_nestedCarouselPreservesItsFillAllocation() {
        assertNestedCollectionCanScroll(
            CollectionKind.CAROUSEL,
            FlexDirection.ROW,
            frame = FrameData(width = 0),
        )
    }

    private fun assertNestedCollectionCanScroll(
        kind: CollectionKind,
        direction: FlexDirection,
        frame: FrameData? = null,
        expectedViewport: Int = 100,
    ) {
        render(flex(
            width = if (direction == FlexDirection.ROW) 100 else 40,
            height = if (direction == FlexDirection.COLUMN) 100 else 40,
            direction = direction,
            overflow = Overflow.SCROLL,
            justifyContent = JustifyContent.START,
            alignItems = AlignItems.START,
            children = listOf(UIBlock.UnionUICollectionBlock(UICollectionBlock(
                data = UICollectionBlockData(
                    kind = kind,
                    direction = direction,
                    frame = frame,
                    itemWidth = 20,
                    itemHeight = 20,
                    gridSize = 1,
                    children = List(10) { text("item-$it", 20, 20) },
                ),
            ))),
        ))
        val collection = composeRule.onNode(hasScrollToIndexAction())
        val size = collection.fetchSemanticsNode().size
        val mainAxisSize = if (direction == FlexDirection.ROW) size.width else size.height
        assertEquals(with(composeRule.density) { expectedViewport.dp.roundToPx() }, mainAxisSize)
        composeRule.onNodeWithText("item-0").assertIsDisplayed()
        // For an explicitly oversized collection, its trailing edge is clipped
        // by the outer viewport. Scroll to an item that can be visible in both.
        val targetIndex = if (expectedViewport > 100) 7 else 9
        collection.performScrollToIndex(targetIndex)
        composeRule.onNodeWithText("item-$targetIndex").assertIsDisplayed()
    }

    private fun render(block: UIFlexContainerBlock) {
        composeRule.setContent {
            content(block)
        }
    }

    private fun renderAndMeasureFlex(block: UIFlexContainerBlock): MeasuredSize {
        var size = IntSize.Zero
        var density = 1f
        composeRule.setContent {
            density = LocalDensity.current.density
            content(
                block = block,
                flexModifier = Modifier.onGloballyPositioned { size = it.size },
            )
        }
        composeRule.waitForIdle()
        return MeasuredSize(
            width = size.width / density,
            height = size.height / density,
        )
    }

    @Composable
    private fun content(
        block: UIFlexContainerBlock,
        background: ComposeColor = ComposeColor.Transparent,
        flexModifier: Modifier = Modifier,
    ) {
        ContainerProvider(container) {
            EventListenerProvider(listener = { _, _ -> }) {
                DataProvider(DataState(loading = false, data = JsonObject(emptyMap()))) {
                    Box(
                        Modifier
                            .size(400.dp)
                            .background(background)
                            .testTag("flex-test-root")
                    ) {
                        Flex(block = block, modifier = flexModifier, insetTop = 0.dp)
                    }
                }
            }
        }
    }

    private fun flex(
        width: Int?,
        height: Int?,
        children: List<UIBlock>,
        direction: FlexDirection? = FlexDirection.ROW,
        justifyContent: JustifyContent? = null,
        alignItems: AlignItems? = null,
        gap: Int = 0,
        overflow: Overflow? = null,
    ) = UIFlexContainerBlock(
        data = UIFlexContainerBlockData(
            children = children,
            direction = direction,
            justifyContent = justifyContent,
            alignItems = alignItems,
            gap = gap,
            overflow = overflow,
            frame = FrameData(width = width, height = height),
        )
    )

    private fun text(
        value: String,
        width: Int,
        height: Int,
        background: ColorValue? = null,
    ) = UIBlock.UnionUITextBlock(
        UITextBlock(
            data = UITextBlockData(
                value = value,
                frame = FrameData(width = width, height = height, background = background),
            )
        )
    )

    private fun ComposeColor.toSchemaColor() = ColorValue.UnionColor(
        SchemaColor(red = red, green = green, blue = blue, alpha = alpha)
    )

    private fun assertPosition(value: String, x: Int, y: Int) {
        val bounds = bounds(value)
        assertEquals(x.toFloat(), bounds.left.value, 0.5f)
        assertEquals(y.toFloat(), bounds.top.value, 0.5f)
    }

    private fun bounds(value: String): DpRect = composeRule.onNodeWithText(value).getBoundsInRoot()

    private fun pixelAt(x: Int, y: Int): ComposeColor {
        val image = composeRule
            .onNodeWithTag("flex-test-root")
            .captureToImage()
            .toPixelMap()
        return image[
            (x / 400f * image.width).roundToInt(),
            (y / 400f * image.height).roundToInt(),
        ]
    }

    private fun assertEquals(expected: DpRect, actual: DpRect) {
        assertEquals(expected.left.value, actual.left.value, 0.5f)
        assertEquals(expected.top.value, actual.top.value, 0.5f)
        assertEquals(expected.right.value, actual.right.value, 0.5f)
        assertEquals(expected.bottom.value, actual.bottom.value, 0.5f)
    }

    private data class MeasuredSize(
        val width: Float,
        val height: Float,
    )
}
