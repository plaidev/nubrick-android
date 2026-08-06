package app.nubrick.nubrick.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.component.provider.pageblock.PageBlockData
import app.nubrick.nubrick.schema.ModalPresentationStyle
import app.nubrick.nubrick.schema.ModalScreenSize
import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.TriggerSetting
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIPageBlockData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class ModalStateHolderTest {
    private fun holder(
        onDismiss: () -> Unit = {},
        onTrigger: (UIBlockAction, kotlinx.serialization.json.JsonElement) -> Unit = { _, _ -> },
    ): ModalStateHolder {
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
            onDismiss = onDismiss,
            onTrigger = onTrigger,
        )
    }

    private fun page(id: String, withTrigger: Boolean = false): PageBlockData {
        return PageBlockData(
            block = UIPageBlock(
                id = id,
                data = UIPageBlockData(
                    kind = PageKind.MODAL,
                    triggerSetting = if (withTrigger) {
                        TriggerSetting(onTrigger = UIBlockAction(destinationPageId = "next"))
                    } else {
                        null
                    },
                ),
            ),
        )
    }

    @Test
    fun showOpensModalAndSetsCurrentPage() {
        val modal = holder()
        try {
            modal.show(
                block = page("m1"),
                modalPresentationStyle = ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_PAGE_SHEET,
                modalScreenSize = ModalScreenSize.MEDIUM,
            )
        } catch (e: Throwable) {
            fail("show threw: $e")
        }
        assertTrue(modal.modalState.modalVisibility)
        assertEquals(1, modal.modalState.modalStack.size)
        assertEquals(0, modal.modalState.displayedModalIndex)
        assertEquals("m1", modal.modalState.currentPageBlock?.block?.id)
        assertEquals(ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_PAGE_SHEET, modal.modalState.modalPresentationStyle)
        assertEquals(ModalScreenSize.MEDIUM, modal.modalState.modalScreenSize)
    }

    @Test
    fun showStacksAndKeepsFirstPresentationStyle() {
        val modal = holder()
        modal.show(page("m1"), ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_PAGE_SHEET, ModalScreenSize.MEDIUM)
        modal.show(page("m2"), ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_FULL_SCREEN, ModalScreenSize.LARGE)
        assertEquals(2, modal.modalState.modalStack.size)
        assertEquals(1, modal.modalState.displayedModalIndex)
        assertEquals("m2", modal.modalState.currentPageBlock?.block?.id)
        // Style is locked to the first visible modal.
        assertEquals(ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_PAGE_SHEET, modal.modalState.modalPresentationStyle)
        assertEquals(ModalScreenSize.MEDIUM, modal.modalState.modalScreenSize)
    }

    @Test
    fun backToJumpsWithinStackWithoutThrowing() {
        val modal = holder()
        modal.show(page("m1"), ModalPresentationStyle.UNKNOWN, ModalScreenSize.UNKNOWN)
        modal.show(page("m2"), ModalPresentationStyle.UNKNOWN, ModalScreenSize.UNKNOWN)
        try {
            modal.backTo(0)
        } catch (e: Throwable) {
            fail("backTo threw: $e")
        }
        assertEquals(0, modal.modalState.displayedModalIndex)
        assertEquals("m1", modal.modalState.currentPageBlock?.block?.id)
        modal.backTo(-1)
        modal.backTo(99)
        assertEquals(0, modal.modalState.displayedModalIndex)
    }

    @Test
    fun backDecrementsIndexThenClosesAtRoot() {
        var dismissed = 0
        val modal = holder(onDismiss = { dismissed++ })
        modal.show(page("m1"), ModalPresentationStyle.UNKNOWN, ModalScreenSize.UNKNOWN)
        modal.show(page("m2"), ModalPresentationStyle.UNKNOWN, ModalScreenSize.UNKNOWN)

        modal.back(JsonNull)
        assertEquals(0, modal.modalState.displayedModalIndex)
        assertTrue(modal.modalState.modalVisibility)
        assertEquals(0, dismissed)

        modal.back(JsonNull)
        assertFalse(modal.modalState.modalVisibility)
        assertEquals(0, modal.modalState.modalStack.size)
        assertEquals(1, dismissed)
    }

    @Test
    fun backWithOnTriggerDoesNotClose() {
        var triggered = 0
        val modal = holder(onTrigger = { _, _ -> triggered++ })
        modal.show(page("m1", withTrigger = true), ModalPresentationStyle.UNKNOWN, ModalScreenSize.UNKNOWN)
        modal.back(JsonNull)
        assertEquals(1, triggered)
        assertTrue(modal.modalState.modalVisibility)
        assertEquals("m1", modal.modalState.currentPageBlock?.block?.id)
    }

    @Test
    fun closeResetsStateAndOptionallyEmitsDismiss() {
        var dismissed = 0
        val modal = holder(onDismiss = { dismissed++ })
        modal.show(page("m1"), ModalPresentationStyle.UNKNOWN, ModalScreenSize.UNKNOWN)

        modal.close(forceReset = true, emitDispatch = false)
        assertFalse(modal.modalState.modalVisibility)
        assertNull(modal.modalState.currentPageBlock)
        assertEquals(0, dismissed)

        modal.show(page("m2"), ModalPresentationStyle.UNKNOWN, ModalScreenSize.UNKNOWN)
        modal.close(forceReset = true, emitDispatch = true)
        assertFalse(modal.modalState.modalVisibility)
        assertEquals(1, dismissed)
    }
}
