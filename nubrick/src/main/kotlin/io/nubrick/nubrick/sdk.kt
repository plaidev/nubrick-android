package io.nubrick.nubrick

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import io.nubrick.nubrick.component.Embedding
import io.nubrick.nubrick.component.EmbeddingLoadingState
import io.nubrick.nubrick.component.Root
import io.nubrick.nubrick.component.Trigger
import io.nubrick.nubrick.component.TriggerViewModel
import io.nubrick.nubrick.component.NubrickTheme
import io.nubrick.nubrick.component.bridge.UIBlockEventBridgeViewModel
import io.nubrick.nubrick.data.CacheStore
import io.nubrick.nubrick.data.Container
import io.nubrick.nubrick.data.ContainerImpl
import io.nubrick.nubrick.data.FormRepositoryImpl
import io.nubrick.nubrick.data.TrackCrashEvent
import io.nubrick.nubrick.data.database.NubrickDbHelper
import io.nubrick.nubrick.data.user.NubrickUser
import io.nubrick.nubrick.remoteconfig.RemoteConfigLoadingState
import io.nubrick.nubrick.schema.UIBlock
import io.nubrick.nubrick.schema.UIRootBlock
import io.nubrick.nubrick.schema.UIPageBlock
import io.nubrick.nubrick.schema.PageKind
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@RequiresOptIn(
    message = "This API is internal to the Flutter bridge and should not be used directly.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class FlutterBridgeApi

enum class EventPropertyType {
    INTEGER,
    STRING,
    TIMESTAMPZ,
    UNKNOWN
}

data class EventProperty(
    val name: String,
    val value: String,
    val type: EventPropertyType
)

data class Event(
    val name: String?,
    val deepLink: String?,
    val payload: List<EventProperty>?
)

data class Config(
    val projectId: String,
    val onEvent: ((event: Event) -> Unit)? = null,
    val cachePolicy: CachePolicy = CachePolicy(),
    val onDispatch: ((event: NubrickEvent) -> Unit)? = null,
    val trackCrashes: Boolean = true,
)

enum class CacheStorage {
    IN_MEMORY
}

data class CachePolicy(
    val cacheTime: Duration = 24.toDuration(DurationUnit.HOURS),
    val staleTime: Duration = Duration.ZERO,
    val storage: CacheStorage = CacheStorage.IN_MEMORY,
)

data class NubrickEvent(
    val name: String
)

private class NubrickUninitializedException : IllegalStateException(
    "NubrickSDK used before NubrickSDK.initialize(...)."
)

private class NubrickRuntime(
    config: Config,
    context: Context,
    onTooltip: ((data: String, experimentId: String) -> Unit)? = null,
) {
    private val user: NubrickUser
    private val db: SQLiteDatabase
    private val defaultExceptionHandler: Thread.UncaughtExceptionHandler?
    private val installedExceptionHandler: Thread.UncaughtExceptionHandler?
    private val trigger: TriggerViewModel
    val container: Container

    init {
        val appContext = context.applicationContext
        this.user = NubrickUser(appContext)
        this.db = NubrickDbHelper(appContext).writableDatabase
        this.container = ContainerImpl(
            config = config.copy(onEvent = { event ->
                val name = event.name ?: ""
                if (name.isNotEmpty()) {
                    this.dispatch(NubrickEvent(name))
                }
                config.onEvent?.let { it(event) }
            }),
            user = this.user,
            db = this.db,
            formRepository = FormRepositoryImpl(),
            cache = CacheStore(config.cachePolicy),
            context = appContext,
        )
        this.trigger = TriggerViewModel(this.container, this.user, onTooltip)

        if (config.trackCrashes) {
            val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
            val crashHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    this.storeNativeCrash(throwable)
                } finally {
                    existingHandler?.uncaughtException(thread, throwable)
                }
            }
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
            this.defaultExceptionHandler = existingHandler
            this.installedExceptionHandler = crashHandler
        } else {
            this.defaultExceptionHandler = null
            this.installedExceptionHandler = null
        }
    }

    fun close() {
        if (this.installedExceptionHandler != null &&
            Thread.getDefaultUncaughtExceptionHandler() === this.installedExceptionHandler
        ) {
            Thread.setDefaultUncaughtExceptionHandler(this.defaultExceptionHandler)
        }
        this.db.close()
    }

    fun dispatch(event: NubrickEvent) {
        this.trigger.dispatch(event)
    }

    fun storeNativeCrash(throwable: Throwable) {
        this.container.storeNativeCrash(throwable)
    }

    fun sendFlutterCrash(crashEvent: TrackCrashEvent) {
        this.container.sendFlutterCrash(crashEvent)
    }

    fun appendTooltipExperimentHistory(experimentId: String) {
        if (experimentId.isEmpty()) return
        this.container.appendExperimentHistory(experimentId)
    }

    fun updateOnTooltip(onTooltip: ((data: String, experimentId: String) -> Unit)?) {
        this.trigger.updateOnTooltip(onTooltip)
    }

    fun setUserId(id: String) {
        this.user.setUserId(id)
    }

    fun getUserId(): String? {
        return this.user.getProperty("userId")
    }

    fun setUserProperty(key: String, value: Any) {
        this.user.setProperty(key, value)
    }

    fun getUserProperty(key: String): String? {
        return this.user.getProperty(key)
    }

    fun setUserProperties(props: Map<String, Any>) {
        this.user.setProperties(props)
    }

    fun getUserProperties(): Map<String, String> {
        return this.user.getProperties().toMap()
    }

    @Composable
    fun Overlay() {
        Trigger(trigger = this.trigger)
    }

    @Composable
    fun Embedding(
        id: String,
        modifier: Modifier = Modifier,
        arguments: Any? = null,
        onEvent: ((event: Event) -> Unit)? = null,
        content: (@Composable() (state: EmbeddingLoadingState) -> Unit)? = null
    ) {
        NubrickTheme {
            Embedding(
                container = this.container.initWith(arguments),
                experimentId = id,
                modifier = modifier,
                onEvent = onEvent,
                content = content
            )
        }
    }

    @Composable
    fun RemoteConfig(
        id: String,
        content: @Composable (RemoteConfigLoadingState) -> Unit
    ) {
        return io.nubrick.nubrick.remoteconfig.RemoteConfigView(
            container = this.container,
            experimentId = id,
            content = content
        )
    }

    fun remoteConfig(id: String): io.nubrick.nubrick.remoteconfig.RemoteConfig {
        return io.nubrick.nubrick.remoteconfig.RemoteConfig(
            container = this.container,
            experimentId = id,
        )
    }
}

