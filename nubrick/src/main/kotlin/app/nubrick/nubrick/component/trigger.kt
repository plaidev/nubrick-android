package app.nubrick.nubrick.component

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    onTooltip: ((data: String, experimentId: String, variantId: String?) -> Unit)? = null,
) {
    @Volatile
    private var onTooltip: ((data: String, experimentId: String, variantId: String?) -> Unit)? = onTooltip

    private val hasActiveTriggerExperiment = AtomicBoolean(false)
    private var displayRecordingJob: Job? = null

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

    fun updateOnTooltip(onTooltip: ((data: String, experimentId: String, variantId: String?) -> Unit)?) {
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
                val recorded = self.container.recordTriggerEvent(event.name, sourceExperimentId)
                if (!recorded || hasActiveTriggerExperiment.get()) {
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
                    if (self.container.recordTriggerEvent(event.name)) {
                        recordedTriggers += event.name
                    }
                }
                if (recordedTriggers.isEmpty() || hasActiveTriggerExperiment.get()) {
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
                if (hasActiveTriggerExperiment.get()) return@withContext
                onTooltip?.let { callback ->
                    val jsonString = Json.encodeToString(UIRootBlock.encode(content.root))
                    callback(jsonString, content.experimentId, content.variantId)
                }
            } else {
                if (!hasActiveTriggerExperiment.compareAndSet(false, true)) return@withContext
                try {
                    modalContents.add(content)
                } catch (error: Throwable) {
                    hasActiveTriggerExperiment.set(false)
                    throw error
                }
            }
        }
    }

    fun handleShown(content: ExperimentContent) {
        if (modalContents.singleOrNull() !== content || displayRecordingJob != null) return
        val variantId = content.variantId ?: return
        displayRecordingJob = scope.launch {
            try {
                container.recordDisplayedTriggerContent(content.experimentId, variantId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("NubrickSDK", "Failed to record displayed trigger content", e)
            }
        }
    }

    fun handleDismiss(root: UIRootBlock) {
        if (modalContents.removeIf { it.root === root }) {
            val recordingJob = displayRecordingJob
            displayRecordingJob = null
            if (recordingJob == null) {
                hasActiveTriggerExperiment.set(false)
            } else {
                recordingJob.invokeOnCompletion { hasActiveTriggerExperiment.set(false) }
            }
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
            key(content.root.id) {
                Root(
                    container = trigger.container,
                    modifier = Modifier.fillMaxSize(),
                    root = content.root,
                    experimentId = content.experimentId,
                    variantId = content.variantId,
                    embeddingVisibility = false,
                    onShown = { trigger.handleShown(content) },
                    onDismiss = {
                        trigger.handleDismiss(it)
                    }
                )
            }
        }
    }
}
