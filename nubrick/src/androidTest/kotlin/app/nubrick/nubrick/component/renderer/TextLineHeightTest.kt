package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import app.nubrick.nubrick.component.RendererSmokeHarness
import app.nubrick.nubrick.schema.UITextBlock
import app.nubrick.nubrick.schema.UITextBlockData

class TextLineHeightTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explicitLineHeightKeepsDifferentGlyphsOnTheSameBaseline() {
        var mixedBaseline = 0f
        var latinBaseline = 0f

        composeRule.setContent {
            val style = testTextStyle()
            val baselineFromCenter = rememberBaselineFromCenter(style)
            val lineHeight = resolveTextLineHeightPx(style, LocalDensity.current)

            Box {
                BasicText(
                    "and ST CARD 新規ご入会&利用でもれなく",
                    modifier = Modifier
                        .testTag("mixed")
                        .onGloballyPositioned {
                            mixedBaseline = it.positionInRoot().y + it[FirstBaseline]
                        }
                        .normalizeTextLines(baselineFromCenter, lineHeight) { 1 },
                    style = style,
                )
                BasicText(
                    "3,000 pt",
                    modifier = Modifier
                        .testTag("latin")
                        .onGloballyPositioned {
                            latinBaseline = it.positionInRoot().y + it[FirstBaseline]
                        }
                        .normalizeTextLines(baselineFromCenter, lineHeight) { 1 },
                    style = style,
                )
            }
        }

        val mixedBounds = composeRule.onNodeWithTag("mixed").fetchSemanticsNode().boundsInRoot
        val latinBounds = composeRule.onNodeWithTag("latin").fetchSemanticsNode().boundsInRoot
        assertEquals(mixedBounds.height, latinBounds.height, 0.5f)
        assertEquals(mixedBaseline, latinBaseline, 0.5f)
    }

    @Test
    fun explicitLineHeightKeepsMultilineBaselinesIndependentOfGlyphs() {
        var mixedFirstBaseline = 0f
        var mixedLastBaseline = 0f
        var latinFirstBaseline = 0f
        var latinLastBaseline = 0f

        composeRule.setContent {
            val style = testTextStyle()
            val baselineFromCenter = rememberBaselineFromCenter(style)
            val lineHeight = with(LocalDensity.current) { style.lineHeight.toPx() }

            Box {
                BasicText(
                    "Latin\n日本語",
                    modifier = Modifier
                        .normalizeTextLines(baselineFromCenter, lineHeight) { 2 }
                        .testTag("mixed-multiline")
                        .onGloballyPositioned {
                            val top = it.positionInRoot().y
                            mixedFirstBaseline = top + it[FirstBaseline]
                            mixedLastBaseline = top + it[LastBaseline]
                        },
                    style = style,
                )
                BasicText(
                    "ABC\n123",
                    modifier = Modifier
                        .normalizeTextLines(baselineFromCenter, lineHeight) { 2 }
                        .testTag("latin-multiline")
                        .onGloballyPositioned {
                            val top = it.positionInRoot().y
                            latinFirstBaseline = top + it[FirstBaseline]
                            latinLastBaseline = top + it[LastBaseline]
                        },
                    style = style,
                )
            }
        }

        val mixedBounds = composeRule.onNodeWithTag("mixed-multiline").fetchSemanticsNode().boundsInRoot
        val latinBounds = composeRule.onNodeWithTag("latin-multiline").fetchSemanticsNode().boundsInRoot
        assertEquals(mixedBounds.height, latinBounds.height, 0.5f)
        assertEquals(mixedFirstBaseline, latinFirstBaseline, 0.5f)
        assertEquals(mixedLastBaseline, latinLastBaseline, 0.5f)
    }

    @Test
    fun lineHeightUsesTheCurrentFontScale() {
        var measuredHeight = 0f
        var expectedHeight = 0f

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                val style = testTextStyle()
                val baselineFromCenter = rememberBaselineFromCenter(style)
                val lineHeight = resolveTextLineHeightPx(style, LocalDensity.current)
                expectedHeight = normalizeTextLineHeight(lineHeight).toFloat()

                BasicText(
                    "Latin",
                    modifier = Modifier
                        .testTag("scaled")
                        .onGloballyPositioned { measuredHeight = it.size.height.toFloat() }
                        .normalizeTextLines(baselineFromCenter, lineHeight) { 1 },
                    style = style,
                )
            }
        }

        assertEquals(expectedHeight, measuredHeight, 0.5f)
    }

    @Test
    fun textContainerHeightUsesTheCurrentFontScale() {
        var measuredHeight = 0f
        var expectedHeight = 0f

        composeRule.setContent {
            RendererSmokeHarness {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                    val style = testTextStyle()
                    expectedHeight = normalizeTextLineHeight(
                        resolveTextLineHeightPx(style, LocalDensity.current),
                    ).toFloat()
                    Text(
                        block = UITextBlock(
                            id = "text",
                            data = UITextBlockData(value = "Latin", size = 13, lineHeight = 15.6f),
                        ),
                        modifier = Modifier
                            .testTag("scaled-container")
                            .onGloballyPositioned { measuredHeight = it.size.height.toFloat() },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("scaled-container").assertExists()
        assertEquals(expectedHeight, measuredHeight, 0.5f)
    }

    @Test
    fun textContainerHeightUsesTheLineCountProducedDuringTextMeasurement() {
        var measuredHeight = 0f
        var expectedLineHeight = 0f

        composeRule.setContent {
            RendererSmokeHarness {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                    val style = testTextStyle()
                    expectedLineHeight = normalizeTextLineHeight(
                        resolveTextLineHeightPx(style, LocalDensity.current),
                    ).toFloat()
                    Text(
                        block = UITextBlock(
                            id = "text",
                            data = UITextBlockData(
                                value = "Latin\nLatin\nLatin",
                                size = 13,
                                lineHeight = 15.6f,
                            ),
                        ),
                        modifier = Modifier
                            .testTag("multiline-container")
                            .onGloballyPositioned { measuredHeight = it.size.height.toFloat() },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("multiline-container").assertExists()
        assertEquals(expectedLineHeight * 3, measuredHeight, 0.5f)
    }

    private fun testTextStyle() = TextStyle(
        fontSize = 13.sp,
        lineHeight = 15.6.sp,
        fontFamily = FontFamily.Default,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

}
