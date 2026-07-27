package app.nubrick.nubrick.view

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.nubrick.nubrick.Event
import app.nubrick.nubrick.NubrickSDK
import app.nubrick.nubrick.R

/**
 * Java-friendly callback for events emitted by a [NubrickEmbeddingView].
 */
fun interface NubrickEventListener {
    fun onEvent(event: Event)
}

/**
 * Hosts a Nubrick embedding in a traditional Android View hierarchy.
 *
 * The experiment ID can be supplied with the `nubrickExperimentId` XML attribute or by calling
 * [setExperimentId].
 */
class NubrickEmbeddingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    private var currentExperimentId by mutableStateOf("")
    private var currentArguments by mutableStateOf<Any?>(null)
    private var eventListener by mutableStateOf<NubrickEventListener?>(null)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

        val styledAttributes = context.obtainStyledAttributes(
            attrs,
            R.styleable.NubrickEmbeddingView,
            defStyleAttr,
            0,
        )
        try {
            currentExperimentId =
                styledAttributes.getString(R.styleable.NubrickEmbeddingView_nubrickExperimentId)
                    .orEmpty()
        } finally {
            styledAttributes.recycle()
        }
    }

    fun setExperimentId(experimentId: String) {
        currentExperimentId = experimentId
    }

    fun setArguments(arguments: Any?) {
        currentArguments = arguments
    }

    fun setOnEventListener(listener: NubrickEventListener?) {
        eventListener = listener
    }

    @Composable
    override fun Content() {
        if (currentExperimentId.isEmpty()) return

        NubrickSDK.Embedding(
            id = currentExperimentId,
            modifier = Modifier.fillMaxSize(),
            arguments = currentArguments,
            onEvent = { event -> eventListener?.onEvent(event) },
        )
    }
}
