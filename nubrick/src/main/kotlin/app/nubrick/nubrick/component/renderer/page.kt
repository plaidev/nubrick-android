package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.nubrick.nubrick.schema.UIPageBlock

@Composable
internal fun Page(
    block: UIPageBlock,
    modifier: Modifier = Modifier,
    isModal: Boolean = false,
    safeAreaInsets: WindowInsets = WindowInsets.safeDrawing,
    fixedTopInset: Dp? = null,
) {
    val renderAs = block.data?.renderAs ?: return
    val contentModifier = if (isModal && block.data.modalRespectSafeArea == true) {
        val safeAreaModifier = Modifier.modalSafeAreaPadding(
            insets = safeAreaInsets,
            nonTopSides = WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            fixedTopInset = fixedTopInset,
        )
        safeAreaModifier.then(
            if (block.data.modalNavigationBackButton?.visible != false) {
                Modifier.padding(top = ModalNavigationHeaderHeight)
            } else {
                Modifier
            }
        )
    } else {
        Modifier
    }
    // Align the root within the page without overriding its authored frame or child alignment.
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Block(block = renderAs, flexContentModifier = contentModifier)
    }
}
