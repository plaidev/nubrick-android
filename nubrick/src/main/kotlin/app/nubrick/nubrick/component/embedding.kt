package app.nubrick.nubrick.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nubrick.nubrick.Event
import app.nubrick.nubrick.NubrickSize
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.data.NotFoundException

sealed class EmbeddingLoadingState {
    class Loading(): EmbeddingLoadingState()
    class Completed(var view: @Composable() () -> Unit): EmbeddingLoadingState()
    class NotFound(): EmbeddingLoadingState()
    class Failed(val e: Throwable): EmbeddingLoadingState()
}

@Composable
internal fun rememberEmbeddingState(
    container: Container,
    arguments: Any?,
    experimentId: String,
    componentId: String?,
    onEvent: ((event: Event) -> Unit)?,
    onSizeChange: (width: NubrickSize, height: NubrickSize) -> Unit
): EmbeddingLoadingState {
    val currentArguments = rememberUpdatedState(arguments)
    val currentOnEvent = rememberUpdatedState(onEvent)
    val currentOnSizeChange = rememberUpdatedState(onSizeChange)
    var loadingState: EmbeddingLoadingState by remember(experimentId, container.variantId, componentId) {
        mutableStateOf(EmbeddingLoadingState.Loading())
    }
    LaunchedEffect(experimentId, container.variantId, componentId) {
        container.fetchEmbedding(
            experimentId = experimentId,
            componentId = componentId,
        ).onSuccess { content ->
            loadingState = EmbeddingLoadingState.Completed {
                Root(
                    container = container,
                    arguments = currentArguments.value,
                    root = content.root,
                    experimentId = content.experimentId,
                    variantId = content.variantId,
                    modifier = Modifier
                        .fillMaxSize(),
                    onEvent = currentOnEvent.value ?: {},
                    onSizeChange = currentOnSizeChange.value
                )
            }
        }.onFailure {
            loadingState = when (it) {
                is NotFoundException -> {
                    EmbeddingLoadingState.NotFound()
                }

                else -> {
                    EmbeddingLoadingState.Failed(it)
                }
            }
        }
    }
    return loadingState
}

@Composable
internal fun Embedding(
    container: Container,
    experimentId: String,
    modifier: Modifier = Modifier,
    arguments: Any? = null,
    componentId: String? = null,
    onEvent: ((event: Event) -> Unit)? = null,
    content: (@Composable() (state: EmbeddingLoadingState) -> Unit)? = null,
    onSizeChange: ((width: NubrickSize, height: NubrickSize) -> Unit)? = null
) {
    var width: NubrickSize by remember(experimentId, container.variantId, componentId) { mutableStateOf(NubrickSize.Fill) }
    var height: NubrickSize by remember(experimentId, container.variantId, componentId) { mutableStateOf(NubrickSize.Fill) }
    val state = rememberEmbeddingState(
        container,
        arguments,
        experimentId,
        componentId,
        onEvent,
        onSizeChange = { newWidth, newHeight ->
            width = newWidth
            height = newHeight
            onSizeChange?.invoke(newWidth, newHeight)
        }
    )
    val w = width
    val h = height
    val modifierWithSize = Modifier
        .then(
            when (w) {
                is NubrickSize.Fixed -> Modifier.width(w.value.dp)
                NubrickSize.Fill -> Modifier
            }
        )
        .then(
            when (h) {
                is NubrickSize.Fixed -> Modifier.height(h.value.dp)
                NubrickSize.Fill -> Modifier
            }
        )
        .then(modifier)
    
    Box(modifier = modifierWithSize, contentAlignment = Alignment.Center) {
        when (state) {
            is EmbeddingLoadingState.Completed -> if (content != null) content(state) else state.view()
            is EmbeddingLoadingState.Loading -> if (content != null) content(state) else CircularProgressIndicator()
            else -> if (content != null) content(state) else Unit
        }
    }
    
}
