package io.nubrick.nubrick.component.renderer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nubrick.nubrick.schema.UIPageBlock

@Composable
internal fun Page(
    block: UIPageBlock,
    modifier: Modifier = Modifier,
    modalInsetTop: Dp = 0.dp,
) {
    val renderAs = block.data?.renderAs ?: return
    Block(block = renderAs, modifier, modalInsetTop)
}
