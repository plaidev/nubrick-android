package app.nubrick.nubrick.vendor.blurhash

import org.junit.Assert.assertNull
import org.junit.Test

class BlurHashDecoderTest {
    // Known valid-length sample (Wolt / blurhash docs style).
    private val validBlurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"

    @Test
    fun decodeReturnsNullForEmptyHash() {
        assertNull(BlurHashDecoder.decode(blurHash = "", width = 32, height = 32))
    }

    @Test
    fun decodeReturnsNullForShortHash() {
        assertNull(BlurHashDecoder.decode(blurHash = "abc", width = 32, height = 32))
    }

    @Test
    fun decodeReturnsNullForZeroSize() {
        assertNull(BlurHashDecoder.decode(blurHash = validBlurHash, width = 0, height = 0))
    }

    @Test
    fun decodeReturnsNullForNegativeSize() {
        assertNull(BlurHashDecoder.decode(blurHash = validBlurHash, width = -1, height = 10))
    }
}
