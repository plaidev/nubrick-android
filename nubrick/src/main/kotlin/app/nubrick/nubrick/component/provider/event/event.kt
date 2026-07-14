package app.nubrick.nubrick.component.provider.event

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import app.nubrick.nubrick.component.provider.container.ContainerContext
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.data.toFormData
import app.nubrick.nubrick.schema.UIBlockAction
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal val LocalEventListener = compositionLocalOf<EventListenerState> {
    error("LocalEventListener is not found")
}

internal data class EventListenerState(
    internal val listener: (event: UIBlockAction, data: JsonElement) -> Unit
) {
    fun dispatch(event: UIBlockAction, data: JsonElement) {
        this.listener(event, data)
    }
}

@Composable
internal fun rememberEventListenerState(
    listener: (event: UIBlockAction, data: JsonElement) -> Unit
): EventListenerState {
    return remember(listener) {
        EventListenerState(listener)
    }
}

@Composable
internal fun EventListenerProvider(
    listener: (event: UIBlockAction, data: JsonElement) -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberEventListenerState(listener)
    CompositionLocalProvider(
        LocalEventListener provides state
    ) {
        content()
    }
}

internal fun requiredFieldsAreInvalid(
    requiredFields: List<String>?,
    values: Map<String, JsonElement>,
): Boolean = requiredFields?.any { key ->
    when (val value = values[key]) {
        null, JsonNull -> true
        is JsonPrimitive -> value.isString && value.content.isEmpty()
        is JsonArray -> value.isEmpty()
        else -> false
    }
} ?: false

@Composable
internal fun Modifier.eventDispatcher(
    action: UIBlockAction?
): Modifier = composed {
    val container = ContainerContext.value
    val data = DataContext.state.data
    val eventListener = LocalEventListener.current
    val action = action ?: return@composed this

    var isRequestPending by remember(action) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }

    val formValues = if (action.requiredFields.isNullOrEmpty()) {
        emptyMap()
    } else {
        val formValues by container.formValuesFlow.collectAsStateWithLifecycle()
        formValues.toFormData()
    }
    val hasInvalidRequiredFields = requiredFieldsAreInvalid(action.requiredFields, formValues)
    val enabled = !hasInvalidRequiredFields && !isRequestPending
    val alpha = when {
        hasInvalidRequiredFields -> 0.5f
        isRequestPending -> 0.8f
        else -> 1f
    }

    this
        .alpha(alpha)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
            val req = action.httpRequest
            if (req != null) {
                isRequestPending = true
                scope.launch {
                    try {
                        container.sendHttpRequest(req, data)
                    } finally {
                        isRequestPending = false
                    }
                }
            }

            eventListener.dispatch(action, data)
        }
}

@Composable
internal fun Modifier.skeleton(enable: Boolean = false): Modifier {
    return composed {
        if (!enable) return@composed this

        val skeletonColors = listOf(
            Color.Black.copy(alpha = 0.08f),
            Color.Black.copy(alpha = 0.09f),
            Color.Black.copy(alpha = 0.11f),
            Color.Black.copy(alpha = 0.09f),
            Color.Black.copy(alpha = 0.08f),
        )
        val width = 500
        val duration = 1000
        val transition = rememberInfiniteTransition(label = "Skeleton loading transition")
        val translateAnimation = transition.animateFloat(
            initialValue = 0f,
            targetValue = (duration.toFloat() + width.toFloat()),
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "Skeleton loading animation"
        )
        this.background(
            brush = Brush.linearGradient(
                colors = skeletonColors,
                start = Offset(x = translateAnimation.value - width, y = 0.0f),
                end = Offset(x = translateAnimation.value, y = 270f),
            )
        )
    }
}
