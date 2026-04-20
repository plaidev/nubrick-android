package io.nubrick.nubrick.component.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import io.nubrick.nubrick.FlutterBridgeApi
import io.nubrick.nubrick.component.provider.container.ContainerContext
import io.nubrick.nubrick.component.provider.data.DataContext
import io.nubrick.nubrick.component.provider.event.LocalEventListener
import io.nubrick.nubrick.schema.UIBlockAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// forcefully dispatch uiblock event in the page compose context, from anywhere.
// dispatch(event) in flutter -> listen the event in the page context, and dispatch event from the page.
@FlutterBridgeApi
public class UIBlockActionBridge {
    private val _events = MutableSharedFlow<UIBlockAction>()
    internal val events: SharedFlow<UIBlockAction> = _events

    suspend fun dispatch(event: String) {
        val json = Json.decodeFromString<JsonElement>(event)
        val dispatcher = UIBlockAction.decode(json) ?: return
        _events.emit(dispatcher)
    }
}

// Watch the event stream, and when it has events, then dispatch them.
// This composable won't render anything.
@Composable
internal fun UIBlockActionBridgeCollector(
    events: SharedFlow<UIBlockAction>?,
    isCurrentPage: Boolean
) {
    val latestContainer = rememberUpdatedState(ContainerContext.value)
    val latestData = rememberUpdatedState(DataContext.state.data)
    val latestEventListener = rememberUpdatedState(LocalEventListener.current)
    LaunchedEffect(events, isCurrentPage) {
        if (!isCurrentPage || events == null) {
            return@LaunchedEffect
        }

        events.collect { event ->
            val data = latestData.value
            val req = event.httpRequest
            req?.runCatching { latestContainer.value.sendHttpRequest(this, data) }
                ?.onFailure { if (it is CancellationException) throw it }
            latestEventListener.value.dispatch(event, data)
        }
    }
}
