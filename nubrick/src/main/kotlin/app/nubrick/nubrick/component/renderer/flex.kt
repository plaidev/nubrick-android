package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.component.provider.event.eventDispatcher
import app.nubrick.nubrick.schema.AlignItems
import app.nubrick.nubrick.schema.FlexDirection
import app.nubrick.nubrick.schema.FrameData
import app.nubrick.nubrick.schema.JustifyContent
import app.nubrick.nubrick.schema.Overflow
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIFlexContainerBlock
import app.nubrick.nubrick.template.compile
import app.nubrick.nubrick.schema.ColorValue
import androidx.core.math.MathUtils
import kotlin.math.roundToInt

internal fun layoutTotal(value: Long): Int =
    value.coerceIn(0L, Constraints.Infinity.toLong()).toInt()

private fun calcWeight(frameData: FrameData?, flexDirection: FlexDirection): Float? {
    if (flexDirection == FlexDirection.ROW) {
        if (frameData?.width != null && frameData.width == 0) {
            return 1f
        }
    } else {
        if (frameData?.height != null && frameData.height == 0) {
            return 1f
        }
    }
    return null
}

private fun childFrameWeight(block: UIBlock, direction: FlexDirection): Float? {
    return when (block) {
        is UIBlock.UnionUITextBlock -> calcWeight(block.data.data?.frame, direction)
        is UIBlock.UnionUIImageBlock -> calcWeight(block.data.data?.frame, direction)
        is UIBlock.UnionUIFlexContainerBlock -> calcWeight(block.data.data?.frame, direction)
        is UIBlock.UnionUICollectionBlock -> calcWeight(block.data.data?.frame, direction)
        is UIBlock.UnionUIMultiSelectInputBlock -> calcWeight(block.data.data?.frame, direction)
        is UIBlock.UnionUISelectInputBlock -> calcWeight(block.data.data?.frame, direction)
        is UIBlock.UnionUITextInputBlock -> calcWeight(block.data.data?.frame, direction)
        else -> null
    }
}

private data class FlexChildMetadata(
    val weight: Float?,
)

private fun Placeable.mainAxisSize(direction: FlexDirection): Int =
    if (direction == FlexDirection.ROW) width else height

private fun Placeable.crossAxisSize(direction: FlexDirection): Int =
    if (direction == FlexDirection.ROW) height else width

private fun flexChildConstraints(
    direction: FlexDirection,
    mainAxisMin: Int,
    mainAxisMax: Int,
    crossAxisMax: Int,
): Constraints = if (direction == FlexDirection.ROW) {
    Constraints(
        minWidth = mainAxisMin,
        maxWidth = mainAxisMax,
        minHeight = 0,
        maxHeight = crossAxisMax,
    )
} else {
    Constraints(
        minWidth = 0,
        maxWidth = crossAxisMax,
        minHeight = mainAxisMin,
        maxHeight = mainAxisMax,
    )
}

