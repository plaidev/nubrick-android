package app.nubrick.nubrick.component

import org.junit.Assert.assertNull
import org.junit.Test

class ModalStateTest {
    @Test
    fun currentPageBlockReturnsNullWhenDisplayedModalIndexIsInvalid() {
        assertNull(ModalState().currentPageBlock)
    }
}
