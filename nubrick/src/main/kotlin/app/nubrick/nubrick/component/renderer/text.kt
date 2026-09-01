package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.component.provider.event.eventDispatcher
import app.nubrick.nubrick.component.provider.event.skeleton
import app.nubrick.nubrick.schema.ColorValue
import app.nubrick.nubrick.schema.FontDesign
import app.nubrick.nubrick.schema.FontWeight
import app.nubrick.nubrick.schema.TextAlign
import app.nubrick.nubrick.schema.UITextBlock
import app.nubrick.nubrick.template.compile
import app.nubrick.nubrick.template.hasDataPlaceholder
import app.nubrick.nubrick.template.hasPlaceholder
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color as PrimitiveColor
import androidx.compose.ui.text.font.FontFamily as PrimitiveFontFamily
import androidx.compose.ui.text.font.FontWeight as PrimitiveFontWeight
import androidx.compose.ui.text.style.TextAlign as PrimitiveTextAlign
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.roundToInt

// Backward-compatible default for text authored before lineHeight was added.
private const val TEXT_LINE_HEIGHT_RATIO = 1.2f
private const val DEFAULT_TEXT_SIZE = 16
private const val MAX_TEXT_SIZE = 512
private const val MAX_TEXT_LINE_HEIGHT = 1_024f

internal fun resolveTextFontSize(size: Int?): Int =
    size?.takeIf { it in 1..MAX_TEXT_SIZE } ?: DEFAULT_TEXT_SIZE

internal fun resolveTextLineHeight(lineHeight: Float?, size: Int?): Float {
    val fallback = resolveTextFontSize(size) * TEXT_LINE_HEIGHT_RATIO
    return lineHeight?.takeIf {
        it.isFinite() && it > 0f && it <= MAX_TEXT_LINE_HEIGHT
    } ?: fallback
}

/** Matches Compose's ratio-preserving line-height conversion under non-linear font scaling. */
internal fun resolveTextLineHeightPx(style: TextStyle, density: Density): Float {
    val fontSizePx = with(density) { style.fontSize.toPx() }
    val fontSizeSp = with(density) { fontSizePx.toSp() }
    return if (fontSizePx.isFinite() && fontSizeSp.value.isFinite() && fontSizeSp.value > 0f) {
        style.lineHeight.value / fontSizeSp.value * fontSizePx
    } else {
        with(density) { style.lineHeight.toPx() }
    }
}

/** Compose's Android line-height span rounds each authored line up to a whole pixel. */
internal fun normalizeTextLineHeight(lineHeight: Float): Int = ceil(lineHeight).toInt().coerceAtLeast(1)

private class TextLayoutInfo {
    var lineCount = 1
}

@Composable
internal fun rememberBaselineFromCenter(style: TextStyle): Float {
    val density = LocalDensity.current
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val typeface = resolver.resolve(
        fontFamily = style.fontFamily,
        fontWeight = style.fontWeight ?: PrimitiveFontWeight.Normal,
    ).value as Typeface
    val fontSizePx = with(density) { style.fontSize.toPx() }

    return remember(typeface, fontSizePx) {
        Paint().apply {
            this.typeface = typeface
            textSize = fontSizePx
        }.fontMetricsInt.let { metrics ->
            (-metrics.ascent - metrics.descent) / 2f
        }
    }
}

/** Give every glyph the same authored line box and a consistent baseline within it. */
internal fun Modifier.normalizeTextLines(
    baselineFromCenter: Float,
    lineHeight: Float,
    lineCount: () -> Int,
): Modifier = layout {
        measurable,
        constraints,
    ->
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
    )

    // BasicText invokes onTextLayout synchronously while it is measured above.
    val actualLineCount = lineCount().coerceAtLeast(1)
    val normalizedLineHeight = normalizeTextLineHeight(lineHeight)
    val normalizedHeight = (normalizedLineHeight.toLong() * actualLineCount)
        .coerceIn(constraints.minHeight.toLong(), constraints.maxHeight.toLong())
        .toInt()
    val firstBaseline = placeable[FirstBaseline]

    if (firstBaseline == AlignmentLine.Unspecified) {
        layout(placeable.width, normalizedHeight) {
            placeable.placeRelative(0, 0)
        }
    } else {
        val normalizedFirstBaseline = normalizedLineHeight / 2f + baselineFromCenter
        val verticalOffset = (normalizedFirstBaseline - firstBaseline).roundToInt()
        val lastBaseline = normalizedFirstBaseline +
            normalizedLineHeight.toFloat() * (actualLineCount - 1)
        layout(
            placeable.width,
            normalizedHeight,
            mapOf(
                FirstBaseline to normalizedFirstBaseline.roundToInt(),
                LastBaseline to lastBaseline.roundToInt(),
            ),
        ) {
            placeable.placeRelative(0, verticalOffset)
        }
    }
}

