package app.nubrick.nubrick.component.renderer

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

class FlexLayoutSafetyTest {
    @Test
    fun layoutTotal_saturatesArithmeticWithoutOverflowing() {
        assertEquals(0, layoutTotal(-1))
        assertEquals(42, layoutTotal(42))
        assertEquals(Constraints.Infinity, layoutTotal(Long.MAX_VALUE))
    }
}
