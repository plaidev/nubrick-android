package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

internal fun Modifier.modalSafeAreaPadding(
    insets: WindowInsets,
    nonTopSides: WindowInsetsSides,
    fixedTopInset: Dp?,
): Modifier = if (fixedTopInset == null) {
    windowInsetsPadding(insets.only(WindowInsetsSides.Top + nonTopSides))
} else {
    padding(top = fixedTopInset)
        .consumeWindowInsets(insets.only(WindowInsetsSides.Top))
        .windowInsetsPadding(insets.only(nonTopSides))
}
