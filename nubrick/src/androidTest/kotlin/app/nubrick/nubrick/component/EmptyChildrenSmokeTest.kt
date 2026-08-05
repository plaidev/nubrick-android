package app.nubrick.nubrick.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.component.renderer.Block
import app.nubrick.nubrick.schema.CollectionKind
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UICollectionBlock
import app.nubrick.nubrick.schema.UICollectionBlockData
import app.nubrick.nubrick.schema.UIFlexContainerBlock
import app.nubrick.nubrick.schema.UIFlexContainerBlockData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmptyChildrenSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun flexWithNullChildrenDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUIFlexContainerBlock(
                        UIFlexContainerBlock(
                            id = "flex",
                            data = UIFlexContainerBlockData(children = null),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun flexWithEmptyChildrenDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUIFlexContainerBlock(
                        UIFlexContainerBlock(
                            id = "flex",
                            data = UIFlexContainerBlockData(children = emptyList()),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun gridWithEmptyChildrenDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUICollectionBlock(
                        UICollectionBlock(
                            id = "grid",
                            data = UICollectionBlockData(
                                kind = CollectionKind.GRID,
                                children = emptyList(),
                                gridSize = 2,
                                itemWidth = 100,
                                itemHeight = 100,
                            ),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun carouselWithEmptyChildrenDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUICollectionBlock(
                        UICollectionBlock(
                            id = "carousel",
                            data = UICollectionBlockData(
                                kind = CollectionKind.CAROUSEL,
                                children = emptyList(),
                                itemWidth = 100,
                                itemHeight = 100,
                            ),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun carouselEmptyAutoScrollDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUICollectionBlock(
                        UICollectionBlock(
                            id = "carousel",
                            data = UICollectionBlockData(
                                kind = CollectionKind.CAROUSEL,
                                children = emptyList(),
                                itemWidth = 100,
                                itemHeight = 100,
                                fullItemWidth = true,
                                autoScroll = true,
                                autoScrollInterval = 0.05f,
                            ),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
        // Allow autoScroll LaunchedEffect (delay ~0) to run; must not ArithmeticException on % 0.
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()
    }
}
