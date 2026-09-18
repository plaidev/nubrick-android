package app.nubrick.nubrick.component.renderer

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nubrick.nubrick.schema.FlexDirection

// Keep fallback viewports comfortably within Compose's packed Constraints range. Remote sizes
// larger than this are not useful as an on-screen viewport and can overflow after dp conversion.
private const val MAX_FALLBACK_VIEWPORT_DP = 10_000
private const val MAX_FALLBACK_VIEWPORT_PX = 32_767

/**
 * Scrollable Compose layouts reject an infinite constraint on their scrolling axis. That is easy
 * to produce with remote layouts (for example, a vertical pager inside a vertically scrolling
 * flex). Preserve normal bounded sizing, but provide a finite viewport when an ancestor measures
 * this layout with an unbounded scrolling axis.
 */
internal fun Modifier.boundScrollableAxis(
    direction: FlexDirection,
    fallbackViewport: Dp,
): Modifier = layout { measurable, constraints ->
    val fallbackPx = fallbackViewport
        .coerceIn(0.dp, MAX_FALLBACK_VIEWPORT_DP.dp)
        .roundToPx()
        .coerceIn(0, MAX_FALLBACK_VIEWPORT_PX)
    val boundedConstraints = when (direction) {
        FlexDirection.ROW -> if (constraints.hasBoundedWidth) {
            constraints
        } else {
            constraints.copy(maxWidth = maxOf(constraints.minWidth, fallbackPx))
        }

        else -> if (constraints.hasBoundedHeight) {
            constraints
        } else {
            constraints.copy(maxHeight = maxOf(constraints.minHeight, fallbackPx))
        }
    }
    val placeable = measurable.measure(boundedConstraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, 0)
    }
}
