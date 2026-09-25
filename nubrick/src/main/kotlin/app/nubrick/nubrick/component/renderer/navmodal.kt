package app.nubrick.nubrick.component.renderer

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.nubrick.nubrick.schema.UIPageBlock

internal val ModalNavigationHeaderHeight = 60.dp

@Composable
internal fun ModalBottomSheetBackHandler(handler: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }

    val currentHandler = rememberUpdatedState(handler)
    val view = LocalView.current
    val callback = remember {
        OnBackInvokedCallback {
            currentHandler.value()
        }
    }

    DisposableEffect(view, callback) {
        val dispatcher = view.findOnBackInvokedDispatcher()
        dispatcher?.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback
        )

        onDispose {
            dispatcher?.unregisterOnBackInvokedCallback(callback)
        }
    }
}

@Composable
internal fun NavigationHeader(
    index: Int,
    block: UIPageBlock,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val visibility = block.data?.modalNavigationBackButton?.visible ?: true
    if (!visibility) return

    Box(
        modifier = Modifier
            .zIndex(10f)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .height(ModalNavigationHeaderHeight)
            .padding(top = 8.dp)
    ) {
        if (index > 0) {
            IconButton(onClick = { onBack() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back"
                )
            }
        } else {
            IconButton(onClick = { onClose() }, modifier = Modifier.size(48.dp)) {
                Icon(imageVector = Icons.Outlined.Close, contentDescription = "Close")
            }
        }
    }
}
