package app.nubrick.nubrick.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.component.renderer.Block
import app.nubrick.nubrick.schema.CollectionKind
import app.nubrick.nubrick.schema.FlexDirection
import app.nubrick.nubrick.schema.Overflow
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UICollectionBlock
import app.nubrick.nubrick.schema.UICollectionBlockData
import app.nubrick.nubrick.schema.UIFlexContainerBlock
import app.nubrick.nubrick.schema.UIFlexContainerBlockData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollableConstraintsSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun verticalCarouselInsideVerticalScrollDoesNotCrash() {
        renderScrollableCollection(CollectionKind.CAROUSEL, FlexDirection.COLUMN)
    }

    @Test
    fun horizontalCarouselInsideHorizontalScrollDoesNotCrash() {
        renderScrollableCollection(CollectionKind.CAROUSEL, FlexDirection.ROW)
    }

    @Test
    fun verticalGridInsideVerticalScrollDoesNotCrash() {
        renderScrollableCollection(CollectionKind.GRID, FlexDirection.COLUMN)
    }

    @Test
    fun horizontalGridInsideHorizontalScrollDoesNotCrash() {
        renderScrollableCollection(CollectionKind.GRID, FlexDirection.ROW)
    }

    @Test
    fun nestedVerticalScrollFlexDoesNotCrash() {
        renderNestedScrollFlex(FlexDirection.COLUMN)
    }

    @Test
    fun nestedHorizontalScrollFlexDoesNotCrash() {
        renderNestedScrollFlex(FlexDirection.ROW)
    }

    private fun renderScrollableCollection(kind: CollectionKind, direction: FlexDirection) {
        val collection = UIBlock.UnionUICollectionBlock(
            UICollectionBlock(
                id = "collection",
                data = UICollectionBlockData(
                    children = listOf(emptyFlex("item")),
                    kind = kind,
                    direction = direction,
                    gridSize = 1,
                    itemWidth = 100,
                    itemHeight = 100,
                ),
            )
        )
        composeRule.setContent {
            RendererSmokeHarness {
                if (direction == FlexDirection.ROW) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Block(collection, Modifier.testTag(COLLECTION_TAG))
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Block(collection, Modifier.testTag(COLLECTION_TAG))
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bounds = composeRule.onNodeWithTag(COLLECTION_TAG).fetchSemanticsNode().boundsInRoot
        if (direction == FlexDirection.ROW) {
            assertEquals(0f, bounds.width, 0f)
        } else {
            assertEquals(0f, bounds.height, 0f)
        }
    }

    private fun renderNestedScrollFlex(direction: FlexDirection) {
        val inner = scrollFlex("inner", direction, listOf(emptyFlex("item")))
        render(scrollFlex("outer", direction, listOf(inner)))
    }

    private fun render(block: UIBlock) {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(block)
            }
        }
        composeRule.waitForIdle()
    }
}

private const val COLLECTION_TAG = "unbounded-collection"

private fun scrollFlex(id: String, direction: FlexDirection, children: List<UIBlock>) =
    UIBlock.UnionUIFlexContainerBlock(
        UIFlexContainerBlock(
            id = id,
            data = UIFlexContainerBlockData(
                children = children,
                direction = direction,
                overflow = Overflow.SCROLL,
            ),
        )
    )

private fun emptyFlex(id: String) = UIBlock.UnionUIFlexContainerBlock(
    UIFlexContainerBlock(
        id = id,
        data = UIFlexContainerBlockData(children = emptyList()),
    )
)
