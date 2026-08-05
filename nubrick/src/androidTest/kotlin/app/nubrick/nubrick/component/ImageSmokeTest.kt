package app.nubrick.nubrick.component

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.component.renderer.Block
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIImageBlock
import app.nubrick.nubrick.schema.UIImageBlockData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val validBlurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"

    @Test
    fun imageWithEmptySrcDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUIImageBlock(
                        UIImageBlock(
                            id = "image",
                            data = UIImageBlockData(src = ""),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun imageWithNullDataDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUIImageBlock(
                        UIImageBlock(id = "image", data = null)
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun imageWithBadUrlDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUIImageBlock(
                        UIImageBlock(
                            id = "image",
                            data = UIImageBlockData(
                                src = "https://invalid.nubrick.test/does-not-exist.png",
                            ),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
    }

    @Test
    fun imageWithBadBlurHashQueryDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUIImageBlock(
                        UIImageBlock(
                            id = "image",
                            data = UIImageBlockData(
                                src = "https://example.com/img.jpg?w=0&h=0&b=$validBlurHash",
                            ),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun imageWithNegativeBlurHashSizeDoesNotCrash() {
        composeRule.setContent {
            RendererSmokeHarness {
                Block(
                    block = UIBlock.UnionUIImageBlock(
                        UIImageBlock(
                            id = "image",
                            data = UIImageBlockData(
                                src = "https://example.com/img.jpg?w=-1&h=10&b=$validBlurHash",
                            ),
                        )
                    )
                )
            }
        }
        composeRule.waitForIdle()
    }
}