object NubrickSDK {
    @Volatile
    private var runtime: NubrickRuntime? = null

    private fun warn(message: String) {
        Log.w("NubrickSDK", message)
    }

    private fun runtimeOrNull(throwInDebug: Boolean): NubrickRuntime? {
        val current = runtime
        if (current != null) {
            return current
        }
        warn("NubrickSDK used before NubrickSDK.initialize(...).")
        if (throwInDebug && BuildConfig.DEBUG) {
            throw NubrickUninitializedException()
        }
        return null
    }

    @Synchronized
    private fun initializeInternal(
        context: Context,
        config: Config,
        onTooltip: ((data: String, experimentId: String) -> Unit)?
    ) {
        val currentRuntime = runtime
        if (currentRuntime != null) {
            warn("NubrickSDK.initialize(...) called more than once. Subsequent calls are ignored.")
            currentRuntime.updateOnTooltip(onTooltip)
            return
        }
        runtime = NubrickRuntime(
            config = config,
            context = context,
            onTooltip = onTooltip
        )
    }

    @Synchronized
    fun initialize(
        context: Context,
        config: Config
    ) {
        initializeInternal(context = context, config = config, onTooltip = null)
    }

    @Synchronized
    @FlutterBridgeApi
    fun initialize(
        context: Context,
        config: Config,
        onTooltip: ((data: String, experimentId: String) -> Unit)?
    ) {
        initializeInternal(context = context, config = config, onTooltip = onTooltip)
    }

    @Synchronized
    internal fun resetForTest() {
        runtime?.close()
        runtime = null
    }

    fun dispatch(event: NubrickEvent) {
        val current = runtimeOrNull(throwInDebug = true) ?: return
        current.dispatch(event)
    }

    @FlutterBridgeApi
    fun sendFlutterCrash(crashEvent: TrackCrashEvent) {
        val current = runtimeOrNull(throwInDebug = true) ?: return
        current.sendFlutterCrash(crashEvent)
    }

    @FlutterBridgeApi
    fun appendTooltipExperimentHistory(experimentId: String) {
        val current = runtimeOrNull(throwInDebug = true) ?: return
        current.appendTooltipExperimentHistory(experimentId)
    }

    fun setUserId(id: String) {
        val current = runtimeOrNull(throwInDebug = false) ?: return
        current.setUserId(id)
    }

    fun getUserId(): String? {
        val current = runtimeOrNull(throwInDebug = false) ?: return null
        return current.getUserId()
    }

    fun setUserProperty(key: String, value: Any) {
        val current = runtimeOrNull(throwInDebug = false) ?: return
        current.setUserProperty(key, value)
    }

    fun getUserProperty(key: String): String? {
        val current = runtimeOrNull(throwInDebug = false) ?: return null
        return current.getUserProperty(key)
    }