internal fun parseFontDesign(fontDesign: FontDesign?): PrimitiveFontFamily {
    return when (fontDesign) {
        FontDesign.DEFAULT -> PrimitiveFontFamily.Default
        FontDesign.ROUNDED -> PrimitiveFontFamily.Default // Fallback to default as comose doesn't have rounded font
        FontDesign.MONOSPACE -> PrimitiveFontFamily.Monospace
        FontDesign.SERIF -> PrimitiveFontFamily.Serif
        else -> PrimitiveFontFamily.Default
    }
}

internal fun parseFontWeight(fontWeight: FontWeight?): PrimitiveFontWeight {
    return when (fontWeight) {
        FontWeight.ULTRA_LIGHT -> PrimitiveFontWeight.ExtraLight
        FontWeight.THIN -> PrimitiveFontWeight.Thin
        FontWeight.LIGHT -> PrimitiveFontWeight.Light
        FontWeight.REGULAR -> PrimitiveFontWeight.Normal
        FontWeight.MEDIUM -> PrimitiveFontWeight.Medium
        FontWeight.SEMI_BOLD -> PrimitiveFontWeight.SemiBold
        FontWeight.BOLD -> PrimitiveFontWeight.Bold
        FontWeight.HEAVY -> PrimitiveFontWeight.ExtraBold
        FontWeight.BLACK -> PrimitiveFontWeight.Black
        else -> PrimitiveFontWeight.Normal
    }
}

@Composable
internal fun parseFontStyle(size: Int? = null, color: ColorValue? = null, fontWeight: FontWeight? = null, fontDesign: FontDesign? = null, alignment: TextAlign? = null, transparent: Boolean = false): TextStyle {
    val textColor = parseColorForText(color) ?: MaterialTheme.colorScheme.onSurface
    return TextStyle.Default.copy(
        color = if (transparent) PrimitiveColor.Transparent else textColor,
        fontSize = size?.sp ?: 16.sp,
        fontWeight = parseFontWeight(fontWeight = fontWeight),
        fontFamily = parseFontDesign(fontDesign = fontDesign),
        textAlign = parseTextAlign(alignment = alignment),
    )
}

internal fun parseTextAlign(alignment: TextAlign?): PrimitiveTextAlign {
    return when (alignment) {
        TextAlign.CENTER -> PrimitiveTextAlign.Center
        TextAlign.LEFT -> PrimitiveTextAlign.Left
        TextAlign.RIGHT -> PrimitiveTextAlign.Right
        else -> PrimitiveTextAlign.Unspecified
    }
}

@Composable
internal fun Text(block: UITextBlock, modifier: Modifier = Modifier) {
    val data = DataContext.state
    val loading = data.loading
    val rawValue = block.data?.value ?: ""
    val skeleton = hasDataPlaceholder(rawValue) && loading
    var value = rawValue
    if (hasPlaceholder(rawValue) && !skeleton) {
        value = compile(rawValue, data.data)
    }
    val containerModifier = modifier
        .eventDispatcher(block.data?.onClick)
        .styleByFrame(block.data?.frame)
        .skeleton(skeleton)

    val fontStyle = parseFontStyle(
        size = resolveTextFontSize(block.data?.size),
        color = block.data?.color,
        fontWeight = block.data?.weight,
        fontDesign = block.data?.design,
        alignment = null,
        transparent = skeleton,
    ).copy(
        lineHeight = resolveTextLineHeight(block.data?.lineHeight, block.data?.size).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    val baselineFromCenter = rememberBaselineFromCenter(fontStyle)
    val density = LocalDensity.current
    val lineHeightPx = resolveTextLineHeightPx(fontStyle, density)
    var maxLines = block.data?.maxLines ?: Int.MAX_VALUE
    if (maxLines <= 0) {
        maxLines = Int.MAX_VALUE
    }
    val textLayoutInfo = remember(value, maxLines) { TextLayoutInfo() }
    val lineCountProvider = remember(textLayoutInfo) { { textLayoutInfo.lineCount } }
    val onTextLayout = remember(textLayoutInfo) {
        { layoutResult: TextLayoutResult -> textLayoutInfo.lineCount = layoutResult.lineCount }
    }
    val textModifier = remember(baselineFromCenter, lineHeightPx, lineCountProvider) {
        Modifier
            .zIndex(1f)
            .normalizeTextLines(baselineFromCenter, lineHeightPx, lineCountProvider)
    }

    Box(modifier = containerModifier) {
        if (block.data?.frame?.backgroundSrc != null) {
            val src = compile(block.data.frame.backgroundSrc, data.data)
            val fallback = parseImageFallbackToBlurhash(src)
            val placeholder = rememberBlurHashPlaceholder(fallback)
            val imageLoader = rememberNubrickImageLoader()
            AsyncImage(
                modifier = Modifier
                    .zIndex(0f)
                    .matchParentSize(),
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
        BasicText(
            text = value,
            modifier = textModifier,
            style = fontStyle,
            maxLines = maxLines,
            onTextLayout = onTextLayout,
        )
    }
}
