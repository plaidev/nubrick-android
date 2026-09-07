package app.nubrick.nubrick.component.renderer

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.nubrick.nubrick.schema.FlexDirection
import app.nubrick.nubrick.schema.FrameData

class FlexLayoutSafetyTest {
    @Test
    fun layoutTotal_saturatesArithmeticWithoutOverflowing() {
        assertEquals(0, layoutTotal(-1))
        assertEquals(42, layoutTotal(42))
        assertEquals(Constraints.Infinity, layoutTotal(Long.MAX_VALUE))
    }

    @Test
    fun scrollContent_usesViewportMinimumOnlyForExplicitMainAxisSizes() {
        assertTrue(scrollContentUsesViewportMinimum(FrameData(width = 0), FlexDirection.ROW))
        assertTrue(scrollContentUsesViewportMinimum(FrameData(width = 100), FlexDirection.ROW))
        assertFalse(scrollContentUsesViewportMinimum(FrameData(height = 100), FlexDirection.ROW))

        assertTrue(scrollContentUsesViewportMinimum(FrameData(height = 0), FlexDirection.COLUMN))
        assertTrue(scrollContentUsesViewportMinimum(FrameData(height = 100), FlexDirection.COLUMN))
        assertFalse(scrollContentUsesViewportMinimum(FrameData(width = 100), FlexDirection.COLUMN))
    }
}
