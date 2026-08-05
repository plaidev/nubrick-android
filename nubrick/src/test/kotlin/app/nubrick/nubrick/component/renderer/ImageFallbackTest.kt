package app.nubrick.nubrick.component.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFallbackTest {
    @Test
    fun parseReturnsNoneWhenWidthIsNotNumeric() {
        val fallback = parseImageFallbackToBlurhash(
            "https://example.com/img.jpg?w=abc&h=1&b=LEHV6nWB2yk8pyo0adR*.7kCMdnj"
        )
        assertEquals(ImageFallback(blurhash = "", width = 0, height = 0), fallback)
    }
}
