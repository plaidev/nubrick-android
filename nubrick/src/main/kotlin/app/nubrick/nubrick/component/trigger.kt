package app.nubrick.nubrick.component

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.data.ExperimentContent
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.data.user.getNubrickUserSharedPreferences
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.TriggerEventNameDefs
import app.nubrick.nubrick.schema.UIRootBlock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class LifecycleObserverRegistration(
    private val observer: LifecycleObserver,
) {
    private var lifecycle: Lifecycle? = null

    @Synchronized
    fun attach(lifecycle: Lifecycle) {
        if (this.lifecycle != null) return

        this.lifecycle = lifecycle
        try {
            lifecycle.addObserver(observer)
        } catch (error: Throwable) {
            this.lifecycle = null
            throw error
        }
    }

    @Synchronized
    fun detach() {
        val lifecycle = this.lifecycle ?: return
        this.lifecycle = null
        try {
            lifecycle.removeObserver(observer)
        } catch (error: Throwable) {
            this.lifecycle = lifecycle
            throw error
        }
    }
}

internal class TriggerStateHolder(
    internal val container: Container,
    internal val user: NubrickUser,
    private val scope: CoroutineScope,
    onTooltip: ((data: String, experimentId: String, variantId: String?, sessionId: String) -> Unit)? = null,
) {
    @Volatile
    private var onTooltip: ((data: String, experimentId: String, variantId: String?, sessionId: String) -> Unit)? = onTooltip

    private val experimentSession = TriggerExperimentSession()

    private val isFirstStart = AtomicBoolean(true)
    private val isProcessLifecycleObservationClosed = AtomicBoolean(false)
    @Volatile
    private var triggerContext: Context? = null
    private val processLifecycleObserver = LifecycleEventObserver { _, event ->
        if (!isProcessLifecycleObservationClosed.get() && event == Lifecycle.Event.ON_START) {
            handleProcessStart()
        }
    }
    // The holder is process-scoped, so this registration survives Activity recreation. Re-adding
    // an observer while the process is already started would synchronously replay ON_START.
    private val processLifecycleRegistration = LifecycleObserverRegistration(processLifecycleObserver)
    internal val modalContents = mutableStateListOf<ExperimentContent>()

    fun updateOnTooltip(onTooltip: ((data: String, experimentId: String, variantId: String?, sessionId: String) -> Unit)?) {
        this.onTooltip = onTooltip
    }

    internal fun ignoreFirstCall(): Boolean {
        return isFirstStart.compareAndSet(true, false)
    }

    internal fun startObservingProcessLifecycle(context: Context) {
        if (isProcessLifecycleObservationClosed.get()) return

        triggerContext = context.applicationContext
        runCatching {
            processLifecycleRegistration.attach(ProcessLifecycleOwner.get().lifecycle)
        }.onFailure { error ->
            Log.w("NubrickSDK", "Could not observe the process lifecycle for trigger events", error)
        }
    }

    internal suspend fun stopObservingProcessLifecycle() {
        withContext(Dispatchers.Main.immediate) {
            isProcessLifecycleObservationClosed.set(true)
            triggerContext = null
            runCatching {
                processLifecycleRegistration.detach()
            }.onFailure { error ->
                Log.w("NubrickSDK", "Could not remove the process lifecycle observer for trigger events", error)
            }
        }
    }

    private fun handleProcessStart() {
        val context = triggerContext ?: return
        val events = mutableListOf<NubrickEvent>()
        if (ignoreFirstCall()) {
            events += NubrickEvent(TriggerEventNameDefs.USER_BOOT_APP.name)

            val preferences = getNubrickUserSharedPreferences(context)
            val countKey = "NATIVEBRIK_SDK_INITIALIZED_COUNT"
            val count: Int = preferences?.getInt(countKey, 0) ?: 0
            preferences?.edit()?.putInt(countKey, count + 1)?.apply()
            if (count == 0) {
                events += NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_APP_FIRSTLY.name)
            }
        } else {
            events += NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_FOREGROUND.name)
        }
        events += userReturnEvents()
        dispatchPredefinedEvents(events)
    }

    private fun userReturnEvents(): List<NubrickEvent> {
        this.user.comeBack()
        val events = mutableListOf(NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_APP.name))

        val retention = this.user.retention
        if (retention == 1) {
            events += NubrickEvent(TriggerEventNameDefs.RETENTION_1.name)
        } else if (retention in 2..3) {
            events += NubrickEvent(TriggerEventNameDefs.RETENTION_2_3.name)
        } else if (retention in 4..7) {
            events += NubrickEvent(TriggerEventNameDefs.RETENTION_4_7.name)
        } else if (retention in 8..14) {
            events += NubrickEvent(TriggerEventNameDefs.RETENTION_8_14.name)
        } else if (retention > 14) {
            events += NubrickEvent(TriggerEventNameDefs.RETENTION_15.name)
        }
        return events
    }

    fun dispatch(event: NubrickEvent, sourceExperimentId: String? = null) {
        val self = this
        // onTooltip is only set in the Flutter SDK. Tooltips are a Flutter-only feature,
        // so we fetch both popups and tooltips when running in Flutter, and popups only otherwise.
        val kinds: List<ExperimentKind> = if (self.onTooltip != null) {
            listOf(ExperimentKind.POPUP, ExperimentKind.TOOLTIP)
        } else {
            listOf(ExperimentKind.POPUP)
        }
        // scope runs on Dispatchers.IO, provided by NubrickRuntime
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    self.container.handleNubrickEvent(event)
                }
                val recorded = self.container.recordTriggerEvent(event, sourceExperimentId)
                if (!recorded || experimentSession.isActive()) {
                    return@launch
                }
                val (content, kind) = self.container.fetchTriggerContent(event.name, kinds).getOrNull()
                    ?: return@launch
                self.presentTriggerContent(content, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Public boundary: never let trigger/dispatch failures crash the host app.
                Log.w("NubrickSDK", "Failed to dispatch trigger event: ${event.name}", e)
            }
        }
    }

    private fun dispatchPredefinedEvents(events: List<NubrickEvent>) {
        val self = this
        val kinds: List<ExperimentKind> = if (self.onTooltip != null) {
            listOf(ExperimentKind.POPUP, ExperimentKind.TOOLTIP)
        } else {
            listOf(ExperimentKind.POPUP)
        }
        scope.launch {
            try {
                for (event in events) {
                    try {
                        withContext(Dispatchers.Main) {
                            self.container.handleNubrickEvent(event)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w("NubrickSDK", "Failed to dispatch predefined trigger event: ${event.name}", e)
                    }
                }
                val recordedTriggers = mutableListOf<String>()
                for (event in events) {
                    if (self.container.recordTriggerEvent(event)) {
                        recordedTriggers += event.name
                    }
                }
                if (recordedTriggers.isEmpty() || experimentSession.isActive()) {
                    return@launch
                }
                val (content, kind) = self.container.fetchTriggerContent(
                    triggers = recordedTriggers,
                    kinds = kinds,
                ).getOrNull() ?: return@launch
                self.presentTriggerContent(content, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("NubrickSDK", "Failed to dispatch predefined trigger events", e)
            }
        }
    }

    private suspend fun presentTriggerContent(content: ExperimentContent, kind: ExperimentKind) {
        // Fetches may complete in any order. Recheck on the UI thread immediately
        // before presentation so only one completed popup starts a modal flow.
        withContext(Dispatchers.Main) {
            if (kind == ExperimentKind.TOOLTIP) {
                val callback = onTooltip ?: return@withContext
                val sessionId = UUID.randomUUID().toString()
                if (!startExperiment(sessionId)) return@withContext
                try {
                    callback(Json.encodeToString(UIRootBlock.encode(content.root)), content.experimentId, content.variantId, sessionId)
                } catch (error: Throwable) {
                    finishExperiment(sessionId)
                    throw error
                }
            } else {
                val sessionId = UUID.randomUUID().toString()
                if (!startExperiment(sessionId)) return@withContext
                try {
                    modalContents.add(ExperimentContent(
                        experimentId = content.experimentId,
                        variantId = content.variantId,
                        root = content.root,
                        sessionId = sessionId,
                    ))
                } catch (error: Throwable) {
                    finishExperiment(sessionId)
                    throw error
                }
            }
        }
    }

    // Called by native dispatch before notifying Flutter, never by Flutter.
    fun startExperiment(sessionId: String): Boolean = experimentSession.start(sessionId)

    fun ownsExperiment(sessionId: String): Boolean = experimentSession.owns(sessionId)

    fun finishExperiment(sessionId: String) {
        experimentSession.finish(sessionId)
    }

    fun recordDisplay(sessionId: String, experimentId: String, variantId: String) {
        if (experimentId.isEmpty() || variantId.isEmpty()) return
        if (!experimentSession.beginRecording(sessionId)) return
        // Register completion even if the scope was already cancelled before launch.
        scope.launch {
            try {
                container.recordDisplayedTriggerContent(experimentId, variantId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("NubrickSDK", "Failed to record displayed trigger content", e)
            }
        }.invokeOnCompletion {
            experimentSession.finishRecording(sessionId)
        }
    }

    fun handleShown(content: ExperimentContent) {
        if (modalContents.singleOrNull() !== content) return
        val sessionId = content.sessionId ?: return
        val variantId = content.variantId ?: return
        recordDisplay(sessionId, content.experimentId, variantId)
    }

    fun handleDismiss(content: ExperimentContent) {
        if (modalContents.remove(content)) {
            content.sessionId?.let(::finishExperiment)
        }
    }

}

@Composable
internal fun Trigger(trigger: TriggerStateHolder) {
    val context = LocalContext.current

    LaunchedEffect(trigger) {
        // Deliberately not tied to this composition's disposal; NubrickRuntime closes it.
        trigger.startObservingProcessLifecycle(context)
    }

    if (trigger.modalContents.isNotEmpty()) {
        for (content in trigger.modalContents) {
            key(content.sessionId) {
                DisposableEffect(trigger, content) {
                    onDispose { trigger.handleDismiss(content) }
                }
                Root(
                    container = trigger.container,
                    modifier = Modifier.fillMaxSize(),
                    root = content.root,
                    sessionId = content.sessionId,
                    experimentId = content.experimentId,
                    variantId = content.variantId,
                    embeddingVisibility = false,
                    onShown = { trigger.handleShown(content) },
                    onDismiss = {
                        trigger.handleDismiss(content)
                    }
                )
            }
        }
    }
}