    fun setUserProperties(props: Map<String, Any>) {
        val current = runtimeOrNull(throwInDebug = false) ?: return
        current.setUserProperties(props)
    }

    fun getUserProperties(): Map<String, String> {
        val current = runtimeOrNull(throwInDebug = false) ?: return emptyMap()
        return current.getUserProperties()
    }

    @Composable
    fun Embedding(
        id: String,
        modifier: Modifier = Modifier,
        arguments: Any? = null,
        onEvent: ((event: Event) -> Unit)? = null,
        content: (@Composable() (state: EmbeddingLoadingState) -> Unit)? = null
    ) {
        val current = runtimeOrNull(throwInDebug = true) ?: return
        current.Embedding(
            id = id,
            modifier = modifier,
            arguments = arguments,
            onEvent = onEvent,
            content = content
        )
    }

    @Composable
    fun RemoteConfig(
        id: String,
        content: @Composable (RemoteConfigLoadingState) -> Unit
    ) {
        val current = runtimeOrNull(throwInDebug = false)
        if (current == null) {
            content(RemoteConfigLoadingState.Failed(NubrickUninitializedException()))
            return
        }
        current.RemoteConfig(id = id, content = content)
    }

    fun remoteConfig(id: String): Result<io.nubrick.nubrick.remoteconfig.RemoteConfig> {
        val current = runtimeOrNull(throwInDebug = false)
        if (current == null) {
            return Result.failure(NubrickUninitializedException())
        }
        return Result.success(current.remoteConfig(id))
    }

    @Composable
    internal fun Overlay() {
        val current = runtimeOrNull(throwInDebug = true) ?: return
        current.Overlay()
    }

    internal fun containerOrNull(): Container? {
        return runtime?.container
    }
}

@Composable
fun NubrickProvider(
    content: @Composable() () -> Unit
) {
    NubrickTheme {
        NubrickSDK.Overlay()
    }
    content()
}

/**
 * This is for flutter SDK
 */
@FlutterBridgeApi
object FlutterBridge {
    suspend fun connectEmbedding(experimentId: String, componentId: String?): Result<Any?> {
        val container = NubrickSDK.containerOrNull() ?: return Result.failure(NubrickUninitializedException())
        return container.fetchEmbedding(experimentId, componentId).map { it }
    }

    fun computeInitialSize(embedding: Any?): Pair<Int?, Int?> {
        val root: UIRootBlock? = extractRootBlock(embedding)
        val pages: List<UIPageBlock>? = root?.data?.pages
        val triggerPage = pages?.firstOrNull {
            it.data?.kind == PageKind.TRIGGER
        }
        val triggerDestinationId = triggerPage?.data?.triggerSetting?.onTrigger?.destinationPageId
        val triggerDestinationPage = pages?.firstOrNull {
            it.id == triggerDestinationId
        }
        return Pair(triggerDestinationPage?.data?.frameWidth, triggerDestinationPage?.data?.frameHeight)
    }

    private fun extractRootBlock(data: Any?): UIRootBlock? {
        return when (data) {
            is UIBlock.UnionUIRootBlock -> data.data
            is UIRootBlock -> data
            is String -> UIRootBlock.decode(Json.decodeFromString(data))
            else -> null
        }
    }

    @DelicateCoroutinesApi
    @Composable
    fun render(
        modifier: Modifier = Modifier,
        arguments: Any? = null,
        data: Any?,
        onEvent: ((event: Event) -> Unit),
        onNextTooltip: ((pageId: String) -> Unit) = {},
        onDismiss: (() -> Unit) = {},
        onSizeChange: ((width: Int?, height: Int?) -> Unit)? = null,
        eventBridge: UIBlockEventBridgeViewModel? = null,
    ) {
        val runtime = NubrickSDK.run {
            containerOrNull()
        } ?: return
        var width: Int? by remember(data) { mutableStateOf(null) }
        var height: Int? by remember(data) { mutableStateOf(null) }
        val container = remember(arguments, runtime) {
            runtime.initWith(arguments)
        }
        val widthModifier = width?.takeIf { it != 0 }?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
        val heightModifier = height?.takeIf { it != 0 }?.let { Modifier.height(it.dp) } ?: Modifier.fillMaxHeight()
        val rootBlock: UIRootBlock? = extractRootBlock(data)
        rootBlock?.let {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Root(
                    modifier = widthModifier.then(heightModifier),
                    container = container,
                    root = it,
                    onEvent = onEvent,
                    onNextTooltip = onNextTooltip,
                    onDismiss = { onDismiss() },
                    eventBridge = eventBridge,
                    onSizeChange = { newWidth, newHeight ->
                        width = newWidth
                        height = newHeight
                        onSizeChange?.invoke(newWidth, newHeight)
                    },
                )
            }
        }
    }
}
