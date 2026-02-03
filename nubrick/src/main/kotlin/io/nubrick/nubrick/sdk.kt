package io.nubrick.nubrick

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
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
import kotlinx.serialization.encodeToString
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

const val VERSION = BuildConfig.VERSION_NAME
private const val SIZE_LOG_TAG = "NubrickSize"

data class Endpoint(
    val cdn: String = "https://cdn.nativebrik.com",
    val track: String = "https://track.nativebrik.com/track/v1",
)

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
    val endpoint: Endpoint = Endpoint(),
    val onEvent: ((event: Event) -> Unit)? = null,
    val cachePolicy: CachePolicy = CachePolicy(),
    val onDispatch: ((event: NubrickEvent) -> Unit)? = null,
    val trackCrashes : Boolean = true,
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

internal var LocalNubrickClient = compositionLocalOf<NubrickClient> {
    error("NubrickClient is not found")
}

object Nubrick {
    /**
     * Retrieves the current [NubrickClient] at the call site's position in the hierarchy.
     */
    val client: NubrickClient
        @Composable
        @ReadOnlyComposable
        get() = LocalNubrickClient.current
}

@Composable
fun NubrickProvider(
    client: NubrickClient,
    content: @Composable() () -> Unit
) {
    CompositionLocalProvider(
        LocalNubrickClient provides client
    ) {
        NubrickTheme {
            client.experiment.Overlay()
        }
        content()
    }
}


class NubrickClient {
    private val config: Config
    private val db: SQLiteDatabase
    val user: NubrickUser
    val experiment: NubrickExperiment

    constructor(config: Config, context: Context) {
        this.config = config
        this.user = NubrickUser(context)
        val helper = NubrickDbHelper(context)
        this.db = helper.writableDatabase
        this.experiment = NubrickExperiment(
            config = this.config,
            user = this.user,
            db = this.db,
            context = context,
        )

        if (this.config.trackCrashes) {
            val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    this.experiment.storeNativeCrash(throwable)
                } finally {
                    existingHandler?.uncaughtException(thread, throwable)
                }
            }
        }
    }

    fun close() {
        this.db.close()
    }
}

class NubrickExperiment {
    internal val container: Container
    private val trigger: TriggerViewModel

    internal constructor(
        config: Config,
        user: NubrickUser,
        db: SQLiteDatabase,
        context: Context
    ) {
        this.container = ContainerImpl(
            config = config.copy(onEvent = { event ->
                val name = event.name ?: ""
                if (name.isNotEmpty()) {
                    this.dispatch(NubrickEvent(name))
                }
                config.onEvent?.let { it(event) }
            }),
            user = user,
            db = db,
            formRepository = FormRepositoryImpl(),
            cache = CacheStore(config.cachePolicy),
            context = context,
        )
        this.trigger = TriggerViewModel(this.container, user)
    }

    fun dispatch(event: NubrickEvent) {
        this.trigger.dispatch(event)
    }

    fun storeNativeCrash(throwable: Throwable) {
        this.container.storeNativeCrash(throwable)
    }

    /**
     * Reports a crash event
     *
     * @param crashEvent The crash event containing exceptions, platform, and SDK version
     */
    @FlutterBridgeApi
    fun sendFlutterCrash(crashEvent: TrackCrashEvent) {
        this.container.sendFlutterCrash(crashEvent)
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
                id,
                modifier = modifier,
                onEvent = onEvent,
                content = content
            )
        }
    }

    @Composable
    fun RemoteConfig(id: String, content: @Composable (RemoteConfigLoadingState) -> Unit) {
        return io.nubrick.nubrick.remoteconfig.RemoteConfigView(
            container = this.container,
            experimentId = id,
            content = content
        )
    }

    // This is for flutter SDK
    fun remoteConfig(id: String): io.nubrick.nubrick.remoteconfig.RemoteConfig {
        return io.nubrick.nubrick.remoteconfig.RemoteConfig(
            container = this.container,
            experimentId = id,
        )
    }
}

/**
 * This is for flutter SDK
 */
@FlutterBridgeApi
class __DO_NOT_USE_THIS_INTERNAL_BRIDGE(private val client: NubrickClient) {
    suspend fun connectEmbedding(experimentId: String, componentId: String?): Result<Any?> {
        return client.experiment.container.fetchEmbedding(experimentId, componentId)
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

    suspend fun connectTooltip(trigger: String): Result<String?> {
        return client.experiment.container.fetchTooltip(trigger).mapCatching { it ->
            it.let { Json.encodeToString(UIBlock.encode(it)) }
        }
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
        var width: Int? by remember(data) { mutableStateOf(null) }
        var height: Int? by remember(data) { mutableStateOf(null) }
        val container = remember(arguments) {
            client.experiment.container.initWith(arguments)
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
