package app.nubrick.nubrick.component.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class TextLineHeightTest {
    @Test
    fun invalidLineHeightFallsBackToTheLegacyDefault() {
        assertEquals(19.2f, resolveTextLineHeight(-1f, 16), 0f)
        assertEquals(19.2f, resolveTextLineHeight(0f, 16), 0f)
        assertEquals(19.2f, resolveTextLineHeight(Float.NaN, 16), 0f)
        assertEquals(19.2f, resolveTextLineHeight(Float.POSITIVE_INFINITY, 16), 0f)
        assertEquals(19.2f, resolveTextLineHeight(Float.MAX_VALUE, 16), 0f)
    }

    @Test
    fun validLineHeightIsPreserved() {
        assertEquals(18f, resolveTextLineHeight(18f, 16), 0f)
    }

    @Test
    fun invalidTextSizeFallsBackBeforeBuildingTheTextStyle() {
        assertEquals(16, resolveTextFontSize(0))
        assertEquals(16, resolveTextFontSize(-1))
        assertEquals(16, resolveTextFontSize(Int.MAX_VALUE))
        assertEquals(19.2f, resolveTextLineHeight(null, -1), 0f)
    }

    @Test
    fun lineHeightUsesComposeRoundingForEachLine() {
        assertEquals(20, normalizeTextLineHeight(19.2f))
        assertEquals(16, normalizeTextLineHeight(15.6f))
    }

    @Test
    fun lineHeightPreservesItsRatioUnderNonLinearFontScaling() {
        val nonLinearDensity = object : Density {
            override val density = 1f
            override val fontScale = 2f

            override fun TextUnit.toDp(): Dp = when (value) {
                13f -> 25.dp
                15.6f -> 27.6.dp
                else -> value.dp
            }

            override fun Dp.toSp(): TextUnit = when (value) {
                25f -> 13.sp
                27.6f -> 15.6.sp
                else -> value.sp
            }
        }
        val style = TextStyle(fontSize = 13.sp, lineHeight = 15.6.sp)

        assertEquals(30f, resolveTextLineHeightPx(style, nonLinearDensity), 0.01f)
    }

    @Test
    fun textUnitScalesWithFontScaleByDefault() {
        val density = Density(density = 1f, fontScale = 2f)

        val fontSize = resolveTextUnit(13f, scaleWithDeviceFontSize = true, density)

        // Same as a plain 13.sp under this density.
        assertEquals(with(density) { 13.sp.toPx() }, with(density) { fontSize.toPx() }, 0.01f)
        assertTrue(with(density) { fontSize.toPx() } > 13f)
    }

    @Test
    fun textUnitIgnoresFontScaleWhenOptedOut() {
        val density = Density(density = 1f, fontScale = 2f)

        val fontSize = resolveTextUnit(13f, scaleWithDeviceFontSize = false, density)

        assertEquals(13f, with(density) { fontSize.toPx() }, 0.01f)
    }

    @Test
    fun lineHeightPxIgnoresFontScaleWhenOptedOut() {
        val density = Density(density = 2f, fontScale = 3f)
        val style = TextStyle(
            fontSize = resolveTextUnit(13f, scaleWithDeviceFontSize = false, density),
            lineHeight = resolveTextLineHeightUnit(
                lineHeight = 15.6f,
                size = 13,
                scaleWithDeviceFontSize = false,
                density = density,
            ),
        )

        assertEquals(15.6f * 2f, resolveTextLineHeightPx(style, density), 0.01f)
    }

    @Test
    fun lineHeightPxPreservesItsAuthoredSizeUnderNonLinearFontScalingWhenOptedOut() {
        val nonLinearDensity = object : Density {
            override val density = 1f
            override val fontScale = 2f

            override fun TextUnit.toDp(): Dp = when (value) {
                8f -> 13.dp
                9.6f -> 16.9.dp
                else -> value.dp
            }

            override fun Dp.toSp(): TextUnit = when (value) {
                13f -> 8.sp
                else -> value.sp
            }
        }
        val style = TextStyle(
            fontSize = resolveTextUnit(13f, scaleWithDeviceFontSize = false, nonLinearDensity),
            lineHeight = resolveTextLineHeightUnit(
                lineHeight = 15.6f,
                size = 13,
                scaleWithDeviceFontSize = false,
                density = nonLinearDensity,
            ),
        )

        assertEquals(15.6f, resolveTextLineHeightPx(style, nonLinearDensity), 0.01f)
    }
}