@Composable
private fun OverflowingFlex(
    children: List<UIBlock>,
    direction: FlexDirection,
    gap: Dp,
    justifyContent: JustifyContent?,
    alignItems: AlignItems?,
    modifier: Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            children.forEach { child ->
                Block(
                    block = child,
                    modifier = Modifier.layoutId(
                        FlexChildMetadata(childFrameWeight(child, direction))
                    ),
                )
            }
        },
    ) { measurables, constraints ->
        val mainAxisMax = if (direction == FlexDirection.ROW) {
            constraints.maxWidth
        } else {
            constraints.maxHeight
        }
        val mainAxisMin = if (direction == FlexDirection.ROW) {
            constraints.minWidth
        } else {
            constraints.minHeight
        }
        val crossAxisMax = if (direction == FlexDirection.ROW) {
            constraints.maxHeight
        } else {
            constraints.maxWidth
        }
        val gapPx = gap.toPx().roundToInt().coerceAtLeast(0)
        val weights = measurables.map { measurable ->
            (measurable.layoutId as? FlexChildMetadata)?.weight
        }
        val placeables = arrayOfNulls<Placeable>(measurables.size)

        // Fixed and hug children retain their own main-axis size, even after
        // the flex frame has run out of remaining room. They are still capped
        // by the parent's maximum, matching the editor's max-size rule.
        var fixedMainAxisSize = layoutTotal(
            gapPx.toLong() * (measurables.size - 1).coerceAtLeast(0)
        )
        measurables.forEachIndexed { index, measurable ->
            if (weights[index] == null) {
                val placeable = measurable.measure(
                    flexChildConstraints(
                        direction = direction,
                        mainAxisMin = 0,
                        mainAxisMax = mainAxisMax,
                        crossAxisMax = crossAxisMax,
                    )
                )
                placeables[index] = placeable
                fixedMainAxisSize = layoutTotal(
                    fixedMainAxisSize.toLong() + placeable.mainAxisSize(direction)
                )
            }
        }

        val weightedIndices = weights.indices.filter { weights[it] != null }
        val availableForFills = if (mainAxisMax == Constraints.Infinity) {
            // A scroll container measures its content with an unbounded max
            // constraint. Its minimum still represents the viewport, so fills
            // use the viewport's free space until fixed children overflow it.
            (mainAxisMin - fixedMainAxisSize).coerceAtLeast(0)
        } else {
            (mainAxisMax - fixedMainAxisSize).coerceAtLeast(0)
        }
        val totalWeight = weightedIndices.fold(0f) { total, index ->
            total + (weights[index] ?: 0f)
        }
        var remainingForFills = availableForFills
        weightedIndices.forEachIndexed { weightedIndex, childIndex ->
            val weight = weights[childIndex] ?: 0f
            val share = if (weightedIndex == weightedIndices.lastIndex) {
                remainingForFills
            } else {
                (availableForFills * weight / totalWeight).toInt()
            }
            remainingForFills -= share
            placeables[childIndex] = measurables[childIndex].measure(
                flexChildConstraints(
                    direction = direction,
                    mainAxisMin = share,
                    mainAxisMax = share,
                    crossAxisMax = crossAxisMax,
                )
            )
        }

        val resolvedPlaceables = placeables.map { requireNotNull(it) }
        val contentMainAxisSize = layoutTotal(
            resolvedPlaceables.sumOf { it.mainAxisSize(direction).toLong() }
                + gapPx.toLong() * (resolvedPlaceables.size - 1).coerceAtLeast(0)
        )
        val contentCrossAxisSize = resolvedPlaceables.maxOfOrNull {
            it.crossAxisSize(direction)
        } ?: 0
        val layoutMainAxisSize = if (direction == FlexDirection.ROW) {
            contentMainAxisSize.coerceIn(constraints.minWidth, constraints.maxWidth)
        } else {
            contentMainAxisSize.coerceIn(constraints.minHeight, constraints.maxHeight)
        }
        val layoutCrossAxisSize = if (direction == FlexDirection.ROW) {
            contentCrossAxisSize.coerceIn(constraints.minHeight, constraints.maxHeight)
        } else {
            contentCrossAxisSize.coerceIn(constraints.minWidth, constraints.maxWidth)
        }
        val remainingMainAxisSpace = layoutMainAxisSize - contentMainAxisSize
        val initialMainAxisPosition = when (justifyContent) {
            JustifyContent.START -> 0
            JustifyContent.END -> remainingMainAxisSpace
            JustifyContent.SPACE_BETWEEN -> 0
            else -> remainingMainAxisSpace / 2
        }
        val spacing = if (
            justifyContent == JustifyContent.SPACE_BETWEEN
                && resolvedPlaceables.size > 1
                && remainingMainAxisSpace > 0
        ) {
            gapPx + remainingMainAxisSpace / (resolvedPlaceables.size - 1)
        } else {
            gapPx
        }

        val layoutWidth = if (direction == FlexDirection.ROW) {
            layoutMainAxisSize
        } else {
            layoutCrossAxisSize
        }
        val layoutHeight = if (direction == FlexDirection.ROW) {
            layoutCrossAxisSize
        } else {
            layoutMainAxisSize
        }
        layout(layoutWidth, layoutHeight) {
            var mainAxisPosition = initialMainAxisPosition
            resolvedPlaceables.forEach { placeable ->
                val crossAxisPosition = when (alignItems) {
                    AlignItems.START -> 0
                    AlignItems.END -> layoutCrossAxisSize - placeable.crossAxisSize(direction)
                    else -> (layoutCrossAxisSize - placeable.crossAxisSize(direction)) / 2
                }
                if (direction == FlexDirection.ROW) {
                    placeable.place(mainAxisPosition, crossAxisPosition)
                } else {
                    placeable.place(crossAxisPosition, mainAxisPosition)
                }
                mainAxisPosition += placeable.mainAxisSize(direction) + spacing
            }
        }
    }
}

