package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nubrick.nubrick.schema.UIPageBlock

@Composable
internal fun Page(
    block: UIPageBlock,
    modifier: Modifier = Modifier,
    isModal: Boolean = false,
    safeAreaInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val renderAs = block.data?.renderAs ?: return
    val contentModifier = if (isModal && block.data.modalRespectSafeArea == true) {
        Modifier.windowInsetsPadding(safeAreaInsets).then(
            if (block.data.modalNavigationBackButton?.visible != false) {
                Modifier.padding(top = ModalNavigationHeaderHeight)
            } else {
                Modifier
            }
        )
    } else {
        Modifier
    }
    Block(block = renderAs, modifier = modifier, flexContentModifier = contentModifier)
}
