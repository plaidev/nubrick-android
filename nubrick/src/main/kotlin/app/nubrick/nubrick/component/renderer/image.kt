package app.nubrick.nubrick.component.renderer

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.component.provider.event.eventDispatcher
import app.nubrick.nubrick.component.provider.event.skeleton
import app.nubrick.nubrick.schema.ImageContentMode
import app.nubrick.nubrick.schema.UIImageBlock
import app.nubrick.nubrick.template.compile
import app.nubrick.nubrick.template.hasDataPlaceholder
import app.nubrick.nubrick.template.hasPlaceholder
import app.nubrick.nubrick.vendor.blurhash.BlurHashDecoder

internal data class ImageFallback(
    val blurhash: String,
    val width: Int,
    val height: Int,
){}

internal fun parseImageFallbackToBlurhash(src: String): ImageFallback {
    val none = ImageFallback(blurhash = "", width = 0, height = 0)
    try {
        val uri = Uri.parse(src)
        val width = uri.getQueryParameter("w")?.toIntOrNull() ?: return none
        val height = uri.getQueryParameter("h")?.toIntOrNull() ?: return none
        val blurhash = uri.getQueryParameter("b") ?: return none
        if (!BlurHashDecoder.supportsDimensions(width, height)) return none
        return ImageFallback(blurhash = blurhash, width = width, height = height)
    } catch (_: Exception) {
        return none
    }
}

@Composable
internal fun rememberBlurHashPlaceholder(fallback: ImageFallback): Painter? {
    val decoded = remember(fallback) {
        BlurHashDecoder.decode(
            blurHash = fallback.blurhash,
            height = fallback.height,
            width = fallback.width
        )
    }

    return remember(decoded) {
        decoded?.let { BitmapPainter(it.asImageBitmap()) }
    }
}

internal fun parseContentModeToContentScale(contentMode: ImageContentMode?): ContentScale {
    return when (contentMode) {
        ImageContentMode.FILL -> ContentScale.Crop
        ImageContentMode.FIT -> ContentScale.Fit
        else -> ContentScale.Crop
    }
}

@Composable
internal fun Image(block: UIImageBlock, modifier: Modifier = Modifier) {
    val data = DataContext.state
    val loading = data.loading
    val rawSrc = block.data?.src ?: ""
    val skeleton = hasDataPlaceholder(rawSrc) && loading
    var src = rawSrc
    if (hasPlaceholder(rawSrc) && !skeleton) {
        src = compile(rawSrc, data.data)
    }

    val modifier = modifier
        .eventDispatcher(block.data?.onClick)
        .styleByFrame(block.data?.frame)
        .skeleton(skeleton)

    val fallback = parseImageFallbackToBlurhash(src)
    val placeholder = rememberBlurHashPlaceholder(fallback)
    val contentScale = parseContentModeToContentScale(block.data?.contentMode)
    val imageLoader = rememberNubrickImageLoader()

    AsyncImage(
        modifier = modifier,
        model = ImageRequest.Builder(LocalContext.current)
            .data(src)
            .crossfade(true)
            .build(),
        imageLoader = imageLoader,
        contentDescription = null,
        placeholder = placeholder,
        contentScale = contentScale,
    )
}
