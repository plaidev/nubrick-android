package app.nubrick.nubrick.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.schema.ModalPresentationStyle
import app.nubrick.nubrick.schema.ModalScreenSize
import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.TriggerSetting
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIPageBlockData
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UIRootBlockData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class RootStateHolderTest {
    private fun modalHolder(): ModalStateHolder {
        val sheet = SheetState(
            skipPartiallyExpanded = false,
            positionalThreshold = { 56f },
            velocityThreshold = { 125f },
            initialValue = SheetValue.Hidden,
        )
        val largeSheet = SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { 56f },
            velocityThreshold = { 125f },
            initialValue = SheetValue.Hidden,
        )
        return ModalStateHolder(
            sheetState = sheet,
            largeSheetState = largeSheet,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onDismiss = {},
        )
    }

    @Test
    fun initializeWithEmptyPagesDoesNotThrow() {
        var dismissed = false
        val holder = RootStateHolder(
            root = UIRootBlock(id = "root", data = UIRootBlockData(pages = emptyList())),
            modalStateHolder = modalHolder(),
            onDismiss = { dismissed = true },
        )
        try {
            holder.initialize(JsonNull)
        } catch (e: Throwable) {
            fail("initialize threw: $e")
        }
        assertEquals(true, dismissed)
        assertNull(holder.displayedPageBlock.value)
    }

    @Test
    fun initializeContainsOnTriggerFailure() {
        val holder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(
                    pages = listOf(
                        UIPageBlock(
                            id = "trigger",
                            data = UIPageBlockData(
                                kind = PageKind.TRIGGER,
                                triggerSetting = TriggerSetting(
                                    onTrigger = UIBlockAction(destinationPageId = "page"),
                                ),
                            ),
                        ),
                        UIPageBlock(
                            id = "page",
                            data = UIPageBlockData(kind = PageKind.COMPONENT),
                        ),
                    ),
                ),
            ),
            modalStateHolder = modalHolder(),
            onTrigger = { _, _ -> throw RuntimeException("boom") },
        )
        try {
            holder.initialize(JsonNull)
        } catch (e: Throwable) {
            fail("initialize threw: $e")
        }
        assertNull(holder.displayedPageBlock.value)
    }

    @Test
    fun handleNavigateContainsDeepLinkFailure() {
        val holder = RootStateHolder(
            root = UIRootBlock(id = "root", data = UIRootBlockData(pages = emptyList())),
            modalStateHolder = modalHolder(),
            onOpenDeepLink = { throw RuntimeException("boom") },
        )
        try {
            holder.handleNavigate(
                UIBlockAction(deepLink = "https://example.com"),
                JsonNull,
            )
        } catch (e: Throwable) {
            fail("handleNavigate threw: $e")
        }
    }

    @Test
    fun initializeRendersDestinationPage() {
        val holder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(
                    pages = listOf(
                        UIPageBlock(
                            id = "trigger",
                            data = UIPageBlockData(
                                kind = PageKind.TRIGGER,
                                triggerSetting = TriggerSetting(
                                    onTrigger = UIBlockAction(destinationPageId = "page"),
                                ),
                            ),
                        ),
                        UIPageBlock(
                            id = "page",
                            data = UIPageBlockData(kind = PageKind.COMPONENT, frameWidth = 100, frameHeight = 50),
                        ),
                    ),
                ),
            ),
            modalStateHolder = modalHolder(),
        )
        holder.initialize(JsonNull)
        assertEquals("page", holder.currentPageBlock.value?.id)
        assertNotNull(holder.displayedPageBlock.value)
        assertEquals("page", holder.displayedPageBlock.value?.block?.id)
    }

    @Test
    fun initializeOpensModalDestinationWithoutThrowing() {
        val modal = modalHolder()
        val holder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(
                    pages = listOf(
                        UIPageBlock(
                            id = "trigger",
                            data = UIPageBlockData(
                                kind = PageKind.TRIGGER,
                                triggerSetting = TriggerSetting(
                                    onTrigger = UIBlockAction(destinationPageId = "modal"),
                                ),
                            ),
                        ),
                        UIPageBlock(
                            id = "modal",
                            data = UIPageBlockData(
                                kind = PageKind.MODAL,
                                modalPresentationStyle = ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_PAGE_SHEET,
                                modalScreenSize = ModalScreenSize.MEDIUM,
                            ),
                        ),
                    ),
                ),
            ),
            modalStateHolder = modal,
        )
        try {
            holder.initialize(JsonNull)
        } catch (e: Throwable) {
            fail("initialize threw: $e")
        }
        assertEquals("modal", holder.currentPageBlock.value?.id)
        assertNull(holder.displayedPageBlock.value)
        assertTrue(modal.modalState.modalVisibility)
        assertEquals("modal", modal.modalState.currentPageBlock?.block?.id)
    }
}
