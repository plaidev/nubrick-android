package app.nubrick.nubrick.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.component.renderer.Page
import app.nubrick.nubrick.schema.UIPageBlock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageAlignmentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fixedWidthEmbeddedRootIsCenteredWithoutChangingChildAlignment() {
        render(width = 120)
        assertChildPosition(left = 60f, top = 110f)
    }

    @Test
    fun hugWidthEmbeddedRootIsCentered() {
        render(width = -1)
        assertChildPosition(left = 100f, top = 110f)
    }

    @Test
    fun fillWidthEmbeddedRootKeepsItsAuthoredChildAlignment() {
        render(width = 0)
        assertChildPosition(left = 0f, top = 110f)
    }

    @Test
    fun fillSizeEmbeddedRootKeepsItsAuthoredChildAlignment() {
        render(width = 0, height = 0)
        assertChildPosition(left = 0f, top = 0f)
    }

    @Test
    fun fixedWidthModalRootIsCenteredWithoutChangingChildAlignment() {
        render(width = 120, isModal = true)
        assertChildPosition(left = 60f, top = 110f)
    }

    @Test
    fun hugWidthModalRootIsCentered() {
        render(width = -1, isModal = true)
        assertChildPosition(left = 100f, top = 110f)
    }

    @Test
    fun fillWidthModalRootKeepsItsAuthoredChildAlignment() {
        render(width = 0, isModal = true)
        assertChildPosition(left = 0f, top = 110f)
    }

    @Test
    fun fillSizeModalRootKeepsItsAuthoredChildAlignment() {
        render(width = 0, height = 0, isModal = true)
        assertChildPosition(left = 0f, top = 0f)
    }

    private fun render(width: Int, height: Int = 80, isModal: Boolean = false) {
        val page = requireNotNull(UIPageBlock.decode(Json.parseToJsonElement("""
            { "data": {
              "kind": "${if (isModal) "MODAL" else "COMPONENT"}",
              "renderAs": { "__typename": "UIFlexContainerBlock", "data": {
                "direction": "COLUMN", "alignItems": "START", "justifyContent": "START",
                "frame": { "width": $width, "height": $height },
                "children": [ { "__typename": "UITextBlock", "data": {
                  "value": "Child", "frame": { "width": 40, "height": 20 }
                } } ]
              } }
            } }
        """.trimIndent())))
        composeRule.setContent {
            RendererSmokeHarness {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(240.dp, 300.dp).testTag("viewport")) {
                        Page(page, modifier = Modifier.testTag("page"), isModal = isModal)
                    }
                }
            }
        }
    }

    private fun assertChildPosition(left: Float, top: Float) {
        val viewport = composeRule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val page = composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        val child = composeRule.onNodeWithText("Child").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport, page)
        assertEquals(left, child.left - viewport.left, 1f)
        assertEquals(top, child.top - viewport.top, 1f)
    }
}
