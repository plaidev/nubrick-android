package app.nubrick.nubrick.view

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
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
import app.nubrick.nubrick.NubrickSize
import app.nubrick.nubrick.R
import kotlin.math.roundToInt

/**
 * Java-friendly callback for events emitted by a [NubrickEmbeddingView].
 */
fun interface NubrickEventListener {
    fun onEvent(event: Event)
}

/**
 * Java-friendly callback for size changes emitted by a [NubrickEmbeddingView].
 */
fun interface NubrickSizeListener {
    fun onSizeChange(width: NubrickSize, height: NubrickSize)
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
    private var sizeListener by mutableStateOf<NubrickSizeListener?>(null)
    private var hostWidth: Int? = null
    private var hostHeight: Int? = null

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

    fun setOnSizeChangeListener(listener: NubrickSizeListener?) {
        sizeListener = listener
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        super.setLayoutParams(params)
        hostWidth = params?.width
        hostHeight = params?.height
    }

    @Composable
    override fun Content() {
        if (currentExperimentId.isEmpty()) return

        NubrickSDK.Embedding(
            id = currentExperimentId,
            modifier = Modifier.fillMaxSize(),
            arguments = currentArguments,
            onEvent = { event -> eventListener?.onEvent(event) },
            onSizeChange = { width, height ->
                applyEmbeddingSize(width, height)
                sizeListener?.onSizeChange(width, height)
            },
        )
    }

    /** Applies backend dimensions only when the corresponding host dimension is `wrap_content`. */
    private fun applyEmbeddingSize(width: NubrickSize, height: NubrickSize) {
        val params = layoutParams ?: return
        val originalWidth = hostWidth ?: params.width
        val originalHeight = hostHeight ?: params.height
        var changed = false
        if (originalWidth == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val targetWidth = width.toBackendLayoutDimension()
            if (params.width != targetWidth) {
                params.width = targetWidth
                changed = true
            }
        }
        if (originalHeight == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val targetHeight = height.toBackendLayoutDimension()
            if (params.height != targetHeight) {
                params.height = targetHeight
                changed = true
            }
        }
        if (!changed) return

        super.setLayoutParams(params)
    }

    private fun NubrickSize.toBackendLayoutDimension(): Int {
        return when (this) {
            is NubrickSize.Fixed -> (value * resources.displayMetrics.density).roundToInt()
            NubrickSize.Fill -> ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }
}
