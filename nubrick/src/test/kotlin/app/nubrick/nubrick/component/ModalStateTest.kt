package app.nubrick.nubrick.component

import app.nubrick.nubrick.component.provider.pageblock.PageBlockData
import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIPageBlockData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModalStateTest {
    @Test
    fun currentPageBlockReturnsNullWhenDisplayedModalIndexIsInvalid() {
        assertNull(ModalState().currentPageBlock)
    }

    @Test
    fun currentPageBlockReturnsStackEntryAtDisplayedIndex() {
        val page = PageBlockData(
            block = UIPageBlock(id = "m1", data = UIPageBlockData(kind = PageKind.MODAL)),
        )
        val state = ModalState(
            modalStack = listOf(page),
            displayedModalIndex = 0,
            modalVisibility = true,
        )
        assertEquals("m1", state.currentPageBlock?.block?.id)
    }
}