@Composable
private fun ScrollableFlex(
    children: List<UIBlock>,
    direction: FlexDirection,
    gap: Dp,
    justifyContent: JustifyContent?,
    alignItems: AlignItems?,
    modifier: Modifier,
) {
    // horizontalScroll/verticalScroll measure their content with an unbounded
    // main axis. Keep the viewport as a minimum size on that content so flex
    // fills can consume free viewport space before scrolling is necessary.
    BoxWithConstraints(
        modifier = modifier,
        propagateMinConstraints = true,
    ) {
        val contentModifier = if (direction == FlexDirection.ROW) {
            Modifier
                .horizontalScroll(rememberScrollState())
                .then(
                    if (maxWidth != Dp.Infinity) Modifier.widthIn(min = maxWidth) else Modifier
                )
        } else {
            Modifier
                .verticalScroll(rememberScrollState())
                .then(
                    if (maxHeight != Dp.Infinity) Modifier.heightIn(min = maxHeight) else Modifier
                )
        }
        OverflowingFlex(
            children = children,
            direction = direction,
            gap = gap,
            justifyContent = justifyContent,
            alignItems = alignItems,
            modifier = contentModifier,
        )
    }
}

@Composable
internal fun Modifier.styleByFrame(frame: FrameData?): Modifier {
    return this
        .frameSize(frame)
        .framePadding(frame)
}

@Composable
private fun toPx(dp: Dp): Float {
    return with(LocalDensity.current) {
        dp.toPx()
    }
}

private data class BorderRadius(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
)

private data class Size(
    val width: Float,
    val height: Float
)

private fun normalizeRadius(radius: BorderRadius, size: Size): BorderRadius {
    val (topLeft, topRight, bottomRight, bottomLeft) = radius
    val (width, height) = size
    var f = 1f

    for ((l, s) in listOf(
        width to topLeft + topRight,
        height to topLeft + bottomLeft,
        height to topRight + bottomRight,
        width to bottomLeft + bottomRight
    )) {
        if (s > 0 && s > l) {
            f = minOf(f, l / s)
        }
    }

    return BorderRadius(
        topLeft = topLeft * f,
        topRight = topRight * f,
        bottomRight = bottomRight * f,
        bottomLeft = bottomLeft * f
    )
}

