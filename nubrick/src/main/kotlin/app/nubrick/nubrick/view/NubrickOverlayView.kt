package app.nubrick.nubrick.view

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.nubrick.nubrick.NubrickSDK
import app.nubrick.nubrick.component.NubrickTheme

/**
 * Hosts Nubrick's app-level popup overlay in a traditional Android View hierarchy.
 *
 * Add one full-screen instance above the activity's content.
 */
class NubrickOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    @Composable
    override fun Content() {
        NubrickTheme {
            NubrickSDK.Overlay()
        }
    }
}
