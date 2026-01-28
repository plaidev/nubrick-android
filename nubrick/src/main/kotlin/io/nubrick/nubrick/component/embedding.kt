package io.nubrick.nubrick.component

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nubrick.nubrick.Event
import io.nubrick.nubrick.data.Container
import io.nubrick.nubrick.data.NotFoundException
import io.nubrick.nubrick.schema.UIBlock

sealed class EmbeddingLoadingState {
    class Loading(): EmbeddingLoadingState()
    class Completed(var view: @Composable() () -> Unit): EmbeddingLoadingState()
    class NotFound(): EmbeddingLoadingState()
    class Failed(e: Throwable): EmbeddingLoadingState()
}

@Composable
internal fun rememberEmbeddingState(container: Container, experimentId: String, componentId: String?, onEvent: ((event: Event) -> Unit)?, onWidthChange: (Int?) -> Unit, onHeightChange: (Int?) -> Unit): EmbeddingLoadingState {
    var loadingState: EmbeddingLoadingState by remember { mutableStateOf(EmbeddingLoadingState.Loading()) }
    LaunchedEffect("key") {
        onWidthChange(null)
        onHeightChange(null)
        container.fetchEmbedding(experimentId, componentId).onSuccess {
            loadingState = when (it) {
                is UIBlock.UnionUIRootBlock -> {
                    EmbeddingLoadingState.Completed {
                        Root(
                            container = container,
                            root = it.data,
                            modifier = Modifier
                                .fillMaxSize(),
                            onEvent = onEvent ?: {},
                            onWidthChange = onWidthChange,
                            onHeightChange = onHeightChange
                        )
                    }
                }

                else -> {
                    onWidthChange(null)
                    onHeightChange(null)
                    EmbeddingLoadingState.NotFound()
                }
            }
        }.onFailure {
            onWidthChange(null)
            onHeightChange(null)
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
    componentId: String? = null,
    modifier: Modifier = Modifier,
    onEvent: ((event: Event) -> Unit)? = null,
    content: (@Composable() (state: EmbeddingLoadingState) -> Unit)?
) {
    var width: Int? by remember(experimentId, componentId) { mutableStateOf(null) }
    var height: Int? by remember(experimentId, componentId) { mutableStateOf(null) }
    val state = rememberEmbeddingState(container, experimentId, componentId, onEvent, onWidthChange = { width = it }, onHeightChange = { height = it })
    val modifierWithSize = Modifier
        // 0 means fill/unspecified size from editor; only apply fixed sizes when non-zero.
        .then(width?.takeIf { it != 0 }?.let { Modifier.width(it.dp) } ?: Modifier)
        .then(height?.takeIf { it != 0 }?.let { Modifier.height(it.dp) } ?: Modifier)
        .then(modifier)
    
    Box(modifier = modifierWithSize) {
        AnimatedContent(
            targetState = state,
            label = "EmbeddingLoadingStateAnimation",
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            modifier = Modifier.fillMaxSize()
        ) { state ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    is EmbeddingLoadingState.Completed -> if (content != null) content(state) else state.view()
                    is EmbeddingLoadingState.Loading -> if (content != null) content(state) else CircularProgressIndicator()
                    else -> if (content != null) content(state) else Unit
                }
            }
        }
    }
}