@Composable
private fun createRoundedShape(frame: FrameData?): Shape {
    val isSingleRadius = frame?.borderTopLeftRadius == frame?.borderTopRightRadius &&
            frame?.borderBottomLeftRadius == frame?.borderBottomRightRadius &&
            frame?.borderTopLeftRadius == frame?.borderBottomLeftRadius

    val roundedShape = if (isSingleRadius) {
        // Prefer borderRadius; if omitted, use the shared per-corner value
        // (all four corners are equal when isSingleRadius is true).
        RoundedCornerShape(
            (frame?.borderRadius ?: frame?.borderTopLeftRadius)?.coerceAtLeast(0)?.dp ?: 0.dp
        )
    } else {
        val topLeftRadiusPx = toPx((frame?.borderTopLeftRadius?.coerceAtLeast(0))?.dp ?: 0.dp)
        val topRightRadiusPx = toPx((frame?.borderTopRightRadius?.coerceAtLeast(0))?.dp ?: 0.dp)
        val bottomRightRadiusPx = toPx((frame?.borderBottomRightRadius?.coerceAtLeast(0))?.dp ?: 0.dp)
        val bottomLeftRadiusPx = toPx((frame?.borderBottomLeftRadius?.coerceAtLeast(0))?.dp ?: 0.dp)

        GenericShape { size, _ ->
            val width = size.width
            val height = size.height

            val (topLeftRadius, topRightRadius, bottomRightRadius, bottomLeftRadius) = normalizeRadius(
                BorderRadius(
                    topLeft = topLeftRadiusPx,
                    topRight = topRightRadiusPx,
                    bottomRight = bottomRightRadiusPx,
                    bottomLeft = bottomLeftRadiusPx
                ),
                Size(
                    width = width,
                    height = height,
                )
            )

            val topLeft = Offset(0f, 0f)
            val topRight = Offset(width, 0f)
            val bottomRight = Offset(width, height)
            val bottomLeft = Offset(0f, height)

            moveTo(topLeft.x + topLeftRadius, topLeft.y)
            lineTo(topRight.x - topRightRadius, topRight.y)
            arcTo(
                rect = Rect(
                    topRight.x - 2 * topRightRadius,
                    topRight.y,
                    topRight.x,
                    topRight.y + 2 * topRightRadius
                ),
                startAngleDegrees = -90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            lineTo(bottomRight.x, bottomRight.y - bottomRightRadius)
            arcTo(
                rect = Rect(
                    bottomRight.x - 2 * bottomRightRadius,
                    bottomRight.y - 2 * bottomRightRadius,
                    bottomRight.x,
                    bottomRight.y
                ),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            lineTo(bottomLeft.x + bottomLeftRadius, bottomLeft.y)
            arcTo(
                rect = Rect(
                    bottomLeft.x,
                    bottomLeft.y - 2 * bottomLeftRadius,
                    bottomLeft.x + 2 * bottomLeftRadius,
                    bottomLeft.y
                ),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )

            lineTo(topLeft.x, topLeft.y + topLeftRadius)
            arcTo(
                rect = Rect(
                    topLeft.x,
                    topLeft.y,
                    topLeft.x + 2 * topLeftRadius,
                    topLeft.y + 2 * topLeftRadius
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false
            )
            close()
        }
    }

    return roundedShape
}

@Composable
internal fun Modifier.borderRadius(frame: FrameData?): Modifier {
    var mod = this
    val roundedShape = createRoundedShape(frame)
    mod = mod.clip(roundedShape)
    return mod
}

@Composable
internal fun Modifier.frameSize(
    frame: FrameData?,
    includeBorder: Boolean = true,
    clipsContent: Boolean = true,
): Modifier {
    var mod = this
    // size should be set most lastly to make padding insets.
    // width should be content fit by default
    frame?.width?.let { width ->
        mod = when {
            width == 0 -> {
            // parent fit
            mod.fillMaxWidth()
            }
            width > 0 -> {
            // fixed size
                mod.width(width.dp)
            }
            else -> mod
        }
    }

    // height should be content fit by default
    frame?.height?.let { height ->
        mod = when {
            height == 0 -> {
            // parent fit
            mod.fillMaxHeight()
            }
            height > 0 -> {
            // fixed size
                mod.height(height.dp)
            }
            else -> mod
        }
    }

    val roundedShape = createRoundedShape(frame)
    if (clipsContent) {
        mod = mod.clip(roundedShape)
    }

    if (frame?.background != null) {
        mod = mod.background(
            color = parseColor(frame.background),
            shape = roundedShape,
        )
    }

    if (!includeBorder || frame?.borderWidth == 0 || frame?.borderWidth == null) {
        return mod
    }

    mod = mod.border(
        width = frame.borderWidth.coerceAtLeast(0).dp,
        color = parseColor(frame.borderColor),
        shape = roundedShape,
    )

    return mod
}


@Composable
internal fun Modifier.framePadding(frame: FrameData?, insetTop: Dp = 0.dp): Modifier {
    val border = (frame?.borderWidth ?: 0).coerceAtLeast(0).dp

    return this.padding(
        start = (frame?.paddingLeft ?: 0).coerceAtLeast(0).dp + border,
        top = (frame?.paddingTop ?: 0).coerceAtLeast(0).dp + insetTop + border,
        end = (frame?.paddingRight ?: 0).coerceAtLeast(0).dp + border,
        bottom = (frame?.paddingBottom ?: 0).coerceAtLeast(0).dp + border,
    )
}


internal fun parseFramePadding(frame: FrameData?): PaddingValues {
    return PaddingValues(
        start = (frame?.paddingLeft ?: 0).coerceAtLeast(0).dp,
        top = (frame?.paddingTop ?: 0).coerceAtLeast(0).dp,
        end = (frame?.paddingRight ?: 0).coerceAtLeast(0).dp,
        bottom = (frame?.paddingBottom ?: 0).coerceAtLeast(0).dp,
    )
}

@Composable
internal fun Modifier.flexOverflow(direction: FlexDirection, overflow: Overflow?): Modifier {
    val overflow = overflow ?: return this
    if (overflow != Overflow.SCROLL) return this
    return if (direction == FlexDirection.ROW) {
        this
            .horizontalScroll(rememberScrollState())
    } else {
        this
            .verticalScroll(rememberScrollState())
    }
}

private fun extractRgba(value: ColorValue): Array<Float?> = when (value) {
    is ColorValue.UnionColor -> arrayOf(value.data.red, value.data.green, value.data.blue, value.data.alpha)
    is ColorValue.UnionLinearGradient -> arrayOf(value.data.red, value.data.green, value.data.blue, value.data.alpha)
    else -> arrayOf(null, null, null, null)
}

internal fun parseColor(color: ColorValue?): Color {
    val (r, g, b, a) = color?.let { extractRgba(it) } ?: arrayOf<Float?>(null, null, null, null)
    return Color(
        red = MathUtils.clamp(r ?: 0f, 0f, 1f),
        green = MathUtils.clamp(g ?: 0f, 0f, 1f),
        blue = MathUtils.clamp(b ?: 0f, 0f, 1f),
        alpha = MathUtils.clamp(a ?: 0f, 0f, 1f),
    )
}

internal fun parseColorForText(color: ColorValue?): Color? {
    if (color == null) return null
    val (r, g, b, a) = extractRgba(color)
    if (r == null) return null
    return Color(
        red = MathUtils.clamp(r, 0f, 1f),
        green = MathUtils.clamp(g ?: 0f, 0f, 1f),
        blue = MathUtils.clamp(b ?: 0f, 0f, 1f),
        alpha = MathUtils.clamp(a ?: 0f, 0f, 1f),
    )
}

internal fun parseHorizontalAlignItems(alignItems: AlignItems?): Alignment.Horizontal {
    return when (alignItems) {
        AlignItems.START -> Alignment.Start
        AlignItems.CENTER -> Alignment.CenterHorizontally
        AlignItems.END -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
}

internal fun parseVerticalAlignItems(alignItems: AlignItems?): Alignment.Vertical {
    return when (alignItems) {
        AlignItems.START -> Alignment.Top
        AlignItems.CENTER -> Alignment.CenterVertically
        AlignItems.END -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }
}

@Composable
internal fun Flex(
    block: UIFlexContainerBlock,
    modifier: Modifier = Modifier,
    insetTop: Dp
) {
    val data = DataContext.state
    val direction: FlexDirection = block.data?.direction ?: FlexDirection.ROW
    val overflow = when (block.data?.overflow) {
        Overflow.VISIBLE -> Overflow.VISIBLE
        Overflow.SCROLL -> Overflow.SCROLL
        else -> Overflow.HIDDEN
    }
    val flexModifier = modifier
        .eventDispatcher(block.data?.onClick)
        .frameSize(
            block.data?.frame,
            clipsContent = overflow == Overflow.HIDDEN || overflow == Overflow.SCROLL,
        )
        .framePadding(block.data?.frame, insetTop)
        .zIndex(1f)

    val gap = (block.data?.gap ?: 0).coerceAtLeast(0)
    val justifyContent = block.data?.justifyContent
    val alignItems = block.data?.alignItems
    val children = block.data?.children ?: emptyList()

    Box(modifier = modifier) {
        if (block.data?.frame?.backgroundSrc != null) {
            val src = compile(block.data.frame.backgroundSrc, data.data)
            val fallback = parseImageFallbackToBlurhash(src)
            val placeholder = rememberBlurHashPlaceholder(fallback)
            val imageLoader = rememberNubrickImageLoader()
            AsyncImage(
                modifier = Modifier
                    .zIndex(0f)
                    .matchParentSize()
                    .borderRadius(block.data.frame),
                model = ImageRequest.Builder(LocalContext.current)
                    .data(src)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = placeholder,
            )
        }
        if (overflow != Overflow.SCROLL) {
            OverflowingFlex(
                children = children,
                direction = direction,
                gap = gap.dp,
                justifyContent = justifyContent,
                alignItems = alignItems,
                modifier = flexModifier,
            )
        } else {
            ScrollableFlex(
                children = children,
                direction = direction,
                gap = gap.dp,
                justifyContent = justifyContent,
                alignItems = alignItems,
                modifier = flexModifier,
            )
        }
    }
}
