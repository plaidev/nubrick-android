package app.nubrick.nubrick.component.renderer

import androidx.compose.ui.unit.Constraints
import app.nubrick.nubrick.schema.FlexDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexLayoutSafetyTest {
    @Test
    fun layoutTotal_staysWithinComposeMeasuredSizeLimits() {
        assertEquals(0, layoutTotal(-1))
        assertEquals(42, layoutTotal(42))
        assertEquals(MaxFlexLayoutSize, layoutTotal(Long.MAX_VALUE))
    }

    @Test
    fun frameConstraints_constrainOversizedDimensionsBeforePacking() {
        val parent = Constraints(maxWidth = 400, maxHeight = 300)
        assertEquals(
            Constraints.fixed(400, 300),
            frameConstraints(parent, width = Int.MAX_VALUE, height = Int.MAX_VALUE),
        )
        val unbounded = frameConstraints(Constraints(), width = Int.MAX_VALUE, height = Int.MAX_VALUE)
        assertTrue(unbounded.hasFixedWidth)
        assertTrue(unbounded.hasFixedHeight)
        assertTrue(unbounded.maxWidth in 1..262142)
        assertTrue(unbounded.maxHeight in 1..262142)
    }

    @Test
    fun frameConstraints_preserveHugFillAndParentMinimums() {
        val parent = Constraints(minWidth = 20, maxWidth = 400, minHeight = 30, maxHeight = 300)
        assertEquals(parent, frameConstraints(parent, width = null, height = -1))
        assertEquals(Constraints.fixed(400, 300), frameConstraints(parent, width = 0, height = 0))
        assertEquals(Constraints.fixed(20, 30), frameConstraints(parent, width = 1, height = 1))
        assertEquals(Constraints(), frameConstraints(Constraints(), width = 0, height = 0))
    }

    @Test
    fun scrollViewportConstraints_boundOnlyAnUnboundedScrollAxis() {
        val bounded = Constraints(maxWidth = 400, maxHeight = 300)
        assertEquals(bounded, scrollViewportConstraints(bounded, FlexDirection.ROW))
        assertEquals(bounded, scrollViewportConstraints(bounded, FlexDirection.COLUMN))

        val row = scrollViewportConstraints(Constraints(maxHeight = 100_000), FlexDirection.ROW)
        assertTrue(row.hasBoundedWidth)
        assertEquals(100_000, row.maxHeight)
        val column = scrollViewportConstraints(Constraints(maxWidth = 100_000), FlexDirection.COLUMN)
        assertTrue(column.hasBoundedHeight)
        assertEquals(100_000, column.maxWidth)
    }
    @Test
    fun scrollViewportConstraints_useTheSuppliedViewportWithoutChangingAllocatedSizes() {
        assertEquals(
            Constraints(maxWidth = 100, maxHeight = 40),
            scrollViewportConstraints(Constraints(maxHeight = 40), FlexDirection.ROW, fallbackMax = 100),
        )
        assertEquals(
            Constraints(maxWidth = 40, maxHeight = 100),
            scrollViewportConstraints(Constraints(maxWidth = 40), FlexDirection.COLUMN, fallbackMax = 100),
        )
        val allocated = Constraints.fixed(50, 20)
        assertEquals(allocated, scrollViewportConstraints(allocated, FlexDirection.ROW, fallbackMax = 100))
    }

}
