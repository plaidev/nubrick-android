package app.nubrick.nubrick.component.provider.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.nubrick.nubrick.component.provider.container.ContainerContext
import app.nubrick.nubrick.component.provider.pageblock.PageBlockContext
import app.nubrick.nubrick.schema.ApiHttpRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val LocalData = compositionLocalOf<DataState> {
    error("LocalData is not found")
}

internal data class DataState(
    val loading: Boolean,
    val data: JsonElement
)

internal object DataContext {
    /**
     * Retrieves the current [DataState] at the call site's position in the hierarchy.
     */
    val state: DataState
        @Composable
        @ReadOnlyComposable
        get() = LocalData.current
}

@Composable
internal fun rememberPageState(
    arguments: Any?,
    request: ApiHttpRequest?,
): DataState {
    val container = ContainerContext.value
    val pageBlock = PageBlockContext.value
    val templateVariable = container.rememberVariableForTemplate(
        data = null,
        pageProperties = pageBlock.toProperties(),
        arguments = arguments,
    )
    val compiledRequest = remember(container, templateVariable, request) {
        request?.let { container.compileHttpRequest(it, templateVariable) }
    }
    var loading by remember {
        mutableStateOf(compiledRequest != null)
    }
    var responseData: JsonElement? by remember {
        mutableStateOf(null)
    }
    LaunchedEffect(compiledRequest) {
        if (compiledRequest == null) {
            responseData = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        container.sendCompiledHttpRequest(compiledRequest).onSuccess {
            responseData = it
            loading = false
        }.onFailure {
            loading = false
        }
    }
    return DataState(
        loading = loading,
        data = responseData?.let {
            JsonObject(templateVariable.jsonObject + ("data" to it))
        } ?: templateVariable,
    )
}

@Composable
internal fun rememberNestedDataState(
    data: JsonElement,
): DataState {
    val parentData = DataContext.state
    return DataState(
        loading = parentData.loading,
        data = JsonObject(parentData.data.jsonObject + ("data" to data)),
    )
}

@Composable
internal fun NestedDataProvider(
    data: JsonElement,
    content: @Composable() () -> Unit
) {
    val state = rememberNestedDataState(data)
    CompositionLocalProvider(
        LocalData provides state
    ) {
        content()
    }
}

@Composable
internal fun PageDataProvider(
    arguments: Any? = null,
    request: ApiHttpRequest?,
    content: @Composable() () -> Unit
) {
    val state = rememberPageState(arguments, request)
    CompositionLocalProvider(
        LocalData provides state
    ) {
        content()
    }
}
